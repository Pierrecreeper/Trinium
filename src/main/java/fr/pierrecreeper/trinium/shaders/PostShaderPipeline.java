// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.shaders;

import fr.pierrecreeper.trinium.graphics.GlCommands;
import fr.pierrecreeper.trinium.graphics.RustGlDriver;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import java.nio.*;
import java.util.*;
import static org.lwjgl.opengl.GL43C.*;

/** Original composite/final renderer. Geometry and shadows remain vanilla.
 * Each logical target has a distinct read image and, when written, an alternate
 * image. Only written targets flip, so a pass never samples its own attachment.
 * Raw GL state is restored so the engine's cached state remains valid. */
public final class PostShaderPipeline implements AutoCloseable {
    public record Frame(int counter, float seconds, float delta, int worldTime, int worldDay,
                        float rain, float near, float far, float cameraX, float cameraY, float cameraZ) { }
    private record Program(int id, Map<String, Integer> uniforms, ShaderTargets.Directives targets) { }
    private static final class Images {
        int main, alternate;
        Images(int main, int alternate) { this.main = main; this.alternate = alternate; }
        void flip() { int old = main; main = alternate; alternate = old; }
        void delete() { glDeleteTextures(main); glDeleteTextures(alternate); }
    }
    private final List<Program> programs = new ArrayList<>();
    private Map<Integer, ShaderTargets.Target> layout = Map.of();
    private Map<Integer, Images> images = new LinkedHashMap<>();
    private int vao, vbo, sampler, mipSampler, readFbo, drawFbo, depthColor, depthProgram;
    private int width, height, depthUnit, textureUnits = 1, outputSlots = 1, attachedSlots;
    private boolean depth;
    private State saved;
    public long frames, draws;

    public PostShaderPipeline(List<StandalonePack.Pass> passes) {
        if (passes.isEmpty() || passes.size() > 17) throw new IllegalArgumentException("Invalid shader pass count.");
        var capabilities = GL.getCapabilities();
        if ((!capabilities.OpenGL43 && !capabilities.GL_ARB_program_interface_query)
            || capabilities.glGetProgramInterfaceiv == 0 || capabilities.glGetProgramResourceiv == 0)
            throw new IllegalArgumentException("Trinium standalone shaders require OpenGL 4.3 or GL_ARB_program_interface_query to validate shader outputs safely.");
        // Before allocation/compilation, calculate every texture unit and indexed
        // draw-buffer state we might touch, including the optional depth unit.
        Set<Integer> sampled = new TreeSet<>();
        for (int i = 0; i < passes.size(); i++) {
            var pass = passes.get(i);
            if (pass.targets().presentation() && i != passes.size() - 1)
                throw new IllegalArgumentException("The final shader must be the last program.");
            outputSlots = Math.max(outputSlots, pass.targets().outputs().size());
        }
        if (outputSlots > glGetInteger(GL_MAX_DRAW_BUFFERS) || outputSlots > glGetInteger(GL_MAX_COLOR_ATTACHMENTS))
            throw new IllegalArgumentException("This GPU cannot draw the requested number of shader targets.");
        // Compiling programs does not change texture bindings. A separate small
        // guard protects compilation; the final guard is sized after reflection.
        try (State ignored = new State(1, outputSlots).capture()) {
            try {
                for (var pass : passes) {
                    int id = link(pass.vertex(), pass.fragment(), pass.name());
                    try {
                        Map<String, Integer> uniforms = uniforms(id);
                        depth |= uniforms.containsKey("depthtex0");
                        for (String uniform : uniforms.keySet()) {
                            int index = ShaderTargets.index(uniform); if (index >= 0) sampled.add(index);
                        }
                        validateOutputs(id, pass.targets().outputs().size());
                        programs.add(new Program(id, uniforms, pass.targets()));
                    } catch (RuntimeException | Error error) { glDeleteProgram(id); throw error; }
                }
                layout = ShaderTargets.layout(passes.stream().map(StandalonePack.Pass::targets).toList(), sampled);
                depthUnit = Collections.max(layout.keySet()) + 1;
                textureUnits = depthUnit + (depth ? 1 : 0);
                if (textureUnits > glGetInteger(GL_MAX_TEXTURE_IMAGE_UNITS))
                    throw new IllegalArgumentException("This GPU has too few shader texture units.");
                for (var target : layout.values()) validateFormat(format(target.format()));
                vao = glGenVertexArrays(); vbo = glGenBuffers();
                glBindVertexArray(vao); glBindBuffer(GL_ARRAY_BUFFER, vbo);
                glBufferData(GL_ARRAY_BUFFER, new float[]{-1,-1,0,0, 1,-1,1,0, -1,1,0,1, 1,1,1,1}, GL_STATIC_DRAW);
                glEnableVertexAttribArray(0); glVertexAttribPointer(0, 2, GL_FLOAT, false, 16, 0L);
                glEnableVertexAttribArray(1); glVertexAttribPointer(1, 2, GL_FLOAT, false, 16, 8L);
                sampler = sampler(false); mipSampler = sampler(true);
                readFbo = glGenFramebuffers(); drawFbo = glGenFramebuffers();
                if (depth) depthProgram = link("#version 330 core\nlayout(location=0) in vec2 p;layout(location=1) in vec2 t;out vec2 uv;void main(){gl_Position=vec4(p,0,1);uv=t;}",
                    "#version 330 core\nuniform sampler2D sceneDepth;in vec2 uv;out float color;void main(){color=1.0-texelFetch(sceneDepth,ivec2(gl_FragCoord.xy),0).r;}", "depth conversion");
            } catch (RuntimeException | Error error) { close(); throw error; }
        }
        saved = new State(textureUnits, outputSlots);
    }

    private static int sampler(boolean mipmaps) {
        int id = glGenSamplers();
        glSamplerParameteri(id, GL_TEXTURE_MIN_FILTER, mipmaps ? GL_LINEAR_MIPMAP_LINEAR : GL_LINEAR);
        glSamplerParameteri(id, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glSamplerParameteri(id, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glSamplerParameteri(id, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glSamplerParameteri(id, GL_TEXTURE_COMPARE_MODE, GL_NONE);
        return id;
    }
    private static int shader(int type, String source, String label) {
        int id = glCreateShader(type);
        try {
            glShaderSource(id, source); glCompileShader(id);
            if (glGetShaderi(id, GL_COMPILE_STATUS) == GL_FALSE) throw new IllegalArgumentException(label + ": " + glGetShaderInfoLog(id, 2048));
            return id;
        } catch (RuntimeException | Error error) { glDeleteShader(id); throw error; }
    }
    private static int link(String vertex, String fragment, String label) {
        int vs = shader(GL_VERTEX_SHADER, vertex, label + " vertex"), fs = 0, program = 0;
        try {
            fs = shader(GL_FRAGMENT_SHADER, fragment, label + " fragment");
            program = glCreateProgram(); glAttachShader(program, vs); glAttachShader(program, fs); glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) throw new IllegalArgumentException(label + ": " + glGetProgramInfoLog(program, 2048));
            return program;
        } catch (RuntimeException | Error error) { if (program != 0) glDeleteProgram(program); throw error; }
        finally { glDeleteShader(vs); if (fs != 0) glDeleteShader(fs); }
    }
    private static void validateOutputs(int program, int slots) {
        int count = glGetProgramInterfacei(program, GL_PROGRAM_OUTPUT, GL_ACTIVE_RESOURCES);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer properties = stack.ints(GL_LOCATION, GL_TYPE, GL_ARRAY_SIZE), values = stack.mallocInt(3);
            for (int i = 0; i < count; i++) {
                glGetProgramResourceiv(program, GL_PROGRAM_OUTPUT, i, properties, null, values);
                int location = values.get(0), type = values.get(1);
                if (location < 0 || location >= slots || values.get(2) != 1
                    || (type != GL_FLOAT && type != GL_FLOAT_VEC2 && type != GL_FLOAT_VEC3 && type != GL_FLOAT_VEC4))
                    throw new IllegalArgumentException("Unsupported shader fragment output location or type.");
            }
        }
    }
    private static Map<String, Integer> uniforms(int program) {
        LinkedHashMap<String, Integer> locations = new LinkedHashMap<>();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer size = stack.mallocInt(1), type = stack.mallocInt(1);
            for (int i = 0; i < glGetProgrami(program, GL_ACTIVE_UNIFORMS); i++) {
                String name = glGetActiveUniform(program, i, size, type);
                int expected = ShaderTargets.index(name) >= 0 ? GL_SAMPLER_2D : switch (name) {
                    case "depthtex0" -> GL_SAMPLER_2D;
                    case "frameCounter", "worldTime", "worldDay" -> GL_INT;
                    case "viewWidth", "viewHeight", "aspectRatio", "frameTimeCounter", "frameTime", "rainStrength", "near", "far" -> GL_FLOAT;
                    case "cameraPosition" -> GL_FLOAT_VEC3;
                    default -> throw new IllegalArgumentException("Unsupported shader input: " + name + ". This pack needs additional rendering stages.");
                };
                if (size.get(0) != 1 || type.get(0) != expected) throw new IllegalArgumentException("Unsupported shader input type: " + name);
                locations.put(name, glGetUniformLocation(program, name));
            }
        }
        return Collections.unmodifiableMap(locations);
    }

    /** Validate actual image allocation before replacing a working pipeline. */
    public void prepare(int width, int height) {
        if (vao == 0) throw new IllegalStateException("Shader pipeline is closed.");
        try (State ignored = saved.capture()) { writeState(); resize(width, height); }
    }

    /** Seed colortex0 with the finished vanilla scene; auxiliary targets are
     * composite data/history, never fabricated geometry normals or materials. */
    public void render(int mainColor, int mainDepth, int width, int height, Frame frame) {
        if (vao == 0) throw new IllegalStateException("Shader pipeline is closed.");
        if (width <= 0 || height <= 0 || mainColor == 0 || (depth && mainDepth == 0)) return;
        try (State ignored = saved.capture()) {
            writeState(); resize(width, height);
            glViewport(0, 0, width, height); glBindVertexArray(vao);
            for (var entry : layout.entrySet()) if (entry.getValue().clear()) {
                Images pair = images.get(entry.getKey());
                if (entry.getKey() != 0) clear(pair.main, entry.getValue().clearColor());
                if (pair.alternate != 0) clear(pair.alternate, entry.getValue().clearColor());
            }
            attachRead(mainColor); attachDraw(images.get(0).main);
            glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);
            if (depth) {
                attachDraw(depthColor); glActiveTexture(GL_TEXTURE0 + depthUnit);
                glBindTexture(GL_TEXTURE_2D, mainDepth); glBindSampler(depthUnit, sampler);
                glUseProgram(depthProgram); glUniform1i(glGetUniformLocation(depthProgram, "sceneDepth"), depthUnit); draw();
            }
            boolean presented = false;
            for (Program program : programs) {
                for (var entry : images.entrySet()) {
                    int index = entry.getKey(); glActiveTexture(GL_TEXTURE0 + index); glBindTexture(GL_TEXTURE_2D, entry.getValue().main);
                    boolean mipmaps = program.targets().mipmaps().contains(index);
                    if (mipmaps) glGenerateMipmap(GL_TEXTURE_2D);
                    glBindSampler(index, mipmaps ? mipSampler : sampler);
                }
                if (depth) { glActiveTexture(GL_TEXTURE0 + depthUnit); glBindTexture(GL_TEXTURE_2D, depthColor); glBindSampler(depthUnit, sampler); }
                if (program.targets().presentation()) { attachDraw(mainColor); presented = true; }
                else {
                    int[] outputs = new int[program.targets().outputs().size()];
                    for (int i = 0; i < outputs.length; i++) outputs[i] = images.get(program.targets().outputs().get(i)).alternate;
                    attachDraw(outputs);
                }
                glUseProgram(program.id()); updateUniforms(program, frame, width, height); draw();
                if (!program.targets().presentation()) for (int index : program.targets().outputs()) images.get(index).flip();
            }
            if (!presented) {
                attachRead(images.get(0).main); attachDraw(mainColor);
                glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);
            }
            frames++;
        }
    }
    private void updateUniforms(Program program, Frame frame, int width, int height) {
        for (var uniform : program.uniforms().entrySet()) {
            int location = uniform.getValue(), index = ShaderTargets.index(uniform.getKey());
            if (index >= 0) { glUniform1i(location, index); continue; }
            switch (uniform.getKey()) {
                case "depthtex0" -> glUniform1i(location, depthUnit);
                case "frameCounter" -> glUniform1i(location, frame.counter());
                case "worldTime" -> glUniform1i(location, frame.worldTime());
                case "worldDay" -> glUniform1i(location, frame.worldDay());
                case "viewWidth" -> glUniform1f(location, width);
                case "viewHeight" -> glUniform1f(location, height);
                case "aspectRatio" -> glUniform1f(location, (float) width / height);
                case "frameTimeCounter" -> glUniform1f(location, frame.seconds());
                case "frameTime" -> glUniform1f(location, frame.delta());
                case "rainStrength" -> glUniform1f(location, frame.rain());
                case "near" -> glUniform1f(location, frame.near());
                case "far" -> glUniform1f(location, frame.far());
                case "cameraPosition" -> glUniform3f(location, frame.cameraX(), frame.cameraY(), frame.cameraZ());
            }
        }
    }
    private void writeState() {
        glDisable(GL_DEPTH_TEST); glDisable(GL_CULL_FACE); glDisable(GL_SCISSOR_TEST);
        glDisable(GL_STENCIL_TEST); glDisable(GL_RASTERIZER_DISCARD); glDisable(GL_COLOR_LOGIC_OP); glDisable(GL_FRAMEBUFFER_SRGB);
        for (int i = 0; i < outputSlots; i++) { glDisablei(GL_BLEND, i); glColorMaski(i, true, true, true, true); }
        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
    }
    private void draw() {
        RustGlDriver.begin();
        try { GlCommands.glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, 1); }
        finally { RustGlDriver.end(); }
        draws++;
    }
    private void attachRead(int texture) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, readFbo);
        glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
        glReadBuffer(GL_COLOR_ATTACHMENT0); complete(GL_READ_FRAMEBUFFER);
    }
    private void attachDraw(int... textures) {
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFbo);
        int[] buffers = new int[textures.length];
        for (int i = 0; i < Math.max(attachedSlots, textures.length); i++) {
            glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0 + i, GL_TEXTURE_2D, i < textures.length ? textures[i] : 0, 0);
            if (i < textures.length) buffers[i] = GL_COLOR_ATTACHMENT0 + i;
        }
        attachedSlots = textures.length; glDrawBuffers(buffers); complete(GL_DRAW_FRAMEBUFFER);
    }
    private static void complete(int binding) {
        if (glCheckFramebufferStatus(binding) != GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("Shader framebuffer is incomplete or its format is unsupported.");
    }
    private void clear(int texture, ShaderTargets.Color color) { attachDraw(texture); glClearBufferfv(GL_COLOR, 0, color.array()); }
    private void resize(int width, int height) {
        if (this.width == width && this.height == height) return;
        int maximum = glGetInteger(GL_MAX_TEXTURE_SIZE);
        if (width <= 0 || height <= 0 || width > maximum || height > maximum)
            throw new IllegalArgumentException("Shader target dimensions exceed this GPU's limit.");
        long bytes;
        try { bytes = ShaderTargets.imageBytes(layout, width, height, depth); }
        catch (ArithmeticException overflow) { throw new IllegalArgumentException("Shader target allocation is too large.", overflow); }
        if (bytes > ShaderTargets.MAX_IMAGE_BYTES)
            throw new IllegalArgumentException("Shader targets exceed the 256 MiB image budget at this resolution.");
        Map<Integer, Images> next = new LinkedHashMap<>(); int nextDepth = 0;
        try {
            for (var entry : layout.entrySet()) {
                var target = entry.getValue(); Images pair = new Images(0, 0); next.put(entry.getKey(), pair);
                pair.main = texture(width, height, format(target.format()), target.mipmapped()); clear(pair.main, target.clearColor());
                if (target.written()) { pair.alternate = texture(width, height, format(target.format()), target.mipmapped()); clear(pair.alternate, target.clearColor()); }
            }
            if (depth) { nextDepth = texture(width, height, GL_R32F, false); attachDraw(nextDepth); }
        } catch (RuntimeException | Error error) {
            next.values().forEach(Images::delete); glDeleteTextures(nextDepth); throw error;
        }
        images.values().forEach(Images::delete); glDeleteTextures(depthColor);
        images = next; depthColor = nextDepth; this.width = width; this.height = height;
    }
    private static int texture(int width, int height, int format, boolean mipmaps) {
        int id = glGenTextures();
        try {
            glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, id); glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
            int level = 0, w = width, h = height;
            do {
                glTexImage2D(GL_TEXTURE_2D, level, format, w, h, 0, GL_RGBA, GL_FLOAT, (ByteBuffer) null);
                int error = glGetError();
                if (error != GL_NO_ERROR || glGetTexLevelParameteri(GL_TEXTURE_2D, level, GL_TEXTURE_WIDTH) != w)
                    throw new IllegalStateException("Shader image allocation failed (OpenGL " + error + ").");
                if (!mipmaps || (w == 1 && h == 1)) break;
                w = Math.max(1, w / 2); h = Math.max(1, h / 2); level++;
            } while (true);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_BASE_LEVEL, 0); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, level);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            return id;
        } catch (RuntimeException | Error error) { glDeleteTextures(id); throw error; }
    }
    private static void validateFormat(int format) {
        if (GL.getCapabilities().OpenGL43
            && (glGetInternalformati(GL_TEXTURE_2D, format, GL_INTERNALFORMAT_SUPPORTED) == GL_FALSE
            || glGetInternalformati(GL_TEXTURE_2D, format, GL_FRAMEBUFFER_RENDERABLE) == GL_NONE))
            throw new IllegalArgumentException("This GPU cannot render a requested shader buffer format.");
        // Every allocation is also checked in an actual framebuffer by resize().
    }
    private static int format(ShaderTargets.Format format) {
        return switch (format) {
            case R8 -> GL_R8; case RG8 -> GL_RG8; case RGB8 -> GL_RGB8; case RGBA8 -> GL_RGBA8;
            case R16F -> GL_R16F; case RG16F -> GL_RG16F; case RGB16F -> GL_RGB16F; case RGBA16F -> GL_RGBA16F;
            case R32F -> GL_R32F; case RG32F -> GL_RG32F; case RGB32F -> GL_RGB32F; case RGBA32F -> GL_RGBA32F;
            case R11F_G11F_B10F -> GL_R11F_G11F_B10F;
            case R8_SNORM -> GL_R8_SNORM; case RG8_SNORM -> GL_RG8_SNORM; case RGB8_SNORM -> GL_RGB8_SNORM; case RGBA8_SNORM -> GL_RGBA8_SNORM;
        };
    }
    @Override public void close() {
        for (Program program : programs) glDeleteProgram(program.id()); programs.clear();
        images.values().forEach(Images::delete); images.clear();
        glDeleteProgram(depthProgram); glDeleteTextures(depthColor);
        glDeleteFramebuffers(readFbo); glDeleteFramebuffers(drawFbo); glDeleteSamplers(sampler); glDeleteSamplers(mipSampler);
        glDeleteVertexArrays(vao); glDeleteBuffers(vbo);
        depthProgram = depthColor = readFbo = drawFbo = sampler = mipSampler = vao = vbo = 0;
    }

    /** Covers all touched texture units, indexed masks, capabilities and bindings. */
    private static final class State implements AutoCloseable {
        int read, draw, program, vao, array, unpack, active;
        final int[] viewport = new int[4], polygon = new int[2], textures, samplers;
        final int[] capabilities = {GL_DEPTH_TEST,GL_CULL_FACE,GL_SCISSOR_TEST,GL_STENCIL_TEST,GL_RASTERIZER_DISCARD,GL_COLOR_LOGIC_OP,GL_FRAMEBUFFER_SRGB};
        final boolean[] enabled = new boolean[capabilities.length], blend;
        final ByteBuffer[] masks;
        State(int units, int slots) {
            textures = new int[units]; samplers = new int[units]; blend = new boolean[slots]; masks = new ByteBuffer[slots];
            for (int i = 0; i < slots; i++) masks[i] = org.lwjgl.BufferUtils.createByteBuffer(4);
        }
        State capture() {
            read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING); draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
            program = glGetInteger(GL_CURRENT_PROGRAM); vao = glGetInteger(GL_VERTEX_ARRAY_BINDING); array = glGetInteger(GL_ARRAY_BUFFER_BINDING);
            unpack = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING); active = glGetInteger(GL_ACTIVE_TEXTURE);
            glGetIntegerv(GL_POLYGON_MODE, polygon); glGetIntegerv(GL_VIEWPORT, viewport);
            for (int i = 0; i < masks.length; i++) { blend[i] = glIsEnabledi(GL_BLEND, i); glGetBooleani_v(GL_COLOR_WRITEMASK, i, masks[i]); }
            for (int i = 0; i < capabilities.length; i++) enabled[i] = glIsEnabled(capabilities[i]);
            for (int i = 0; i < textures.length; i++) {
                glActiveTexture(GL_TEXTURE0 + i); textures[i] = glGetInteger(GL_TEXTURE_BINDING_2D); samplers[i] = glGetInteger(GL_SAMPLER_BINDING);
            }
            glActiveTexture(active); return this;
        }
        public void close() {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, read); glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw);
            glUseProgram(program); glBindVertexArray(vao); glBindBuffer(GL_ARRAY_BUFFER, array); glBindBuffer(GL_PIXEL_UNPACK_BUFFER, unpack);
            glViewport(viewport[0],viewport[1],viewport[2],viewport[3]); glPolygonMode(GL_FRONT_AND_BACK, polygon[0]);
            for (int i = 0; i < capabilities.length; i++) { if (enabled[i]) glEnable(capabilities[i]); else glDisable(capabilities[i]); }
            for (int i = 0; i < masks.length; i++) {
                if (blend[i]) glEnablei(GL_BLEND, i); else glDisablei(GL_BLEND, i);
                var mask = masks[i]; glColorMaski(i, mask.get(0) != 0, mask.get(1) != 0, mask.get(2) != 0, mask.get(3) != 0);
            }
            for (int i = 0; i < textures.length; i++) { glActiveTexture(GL_TEXTURE0 + i); glBindTexture(GL_TEXTURE_2D, textures[i]); glBindSampler(i, samplers[i]); }
            glActiveTexture(active);
        }
    }
}

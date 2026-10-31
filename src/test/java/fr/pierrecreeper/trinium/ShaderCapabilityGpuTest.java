// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium;

import fr.pierrecreeper.trinium.shaders.PostShaderPipeline;
import fr.pierrecreeper.trinium.shaders.StandalonePack;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.sdl.SDLInit.*;
import static org.lwjgl.sdl.SDLVideo.*;

/** Real SDL/OpenGL 3.3 capability oracle. Run in separate Mesa processes with
 * ARB_program_interface_query enabled and disabled; capabilities are never mocked. */
public final class ShaderCapabilityGpuTest {
    private static final String V = "#version 330 core\nlayout(location=0) in vec2 p;void main(){gl_Position=vec4(p,0,1);}";
    private static final String FLOAT = "#version 330 core\nlayout(location=0) out vec4 color;void main(){color=vec4(.25,.5,.75,1);}";
    private static final String INTEGER = "#version 330 core\nlayout(location=0) out uvec4 color;void main(){color=uvec4(1);}";
    private static int checks;
    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
        checks++;
    }
    private static List<Integer> state() {
        List<Integer> values = new ArrayList<>();
        for (int token : new int[]{GL_CURRENT_PROGRAM,GL_VERTEX_ARRAY_BINDING,GL_ARRAY_BUFFER_BINDING,
            GL_PIXEL_UNPACK_BUFFER_BINDING,GL_READ_FRAMEBUFFER_BINDING,GL_DRAW_FRAMEBUFFER_BINDING,GL_ACTIVE_TEXTURE})
            values.add(glGetInteger(token));
        int active = glGetInteger(GL_ACTIVE_TEXTURE);
        for (int i = 0; i < 3; i++) {
            glActiveTexture(GL_TEXTURE0 + i);
            values.add(glGetInteger(GL_TEXTURE_BINDING_2D));
            values.add(glGetInteger(GL_SAMPLER_BINDING));
        }
        glActiveTexture(active);
        for (int token : new int[]{GL_DEPTH_TEST,GL_CULL_FACE,GL_SCISSOR_TEST,GL_STENCIL_TEST,
            GL_RASTERIZER_DISCARD,GL_COLOR_LOGIC_OP,GL_FRAMEBUFFER_SRGB}) values.add(glIsEnabled(token) ? 1 : 0);
        ByteBuffer mask = BufferUtils.createByteBuffer(4);
        for (int i = 0; i < 2; i++) {
            values.add(glIsEnabledi(GL_BLEND, i) ? 1 : 0);
            glGetBooleani_v(GL_COLOR_WRITEMASK, i, mask);
            for (int c = 0; c < 4; c++) values.add((int) mask.get(c));
        }
        int[] vector = new int[4];
        for (int token : new int[]{GL_VIEWPORT,GL_SCISSOR_BOX}) {
            glGetIntegerv(token, vector); for (int value : vector) values.add(value);
        }
        int[] polygon = new int[2]; glGetIntegerv(GL_POLYGON_MODE, polygon);
        for (int value : polygon) values.add(value);
        return values;
    }
    private static int shader(int type, String source) {
        int shader = glCreateShader(type); glShaderSource(shader, source); glCompileShader(shader);
        check(glGetShaderi(shader, GL_COMPILE_STATUS) == GL_TRUE, "Oracle shader compiles: " + glGetShaderInfoLog(shader));
        return shader;
    }
    public static void main(String[] args) {
        if (args.length != 1 || (!args[0].equals("extension") && !args[0].equals("missing")))
            throw new IllegalArgumentException("Expected extension or missing; run each mode in a separate Mesa process.");
        boolean extension = args[0].equals("extension");
        org.lwjgl.system.Configuration.OPENGL_EXPLICIT_INIT.set(true);
        check(SDL_Init(SDL_INIT_VIDEO), "SDL video initializes");
        SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, 3);
        SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, 3);
        SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK, SDL_GL_CONTEXT_PROFILE_CORE);
        long window = 0, context = 0;
        try {
            window = SDL_CreateWindow("Trinium GL 3.3 capability oracle", 32, 32, SDL_WINDOW_OPENGL | SDL_WINDOW_HIDDEN);
            check(window != 0, "Hidden SDL window exists");
            context = SDL_GL_CreateContext(window); check(context != 0, "OpenGL context exists");
            GL.create((ByteBuffer name) -> SDL_GL_GetProcAddress(name));
            var caps = GL.createCapabilities();
            System.out.println("GPU=" + glGetString(GL_RENDERER) + " GL=" + glGetString(GL_VERSION)
                + " OpenGL43=" + caps.OpenGL43 + " ARB_program_interface_query=" + caps.GL_ARB_program_interface_query);
            check(glGetInteger(GL_MAJOR_VERSION) == 3 && glGetInteger(GL_MINOR_VERSION) == 3 && !caps.OpenGL43,
                "Driver exposes a real 3.3 context, not a newer core context");
            check(caps.GL_ARB_program_interface_query == extension, "Driver extension matches requested test mode");
            if (extension) check(caps.glGetProgramInterfaceiv != 0 && caps.glGetProgramResourceiv != 0,
                "Extension exposes the unsuffixed query entry points used by GL43C");

            int vs = shader(GL_VERTEX_SHADER, V), fs = shader(GL_FRAGMENT_SHADER, FLOAT), program = glCreateProgram();
            glAttachShader(program, vs); glAttachShader(program, fs); glLinkProgram(program);
            check(glGetProgrami(program, GL_LINK_STATUS) == GL_TRUE, "External program links"); glUseProgram(program);
            int texture = glGenTextures(), sampler = glGenSamplers(), vao = glGenVertexArrays(), vbo = glGenBuffers();
            int unpack = glGenBuffers(), read = glGenFramebuffers(), draw = glGenFramebuffers();
            glBindTexture(GL_TEXTURE_2D, texture); glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 2, 2, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            for (int i = 0; i < 3; i++) { glActiveTexture(GL_TEXTURE0 + i); glBindTexture(GL_TEXTURE_2D, texture); glBindSampler(i, sampler); }
            glBindVertexArray(vao); glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, unpack); glBufferData(GL_PIXEL_UNPACK_BUFFER, 256, GL_STATIC_DRAW);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, read); glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw);
            for (int token : new int[]{GL_DEPTH_TEST,GL_CULL_FACE,GL_SCISSOR_TEST,GL_STENCIL_TEST,
                GL_RASTERIZER_DISCARD,GL_COLOR_LOGIC_OP,GL_FRAMEBUFFER_SRGB}) glEnable(token);
            glEnablei(GL_BLEND, 0); glDisablei(GL_BLEND, 1);
            glColorMaski(0, false, true, false, true); glColorMaski(1, true, false, true, false);
            glPolygonMode(GL_FRONT_AND_BACK, GL_LINE); glViewport(3, 5, 7, 9); glScissor(1, 2, 3, 4); glActiveTexture(GL_TEXTURE2);
            check(glGetError() == GL_NO_ERROR, "Dirty external GL state is valid");
            List<Integer> before = state();
            if (extension) {
                try (var valid = new PostShaderPipeline(List.of(new StandalonePack.Pass("final", V, FLOAT)))) {
                    check(state().equals(before), "Float-output constructor preserves external state on GL 3.3 + extension");
                    valid.prepare(7, 5); check(state().equals(before), "Image allocation preserves dirty external state");
                    try (var invalid = new PostShaderPipeline(List.of(new StandalonePack.Pass("final", V, INTEGER)))) {
                        throw new AssertionError("Integer output was accepted on GL 3.3");
                    } catch (IllegalArgumentException expected) {
                        check(expected.getMessage().contains("fragment output"), "Integer output is rejected by actual program reflection");
                    }
                    check(state().equals(before), "Invalid program preserves external state and existing pipeline");
                    valid.prepare(9, 7); check(state().equals(before), "Existing pipeline can still resize after invalid replacement");
                }
            } else {
                for (String fragment : new String[]{FLOAT, INTEGER}) {
                    try (var invalid = new PostShaderPipeline(List.of(new StandalonePack.Pass("final", V, fragment)))) {
                        throw new AssertionError("Pipeline was accepted without output reflection support");
                    } catch (IllegalArgumentException expected) {
                        check(expected.getMessage().contains("require OpenGL 4.3 or GL_ARB_program_interface_query"),
                            "Missing reflection capability produces the explicit English refusal");
                    }
                    check(state().equals(before), "Capability refusal preserves dirty external state");
                }
            }
            check(state().equals(before), "All shader lifecycles preserve external state");
            check(glGetError() == GL_NO_ERROR, "No residual OpenGL error");
            System.out.println("PASS OpenGL 3.3 shader capability " + args[0] + ": " + checks + " checks");
            glUseProgram(0); glDeleteProgram(program); glDeleteShader(vs); glDeleteShader(fs);
            glDeleteTextures(texture); glDeleteSamplers(sampler); glDeleteBuffers(unpack); glDeleteBuffers(vbo);
            glDeleteVertexArrays(vao); glDeleteFramebuffers(read); glDeleteFramebuffers(draw);
        } finally {
            GL.setCapabilities(null);
            if (context != 0) SDL_GL_DestroyContext(context);
            if (window != 0) SDL_DestroyWindow(window);
            SDL_Quit();
        }
    }
}

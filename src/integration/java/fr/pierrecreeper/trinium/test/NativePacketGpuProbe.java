// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.probe;

import fr.pierrecreeper.trinium.NativeBridge;
import fr.pierrecreeper.trinium.graphics.GlCommands;
import fr.pierrecreeper.trinium.graphics.RustGlDriver;
import java.lang.reflect.Method;
import java.nio.*;
import java.nio.file.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL33C.*;

/** TEST ONLY. Call once on the render thread, outside all RustGlDriver scopes.
 * Compares LWJGL, aligned native records and deliberately unaligned native records.
 * The latter exercises the portable decoder of the SAME native library/context.
 * An independent 32x32 framebuffer keeps world/terrain streaming out of this oracle.
 */
public final class NativePacketGpuProbe {
    private static final int SIZE = 32;
    private static final int FUNCTION_BYTES = (GlCommands.FUNCTIONS.length * 8 + 63) & ~63;
    private NativePacketGpuProbe() {}

    private record Pixels(byte[] color, float[] depth) {}

    public static void run(Path output) throws Exception {
        Files.createDirectories(output);
        if (!NativeBridge.available()) throw new AssertionError("Native library is not loaded");
        if (ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN)
            throw new AssertionError("This oracle must exercise the aligned little-endian fast path");
        Method packed = RustGlDriver.class.getDeclaredMethod("executePacked0", ByteBuffer.class, int.class);
        packed.setAccessible(true);
        int oldProgram = glGetInteger(GL_CURRENT_PROGRAM), oldVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int oldArray = glGetInteger(GL_ARRAY_BUFFER_BINDING), oldRenderbuffer = glGetInteger(GL_RENDERBUFFER_BINDING);
        int oldRead = glGetInteger(GL_READ_FRAMEBUFFER_BINDING), oldDraw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int oldDepthFunction = glGetInteger(GL_DEPTH_FUNC), oldPack = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        int oldPackAlignment = glGetInteger(GL_PACK_ALIGNMENT), oldPackRow = glGetInteger(GL_PACK_ROW_LENGTH);
        int oldPackRows = glGetInteger(GL_PACK_SKIP_ROWS), oldPackPixels = glGetInteger(GL_PACK_SKIP_PIXELS);
        boolean oldSwap = glGetBoolean(GL_PACK_SWAP_BYTES), oldLsb = glGetBoolean(GL_PACK_LSB_FIRST);
        boolean oldDepthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
        double oldClearDepth = glGetDouble(GL_DEPTH_CLEAR_VALUE);
        boolean indexedViewports = GL.getCapabilities().OpenGL41 || GL.getCapabilities().GL_ARB_viewport_array;
        int viewportCount = indexedViewports ? glGetInteger(org.lwjgl.opengl.GL41C.GL_MAX_VIEWPORTS) : 1;
        int[][] scissors = new int[viewportCount][4];
        boolean[] scissorEnabled = new boolean[viewportCount];
        for(int i=0;i<viewportCount;i++) {
            if(indexedViewports)glGetIntegeri_v(GL_SCISSOR_BOX,i,scissors[i]);
            else glGetIntegerv(GL_SCISSOR_BOX,scissors[i]);
            scissorEnabled[i] = indexedViewports ? glIsEnabledi(GL_SCISSOR_TEST,i) : glIsEnabled(GL_SCISSOR_TEST);
        }
        boolean oldBlend = glIsEnabledi(GL_BLEND,0);
        boolean[] clipEnabled = new boolean[glGetInteger(GL_MAX_CLIP_DISTANCES)];
        for(int i=0;i<clipEnabled.length;i++)clipEnabled[i]=glIsEnabled(GL_CLIP_DISTANCE0+i);
        int[] viewport = new int[4], polygon = new int[2];
        float[] clearColor = new float[4];
        glGetIntegerv(GL_VIEWPORT, viewport);
        glGetIntegerv(GL_POLYGON_MODE, polygon); glGetFloatv(GL_COLOR_CLEAR_VALUE, clearColor);
        ByteBuffer colorMask = MemoryUtil.memAlloc(4);
        glGetBooleanv(GL_COLOR_WRITEMASK, colorMask);
        int[] capabilities = {GL_DEPTH_TEST, GL_CULL_FACE,
            GL_STENCIL_TEST, GL_DITHER, GL_MULTISAMPLE, GL_FRAMEBUFFER_SRGB, GL_RASTERIZER_DISCARD};
        boolean[] enabled = new boolean[capabilities.length];
        for (int i=0;i<enabled.length;i++) enabled[i] = glIsEnabled(capabilities[i]);
        int program=0, vao=0, indices=0, framebuffer=0, color=0, depth=0;
        ByteBuffer aligned=null, unalignedOwner=null;
        try {
            int existingError = glGetError();
            if (existingError != GL_NO_ERROR) throw new AssertionError("Pre-existing OpenGL error: "+existingError);
            program = program(); vao = glGenVertexArrays(); indices = glGenBuffers();
            framebuffer = glGenFramebuffers(); color = glGenRenderbuffers(); depth = glGenRenderbuffers();
            glBindVertexArray(vao); glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indices);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, new int[]{0,1,2}, GL_STATIC_DRAW);
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glBindRenderbuffer(GL_RENDERBUFFER, color); glRenderbufferStorage(GL_RENDERBUFFER,GL_RGBA8,SIZE,SIZE);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_RENDERBUFFER,color);
            glBindRenderbuffer(GL_RENDERBUFFER, depth); glRenderbufferStorage(GL_RENDERBUFFER,GL_DEPTH_COMPONENT32F,SIZE,SIZE);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_RENDERBUFFER,depth);
            glDrawBuffer(GL_COLOR_ATTACHMENT0); glReadBuffer(GL_COLOR_ATTACHMENT0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
                throw new AssertionError("Native packet test framebuffer is incomplete");
            glViewport(0,0,SIZE,SIZE); glPolygonMode(GL_FRONT_AND_BACK,GL_FILL);
            for (int capability : capabilities) glDisable(capability);
            glDisablei(GL_BLEND,0); glDisable(GL_SCISSOR_TEST);
            for(int i=0;i<clipEnabled.length;i++)glDisable(GL_CLIP_DISTANCE0+i);
            glBindBuffer(GL_PIXEL_PACK_BUFFER,0);
            glPixelStorei(GL_PACK_ALIGNMENT,1); glPixelStorei(GL_PACK_ROW_LENGTH,0);
            glPixelStorei(GL_PACK_SKIP_ROWS,0); glPixelStorei(GL_PACK_SKIP_PIXELS,0);
            glPixelStorei(GL_PACK_SWAP_BYTES,0); glPixelStorei(GL_PACK_LSB_FIRST,0);
            long[][] commands = commands(program,vao);
            int length = FUNCTION_BYTES + commands.length * 64;
            aligned = MemoryUtil.memAlloc(length).order(ByteOrder.LITTLE_ENDIAN);
            unalignedOwner = MemoryUtil.memAlloc(length+1);
            ByteBuffer unaligned = unalignedOwner.position(1).slice().order(ByteOrder.LITTLE_ENDIAN);
            if ((MemoryUtil.memAddress(aligned)&7L)!=0 || (MemoryUtil.memAddress(unaligned)&7L)==0)
                throw new AssertionError("The oracle did not construct distinct alignment paths");
            encode(aligned,commands); encode(unaligned,commands);

            clear(); direct(program,vao); Pixels reference = capture();
            clear(); invoke(packed,aligned,commands.length); Pixels fast = capture();
            clear(); invoke(packed,unaligned,commands.length); Pixels portable = capture();
            clear(); direct(program,vao); Pixels repeated = capture();
            long colorFast=colors(reference,fast), depthFast=depths(reference,fast);
            long colorPortable=colors(reference,portable), depthPortable=depths(reference,portable);
            long colorNoise=colors(reference,repeated), depthNoise=depths(reference,repeated);
            long drawn=0;
            for (float value:reference.depth) if (Float.floatToRawIntBits(value)!=Float.floatToRawIntBits(1f)) drawn++;
            boolean passed = drawn>SIZE*SIZE/8 && colorFast==0 && depthFast==0 && colorPortable==0
                && depthPortable==0 && colorNoise==0 && depthNoise==0;
            String json = "{\"passed\":"+passed+",\"width\":"+SIZE+",\"height\":"+SIZE
                +",\"drawnPixels\":"+drawn+",\"commandsPerNativeBatch\":"+commands.length
                +",\"alignedColorDifferences\":"+colorFast+",\"alignedDepthDifferences\":"+depthFast
                +",\"portableColorDifferences\":"+colorPortable+",\"portableDepthDifferences\":"+depthPortable
                +",\"referenceColorNoise\":"+colorNoise+",\"referenceDepthNoise\":"+depthNoise
                +",\"nativeAvailable\":true,\"exactComparison\":true,\"sameContext\":true}\n";
            Files.writeString(output.resolve("native-packet-gpu.json"),json);
            System.out.println("TRINIUM_NATIVE_PACKET_GPU "+json);
            if (!passed) throw new AssertionError("Native packet GPU parity failed");
        } finally {
            glBindFramebuffer(GL_READ_FRAMEBUFFER,oldRead); glBindFramebuffer(GL_DRAW_FRAMEBUFFER,oldDraw);
            glBindRenderbuffer(GL_RENDERBUFFER,oldRenderbuffer);
            glUseProgram(oldProgram); glBindVertexArray(oldVao); glBindBuffer(GL_ARRAY_BUFFER,oldArray);
            glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);
            for(int i=0;i<viewportCount;i++) {
                int[] box=scissors[i];
                if(indexedViewports) {
                    org.lwjgl.opengl.GL41C.glScissorIndexed(i,box[0],box[1],box[2],box[3]);
                    if(scissorEnabled[i])glEnablei(GL_SCISSOR_TEST,i);else glDisablei(GL_SCISSOR_TEST,i);
                } else {
                    glScissor(box[0],box[1],box[2],box[3]);
                    if(scissorEnabled[i])glEnable(GL_SCISSOR_TEST);else glDisable(GL_SCISSOR_TEST);
                }
            }
            if(oldBlend)glEnablei(GL_BLEND,0);else glDisablei(GL_BLEND,0);
            for(int i=0;i<clipEnabled.length;i++) {if(clipEnabled[i])glEnable(GL_CLIP_DISTANCE0+i);else glDisable(GL_CLIP_DISTANCE0+i);}
            glDepthFunc(oldDepthFunction);
            glDepthMask(oldDepthMask); glColorMaski(0,colorMask.get(0)!=0,colorMask.get(1)!=0,colorMask.get(2)!=0,colorMask.get(3)!=0);
            if(polygon[0]==polygon[1])glPolygonMode(GL_FRONT_AND_BACK,polygon[0]);
            else {glPolygonMode(GL_FRONT,polygon[0]);glPolygonMode(GL_BACK,polygon[1]);}
            glClearColor(clearColor[0],clearColor[1],clearColor[2],clearColor[3]); glClearDepth(oldClearDepth);
            for (int i=0;i<enabled.length;i++) { if(enabled[i])glEnable(capabilities[i]);else glDisable(capabilities[i]); }
            glBindBuffer(GL_PIXEL_PACK_BUFFER,oldPack); glPixelStorei(GL_PACK_ALIGNMENT,oldPackAlignment);
            glPixelStorei(GL_PACK_ROW_LENGTH,oldPackRow); glPixelStorei(GL_PACK_SKIP_ROWS,oldPackRows);
            glPixelStorei(GL_PACK_SKIP_PIXELS,oldPackPixels); glPixelStorei(GL_PACK_SWAP_BYTES,oldSwap?1:0);
            glPixelStorei(GL_PACK_LSB_FIRST,oldLsb?1:0);
            if(framebuffer!=0)glDeleteFramebuffers(framebuffer);
            if(color!=0)glDeleteRenderbuffers(color); if(depth!=0)glDeleteRenderbuffers(depth);
            if(indices!=0)glDeleteBuffers(indices); if(vao!=0)glDeleteVertexArrays(vao); if(program!=0)glDeleteProgram(program);
            MemoryUtil.memFree(colorMask); if(aligned!=null)MemoryUtil.memFree(aligned);
            if(unalignedOwner!=null)MemoryUtil.memFree(unalignedOwner.position(0));
        }
    }

    private static long[][] commands(int program,int vao) {
        return new long[][] {
            {0,program},{1,vao},{2,GL_ARRAY_BUFFER,0},{3,GL_DEPTH_TEST},{4,GL_CULL_FACE},
            {7,GL_LEQUAL},{8,1},{10,0,1,1,1,1},{17,0,0,SIZE,SIZE},{3,GL_SCISSOR_TEST},
            {28,GL_TRIANGLES,0,3,2},{17,5,4,19,21},
            {26,GL_TRIANGLES,3,GL_UNSIGNED_INT,0,1,0},
        };
    }
    private static void direct(int program,int vao) {
        glUseProgram(program); glBindVertexArray(vao); glBindBuffer(GL_ARRAY_BUFFER,0);
        glEnable(GL_DEPTH_TEST); glDisable(GL_CULL_FACE); glDepthFunc(GL_LEQUAL); glDepthMask(true);
        glColorMaski(0,true,true,true,true); glScissor(0,0,SIZE,SIZE); glEnable(GL_SCISSOR_TEST);
        glDrawArraysInstanced(GL_TRIANGLES,0,3,2); glScissor(5,4,19,21);
        glDrawElementsInstancedBaseVertex(GL_TRIANGLES,3,GL_UNSIGNED_INT,0L,1,0);
    }
    private static void encode(ByteBuffer packet,long[][] commands) throws ReflectiveOperationException {
        var caps = GL.getCapabilities();
        for(int i=0;i<GlCommands.FUNCTIONS.length;i++) {
            long address=caps.getClass().getField(GlCommands.FUNCTIONS[i]).getLong(caps);
            packet.putLong(i*8,address);
        }
        for(int i=0;i<commands.length;i++) {
            for(int j=0;j<8;j++) packet.putLong(FUNCTION_BYTES+i*64+j*8, j<commands[i].length?commands[i][j]:0xcafebabedeadbeefL);
        }
    }
    private static void invoke(Method method,ByteBuffer packet,int count) throws Exception {
        int result=(Integer)method.invoke(null,packet,count);
        if(result!=0)throw new AssertionError("Native GL batch rejected: "+result);
    }
    private static void clear() {
        glDisable(GL_SCISSOR_TEST); glDepthMask(true); glColorMaski(0,true,true,true,true);
        glClearColor(0.0625f,0.125f,0.25f,1f); glClearDepth(1d); glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
    }
    private static Pixels capture() {
        ByteBuffer color=MemoryUtil.memAlloc(SIZE*SIZE*4); FloatBuffer depth=MemoryUtil.memAllocFloat(SIZE*SIZE);
        try {
            glReadPixels(0,0,SIZE,SIZE,GL_RGBA,GL_UNSIGNED_BYTE,color);
            glReadPixels(0,0,SIZE,SIZE,GL_DEPTH_COMPONENT,GL_FLOAT,depth);
            int error=glGetError(); if(error!=GL_NO_ERROR)throw new AssertionError("Native GPU probe OpenGL error: "+error);
            byte[] c=new byte[color.remaining()]; color.get(c); float[] d=new float[depth.remaining()]; depth.get(d);
            return new Pixels(c,d);
        } finally {MemoryUtil.memFree(color);MemoryUtil.memFree(depth);}
    }
    private static long colors(Pixels a,Pixels b) {
        long changed=0;for(int i=0;i<a.color.length;i++)if(a.color[i]!=b.color[i])changed++;return changed;
    }
    private static long depths(Pixels a,Pixels b) {
        long changed=0;for(int i=0;i<a.depth.length;i++)if(Float.floatToRawIntBits(a.depth[i])!=Float.floatToRawIntBits(b.depth[i]))changed++;return changed;
    }
    private static int program() {
        int vertex=shader(GL_VERTEX_SHADER,"""
            #version 330 core
            out vec3 tint;
            void main() {
                vec2 positions[3]=vec2[](vec2(-0.90,-0.85),vec2(0.75,-0.70),vec2(-0.10,0.90));
                float instance=float(gl_InstanceID);
                gl_Position=vec4(positions[gl_VertexID]+vec2(instance*0.15,instance*0.04),0.40-instance*0.70,1.0);
                tint=vec3(0.20+instance*0.60,0.75-instance*0.45,0.40);
            }
            """);
        int fragment=0, program=0;
        try {
            fragment=shader(GL_FRAGMENT_SHADER,"""
                #version 330 core
                in vec3 tint;
                out vec4 color;
                void main() { color=vec4(tint,1.0); }
                """);
            program=glCreateProgram();glAttachShader(program,vertex);glAttachShader(program,fragment);glLinkProgram(program);
            if(glGetProgrami(program,GL_LINK_STATUS)==GL_FALSE)throw new AssertionError(glGetProgramInfoLog(program));
            int result=program;program=0;return result;
        } finally {glDeleteShader(vertex);if(fragment!=0)glDeleteShader(fragment);if(program!=0)glDeleteProgram(program);}
    }
    private static int shader(int type,String source) {
        int shader=glCreateShader(type);glShaderSource(shader,source);glCompileShader(shader);
        if(glGetShaderi(shader,GL_COMPILE_STATUS)==GL_FALSE) {
            String log=glGetShaderInfoLog(shader);glDeleteShader(shader);throw new AssertionError(log);
        }
        return shader;
    }
}

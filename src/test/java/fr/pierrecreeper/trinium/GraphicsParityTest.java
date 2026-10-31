package fr.pierrecreeper.trinium;

import fr.pierrecreeper.trinium.graphics.*;
import java.nio.*;
import java.util.*;
import org.lwjgl.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.sdl.SDLInit.*;
import static org.lwjgl.sdl.SDLVideo.*;

/** Real driver test in a separate hidden SDL window; never connects to a server. */
public final class GraphicsParityTest {
    static int program,vao,vbo,ebo,ubo,indirect,texture,sampler;
    static void check(boolean value,String why) { if (!value) throw new AssertionError(why); }
    public static void main(String[] args) throws Exception {
        boolean legacyLibrary=Arrays.asList(args).contains("--legacy-library");
        org.lwjgl.system.Configuration.OPENGL_EXPLICIT_INIT.set(true);
        NativeBridge.initialize(); check(NativeBridge.available(),NativeBridge.status());
        Trinium.initialized=true;
        check(SDL_Init(SDL_INIT_VIDEO),"SDL video");
        SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION,4); SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION,3);
        SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK,SDL_GL_CONTEXT_PROFILE_CORE);
        long window=SDL_CreateWindow("Trinium GPU parity",64,64,SDL_WINDOW_OPENGL|SDL_WINDOW_HIDDEN);
        check(window!=0,"SDL window");
        long context=SDL_GL_CreateContext(window); check(context!=0,"GL context");
        GL.create(SDLVideoAddress::address); GL.createCapabilities();
        System.out.println("GPU: "+glGetString(GL_RENDERER)+" / "+glGetString(GL_VERSION));
        try {
            setup();
            int checks=0;
            for (int cycle=0;cycle<3;cycle++) for (int variant=0;variant<12;variant++) {
                // Both transports must produce exactly the same pixels. Cycle 2 also
                // reuses records with stale unused arguments after legacy submission.
                if (!legacyLibrary) transport(cycle == 1);
                byte[] reference=render(false,variant), actual=render(true,variant);
                check(Arrays.equals(reference,actual),"GPU pixels diverged variant "+variant);
                long lit=0;for(int i=0;i<actual.length;i+=4)if((actual[i]&255)>100)lit++;
                check(lit>100 && lit<4096,"nontrivial geometry variant "+variant+" lit="+lit);
                checks++;
            }
            // Exercise all state-only operations, including floating-point and GLboolean ABI.
            long[] before=state(false), after=state(true);
            check(Arrays.equals(before,after),"GL state parity"); checks++;
            // Fill and flush multiple fixed-size packets; nested scopes keep ordering.
            RustGlDriver.begin();
            try {
                for(int i=0;i<600;i++)GlCommands.glScissor(i%16,i%8,32,32);
                RustGlDriver.begin();try{GlCommands.glScissor(7,9,23,25);}finally{RustGlDriver.end();}
            } finally { RustGlDriver.end(); }
            int[] box=new int[4];glGetIntegerv(GL_SCISSOR_BOX,box);
            check(Arrays.equals(box,new int[]{7,9,23,25}),"batch overflow/nesting order");checks++;
            if (!legacyLibrary) checks += malformedPackets();
            else {
                var field=RustGlDriver.class.getDeclaredField("legacyTransport");
                field.setAccessible(true);
                check(field.getBoolean(null),"automatic fallback for old ABI3 library");
                checks++;
            }
            check(RustGlDriver.draws.sum() == 36,"exact per-thread draw counter");
            check(glGetError()==GL_NO_ERROR,"final GL error");
            System.out.println("PASS GPU pixels/state/overflow: "+checks+" checks; batches="+RustGlDriver.batches.sum()+", commands="+RustGlDriver.commands.sum()+", draws="+RustGlDriver.draws.sum());
        } finally { GL.setCapabilities(null); SDL_GL_DestroyContext(context); SDL_DestroyWindow(window); SDL_Quit(); }
    }
    static void transport(boolean legacy) throws ReflectiveOperationException {
        var field=RustGlDriver.class.getDeclaredField("legacyTransport");
        field.setAccessible(true);field.setBoolean(null,legacy);
    }
    static int malformedPackets() throws ReflectiveOperationException {
        var call=RustGlDriver.class.getDeclaredMethod("executePacked0",ByteBuffer.class,int.class);
        call.setAccessible(true);
        int header=(GlCommands.FUNCTIONS.length*8+63)&~63;
        ByteBuffer storage=ByteBuffer.allocateDirect(header+128).order(ByteOrder.LITTLE_ENDIAN);
        check((int)call.invoke(null,storage,0)==0,"empty packed batch");
        check((int)call.invoke(null,storage,-1)==-1,"negative packed count");
        check((int)call.invoke(null,storage,3)==-1,"truncated packed count");
        check((int)call.invoke(null,storage,1)==-1,"null GL function rejected");
        check((int)call.invoke(null,ByteBuffer.allocate(header+128),0)==-1,"heap buffer rejected");
        check((int)call.invoke(null,ByteBuffer.allocateDirect(header-1),0)==-1,"short header rejected");
        check((int)call.invoke(null,new Object[]{null,0})==-1,"null storage rejected");
        // A bad second opcode must reject the WHOLE batch before calling function 1.
        storage.putLong(0,1).putLong(header+64,GlCommands.FUNCTIONS.length);
        check((int)call.invoke(null,storage,2)==-1,"all commands validated before execution");
        return 8;
    }
    static final class SDLVideoAddress { static long address(ByteBuffer name) { return SDL_GL_GetProcAddress(name); } }
    static int shader(int type,String text) {
        int id=glCreateShader(type);glShaderSource(id,text);glCompileShader(id);
        check(glGetShaderi(id,GL_COMPILE_STATUS)!=0,glGetShaderInfoLog(id));return id;
    }
    static void setup() {
        int vs=shader(GL_VERTEX_SHADER,"#version 330 core\nlayout(location=0) in vec2 p;void main(){gl_Position=vec4(p+vec2(float(gl_InstanceID)*.15,0),0,1);}");
        int fs=shader(GL_FRAGMENT_SHADER,"#version 330 core\nlayout(std140) uniform Color {vec4 color;};uniform sampler2D image;out vec4 frag;void main(){frag=color*texture(image,vec2(.5));}");
        program=glCreateProgram();glAttachShader(program,vs);glAttachShader(program,fs);glLinkProgram(program);
        check(glGetProgrami(program,GL_LINK_STATUS)!=0,glGetProgramInfoLog(program));glDeleteShader(vs);glDeleteShader(fs);
        glUseProgram(program);glUniformBlockBinding(program,glGetUniformBlockIndex(program,"Color"),0);glUniform1i(glGetUniformLocation(program,"image"),1);
        vao=glGenVertexArrays();glBindVertexArray(vao);vbo=glGenBuffers();glBindBuffer(GL_ARRAY_BUFFER,vbo);
        // First three vertices are padding: nonzero first/baseVertex must be preserved.
        glBufferData(GL_ARRAY_BUFFER,new float[]{0,0,0,0,0,0,-.7f,-.6f,.55f,-.6f,-.1f,.65f},GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);glVertexAttribPointer(0,2,GL_FLOAT,false,8,0L);
        ebo=glGenBuffers();glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,ebo);glBufferData(GL_ELEMENT_ARRAY_BUFFER,new int[]{99,99,99,0,1,2},GL_STATIC_DRAW);
        ubo=glGenBuffers();glBindBuffer(GL_UNIFORM_BUFFER,ubo);glBufferData(GL_UNIFORM_BUFFER,new float[]{.8f,.3f,.15f,1},GL_STATIC_DRAW);
        texture=glGenTextures();glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,texture);
        ByteBuffer white=BufferUtils.createByteBuffer(4).putInt(-1).flip();glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,1,1,0,GL_RGBA,GL_UNSIGNED_BYTE,white);
        sampler=glGenSamplers();glSamplerParameteri(sampler,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glSamplerParameteri(sampler,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        indirect=glGenBuffers();glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER,indirect);glBufferData(GL40C.GL_DRAW_INDIRECT_BUFFER,64,GL_STREAM_DRAW);
        glViewport(0,0,64,64);glDisable(GL_DITHER);check(glGetError()==GL_NO_ERROR,"setup GL error");
    }
    static byte[] render(boolean rust,int variant) {
        glBindBuffer(GL40C.GL_DRAW_INDIRECT_BUFFER,indirect);
        // Draw indirect records differ for indexed / arrays. Nonzero offsets exercise pointer width.
        if(variant>=8) {
            int[] data=variant<10?new int[]{99,99,99,99,3,1,3,3,0,3,1,3,3,0}:new int[]{99,99,99,99,3,1,3,0,3,1,3,0};
            glBufferSubData(GL40C.GL_DRAW_INDIRECT_BUFFER,0,data);
        }
        glDisable(GL_SCISSOR_TEST);glColorMask(true,true,true,true);glClearColor(0,0,0,1);glClear(GL_COLOR_BUFFER_BIT);
        IntBuffer counts=BufferUtils.createIntBuffer(2).put(new int[]{3,3}).flip();
        IntBuffer bases=BufferUtils.createIntBuffer(2).put(new int[]{3,3}).flip();
        PointerBuffer offsets=BufferUtils.createPointerBuffer(2).put(12L).put(12L).flip();
        if(rust)RustGlDriver.begin();
        try {
            GlCommands.glUseProgram(program);GlCommands.glBindVertexArray(vao);
            GlCommands.glBindBuffer(GL_ARRAY_BUFFER,vbo);GlCommands.glVertexAttribPointer(0,2,GL_FLOAT,false,8,0);
            GlCommands.glVertexAttribDivisor(0,0);GlCommands.glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,ebo);
            GlCommands.glBindBufferRange(GL_UNIFORM_BUFFER,0,ubo,0,16);
            GlCommands.glActiveTexture(GL_TEXTURE1);GlCommands.glBindTexture(GL_TEXTURE_2D,texture);
            GlCommands.glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_BASE_LEVEL,0);GlCommands.glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAX_LEVEL,0);
            GlCommands.glBindSampler(1,sampler);GlCommands.glDisable(GL_BLEND);GlCommands.glDisable(GL_CULL_FACE);GlCommands.glDisable(GL_DEPTH_TEST);
            GlCommands.glDepthMask(false);GlCommands.glEnable(GL_SCISSOR_TEST);GlCommands.glScissor(3,5,57,51);
            GlCommands.glColorMaski(0,true,true,true,true);GlCommands.glPolygonMode(GL_FRONT_AND_BACK,GL_FILL);
            switch(variant) {
                case 0 -> GlCommands.glDrawArraysInstanced(GL_TRIANGLES,3,3,1);
                case 1 -> GlCommands.glDrawArraysInstanced(GL_TRIANGLES,3,3,2);
                case 2 -> GlCommands.glDrawArraysInstancedBaseInstance(GL_TRIANGLES,3,3,2,7);
                case 3 -> GlCommands.glDrawElementsInstancedBaseVertex(GL_TRIANGLES,3,GL_UNSIGNED_INT,12,2,3);
                case 4 -> GlCommands.glDrawElementsInstancedBaseVertexBaseInstance(GL_TRIANGLES,3,GL_UNSIGNED_INT,12,2,3,7);
                case 5 -> GlCommands.nglMultiDrawArrays(GL_TRIANGLES,MemoryUtil.memAddress(bases),MemoryUtil.memAddress(counts),2);
                case 6 -> GlCommands.nglMultiDrawElementsBaseVertex(GL_TRIANGLES,MemoryUtil.memAddress(counts),GL_UNSIGNED_INT,MemoryUtil.memAddress(offsets),2,MemoryUtil.memAddress(bases));
                case 7 -> GlCommands.glDrawElementsInstancedBaseVertex(GL_TRIANGLES,3,GL_UNSIGNED_INT,12,1,3);
                case 8 -> GlCommands.glDrawElementsIndirect(GL_TRIANGLES,GL_UNSIGNED_INT,16);
                case 9 -> GlCommands.glMultiDrawElementsIndirect(GL_TRIANGLES,GL_UNSIGNED_INT,16,2,0);
                case 10 -> GlCommands.glDrawArraysIndirect(GL_TRIANGLES,16);
                case 11 -> GlCommands.glMultiDrawArraysIndirect(GL_TRIANGLES,16,2,0);
            }
        } finally {if(rust)RustGlDriver.end();}
        check(glGetError()==GL_NO_ERROR,"draw GL error "+variant+" rust="+rust);
        ByteBuffer out=BufferUtils.createByteBuffer(64*64*4);glReadPixels(0,0,64,64,GL_RGBA,GL_UNSIGNED_BYTE,out);
        byte[] result=new byte[out.remaining()];out.get(result);return result;
    }
    static long[] state(boolean rust) {
        int bufferTexture=glGenTextures();
        if(rust)RustGlDriver.begin();
        try {
            GlCommands.glEnablei(GL_BLEND,0);GlCommands.glDisablei(GL_BLEND,0);
            GlCommands.glDepthFunc(GL_GEQUAL);GlCommands.glDepthMask(true);GlCommands.glPolygonOffset(1.25f,-2.5f);
            GlCommands.glBlendFuncSeparate(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA,GL_ONE,GL_ZERO);
            GlCommands.glBlendEquationSeparate(GL_FUNC_ADD,GL_FUNC_REVERSE_SUBTRACT);
            GlCommands.glBindVertexBuffer(0,vbo,8,8);GlCommands.glVertexAttribIPointer(1,2,GL_INT,8,8);
            GlCommands.glActiveTexture(GL_TEXTURE2);GlCommands.glBindTexture(GL_TEXTURE_BUFFER,bufferTexture);
            GlCommands.glTexBuffer(GL_TEXTURE_BUFFER,GL_R32F,ubo);
        } finally {if(rust)RustGlDriver.end();}
        long[] result={glGetInteger(GL_DEPTH_FUNC),glGetInteger(GL_DEPTH_WRITEMASK),Float.floatToIntBits(glGetFloat(GL_POLYGON_OFFSET_FACTOR)),Float.floatToIntBits(glGetFloat(GL_POLYGON_OFFSET_UNITS)),glGetInteger(GL_BLEND_SRC_RGB),glGetInteger(GL_BLEND_DST_RGB),glGetInteger(GL_BLEND_EQUATION_ALPHA),glGetInteger(GL_ACTIVE_TEXTURE),glGetVertexAttribi(1,GL_VERTEX_ATTRIB_ARRAY_INTEGER)};
        check(glGetError()==GL_NO_ERROR,"state GL error");glDeleteTextures(bufferTexture);return result;
    }
}

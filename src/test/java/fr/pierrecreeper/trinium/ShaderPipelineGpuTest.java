// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium;

import fr.pierrecreeper.trinium.shaders.*;
import org.lwjgl.*;
import org.lwjgl.opengl.GL;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.sdl.SDLInit.*;
import static org.lwjgl.sdl.SDLVideo.*;

/** Actual GPU pixel/state checks for the independent renderer, with no mod loader. */
public final class ShaderPipelineGpuTest {
    private static int checks;
    private static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);checks++;}
    private static final PostShaderPipeline.Frame FRAME=new PostShaderPipeline.Frame(7,12,.01f,6000,2,.5f,.05f,128,1,2,3);
    private static int texture(int w,int h,boolean depth){
        int t=glGenTextures();glBindTexture(GL_TEXTURE_2D,t);
        float[] values=new float[w*h*(depth?1:4)];
        for(int i=0;i<w*h;i++){if(depth)values[i]=.25f;else {values[i*4]=.2f;values[i*4+1]=.4f;values[i*4+2]=.6f;values[i*4+3]=1;}}
        glTexImage2D(GL_TEXTURE_2D,0,depth?GL_DEPTH_COMPONENT32F:GL_RGBA8,w,h,0,depth?GL_DEPTH_COMPONENT:GL_RGBA,GL_FLOAT,values);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        return t;
    }
    private static byte[] pixels(int texture,int w,int h){
        int previous=glGetInteger(GL_READ_FRAMEBUFFER_BINDING),fbo=glGenFramebuffers();
        glBindFramebuffer(GL_READ_FRAMEBUFFER,fbo);glFramebufferTexture2D(GL_READ_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,texture,0);glReadBuffer(GL_COLOR_ATTACHMENT0);
        ByteBuffer result=BufferUtils.createByteBuffer(w*h*4);glReadPixels(0,0,w,h,GL_RGBA,GL_UNSIGNED_BYTE,result);
        byte[] bytes=new byte[result.remaining()];result.get(bytes);glBindFramebuffer(GL_READ_FRAMEBUFFER,previous);glDeleteFramebuffers(fbo);return bytes;
    }
    private static void color(byte[] bytes,int r,int g,int b,String label){for(int i=0;i<bytes.length;i+=4)if(Math.abs((bytes[i]&255)-r)>1||Math.abs((bytes[i+1]&255)-g)>1||Math.abs((bytes[i+2]&255)-b)>1)throw new AssertionError(label+" pixel "+i+": "+(bytes[i]&255)+","+(bytes[i+1]&255)+","+(bytes[i+2]&255));checks++;}
    private static List<Integer> state(){
        List<Integer> result=new ArrayList<>();
        for(int token:new int[]{GL_READ_FRAMEBUFFER_BINDING,GL_DRAW_FRAMEBUFFER_BINDING,GL_CURRENT_PROGRAM,GL_VERTEX_ARRAY_BINDING,GL_ARRAY_BUFFER_BINDING,GL_ACTIVE_TEXTURE})result.add(glGetInteger(token));
        int active=glGetInteger(GL_ACTIVE_TEXTURE);
        for(int i=0;i<2;i++){glActiveTexture(GL_TEXTURE0+i);result.add(glGetInteger(GL_TEXTURE_BINDING_2D));result.add(glGetInteger(GL_SAMPLER_BINDING));}
        glActiveTexture(active);
        for(int token:new int[]{GL_DEPTH_TEST,GL_CULL_FACE,GL_SCISSOR_TEST,GL_STENCIL_TEST,GL_RASTERIZER_DISCARD,GL_COLOR_LOGIC_OP,GL_FRAMEBUFFER_SRGB})result.add(glIsEnabled(token)?1:0);
        result.add(glIsEnabledi(GL_BLEND,0)?1:0);int[] viewport=new int[4];glGetIntegerv(GL_VIEWPORT,viewport);for(int v:viewport)result.add(v);
        ByteBuffer mask=BufferUtils.createByteBuffer(4);glGetBooleani_v(GL_COLOR_WRITEMASK,0,mask);for(int i=0;i<4;i++)result.add((int)mask.get(i));
        int[] polygon=new int[2];glGetIntegerv(GL_POLYGON_MODE,polygon);for(int v:polygon)result.add(v);return result;
    }
    public static void main(String[] args)throws Exception{
        org.lwjgl.system.Configuration.OPENGL_EXPLICIT_INIT.set(true);NativeBridge.initialize();Trinium.initialized=true;
        check(SDL_Init(SDL_INIT_VIDEO),"SDL video");SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION,4);SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION,3);SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK,SDL_GL_CONTEXT_PROFILE_CORE);
        long window=SDL_CreateWindow("Trinium independent shader GPU test",64,64,SDL_WINDOW_OPENGL|SDL_WINDOW_HIDDEN);check(window!=0,"SDL window");long context=SDL_GL_CreateContext(window);check(context!=0,"GL context");
        GL.create((ByteBuffer name) -> SDL_GL_GetProcAddress(name));GL.createCapabilities();
        Path root=Files.createTempDirectory("trinium-shader-gpu-");
        try{
            System.out.println("GPU: "+glGetString(GL_RENDERER)+" / "+glGetString(GL_VERSION));
            String vertex=StandalonePackTest.VERTEX;
            String identity="#version 120\nvarying vec2 uv;uniform sampler2D colortex0;void main(){gl_FragColor=texture2D(colortex0,uv);}";
            StandalonePackTest.zip(root.resolve("identity.zip"),Map.of("shaders/final.vsh",vertex,"shaders/final.fsh",identity));
            int target=texture(16,8,false),depth=texture(16,8,true),externalVao=glGenVertexArrays(),externalVbo=glGenBuffers();
            glBindVertexArray(externalVao);glBindBuffer(GL_ARRAY_BUFFER,externalVbo);glViewport(3,4,17,14);
            glEnable(GL_DEPTH_TEST);glEnable(GL_CULL_FACE);glEnable(GL_SCISSOR_TEST);glEnable(GL_STENCIL_TEST);glEnable(GL_RASTERIZER_DISCARD);glEnable(GL_COLOR_LOGIC_OP);glEnable(GL_FRAMEBUFFER_SRGB);glEnablei(GL_BLEND,0);glColorMaski(0,true,false,true,false);glPolygonMode(GL_FRONT_AND_BACK,GL_LINE);glActiveTexture(GL_TEXTURE4);
            List<Integer> before=state();
            try(var pipeline=new PostShaderPipeline(new StandalonePack(root,"identity.zip").passes("0",Map.of()))){
                check(state().equals(before),"Constructor restores complete GL state");
                for(int i=0;i<3;i++){pipeline.render(target,depth,16,8,FRAME);check(state().equals(before),"Render preserves engine GL state "+i);color(pixels(target,16,8),51,102,153,"Identity");}
                check(pipeline.frames==3&&pipeline.draws==3,"Actual GPU pass counters");
                try{new PostShaderPipeline(List.of(new StandalonePack.Pass("broken",vertex,"not GLSL")));throw new AssertionError("Broken source accepted");}catch(IllegalArgumentException expected){checks++;}
                check(state().equals(before),"Compilation failure preserves GL state");
                pipeline.render(target,depth,16,8,FRAME);color(pixels(target,16,8),51,102,153,"Old pipeline remains usable after failed compile");
                int resized=texture(7,5,false),resizedDepth=texture(7,5,true);List<Integer> resizeBefore=state();pipeline.render(resized,resizedDepth,7,5,FRAME);check(state().equals(resizeBefore),"Resize preserves GL state");color(pixels(resized,7,5),51,102,153,"Resized render");glDeleteTextures(resized);glDeleteTextures(resizedDepth);
            }
            StandalonePackTest.zip(root.resolve("chain.zip"),Map.of("shaders/composite.vsh",vertex,"shaders/composite.fsh",identity.replace("texture2D(colortex0,uv)","vec4(1.0-texture2D(colortex0,uv).rgb,1)"),"shaders/final.vsh",vertex,"shaders/final.fsh",identity.replace("texture2D(colortex0,uv)","texture2D(colortex0,uv).bgra")));
            try(var pipeline=new PostShaderPipeline(new StandalonePack(root,"chain.zip").passes("0",Map.of()))){pipeline.render(target,depth,16,8,FRAME);color(pixels(target,16,8),102,153,204,"Ordered ping-pong passes");check(pipeline.draws==2,"Two actual composite/final draws");}
            StandalonePackTest.zip(root.resolve("depth.zip"),Map.of("shaders/final.vsh",vertex,"shaders/final.fsh",identity.replace("colortex0","depthtex0").replace("gl_FragColor=texture2D(depthtex0,uv)","gl_FragColor=vec4(vec3(texture2D(depthtex0,uv).r),1)")));
            try(var pipeline=new PostShaderPipeline(new StandalonePack(root,"depth.zip").passes("0",Map.of()))){pipeline.render(target,depth,16,8,FRAME);color(pixels(target,16,8),191,191,191,"Reverse depth conversion");check(pipeline.draws==2,"Depth conversion and final draw");}
            StandalonePackTest.zip(root.resolve("unknown.zip"),Map.of("shaders/final.vsh",vertex,"shaders/final.fsh",identity.replace("colortex0","shadowtex0")));
            try{new PostShaderPipeline(new StandalonePack(root,"unknown.zip").passes("0",Map.of()));throw new AssertionError("Unsupported inputs silently accepted");}catch(IllegalArgumentException expected){check(expected.getMessage().contains("shadowtex0"),"Unsupported sampler rejected");}
            check(glGetError()==GL_NO_ERROR,"No GL errors after render, resize, failed compile and close");
            check(fr.pierrecreeper.trinium.graphics.RustGlDriver.draws.sum()==9,"Independent shader draws submitted through Rust");
            System.out.println("PASS independent shader GPU pixels/state/lifecycle: "+checks+" checks; Rust draws="+fr.pierrecreeper.trinium.graphics.RustGlDriver.draws.sum());
        }finally{
            try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}
            GL.setCapabilities(null);SDL_GL_DestroyContext(context);SDL_DestroyWindow(window);SDL_Quit();
        }
    }
}

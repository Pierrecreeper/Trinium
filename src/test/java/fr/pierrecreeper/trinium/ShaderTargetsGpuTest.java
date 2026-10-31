// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium;

import fr.pierrecreeper.trinium.shaders.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import static org.lwjgl.opengl.GL43C.*;
import static org.lwjgl.sdl.SDLInit.*;
import static org.lwjgl.sdl.SDLVideo.*;

/** Original real-GL pixel oracle for bounded MRT/HDR/history. No Minecraft launch. */
public final class ShaderTargetsGpuTest {
    private static int checks;
    private static long expectedDraws;
    private static final PostShaderPipeline.Frame FRAME = new PostShaderPipeline.Frame(1,1,.01f,6000,0,0,.05f,128,0,0,0);
    private static final String V = StandalonePackTest.VERTEX;
    private static final String F = "#version 120\nvarying vec2 uv;\n";
    private static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);checks++;}
    private static int texture(int w,int h,boolean checker) {
        int id=glGenTextures(); glBindTexture(GL_TEXTURE_2D,id); glBindBuffer(GL_PIXEL_UNPACK_BUFFER,0);
        float[] data=new float[w*h*4];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
            int p=(y*w+x)*4;float c=((x+y)&1)==0?0:1;
            data[p]=checker?c:.2f;data[p+1]=checker?c:.4f;data[p+2]=checker?c:.6f;data[p+3]=1;
        }
        glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,w,h,0,GL_RGBA,GL_FLOAT,data);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST); return id;
    }
    private static void pixels(int texture,int w,int h,float r,float g,float b,String label) {
        int previous=glGetInteger(GL_READ_FRAMEBUFFER_BINDING),fbo=glGenFramebuffers();
        glBindFramebuffer(GL_READ_FRAMEBUFFER,fbo);glFramebufferTexture2D(GL_READ_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,texture,0);glReadBuffer(GL_COLOR_ATTACHMENT0);
        FloatBuffer data=BufferUtils.createFloatBuffer(w*h*4);glReadPixels(0,0,w,h,GL_RGBA,GL_FLOAT,data);
        glBindFramebuffer(GL_READ_FRAMEBUFFER,previous);glDeleteFramebuffers(fbo);
        for(int i=0;i<w*h;i++) {
            float[] expected={r,g,b,1};
            for(int c=0;c<4;c++)if(Math.abs(data.get(i*4+c)-expected[c])>.012f)
                throw new AssertionError(label+" pixel="+i+" channel="+c+" actual="+data.get(i*4+c)+" expected="+expected[c]);
        }
        checks++;
    }
    private static List<Integer> state() {
        List<Integer> result=new ArrayList<>();int active=glGetInteger(GL_ACTIVE_TEXTURE);
        for(int token:new int[]{GL_READ_FRAMEBUFFER_BINDING,GL_DRAW_FRAMEBUFFER_BINDING,GL_CURRENT_PROGRAM,GL_VERTEX_ARRAY_BINDING,GL_ARRAY_BUFFER_BINDING,GL_PIXEL_UNPACK_BUFFER_BINDING,GL_ACTIVE_TEXTURE})result.add(glGetInteger(token));
        for(int i=0;i<9;i++){glActiveTexture(GL_TEXTURE0+i);result.add(glGetInteger(GL_TEXTURE_BINDING_2D));result.add(glGetInteger(GL_SAMPLER_BINDING));}
        glActiveTexture(active);
        for(int token:new int[]{GL_DEPTH_TEST,GL_CULL_FACE,GL_SCISSOR_TEST,GL_STENCIL_TEST,GL_RASTERIZER_DISCARD,GL_COLOR_LOGIC_OP,GL_FRAMEBUFFER_SRGB})result.add(glIsEnabled(token)?1:0);
        ByteBuffer mask=BufferUtils.createByteBuffer(4);
        for(int i=0;i<5;i++){result.add(glIsEnabledi(GL_BLEND,i)?1:0);glGetBooleani_v(GL_COLOR_WRITEMASK,i,mask);for(int c=0;c<4;c++)result.add((int)mask.get(c));}
        int[] vp=new int[4],scissor=new int[4],polygon=new int[2];glGetIntegerv(GL_VIEWPORT,vp);glGetIntegerv(GL_SCISSOR_BOX,scissor);glGetIntegerv(GL_POLYGON_MODE,polygon);for(int v:vp)result.add(v);for(int v:scissor)result.add(v);for(int v:polygon)result.add(v);return result;
    }
    private static List<StandalonePack.Pass> pack(Path root,String name,Map<String,String> fragments)throws Exception {
        Map<String,String> files=new LinkedHashMap<>();fragments.forEach((program,source)->{files.put("shaders/"+program+".vsh",V);files.put("shaders/"+program+".fsh",F+source);});
        StandalonePackTest.zip(root.resolve(name),files);return new StandalonePack(root,name).passes("0",Map.of());
    }
    private static void render(PostShaderPipeline pipeline,int target,int w,int h,List<Integer> before) {
        pipeline.render(target,0,w,h,FRAME);check(state().equals(before),"All GL texture/indexed state restored after render");check(glGetError()==GL_NO_ERROR,"No rendering GL error");
    }
    public static void main(String[] args)throws Exception {
        org.lwjgl.system.Configuration.OPENGL_EXPLICIT_INIT.set(true);NativeBridge.initialize();Trinium.initialized=true;
        check(SDL_Init(SDL_INIT_VIDEO),"SDL video");SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION,4);SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION,3);SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK,SDL_GL_CONTEXT_PROFILE_CORE);
        long window=SDL_CreateWindow("Trinium MRT GPU oracle",64,64,SDL_WINDOW_OPENGL|SDL_WINDOW_HIDDEN);check(window!=0,"Window");long context=SDL_GL_CreateContext(window);check(context!=0,"GL context");
        GL.create((ByteBuffer name)->SDL_GL_GetProcAddress(name));GL.createCapabilities();Path root=Files.createTempDirectory("trinium-mrt-gpu-");
        try {
            System.out.println("GPU="+glGetString(GL_RENDERER)+" GL="+glGetString(GL_VERSION)+" drawBuffers="+glGetInteger(GL_MAX_DRAW_BUFFERS)+" colorAttachments="+glGetInteger(GL_MAX_COLOR_ATTACHMENTS));
            check(glGetInteger(GL_MAX_DRAW_BUFFERS)>=5&&glGetInteger(GL_MAX_COLOR_ATTACHMENTS)>=5,"Five real output attachments supported");
            int target=texture(16,8,false),small=texture(7,5,false),checker=texture(16,8,true);
            int externalRead=glGenFramebuffers(),externalDraw=glGenFramebuffers();
            glBindFramebuffer(GL_READ_FRAMEBUFFER,externalRead);glFramebufferTexture2D(GL_READ_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,checker,0);glReadBuffer(GL_COLOR_ATTACHMENT0);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER,externalDraw);glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,target,0);glDrawBuffer(GL_COLOR_ATTACHMENT0);
            int vao=glGenVertexArrays(),vbo=glGenBuffers(),unpack=glGenBuffers(),sampler=glGenSamplers();
            glBindVertexArray(vao);glBindBuffer(GL_ARRAY_BUFFER,vbo);glBindBuffer(GL_PIXEL_UNPACK_BUFFER,unpack);glBufferData(GL_PIXEL_UNPACK_BUFFER,1024,GL_STATIC_DRAW);
            for(int i=0;i<9;i++){glActiveTexture(GL_TEXTURE0+i);glBindTexture(GL_TEXTURE_2D,checker);glBindSampler(i,sampler);}
            for(int cap:new int[]{GL_DEPTH_TEST,GL_CULL_FACE,GL_SCISSOR_TEST,GL_STENCIL_TEST,GL_RASTERIZER_DISCARD,GL_COLOR_LOGIC_OP,GL_FRAMEBUFFER_SRGB})glEnable(cap);
            for(int i=0;i<5;i++){if((i&1)==0)glEnablei(GL_BLEND,i);else glDisablei(GL_BLEND,i);glColorMaski(i,(i&1)==0,false,true,false);}
            glPolygonMode(GL_FRONT_AND_BACK,GL_LINE);glViewport(3,4,9,11);glScissor(2,3,4,5);glActiveTexture(GL_TEXTURE7);List<Integer> before=state();
            String formats="/* const int colortex0Format=RGBA16F;\nconst int colortex1Format=RGBA16F;\nconst int colortex2Format=RGBA16F;\nconst int colortex3Format=RGBA16F;\nconst int colortex4Format=RGBA16F; */\n";
            var mrt=pack(root,"five.zip",Map.of(
                "composite","/* RENDERTARGETS:4,2,1,3,0 */\n"+formats+"void main(){gl_FragData[0]=vec4(2.0);gl_FragData[1]=vec4(-.5);gl_FragData[2]=vec4(.3);gl_FragData[3]=vec4(.7);gl_FragData[4]=vec4(3.0);}",
                "composite1","/* DRAWBUFFERS:3 */\nuniform sampler2D colortex3;void main(){gl_FragData[0]=texture2D(colortex3,uv)+vec4(.1);}",
                "final","uniform sampler2D colortex0,colortex1,colortex2,colortex3,colortex4;void main(){gl_FragColor=vec4(texture2D(colortex4,uv).r*.1,texture2D(colortex2,uv).r+texture2D(colortex1,uv).r+texture2D(colortex3,uv).r-.1,texture2D(colortex0,uv).r*.2,1);}"));
            try(var pipeline=new PostShaderPipeline(mrt)) {
                check(state().equals(before),"MRT constructor preserves state");pipeline.prepare(16,8);check(state().equals(before),"MRT allocation preserves PBO and indexed state");
                render(pipeline,target,16,8,before);pixels(target,16,8,.2f,.5f,.6f,"Five routed HDR outputs, selective flip and negative values");
                check(pipeline.draws==3,"Three real MRT/composite/final draws");expectedDraws+=pipeline.draws;
            }
            var history=pack(root,"history.zip",Map.of("composite","/* DRAWBUFFERS:1 */\nconst int colortex1Format=RGBA16F;\nconst bool colortex1Clear=false;\nconst vec4 colortex1ClearColor=vec4(0.0);\nuniform sampler2D colortex1;void main(){gl_FragData[0]=texture2D(colortex1,uv)+vec4(.25);}","final","uniform sampler2D colortex1;void main(){gl_FragColor=vec4(texture2D(colortex1,uv).rrr,1);}"));
            try(var pipeline=new PostShaderPipeline(history)) {
                for(int i=1;i<=3;i++){render(pipeline,target,16,8,before);pixels(target,16,8,i*.25f,i*.25f,i*.25f,"Persistent auxiliary history frame "+i);}
                render(pipeline,small,7,5,before);pixels(small,7,5,.25f,.25f,.25f,"Resize clears history exactly once");
                try{pipeline.prepare(Integer.MAX_VALUE,16);throw new AssertionError("Invalid allocation accepted");}catch(IllegalArgumentException expected){checks++;}
                check(state().equals(before),"Invalid resize leaves GL state intact");
                try{pipeline.prepare(glGetInteger(GL_MAX_TEXTURE_SIZE),glGetInteger(GL_MAX_TEXTURE_SIZE));throw new AssertionError("Over-budget allocation accepted");}catch(IllegalArgumentException expected){check(expected.getMessage().contains("budget"),"Budget rejected before allocation");}
                render(pipeline,small,7,5,before);pixels(small,7,5,.5f,.5f,.5f,"Previous images survive failed allocation");
                try{new PostShaderPipeline(List.of(new StandalonePack.Pass("broken",V,"not GLSL")));throw new AssertionError("Broken source accepted");}catch(IllegalArgumentException expected){checks++;}
                render(pipeline,small,7,5,before);pixels(small,7,5,.75f,.75f,.75f,"Old pipeline survives failed replacement compile");expectedDraws+=pipeline.draws;
            }
            var cleared=pack(root,"clear.zip",Map.of("composite","/* DRAWBUFFERS:1 */\nconst bool colortex1Clear=true;\nconst vec4 colortex1ClearColor=vec4(.1);\nuniform sampler2D colortex1;void main(){gl_FragData[0]=texture2D(colortex1,uv)+vec4(.2);}","final","uniform sampler2D colortex1;void main(){gl_FragColor=vec4(texture2D(colortex1,uv).rrr,1);}"));
            try(var pipeline=new PostShaderPipeline(cleared)){for(int i=0;i<2;i++){render(pipeline,target,16,8,before);pixels(target,16,8,.3f,.3f,.3f,"Clear every frame "+i);}expectedDraws+=pipeline.draws;}
            var mip=pack(root,"mip.zip",Map.of("final","const bool colortex0MipmapEnabled=true;\nuniform sampler2D colortex0;void main(){gl_FragColor=vec4(texture2DLod(colortex0,uv,3.0).rgb,1);}"));
            try(var pipeline=new PostShaderPipeline(mip)){render(pipeline,checker,16,8,before);pixels(checker,16,8,.5f,.5f,.5f,"Real generated mip levels");expectedDraws+=pipeline.draws;}
            var alone=pack(root,"composite-only.zip",Map.of("composite","void main(){gl_FragColor=vec4(.9,.2,.1,1);}"));
            try(var pipeline=new PostShaderPipeline(alone)){render(pipeline,target,16,8,before);pixels(target,16,8,.9f,.2f,.1f,"Composite without final presents colortex0");expectedDraws+=pipeline.draws;}
            var normalized=pack(root,"snorm.zip",Map.of("composite","/* DRAWBUFFERS:2 */\nconst int colortex2Format=RGBA8_SNORM;\nvoid main(){gl_FragData[0]=vec4(-.5,.25,1,1);}","final","uniform sampler2D colortex2;void main(){vec3 c=texture2D(colortex2,uv).rgb;gl_FragColor=vec4(c.r+.75,c.g,c.b,1);}"));
            if (glGetInternalformati(GL_TEXTURE_2D,GL_RGBA8_SNORM,GL_FRAMEBUFFER_RENDERABLE)!=GL_NONE) {
                try(var pipeline=new PostShaderPipeline(normalized)){render(pipeline,target,16,8,before);pixels(target,16,8,.25f,.25f,1,"Signed normalized target");expectedDraws+=pipeline.draws;}
            } else {
                try{new PostShaderPipeline(normalized);throw new AssertionError("Unsupported SNORM format accepted");}catch(IllegalArgumentException expected){check(expected.getMessage().contains("format"),"Unsupported SNORM refused using actual GPU capabilities");}
                System.out.println("RGBA8_SNORM render target unsupported on this GPU: refusal verified.");
            }
            var invalid=pack(root,"bad-output.zip",Map.of("composite","layout(location=0) out uvec4 value;void main(){value=uvec4(1);}"));
            try{new PostShaderPipeline(invalid);throw new AssertionError("Integer output accepted");}catch(IllegalArgumentException expected){checks++;}
            check(state().equals(before),"All failures preserve external state");check(glGetError()==GL_NO_ERROR,"No residual GL error");
            check(fr.pierrecreeper.trinium.graphics.RustGlDriver.draws.sum()==expectedDraws,"Every draw went through the Rust command transport");
            System.out.println("PASS standalone shader MRT/HDR/history/mipmap/state: "+checks+" checks; Rust draws="+expectedDraws);
            glDeleteFramebuffers(externalRead);glDeleteFramebuffers(externalDraw);glDeleteTextures(target);glDeleteTextures(small);glDeleteTextures(checker);glDeleteSamplers(sampler);glDeleteBuffers(unpack);glDeleteBuffers(vbo);glDeleteVertexArrays(vao);
        } finally {
            try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}
            GL.setCapabilities(null);SDL_GL_DestroyContext(context);SDL_DestroyWindow(window);SDL_Quit();
        }
    }
}

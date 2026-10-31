// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium;
import fr.pierrecreeper.trinium.graphics.*;
import java.nio.*;
import java.util.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.*;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.sdl.SDLInit.*;
import static org.lwjgl.sdl.SDLVideo.*;

/** Independent driver oracle for direct draw IDs, integer texel buffers and Rust submission. */
public final class DrawIdGpuTest {
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 public static void main(String[] args){
  int n=Arrays.asList(args).contains("--large")?1024:64,grid=(int)Math.sqrt(n),resolution=grid*8;
  org.lwjgl.system.Configuration.OPENGL_EXPLICIT_INIT.set(true);
  NativeBridge.initialize();check(NativeBridge.available(),NativeBridge.status());Trinium.initialized=true;
  check(SDL_Init(SDL_INIT_VIDEO),"SDL video");SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION,4);SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION,3);SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK,SDL_GL_CONTEXT_PROFILE_CORE);
  long w=SDL_CreateWindow("Trinium direct draw ID oracle",resolution,resolution,SDL_WINDOW_OPENGL|SDL_WINDOW_HIDDEN),c=SDL_GL_CreateContext(w);check(w!=0&&c!=0,"GL context");
  GL.create(GraphicsParityTest.SDLVideoAddress::address);GL.createCapabilities();
  try(MemoryStack stack=MemoryStack.stackPush()){
   String vs="""
    #version 450
    #extension GL_ARB_shader_draw_parameters : require
    layout(location=0) in vec2 Position;
    uniform int manual;
    uniform int drawBase;
    uniform isamplerBuffer ids;
    uniform isamplerBuffer chunks;
    flat out int drawid;
    const vec2 p[3]=vec2[3](vec2(-.09,-.09),vec2(.09,-.09),vec2(-.09,.09));
    void main(){
      int id=manual<0?gl_DrawIDARB+drawBase:manual;
      ivec4 data=texelFetch(chunks,texelFetch(ids,id).r);
      gl_Position=vec4(Position+vec2(data.xy)*.25-vec2(.875),intBitsToFloat(data.w),1);
      drawid=id;
    }
    """;
   if(n==1024)vs=vs.replace("*.25","*.0625").replace("(.875)","(.96875)");
   if(Arrays.asList(args).contains("--glsl330"))vs=vs.replace("#version 450","#version 330");
   int a=GraphicsParityTest.shader(GL_VERTEX_SHADER,vs),b=GraphicsParityTest.shader(GL_FRAGMENT_SHADER,"#version 450\nflat in int drawid;out vec4 frag;void main(){frag=vec4(float(drawid+1)/65.,.7,.2,1);}");
   int program=glCreateProgram();glAttachShader(program,a);glAttachShader(program,b);glLinkProgram(program);check(glGetProgrami(program,GL_LINK_STATUS)!=0,glGetProgramInfoLog(program));glUseProgram(program);
   glBindVertexArray(glGenVertexArrays());int ebo=glGenBuffers();glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,ebo);glBufferData(GL_ELEMENT_ARRAY_BUFFER,new int[]{99,99,99,0,1,2},GL_STATIC_DRAW);
   int vbo=glGenBuffers();glBindBuffer(GL_ARRAY_BUFFER,vbo);float[] vertices=new float[n*6];float half=.72f/grid;for(int i=0;i<n;i++){System.arraycopy(new float[]{-half,-half,half,-half,-half,half},0,vertices,i*6,6);}
   glBufferData(GL_ARRAY_BUFFER,vertices,GL_STATIC_DRAW);glEnableVertexAttribArray(0);glVertexAttribPointer(0,2,GL_FLOAT,false,8,0L);
   if(Arrays.asList(args).contains("--separate")){GL43C.glBindVertexBuffer(0,vbo,0,8);GL43C.glVertexAttribFormat(0,2,GL_FLOAT,false,0);GL43C.glVertexAttribBinding(0,0);GL43C.glVertexBindingDivisor(0,0);}
   int[] ids=new int[n],data=new int[n*4];for(int i=0;i<n;i++){int index=(i*13+7)%n;ids[i]=index;data[index*4]=i%grid;data[index*4+1]=i/grid;data[index*4+2]=-37;data[index*4+3]=Float.floatToRawIntBits(.25f);}
   for(int unit=0;unit<2;unit++){
    glActiveTexture(GL_TEXTURE0+unit);int texture=glGenTextures(),buffer=glGenBuffers();glBindTexture(GL_TEXTURE_BUFFER,texture);glBindBuffer(GL_TEXTURE_BUFFER,buffer);glBufferData(GL_TEXTURE_BUFFER,unit==0?ids:data,GL_STATIC_DRAW);glTexBuffer(GL_TEXTURE_BUFFER,unit==0?GL_R32I:GL_RGBA32I,buffer);
   }
   glUniform1i(glGetUniformLocation(program,"ids"),0);glUniform1i(glGetUniformLocation(program,"chunks"),1);
   int manual=glGetUniformLocation(program,"manual");var offsets=stack.mallocPointer(n);var counts=stack.mallocInt(n);var bases=stack.mallocInt(n);
   for(int i=0;i<n;i++){offsets.put(i,12);counts.put(i,3);bases.put(i,i*3);}
   glViewport(0,0,resolution,resolution);glDisable(GL_DITHER);
   byte[] reference=null;
   var pixels=MemoryUtil.memAlloc(resolution*resolution*4);
   for(int mode=0;mode<5;mode++){
    if(mode==3)for(int i=0;i<n;i++)bases.put(i,0);
    glClearColor(0,0,0,1);glClear(GL_COLOR_BUFFER_BIT);
    if(mode==0){for(int i=0;i<n;i++){glUniform1i(manual,i);GL32C.glDrawElementsBaseVertex(GL_TRIANGLES,3,GL_UNSIGNED_INT,12,i*3);}}
    else {
     glUniform1i(manual,-1);
     int limit=Arrays.asList(args).contains("--split64")?64:n;
     for(int start=0;start<n;start+=limit) {
      int count=Math.min(limit,n-start);glUniform1i(glGetUniformLocation(program,"drawBase"),start);
      counts.position(start).limit(start+count);bases.position(start).limit(start+count);offsets.position(start).limit(start+count);
      if(mode==1||mode==3)GL32C.glMultiDrawElementsBaseVertex(GL_TRIANGLES,counts,GL_UNSIGNED_INT,offsets,bases);
      else {RustGlDriver.begin();try{GlCommands.nglMultiDrawElementsBaseVertex(GL_TRIANGLES,MemoryUtil.memAddress(counts),GL_UNSIGNED_INT,MemoryUtil.memAddress(offsets),count,MemoryUtil.memAddress(bases));}finally{RustGlDriver.end();}}
      counts.clear();bases.clear();offsets.clear();
     }
    }
    pixels.clear();glReadPixels(0,0,resolution,resolution,GL_RGBA,GL_UNSIGNED_BYTE,pixels);byte[] actual=new byte[pixels.remaining()];pixels.get(actual);
    check(glGetError()==GL_NO_ERROR,"GL error mode "+mode);
    if(mode==0)reference=actual;
    else if(!Arrays.equals(reference,actual)) {
     int changed=0,litA=0,litB=0;for(int i=0;i<actual.length;i+=4){if(reference[i]!=actual[i]||reference[i+1]!=actual[i+1]||reference[i+2]!=actual[i+2])changed++;if((reference[i+1]&255)>100)litA++;if((actual[i+1]&255)>100)litB++;}
     throw new AssertionError("Draw ID / integer texel buffer pixels differ mode "+mode+" changed="+changed+" litReference="+litA+" litActual="+litB);
    }
   }
   MemoryUtil.memFree(pixels);
   int lit=0;for(int i=0;i<reference.length;i+=4)if((reference[i+1]&255)>100)lit++;check(lit>500,"Nontrivial 64-draw grid "+lit);
   System.out.println("PASS direct gl_DrawIDARB oracle: "+n+" ordered indexed draws, signed integer texel buffers, nonzero indices/base vertices; actual vertex attributes, distinct/reused base vertices, raw and Rust pixels identical; lit="+lit);
  }finally{GL.setCapabilities(null);SDL_GL_DestroyContext(c);SDL_DestroyWindow(w);SDL_Quit();}
 }
}

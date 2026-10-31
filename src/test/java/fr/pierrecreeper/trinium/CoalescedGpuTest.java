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

/** Real GPU oracle: nested draw scopes, overflow, borrowed arrays and exceptional exit. */
public final class CoalescedGpuTest {
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 public static void main(String[] args){
  org.lwjgl.system.Configuration.OPENGL_EXPLICIT_INIT.set(true);NativeBridge.initialize();check(NativeBridge.available(),NativeBridge.status());Trinium.initialized=true;
  check(SDL_Init(SDL_INIT_VIDEO),"SDL video");SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION,4);SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION,3);SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK,SDL_GL_CONTEXT_PROFILE_CORE);
  long window=SDL_CreateWindow("Trinium coalesced GL oracle",64,64,SDL_WINDOW_OPENGL|SDL_WINDOW_HIDDEN),context=SDL_GL_CreateContext(window);check(window!=0&&context!=0,"GL context");
  GL.create(GraphicsParityTest.SDLVideoAddress::address);GL.createCapabilities();
  try {
   GraphicsParityTest.setup();byte[] reference=GraphicsParityTest.render(false,7);
   long normal=loop(false,2000,reference),coalesced=loop(true,2000,reference);
   check(normal==2000 && coalesced>1 && coalesced<normal/3,"Measured coalescing and fixed packet overflow: "+normal+" -> "+coalesced);
   clear();RustGlDriver.beginCoalesced();
   try {
    RustGlDriver.begin();try{emit();}finally{RustGlDriver.end();}
    try(MemoryStack stack=MemoryStack.stackPush()) {
     var counts=stack.ints(3,3);var bases=stack.ints(3,3);var offsets=stack.pointers(12L,12L);
     long before=RustGlDriver.batches.sum();
     RustGlDriver.begin();try{GlCommands.nglMultiDrawElementsBaseVertex(GL_TRIANGLES,MemoryUtil.memAddress(counts),GL_UNSIGNED_INT,MemoryUtil.memAddress(offsets),2,MemoryUtil.memAddress(bases));}finally{RustGlDriver.end();}
     check(RustGlDriver.batches.sum()>before,"Borrowed pointers flushed before inner caller returns");
    }
    try(MemoryStack overwritten=MemoryStack.stackPush()){overwritten.calloc(256).putInt(0,-1);}
   } finally {RustGlDriver.endCoalesced();}
   check(Arrays.equals(reference,read()),"Borrowed pointer lifetime pixels");
   clear();try {RustGlDriver.beginCoalesced();try{RustGlDriver.begin();try{emit();}finally{RustGlDriver.end();}throw new IllegalArgumentException("fixture");}finally{RustGlDriver.endCoalesced();}}
   catch(IllegalArgumentException expected){}
   RustGlDriver.begin();try{emit();}finally{RustGlDriver.end();}
   check(Arrays.equals(reference,read()),"Exception restores ordinary scope");
   check(glGetError()==GL_NO_ERROR,"Final GL state");
   System.out.println("PASS coalesced GPU: 2000 draws with identical pixels; native submissions "+normal+" -> "+coalesced+"; overflow, borrowed-pointer flush and exception recovery");
  }finally{GL.setCapabilities(null);SDL_GL_DestroyContext(context);SDL_DestroyWindow(window);SDL_Quit();}
 }
 static long loop(boolean coalesced,int count,byte[] reference){
  clear();long before=RustGlDriver.batches.sum();
  if(coalesced)RustGlDriver.beginCoalesced();
  try{for(int i=0;i<count;i++){RustGlDriver.begin();try{emit();}finally{RustGlDriver.end();}}}
  finally{if(coalesced)RustGlDriver.endCoalesced();}
  long submissions=RustGlDriver.batches.sum()-before;check(Arrays.equals(reference,read()),"Loop pixels coalesced="+coalesced);return submissions;
 }
 static void clear(){glDisable(GL_SCISSOR_TEST);glColorMask(true,true,true,true);glClearColor(0,0,0,1);glClear(GL_COLOR_BUFFER_BIT);}
 static byte[] read(){ByteBuffer data=MemoryUtil.memAlloc(64*64*4);try{glReadPixels(0,0,64,64,GL_RGBA,GL_UNSIGNED_BYTE,data);byte[] result=new byte[data.remaining()];data.get(result);return result;}finally{MemoryUtil.memFree(data);}}
 static void emit(){
  GlCommands.glUseProgram(GraphicsParityTest.program);GlCommands.glBindVertexArray(GraphicsParityTest.vao);
  GlCommands.glBindBuffer(GL_ARRAY_BUFFER,GraphicsParityTest.vbo);GlCommands.glVertexAttribPointer(0,2,GL_FLOAT,false,8,0);GlCommands.glVertexAttribDivisor(0,0);GlCommands.glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,GraphicsParityTest.ebo);
  GlCommands.glBindBufferRange(GL_UNIFORM_BUFFER,0,GraphicsParityTest.ubo,0,16);GlCommands.glActiveTexture(GL_TEXTURE1);GlCommands.glBindTexture(GL_TEXTURE_2D,GraphicsParityTest.texture);GlCommands.glBindSampler(1,GraphicsParityTest.sampler);
  GlCommands.glDisable(GL_BLEND);GlCommands.glDisable(GL_CULL_FACE);GlCommands.glDisable(GL_DEPTH_TEST);GlCommands.glDepthMask(false);GlCommands.glEnable(GL_SCISSOR_TEST);GlCommands.glScissor(3,5,57,51);GlCommands.glColorMaski(0,true,true,true,true);GlCommands.glPolygonMode(GL_FRONT_AND_BACK,GL_FILL);
  GlCommands.glDrawElementsInstancedBaseVertex(GL_TRIANGLES,3,GL_UNSIGNED_INT,12,1,3);
 }
}

package fr.pierrecreeper.trinium.test;

import java.nio.IntBuffer;
import java.util.Arrays;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL43C.*;

/** Test-only readback, after the transport has completed the draw's GL calls. */
@Mixin(targets="com.mojang.renderpearl.backend.opengl.GlRenderPass",remap=false)
public abstract class DirectUniformProbeMixin {
    @Unique private static boolean trinium$old,trinium$new;
    @Inject(method="drawIndexed",at=@At("RETURN"))
    private void trinium$old(int count,int instances,int first,int base,int instance,CallbackInfo ci) {
        int program=glGetInteger(GL_CURRENT_PROGRAM);
        int section=glGetUniformBlockIndex(program,"_uniform_00_00");
        if(!trinium$old && count>2000 && glGetProgrami(program,GL_ACTIVE_UNIFORM_BLOCKS)>=5
            && section>=0 && glGetActiveUniformBlocki(program,section,GL_UNIFORM_BLOCK_DATA_SIZE)==16) {
            trinium$old=true;trinium$dump("vanilla",program,base);
        }
    }
    @Inject(method="multiDrawIndexed(Lorg/lwjgl/PointerBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;I)V",at=@At("RETURN"))
    private void trinium$new(PointerBuffer offsets,IntBuffer counts,IntBuffer bases,int count,CallbackInfo ci) {
        if(!trinium$new && count>300) {trinium$new=true;trinium$dump("direct",glGetInteger(GL_CURRENT_PROGRAM),bases.get(bases.position()));}
    }
    @Unique private static void trinium$dump(String mode,int program,int base) {
        int old=glGetInteger(GL_COPY_READ_BUFFER);
        try(MemoryStack stack=MemoryStack.stackPush()) {
            System.out.println("TRINIUM_UNIFORM_READBACK "+mode+" program="+program+" base="+base);
            for(int block=0;block<glGetProgrami(program,GL_ACTIVE_UNIFORM_BLOCKS);block++) {
                int binding=glGetActiveUniformBlocki(program,block,GL_UNIFORM_BLOCK_BINDING);
                int buffer=glGetIntegeri(GL_UNIFORM_BUFFER_BINDING,binding);
                long offset=glGetInteger64i(GL_UNIFORM_BUFFER_START,binding);
                int size=glGetActiveUniformBlocki(program,block,GL_UNIFORM_BLOCK_DATA_SIZE);
                var values=stack.mallocInt(Math.min(size/4,20));glBindBuffer(GL_COPY_READ_BUFFER,buffer);glGetBufferSubData(GL_COPY_READ_BUFFER,offset,values);
                int[] words=new int[values.remaining()];values.get(words);float[] floats=new float[words.length];for(int i=0;i<words.length;i++)floats[i]=Float.intBitsToFloat(words[i]);
                System.out.println("TRINIUM_UNIFORM_READBACK "+mode+" "+glGetActiveUniformBlockName(program,block)+" binding="+binding+" buffer="+buffer+" offset="+offset+" size="+size+" ints="+Arrays.toString(words)+" floats="+Arrays.toString(floats));
            }
            int vertex=glGetIntegeri(GL_VERTEX_BINDING_BUFFER,0),stride=glGetIntegeri(GL_VERTEX_BINDING_STRIDE,0);
            long offset=glGetInteger64i(GL_VERTEX_BINDING_OFFSET,0);
            var xyz=stack.mallocFloat(3);glBindBuffer(GL_COPY_READ_BUFFER,vertex);glGetBufferSubData(GL_COPY_READ_BUFFER,offset+(long)stride*base,xyz);float[] array=new float[3];xyz.get(array);
            System.out.println("TRINIUM_UNIFORM_READBACK "+mode+" vertex="+vertex+" stride="+stride+" offset="+offset+" xyz="+Arrays.toString(array)+" error="+glGetError());
        } finally {glBindBuffer(GL_COPY_READ_BUFFER,old);}
    }
}

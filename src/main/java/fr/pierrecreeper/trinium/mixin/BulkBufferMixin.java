package fr.pierrecreeper.trinium.mixin;

import com.mojang.blaze3d.vertex.*;
import fr.pierrecreeper.trinium.graphics.BulkVertexSink;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.lwjgl.system.MemoryUtil;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;

@Mixin(value=BufferBuilder.class,remap=false)
public abstract class BulkBufferMixin implements BulkVertexSink {
    @Shadow @Final private ByteBufferBuilder buffer;
    @Shadow @Final private int vertexSize;
    @Shadow @Final private boolean entityFormat;
    @Shadow @Final private PrimitiveTopology primitiveTopology;
    @Shadow private boolean building;
    @Shadow private int vertices;
    @Shadow private int elementsToFill;
    @Shadow private long vertexPointer;
    @Shadow private static void putVec3f(long address,float x,float y,float z) { throw new AssertionError(); }
    @Shadow private static void putRgba(long address,int color) { throw new AssertionError(); }
    @Shadow private static void putPackedUv(long address,int uv) { throw new AssertionError(); }
    @Shadow private static void putNormals(long address,float x,float y,float z) { throw new AssertionError(); }
    @Unique private long trinium$cursor;
    @Unique private int trinium$remaining;
    @Override public boolean trinium$beginBulk(int count) {
        if(!building || !entityFormat || vertexSize!=36 || primitiveTopology!=PrimitiveTopology.QUADS || elementsToFill!=0 || trinium$remaining!=0 || count<=0 || count>1024 || vertices>16_777_215-count)return false;
        trinium$cursor=buffer.reserve(Math.multiplyExact(count,vertexSize));
        trinium$remaining=count;
        return true;
    }
    @Override public void trinium$endBulk(boolean completed) {
        int remaining=trinium$remaining;
        trinium$remaining=0;trinium$cursor=0;
        if(completed && remaining!=0)throw new IllegalStateException("Model did not consume its vertex reservation: "+remaining);
    }
    @Override public void trinium$entityVertex(float x,float y,float z,int color,float u,float v,int overlay,int light,float normalX,float normalY,float normalZ) {
        // beginBulk has already validated the complete ENTITY-format reservation. Keep
        // vanilla's own packing helpers (endianness, color, UV shorts, normal rounding).
        if(trinium$remaining<=0)throw new IllegalStateException("Entity vertex outside its reserved model");
        long pointer=trinium$cursor;
        trinium$cursor+=vertexSize;trinium$remaining--;
        vertices++;vertexPointer=pointer;
        putVec3f(pointer,x,y,z);
        putRgba(pointer+12,color);
        MemoryUtil.memPutFloat(pointer+16,u);
        MemoryUtil.memPutFloat(pointer+20,v);
        putPackedUv(pointer+24,overlay);
        putPackedUv(pointer+28,light);
        putNormals(pointer+32,normalX,normalY,normalZ);
    }
    @Redirect(method="beginVertex",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/vertex/ByteBufferBuilder;reserve(I)J"))
    private long trinium$reservedVertex(ByteBufferBuilder builder,int bytes) {
        if(trinium$remaining==0)return builder.reserve(bytes);
        if(bytes!=vertexSize)throw new IllegalStateException("Vertex format changed during the model");
        long pointer=trinium$cursor;
        trinium$cursor+=bytes;trinium$remaining--;
        return pointer;
    }
}

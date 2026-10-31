package fr.pierrecreeper.trinium.graphics;
/** A single contiguous reservation, consumed synchronously by a known vanilla model cube. */
public interface BulkVertexSink {
    boolean trinium$beginBulk(int count);
    void trinium$entityVertex(float x,float y,float z,int color,float u,float v,int overlay,int light,float normalX,float normalY,float normalZ);
    void trinium$endBulk(boolean completed);
}

package fr.pierrecreeper.trinium.graphics;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/** Reused only for the exact vanilla Cube.compile full-vertex contract, on one thread. */
final class EntityModelConsumer implements VertexConsumer {
    private BulkVertexSink sink;
    private TextureAtlasSprite sprite;
    boolean active() { return sink!=null; }
    void begin(BulkVertexSink sink,TextureAtlasSprite sprite) { this.sink=sink;this.sprite=sprite; }
    void clear() { sink=null;sprite=null; }
    @Override public void addVertex(float x,float y,float z,int color,float u,float v,int overlay,int light,float nx,float ny,float nz) {
        if(sprite!=null) {u=sprite.getU(u);v=sprite.getV(v);}
        sink.trinium$entityVertex(x,y,z,color,u,v,overlay,light,nx,ny,nz);
    }
    private static UnsupportedOperationException partial() { return new UnsupportedOperationException("Vanilla cube emitted a partial vertex"); }
    @Override public VertexConsumer addVertex(float x,float y,float z) {throw partial();}
    @Override public VertexConsumer setColor(int r,int g,int b,int a) {throw partial();}
    @Override public VertexConsumer setColor(int color) {throw partial();}
    @Override public VertexConsumer setUv(float u,float v) {throw partial();}
    @Override public VertexConsumer setUv1(int u,int v) {throw partial();}
    @Override public VertexConsumer setUv2(int u,int v) {throw partial();}
    @Override public VertexConsumer setUv3(float u,float v) {throw partial();}
    @Override public VertexConsumer setNormal(float x,float y,float z) {throw partial();}
    @Override public VertexConsumer setLineWidth(float width) {throw partial();}
}

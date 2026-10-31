package fr.pierrecreeper.trinium.graphics;

import com.mojang.blaze3d.vertex.*;
import fr.pierrecreeper.trinium.Trinium;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SpriteCoordinateExpander;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/** Keep Minecraft's transforms/UVs/normals exactly, but reserve a cube's storage once. */
public final class ModelBatcher {
    private static final boolean DISABLED=Boolean.getBoolean("trinium.disableModelBatch");
    private static final boolean DIRECT_DISABLED=!Boolean.getBoolean("trinium.experimentalEntityVertexBatch")
        || Boolean.getBoolean("trinium.disableEntityVertexBatch");
    private static final class WriterHolder {
        static final ThreadLocal<EntityModelConsumer> LOCAL=ThreadLocal.withInitial(EntityModelConsumer::new);
    }
    public static long cubes, vertices;
    public static long directCubes,directVertices;
    private ModelBatcher() { }
    public static void compile(ModelPart.Cube cube,PoseStack.Pose pose,VertexConsumer consumer,int light,int overlay,int color) {
        Compiler.compile(cube,pose,consumer,light,overlay,color);
    }
    // Load the implementation only when Minecraft actually submits a model.
    // Diagnostic counters remain readable from a standalone native/CLI process.
    private static final class Compiler {
    private static void compile(ModelPart.Cube cube,PoseStack.Pose pose,VertexConsumer consumer,int light,int overlay,int color) {
        VertexConsumer raw=consumer;
        // Only unwrap known, pure vanilla UV adapters. Arbitrary mod consumers stay vanilla.
        while(raw instanceof SpriteCoordinateExpander sprite && sprite.mapping().getClass()==TextureAtlasSprite.class)
            raw=sprite.delegate();
        if(DISABLED || !Trinium.active() || cube.getClass()!=ModelPart.Cube.class || raw.getClass()!=BufferBuilder.class || !(raw instanceof BulkVertexSink sink)) {
            cube.compile(pose,consumer,light,overlay,color);return;
        }
        int count=0;
        for(var polygon:cube.polygons)count+=polygon.vertices().length;
        if(!sink.trinium$beginBulk(count)) {cube.compile(pose,consumer,light,overlay,color);return;}
        EntityModelConsumer writer=null;
        boolean done=false;
        try {
            // Only known pure vanilla consumers can take the direct ENTITY path. Unknown
            // wrappers keep receiving every original call, including any custom side effects.
            if(!DIRECT_DISABLED && pose.getClass()==PoseStack.Pose.class && (consumer==raw || consumer instanceof SpriteCoordinateExpander sprite && sprite.delegate()==raw)) {
                EntityModelConsumer candidate=WriterHolder.LOCAL.get();
                if(!candidate.active()) {
                    writer=candidate;
                    writer.begin(sink,consumer instanceof SpriteCoordinateExpander sprite ? (TextureAtlasSprite)sprite.mapping() : null);
                }
            }
            cube.compile(pose,writer==null ? consumer : writer,light,overlay,color);
            done=true;cubes++;vertices+=count;
            if(writer!=null) {directCubes++;directVertices+=count;}
        }
        finally {
            if(writer!=null)writer.clear();
            sink.trinium$endBulk(done);
        }
    }
    }
}

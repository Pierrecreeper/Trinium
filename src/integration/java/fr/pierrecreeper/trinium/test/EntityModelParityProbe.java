package fr.pierrecreeper.trinium.probe;

import com.mojang.blaze3d.vertex.*;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import fr.pierrecreeper.trinium.graphics.ModelBatcher;
import fr.pierrecreeper.trinium.graphics.BulkVertexSink;
import java.lang.reflect.Field;
import java.util.EnumSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SpriteCoordinateExpander;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.core.Direction;

/** Byte oracle against unmodified Cube.compile/BufferBuilder, including safe fallbacks. */
public final class EntityModelParityProbe {
    private static int checks;
    private static void check(boolean condition,String text) {
        if(!condition)throw new AssertionError(text);
        checks++;
    }
    private static ModelPart.Cube cube() {
        return new ModelPart.Cube(3,11,-5.25f,1.5f,-7,10,3,15,.1f,.3f,.2f,true,64,64,EnumSet.allOf(Direction.class));
    }
    public static void run() throws ReflectiveOperationException {
        checks=0;
        TextureAtlasSprite sprite=Minecraft.getInstance().getAtlasManager().get(Sheets.ENDER_CHEST_LOCATION);
        check(sprite.getClass()==TextureAtlasSprite.class,"real vanilla chest sprite");
        try(var memory=new ByteBufferBuilder(128)) {
            var buffer=new BufferBuilder(memory,PrimitiveTopology.QUADS,DefaultVertexFormat.ENTITY);
            BulkVertexSink sink=(BulkVertexSink)buffer;
            for(int count:new int[]{-1,0,1025,Integer.MAX_VALUE})check(!sink.trinium$beginBulk(count),"invalid reservation rejected "+count);
            try {
                sink.trinium$entityVertex(0,0,0,0,0,0,0,0,0,0,0);
                throw new AssertionError("unreserved entity write accepted");
            } catch(IllegalStateException expected) {checks++;}
        }
        long before=ModelBatcher.directCubes;
        for(int seed=0;seed<12;seed++)for(int variant=0;variant<5;variant++) {
            byte[] vanilla=build(false,variant,seed,sprite),optimized=build(true,variant,seed,sprite);
            check(vanilla.length==optimized.length,"entity byte length "+seed+"/"+variant);
            // Byte 35 is unspecified padding in vanilla's 36-byte ENTITY layout.
            for(int i=0;i<vanilla.length;i++)if(i%36!=35 && vanilla[i]!=optimized[i])
                throw new AssertionError("entity byte divergence seed="+seed+" variant="+variant+" offset="+i);
            checks++;
        }
        check(ModelBatcher.directCubes-before==(Boolean.getBoolean("trinium.disableEntityVertexBatch")?0:24),"direct writer activated only for known consumers");
        check(error(false,false).equals(error(true,false)),"pending vertex retains vanilla validation");
        check(error(false,true).equals(error(true,true)),"vertex limit retains vanilla validation");
        // A rejected call must not poison the reused writer or a subsequent independent buffer.
        byte[] first=build(false,1,2,sprite),next=build(true,1,2,sprite);
        check(first.length==next.length,"writer usable after rejected buffer");
        for(int i=0;i<first.length;i++)if(i%36!=35)check(first[i]==next[i],"writer recovery bytes");
        System.out.println("TRINIUM_ENTITY_MODEL_PARITY_OK "+checks+" checks; sprite/nested/custom/lines/pending/limit/recovery; directCubes="+(ModelBatcher.directCubes-before));
    }
    private static byte[] build(boolean optimized,int variant,int seed,TextureAtlasSprite sprite) {
        var pose=new PoseStack();
        pose.translate(.125*seed,63.75,-18.25);
        pose.scale(seed%2==0?.7f:-1.3f,1.2f,.45f);
        pose.mulPose(new org.joml.Matrix4f().rotationXYZ(.11f*seed,.23f,-.31f));
        try(var memory=new ByteBufferBuilder(128)) {
            var buffer=new BufferBuilder(memory,variant==4?PrimitiveTopology.LINES:PrimitiveTopology.QUADS,DefaultVertexFormat.ENTITY);
            for(int i=0;i<4;i++)buffer.addVertex(i,-2,3,0x719a42ef,.13f,.79f,0x12345678,0x9abcdef0,-.5f,.3f,1.2f);
            VertexConsumer consumer=buffer;
            int[] calls={0};
            if(variant==1)consumer=new SpriteCoordinateExpander(buffer,sprite);
            if(variant==2)consumer=new SpriteCoordinateExpander(new SpriteCoordinateExpander(buffer,sprite),sprite);
            if(variant==3)consumer=new SpriteCoordinateExpander(buffer,new UvMapping() {
                public float getU(float u){calls[0]++;return u*.6f+.14f;}
                public float getV(float v){calls[0]++;return v*.3f+.29f;}
            });
            if(optimized)ModelBatcher.compile(cube(),pose.last(),consumer,0x00f01234,0x00aa5678,0x837abd12);
            else cube().compile(pose.last(),consumer,0x00f01234,0x00aa5678,0x837abd12);
            if(variant==3)check(calls[0]==48,"custom mapping gets every original UV call");
            for(int i=0;i<4;i++)buffer.addVertex(i,8,-9,0x4f123456,.88f,.02f,0xabcd1234,0x5678ef90,.6f,-.3f,.1f);
            try(var mesh=buffer.buildOrThrow()) {
                var bytes=mesh.vertexBuffer();byte[] result=new byte[bytes.remaining()];bytes.get(result);return result;
            }
        }
    }
    private static String error(boolean optimized,boolean overflow) throws ReflectiveOperationException {
        try(var memory=new ByteBufferBuilder(128)) {
            var buffer=new BufferBuilder(memory,PrimitiveTopology.QUADS,DefaultVertexFormat.ENTITY);
            if(overflow) {
                Field vertices=BufferBuilder.class.getDeclaredField("vertices");vertices.setAccessible(true);vertices.setInt(buffer,16_777_215);
            } else buffer.addVertex(1,2,3);
            try {
                if(optimized)ModelBatcher.compile(cube(),new PoseStack().last(),buffer,0,0,0);
                else cube().compile(new PoseStack().last(),buffer,0,0,0);
                throw new AssertionError("invalid buffer unexpectedly accepted");
            } catch(IllegalStateException expected) { return expected.getClass().getName()+":"+expected.getMessage(); }
        }
    }
}

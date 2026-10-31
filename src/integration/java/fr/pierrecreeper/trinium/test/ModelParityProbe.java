package fr.pierrecreeper.trinium.probe;

import com.mojang.blaze3d.vertex.*;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import fr.pierrecreeper.trinium.graphics.ModelBatcher;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.Direction;
import org.joml.Quaternionf;
import java.util.*;

/** Real transformed BufferBuilder, including growth and vertices around each batch. */
public final class ModelParityProbe {
    public static void run() {
        long before=ModelBatcher.cubes;
        for(int test=0;test<64;test++) {
            Set<Direction> faces=EnumSet.noneOf(Direction.class);
            for(Direction d:Direction.values())if((test&(1<<d.ordinal()))!=0)faces.add(d);
            var cube=new ModelPart.Cube(7,13,-3.5f,2,1,8,11,4,.1f,.2f,.3f,(test&1)!=0,64,64,faces);
            var pose=new PoseStack();
            pose.translate(.123,456.7,-89.2);pose.scale(.7f,1.3f,-.6f);
            pose.mulPose(new org.joml.Matrix4f().rotationXYZ(.1f*test,.27f,-.3f));
            byte[][] results=new byte[2][];
            for(int mode=0;mode<2;mode++)try(var memory=new ByteBufferBuilder(128)) {
                var buffer=new BufferBuilder(memory,PrimitiveTopology.QUADS,DefaultVertexFormat.ENTITY);
                for(int i=0;i<4;i++)buffer.addVertex(i,2,3,0xff345678,.5f,.3f,1234,5678,0,1,0);
                for(int i=0;i<5;i++) {
                    if(mode==0)cube.compile(pose.last(),buffer,0x00f000f0,0x000a000a,0x9a123456);
                    else ModelBatcher.compile(cube,pose.last(),buffer,0x00f000f0,0x000a000a,0x9a123456);
                }
                for(int i=0;i<4;i++)buffer.addVertex(-i,8,9,0x12345678,.8f,.9f,4321,8765,1,0,0);
                try(var mesh=buffer.buildOrThrow()) {
                    var data=mesh.vertexBuffer();results[mode]=new byte[data.remaining()];data.get(results[mode]);
                }
            }
            if(results[0].length!=results[1].length)throw new AssertionError("Model length "+test);
            for(int i=0;i<results[0].length;i++)if(i%36!=35 && results[0][i]!=results[1][i])throw new AssertionError("Model bytes case="+test+" offset="+i);
        }
        if(ModelBatcher.cubes-before!=315)throw new AssertionError("Bulk mixin inactive "+(ModelBatcher.cubes-before));
        System.out.println("TRINIUM_MODEL_PARITY_OK 64 face/mirror/transform/growth cases, 315 batches");
    }
}

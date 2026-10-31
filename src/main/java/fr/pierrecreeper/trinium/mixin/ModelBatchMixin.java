package fr.pierrecreeper.trinium.mixin;
import com.mojang.blaze3d.vertex.*;
import fr.pierrecreeper.trinium.graphics.ModelBatcher;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
@Mixin(value=ModelPart.class,remap=false)
public abstract class ModelBatchMixin {
    @Redirect(method="compile",at=@At(value="INVOKE",target="Lnet/minecraft/client/model/geom/ModelPart$Cube;compile(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"))
    private void trinium$batchCube(ModelPart.Cube cube,PoseStack.Pose pose,VertexConsumer consumer,int light,int overlay,int color) {
        ModelBatcher.compile(cube,pose,consumer,light,overlay,color);
    }
}

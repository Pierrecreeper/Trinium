// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import fr.pierrecreeper.trinium.graphics.ChestFrustumCulling;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Keep the frustum inside this one main-camera extraction; no world/pass cache. */
@Mixin(value=LevelExtractor.class,remap=false)
public abstract class ChestFrustumCullingMixin {
    @Unique private Frustum trinium$chestFrustum;
    @Unique private boolean trinium$chestFrustumChecked,trinium$chestFrustumFinite;

    @WrapMethod(method="extractVisibleBlockEntities")
    private void trinium$chestScope(Camera camera,float partialTick,LevelRenderState state,Operation<Void> original) {
        Frustum previous=trinium$chestFrustum;
        boolean checked=trinium$chestFrustumChecked,finite=trinium$chestFrustumFinite;
        trinium$chestFrustum=ChestFrustumCulling.enabled() && Float.isFinite(partialTick) && camera.getCapturedFrustum()==null
            ? camera.getCullFrustum() : null;
        trinium$chestFrustumChecked=false;trinium$chestFrustumFinite=false;
        try {original.call(camera,partialTick,state);}
        finally {
            trinium$chestFrustum=previous;
            trinium$chestFrustumChecked=checked;trinium$chestFrustumFinite=finite;
        }
    }

    @WrapOperation(method="extractVisibleBlockEntities",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;tryExtractRenderState(Lnet/minecraft/world/level/block/entity/BlockEntity;FLnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;Z)Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;"))
    private BlockEntityRenderState trinium$skipHiddenChest(BlockEntityRenderDispatcher dispatcher,BlockEntity entity,
        float partialTick,ModelFeatureRenderer.CrumblingOverlay overlay,boolean offScreen,Operation<BlockEntityRenderState> original) {
        Frustum frustum=trinium$chestFrustum;
        if(frustum!=null && ChestFrustumCulling.supported(entity,dispatcher,offScreen)) {
            if(!trinium$chestFrustumChecked) {
                trinium$chestFrustumChecked=true;
                trinium$chestFrustumFinite=ChestFrustumCulling.finite(frustum);
            }
            if(trinium$chestFrustumFinite && ChestFrustumCulling.outside(frustum,entity.getBlockPos()))return null;
        }
        return original.call(dispatcher,entity,partialTick,overlay,offScreen);
    }
}

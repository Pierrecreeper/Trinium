// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.test;

import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import fr.pierrecreeper.trinium.probe.ChestCullingOracle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Three renders of one frozen extraction, with no intervening tick or animation. */
@Mixin(value=LevelRenderer.class, remap=false)
public abstract class ChestCullingParityMixin {
    @Shadow @Final private LevelRenderState levelRenderState;
    @Unique private boolean trinium$cullingGuard;

    @Inject(method="render", at=@At("HEAD"), cancellable=true)
    private void trinium$cullingParity(GraphicsResourceAllocator allocator, boolean outline,
            CameraRenderState camera, GpuBufferSlice fog, Vector4f fogColor,
            boolean sky, boolean post, CallbackInfo callback) {
        if (trinium$cullingGuard || !ChestCullingOracle.ready(Minecraft.getInstance())) return;
        trinium$cullingGuard=true;
        try {
            ChestCullingOracle.renderView(Minecraft.getInstance(), levelRenderState,
                () -> ((LevelRenderer)(Object)this).render(allocator, outline, camera, fog, fogColor, sky, post));
        } finally {
            trinium$cullingGuard=false;
        }
        callback.cancel();
    }
}

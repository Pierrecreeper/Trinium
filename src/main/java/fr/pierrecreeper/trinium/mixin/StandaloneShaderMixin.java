// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import fr.pierrecreeper.trinium.shaders.StandaloneShaders;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GameRenderer.class, remap = false)
public abstract class StandaloneShaderMixin {
    @Shadow @Final private RenderTarget mainRenderTarget;
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;applyPostEffects()V", shift = At.Shift.AFTER))
    private void trinium$shadersBeforeHud(CallbackInfo ci) { StandaloneShaders.instance().render(mainRenderTarget); }
    @Inject(method = "close", at = @At("HEAD"))
    private void trinium$closeShaders(CallbackInfo ci) { StandaloneShaders.instance().close(); }
}

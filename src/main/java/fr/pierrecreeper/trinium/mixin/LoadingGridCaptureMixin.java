// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import fr.pierrecreeper.trinium.graphics.LoadingGridBatch;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = GuiGraphicsExtractor.class, remap = false)
public abstract class LoadingGridCaptureMixin {
    @WrapOperation(method = "innerFill", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/client/renderer/state/gui/GuiRenderState;addGuiElement(Lnet/minecraft/client/renderer/state/gui/GuiElementRenderState;)V"))
    private void trinium$capture(GuiRenderState state, GuiElementRenderState element, Operation<Void> original) {
        if (!LoadingGridBatch.capture((GuiGraphicsExtractor)(Object)this, state, element))
            original.call(state, element);
    }
}

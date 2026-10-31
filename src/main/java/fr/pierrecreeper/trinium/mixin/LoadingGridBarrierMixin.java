// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.mixin;

import fr.pierrecreeper.trinium.graphics.LoadingGridBatch;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Flush only an active grid targeting this state before another submission or layer. */
@Mixin(value = GuiRenderState.class, remap = false)
public abstract class LoadingGridBarrierMixin {
    @Inject(method = {
        "nextStratum", "up", "blurBeforeThisStratum", "addItem", "addText",
        "addGuiElement", "addPicturesInPictureState", "addBlitToCurrentLayer",
        "addGlyphToCurrentLayer"
    }, at = @At("HEAD"))
    private void trinium$flushPendingGrid(CallbackInfo ci) {
        LoadingGridBatch.flushBeforeOtherSubmission((GuiRenderState)(Object)this);
    }
}

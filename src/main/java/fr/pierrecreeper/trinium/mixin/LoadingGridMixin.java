// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import fr.pierrecreeper.trinium.Trinium;
import fr.pierrecreeper.trinium.graphics.LoadingGridBatch;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.server.level.progress.ChunkLoadStatusView;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = LevelLoadingScreen.class, remap = false)
public abstract class LoadingGridMixin {
    @WrapMethod(method = "extractChunksForRendering")
    private static void trinium$grid(GuiGraphicsExtractor graphics, int x, int y, int size,
                                    int gap, ChunkLoadStatusView status, Operation<Void> original) {
        if (!Trinium.active() || Boolean.getBoolean("trinium.disableLoadingGrid")
                || SharedConstants.DEBUG_RENDER_UI_LAYERING_RECTANGLES) {
            original.call(graphics, x, y, size, gap, status);
            return;
        }
        try (var ignored = new LoadingGridBatch(graphics)) {
            original.call(graphics, x, y, size, gap, status);
        }
    }
}

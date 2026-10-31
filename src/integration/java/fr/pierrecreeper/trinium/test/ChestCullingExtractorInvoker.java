// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.test;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Test harness only. Exact Minecraft 26.3 signature verified against the client. */
@Mixin(value=LevelExtractor.class, remap=false)
public interface ChestCullingExtractorInvoker {
    @Invoker("extractVisibleBlockEntities")
    void trinium$extractVisibleBlockEntities(Camera camera, float partialTicks, LevelRenderState state);
}

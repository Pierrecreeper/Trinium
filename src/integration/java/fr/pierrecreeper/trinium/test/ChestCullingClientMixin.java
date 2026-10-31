// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.test;

import fr.pierrecreeper.trinium.probe.ChestCullingOracle;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Select instead of ClientTestMixin: that driver's camera rotation would conflict. */
@Mixin(value=Minecraft.class, remap=false)
public abstract class ChestCullingClientMixin {
    @Inject(method="onGameLoadFinished", at=@At("HEAD"))
    private void trinium$cullingSettings(CallbackInfo callback) {
        ChestCullingOracle.configure((Minecraft)(Object)this);
    }

    @Inject(method="tick", at=@At("TAIL"))
    private void trinium$cullingTick(CallbackInfo callback) {
        ChestCullingOracle.tick((Minecraft)(Object)this);
    }
}

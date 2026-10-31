package fr.pierrecreeper.trinium.mixin;

import fr.pierrecreeper.trinium.*;
import net.minecraft.util.SimpleBitStorage;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SimpleBitStorage.class, remap = false)
public abstract class PaletteMixin {
    @Shadow @Final private long[] data;
    @Shadow @Final private int bits;
    @Shadow @Final private int size;

    @Inject(method = "unpack", at = @At("HEAD"), cancellable = true)
    private void trinium$unpack(int[] out, CallbackInfo ci) {
        if (Trinium.active() && PaletteDecoder.unpack(data, bits, size, out)) {
            Trinium.recordPalette();
            ci.cancel();
        }
    }
}

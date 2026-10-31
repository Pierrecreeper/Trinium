package fr.pierrecreeper.trinium.mixin;

import fr.pierrecreeper.trinium.*;
import java.util.BitSet;
import net.minecraft.client.renderer.chunk.VisGraph;
import net.minecraft.client.renderer.chunk.VisibilitySet;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = VisGraph.class, remap = false)
public abstract class VisGraphMixin {
    @Shadow @Final private BitSet bitSet;
    @Shadow private int empty;

    @Inject(method = "resolve", at = @At("HEAD"), cancellable = true)
    private void trinium$resolve(CallbackInfoReturnable<VisibilitySet> cir) {
        if (!Trinium.active() || empty == 0 || 4096 - empty < 256) return;
        boolean nativeCall = NativeBridge.available() && Trinium.mode() != Trinium.Mode.JAVA;
        long mask;
        if (nativeCall) {
            try { mask = NativeBridge.resolve(bitSet.toLongArray()); }
            catch (RuntimeException | LinkageError error) {
                NativeBridge.disable(error);
                mask = VisibilitySolver.resolve(bitSet);
                nativeCall = false;
            }
        } else mask = VisibilitySolver.resolve(bitSet);
        VisibilitySet result = new VisibilitySet();
        if (mask == VisibilitySolver.ALL) result.setAll(true);
        else {
            Direction[] faces = Direction.values();
            // Verified against all 36 real Minecraft direction pairs in GameContractTest.
            for (int a = 0; a < 6; a++) for (int b = a; b < 6; b++)
                if ((mask & (1L << (a * 6 + b))) != 0) result.set(faces[a], faces[b], true);
        }
        Trinium.recordSection(nativeCall);
        cir.setReturnValue(result);
    }
}

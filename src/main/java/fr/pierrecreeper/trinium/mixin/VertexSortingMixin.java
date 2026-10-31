package fr.pierrecreeper.trinium.mixin;

import com.mojang.blaze3d.vertex.*;
import fr.pierrecreeper.trinium.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = VertexSorting.class, remap = false)
public interface VertexSortingMixin {
    @Inject(method = "lambda$byDistance$0", at = @At("HEAD"), cancellable = true)
    private static void trinium$sort(VertexSorting.DistanceFunction distance, CompactVectorArray vertices,
                                     CallbackInfoReturnable<int[]> cir) {
        if (!Trinium.active() || Trinium.mode() == Trinium.Mode.JAVA) return;
        int[] result = NativeSort.sort(distance, vertices);
        if (result != null) cir.setReturnValue(result);
    }
}

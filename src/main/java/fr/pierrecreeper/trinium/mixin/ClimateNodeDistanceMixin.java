// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.mixin;

import fr.pierrecreeper.trinium.graphics.ClimateDistanceMath;
import fr.pierrecreeper.trinium.Trinium;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A per-node immutable snapshot, with no global cache or change to tree traversal. */
@Mixin(targets="net.minecraft.world.level.biome.Climate$RTree$Node",remap=false)
public abstract class ClimateNodeDistanceMixin {
    @Shadow @Final protected Climate.Parameter[] parameterSpace;

    @Unique private long trinium$min0,trinium$max0,trinium$min1,trinium$max1;
    @Unique private long trinium$min2,trinium$max2,trinium$min3,trinium$max3;
    @Unique private long trinium$min4,trinium$max4,trinium$min5,trinium$max5;
    @Unique private long trinium$min6,trinium$max6;
    @Unique private boolean trinium$immutableBounds;

    @Inject(method="<init>",at=@At("RETURN"))
    private void trinium$snapshotClimateBounds(CallbackInfo ci) {
        if(!ClimateDistanceMath.ENABLED)return;
        // Custom node subclasses keep the original calculation, including any
        // mutations they make to their protected array after the base constructor.
        String type=getClass().getName();
        if(!type.equals("net.minecraft.world.level.biome.Climate$RTree$Leaf")
            && !type.equals("net.minecraft.world.level.biome.Climate$RTree$SubTree"))return;
        Climate.Parameter[] bounds=parameterSpace;
        if(bounds==null || bounds.length!=7)return;
        for(Climate.Parameter bound:bounds)if(bound==null)return;
        trinium$min0=bounds[0].min();trinium$max0=bounds[0].max();
        trinium$min1=bounds[1].min();trinium$max1=bounds[1].max();
        trinium$min2=bounds[2].min();trinium$max2=bounds[2].max();
        trinium$min3=bounds[3].min();trinium$max3=bounds[3].max();
        trinium$min4=bounds[4].min();trinium$max4=bounds[4].max();
        trinium$min5=bounds[5].min();trinium$max5=bounds[5].max();
        trinium$min6=bounds[6].min();trinium$max6=bounds[6].max();
        trinium$immutableBounds=true;
    }

    /**
     * @author Trinium contributors
     * @reason Avoid seven dependent record loads on the profiled biome-search path.
     * Addition order and signed-long overflow are identical to the reference loop.
     */
    @Overwrite
    protected long distance(long[] target) {
        if(ClimateDistanceMath.ENABLED && trinium$immutableBounds && Trinium.active()) {
            return ClimateDistanceMath.distance7(target,
                trinium$min0,trinium$max0,trinium$min1,trinium$max1,
                trinium$min2,trinium$max2,trinium$min3,trinium$max3,
                trinium$min4,trinium$max4,trinium$min5,trinium$max5,trinium$min6,trinium$max6);
        }
        // Also preserves vanilla's exceptions for malformed/custom parameter arrays.
        long distance=0;
        for(int axis=0;axis<7;axis++) {
            long delta=parameterSpace[axis].distance(target[axis]);
            distance+=delta*delta;
        }
        return distance;
    }
}

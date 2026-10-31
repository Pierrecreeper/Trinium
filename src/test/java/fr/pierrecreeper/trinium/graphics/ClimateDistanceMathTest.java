// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.graphics;

import java.util.SplittableRandom;
import net.minecraft.world.level.biome.Climate;

/** Compares the candidate kernel with the actual 26.3 interval implementation. */
public final class ClimateDistanceMathTest {
    public static void main(String[] args) {
        long checks=0;
        long[] extremes={Long.MIN_VALUE,Long.MIN_VALUE+1,-10000,-1,0,1,10000,Long.MAX_VALUE-1,Long.MAX_VALUE};
        // Include reversed intervals: the public record constructor permits them.
        for(long minimum:extremes)for(long maximum:extremes)for(long target:extremes) {
            verify(new Climate.Parameter(minimum,maximum),target);
            checks++;
        }
        SplittableRandom random=new SplittableRandom(0x5452494e49554dL);
        for(int i=0;i<250000;i++) {
            verify(new Climate.Parameter(random.nextLong(),random.nextLong()),random.nextLong());
            long minimum=random.nextLong(-20000,20001),maximum=random.nextLong(minimum,20002);
            Climate.Parameter interval=new Climate.Parameter(minimum,maximum);
            verify(interval,minimum);verify(interval,maximum);
            verify(interval,random.nextLong(-40000,40001));
            checks+=4;
        }
        System.out.println("Climate interval arithmetic: "+checks+" exact checks passed");
    }

    private static void verify(Climate.Parameter interval,long target) {
        long reference=interval.distance(target);
        reference*=reference;
        long actual=ClimateDistanceMath.squared(target,interval.min(),interval.max());
        if(actual!=reference)throw new AssertionError("Climate distance mismatch for "+interval+" at "+target);
    }
}

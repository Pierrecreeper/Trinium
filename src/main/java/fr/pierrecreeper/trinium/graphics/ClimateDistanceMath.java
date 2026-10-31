// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.graphics;

/** Exact integer interval math; deliberately independent of Minecraft bootstrap. */
public final class ClimateDistanceMath {
    /** Experimental until both CPU parity and complete world-creation measurements pass. */
    public static final boolean ENABLED=Boolean.getBoolean("trinium.experimentalClimateDistance");

    private ClimateDistanceMath() { }

    public static long distance7(long[] target,
        long min0,long max0,long min1,long max1,long min2,long max2,long min3,long max3,
        long min4,long max4,long min5,long max5,long min6,long max6) {
        long distance=squared(target[0],min0,max0);
        distance+=squared(target[1],min1,max1);
        distance+=squared(target[2],min2,max2);
        distance+=squared(target[3],min3,max3);
        distance+=squared(target[4],min4,max4);
        distance+=squared(target[5],min5,max5);
        distance+=squared(target[6],min6,max6);
        return distance;
    }

    public static long squared(long target,long minimum,long maximum) {
        // Subtract before comparing, exactly as Parameter.distance does. Comparing
        // target directly against an endpoint would change signed-overflow cases.
        long above=target-maximum;
        long below=minimum-target;
        long distance=above>0 ? above : Math.max(below,0);
        return distance*distance;
    }
}

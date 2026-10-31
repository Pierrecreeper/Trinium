package fr.pierrecreeper.trinium;

import com.mojang.blaze3d.vertex.*;
import java.util.*;

/** Indicative warmed microbenchmark, not a Minecraft FPS benchmark or JMH result. */
public final class GameBenchmark {
    private static volatile int blackhole;
    public static void main(String[] args) {
        NativeBridge.initialize();
        if (!NativeBridge.available()) throw new AssertionError(NativeBridge.status());
        SplittableRandom random = new SplittableRandom(7371);
        System.out.println("quads,vanilla_ns,rust_including_jni_ns,speedup");
        for (int size : new int[]{128,256,512,1024,2048,8192,32768}) {
            CompactVectorArray[] inputs = new CompactVectorArray[16];
            for (int k=0;k<inputs.length;k++) {
                inputs[k] = new CompactVectorArray(size);
                for(int i=0;i<size;i++) inputs[k].set(i,(float)random.nextDouble(-32,32),(float)random.nextDouble(-32,32),(float)random.nextDouble(-32,32));
            }
            VertexSorting.DistanceFunction distance = v -> v.distanceSquared(3.1f,1.5f,4.25f);
            VertexSorting vanilla = VertexSorting.byDistance(distance);
            int rounds = Math.max(100, 1048576 / size);
            for(int i=0;i<rounds;i++) {
                blackhole ^= vanilla.sort(inputs[i&15])[0];
                blackhole ^= NativeSort.sort(distance,inputs[i&15])[0];
            }
            long[] vt=new long[7],nt=new long[7];
            for(int pass=0;pass<7;pass++) {
                for(int order=0;order<2;order++) {
                    boolean nativeFirst=((order+pass)&1)==0;
                    long start=System.nanoTime();
                    for(int i=0;i<rounds;i++) blackhole ^= (nativeFirst ? NativeSort.sort(distance,inputs[i&15]) : vanilla.sort(inputs[i&15]))[0];
                    (nativeFirst?nt:vt)[pass]=(System.nanoTime()-start)/rounds;
                }
            }
            Arrays.sort(vt);Arrays.sort(nt);
            System.out.printf(Locale.ROOT,"%d,%d,%d,%.3f%n",size,vt[3],nt[3],(double)vt[3]/nt[3]);
        }
    }
}

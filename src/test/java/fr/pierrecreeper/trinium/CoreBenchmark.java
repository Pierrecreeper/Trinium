package fr.pierrecreeper.trinium;

import java.util.*;

/** Small synthetic microbenchmark, NOT JMH, NOT Minecraft, NOT Sodium. */
public final class CoreBenchmark {
    private static volatile long sink;
    private static double run(List<BitSet> data, boolean old, int rounds) {
        long v=0,start=System.nanoTime();
        if(old)for(int r=0;r<rounds;r++)for(BitSet b:data)v^=PreviousVisibilitySolver.resolve(b);
        else for(int r=0;r<rounds;r++)for(BitSet b:data)v^=VisibilitySolver.resolve(b);
        sink=v;
        return (double)(System.nanoTime()-start)/(rounds*data.size());
    }
    public static void main(String[] args){
        Map<String,List<BitSet>> groups=new LinkedHashMap<>();
        groups.put("structured",TestVectors.fixtures().stream().filter(b->b.cardinality()>=256 && b.cardinality()<4096).toList());
        for(int d=0;d<8;d++){
            List<BitSet> list=new ArrayList<>();SplittableRandom rng=new SplittableRandom(731+d);
            for(int i=0;i<32;i++)list.add(TestVectors.random(rng,d));groups.put("random-density-"+new int[]{2,8,20,40,60,80,95,99}[d],list);
        }
        List<BitSet> all=new ArrayList<>();groups.values().forEach(all::addAll);
        for(BitSet b:all)if(PreviousVisibilitySolver.resolve(b)!=VisibilitySolver.resolve(b))throw new AssertionError("Parity");
        // Both implementations are warmed with the same varied inputs before any measurements.
        for(int i=0;i<10;i++){run(all,(i&1)==0,20);run(all,(i&1)!=0,20);}
        System.out.println("category,previous_0.1.2_ns,current_0.2.0_ns,previous_divided_by_current");
        groups.put("combined-synthetic",all);
        for(var entry:groups.entrySet()){
            double[] old=new double[7],now=new double[7];
            for(int i=0;i<7;i++) {
                if((i&1)==0){old[i]=run(entry.getValue(),true,24);now[i]=run(entry.getValue(),false,24);}
                else{now[i]=run(entry.getValue(),false,24);old[i]=run(entry.getValue(),true,24);}
            }
            Arrays.sort(old);Arrays.sort(now);
            System.out.printf(Locale.ROOT,"%s,%.1f,%.1f,%.3f%n",entry.getKey(),old[3],now[3],old[3]/now[3]);
        }
        System.out.println("# CPU solve only; includes BitSet snapshot. No JNI, game integration, FPS or vanilla/Sodium comparison.");
    }
}

// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package net.minecraft.world.level.biome;

import com.mojang.datafixers.util.Pair;
import fr.pierrecreeper.trinium.graphics.ClimateDistanceMath;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;

/** Test-only package access to the real 26.3 R-tree; never included in the mod. */
public final class TriniumClimateTreeTest {
    private static long checks;
    private static volatile long sink;
    private static final long[] EXTREMES={Long.MIN_VALUE,Long.MIN_VALUE+1,-10000,-1,0,1,10000,Long.MAX_VALUE-1,Long.MAX_VALUE};

    private record Bounds(long min0,long max0,long min1,long max1,long min2,long max2,
        long min3,long max3,long min4,long max4,long min5,long max5,long min6,long max6) {
        Bounds(Climate.Parameter[] p) {
            this(p[0].min(),p[0].max(),p[1].min(),p[1].max(),p[2].min(),p[2].max(),
                p[3].min(),p[3].max(),p[4].min(),p[4].max(),p[5].min(),p[5].max(),p[6].min(),p[6].max());
        }
        long distance(long[] target) {
            return ClimateDistanceMath.distance7(target,min0,max0,min1,max1,min2,max2,
                min3,max3,min4,max4,min5,max5,min6,max6);
        }
    }

    public static void main(String[] args) throws Exception {
        // Building vanilla biome parameter lists does not create a world or window.
        List<Pair<Climate.ParameterPoint,Integer>> overworld=new ArrayList<>();
        new OverworldBiomeBuilder().addBiomes(pair -> overworld.add(Pair.of(pair.getFirst(),overworld.size())));
        List<Pair<Climate.ParameterPoint,Integer>> ties=new ArrayList<>();
        for(int i=0;i<64;i++) {
            Climate.Parameter axis=new Climate.Parameter((i/4-8)*1000,(i/4-7)*1000);
            ties.add(Pair.of(new Climate.ParameterPoint(axis,axis,axis,axis,axis,axis,0),i));
        }
        SplittableRandom random=new SplittableRandom(0x5452494e49554dL);
        List<Pair<Climate.ParameterPoint,Integer>> generated=new ArrayList<>();
        for(int i=0;i<512;i++) {
            Climate.Parameter[] p=new Climate.Parameter[6];
            for(int axis=0;axis<6;axis++) {
                long low=random.nextLong(-20000,20001);
                p[axis]=new Climate.Parameter(low,low+random.nextLong(5001));
            }
            generated.add(Pair.of(new Climate.ParameterPoint(p[0],p[1],p[2],p[3],p[4],p[5],random.nextLong(5001)),i));
        }
        verifyTree("vanilla-overworld",overworld,19);
        verifyTree("duplicate-interval-ties",ties,19);
        verifyTree("generated",generated,19);
        verifyTree("generated-wide",generated,8);
        System.out.println("Climate R-tree parity: "+checks+" exact checks passed; checksum="+sink);
    }

    private static void verifyTree(String name,List<Pair<Climate.ParameterPoint,Integer>> entries,int children) throws Exception {
        Climate.RTree<Integer> reference=Climate.RTree.create(entries,children);
        Climate.RTree<Integer> candidate=Climate.RTree.create(entries,children);
        IdentityHashMap<Climate.RTree.Node<Integer>,Bounds> snapshots=new IdentityHashMap<>();
        Field root=Climate.RTree.class.getDeclaredField("root");root.setAccessible(true);
        @SuppressWarnings("unchecked") Climate.RTree.Node<Integer> rootNode=(Climate.RTree.Node<Integer>)root.get(candidate);
        collect(rootNode,snapshots);
        List<Climate.RTree.Node<Integer>> nodes=new ArrayList<>(snapshots.keySet());
        SplittableRandom random=new SplittableRandom(0x1337+children);
        for(var node:nodes) {
            Bounds bounds=snapshots.get(node);
            for(int i=0;i<12;i++) {
                long[] query=new long[7];
                for(int axis=0;axis<7;axis++)query[axis]=i<9?EXTREMES[(i+axis)%9]:random.nextLong();
                equal(node.distance(query),bounds.distance(query),"node "+name);
            }
        }
        Climate.DistanceMetric<Integer> referenceMetric=Climate.RTree.Node::distance;
        // This map is only an oracle adapter. Production stores the fourteen
        // primitive bounds directly in each node and has no map on this path.
        Climate.DistanceMetric<Integer> candidateMetric=(node,target) -> snapshots.get(node).distance(target);
        List<Climate.TargetPoint> queries=new ArrayList<>();
        for(int i=0;i<8000;i++) {
            long[] q=new long[6];
            for(int axis=0;axis<6;axis++)q[axis]=i<729?EXTREMES[(i/(axis+1)+axis)%9]:random.nextLong(-30000,30001);
            queries.add(new Climate.TargetPoint(q[0],q[1],q[2],q[3],q[4],q[5]));
        }
        // Repeat in the reverse order to exercise the R-tree's per-thread lastResult
        // tie preference, including exact repeated targets and reset-free searches.
        for(int pass=0;pass<2;pass++)for(int i=0;i<queries.size();i++) {
            var query=queries.get(pass==0?i:queries.size()-1-i);
            Object expected=result(reference,query,referenceMetric);
            Object actual=result(candidate,query,candidateMetric);
            if(!Objects.equals(expected,actual))throw new AssertionError("Tree mismatch "+name+" at "+query+": "+expected+" / "+actual);
            checks++;
        }
        System.out.println(name+": entries="+entries.size()+", nodes="+nodes.size()+
            ", addedPrimitiveBytes="+(112L*nodes.size())+", addedReadyBooleanBytes="+nodes.size()+
            " (alignment excluded; runtime heap size still requires instrumentation)");
        if(name.equals("vanilla-overworld"))benchmark(nodes,snapshots);
    }

    private static Object result(Climate.RTree<Integer> tree,Climate.TargetPoint query,Climate.DistanceMetric<Integer> metric) {
        try {return tree.search(query,metric);}
        catch(RuntimeException exception) {return exception.getClass();}
    }

    private static void collect(Climate.RTree.Node<Integer> node,IdentityHashMap<Climate.RTree.Node<Integer>,Bounds> snapshots) throws Exception {
        snapshots.put(node,new Bounds(node.parameterSpace));
        try {
            Field children=node.getClass().getDeclaredField("children");children.setAccessible(true);
            for(Object child:(Object[])children.get(node)) {
                @SuppressWarnings("unchecked") Climate.RTree.Node<Integer> typed=(Climate.RTree.Node<Integer>)child;
                collect(typed,snapshots);
            }
        } catch(NoSuchFieldException leaf) { /* Vanilla leaf has no child array. */ }
    }

    private static void benchmark(List<Climate.RTree.Node<Integer>> list,IdentityHashMap<Climate.RTree.Node<Integer>,Bounds> snapshots) {
        @SuppressWarnings("unchecked") Climate.RTree.Node<Integer>[] nodes=list.toArray(new Climate.RTree.Node[0]);
        Bounds[] bounds=new Bounds[nodes.length];
        for(int i=0;i<nodes.length;i++)bounds[i]=snapshots.get(nodes[i]);
        long[][] targets=new long[1024][7];
        SplittableRandom random=new SplittableRandom(87123);
        for(long[] target:targets)for(int i=0;i<7;i++)target[i]=random.nextLong(-15000,15001);
        int warm=Integer.getInteger("trinium.climateTestWarmIterations",200000);
        int count=Integer.getInteger("trinium.climateTestIterations",1000000);
        int rounds=Integer.getInteger("trinium.climateTestRounds",8);
        for(int i=0;i<8;i++) {run(false,nodes,bounds,targets,warm);run(true,nodes,bounds,targets,warm);}
        System.out.println("kernel-only ABBA; direct vanilla Node.distance vs the production distance7 helper; not world-creation/FPS timings");
        for(int i=0;i<rounds;i++) {
            boolean packed=i%4==1 || i%4==2;
            long start=System.nanoTime();
            long checksum=run(packed,nodes,bounds,targets,count);
            long elapsed=System.nanoTime()-start;
            System.out.println("kernel,"+i+","+(packed?"packed":"vanilla")+","+elapsed+","+checksum);
        }
    }

    private static long run(boolean packed,Climate.RTree.Node<Integer>[] nodes,Bounds[] bounds,long[][] targets,int count) {
        long sum=0;
        for(int i=0;i<count;i++) {
            int node=i%nodes.length;
            sum+=packed?bounds[node].distance(targets[i&1023]):nodes[node].distance(targets[i&1023]);
        }
        sink=sum;return sum;
    }

    private static void equal(long expected,long actual,String context) {
        if(expected!=actual)throw new AssertionError(context+": "+expected+" / "+actual);
        checks++;
    }
}

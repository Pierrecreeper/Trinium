// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.probe;

import fr.pierrecreeper.trinium.Trinium;
import fr.pierrecreeper.trinium.graphics.ClimateDistanceMath;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SplittableRandom;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;

/** Correctness/retention oracle for the actual Mixin-transformed node, never a FPS test. */
public final class ClimateDistanceProbe {
    private static final long[] EXTREMES={Long.MIN_VALUE,Long.MIN_VALUE+1,-10000,-1,0,1,10000,Long.MAX_VALUE-1,Long.MAX_VALUE};
    private ClimateDistanceProbe() { }

    public static Map<String,Object> run() throws Exception {
        if(Minecraft.getInstance().level!=null || Minecraft.getInstance().getSingleplayerServer()!=null)
            throw new IllegalStateException("Run the climate oracle before loading a world; it temporarily tests the OFF guard");
        if(!ClimateDistanceMath.ENABLED || !Trinium.active())
            throw new IllegalStateException("Climate oracle requires active Trinium and -Dtrinium.experimentalClimateDistance=true");
        ClassLoader loader=Climate.class.getClassLoader();
        Class<?> nodeType=Class.forName("net.minecraft.world.level.biome.Climate$RTree$Node",true,loader);
        Class<?> metricType=Class.forName("net.minecraft.world.level.biome.Climate$DistanceMetric",true,loader);
        Class<?> treeType=Class.forName("net.minecraft.world.level.biome.Climate$RTree",true,loader);
        Field parameterSpace=field(nodeType,"parameterSpace");
        Field ready=field(nodeType,"trinium$immutableBounds");
        Method distance=method(nodeType,"distance",long[].class);
        Method findWithMetric=method(Climate.ParameterList.class,"findValueIndex",Climate.TargetPoint.class,metricType);
        Field index=field(Climate.ParameterList.class,"index"),root=field(treeType,"root");
        // The premain copy lives in the system loader, independently of Fabric's loader.
        Class<?> agent=Class.forName("fr.pierrecreeper.trinium.probe.ClimateObjectSizeAgent",true,ClassLoader.getSystemClassLoader());
        Method sizeOf=agent.getMethod("sizeOf",Object.class);
        Object referenceMetric=Proxy.newProxyInstance(loader,new Class<?>[]{metricType},(proxy,call,args) -> {
            if(call.getName().equals("distance"))return reference((Climate.Parameter[])parameterSpace.get(args[0]),(long[])args[1]);
            if(call.getName().equals("toString"))return "Trinium reference climate metric";
            if(call.getName().equals("hashCode"))return System.identityHashCode(proxy);
            if(call.getName().equals("equals"))return proxy==args[0];
            throw new UnsupportedOperationException(call.toString());
        });
        long nodeChecks=0,treeChecks=0,totalNodes=0,totalBytes=0,guardChecks=0;
        Map<String,Object> presets=new LinkedHashMap<>();
        for(var preset:MultiNoiseBiomeSourceParameterList.knownPresets().entrySet()) {
            var candidate=preset.getValue();
            var baseline=new Climate.ParameterList<>(candidate.values());
            List<Object> nodes=new ArrayList<>();
            collect(root.get(index.get(candidate)),nodes,new IdentityHashMap<>());
            Map<String,Long> classCounts=new LinkedHashMap<>(),classSizes=new LinkedHashMap<>();
            SplittableRandom random=new SplittableRandom(0x5452494e49554dL);
            long bytes=0;
            for(Object node:nodes) {
                if(!ready.getBoolean(node))throw new AssertionError("Known vanilla node lacks initialized primitive bounds");
                Climate.Parameter[] bounds=(Climate.Parameter[])parameterSpace.get(node);
                for(int axis=0;axis<7;axis++) {
                    if(field(nodeType,"trinium$min"+axis).getLong(node)!=bounds[axis].min()
                        || field(nodeType,"trinium$max"+axis).getLong(node)!=bounds[axis].max())
                        throw new AssertionError("Transformed node snapshot differs at axis "+axis);
                }
                long shallow=((Number)sizeOf.invoke(null,node)).longValue();bytes+=shallow;
                String type=node.getClass().getSimpleName();classCounts.merge(type,1L,Long::sum);classSizes.put(type,shallow);
                for(int sample=0;sample<12;sample++) {
                    long[] target=new long[7];
                    for(int axis=0;axis<7;axis++)target[axis]=sample<9?EXTREMES[(sample+axis)%9]:random.nextLong();
                    long expected=reference(bounds,target),actual=((Number)distance.invoke(node,(Object)target)).longValue();
                    if(expected!=actual)throw new AssertionError("Actual transformed Node.distance changed a long result");
                    nodeChecks++;
                }
            }
            guardChecks+=verifyOffGuard(nodes.getFirst(),nodeType,parameterSpace,ready,distance);
            List<Climate.TargetPoint> queries=new ArrayList<>();
            for(int i=0;i<4096;i++) {
                long[] q=new long[6];
                for(int axis=0;axis<6;axis++)q[axis]=i<729?EXTREMES[(i/(axis+1)+axis)%9]:random.nextLong(-30000,30001);
                queries.add(new Climate.TargetPoint(q[0],q[1],q[2],q[3],q[4],q[5]));
            }
            for(int pass=0;pass<2;pass++)for(int i=0;i<queries.size();i++) {
                Climate.TargetPoint query=queries.get(pass==0?i:queries.size()-1-i);
                Object expected=findReference(findWithMetric,baseline,query,referenceMetric);
                Object actual=findCandidate(candidate,query);
                if(!Objects.equals(expected,actual))throw new AssertionError("Actual R-tree selected a different biome at "+query);
                if((pass==0?i:queries.size()-1-i)>=729 && expected instanceof Class<?>)
                    throw new AssertionError("Ordinary bounded climate query unexpectedly failed: "+expected);
                treeChecks++;
            }
            Map<String,Object> result=new LinkedHashMap<>();
            result.put("entries",candidate.values().size());result.put("nodes",nodes.size());
            result.put("classCounts",classCounts);result.put("measuredNodeShallowSizeBytes",classSizes);
            result.put("measuredRetainedNodeShallowBytes",bytes);
            presets.put(preset.getKey().id().toString(),result);totalNodes+=nodes.size();totalBytes+=bytes;
        }
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("passed",true);result.put("actualTransformedNodeChecks",nodeChecks);
        result.put("actualRTreeChecks",treeChecks);result.put("totalNodes",totalNodes);
        result.put("fastPathAndOffGuardChecks",guardChecks);
        result.put("measuredRetainedNodeShallowBytes",totalBytes);result.put("presets",presets);
        result.put("jvmLayout",jvmLayout());
        result.put("note","CPU parity and actual object-size measurement only; no world-creation or FPS gain is inferred.");
        return result;
    }

    private static int verifyOffGuard(Object node,Class<?> nodeType,Field parameterSpace,Field ready,Method distance) throws Exception {
        Field mode=field(Trinium.class,"mode"),minimum=field(nodeType,"trinium$min0"),maximum=field(nodeType,"trinium$max0");
        Object previousMode=mode.get(null);
        long previousMinimum=minimum.getLong(node),previousMaximum=maximum.getLong(node);
        boolean previousReady=ready.getBoolean(node);
        long[] target=new long[7];
        long expected=reference((Climate.Parameter[])parameterSpace.get(node),target);
        try {
            // This is a newly constructed oracle-only node, never a live-world node.
            minimum.setLong(node,123456789);maximum.setLong(node,123456789);
            long perturbed=((Number)distance.invoke(node,(Object)target)).longValue();
            if(perturbed==expected)throw new AssertionError("No evidence that the transformed fast path actually executed");
            mode.set(null,Trinium.Mode.OFF);
            if(Trinium.active())throw new AssertionError("OFF guard did not become inactive");
            long off=((Number)distance.invoke(node,(Object)target)).longValue();
            if(off!=expected)throw new AssertionError("OFF mode read the deliberately perturbed cache");
            mode.set(null,previousMode);
            ready.setBoolean(node,false);
            long unready=((Number)distance.invoke(node,(Object)target)).longValue();
            if(unready!=expected)throw new AssertionError("Uninitialized-cache fallback read the perturbed cache");
            return 3;
        } finally {
            minimum.setLong(node,previousMinimum);maximum.setLong(node,previousMaximum);
            ready.setBoolean(node,previousReady);mode.set(null,previousMode);
        }
    }

    /** Run in a bare Java process using the original game JAR and the same size agent. */
    public static Map<String,Object> measureBaselineLayout() throws Exception {
        Class<?> nodeType=Class.forName("net.minecraft.world.level.biome.Climate$RTree$Node");
        try {nodeType.getDeclaredField("trinium$immutableBounds");throw new AssertionError("Baseline unexpectedly contains the mixin");}
        catch(NoSuchFieldException expected) { }
        Field index=field(Climate.ParameterList.class,"index");
        Field root=field(Class.forName("net.minecraft.world.level.biome.Climate$RTree"),"root");
        Class<?> agent=Class.forName("fr.pierrecreeper.trinium.probe.ClimateObjectSizeAgent",true,ClassLoader.getSystemClassLoader());
        Method sizeOf=agent.getMethod("sizeOf",Object.class);
        Map<String,Object> result=new LinkedHashMap<>(),presets=new LinkedHashMap<>();
        for(var preset:MultiNoiseBiomeSourceParameterList.knownPresets().entrySet()) {
            List<Object> nodes=new ArrayList<>();collect(root.get(index.get(preset.getValue())),nodes,new IdentityHashMap<>());
            Map<String,Long> sizes=new LinkedHashMap<>();long total=0;
            for(Object node:nodes) {
                long size=((Number)sizeOf.invoke(null,node)).longValue();
                sizes.put(node.getClass().getSimpleName(),size);total+=size;
            }
            presets.put(preset.getKey().id().toString(),Map.of("nodes",nodes.size(),"measuredNodeShallowSizeBytes",sizes,"measuredRetainedNodeShallowBytes",total));
        }
        result.put("presets",presets);result.put("jvmLayout",jvmLayout());return result;
    }

    public static Map<String,String> jvmLayout() {
        var bean=java.lang.management.ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
        Map<String,String> values=new LinkedHashMap<>();
        for(String option:new String[]{"UseCompressedOops","UseCompressedClassPointers","ObjectAlignmentInBytes","UseCompactObjectHeaders"})
            try {values.put(option,bean.getVMOption(option).getValue());}
            catch(IllegalArgumentException unsupported) {values.put(option,"unsupported");}
        return values;
    }

    public static void main(String[] args) throws Exception {
        System.out.println(new com.google.gson.Gson().toJson(measureBaselineLayout()));
    }

    private static long reference(Climate.Parameter[] bounds,long[] target) {
        long total=0;
        for(int axis=0;axis<7;axis++) {long delta=bounds[axis].distance(target[axis]);total+=delta*delta;}
        return total;
    }
    private static Object findReference(Method find,Object list,Climate.TargetPoint query,Object metric) throws Exception {
        try {return find.invoke(list,query,metric);}
        catch(InvocationTargetException failure) {
            if(failure.getCause() instanceof RuntimeException cause)return cause.getClass();
            throw failure;
        }
    }
    private static Object findCandidate(Climate.ParameterList<?> list,Climate.TargetPoint query) {
        try {return list.findValueIndex(query);}
        catch(RuntimeException failure) {return failure.getClass();}
    }
    private static void collect(Object node,List<Object> nodes,IdentityHashMap<Object,Boolean> seen) throws Exception {
        if(seen.put(node,Boolean.TRUE)!=null)throw new AssertionError("Unexpected cyclic/shared climate node");
        nodes.add(node);
        try {for(Object child:(Object[])field(node.getClass(),"children").get(node))collect(child,nodes,seen);}
        catch(NoSuchFieldException leaf) { }
    }
    private static Field field(Class<?> type,String name) throws Exception {
        Field field=type.getDeclaredField(name);field.setAccessible(true);return field;
    }
    private static Method method(Class<?> type,String name,Class<?>... arguments) throws Exception {
        Method method=type.getDeclaredMethod(name,arguments);method.setAccessible(true);return method;
    }
}

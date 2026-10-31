import java.nio.file.*;
import java.time.*;
import java.util.*;
import jdk.jfr.consumer.*;

/** Aggregate offline loading CPU samples and blocking; never export file paths/arguments. */
public final class JfrLoadingAudit {
    record Mark(String name,long uptime,Instant time) {}
    static final class Stats {
        final Map<String,Long> threads=new HashMap<>(),sites=new HashMap<>(),renderSites=new HashMap<>(),renderInclusive=new HashMap<>(),blocked=new HashMap<>();
        long samples,parks,reads,gcNanos;
    }
    static final Map<String,Stats> phases=new LinkedHashMap<>();
    static void add(Map<String,Long> map,String name,long n){map.merge(name,n,Long::sum);}
    static String method(RecordedFrame f){var m=f.getMethod();return m.getType().getName()+"."+m.getName();}
    static boolean relevant(String m){return m.startsWith("net.minecraft.")||m.startsWith("com.mojang.")||m.startsWith("fr.pierrecreeper.")||m.startsWith("net.fabricmc.")||m.startsWith("org.spongepowered.")||m.startsWith("org.objectweb.");}
    static String site(RecordedStackTrace stack){
        if(stack==null||stack.getFrames().isEmpty())return "unknown";
        String first=method(stack.getFrames().getFirst()),caller="";
        for(var f:stack.getFrames())if(relevant(method(f))){caller=method(f);break;}
        return first.equals(caller)||caller.isEmpty()?first:first+" <- "+caller;
    }
    static String phase(Instant time,List<Mark> marks){
        String phase="startup";
        for(var mark:marks){if(time.isBefore(mark.time))break;phase=mark.name;}
        return phase;
    }
    static void table(String title,Map<String,Long> map,int limit,boolean ms){
        System.out.println(title);
        map.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed()).limit(limit).forEach(e->
            System.out.printf(Locale.ROOT,"  %12.3f %s%n",ms?e.getValue()/1e6:e.getValue().doubleValue(),e.getKey()));
    }
    public static void main(String[] args)throws Exception {
        Path file=Path.of(args[0]);List<Mark> marks=new ArrayList<>();
        try(var r=new RecordingFile(file)){while(r.hasMoreEvents()){
            var e=r.readEvent();
            if(e.getEventType().getName().equals("trinium.LoadingMarker"))marks.add(new Mark(e.getString("stage"),e.getLong("uptimeMs"),e.getStartTime()));
        }}
        marks.sort(Comparator.comparing(Mark::time));
        for(var mark:marks)System.out.println("MARK "+mark.name+" uptimeMs="+mark.uptime);
        boolean incomplete=marks.size()<4;
        if(incomplete)System.out.println("INCOMPLETE: expected four offline phase markers; never use this as a completed loading benchmark.");
        phases.put("startup",new Stats());for(var mark:marks)phases.put(mark.name,new Stats());
        try(var r=new RecordingFile(file)){while(r.hasMoreEvents()){
            var e=r.readEvent();String name=e.getEventType().getName();
            var stats=phases.get(phase(e.getStartTime(),marks));
            if(name.equals("jdk.ExecutionSample")||name.equals("jdk.NativeMethodSample")){
                var t=e.getThread("sampledThread");if(t==null)continue;
                String thread=t.getJavaName();stats.samples++;add(stats.threads,thread,1);
                var stack=e.getStackTrace();add(stats.sites,site(stack),1);
                if(thread.equals("Render thread")||thread.equals("main")){
                    add(stats.renderSites,site(stack),1);
                    if(stack!=null){Set<String> seen=new HashSet<>();for(var f:stack.getFrames())if(relevant(method(f)))seen.add(method(f));for(var m:seen)add(stats.renderInclusive,m,1);}
                }
            }else if(name.equals("jdk.ThreadPark")||name.equals("jdk.JavaMonitorEnter")){
                var t=e.getThread();if(t!=null&&(t.getJavaName().equals("Render thread")||t.getJavaName().equals("main"))){stats.parks++;add(stats.blocked,name+" "+site(e.getStackTrace()),e.getDuration().toNanos());}
            }else if(name.equals("jdk.FileRead"))stats.reads++;
            else if(name.equals("jdk.GCPhasePause"))stats.gcNanos+=e.getDuration().toNanos();
        }}
        System.out.println("Samples are observations, not measured CPU milliseconds. Blocking totals can overlap phases; event attribution uses start time. FileRead threshold 3ms. Raw JFR remains local.");
        for(var entry:phases.entrySet()){
            var s=entry.getValue();System.out.printf(Locale.ROOT,"\nPHASE after %s samples=%d mainBlockingEvents=%d slowReads=%d gcPauseMs=%.3f%n",entry.getKey(),s.samples,s.parks,s.reads,s.gcNanos/1e6);
            table("THREAD SAMPLES",s.threads,12,false);table("ALL THREAD TOP SITES",s.sites,16,false);
            table("MAIN/RENDER TOP SITES",s.renderSites,18,false);table("MAIN/RENDER INCLUSIVE CALLERS",s.renderInclusive,16,false);table("MAIN/RENDER BLOCKING MS",s.blocked,12,true);
        }
        if(incomplete)System.exit(2);
    }
}

import java.nio.file.*;
import java.util.*;
import jdk.jfr.consumer.*;
public class JfrAllocationAudit {
  static final Map<String,long[]> classes=new HashMap<>(),sites=new HashMap<>(),cpu=new HashMap<>();
  static void add(Map<String,long[]> map,String key,long weight){var p=map.computeIfAbsent(key,k->new long[2]);p[0]+=weight;p[1]++;}
  static String method(RecordedFrame f){var m=f.getMethod();return m.getType().getName()+"."+m.getName();}
  static void print(String title,Map<String,long[]> map,boolean bytes){System.out.println(title);map.entrySet().stream().sorted((a,b)->Long.compare(b.getValue()[0],a.getValue()[0])).limit(18).forEach(e->System.out.printf(Locale.ROOT,"%12.3f %6d %s%n",bytes?e.getValue()[0]/1048576.0:e.getValue()[0],e.getValue()[1],e.getKey()));}
  public static void main(String[] args)throws Exception {
    Set<Long> seen=new HashSet<>();Map<Long,long[]> statistics=new HashMap<>();Map<Long,String> names=new HashMap<>();long firstWeights=0,remainingWeights=0,pause=0,maxPause=0;int pauses=0;
    try(var recording=new RecordingFile(Path.of(args[0]))){while(recording.hasMoreEvents()){
      var e=recording.readEvent();var type=e.getEventType().getName();
      if(type.equals("jdk.ThreadAllocationStatistics")){
        var t=e.getThread("thread");if(t==null)continue;long allocated=e.getLong("allocated");long nanos=e.getStartTime().getEpochSecond()*1_000_000_000L+e.getStartTime().getNano();
        names.put(t.getJavaThreadId(),t.getJavaName());var data=statistics.computeIfAbsent(t.getJavaThreadId(),id->new long[]{allocated,nanos,allocated,nanos,0});data[2]=allocated;data[3]=nanos;data[4]++;
      }else if(type.equals("jdk.ObjectAllocationSample")){
        var t=e.getThread();if(t==null)continue;long weight=e.getLong("weight");
        if(!seen.add(t.getJavaThreadId()))remainingWeights+=weight;else{firstWeights+=weight;continue;}
        if(!t.getJavaName().equals("Render thread"))continue;
        String clazz=e.getClass("objectClass").getName();add(classes,clazz,weight);var stack=e.getStackTrace();String site="unknown";
        if(stack!=null)for(var f:stack.getFrames()){String m=method(f);if(m.startsWith("net.minecraft.")||m.startsWith("com.mojang.")||m.startsWith("fr.pierrecreeper.")){site=m;break;}}
        add(sites,site,weight);
      }else if(type.equals("jdk.ExecutionSample")||type.equals("jdk.NativeMethodSample")){
        var t=e.getThread("sampledThread");var stack=e.getStackTrace();if(t==null||!t.getJavaName().equals("Render thread")||stack==null)continue;
        String site="unknown";for(var f:stack.getFrames()){String m=method(f);if(m.startsWith("net.minecraft.")||m.startsWith("com.mojang.")||m.startsWith("fr.pierrecreeper.")){site=m;break;}}add(cpu,site,1);
      }else if(type.equals("jdk.GCPhasePause")){long n=e.getDuration().toNanos();pause+=n;maxPause=Math.max(maxPause,n);pauses++;}
    }}
    System.out.printf(Locale.ROOT,"Allocation weights (all threads): first/thread=%.3fMiB later=%.3fMiB; first/thread excluded from rankings%n",firstWeights/1048576.0,remainingWeights/1048576.0);
    statistics.entrySet().stream().filter(x->x.getValue()[3]>x.getValue()[1]).sorted((a,b)->Long.compare(b.getValue()[2]-b.getValue()[0],a.getValue()[2]-a.getValue()[0])).limit(10).forEach(x->{var p=x.getValue();System.out.printf(Locale.ROOT,"THREAD ALLOC %s: %.3fMiB in %.3fs = %.3fMiB/s (%d statistics)%n",names.get(x.getKey()),(p[2]-p[0])/1048576.0,(p[3]-p[1])/1e9,(p[2]-p[0])/1048576.0/((p[3]-p[1])/1e9),p[4]);});
    print("RENDER allocation weighted estimates after first/thread",classes,true);print("RENDER allocation sites",sites,true);print("RENDER CPU samples",cpu,false);System.out.printf(Locale.ROOT,"GC pauses=%d sum=%.3fms max=%.3fms%n",pauses,pause/1e6,maxPause/1e6);
  }
}

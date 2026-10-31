package fr.pierrecreeper.trinium;

import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/** Contract doubles are ONLY test fixtures, not Minecraft, Fabric, Mixin or loader stubs. */
public final class TriniumSelfTest {
    enum Face { WEST,EAST,UP,DOWN,SOUTH,NORTH }
    public static final class FakeVisibility {
        private final BitSet data=new BitSet(36);
        public void set(Face a,Face b,boolean v){data.set(a.ordinal()+b.ordinal()*6,v);data.set(b.ordinal()+a.ordinal()*6,v);}
        public boolean visibilityBetween(Face a,Face b){return data.get(a.ordinal()+b.ordinal()*6);}
    }
    public static final class FakeGraph {
        private final BitSet bitSet; private final int empty;
        FakeGraph(BitSet bits){bitSet=(BitSet)bits.clone();empty=4096-bits.cardinality();}
        private static int getIndex(int x,int y,int z){return x|z<<4|y<<8;}
        public FakeVisibility resolve(){return fromMask(ReferenceSolver.resolve(bitSet));}
    }
    public static final class FakeCallback {
        Object result; boolean cancelled;
        public void setReturnValue(Object value){result=value;cancelled=true;}
        public void cancel(){cancelled=true;}
    }
    public static class FakeStorage {
        final long[] words; final int bits,size;
        FakeStorage(long[] words,int bits,int size){this.words=words;this.bits=bits;this.size=size;}
        public long[] getRaw(){return words;}
        public int getBits(){return bits;}
        public int getSize(){return size;}
        public int get(int index){int n=64/bits;return (int)((words[index/n] >>> ((index%n)*bits)) & ((1L<<bits)-1));}
    }
    public static final class IncompatibleStorage extends FakeStorage {
        IncompatibleStorage(){super(new long[]{0L},7,4);}
        @Override public int get(int i){return 1;}
    }
    private static FakeVisibility fromMask(long mask){
        FakeVisibility out=new FakeVisibility(); String[] names={"DOWN","UP","NORTH","SOUTH","WEST","EAST"};
        for(int a=0;a<6;a++) for(int b=0;b<6;b++) if((mask&(1L<<(a*6+b)))!=0) out.set(Face.valueOf(names[a]),Face.valueOf(names[b]),true);
        return out;
    }
    private static void check(boolean condition,String why){if(!condition)throw new AssertionError(why);}
    public static void main(String[] args) throws Exception {
        long start=System.nanoTime();
        PortabilityTest.run();
        int randomCases=12000;
        System.out.println("PASS visibility fixtures/random: "+TestVectors.quickCheck(randomCases)+"; independent reference, repeatability, immutability");
        int structured=0;
        // Every plane, across every axis, then a one-voxel opening in that plane.
        for(int axis=0;axis<3;axis++)for(int plane=0;plane<16;plane++) {
            BitSet wall=new BitSet(4096);
            for(int p=0;p<4096;p++) {
                int component=switch(axis){case 0->p&15;case 1->p>>>8;default->p>>>4&15;};
                if(component==plane)wall.set(p);
            }
            for(int opening=0;opening<17;opening++) {
                BitSet bits=(BitSet)wall.clone();
                if(opening<16){int[] xyz={opening,opening,opening};xyz[axis]=plane;bits.clear(xyz[0]|xyz[2]<<4|xyz[1]<<8);}
                check(VisibilitySolver.resolve(bits)==ReferenceSolver.resolve(bits),"planar fixture"); structured++;
            }
        }
        // A closed shell followed by alternating almost-full and almost-empty runs exercises scratch reset.
        for(int n=0;n<4096;n+=13){BitSet b=new BitSet(4096);b.set(0,n);check(VisibilitySolver.resolve(b)==ReferenceSolver.resolve(b),"threshold/span "+n);structured++;}
        System.out.println("PASS structured/adversarial sections: "+structured);
        try(var pool=Executors.newFixedThreadPool(8)) {
            List<Future<?>> jobs=new ArrayList<>();
            for(int worker=0;worker<8;worker++){final int seed=worker;jobs.add(pool.submit(()->{
                SplittableRandom rng=new SplittableRandom(seed);
                for(int i=0;i<256;i++){BitSet b=TestVectors.random(rng,i);check(VisibilitySolver.resolve(b)==ReferenceSolver.resolve(b),"concurrent isolation");}
            }));}
            for(var job:jobs)job.get();
        }
        System.out.println("PASS concurrent sections: 2048 on 8 threads");
        BitSet bad=new BitSet();bad.set(4096);
        try{VisibilitySolver.resolve(bad);throw new AssertionError("bounds");}catch(IllegalArgumentException expected){ }
        try{VisibilitySolver.resolveWords(new long[65]);throw new AssertionError("word bounds");}catch(IllegalArgumentException expected){ }
        int paletteCases=0;
        SplittableRandom random=new SplittableRandom(21397);
        int[] sizes={0,1,2,3,7,15,16,17,31,32,33,63,64,65,127,128,255,256,257,4095,4096,4097};
        for(int bits=1;bits<=32;bits++)for(int size:sizes)for(int round=0;round<4;round++){
            int per=64/bits;
            long[] data=new long[(size+per-1)/per];for(int w=0;w<data.length;w++)data[w]=random.nextLong();
            long[] before=data.clone();int[] out=new int[size+7];Arrays.fill(out,0xBADCAFE);
            check(PaletteDecoder.unpack(data,bits,size,out),"decode valid");
            FakeStorage ref=new FakeStorage(data,bits,size);
            for(int i=0;i<size;i++)check(out[i]==ref.get(i),"palette bits="+bits+" index="+i);
            for(int i=size;i<out.length;i++)check(out[i]==0xBADCAFE,"tail unchanged");
            check(Arrays.equals(before,data),"packed input immutable");paletteCases++;
        }
        int[] untouched={17,17};
        check(!PaletteDecoder.unpack(new long[0],4,2,untouched),"short data rejected");
        check(!PaletteDecoder.unpack(new long[1],0,2,untouched),"zero bits rejected");
        check(!PaletteDecoder.unpack(new long[1],33,2,untouched),"invalid bits rejected");
        check(!PaletteDecoder.unpack(new long[1],4,3,untouched),"short output rejected");
        check(Arrays.equals(untouched,new int[]{17,17}),"invalid inputs never partially decode");
        System.out.println("PASS palette unpack: "+paletteCases+" cases, widths 1..32; input/tail preservation; invalid bounds");
        GameBinding adapter=new GameBinding(FakeGraph.class);
        for(BitSet b:TestVectors.fixtures()){
            long expected=ReferenceSolver.resolve(b);
            check(adapter.read(adapter.newVisibility(expected))==expected,"reordered enum translation");
            check(adapter.opaqueCount(new FakeGraph(b),b)==b.cardinality(),"stored count");
        }
        var directory=Files.createTempDirectory("trinium-contract-");
        var field=Trinium.class.getDeclaredField("directory");field.setAccessible(true);field.set(null,directory);
        Files.writeString(directory.resolve("trinium.properties"),"configRevision=3\nstartupMode=off\nmode=verify\n");
        Trinium.loadConfiguration();Trinium.initialized=true;
        check(Trinium.mode()==Trinium.Mode.AUTO && Trinium.active(),"legacy config auto migrates");
        try (var backups=Files.list(directory)) {
            var saved=backups.filter(p -> p.getFileName().toString().startsWith("trinium-before-migration-")).toList();
            check(saved.size()==1 && Files.readString(saved.getFirst()).equals("configRevision=3\nstartupMode=off\nmode=verify\n"),"unique byte-preserving migration backup");
        }
        for(BitSet b:TestVectors.fixtures()){
            FakeCallback cb=new FakeCallback();FakeGraph g=new FakeGraph(b);TriniumHooks.before(g,cb);
            boolean complex=b.cardinality()>=256 && b.cardinality()<4096;
            check(cb.cancelled==complex,"auto enabled without verification");
            if(complex)check(adapter.read(cb.result)==ReferenceSolver.resolve(b),"replacement correctness");
        }
        check(!Trinium.failed && Trinium.replaced.sum()>0,"automatic hook simulation");
        Trinium.changeMode(Trinium.Mode.OFF);Trinium.loadConfiguration();
        check(!Trinium.active() && Trinium.mode()==Trinium.Mode.OFF,"explicit opt-out persists");
        long before=Trinium.intercepted.sum();
        TriniumHooks.before(new Object(),new Object());
        check(before==Trinium.intercepted.sum() && !Trinium.failed,"OFF bypasses binding");
        Trinium.changeMode(Trinium.Mode.AUTO);Trinium.loadConfiguration();
        check(Trinium.active() && Trinium.mode()==Trinium.Mode.AUTO,"ON persists");
        System.out.println("PASS startup migration/backup, auto activation, explicit opt-out persistence, enum-layout adapter (simulation only)");
        for(int bits=1;bits<=32;bits++){
            int size=4096,per=64/bits;long[] data=new long[(size+per-1)/per];
            for(int w=0;w<data.length;w++)data[w]=random.nextLong();
            FakeStorage storage=new FakeStorage(data,bits,size);FakeCallback cb=new FakeCallback();int[] out=new int[size];
            PaletteHooks.unpack(storage,out,cb);check(cb.cancelled && !PaletteHooks.failed,"palette callback");
            for(int i=0;i<size;i++)check(out[i]==storage.get(i),"palette adapter");
        }
        FakeCallback failedCallback=new FakeCallback();
        PaletteHooks.unpack(new IncompatibleStorage(),new int[4],failedCallback);
        check(!failedCallback.cancelled && PaletteHooks.failed,"incompatible layout falls through to vanilla");
        check(Trinium.active(),"palette failure doesn't disable visibility core");
        System.out.println("PASS palette adapter, layout validation and fallback (simulation only)");
        var report=Trinium.writeReport();String json=Files.readString(report);
        check(json.contains("\"mode\": \"AUTO\"") && json.contains("heapUsedBytes"),"diagnostic fields");
        TriniumHooks.before(new Object(),new FakeCallback());
        check(Trinium.failed && !Trinium.active(),"unsupported graph fallback");
        System.out.println("PASS incompatible graph safe fallback and diagnostic report (simulation only)");
        NativeBridge.initialize();
        System.out.println("Native: "+NativeBridge.status());
        if(Boolean.getBoolean("trinium.requireNative"))check(NativeBridge.available(),"native Maven profile requires a REAL loaded library");
        try(var files=Files.walk(directory)){for(var p:files.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}
        System.out.printf(Locale.ROOT,"ALL TESTS PASS in %.3f s. Minecraft/Fabric not launched.%n",(System.nanoTime()-start)/1e9);
    }
}

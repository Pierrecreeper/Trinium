package fr.pierrecreeper.trinium;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

public final class TriniumCli {
    private static volatile long blackhole;
    private TriniumCli() { }
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--build-native")) {
            java.nio.file.Path output = null;
            if (args.length == 3 && args[1].equals("--output")) output = java.nio.file.Path.of(args[2]);
            else if (args.length != 1) throw new IllegalArgumentException("Usage: --build-native [--output new.jar]");
            System.out.println("Build the Rust CPU module using installed Cargo. No automatic downloads.");
            System.out.println("Minecraft retains its rendering pipeline. This is not an FPS benchmark.");
            System.out.println("Tested native JAR: " + LocalNativeBuild.buildJar(output));
            return;
        }
        if(args.length==0) {
            System.out.println("Trinium "+Trinium.VERSION+" | experimental Fabric client mod for Minecraft 26.3");
            System.out.println("Put the JAR in mods/. This program does not launch Minecraft.");
            System.out.println("Build Rust and produce a new JAR: java -jar Trinium.jar --build-native");
            System.out.println("CPU tests: java -jar Trinium.jar --selftest");
            System.out.println("Synthetic microbenchmark (not FPS): java -jar Trinium.jar --benchmark");
            return;
        }
        boolean test=false,bench=false,require=false,inventory=false,universal=false;
        for(String arg:args) switch(arg) {
            case "--selftest" -> test=true;
            case "--benchmark" -> bench=true;
            case "--require-native" -> require=true;
            case "--platforms" -> inventory=true;
            case "--verify-universal" -> universal=true;
            default -> throw new IllegalArgumentException("Unknown option: "+arg);
        }
        if (inventory) NativeInventory.print();
        if (universal) System.out.println("PASS packaged native integrity: " + NativeInventory.verifyAll() + " targets (not a runtime test on foreign systems)");
        NativeBridge.initialize();
        System.out.println(NativeBridge.status());
        if(require && !NativeBridge.available())throw new IllegalStateException("Native binary required but unavailable");
        if(test) {
            int cases=TestVectors.quickCheck(2048);
            System.out.println("PASS: "+cases+" Java visibility cases; independent reference; inputs unchanged.");
            if(NativeBridge.available()) {
                SplittableRandom random=new SplittableRandom(42);
                for(int i=0;i<2048;i++){
                    BitSet bits=TestVectors.random(random,i);
                    if(NativeBridge.resolve(bits.toLongArray())!=ReferenceSolver.resolve(bits))throw new AssertionError("Native parity: "+i);
                }
                System.out.println("PASS: 2048 additional native parity cases.");
            }
            System.out.println("These tests do not validate Fabric loading, Minecraft or FPS.");
        }
        if(bench) benchmark();
    }
    private static void benchmark() {
        List<BitSet> data=new ArrayList<>(TestVectors.fixtures());
        SplittableRandom random=new SplittableRandom(1901);
        for(int i=0;i<256;i++)data.add(TestVectors.random(random,i));
        for(int round=0;round<8;round++)for(BitSet bits:data)blackhole^=VisibilitySolver.resolve(bits);
        long start=System.nanoTime();int rounds=64;
        for(int round=0;round<rounds;round++)for(BitSet bits:data)blackhole^=VisibilitySolver.resolve(bits);
        double nanos=(double)(System.nanoTime()-start)/(rounds*data.size());
        System.out.printf(Locale.ROOT,"Java solver: %.1f ns/section, %d synthetic resolutions.%n",nanos,rounds*data.size());
        System.out.println("Indicative microbenchmark, not JMH: no Sodium/vanilla comparison, not an FPS measurement.");
    }
}

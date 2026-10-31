package fr.pierrecreeper.trinium.test;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.nio.file.*;
import java.util.*;

/** Packaged separately as trinium-test-harness.jar, never inside the user's mod. */
@Mixin(value = Minecraft.class, remap = false)
public abstract class ClientTestMixin {
    @Unique private long trinium$joined;
    @Unique private double trinium$loadMs;
    @Unique private double trinium$joinMs;
    @Unique private volatile double trinium$reloadMs;
    @Unique private volatile boolean trinium$reloading;
    @Unique private int trinium$reloadCount;
    @Unique private List<Double> trinium$reloadSamples;
    @Unique private boolean trinium$creating;
    @Unique private long trinium$creationStart;
    @Unique private double trinium$creationMs;
    @Unique private double trinium$playerArrivedMs;
    @Unique private long trinium$lastFrame;
    @Unique private boolean trinium$shot;
    @Unique private boolean trinium$finished;
    @Unique private boolean trinium$terrainChecked;
    @Unique private boolean trinium$f3Shot;
    @Unique private boolean trinium$indirectStarted;
    @Unique private static final boolean trinium$indirect=Boolean.getBoolean("trinium.testIndirectParity");
    @Unique private static final boolean trinium$dense="dense".equals(System.getProperty("trinium.testScene"));
    @Unique private List<Long> trinium$frames;
    @Unique private static final int trinium$renderDistance=Integer.getInteger("trinium.testRenderDistance",8);
    @Unique private static final int trinium$simulationDistance=Integer.getInteger("trinium.testSimulationDistance",5);
    @Unique private static final int trinium$warmup=Integer.getInteger("trinium.testWarmup",20);
    @Unique private static final int trinium$duration=Integer.getInteger("trinium.testDuration",30);
    @Unique private static final double[] trinium$camera=java.util.Arrays.stream(System.getProperty("trinium.testCamera","42.5,80,72.5,0,25").split(",")).mapToDouble(Double::parseDouble).toArray();

    @Inject(method = "onGameLoadFinished", at = @At("HEAD"))
    private void trinium$settings(CallbackInfo ci) {
        Minecraft mc = (Minecraft)(Object)this;
        trinium$frames=new ArrayList<>();
        trinium$reloadSamples=new ArrayList<>();
        trinium$loadMs=java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime();
        fr.pierrecreeper.trinium.probe.LoadingMarkerEvent.mark("game-ready");
        if(Boolean.getBoolean("trinium.testNativePacketGpu")) {
            try {fr.pierrecreeper.trinium.probe.NativePacketGpuProbe.run(mc.gameDirectory.toPath());}
            catch(Exception failure){throw new RuntimeException("Native packet GPU oracle failed",failure);}
        }
        if(Boolean.getBoolean("trinium.testClimateProbe")) {
            try {
                var result=fr.pierrecreeper.trinium.probe.ClimateDistanceProbe.run();
                Files.writeString(mc.gameDirectory.toPath().resolve("climate-distance-result.json"),
                    new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(result)+"\n");
                System.out.println("TRINIUM_CLIMATE_PARITY "+result);
            } catch(Exception failure){throw new RuntimeException("Climate distance oracle failed",failure);}
        }
        if(Boolean.getBoolean("trinium.testModels")) {
            fr.pierrecreeper.trinium.probe.ModelParityProbe.run();
            try {fr.pierrecreeper.trinium.probe.EntityModelParityProbe.run();}
            catch(Exception e){throw new RuntimeException(e);}
            try {fr.pierrecreeper.trinium.probe.TerrainCacheProbe.runLifecycle();fr.pierrecreeper.trinium.probe.DrawCacheProbe.runLifecycle();}
            catch(Exception e){throw new RuntimeException(e);}
            System.setProperty("trinium.disableShaderCache","true");
            try {fr.pierrecreeper.trinium.ShaderCacheTest.main(new String[0]);}
            catch(Exception e){throw new RuntimeException(e);}
            finally {System.clearProperty("trinium.disableShaderCache");}
        }
        mc.options.renderDistance().set(trinium$renderDistance);
        mc.options.simulationDistance().set(trinium$simulationDistance);
        mc.options.enableVsync().set(false);
        mc.options.framerateLimit().set(260);
        mc.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
        mc.options.pauseOnLostFocus = false;
        mc.debugEntries.setOverlayVisible(false);
    }

    @Unique private boolean trinium$bootShot;
    @Inject(method="runTick",at=@At("HEAD"))
    private void trinium$bootDiagnostic(CallbackInfo ci) {
        if(!Boolean.getBoolean("trinium.testLoadingMarkers") || trinium$bootShot || trinium$joined!=0
            || java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime()<30000)return;
        trinium$bootShot=true;
        Minecraft mc=(Minecraft)(Object)this;
        System.out.println("TRINIUM_BOOT_SCREEN "+(mc.gui.screen()==null?"null":mc.gui.screen().getClass().getName()+" "+mc.gui.screen().getTitle().getString()));
        Screenshot.grab(mc.gameDirectory,"trinium-loading.png",mc.gameRenderer.mainRenderTarget(),1,
            message->System.out.println("TRINIUM_BOOT_SCREENSHOT "+message.getString()));
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void trinium$testTick(CallbackInfo ci) {
        Minecraft mc = (Minecraft)(Object)this;
        if(Boolean.getBoolean("trinium.testNewWorld") && trinium$loadMs>0 && !trinium$creating) {
            trinium$creating=true;trinium$creationStart=System.nanoTime();
            var settings=new net.minecraft.world.level.LevelSettings("TriniumTest",net.minecraft.world.level.GameType.CREATIVE,
                net.minecraft.world.level.LevelSettings.DifficultySettings.DEFAULT,true,net.minecraft.world.level.WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel("TriniumTest",settings,new net.minecraft.world.level.levelgen.WorldOptions(123456789L,true,false),
                net.minecraft.world.level.levelgen.presets.WorldPresets::createNormalWorldDimensions,null);
            return;
        }
        if (mc.player == null || mc.level == null || trinium$finished) return;
        if (Boolean.getBoolean("trinium.testNewWorld")) {
            if (trinium$playerArrivedMs == 0) trinium$playerArrivedMs = (System.nanoTime()-trinium$creationStart)/1e6;
            // Player objects arrive before the loading screen permits gameplay.
            // Do not teleport or count completion until the real ready condition closes it.
            if (mc.gui.screen() != null) return;
        }
        // Debug profile loading or input must not make compared runs draw different overlays.
        if (mc.debugEntries.isOverlayVisible() && (!Boolean.getBoolean("trinium.testF3") || trinium$f3Shot))
            mc.debugEntries.setOverlayVisible(false);
        if (mc.options.renderDistance().get() != trinium$renderDistance) mc.options.renderDistance().set(trinium$renderDistance);
        if (mc.options.simulationDistance().get() != trinium$simulationDistance) mc.options.simulationDistance().set(trinium$simulationDistance);
        if (mc.options.enableVsync().get()) mc.options.enableVsync().set(false);
        if (mc.options.inactivityFpsLimit().get() != net.minecraft.client.InactivityFpsLimit.MINIMIZED)
            mc.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
        long now = System.nanoTime();
        if (trinium$joined == 0) {
            trinium$joined = now;
            if(trinium$frames==null)trinium$frames=new ArrayList<>();
            if(trinium$creationStart!=0)trinium$creationMs=(now-trinium$creationStart)/1e6;
            trinium$joinMs=java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime();
            fr.pierrecreeper.trinium.probe.LoadingMarkerEvent.mark("world-joined");
            mc.options.pauseOnLostFocus = false;
            mc.options.enableVsync().set(false);
            mc.options.framerateLimit().set(260);
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                if(Boolean.getBoolean("trinium.testNewWorld"))return;
                if("entities".equals(System.getProperty("trinium.testScene"))){fr.pierrecreeper.trinium.probe.EntityStressScene.setup(mc);return;}
                var source = server.createCommandSourceStack();
                if(!"village".equals(System.getProperty("trinium.testScene")))server.getCommands().performPrefixedCommand(source, "gamemode spectator @a");
                if (trinium$dense) {
                    server.getCommands().performPrefixedCommand(source,"fill 20 79 56 64 79 88 minecraft:stone");
                    for(int x=24;x<=60;x+=3) for(int z=60;z<=84;z+=3) {
                        server.getCommands().performPrefixedCommand(source,"setblock "+x+" 80 "+z+" minecraft:chest[facing=south]");
                        server.getCommands().performPrefixedCommand(source,"summon minecraft:experience_orb "+(x+.5)+" 81 "+(z+.5)+" {Value:1}");
                        server.getCommands().performPrefixedCommand(source,"summon minecraft:item "+(x+1.5)+" 81 "+(z+.5)+" {Item:{id:\"minecraft:diamond\",count:1}}");
                    }
                    for(int x=26;x<=58;x+=4) server.getCommands().performPrefixedCommand(source,"summon minecraft:armor_stand "+x+" 80 86 {NoGravity:1b}");
                    server.getCommands().performPrefixedCommand(source,"tp @a 42.5 87 100 180 25");
                } else if("cave".equals(System.getProperty("trinium.testScene"))) {
                    server.getCommands().performPrefixedCommand(source,"fill 20 50 56 64 65 88 minecraft:stone");
                    server.getCommands().performPrefixedCommand(source,"fill 22 51 58 62 63 86 minecraft:air");
                    server.getCommands().performPrefixedCommand(source,"setblock 36 51 64 minecraft:chest");
                    server.getCommands().performPrefixedCommand(source,"tp @a 42.5 54 80 180 10");
                } else if("village".equals(System.getProperty("trinium.testScene"))) {
                    server.getCommands().performPrefixedCommand(source,"tp @a "+trinium$camera[0]+" "+trinium$camera[1]+" "+trinium$camera[2]+" "+trinium$camera[3]+" "+trinium$camera[4]);
                } else server.getCommands().performPrefixedCommand(source, "tp @a 42.5 80 72.5 0 25");
            });
            System.out.println("TRINIUM_TEST_WORLD_JOINED");
        }
        double seconds = (now - trinium$joined) / 1e9;
        if (trinium$indirect && seconds>70 && !trinium$indirectStarted) {
            trinium$indirectStarted=true;
            try {fr.pierrecreeper.trinium.probe.IndirectParityProbe.start(mc);}
            catch(Exception error) {throw new RuntimeException(error);}
        }
        if(Boolean.getBoolean("trinium.testModels") && seconds>10 && !trinium$terrainChecked){
            trinium$terrainChecked=true;
            try {fr.pierrecreeper.trinium.probe.TerrainCacheProbe.runReferenceParity(mc);fr.pierrecreeper.trinium.probe.DrawCacheProbe.runReferenceParity(mc);}
            catch(Exception e){throw new RuntimeException(e);}
        }
        if(Boolean.getBoolean("trinium.testReload") && seconds>5+trinium$reloadCount*10 && !trinium$reloading
            && trinium$reloadCount<Integer.getInteger("trinium.testReloadCount",1)) {
            trinium$reloading=true;trinium$reloadCount++;long start=System.nanoTime();
            fr.pierrecreeper.trinium.probe.LoadingMarkerEvent.mark("reload-start");
            mc.reloadResourcePacks().whenComplete((unused,error)->{
                if(error!=null){System.err.println("TRINIUM_RELOAD_FAILED "+error);error.printStackTrace();mc.stop();return;}
                try{
                    trinium$reloadMs=(System.nanoTime()-start)/1e6;
                    trinium$reloadSamples.add(trinium$reloadMs);
                    fr.pierrecreeper.trinium.probe.LoadingMarkerEvent.mark("reload-end");
                    System.out.println("TRINIUM_RELOAD_MS "+trinium$reloadMs);
                    trinium$reloading=false;
                }catch(RuntimeException callbackError){System.err.println("TRINIUM_RELOAD_CALLBACK_FAILED "+callbackError);callbackError.printStackTrace();mc.stop();}
            });
        }
        // One fixed camera position, slow deterministic rotation, same in both runs.
        mc.player.setYRot("village".equals(System.getProperty("trinium.testScene")) ? (float)trinium$camera[3] : !"coast".equals(System.getProperty("trinium.testScene")) ? 180 : (float)(seconds * 3.0));
        mc.player.setXRot("village".equals(System.getProperty("trinium.testScene")) ? (float)trinium$camera[4] : 25);
        // Save the screenshot during warm-up, outside the frame-time sample.
        if (Boolean.getBoolean("trinium.testF3") && !trinium$f3Shot && seconds > 12) {
            mc.debugEntries.setOverlayVisible(true);
            if (seconds > 15) {
                trinium$f3Shot = true;
                Screenshot.grab(mc.gameDirectory, "trinium-f3.png", mc.gameRenderer.mainRenderTarget(), 1,
                    message -> System.out.println("TRINIUM_TEST_F3_SCREENSHOT " + message.getString()));
            }
        }
        if (seconds > 15 && !trinium$shot) {
            trinium$shot = true;
            Screenshot.grab(mc.gameDirectory, "trinium-test.png", mc.gameRenderer.mainRenderTarget(), 1,
                message -> System.out.println("TRINIUM_TEST_SCREENSHOT " + message.getString()));
        }
        if (seconds > trinium$warmup+trinium$duration && (!Boolean.getBoolean("trinium.testReload")
            || trinium$reloadCount==Integer.getInteger("trinium.testReloadCount",1) && !trinium$reloading)) {
            trinium$finished = true;
            try {
                if (Boolean.getBoolean("trinium.testModeOff")) fr.pierrecreeper.trinium.probe.ModeOffProbe.run(mc);
                try {
                    Class<?> mod = Class.forName("fr.pierrecreeper.trinium.Trinium");
                    mod.getMethod("writeReport").invoke(null);
                } catch (ClassNotFoundException vanilla) { }
                List<Long> sorted = new ArrayList<>(trinium$frames); Collections.sort(sorted);
                double mean = trinium$frames.stream().mapToLong(Long::longValue).average().orElse(0)/1e6;
                String direct="";
                if(Boolean.getBoolean("trinium.experimentalDirectTerrain")) {
                    var batch=Class.forName("fr.pierrecreeper.trinium.graphics.DirectTerrainBatch");
                    direct=",\"directCalls\":"+batch.getField("batches").getLong(null)
                        +",\"directDraws\":"+batch.getField("draws").getLong(null)
                        +",\"directFallbacks\":"+batch.getField("fallbacks").getLong(null)
                        +",\"directIndividual\":"+Boolean.getBoolean("trinium.debugDirectIndividual");
                }
                if(Boolean.getBoolean("trinium.experimentalTerrainSubmit")) {
                    var submit=Class.forName("fr.pierrecreeper.trinium.graphics.ScopedTerrainSubmit");
                    direct+=",\"terrainSubmitPasses\":"+submit.getField("passes").getLong(null)
                        +",\"terrainSubmitDraws\":"+submit.getField("draws").getLong(null)
                        +",\"terrainSubmitFallbacks\":"+submit.getField("fallbacks").getLong(null);
                }
                String sceneCounts="";
                if("entities".equals(System.getProperty("trinium.testScene"))) sceneCounts=",\"entityFixture\":"+new com.google.gson.Gson().toJson(fr.pierrecreeper.trinium.probe.EntityStressScene.snapshot(mc));
                String json = "{\"frames\":" + sorted.size() + ",\"meanFrameMs\":" + mean
                    + ",\"p50FrameMs\":" + sorted.get(sorted.size()/2)/1e6
                    + ",\"p95FrameMs\":" + sorted.get(sorted.size()*95/100)/1e6
                    + ",\"p99FrameMs\":" + sorted.get(sorted.size()*99/100)/1e6
                    + ",\"p999FrameMs\":" + sorted.get(sorted.size()*999/1000)/1e6
                    + ",\"maxFrameMs\":" + sorted.getLast()/1e6
                    + ",\"framesOver50ms\":" + sorted.stream().filter(n->n>50_000_000L).count()
                    + ",\"framesOver100ms\":" + sorted.stream().filter(n->n>100_000_000L).count()
                    + ",\"width\":"+mc.getWindow().getWidth()+",\"height\":"+mc.getWindow().getHeight()
                    + ",\"renderDistance\":"+mc.options.renderDistance().get()
                    + ",\"simulationDistance\":"+mc.options.simulationDistance().get()
                    + ",\"multiDrawIndirect\":"+mc.levelRenderer.isChunkRenderingUsingMultiDrawIndirect()
                    + ",\"debugOverlay\":"+mc.debugEntries.isOverlayVisible()
                    + ",\"inactivityLimit\":\""+mc.options.inactivityFpsLimit().get().getSerializedName()+"\""
                    + ",\"iconified\":"+mc.getWindow().isIconified()
                    + direct + sceneCounts + ",\"vsync\":"+mc.options.enableVsync().get()+",\"seconds\":"+trinium$duration+"}\n";
                Files.writeString(mc.gameDirectory.toPath().resolve("test-result.json"), json);
                Files.writeString(mc.gameDirectory.toPath().resolve("timings.json"),"{\"gameReadyMs\":"+trinium$loadMs+",\"worldJoinedMs\":"+trinium$joinMs+",\"resourceReloadMs\":"+trinium$reloadMs+",\"resourceReloadSamplesMs\":"+trinium$reloadSamples+",\"newWorldCreationMs\":"+trinium$creationMs+",\"newWorldPlayerArrivedMs\":"+trinium$playerArrivedMs+"}\n");
                StringBuilder csv=new StringBuilder("frame,ms\n");
                for(int i=0;i<trinium$frames.size();i++)csv.append(i).append(',').append(trinium$frames.get(i)/1e6).append('\n');
                Files.writeString(mc.gameDirectory.toPath().resolve("frames.csv"),csv);
                System.out.println("TRINIUM_TEST_COMPLETE " + json);
            } catch (Exception e) { e.printStackTrace(); }
            mc.stop();
        }
    }

    @Inject(method = "runTick", at = @At("TAIL"))
    private void trinium$frame(boolean render, CallbackInfo ci) {
        if (trinium$indirectStarted) fr.pierrecreeper.trinium.probe.IndirectParityProbe.frame((Minecraft)(Object)this);
        long now = System.nanoTime();
        if (trinium$joined != 0 && now - trinium$joined > trinium$warmup*1_000_000_000L && !trinium$finished
                && trinium$lastFrame != 0) trinium$frames.add(now - trinium$lastFrame);
        trinium$lastFrame = now;
    }
}

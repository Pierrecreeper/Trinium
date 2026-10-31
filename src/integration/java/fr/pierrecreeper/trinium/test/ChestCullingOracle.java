// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.probe;

import com.google.gson.GsonBuilder;
import fr.pierrecreeper.trinium.test.ChestCullingExtractorInvoker;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.blockentity.state.ChestRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Test-only oracle for a disposable offline world. This is correctness evidence,
 * never an FPS benchmark. Each view re-extracts OFF/ON at the same tick and pose,
 * then renders A/B/A synchronously. Exact color AND depth equality are required.
 */
public final class ChestCullingOracle {
    private static final float[] YAWS={180,160,140,120,200,220,240,260};
    private static final int WARMUP_RENDERS_PER_VIEW=3;
    private static final BlockPos CRACK_STONE=new BlockPos(42,80,74);
    private static final BlockPos CRACK_CHEST=new BlockPos(39,80,71);
    private static final int STONE_BREAKER=2_100_000_001, CHEST_BREAKER=2_100_000_002;
    private static final List<Map<String,Object>> RESULTS=new ArrayList<>();
    private static final List<Map<String,Object>> EXTRACTION_DIAGNOSTICS=new ArrayList<>();
    private static final List<Runnable> RESTORE=new ArrayList<>();
    private static final Map<ChestBlockEntity,LidSnapshot> LIDS=new LinkedHashMap<>();
    private static long started, pausedAt;
    private static volatile boolean fixtureReady;
    private static volatile Throwable fixtureError;
    private static boolean paused, done;
    private static int view, settleFrames;
    private static Screen previousScreen;
    private static float previousYaw, previousPitch, previousOldYaw, previousOldPitch;

    private ChestCullingOracle() { }

    public static void configure(Minecraft minecraft) {
        option(minecraft.options.renderDistance(),Integer.getInteger("trinium.testRenderDistance",8));
        option(minecraft.options.simulationDistance(),Integer.getInteger("trinium.testSimulationDistance",5));
        option(minecraft.options.enableVsync(),false);
        option(minecraft.options.framerateLimit(),260);
        option(minecraft.options.inactivityFpsLimit(),net.minecraft.client.InactivityFpsLimit.MINIMIZED);
        option(minecraft.options.chunkSectionFadeInTime(),0.0);
        boolean pauseOnLostFocus=minecraft.options.pauseOnLostFocus;
        RESTORE.add(()->minecraft.options.pauseOnLostFocus=pauseOnLostFocus);
        minecraft.options.pauseOnLostFocus=false;
        var cameraType=minecraft.options.getCameraType();
        RESTORE.add(()->minecraft.options.setCameraType(cameraType));
        minecraft.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
        boolean overlay=minecraft.debugEntries.isOverlayVisible();
        RESTORE.add(()->minecraft.debugEntries.setOverlayVisible(overlay));
        minecraft.debugEntries.setOverlayVisible(false);
    }

    private static <T> void option(OptionInstance<T> option,T value) {
        T before=option.get();RESTORE.add(()->option.set(before));option.set(value);
    }

    public static void tick(Minecraft minecraft) {
        if (done || minecraft.level==null || minecraft.player==null) return;
        try {
            require(minecraft.getSingleplayerServer()!=null,"Oracle requires a disposable single-player world");
            if (fixtureError!=null) throw new AssertionError("Fixture commands failed",fixtureError);
            if (started==0) {started=System.nanoTime();setup(minecraft);return;}
            if (!fixtureReady || paused) return;
            // Real chest controllers, not fabricated render states. Cover fully
            // and partially raised lids, including both halves of a double.
            forceLid(minecraft,new BlockPos(36,80,68),1.0f);
            forceLid(minecraft,new BlockPos(37,80,70),0.5f);
            forceLid(minecraft,new BlockPos(38,80,70),0.5f);
            forceLid(minecraft,new BlockPos(45,80,74),0.25f);
            minecraft.level.destroyBlockProgress(STONE_BREAKER,CRACK_STONE,5);
            minecraft.level.destroyBlockProgress(CHEST_BREAKER,CRACK_CHEST,4);
            if ((System.nanoTime()-started)/1e9<Math.max(15,Integer.getInteger("trinium.testWarmup",20))) return;
            require(LIDS.size()==4,"Open-chest fixture did not arrive at client");
            previousYaw=minecraft.player.getYRot();previousPitch=minecraft.player.getXRot();
            previousOldYaw=minecraft.player.yRotO;previousOldPitch=minecraft.player.xRotO;
            previousScreen=minecraft.gui.screen();
            paused=true;pausedAt=System.nanoTime();
            minecraft.gui.setScreen(new StillScreen());
            rotate(minecraft);
        } catch (Throwable failure) {finish(minecraft,failure);}
    }

    private static void setup(Minecraft minecraft) {
        var server=minecraft.getSingleplayerServer();
        server.execute(()->{
            try {
                var source=server.createCommandSourceStack();
                java.util.function.Consumer<String> command=text->server.getCommands().performPrefixedCommand(source,text);
                command.accept("gamemode spectator @a");
                command.accept("gamerule minecraft:spawn_mobs false");
                command.accept("time set noon");command.accept("weather clear");
                command.accept("kill @e[type=!minecraft:player,x=30,y=78,z=62,dx=20,dy=14,dz=20]");
                command.accept("fill 30 79 62 49 79 82 minecraft:stone");
                command.accept("fill 30 80 62 49 90 82 minecraft:air");
                for (int x=33;x<=45;x+=3) for (int z=65;z<=77;z+=3)
                    command.accept("setblock "+x+" 80 "+z+" minecraft:chest[facing=south]");
                // For SOUTH, LEFT connects west and RIGHT connects east.
                command.accept("setblock 37 80 70 minecraft:chest[facing=south,type=right]");
                command.accept("setblock 38 80 70 minecraft:chest[facing=south,type=left]");
                command.accept("setblock 43 80 70 minecraft:chest[facing=south,type=right]");
                command.accept("setblock 44 80 70 minecraft:chest[facing=south,type=left]");
                command.accept("setblock 46 80 73 minecraft:oak_sign[rotation=8]");
                command.accept("setblock 42 80 74 minecraft:stone");
                command.accept("summon minecraft:armor_stand 40.5 80 68.5 {NoGravity:1b,ShowArms:1b}");
                // Center Z inside the chest grid: at every tested yaw, several
                // complete conservative boxes lie behind the camera plane, even
                // at a wide FOV. The previous south-edge camera admitted them all.
                command.accept("tp @a 40.5 82 71.5 180 25");
                fixtureReady=true;
                System.out.println("TRINIUM_CHEST_CULLING_FIXTURE_READY one section, singles/doubles/open lids/sign/entity/breaking");
            } catch (Throwable failure) {fixtureError=failure;}
        });
    }

    private static void forceLid(Minecraft minecraft,BlockPos position,float value) throws ReflectiveOperationException {
        if (!(minecraft.level.getBlockEntity(position) instanceof ChestBlockEntity chest)) return;
        LidSnapshot snapshot=LIDS.get(chest);
        if (snapshot==null) {snapshot=LidSnapshot.capture(chest);LIDS.put(chest,snapshot);}
        snapshot.open.setFloat(snapshot.controller,value);
        snapshot.previous.setFloat(snapshot.controller,value);
        snapshot.requested.setBoolean(snapshot.controller,true);
    }

    private static void rotate(Minecraft minecraft) {
        minecraft.player.setYRot(YAWS[view]);minecraft.player.yRotO=YAWS[view];
        minecraft.player.setXRot(25);minecraft.player.xRotO=25;
        settleFrames=8;
    }

    public static boolean ready(Minecraft minecraft) {
        if (done || !paused || minecraft.level==null || minecraft.player==null) return false;
        if ((System.nanoTime()-pausedAt)/1e9>90) {finish(minecraft,new AssertionError("Culling oracle did not settle"));return false;}
        if (settleFrames>0) {settleFrames--;return false;}
        return true;
    }

    public static void renderView(Minecraft minecraft,LevelRenderState state,Runnable render) {
        // Newly visible terrain is allowed to upload normally before measuring.
        if (!state.sectionUpdateRenderStates.isEmpty()) {render.run();return;}
        var originalEntities=new ArrayList<>(state.entityRenderStates);
        var originalBlocks=new ArrayList<>(state.blockEntityRenderStates);
        var originalBreaking=new ArrayList<>(state.blockBreakingRenderStates);
        var originalParticles=new ArrayList<>(state.particlesRenderState.particles);
        Boolean previousEnabled=null;
        Throwable failure=null;
        try {
            var subject=Class.forName("fr.pierrecreeper.trinium.graphics.ChestFrustumCulling");
            Field enabled=subject.getField("renderingEnabled");
            require(subject.getField("REQUESTED").getBoolean(null),"Culling disabled by launch configuration");
            previousEnabled=enabled.getBoolean(null);
            var camera=minecraft.gameRenderer.mainCamera();
            require(Math.abs(camera.yRot()-YAWS[view])<0.01,"Camera did not adopt the requested test view");
            long tick=minecraft.level.getGameTime();float partial=state.worldPartialTicks;
            Map<String,Object> extraction=new LinkedHashMap<>();
            EXTRACTION_DIAGNOSTICS.add(extraction);
            extraction.put("view",view);extraction.put("yaw",camera.yRot());extraction.put("pitch",camera.xRot());
            extraction.put("cameraPosition",List.of(camera.position().x,camera.position().y,camera.position().z));
            extraction.put("cameraBlock",position(camera.blockPosition()));
            extraction.put("partialTicks",partial);extraction.put("gameTick",tick);
            extraction.put("capturedFrustum",camera.getCapturedFrustum()!=null);
            extraction.put("frustumClass",camera.getCullFrustum()==null?"null":camera.getCullFrustum().getClass().getName());
            extraction.put("visibleSections",minecraft.levelRenderer.visibleSections().size());
            extraction.put("originalBlockStates",originalBlocks.size());
            extraction.put("extractorMethods",java.util.Arrays.stream(minecraft.levelExtractor.getClass().getDeclaredMethods())
                .map(java.lang.reflect.Method::getName).filter(name->name.contains("extractVisibleBlockEntities") || name.contains("chestScope")).sorted().toList());

            // Keep the old route as a diagnostic control. MixinExtras may bind an
            // accessor to a renamed original body, bypassing the surrounding scope.
            var invoker=(ChestCullingExtractorInvoker)(Object)minecraft.levelExtractor;
            enabled.setBoolean(null,false);state.blockEntityRenderStates.clear();
            long invokerBefore=subject.getField("tested").getLong(null);
            invoker.trinium$extractVisibleBlockEntities(camera,partial,state);
            var invokerBaseline=new ArrayList<>(state.blockEntityRenderStates);
            long invokerOffTests=subject.getField("tested").getLong(null)-invokerBefore;
            enabled.setBoolean(null,true);state.blockEntityRenderStates.clear();
            long invokerTested=subject.getField("tested").getLong(null),invokerCulled=subject.getField("culled").getLong(null);
            invoker.trinium$extractVisibleBlockEntities(camera,partial,state);
            extraction.put("invokerOff",invokerBaseline.size());extraction.put("invokerOn",state.blockEntityRenderStates.size());
            extraction.put("invokerOffTests",invokerOffTests);
            extraction.put("invokerOnTests",subject.getField("tested").getLong(null)-invokerTested);
            extraction.put("invokerOnCulled",subject.getField("culled").getLong(null)-invokerCulled);

            // Invoke the nominal vanilla entry point, which owns the WrapMethod
            // scope. The real caller uses this name; do not target renamed bodies.
            var extract=minecraft.levelExtractor.getClass().getDeclaredMethod("extractVisibleBlockEntities",
                net.minecraft.client.Camera.class,float.class,LevelRenderState.class);
            extract.setAccessible(true);
            extraction.put("renderedExtractionRoute","nominal-private-method-reflection");
            enabled.setBoolean(null,false);state.blockEntityRenderStates.clear();
            long offBefore=subject.getField("tested").getLong(null);
            extract.invoke(minecraft.levelExtractor,camera,partial,state);
            var baseline=new ArrayList<>(state.blockEntityRenderStates);
            extraction.put("nominalOffTests",subject.getField("tested").getLong(null)-offBefore);
            enabled.setBoolean(null,true);state.blockEntityRenderStates.clear();
            long testedBefore=subject.getField("tested").getLong(null),culledBefore=subject.getField("culled").getLong(null);
            extract.invoke(minecraft.levelExtractor,camera,partial,state);
            var filtered=new ArrayList<>(state.blockEntityRenderStates);
            long tested=subject.getField("tested").getLong(null)-testedBefore;
            long culled=subject.getField("culled").getLong(null)-culledBefore;
            extraction.put("baseline",baseline.size());extraction.put("filtered",filtered.size());
            extraction.put("tested",tested);extraction.put("culled",culled);
            extraction.put("enabledAfterOn",subject.getMethod("enabled").invoke(null));
            extraction.put("baselineStates",describeStates(minecraft,subject,baseline));
            extraction.put("filteredStates",describeStates(minecraft,subject,filtered));
            require(tick==minecraft.level.getGameTime() && partial==state.worldPartialTicks,"Tick changed during paired extraction");
            require(originalEntities.equals(state.entityRenderStates) && originalBreaking.equals(state.blockBreakingRenderStates),
                "Block extraction changed entity or breaking lists");
            var removed=removedChests(baseline,filtered);
            long sameSection=removed.stream().filter(chest->sameSection(chest.blockPos,camera.blockPosition())).count();
            extraction.put("removed",removed.size());extraction.put("sameSection",sameSection);
            extraction.put("removedPositions",removed.stream().map(chest->position(chest.blockPos)).toList());
            Files.writeString(minecraft.gameDirectory.toPath().resolve("chest-culling-extraction.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(EXTRACTION_DIAGNOSTICS)+"\n");
            System.out.println("TRINIUM_CHEST_EXTRACTION_DIAGNOSTIC view="+view+" invoker="+invokerBaseline.size()+"/"+extraction.get("invokerOn")
                +" invokerTests="+extraction.get("invokerOnTests")+" nominal="+baseline.size()+"/"+filtered.size()
                +" tested="+tested+" culled="+culled+" removed="+removed.size()+" sameSection="+sameSection);
            require(!removed.isEmpty() && sameSection>0 && tested>0 && culled>=removed.size(),
                "No real chest filtered from a camera-visible section: off="+baseline.size()+", on="+filtered.size()
                    +", tested="+tested+", culled="+culled+", removed="+removed.size()+", sameSection="+sameSection);
            Map<String,Object> result=new LinkedHashMap<>();
            result.put("view",view);result.put("yaw",YAWS[view]);result.put("pitch",25);
            result.put("gameTick",tick);result.put("partialTicks",partial);
            result.put("extractedOff",baseline.size());result.put("extractedOn",filtered.size());
            result.put("tested",tested);result.put("culled",culled);result.put("removedChests",removed.size());
            result.put("removedInCameraSection",sameSection);
            result.put("nonChestStates",filtered.stream().filter(item->!(item instanceof ChestRenderState)).count());
            result.put("entityStates",originalEntities.size());result.put("breakingStates",originalBreaking.size());
            result.put("chestBreakingOverlays",baseline.stream().filter(item->item instanceof ChestRenderState && item.breakProgress!=null).count());
            long singles=0,doubles=0,open=0,edges=0;
            for (var item:filtered) if (item instanceof ChestRenderState chest) {
                if (chest.type==ChestType.SINGLE) singles++; else doubles++;
                if (chest.open>0.01f) open++;
                AABB bounds=(AABB)subject.getMethod("bounds",BlockPos.class).invoke(null,chest.blockPos);
                if (crossesScreenEdge(camera,bounds)) edges++;
            }
            result.put("retainedSingles",singles);result.put("retainedDoubleHalves",doubles);
            result.put("retainedOpenLids",open);result.put("retainedScreenEdgeBounds",edges);
            result.put("warmupRenders",WARMUP_RENDERS_PER_VIEW);
            // Rotating the camera can leave render-side terrain updates pending
            // even after extraction's upload list is empty. Consume a fixed,
            // declared number of complete baseline renders before the oracle.
            // These are preparation, not discarded parity attempts: there is one
            // measured A/B/A below, and any remaining instability still fails.
            for (int warmup=0;warmup<WARMUP_RENDERS_PER_VIEW;warmup++) {
                enabled.setBoolean(null,false);
                restore(state.entityRenderStates,originalEntities);restore(state.blockEntityRenderStates,baseline);
                restore(state.blockBreakingRenderStates,originalBreaking);restore(state.particlesRenderState.particles,originalParticles);
                render.run();
            }
            require(tick==minecraft.level.getGameTime(),"Tick advanced during render preparation");
            enabled.setBoolean(null,false);
            restore(state.entityRenderStates,originalEntities);restore(state.blockEntityRenderStates,baseline);
            restore(state.blockBreakingRenderStates,originalBreaking);restore(state.particlesRenderState.particles,originalParticles);
            render.run();var a=ChestCullingPixels.capture(minecraft,"culling-"+view+"-off-a");
            enabled.setBoolean(null,true);
            restore(state.entityRenderStates,originalEntities);restore(state.blockEntityRenderStates,filtered);
            restore(state.blockBreakingRenderStates,originalBreaking);restore(state.particlesRenderState.particles,originalParticles);
            render.run();var b=ChestCullingPixels.capture(minecraft,"culling-"+view+"-on-b");
            enabled.setBoolean(null,false);
            restore(state.entityRenderStates,originalEntities);restore(state.blockEntityRenderStates,baseline);
            restore(state.blockBreakingRenderStates,originalBreaking);restore(state.particlesRenderState.particles,originalParticles);
            render.run();var c=ChestCullingPixels.capture(minecraft,"culling-"+view+"-off-c");
            long colorAC=a.colorDifference(c),colorAB=a.colorDifference(b),colorBC=b.colorDifference(c);
            long depthAC=a.depthDifference(c),depthAB=a.depthDifference(b),depthBC=b.depthDifference(c);
            result.put("pixels",a.depth().length);result.put("colorAC",colorAC);result.put("colorAB",colorAB);result.put("colorBC",colorBC);
            result.put("depthAC",depthAC);result.put("depthAB",depthAB);result.put("depthBC",depthBC);
            boolean passed=colorAC==0 && colorAB==0 && colorBC==0 && depthAC==0 && depthAB==0 && depthBC==0;
            result.put("passed",passed);RESULTS.add(result);
            require(passed,"Exact color/depth parity failed, or repeated baseline was unstable");
            require(tick==minecraft.level.getGameTime(),"Tick advanced between A/B/A renders");
        } catch (Throwable error) {failure=error;}
        finally {
            restore(state.entityRenderStates,originalEntities);restore(state.blockEntityRenderStates,originalBlocks);
            restore(state.blockBreakingRenderStates,originalBreaking);restore(state.particlesRenderState.particles,originalParticles);
            if (previousEnabled!=null) try {
                Class.forName("fr.pierrecreeper.trinium.graphics.ChestFrustumCulling").getField("renderingEnabled").setBoolean(null,previousEnabled);
            } catch (Throwable restoreFailure) {if (failure==null) failure=restoreFailure;else failure.addSuppressed(restoreFailure);}
        }
        if (failure!=null) {finish(minecraft,failure);return;}
        if (++view==YAWS.length) {
            try {
                for (String coverage:new String[]{"retainedSingles","retainedDoubleHalves","retainedOpenLids","retainedScreenEdgeBounds",
                        "nonChestStates","entityStates","breakingStates","chestBreakingOverlays"})
                    require(RESULTS.stream().mapToLong(item->((Number)item.get(coverage)).longValue()).sum()>0,"Missing fixture coverage: "+coverage);
                finish(minecraft,null);
            } catch (Throwable error) {finish(minecraft,error);}
        } else rotate(minecraft);
    }

    private static List<ChestRenderState> removedChests(List<BlockEntityRenderState> baseline,List<BlockEntityRenderState> filtered) {
        var removed=new ArrayList<ChestRenderState>();int next=0;
        for (var candidate:baseline) {
            if (next<filtered.size() && sameIdentity(candidate,filtered.get(next))) {next++;continue;}
            require(candidate instanceof ChestRenderState,"Culling removed or reordered a non-chest state");
            removed.add((ChestRenderState)candidate);
        }
        require(next==filtered.size(),"Filtered extraction inserted or reordered render states");
        return removed;
    }

    private static boolean sameIdentity(BlockEntityRenderState a,BlockEntityRenderState b) {
        if (a.getClass()!=b.getClass() || !a.blockPos.equals(b.blockPos) || a.blockEntityType!=b.blockEntityType || a.lightCoords!=b.lightCoords) return false;
        if ((a.breakProgress==null)!=(b.breakProgress==null)) return false;
        return !(a instanceof ChestRenderState x) || b instanceof ChestRenderState y && x.type==y.type && x.facing==y.facing
            && x.material==y.material && Float.floatToRawIntBits(x.open)==Float.floatToRawIntBits(y.open);
    }

    private static boolean sameSection(BlockPos a,BlockPos b) {
        return (a.getX()>>4)==(b.getX()>>4) && (a.getY()>>4)==(b.getY()>>4) && (a.getZ()>>4)==(b.getZ()>>4);
    }

    private static List<Integer> position(BlockPos position) {
        return List.of(position.getX(),position.getY(),position.getZ());
    }

    private static List<Map<String,Object>> describeStates(Minecraft minecraft,Class<?> subject,
            List<BlockEntityRenderState> states) throws ReflectiveOperationException {
        var camera=minecraft.gameRenderer.mainCamera();var descriptions=new ArrayList<Map<String,Object>>();
        var dispatcher=minecraft.levelRenderer.blockEntityRenderDispatcher();
        for (var state:states) {
            Map<String,Object> item=new LinkedHashMap<>();
            item.put("class",state.getClass().getName());item.put("position",position(state.blockPos));
            item.put("cameraSection",sameSection(state.blockPos,camera.blockPosition()));
            item.put("breaking",state.breakProgress!=null);
            var entity=minecraft.level.getBlockEntity(state.blockPos);
            item.put("blockEntityClass",entity==null?"null":entity.getClass().getName());
            var renderer=entity==null?null:dispatcher.getRenderer(entity);
            item.put("rendererClass",renderer==null?"null":renderer.getClass().getName());
            if (state instanceof ChestRenderState chest) {
                item.put("chestType",chest.type.name());item.put("open",chest.open);
                AABB bounds=(AABB)subject.getMethod("bounds",BlockPos.class).invoke(null,state.blockPos);
                item.put("referenceFrustumVisible",camera.getCullFrustum()!=null && camera.getCullFrustum().isVisible(bounds));
                item.put("supported",subject.getMethod("supported",net.minecraft.world.level.block.entity.BlockEntity.class,
                    net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher.class,boolean.class).invoke(null,entity,dispatcher,false));
            }
            descriptions.add(item);
        }
        return descriptions;
    }

    private static boolean crossesScreenEdge(net.minecraft.client.Camera camera,AABB bounds) {
        Matrix4f matrix=camera.getViewRotationProjectionMatrix(new Matrix4f());var p=camera.position();
        float minX=Float.POSITIVE_INFINITY,maxX=Float.NEGATIVE_INFINITY,minY=minX,maxY=maxX;
        for (int corner=0;corner<8;corner++) {
            var clip=matrix.transform(new Vector4f((float)(((corner&1)==0?bounds.minX:bounds.maxX)-p.x),
                (float)(((corner&2)==0?bounds.minY:bounds.maxY)-p.y),(float)(((corner&4)==0?bounds.minZ:bounds.maxZ)-p.z),1));
            if (clip.w<=0) return false;
            float x=clip.x/clip.w,y=clip.y/clip.w;
            minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);
        }
        boolean horizontal=(minX<-1 && maxX>-1 || minX<1 && maxX>1) && minY<1 && maxY>-1;
        boolean vertical=(minY<-1 && maxY>-1 || minY<1 && maxY>1) && minX<1 && maxX>-1;
        return horizontal || vertical;
    }

    private static <T> void restore(List<T> target,List<T> saved) {target.clear();target.addAll(saved);}
    private static void require(boolean condition,String message) {if (!condition) throw new AssertionError(message);}

    private static void finish(Minecraft minecraft,Throwable failure) {
        if (done) return;
        done=true;
        try {
            for (var snapshot:LIDS.values()) snapshot.restore();
            if (minecraft.level!=null) {
                minecraft.level.destroyBlockProgress(STONE_BREAKER,CRACK_STONE,-1);
                minecraft.level.destroyBlockProgress(CHEST_BREAKER,CRACK_CHEST,-1);
            }
            if (paused && minecraft.player!=null) {
                minecraft.player.setYRot(previousYaw);minecraft.player.yRotO=previousOldYaw;
                minecraft.player.setXRot(previousPitch);minecraft.player.xRotO=previousOldPitch;
            }
            if (paused) minecraft.gui.setScreen(previousScreen);
            for (int i=RESTORE.size()-1;i>=0;i--) RESTORE.get(i).run();
        } catch (Throwable restoreFailure) {if (failure==null) failure=restoreFailure;else failure.addSuppressed(restoreFailure);}
        finally {
            Map<String,Object> result=new LinkedHashMap<>();
            result.put("methodology","Same-tick paired extraction, three declared baseline preparation renders, then one exact A/B/A attempt without a game tick");
            result.put("warmupRendersPerView",WARMUP_RENDERS_PER_VIEW);
            result.put("parityAttemptsPerView",1);
            result.put("exactColorComparison",true);result.put("exactDepthComparison",true);
            result.put("views",RESULTS);result.put("expectedViews",YAWS.length);
            result.put("extractionDiagnostics",EXTRACTION_DIAGNOSTICS);
            result.put("passed",failure==null && RESULTS.size()==YAWS.length);
            result.put("error",failure==null?"":failure.toString());
            try {
                Files.writeString(minecraft.gameDirectory.toPath().resolve("chest-culling-parity.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result)+"\n");
                System.out.println("TRINIUM_CHEST_CULLING_PARITY "+result.get("passed")+" views="+RESULTS.size()+" error="+result.get("error"));
                Class.forName("fr.pierrecreeper.trinium.Trinium").getMethod("writeReport").invoke(null);
            } catch (Throwable reportingFailure) {reportingFailure.printStackTrace();}
            if (failure!=null) failure.printStackTrace();
            minecraft.stop();
        }
    }

    private record LidSnapshot(Object controller,Field requested,Field open,Field previous,boolean oldRequested,float oldOpen,float oldPrevious) {
        static LidSnapshot capture(ChestBlockEntity chest) throws ReflectiveOperationException {
            Field lid=ChestBlockEntity.class.getDeclaredField("chestLidController");lid.setAccessible(true);Object controller=lid.get(chest);
            Field requested=controller.getClass().getDeclaredField("shouldBeOpen");requested.setAccessible(true);
            Field open=controller.getClass().getDeclaredField("openness");open.setAccessible(true);
            Field previous=controller.getClass().getDeclaredField("oOpenness");previous.setAccessible(true);
            return new LidSnapshot(controller,requested,open,previous,requested.getBoolean(controller),open.getFloat(controller),previous.getFloat(controller));
        }
        void restore() throws IllegalAccessException {requested.setBoolean(controller,oldRequested);open.setFloat(controller,oldOpen);previous.setFloat(controller,oldPrevious);}
    }

    private static final class StillScreen extends Screen {
        StillScreen() {super(Component.literal("Chest culling pixel parity"));}
        @Override public boolean isPauseScreen() {return true;}
        @Override public void extractBackground(GuiGraphicsExtractor graphics,int x,int y,float delta) { }
        @Override public void extractRenderState(GuiGraphicsExtractor graphics,int x,int y,float delta) { }
    }
}

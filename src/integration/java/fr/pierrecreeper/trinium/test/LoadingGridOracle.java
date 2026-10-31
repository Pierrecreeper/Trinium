// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.probe;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.progress.ChunkLoadStatusView;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import java.nio.file.*;

/** Deterministic actual-GPU comparison, separate test mod only. */
public final class LoadingGridOracle extends Screen {
    private int ticks, step;
    private boolean waiting;
    private static final ChunkStatus[] STATES = {null, ChunkStatus.EMPTY, ChunkStatus.TERRAIN,
        ChunkStatus.BIOMES, ChunkStatus.STRUCTURE_STARTS, ChunkStatus.FEATURES, ChunkStatus.LIGHT, ChunkStatus.FULL};
    private static final ChunkLoadStatusView STATUS = new ChunkLoadStatusView() {
        public void moveTo(ResourceKey<Level> dimension, ChunkPos position) {}
        public int radius() { return 31; }
        public ChunkStatus get(int x, int y) { return STATES[Math.floorMod(x*7+y*13,STATES.length)]; }
    };
    public LoadingGridOracle() { super(Component.literal("Loading grid oracle")); }
    @Override public void extractBackground(GuiGraphicsExtractor g,int mx,int my,float delta) {}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta) {
        System.setProperty("trinium.disableLoadingGrid",Boolean.toString((step&1)==0));
        int scenario=step/2;
        g.fill(0,0,width,height,0xff183044);
        // Intersecting elements before and after the batch expose ordering mistakes.
        g.fill(width/2-85,height/2-70,width/2+50,height/2+60,0xff603020);
        g.pose().pushMatrix();
        if (scenario==1) g.enableScissor(width/2-48,height/2-40,width/2+45,height/2+50);
        if (scenario==2) { g.pose().translate(17.25f,-7.5f);g.pose().scale(1.13f,.81f); }
        if (scenario==3) { g.pose().translate(width/2f,height/2f);g.pose().rotate(.17f);g.pose().translate(-width/2f,-height/2f); }
        ChunkLoadStatusView status=scenario==6?new ChunkLoadStatusView() {
            public void moveTo(ResourceKey<Level> dimension,ChunkPos position) {}
            public int radius(){return 65;}
            public ChunkStatus get(int x,int y){return STATUS.get(x,y);}
        }:STATUS;
        LevelLoadingScreen.extractChunksForRendering(g,width/2,height/2,2,scenario==1?1:0,status);
        if(scenario==4 || scenario==5 || scenario==7) {
            // Simulate another mod inserting text/layer transitions between captured fills.
            fr.pierrecreeper.trinium.graphics.LoadingGridBatch scope=(step&1)==0?null:new fr.pierrecreeper.trinium.graphics.LoadingGridBatch(g);
            try {
                g.fill(width/2-50,height/2-30,width/2+50,height/2+30,0xffa02030);
                if(scenario==4)g.text(font,"Injected overlay",width/2-40,height/2-5,0xffffffff);
                else if(scenario==5)g.nextStratum();
                else {
                    try(var inner=(step&1)==0?null:new fr.pierrecreeper.trinium.graphics.LoadingGridBatch(g)) {
                        g.fill(width/2-25,height/2-10,width/2+25,height/2+10,0xff1090a0);
                    }
                }
                g.fill(width/2-30,height/2,width/2+30,height/2+20,0x9080c050);
            } finally {if(scope!=null)scope.close();}
        }
        if (scenario==1) g.disableScissor();
        g.pose().popMatrix();
        g.fill(width/2-18,height/2-17,width/2+22,height/2+26,0x9060f0a0);
    }
    @Override public void tick() {
        if (++ticks%8!=0 || waiting) return;
        if(step<16) {
            waiting=true;
            String name="loading-grid-"+(step/2)+((step&1)==0?"-reference":"-batch")+".png";
            Screenshot.grab(minecraft.gameDirectory,name,minecraft.gameRenderer.mainRenderTarget(),1,message->{
                minecraft.execute(()->{step++;waiting=false;});
            });
        } else {
            System.clearProperty("trinium.disableLoadingGrid");
            try {
                var batch=fr.pierrecreeper.trinium.graphics.LoadingGridBatch.class;
                long count=batch.getField("batches").getLong(null), cells=batch.getField("rectangles").getLong(null);
                if(count<4 || cells<count)throw new AssertionError("Loading batch never executed: batches="+count+", rectangles="+cells);
                Files.writeString(minecraft.gameDirectory.toPath().resolve("loading-grid-result.json"),
                    "{\"scenarios\":8,\"batches\":"+count+",\"rectangles\":"+cells+"}\n");
            } catch(Exception e) {throw new RuntimeException(e);}
            minecraft.stop();
        }
    }
}

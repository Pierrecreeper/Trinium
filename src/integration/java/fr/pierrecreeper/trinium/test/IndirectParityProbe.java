package fr.pierrecreeper.trinium.probe;

import com.mojang.blaze3d.systems.RenderSystem;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.network.chat.Component;

/** TEST ONLY: never overrides a driver workaround in the distributed mod. */
public final class IndirectParityProbe {
    private static int frame;
    private static int hashA,hashB,hashC;
    private static boolean started,finished;
    private static Screen previous;
    private IndirectParityProbe() {}

    public static void start(Minecraft mc) throws ReflectiveOperationException {
        if (started) return;
        if (mc.getSingleplayerServer()==null) throw new IllegalStateException("Local test world required");
        var info=RenderSystem.getDevice().getDeviceInfo();
        if (info.limits().maxDrawIndirectDrawCount()<=0 || !info.features().nonZeroFirstInstance())
            throw new IllegalStateException("Required indirect capabilities unavailable");
        System.out.println("TRINIUM_INDIRECT_EXPERIMENT knownIssues="+info.hintsAndWorkarounds().multiDrawIndirectHasKnownIssues()
            +" maxDraws="+info.limits().maxDrawIndirectDrawCount()+" nonZeroFirstInstance="+info.features().nonZeroFirstInstance());
        Field available=LevelRenderer.class.getDeclaredField("multiDrawIndirectAvailable");
        available.setAccessible(true);
        available.setBoolean(mc.levelRenderer,true);
        mc.multiDrawIndirect=false;
        previous=mc.gui.screen();
        mc.gui.setScreen(new StillScreen());
        started=true;
    }

    /** After rendering: capture naive / indirect / naive at one paused camera. */
    public static void frame(Minecraft mc) {
        if (!started || finished) return;
        switch (++frame) {
            case 40 -> {hashA=meshHash(mc);capture(mc,"indirect-naive-a");}
            case 41 -> mc.multiDrawIndirect=true;
            case 81 -> {
                if (!mc.levelRenderer.isChunkRenderingUsingMultiDrawIndirect())
                    throw new AssertionError("Indirect terrain renderer was not activated");
                hashB=meshHash(mc);capture(mc,"indirect-mdi-b");
            }
            case 82 -> mc.multiDrawIndirect=false;
            case 122 -> {hashC=meshHash(mc);capture(mc,"indirect-naive-c");}
            case 123 -> {
                mc.multiDrawIndirect=true;
                mc.gui.setScreen(previous);
                finished=true;
                try {
                    Files.writeString(mc.gameDirectory.toPath().resolve("indirect-state.json"),
                        "{\"testOnly\":true,\"meshHashA\":"+hashA+",\"meshHashB\":"+hashB+",\"meshHashC\":"+hashC
                        +",\"meshStable\":"+(hashA==hashB&&hashB==hashC)+"}\n");
                } catch (Exception error) { throw new RuntimeException(error); }
                System.out.println("TRINIUM_INDIRECT_CAPTURE_COMPLETE meshStable="+(hashA==hashB&&hashB==hashC));
            }
        }
    }

    private static int meshHash(Minecraft mc) {
        int[] ids=mc.levelRenderer.visibleSections().stream()
            .mapToInt(section->System.identityHashCode(section.getSectionMesh())).toArray();
        Arrays.sort(ids);
        return Arrays.hashCode(ids);
    }
    private static void capture(Minecraft mc,String name) {
        Screenshot.grab(mc.gameDirectory,name+".png",mc.gameRenderer.mainRenderTarget(),1,
            message->System.out.println("TRINIUM_INDIRECT_SCREENSHOT "+name));
    }
    private static final class StillScreen extends Screen {
        private StillScreen() { super(Component.literal("Render parity test")); }
        @Override public boolean isPauseScreen() { return true; }
        @Override public void extractBackground(GuiGraphicsExtractor graphics,int x,int y,float delta) {}
        @Override public void extractRenderState(GuiGraphicsExtractor graphics,int x,int y,float delta) {}
    }
}

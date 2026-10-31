package fr.pierrecreeper.trinium.test;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import java.nio.file.*;
import java.util.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Public-server probe: measures rendering only. No input, movement, commands or packet hooks. */
@Mixin(value=Minecraft.class,remap=false)
public abstract class PassiveProbeMixin {
    @Unique private long trinium$start, trinium$joined, trinium$last;
    @Unique private boolean trinium$done;
    @Unique private ArrayList<Long> trinium$frames;
    @Inject(method="onGameLoadFinished",at=@At("TAIL"))
    private void trinium$start(CallbackInfo ci) {
        trinium$frames=new ArrayList<>();
        trinium$start=System.nanoTime();
        Minecraft mc=(Minecraft)(Object)this;
        mc.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
        mc.options.pauseOnLostFocus=false;
    }
    @Inject(method="runTick",at=@At("TAIL"))
    private void trinium$frame(boolean render,CallbackInfo ci) {
        long now=System.nanoTime();
        if(trinium$joined!=0 && now-trinium$joined>10_000_000_000L && !trinium$done && trinium$last!=0)
            trinium$frames.add(now-trinium$last);
        trinium$last=now;
    }
    @Inject(method="tick",at=@At("TAIL"))
    private void trinium$observe(CallbackInfo ci) {
        if(trinium$done || trinium$start==0)return;
        Minecraft mc=(Minecraft)(Object)this;
        long now=System.nanoTime();
        boolean joined=mc.player!=null && mc.level!=null && mc.getSingleplayerServer()==null;
        if(joined && trinium$joined==0) {trinium$joined=now;System.out.println("TRINIUM_PASSIVE_SERVER_JOINED");}
        if((trinium$joined!=0 && now-trinium$joined>40_000_000_000L) || now-trinium$start>120_000_000_000L) {
            trinium$done=true;
            try {
                Class.forName("fr.pierrecreeper.trinium.Trinium").getMethod("writeReport").invoke(null);
                var sorted=new ArrayList<>(trinium$frames);Collections.sort(sorted);
                String json="{\"passive\":true,\"multiplayerJoined\":"+(trinium$joined!=0)+",\"stillConnected\":"+joined
                    +",\"frames\":"+sorted.size()+",\"framesOver50ms\":"+sorted.stream().filter(n->n>50_000_000L).count()
                    +",\"meanFrameMs\":"+sorted.stream().mapToLong(Long::longValue).average().orElse(0)/1e6+"}\n";
                Files.writeString(mc.gameDirectory.toPath().resolve("passive-result.json"),json);
                Screenshot.grab(mc.gameDirectory,"passive-test.png",mc.gameRenderer.mainRenderTarget(),1,c->{});
                System.out.println("TRINIUM_PASSIVE_COMPLETE "+json);
            } catch(Exception e) {e.printStackTrace();}
            mc.stop();
        }
    }
}

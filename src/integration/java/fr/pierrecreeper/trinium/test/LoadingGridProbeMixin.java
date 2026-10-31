package fr.pierrecreeper.trinium.test;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=Minecraft.class,remap=false)
public abstract class LoadingGridProbeMixin {
    @Unique private boolean trinium$pending;
    @Inject(method="onGameLoadFinished",at=@At("TAIL"))
    private void trinium$ready(CallbackInfo ci){trinium$pending=true;}
    @Inject(method="tick",at=@At("TAIL"))
    private void trinium$open(CallbackInfo ci){
        Minecraft mc=(Minecraft)(Object)this;
        if(trinium$pending && mc.gui.overlay()==null){trinium$pending=false;mc.gui.setScreen(new fr.pierrecreeper.trinium.probe.LoadingGridOracle());}
    }
}

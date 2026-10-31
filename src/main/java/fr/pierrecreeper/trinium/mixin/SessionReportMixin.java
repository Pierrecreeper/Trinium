package fr.pierrecreeper.trinium.mixin;

import fr.pierrecreeper.trinium.Trinium;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** One diagnostic write at normal shutdown, with no recurring work during gameplay. */
@Mixin(value=Minecraft.class,remap=false)
public abstract class SessionReportMixin {
    @Inject(method="close",at=@At("HEAD"))
    private void trinium$saveSession(CallbackInfo ci) {
        try {Trinium.writeReport();}
        catch(Exception|LinkageError e){System.getLogger("Trinium").log(System.Logger.Level.WARNING,"Shutdown diagnostic unavailable",e);}
    }
}

package fr.pierrecreeper.trinium.mixin;

import com.google.common.collect.ImmutableSet;
import fr.pierrecreeper.trinium.shaders.IrisProgramCache;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Optional target: loaded only for the audited Iris version by TriniumMixinPlugin. */
@Pseudo
@Mixin(targets="net.irisshaders.iris.gl.program.ProgramBuilder",remap=false)
public abstract class IrisProgramCacheMixin {
    @Inject(method="begin",at=@At("HEAD"),cancellable=true)
    private static void trinium$load(String name,String vertex,String geometry,String fragment,ImmutableSet<Integer> reserved,CallbackInfoReturnable<Object> cir) {
        Object result=IrisProgramCache.load(IrisProgramCache.key(name,vertex,geometry,fragment),name,reserved);
        if(result!=null)cir.setReturnValue(result);
    }
    @Inject(method="begin",at=@At("RETURN"))
    private static void trinium$save(String name,String vertex,String geometry,String fragment,ImmutableSet<Integer> reserved,CallbackInfoReturnable<Object> cir) {
        IrisProgramCache.store(IrisProgramCache.key(name,vertex,geometry,fragment),cir.getReturnValue());
    }
}

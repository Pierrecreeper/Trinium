package fr.pierrecreeper.trinium.mixin;

import fr.pierrecreeper.trinium.shaders.IrisProgramCache;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

@Pseudo
@Mixin(targets="net.irisshaders.iris.gl.shader.ProgramCreator",remap=false)
public abstract class IrisBinaryHintMixin {
    @Redirect(method="create",at=@At(value="INVOKE",target="Lcom/mojang/renderpearl/backend/opengl/GlStateManager;glCreateProgram()I"))
    private static int trinium$retrievable(){return IrisProgramCache.createProgram();}
}

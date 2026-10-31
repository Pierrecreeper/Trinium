package fr.pierrecreeper.trinium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.frontend.shaders.GlslCompiler;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.util.ShaderCompileException;
import fr.pierrecreeper.trinium.Trinium;
import fr.pierrecreeper.trinium.shaders.ShaderCache;
import net.minecraft.client.renderer.ShaderDefines;
import org.spongepowered.asm.mixin.*;

@Mixin(value=GlslCompiler.class,remap=false)
public abstract class ShaderCacheMixin {
    @Shadow @Final private boolean isZeroToOne;
    @Shadow @Final private boolean shaderDrawParameters;
    @WrapMethod(method="compileToSpv")
    private SpvModule trinium$cached(String label,String source,ShaderType type,ShaderDefines defines,ShaderSource resolver,Operation<SpvModule> original) throws ShaderCompileException {
        final String prepared=source;
        if(!Trinium.active()||Boolean.getBoolean("trinium.disableShaderCache"))return original.call(label,source,type,defines,resolver);
        return ShaderCache.compile(Trinium.directory().resolve("shader-cache-v1"),label,source,type,defines,isZeroToOne,shaderDrawParameters,resolver,
            tracked->original.call(label,prepared,type,defines,tracked));
    }
}

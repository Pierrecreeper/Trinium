package fr.pierrecreeper.trinium.test;

import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import java.nio.file.*;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Local diagnostic only; generated Minecraft shader source is never distributed. */
@Mixin(targets="com.mojang.renderpearl.backend.opengl.GlPipelineRecompiler",remap=false)
public abstract class DirectShaderSourceProbeMixin {
    @Inject(method="decompileShaders",at=@At("RETURN"))
    private void trinium$sources(BackendRenderPipeline.CreateInfo info,
        CallbackInfoReturnable<Map<BackendRenderPipeline.CreateInfo.Shader,String>> cir) {
        if(info.name().contains("pipeline/solid_terrain") || info.name().contains("trinium:direct/")) {
            try {Files.writeString(Path.of("metadata-"+info.name().replace(':','_').replace('/','_')+".txt"),"buffers="+info.vertexBuffers()+"\nattributes="+info.attribBindings()+"\nuniforms="+info.uniforms()+"\n");}
            catch(java.io.IOException error){throw new RuntimeException(error);}
        }
        for (var entry:cir.getReturnValue().entrySet()) if(info.name().contains("trinium:direct/")) {
            try {Files.writeString(Path.of("decompiled-"+info.name().replace(':','_').replace('/','_')+"-"+entry.getKey().module().type().name()+".glsl"),entry.getValue());}
            catch(java.io.IOException error){throw new RuntimeException(error);}
        }
    }
}

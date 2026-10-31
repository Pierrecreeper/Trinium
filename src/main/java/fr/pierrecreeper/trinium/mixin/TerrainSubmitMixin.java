// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.mixin;

import com.mojang.renderpearl.api.buffers.*;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.*;
import fr.pierrecreeper.trinium.graphics.ScopedTerrainSubmit;
import java.util.*;
import net.minecraft.client.renderer.chunk.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=ChunkSectionsToRender.DrawSeparate.class,remap=false)
public abstract class TerrainSubmitMixin {
    @Shadow @Final private Map<ChunkSectionLayer,List<RenderPass.Draw<GpuBufferSlice[]>>> drawsPerLayer;
    @Shadow @Final private GpuBufferSlice[] chunkSectionInfos;
    @Inject(method="render",at=@At("HEAD"),cancellable=true)
    private void trinium$submit(ChunkSectionLayer layer,RenderPass pass,GpuBuffer indices,IndexType type,
        RenderPipeline override,RenderPipeline indirectOverride,CallbackInfo ci) {
        if(ScopedTerrainSubmit.render(layer,pass,indices,type,override,drawsPerLayer.get(layer),chunkSectionInfos)) ci.cancel();
    }
}

// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.mixin;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import fr.pierrecreeper.trinium.graphics.CachedTerrainDraw;
import net.minecraft.client.renderer.DynamicGpuData.IndexedDraw;
import org.spongepowered.asm.mixin.*;
import java.util.function.BiConsumer;

@Mixin(value=IndexedDraw.class,remap=false)
public abstract class TerrainIndexedDrawMixin implements CachedTerrainDraw {
    @Unique private RenderPass.Draw<GpuBufferSlice[]> trinium$cachedDraw;

    @Override public RenderPass.Draw<GpuBufferSlice[]> trinium$cachedDraw() { return trinium$cachedDraw; }

    @Override public RenderPass.Draw<GpuBufferSlice[]> trinium$draw(int slot,GpuBuffer vertex,GpuBuffer index,IndexType type,
        int first,int count,int base,BiConsumer<GpuBufferSlice[],RenderPass.UniformUploader> consumer) {
        var old=trinium$cachedDraw;
        if (old!=null && old.slot()==slot && old.vertexBuffer()==vertex && old.indexBuffer()==index
            && old.indexType()==type && old.firstIndex()==first && old.indexCount()==count && old.baseVertex()==base
            && old.uniformUploaderConsumer().getClass()==consumer.getClass()) { fr.pierrecreeper.trinium.Trinium.terrainRenderDrawHits++; return old; }
        fr.pierrecreeper.trinium.Trinium.terrainRenderDrawMisses++;
        return trinium$cachedDraw=new RenderPass.Draw<>(slot,vertex,index,type,first,count,base,consumer);
    }
}

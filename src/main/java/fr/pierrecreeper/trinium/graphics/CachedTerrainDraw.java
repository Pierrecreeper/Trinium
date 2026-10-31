// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.graphics;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import java.util.function.BiConsumer;

/** Implemented on an immutable IndexedDraw, never a global GPU resource registry. */
public interface CachedTerrainDraw {
    RenderPass.Draw<GpuBufferSlice[]> trinium$cachedDraw();
    RenderPass.Draw<GpuBufferSlice[]> trinium$draw(int slot,GpuBuffer vertex,GpuBuffer index,IndexType type,
        int first,int count,int base,BiConsumer<GpuBufferSlice[],RenderPass.UniformUploader> consumer);
}

// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.graphics;

import java.util.Arrays;
import net.minecraft.client.renderer.DynamicGpuData.IndexedDraw;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

/** One immutable command per layer, attached to the exact compiled mesh.
 * Visibility ordering (baseInstance) is part of the key, as are both GPU offsets. */
public final class TerrainDrawCache {

    private final IndexedDraw[] draws=new IndexedDraw[ChunkSectionLayer.values().length];

    public IndexedDraw resolve(ChunkSectionLayer layer,int count,int instances,int first,int base,int instance) {
        IndexedDraw previous=draws[layer.ordinal()];
        if (previous!=null && previous.indexCount()==count && previous.instanceCount()==instances
            && previous.firstIndex()==first && previous.baseVertex()==base && previous.baseInstance()==instance) {
            fr.pierrecreeper.trinium.Trinium.terrainDrawHits++; return previous;
        }
        IndexedDraw next=new IndexedDraw(count,instances,first,base,instance);
        draws[layer.ordinal()]=next; fr.pierrecreeper.trinium.Trinium.terrainDrawMisses++; return next;
    }
    /** Also releases any Draw/GPU-buffer references attached to these commands. */
    public void clear() { Arrays.fill(draws,null); }
}

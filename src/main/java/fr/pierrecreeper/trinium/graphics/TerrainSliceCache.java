package fr.pierrecreeper.trinium.graphics;

import fr.pierrecreeper.trinium.Trinium;
import java.util.Arrays;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSectionBufferSlice;

/**
 * One bounded cache per compiled mesh, under SectionRenderDispatcher's existing copyLock.
 * UberGpuBuffer invalidates it on allocation put/remove/clear, before freeing any referenced
 * resource. Vanilla allocations have immutable heap/offset and heaps have a final GpuBuffer;
 * growth creates a new heap instead of moving live allocations. No global mesh map is kept.
 */
public final class TerrainSliceCache {
    private static final boolean DISABLED = Boolean.getBoolean("trinium.disableTerrainCache");
    public static long hits, misses, invalidations;
    private Object dispatcher;
    private final RenderSectionBufferSlice[] slices;
    private int known;

    public TerrainSliceCache() {
        // Lazy per-mesh construction avoids initializing terrain enums during client setup.
        int layers = ChunkSectionLayer.values().length;
        if (layers > Integer.SIZE) throw new IllegalStateException("Too many terrain layers");
        slices = new RenderSectionBufferSlice[layers];
    }

    public static RenderSectionBufferSlice resolve(SectionRenderDispatcher dispatcher, SectionMesh mesh, ChunkSectionLayer layer) {
        if (DISABLED || !Trinium.active() || mesh.getClass() != CompiledSectionMesh.class || !(mesh instanceof TerrainSliceOwner owner))
            return dispatcher.getRenderSectionSlice(mesh, layer);
        TerrainSliceCache cache = owner.trinium$terrainSlices();
        int index = layer.ordinal(), bit = 1 << index;
        if (cache.dispatcher == dispatcher && (cache.known & bit) != 0) {
            hits++;
            return cache.slices[index];
        }
        // Preserve the exact vanilla query and its null result on a miss. There is no
        // new allocation lookup or record creation on subsequent unchanged frames.
        RenderSectionBufferSlice result = dispatcher.getRenderSectionSlice(mesh, layer);
        if (cache.dispatcher != dispatcher) {
            cache.clear();
            cache.dispatcher = dispatcher;
        }
        cache.slices[index] = result;
        cache.known |= bit;
        misses++;
        return result;
    }

    public static void invalidate(Object key) {
        if (key instanceof TerrainSliceOwner owner) owner.trinium$invalidateTerrainSlices();
    }

    public void clear() {
        if (known != 0) invalidations++;
        Arrays.fill(slices, null);
        dispatcher = null;
        known = 0;
    }
}

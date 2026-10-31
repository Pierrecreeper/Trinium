package fr.pierrecreeper.trinium.mixin;

import com.mojang.blaze3d.vertex.UberGpuBuffer;
import fr.pierrecreeper.trinium.graphics.TerrainSliceCache;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keep cached slices synchronized with the authoritative vanilla allocation map. */
@Mixin(value=UberGpuBuffer.class,remap=false)
public abstract class UberBufferInvalidationMixin {
    @Redirect(method="uploadStagedAllocations",at=@At(value="INVOKE",target="Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object trinium$allocationUploaded(Map<Object,Object> allocations,Object key,Object allocation) {
        Object previous = allocations.put(key,allocation);
        // Before the upload callback can expose the new mesh to rendering.
        TerrainSliceCache.invalidate(key);
        return previous;
    }
    @Redirect(method="freeAllocation",at=@At(value="INVOKE",target="Ljava/util/Map;remove(Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object trinium$allocationRemoved(Map<Object,Object> allocations,Object key) {
        Object previous = allocations.remove(key);
        // Before TLSF frees/reuses the address or upload closes a completely free heap.
        TerrainSliceCache.invalidate(key);
        return previous;
    }
    @Redirect(method="close",at=@At(value="INVOKE",target="Ljava/util/Map;clear()V"))
    private void trinium$allocationsClosed(Map<Object,Object> allocations) {
        for (Object key : allocations.keySet()) TerrainSliceCache.invalidate(key);
        allocations.clear();
    }
}

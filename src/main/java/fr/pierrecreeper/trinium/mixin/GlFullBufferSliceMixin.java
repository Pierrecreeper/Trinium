// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.mixin;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.common.BaseGpuBuffer;
import fr.pierrecreeper.trinium.Trinium;
import org.spongepowered.asm.mixin.*;

/** A full-buffer view is immutable. Keep one view on its owner, without a global map.
 * Range slices and custom subclasses overriding slice() keep their existing methods. */
@Mixin(value=BaseGpuBuffer.class,remap=false)
public abstract class GlFullBufferSliceMixin implements GpuBuffer {
    @Unique private static final boolean trinium$disabled=Boolean.getBoolean("trinium.disableGpuSliceCache");
    @Unique private volatile GpuBufferSlice trinium$fullSlice;

    @Override public GpuBufferSlice slice() {
        long size=size();
        if (trinium$disabled || !Trinium.active()) return new GpuBufferSlice(this,0,size);
        GpuBufferSlice cached=trinium$fullSlice;
        // Vanilla BaseGpuBuffer.size is final. Also handle a subclass changing size()
        // defensively, while preserving vanilla's ability to form a view after close.
        if (cached==null || cached.length()!=size) {
            trinium$fullSlice=cached=new GpuBufferSlice(this,0,size); Trinium.gpuFullSliceMisses++;
        } else Trinium.gpuFullSliceHits++;
        return cached;
    }
}

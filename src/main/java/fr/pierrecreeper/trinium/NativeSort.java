package fr.pierrecreeper.trinium;

import com.mojang.blaze3d.vertex.CompactVectorArray;
import com.mojang.blaze3d.vertex.VertexSorting;
import java.nio.*;
import java.util.concurrent.atomic.LongAdder;
import org.joml.Vector3f;

/** Exact stable descending Float.compare order; preserves vanilla's distance calculation. */
public final class NativeSort {
    public static final LongAdder calls = new LongAdder();
    public static final LongAdder quads = new LongAdder();
    public static final int MIN_QUADS = 128;
    public static final int MAX_QUADS = NativeRadix.MAX_QUADS;
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);
    private static final class Scratch {
        ByteBuffer buffer = ByteBuffer.allocateDirect(MIN_QUADS * 8).order(ByteOrder.LITTLE_ENDIAN);
        final Vector3f vector = new Vector3f();
        boolean busy;
        ByteBuffer buffer(int n) {
            if (buffer.capacity() < n * 8)
                buffer = ByteBuffer.allocateDirect(Integer.highestOneBit(n * 8 - 1) << 1).order(ByteOrder.LITTLE_ENDIAN);
            return buffer;
        }
    }
    private NativeSort() { }

    /** Returns null to let the original game method execute on an unavailable native path. */
    public static int[] sort(VertexSorting.DistanceFunction distance, CompactVectorArray vertices) {
        int n = vertices.size();
        if (n < MIN_QUADS || n > MAX_QUADS || !NativeBridge.available()) return null;
        Scratch scratch = SCRATCH.get();
        if (scratch.busy) return null; // A custom distance callback may recursively sort geometry.
        scratch.busy = true;
        try {
        ByteBuffer buffer = scratch.buffer(n);
        // Use the game's callback verbatim: no FMA/reassociation or camera snapshot changes.
        for (int i = 0; i < n; i++) buffer.putFloat(i * 4, distance.apply(vertices.get(i, scratch.vector)));
        try {
            if (sortChecked(buffer, n) != 0) throw new IllegalStateException("Rust sort rejected buffer");
        } catch (RuntimeException | LinkageError error) {
            NativeBridge.disable(error);
            return null;
        }
        int[] indices = new int[n];
        buffer.asIntBuffer().get(n, indices);
        calls.increment(); quads.add(n);
        return indices;
        } finally { scratch.busy = false; }
    }

    static int sortChecked(ByteBuffer buffer, int count) {
        return NativeRadix.sort(buffer, count);
    }
}

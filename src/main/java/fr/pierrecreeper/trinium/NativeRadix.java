package fr.pierrecreeper.trinium;

import java.nio.*;

/** The buffer ABI is independent of Minecraft, so the packaged JAR can self-test it. */
final class NativeRadix {
    static final int MAX_QUADS = 262144;
    private NativeRadix() { }
    private static native int sort0(ByteBuffer distancesAndIndices, int count);

    static int sort(ByteBuffer buffer, int count) {
        if (buffer == null || !buffer.isDirect() || buffer.isReadOnly()
                || count < 0 || count > MAX_QUADS || buffer.capacity() < count * 8)
            throw new IllegalArgumentException("Writable direct sort buffer of at least 8*n bytes required");
        return sort0(buffer, count);
    }

    static void check() {
        float[] values = {3,1,3,-0.0f,0.0f,Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY};
        int[] expected = {5,6,0,2,1,4,3,7};
        ByteBuffer buffer = ByteBuffer.allocateDirect(values.length*8).order(ByteOrder.LITTLE_ENDIAN);
        for (int i=0;i<values.length;i++) buffer.putFloat(i*4,values[i]);
        if (sort(buffer,values.length) != 0) throw new IllegalStateException("Native radix self-test rejected buffer");
        for (int i=0;i<expected.length;i++) if (buffer.getInt((values.length+i)*4) != expected[i])
            throw new IllegalStateException("Native radix self-test mismatch at " + i);
    }
}

package fr.pierrecreeper.trinium;

import java.util.BitSet;

/**
 * Exact conservative section connectivity, independently implemented as a row-span fill.
 * Cell index x | z<<4 | y<<8. The per-worker arrays are primitive, bounded and reused.
 * No per-cell queue, no per-node object, no locking; input is never modified.
 */
public final class VisibilitySolver {
    public static final long ALL = (1L << 36) - 1;
    public static final int SCRATCH_PAYLOAD_BYTES = 256 * (2 + 2 + 4);
    private static final long[] CLIQUES = makeCliques();
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);
    private VisibilitySolver() { }

    public static long resolve(BitSet opaque) {
        return resolve(opaque, opaque.cardinality());
    }
    static long resolve(BitSet opaque, int count) {
        if (opaque.length() > 4096) throw new IllegalArgumentException("Section exceeds 4096 cells");
        if (count < 256) return ALL;
        if (count == 4096) return 0;
        return resolveWordsKnownCount(opaque.toLongArray());
    }
    public static long resolveWords(long[] words) {
        if (words.length > 64) throw new IllegalArgumentException("At most 64 words");
        int count = 0;
        for (long word : words) count += Long.bitCount(word);
        if (count < 256) return ALL;
        if (count == 4096) return 0;
        return resolveWordsKnownCount(words);
    }
    static long resolveWordsKnownCount(long[] words) {
        Scratch s = SCRATCH.get();
        // Reset even after an early ALL exit: no previous task may leak into a new one.
        for (int i=0; i<64; i++) {
            long empty = ~(i < words.length ? words[i] : 0L);
            int r = i << 2;
            s.free[r] = (short) empty;
            s.free[r+1] = (short) (empty >>> 16);
            s.free[r+2] = (short) (empty >>> 32);
            s.free[r+3] = (short) (empty >>> 48);
        }
        java.util.Arrays.fill(s.pending, (short)0);
        long result = 0L;
        for (int start=0; start<256; start++) {
            int y = start >>> 4, z = start & 15;
            int boundary = (y == 0 || y == 15 || z == 0 || z == 15) ? 0xffff : 0x8001;
            int candidates;
            while ((candidates = (s.free[start] & 0xffff) & boundary) != 0) {
                int head=0, tail=1, faces=0;
                s.queue[0] = start;
                s.pending[start] = (short) Integer.lowestOneBit(candidates);
                while (head < tail) {
                    int row = s.queue[head++ & 255];
                    int free = s.free[row] & 0xffff;
                    int span = (s.pending[row] & 0xffff) & free;
                    s.pending[row] = 0;
                    if (span == 0) continue;
                    // At most 15 expansions. Several contiguous empty cells per operation.
                    int old;
                    do {
                        old = span;
                        span |= ((span << 1) | (span >>> 1)) & free;
                    } while (span != old);
                    s.free[row] = (short) (free & ~span);
                    int ry = row >>> 4, rz = row & 15;
                    if (ry==0) faces |= 1;
                    if (ry==15) faces |= 2;
                    if (rz==0) faces |= 4;
                    if (rz==15) faces |= 8;
                    if ((span & 1)!=0) faces |= 16;
                    if ((span & 0x8000)!=0) faces |= 32;
                    // Once a connected component reaches all faces, no further flood is needed.
                    if (faces==63) return ALL;
                    if (ry>0) tail = enqueue(s, row-16, span, tail);
                    if (ry<15) tail = enqueue(s, row+16, span, tail);
                    if (rz>0) tail = enqueue(s, row-1, span, tail);
                    if (rz<15) tail = enqueue(s, row+1, span, tail);
                    assert tail-head <= 256 : "row queue overflow";
                }
                result |= CLIQUES[faces];
                if (result==ALL) return ALL;
            }
        }
        return result;
    }
    private static int enqueue(Scratch s, int row, int span, int tail) {
        int next = span & (s.free[row] & 0xffff);
        if (next != 0) {
            if (s.pending[row] == 0) s.queue[tail++ & 255] = row;
            s.pending[row] |= (short)next;
        }
        return tail;
    }
    private static long[] makeCliques() {
        long[] out = new long[64];
        for (int f=0; f<64; f++) for (int a=0;a<6;a++) for (int b=0;b<6;b++)
            if ((f & (1<<a))!=0 && (f & (1<<b))!=0) out[f] |= 1L << (a*6+b);
        return out;
    }
    private static final class Scratch {
        final short[] free = new short[256];
        final short[] pending = new short[256];
        final int[] queue = new int[256];
    }
}

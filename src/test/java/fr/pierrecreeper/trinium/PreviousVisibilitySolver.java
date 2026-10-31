package fr.pierrecreeper.trinium;

import java.util.Arrays;
import java.util.BitSet;

/**
 * Six-face connectivity for one 16x16x16 section, not a full renderer.
 * Index: x | z << 4 | y << 8. Face order: DOWN, UP, NORTH, SOUTH, WEST, EAST.
 * Preserves the conservative all-visible shortcut for fewer than 256 opaque cells.
 * Does not mutate the input; worker scratch memory is reused per Java thread.
 */
public final class PreviousVisibilitySolver {
    public static final long ALL = (1L << 36) - 1;
    private static final int[] BOUNDARY = makeBoundary();
    private static final long[] CLIQUES = makeCliques();
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private PreviousVisibilitySolver() { }

    public static long resolve(BitSet opaque) {
        if (opaque.length() > 4096) throw new IllegalArgumentException("Section exceeds 4096 cells");
        return resolve(opaque, opaque.cardinality());
    }

    /** Internal entrypoint: caller has already counted opaque cells. */
    static long resolve(BitSet opaque, int count) {
        if (opaque.length() > 4096) throw new IllegalArgumentException("Section exceeds 4096 cells");
        if (count < 256) return ALL;
        if (count == 4096) return 0;
        return solve(opaque.toLongArray());
    }

    public static long resolveWords(long[] words) {
        if (words.length > 64) throw new IllegalArgumentException("At most 64 words are permitted");
        int count = 0;
        for (long word : words) count += Long.bitCount(word);
        if (count < 256) return ALL;
        if (count == 4096) return 0;
        return solve(words);
    }

    private static long solve(long[] words) {
        Scratch s = SCRATCH.get();
        Arrays.fill(s.visited, 0);
        System.arraycopy(words, 0, s.visited, 0, words.length);
        long result = 0;
        for (int start : BOUNDARY) {
            if (marked(s.visited, start)) continue;
            int head = 0, tail = 1, faces = 0;
            s.queue[0] = start;
            mark(s.visited, start);
            while (head < tail) {
                int p = s.queue[head++];
                int x = p & 15, z = p >>> 4 & 15, y = p >>> 8;
                if (y == 0) faces |= 1;
                if (y == 15) faces |= 2;
                if (z == 0) faces |= 4;
                if (z == 15) faces |= 8;
                if (x == 0) faces |= 16;
                if (x == 15) faces |= 32;
                if (y > 0) tail = enqueue(s, p - 256, tail);
                if (y < 15) tail = enqueue(s, p + 256, tail);
                if (z > 0) tail = enqueue(s, p - 16, tail);
                if (z < 15) tail = enqueue(s, p + 16, tail);
                if (x > 0) tail = enqueue(s, p - 1, tail);
                if (x < 15) tail = enqueue(s, p + 1, tail);
            }
            result |= CLIQUES[faces];
            if (result == ALL) return result;
        }
        return result;
    }

    private static boolean marked(long[] bits, int p) {
        return (bits[p >>> 6] & (1L << (p & 63))) != 0;
    }
    private static void mark(long[] bits, int p) { bits[p >>> 6] |= 1L << (p & 63); }
    private static int enqueue(Scratch s, int p, int tail) {
        if (!marked(s.visited, p)) { mark(s.visited, p); s.queue[tail++] = p; }
        return tail;
    }
    private static int[] makeBoundary() {
        int[] boundary = new int[4096 - 14 * 14 * 14];
        int n = 0;
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            if (x == 0 || x == 15 || y == 0 || y == 15 || z == 0 || z == 15)
                boundary[n++] = x | z << 4 | y << 8;
        }
        if (n != boundary.length) throw new AssertionError("Boundary size");
        return boundary;
    }
    private static long[] makeCliques() {
        long[] result = new long[64];
        for (int faces = 0; faces < 64; faces++)
            for (int a = 0; a < 6; a++) for (int b = 0; b < 6; b++)
                if ((faces & (1 << a)) != 0 && (faces & (1 << b)) != 0)
                    result[faces] |= 1L << (a * 6 + b);
        return result;
    }
    private static final class Scratch {
        final long[] visited = new long[64];
        final int[] queue = new int[4096];
    }
}

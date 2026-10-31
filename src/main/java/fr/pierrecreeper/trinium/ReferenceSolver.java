package fr.pierrecreeper.trinium;

import java.util.ArrayDeque;
import java.util.BitSet;

/** Independent, intentionally simple correctness oracle, not Minecraft/Sodium benchmark code. */
public final class ReferenceSolver {
    private ReferenceSolver() { }
    public static long resolve(BitSet opaque) {
        if (opaque.length() > 4096) throw new IllegalArgumentException("Section exceeds 4096 cells");
        int n = opaque.cardinality();
        if (n < 256) return VisibilitySolver.ALL;
        if (n == 4096) return 0;
        boolean[][][] seen = new boolean[16][16][16];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        long result = 0;
        int[][] directions = {{0,-1,0},{0,1,0},{0,0,-1},{0,0,1},{-1,0,0},{1,0,0}};
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            if (x != 0 && x != 15 && y != 0 && y != 15 && z != 0 && z != 15) continue;
            if (seen[x][y][z] || opaque.get(x + z * 16 + y * 256)) continue;
            queue.add(x + z * 16 + y * 256);
            seen[x][y][z] = true;
            boolean[] faces = new boolean[6];
            while (!queue.isEmpty()) {
                int p = queue.remove();
                int xx = p % 16, zz = p / 16 % 16, yy = p / 256;
                if (yy == 0) faces[0] = true;
                if (yy == 15) faces[1] = true;
                if (zz == 0) faces[2] = true;
                if (zz == 15) faces[3] = true;
                if (xx == 0) faces[4] = true;
                if (xx == 15) faces[5] = true;
                for (int[] d : directions) {
                    int nx = xx + d[0], ny = yy + d[1], nz = zz + d[2];
                    if (nx < 0 || nx > 15 || ny < 0 || ny > 15 || nz < 0 || nz > 15) continue;
                    if (seen[nx][ny][nz] || opaque.get(nx + nz * 16 + ny * 256)) continue;
                    seen[nx][ny][nz] = true;
                    queue.add(nx + nz * 16 + ny * 256);
                }
            }
            for (int a = 0; a < 6; a++) for (int b = 0; b < 6; b++)
                if (faces[a] && faces[b]) result |= 1L << (a * 6 + b);
        }
        return result;
    }
}

package fr.pierrecreeper.trinium;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.SplittableRandom;

public final class TestVectors {
    private TestVectors() { }
    public static List<BitSet> fixtures() {
        List<BitSet> result = new ArrayList<>();
        result.add(new BitSet(4096));
        BitSet full = new BitSet(4096); full.set(0, 4096); result.add(full);
        for (int axis = 0; axis < 3; axis++) {
            BitSet wall = new BitSet(4096);
            BitSet corridor = (BitSet) full.clone();
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int[] xyz = {x, y, z}; int p = x | z << 4 | y << 8;
                if (xyz[axis] == 7) wall.set(p);
                if (xyz[(axis + 1) % 3] == 8 && xyz[(axis + 2) % 3] == 8) corridor.clear(p);
            }
            result.add(wall); result.add(corridor);
            BitSet doorway = (BitSet) wall.clone();
            int[] pos = {8,8,8}; pos[axis] = 7; doorway.clear(pos[0] | pos[2] << 4 | pos[1] << 8);
            result.add(doorway);
        }
        BitSet shell = new BitSet(4096);
        BitSet checkerboard = new BitSet(4096);
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int p = x | z << 4 | y << 8;
            if (x == 0 || x == 15 || y == 0 || y == 15 || z == 0 || z == 15) shell.set(p);
            if (((x+y+z) & 1) == 0) checkerboard.set(p);
        }
        result.add(shell); result.add(checkerboard);
        for (int count : new int[]{1,255,256,257,4095}) {
            BitSet row = new BitSet(4096); row.set(0,count); result.add(row);
        }
        return result;
    }
    public static BitSet random(SplittableRandom random, int index) {
        BitSet result = new BitSet(4096);
        double density = new double[]{0.02,0.08,0.2,0.4,0.6,0.8,0.95,0.995}[index & 7];
        for (int p = 0; p < 4096; p++) if (random.nextDouble() < density) result.set(p);
        return result;
    }
    public static int quickCheck(int randomCases) {
        List<BitSet> cases = fixtures();
        SplittableRandom random = new SplittableRandom(0x5452494e49554dL);
        for (int i=0; i<randomCases; i++) cases.add(random(random,i));
        int n = 0;
        for (BitSet bits : cases) {
            BitSet before = (BitSet) bits.clone();
            long actual = VisibilitySolver.resolve(bits), expected = ReferenceSolver.resolve(bits);
            if (actual != expected) throw new AssertionError("Mismatch in fixture " + n);
            if (!bits.equals(before)) throw new AssertionError("Input mutated");
            if (VisibilitySolver.resolve(bits) != actual) throw new AssertionError("Nonrepeatable result");
            n++;
        }
        return n;
    }
}

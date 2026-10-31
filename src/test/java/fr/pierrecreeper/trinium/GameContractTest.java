package fr.pierrecreeper.trinium;

import com.mojang.blaze3d.vertex.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.renderer.chunk.*;
import net.minecraft.core.*;
import net.minecraft.util.SimpleBitStorage;

/** Runs on official Minecraft classes, without mixins, to obtain an independent vanilla oracle. */
public final class GameContractTest {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        NativeBridge.initialize();
        check(NativeBridge.available(), NativeBridge.status());
        Direction[] directions = Direction.values();
        check(Arrays.equals(Arrays.stream(directions).map(Enum::name).toArray(),
            new String[]{"DOWN","UP","NORTH","SOUTH","WEST","EAST"}), "real direction layout");
        List<BitSet> cases = new ArrayList<>(TestVectors.fixtures());
        SplittableRandom random = new SplittableRandom(20261029);
        for (int i = 0; i < 1024; i++) cases.add(TestVectors.random(random, i));
        for (BitSet bits : cases) {
            VisGraph graph = new VisGraph();
            for (int p = bits.nextSetBit(0); p >= 0; p = bits.nextSetBit(p + 1))
                graph.setOpaque(new BlockPos(p & 15, p >> 8, (p >> 4) & 15));
            VisibilitySet vanilla = graph.resolve();
            long nativeMask = NativeBridge.resolve(bits.toLongArray());
            for (int a = 0; a < 6; a++) for (int b = 0; b < 6; b++)
                check(vanilla.visibilityBetween(directions[a], directions[b]) == ((nativeMask & 1L << (a*6+b)) != 0), "real vanilla visibility parity");
        }
        System.out.println("PASS real Minecraft VisGraph parity: " + cases.size() + " sections, all 36 face pairs");
        int paletteCases = 0;
        for (int bits = 1; bits <= 32; bits++) for (int size : new int[]{0,1,15,16,17,255,4096,4097}) {
            long[] data = new long[(size + 64/bits - 1)/(64/bits)];
            for (int i = 0; i < data.length; i++) data[i] = random.nextLong();
            SimpleBitStorage storage = new SimpleBitStorage(bits, size, data);
            int[] vanilla = new int[size + 5], actual = new int[size + 5];
            Arrays.fill(vanilla, 123); Arrays.fill(actual, 123);
            storage.unpack(vanilla);
            check(PaletteDecoder.unpack(data,bits,size,actual) && Arrays.equals(vanilla,actual), "real palette parity bits="+bits);
            paletteCases++;
        }
        System.out.println("PASS real Minecraft SimpleBitStorage parity: " + paletteCases + " cases");
        for (int i = 0; i < 240; i++) sortCase(i);
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Future<?>> jobs = new ArrayList<>();
            for (int w = 0; w < 8; w++) { final int seed = w; jobs.add(pool.submit(() -> {
                for (int i = 0; i < 32; i++) sortCase(1000 + seed * 32 + i);
            })); }
            for (var job : jobs) job.get();
        }
        for (ByteBuffer invalid : new ByteBuffer[]{null, ByteBuffer.allocate(1024),
                ByteBuffer.allocateDirect(1024).asReadOnlyBuffer(), ByteBuffer.allocateDirect(8)}) {
            try { NativeSort.sortChecked(invalid,128); throw new AssertionError("invalid buffer accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        System.out.println("PASS real Minecraft stable sorting parity: 496 cases, NaN/infinities/signed zero/ties, 8-thread isolation, invalid buffers");
        CompactVectorArray nested = new CompactVectorArray(256);
        for (int i=0;i<256;i++) nested.set(i,i,0,0);
        VertexSorting.DistanceFunction reentrant = v -> {
            check(NativeSort.sort(w -> w.x,nested) == null,"recursive sort must fall back to vanilla");
            return v.x;
        };
        check(Arrays.equals(NativeSort.sort(reentrant,nested),VertexSorting.byDistance(v -> v.x).sort(nested)),"outer sort survives recursive callbacks");
        try { NativeSort.sort(v -> { throw new IllegalArgumentException("callback error"); },nested); throw new AssertionError("exception swallowed"); }
        catch (IllegalArgumentException expected) { }
        check(NativeSort.sort(v -> v.x,nested) != null,"scratch guard resets after exceptions");
        System.out.println("PASS recursive distance callback and exception recovery");
    }
    static void sortCase(int seed) {
        SplittableRandom random = new SplittableRandom(seed);
        int[] sizes = {128,129,255,256,511,1024,2048,8192};
        int size = sizes[seed % sizes.length];
        CompactVectorArray vertices = new CompactVectorArray(size);
        for (int i=0;i<size;i++) vertices.set(i, Float.intBitsToFloat(random.nextInt()), (float)random.nextDouble(), (float)random.nextDouble());
        float[] special = {Float.NaN, Float.intBitsToFloat(0xffc01234), Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY, 0.0f, -0.0f, 1,1,1,1};
        for(int i=0;i<special.length;i++) vertices.set(i,special[i],0,0);
        VertexSorting.DistanceFunction distance = seed % 2 == 0 ? v -> v.x : v -> v.distanceSquared(2.5f, 4.25f, -8.5f);
        int[] vanilla = VertexSorting.byDistance(distance).sort(vertices);
        int[] actual = NativeSort.sort(distance,vertices);
        check(Arrays.equals(vanilla,actual), "real sorting parity seed="+seed);
    }
}

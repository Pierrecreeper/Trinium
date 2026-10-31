package fr.pierrecreeper.trinium;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.BitSet;
import java.util.SplittableRandom;

/** JNI library loader shared by native visibility and transparent-quad sorting. */
public final class NativeBridge {
    private static volatile boolean available;
    private static volatile String status = "Rust not initialized";
    private static boolean attempted;
    private static boolean libraryLoaded;
    private static final ThreadLocal<ByteBuffer> BUFFERS = ThreadLocal.withInitial(
            () -> ByteBuffer.allocateDirect(512).order(ByteOrder.LITTLE_ENDIAN));
    private NativeBridge() { }
    private static native long apiVersion0();
    private static native long resolve0(ByteBuffer opacity);
    public static boolean available() { return available; }
    public static String status() { return status; }
    public static synchronized void initialize() {
        if (attempted) return;
        attempted = true;
        if (Boolean.getBoolean("trinium.disableNative")) {
            status = "Native code explicitly disabled; Java backend"; return;
        }
        NativePlatform platform = NativePlatform.host();
        if (platform == null) { status = "Unsupported native platform; Java backend"; return; }
        try {
            // Only the explicitly launched test JVM uses an external candidate path.
            if (Boolean.getBoolean("trinium.buildVerificationProcess")) {
                String candidate = System.getProperty("trinium.nativeCandidate");
                String sha = System.getProperty("trinium.nativeCandidateSha256");
                if (candidate == null || sha == null) throw new IOException("Incomplete test candidate");
                loadLocal(new LocalNativeBuild.Binary(Path.of(candidate), sha, platform)); return;
            }
            String resource = platform.resource();
            try (InputStream in = NativeBridge.class.getResourceAsStream(resource);
                 InputStream digest = NativeBridge.class.getResourceAsStream(resource + ".sha256")) {
                if (in != null) {
                    loadVerifiedBytes(NativeBinary.readVerified(in, digest, platform), platform, "bundled"); return;
                }
            }
            LocalNativeBuild.Binary cached = LocalNativeBuild.cached(platform);
            if (cached != null) { loadLocal(cached); return; }
            status = "Rust CPU unavailable for " + platform.id() + "; Java backend active";
        } catch (Exception | LinkageError e) {
            available = false; status = "Native library not loaded: " + Trinium.describe(e);
        }
    }
    static synchronized void loadLocal(LocalNativeBuild.Binary binary) throws IOException {
        if (available) return;
        NativePlatform host = NativePlatform.host();
        if (host == null || !host.equals(binary.platform())) throw new IOException("Native platform does not match the JVM");
        try (InputStream in = Files.newInputStream(binary.file());
             InputStream sha = new ByteArrayInputStream(binary.sha256().getBytes(StandardCharsets.US_ASCII))) {
            loadVerifiedBytes(NativeBinary.readVerified(in, sha, host), host, "locally compiled");
        }
    }
    private static void loadVerifiedBytes(byte[] bytes, NativePlatform platform, String source) throws IOException {
        if (libraryLoaded) throw new IOException("A library is already loaded in this JVM. Restart to replace an invalid native library.");
        Path directory = Files.createTempDirectory("trinium-native-");
        Path file = directory.resolve(platform.filename());
        directory.toFile().deleteOnExit(); file.toFile().deleteOnExit();
        Files.write(file, bytes);
        System.load(file.toAbsolutePath().toString());
        libraryLoaded = true;
        if (apiVersion0() != 3) throw new IllegalStateException("Incompatible Rust ABI");
        for (BitSet bits : TestVectors.fixtures()) checkNative(bits);
        SplittableRandom random = new SplittableRandom(0x5452494eL);
        for (int i = 0; i < 128; i++) checkNative(TestVectors.random(random, i));
        NativeRadix.check();
        available = true;
        status = "Rust CPU loaded (" + source + ", " + platform.id() + "); ABI 3 and CPU parity verified. Rust OpenGL initializes on the first draw.";
    }
    private static void checkNative(BitSet bits) {
        if (call(bits.toLongArray()) != ReferenceSolver.resolve(bits))
            throw new IllegalStateException("Native / Java reference mismatch");
    }
    public static long resolve(long[] words) {
        if (!available) throw new IllegalStateException(status);
        long result = call(words);
        if (result < 0 || (result & ~VisibilitySolver.ALL) != 0) {
            available = false; status = "Invalid native result; falling back to Java";
            throw new IllegalStateException(status);
        }
        return result;
    }
    private static long call(long[] words) {
        if (words.length > 64) throw new IllegalArgumentException("Section too large");
        ByteBuffer buffer = BUFFERS.get();
        for (int i = 0; i < 64; i++) buffer.putLong(i * 8, i < words.length ? words[i] : 0);
        return resolve0(buffer);
    }
    public static synchronized void disable(Throwable cause) {
        available = false; status = "Native code disabled: " + Trinium.describe(cause) + "; Java fallback";
        Trinium.warn(status);
    }
    public static String platform() {
        NativePlatform p = NativePlatform.host();
        return p == null ? "java-only-unsupported-native-platform" : p.id();
    }
}

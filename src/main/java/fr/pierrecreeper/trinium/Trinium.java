package fr.pierrecreeper.trinium;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.Properties;
import java.util.concurrent.atomic.LongAdder;

/** Startup, persisted configuration, and on-demand diagnostics. No recurring benchmark. */
public final class Trinium {
    public static long emptyLayerLookupsSkipped;
    public static long terrainDrawHits, terrainDrawMisses, terrainRenderDrawHits, terrainRenderDrawMisses;
    public static long gpuFullSliceHits, gpuFullSliceMisses;
    public static final String VERSION = "0.1.0";
    public enum Mode { OFF, AUTO, JAVA, NATIVE }
    static final LongAdder intercepted = new LongAdder();
    static final LongAdder replaced = new LongAdder();
    static final LongAdder trivialSkipped = new LongAdder();
    static final LongAdder nativeCalls = new LongAdder();
    static final LongAdder javaCalls = new LongAdder();
    static volatile boolean initialized;
    static volatile boolean failed;
    static volatile String failure = "";
    private static volatile Mode mode = Mode.AUTO;
    private static volatile Path directory = Path.of("config", "trinium");
    private static final System.Logger LOG = System.getLogger("Trinium");
    private Trinium() { }

    public static synchronized void initialize() {
        if (initialized) return;
        try {
            Class<?> loader = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object instance = loader.getMethod("getInstance").invoke(null);
            directory = ((Path)loader.getMethod("getConfigDir").invoke(instance)).resolve("trinium");
            Files.createDirectories(directory);
            loadConfiguration();
            int checked = mode == Mode.OFF ? 0 : TestVectors.quickCheck(24);
            if (mode != Mode.OFF) NativeBridge.initialize();
            initialized = true;
            info("Trinium " + VERSION + " | mode=" + mode + " | testsCPU=" + checked + " | nativeBuild=" + LocalNativeBuild.status() + " | " + NativeBridge.status());
            info("Section optimization " + (active() ? "active" : "disabled") + ". No FPS measurement performed.");
            // Runtime compilation is explicit through the CLI, never during gameplay.
            try { writeReport(); } catch (IOException e) { warn("Report: " + e.getMessage()); }
        } catch (Exception | LinkageError | AssertionError e) {
            fail(e); initialized = true;
        }
    }
    public static Mode mode() { return mode; }
    public static Path directory() { return directory; }
    public static boolean active() { return initialized && !failed && mode != Mode.OFF; }
    public static synchronized void loadConfiguration() throws IOException {
        Files.createDirectories(directory);
        Path file = directory.resolve("trinium.properties");
        Properties p = new Properties();
        if (Files.exists(file)) try (InputStream in=Files.newInputStream(file)) { p.load(in); }
        if (failed) { mode = Mode.OFF; return; }
        // A revision migration must not turn an explicit opt-out back on. Keep
        // every valid mode, including files written by a player without revision.
        if (Files.exists(file) && !"4".equals(p.getProperty("configRevision"))) {
            Path backup = Files.createTempFile(directory, "trinium-before-migration-", ".properties");
            boolean copied = false;
            try {
                Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
                copied = true;
            } finally { if (!copied) Files.deleteIfExists(backup); }
        }
        try { mode = Mode.valueOf(p.getProperty("mode", "auto").strip().toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException ex) { mode = Mode.AUTO; }
        saveConfiguration(p);
    }
    public static synchronized void changeMode(Mode next) throws IOException {
        if (failed && next != Mode.OFF) throw new IllegalStateException("Safety fallback: " + failure);
        if (next == Mode.NATIVE && !NativeBridge.available()) throw new IllegalStateException(NativeBridge.status());
        java.util.Objects.requireNonNull(next, "mode");
        Mode previous = mode;
        mode = next;
        try { saveConfiguration(); }
        catch (IOException | RuntimeException error) { mode = previous; throw error; }
    }
    private static void saveConfiguration() throws IOException {
        Properties p = new Properties();
        Path file = directory.resolve("trinium.properties");
        if (Files.exists(file)) try (InputStream in=Files.newInputStream(file)) { p.load(in); }
        saveConfiguration(p);
    }
    private static void saveConfiguration(Properties p) throws IOException {
        Files.createDirectories(directory);
        p.setProperty("configRevision", "4");
        p.setProperty("mode", mode.name().toLowerCase(java.util.Locale.ROOT));
        p.setProperty("notes", "auto: Rust when available, Java otherwise; off: persistently disabled");
        Path tmp=Files.createTempFile(directory,"trinium-",".tmp");
        try {
            try (OutputStream out=Files.newOutputStream(tmp)) { p.store(out,"Trinium: enabled by default; no continuous verification."); }
            atomicMove(tmp,directory.resolve("trinium.properties"));
        } finally { Files.deleteIfExists(tmp); }
    }
    static String backend() {
        if (!active()) return "vanilla";
        return (NativeBridge.available() && (mode==Mode.NATIVE || mode==Mode.AUTO)) ? "Rust" : "Java-row-span";
    }
    public static void recordSection(boolean nativeCall) {
        intercepted.increment(); replaced.increment();
        if (nativeCall) nativeCalls.increment(); else javaCalls.increment();
    }
    public static void recordPalette() { PaletteHooks.intercepted.increment(); PaletteHooks.replaced.increment(); }
    public static String debugLine() {
        return "Trinium " + VERSION + " | CPU " + backend() + " | Rust sections " + nativeCalls.sum() + " | Rust GPU draws " + fr.pierrecreeper.trinium.graphics.RustGlDriver.draws.sum();
    }
    public static String rendererLine() {
        if (fr.pierrecreeper.trinium.compat.RenderCompatibility.SODIUM || fr.pierrecreeper.trinium.compat.RenderCompatibility.IRIS)
            return "Renderer: Iris / Sodium";
        return "Renderer: " + (fr.pierrecreeper.trinium.graphics.RustGlDriver.rendererActive()
                ? "Trinium 3D Render" : "Minecraft RenderPearl");
    }
    public static String status() {
        return "Trinium " + VERSION + " | mode=" + mode + " | cpuBackend=" + backend()
                + " | sections=" + intercepted.sum() + " | replaced=" + replaced.sum()
                + " | vanillaShortcuts=" + trivialSkipped.sum()
                + " | palettes=" + PaletteHooks.replaced.sum()
                + " | nativeBuild=" + LocalNativeBuild.status() + " | " + NativeBridge.status()
                + (failed ? " | fallback=" + failure : "");
    }
    static synchronized void fail(Throwable e) {
        if (failed) return;
        failure = describe(e); failed=true; mode=Mode.OFF;
        warn("Vanilla fallback for this session: " + failure);
        // Do not persist a runtime failure as a user's OFF preference.
    }
    public static Path writeReport() throws IOException {
        Files.createDirectories(directory);
        var memory=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long collections=0, millis=0;
        for (var gc:ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gc.getCollectionCount()>0) collections+=gc.getCollectionCount();
            if (gc.getCollectionTime()>0) millis+=gc.getCollectionTime();
        }
        String text="{\n"
                + "  \"mod\": \"Trinium\",\n"
                + "  \"version\": " + quote(VERSION) + ",\n"
                + "  \"targetMinecraft\": \"26.3\",\n"
                + "  \"timestampUtc\": " + quote(Instant.now().toString()) + ",\n"
                + "  \"mode\": " + quote(mode.name()) + ",\n"
                + "  \"backend\": " + quote(backend()) + ",\n"
                + "  \"active\": " + active() + ",\n"
                + "  \"renderer\": " + quote(rendererLine().substring("Renderer: ".length())) + ",\n"
                + "  \"rustModule\": \"Rust CPU helpers and optional OpenGL command batches; see observed counters and graphicsStatus\",\n"
                + "  \"nativeBuildStatus\": " + quote(LocalNativeBuild.status()) + ",\n"
                + "  \"nativeBuildDetail\": " + quote(LocalNativeBuild.detail()) + ",\n"
                + "  \"nativeBuildLog\": " + quote(LocalNativeBuild.logPath().toString()) + ",\n"
                + "  \"nativeAvailable\": " + NativeBridge.available() + ",\n"
                + "  \"nativeStatus\": " + quote(NativeBridge.status()) + ",\n"
                + "  \"nativePlatform\": " + quote(NativeBridge.platform()) + ",\n"
                + "  \"java\": " + quote(System.getProperty("java.version")) + ",\n"
                + "  \"os\": " + quote(System.getProperty("os.name")) + ",\n"
                + "  \"architecture\": " + quote(System.getProperty("os.arch")) + ",\n"
                + "  \"interceptedSections\": " + intercepted.sum() + ",\n"
                + "  \"sectionHookObserved\": " + (intercepted.sum()>0) + ",\n"
                + "  \"paletteHookObserved\": " + (PaletteHooks.intercepted.sum()>0) + ",\n"
                + "  \"replacedSections\": " + replaced.sum() + ",\n"
                + "  \"javaSections\": " + javaCalls.sum() + ",\n"
                + "  \"nativeSections\": " + nativeCalls.sum() + ",\n"
                + "  \"vanillaTrivialSections\": " + trivialSkipped.sum() + ",\n"
                + "  \"nativeSortCalls\": " + NativeSort.calls.sum() + ",\n"
                + "  \"nativeSortedQuads\": " + NativeSort.quads.sum() + ",\n"
                + "  \"graphicsStatus\": " + quote(fr.pierrecreeper.trinium.graphics.RustGlDriver.status()) + ",\n"
                + "  \"graphicsBatches\": " + fr.pierrecreeper.trinium.graphics.RustGlDriver.batches.sum() + ",\n"
                + "  \"graphicsCommands\": " + fr.pierrecreeper.trinium.graphics.RustGlDriver.commands.sum() + ",\n"
                + "  \"graphicsDraws\": " + fr.pierrecreeper.trinium.graphics.RustGlDriver.draws.sum() + ",\n"
                + "  \"loadingGridBatches\": " + fr.pierrecreeper.trinium.graphics.LoadingGridBatch.batches + ",\n"
                + "  \"emptyLayerLookupsSkipped\": " + emptyLayerLookupsSkipped + ",\n"
                + "  \"terrainDrawHits\": " + terrainDrawHits + ",\n"
                + "  \"terrainDrawMisses\": " + terrainDrawMisses + ",\n"
                + "  \"terrainRenderDrawHits\": " + terrainRenderDrawHits + ",\n"
                + "  \"terrainRenderDrawMisses\": " + terrainRenderDrawMisses + ",\n"
                + "  \"gpuFullSliceHits\": " + gpuFullSliceHits + ",\n"
                + "  \"gpuFullSliceMisses\": " + gpuFullSliceMisses + ",\n"
                + "  \"terrainSliceCacheHits\": " + fr.pierrecreeper.trinium.graphics.TerrainSliceCache.hits + ",\n"
                + "  \"terrainSliceCacheMisses\": " + fr.pierrecreeper.trinium.graphics.TerrainSliceCache.misses + ",\n"
                + "  \"terrainSliceCacheInvalidations\": " + fr.pierrecreeper.trinium.graphics.TerrainSliceCache.invalidations + ",\n"
                + "  \"modelCubesBatched\": " + fr.pierrecreeper.trinium.graphics.ModelBatcher.cubes + ",\n"
                + "  \"modelVerticesBatched\": " + fr.pierrecreeper.trinium.graphics.ModelBatcher.vertices + ",\n"
                + "  \"modelDirectCubes\": " + fr.pierrecreeper.trinium.graphics.ModelBatcher.directCubes + ",\n"
                + "  \"chestFrustumTested\": " + fr.pierrecreeper.trinium.graphics.ChestFrustumCulling.tested + ",\n"
                + "  \"chestFrustumCulled\": " + fr.pierrecreeper.trinium.graphics.ChestFrustumCulling.culled + ",\n"
                + "  \"shaderCacheHits\": " + fr.pierrecreeper.trinium.shaders.ShaderCache.hits.sum() + ",\n"
                + "  \"shaderCacheMisses\": " + fr.pierrecreeper.trinium.shaders.ShaderCache.misses.sum() + ",\n"
                + "  \"shaderCacheFailures\": " + fr.pierrecreeper.trinium.shaders.ShaderCache.failures.sum() + ",\n"
                + "  \"irisCacheHits\": " + fr.pierrecreeper.trinium.shaders.IrisProgramCache.hits.sum() + ",\n"
                + "  \"irisCacheMisses\": " + fr.pierrecreeper.trinium.shaders.IrisProgramCache.misses.sum() + ",\n"
                + "  \"irisCacheStores\": " + fr.pierrecreeper.trinium.shaders.IrisProgramCache.stores.sum() + ",\n"
                + "  \"irisCacheFailures\": " + fr.pierrecreeper.trinium.shaders.IrisProgramCache.failures.sum() + ",\n"
                + "  \"paletteCalls\": " + PaletteHooks.intercepted.sum() + ",\n"
                + "  \"paletteReplaced\": " + PaletteHooks.replaced.sum() + ",\n"
                + "  \"paletteEnabled\": " + !PaletteHooks.failed + ",\n"
                + "  \"paletteFailure\": " + quote(PaletteHooks.failure) + ",\n"
                + "  \"solverScratchPayloadBytesPerWorker\": " + VisibilitySolver.SCRATCH_PAYLOAD_BYTES + ",\n"
                + "  \"heapUsedBytes\": " + memory.getUsed() + ",\n"
                + "  \"heapMaxBytes\": " + memory.getMax() + ",\n"
                + "  \"gcCollectionsSinceJvmStart\": " + collections + ",\n"
                + "  \"gcMillisSinceJvmStart\": " + millis + ",\n"
                + "  \"failed\": " + failed + ",\n"
                + "  \"failure\": " + quote(failure) + ",\n"
                + "  \"note\": \"Not an FPS benchmark. Not a complete renderer or world-generation replacement.\"\n"
                + "}\n";
        Path tmp=Files.createTempFile(directory,"report-",".tmp");
        Path out=directory.resolve("diagnostic.json");
        try { Files.writeString(tmp,text,StandardCharsets.UTF_8); atomicMove(tmp,out); }
        finally { Files.deleteIfExists(tmp); }
        return out.toAbsolutePath();
    }
    private static void atomicMove(Path from,Path to) throws IOException {
        try { Files.move(from,to,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(from,to,StandardCopyOption.REPLACE_EXISTING); }
    }
    static String quote(String s) {
        StringBuilder b=new StringBuilder("\"");
        for (char c:s.toCharArray()) switch(c) {
            case '"' -> b.append("\\\""); case '\\' -> b.append("\\\\");
            case '\n' -> b.append("\\n"); case '\r' -> b.append("\\r"); case '\t' -> b.append("\\t");
            default -> { if(c<32) b.append(String.format("\\u%04x",(int)c)); else b.append(c); }
        }
        return b.append('"').toString();
    }
    static String describe(Throwable e) {
        while(e instanceof InvocationTargetException && e.getCause()!=null) e=e.getCause();
        return e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
    }
    static void info(String s) { LOG.log(System.Logger.Level.INFO,s); }
    static void warn(String s) { LOG.log(System.Logger.Level.WARNING,s); }
}

// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.shaders;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import fr.pierrecreeper.trinium.Trinium;
import fr.pierrecreeper.trinium.ui.ShaderAccess.*;
import net.minecraft.client.Minecraft;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Render-thread owner of the independent shader pipeline and atomic user configuration. */
public final class StandaloneShaders {
    private static final System.Logger LOG = System.getLogger("Trinium shaders");
    private static final StandaloneShaders INSTANCE = new StandaloneShaders();
    public static StandaloneShaders instance() { return INSTANCE; }
    private final Path directory = Minecraft.getInstance().gameDirectory.toPath().resolve("shaderpacks");
    private final Path config = Minecraft.getInstance().gameDirectory.toPath().resolve("config/trinium/shaders.properties");
    private boolean initialized, attempted, enabled;
    private String name;
    private StandalonePack pack;
    private Map<String, String> options = Map.of();
    private final Map<String, Map<String, String>> packOptions = new LinkedHashMap<>();
    private List<StandalonePack.Pass> passes = List.of();
    private PostShaderPipeline pipeline;
    private String dimension;
    private long started = System.nanoTime(), previous;
    private int frame;
    private long renderedFrames;
    private String lastError = "";

    private StandaloneShaders() { }
    public Path directory() { return directory; }
    public String lastError() { return lastError; }
    public long renderedFrames() { return renderedFrames; }
    public State state() { initialize(); return new State(name, enabled, pipeline != null); }
    private void initialize() {
        if (initialized) return;
        initialized = true;
        try {
            Files.createDirectories(directory);
            Properties p = new Properties();
            if (Files.isRegularFile(config)) {
                if (Files.size(config) > ShaderSettingsFile.MAX_BYTES) throw new IOException("Shader configuration is too large.");
                try (Reader reader = Files.newBufferedReader(config)) { p.load(reader); }
            }
            name = p.getProperty("pack");
            enabled = Boolean.parseBoolean(p.getProperty("enabled", "false")) && StandalonePack.safeName(name);
            LinkedHashMap<String, String> saved = new LinkedHashMap<>();
            for (String key : p.stringPropertyNames()) if (key.startsWith("option.")) saved.put(key.substring(7), p.getProperty(key));
            options = Map.copyOf(saved);
            for (String key : p.stringPropertyNames()) if (key.startsWith("packOption.")) {
                String[] parts = key.split("\\.", 3);
                if (parts.length == 3 && parts[1].matches("[0-9a-f]{64}") && parts[2].matches("[A-Za-z_]\\w*")) {
                    packOptions.computeIfAbsent(parts[1], ignored -> new LinkedHashMap<>()).put(parts[2], p.getProperty(key));
                }
            }
            if (StandalonePack.safeName(name)) packOptions.put(key(name), options);
        } catch (IOException | RuntimeException error) { enabled = false; lastError = describe(error); LOG.log(System.Logger.Level.WARNING, lastError); }
    }
    public List<String> packs() throws IOException {
        initialize();
        try (var paths = Files.list(directory)) {
            return paths.filter(p -> Files.isDirectory(p) || p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                .map(p -> p.getFileName().toString()).filter(StandalonePack::safeName).sorted(String.CASE_INSENSITIVE_ORDER).limit(4096).toList();
        }
    }
    public Settings settings() {
        if (pack == null || pipeline == null) throw new IllegalStateException("Apply a shader before opening its settings.");
        return pack.settings(options);
    }
    public Result apply(String selected, Map<String, String> changes) {
        RenderSystem.assertOnRenderThread(); initialize();
        if (selected != null && !Trinium.active()) {
            closePipeline(); attempted = false;
            return new Result(false, "Trinium is disabled. Enable Trinium before applying a shader.");
        }
        attempted = true;
        PostShaderPipeline next = null;
        try {
            if (selected == null) {
                closePipeline(); enabled = false;
                try {
                    save(name, false, options);
                    lastError = "";
                    return new Result(true, "Shaders off. Minecraft renderer active.");
                } catch (IOException | RuntimeException error) {
                    lastError = describe(error);
                    LOG.log(System.Logger.Level.WARNING, "Shaders stopped, but settings were not saved: " + lastError);
                    return new Result(false, "Shaders off for this session; settings could not be saved. " + lastError);
                }
            }
            StandalonePack candidate = new StandalonePack(directory, selected);
            Map<String, String> saved = Objects.equals(selected, name) ? options : packOptions.getOrDefault(key(selected), Map.of());
            Map<String, String> configured = candidate.configuredOptions(saved, changes);
            String targetDimension = currentDimension();
            List<StandalonePack.Pass> nextPasses = candidate.passes(targetDimension, configured);
            next = new PostShaderPipeline(nextPasses);
            var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            next.prepare(target.width, target.height);
            save(selected, true, configured);
            closePipeline();
            pipeline = next; next = null; pack = candidate; name = selected; options = Map.copyOf(configured);
            passes = nextPasses; dimension = targetDimension; enabled = true; lastError = "";
            started = System.nanoTime(); previous = 0; frame = 0;
            LOG.log(System.Logger.Level.INFO, "Loaded " + selected + " with Trinium's independent shader renderer (" + passes.size() + " passes).");
            return new Result(true, "Shader applied with Trinium.");
        } catch (IOException | RuntimeException error) {
            if (next != null) next.close();
            lastError = describe(error);
            LOG.log(System.Logger.Level.WARNING, "Shader not applied: " + lastError);
            return new Result(false, "Shader not applied. Previous shader kept. " + lastError);
        }
    }
    private void save(String pack, boolean enabled, Map<String, String> options) throws IOException {
        Properties p = new Properties();
        if (pack != null) p.setProperty("pack", pack);
        p.setProperty("enabled", Boolean.toString(enabled));
        options.forEach((key, value) -> p.setProperty("option." + key, value));
        Map<String, Map<String, String>> allOptions = new LinkedHashMap<>(packOptions);
        if (pack != null) allOptions.put(key(pack), Map.copyOf(options));
        allOptions.forEach((id, values) -> values.forEach((key, value) -> p.setProperty("packOption." + id + "." + key, value)));
        ShaderSettingsFile.write(config, p);
        packOptions.clear(); packOptions.putAll(allOptions);
    }
    private static String key(String pack) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(pack.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String currentDimension() {
        var level = Minecraft.getInstance().level;
        if (level == null) return "0";
        if (level.dimension().equals(net.minecraft.world.level.Level.NETHER)) return "-1";
        if (level.dimension().equals(net.minecraft.world.level.Level.END)) return "1";
        return "0";
    }
    /** Called after world post effects, before depth clear and HUD drawing. */
    public void render(RenderTarget target) {
        if (!Trinium.active()) {
            closePipeline(); attempted = false;
            return;
        }
        initialize();
        if (!attempted) {
            attempted = true;
            if (enabled && name != null) apply(name, Map.of());
        }
        if (pipeline == null) return;
        try {
            if (!(target.getColorTexture() instanceof GlTexture color) || !(target.getDepthTexture() instanceof GlTexture depth)) {
                throw new IllegalStateException("Trinium shaders currently require the OpenGL backend.");
            }
            String current = currentDimension();
            if (!Objects.equals(current, dimension)) {
                PostShaderPipeline next = null;
                try {
                    List<StandalonePack.Pass> updated = pack.passes(current, options);
                    if (!updated.equals(passes)) {
                        next = new PostShaderPipeline(updated);
                        next.prepare(target.width, target.height);
                        pipeline.close(); pipeline = next; next = null; passes = updated;
                    }
                } catch (IOException | RuntimeException error) {
                    if (next != null) next.close();
                    lastError = describe(error);
                    LOG.log(System.Logger.Level.WARNING, "Dimension shader not applied; previous pipeline kept: " + lastError);
                }
                dimension = current;
            }
            Minecraft mc = Minecraft.getInstance();
            long now = System.nanoTime();
            float delta = previous == 0 ? 0 : (now - previous) / 1_000_000_000f; previous = now;
            var pos = mc.gameRenderer.mainCamera().position();
            long time = mc.level == null ? 0 : mc.level.getOverworldClockTime();
            pipeline.render(color.glId(), depth.glId(), target.width, target.height,
                new PostShaderPipeline.Frame(frame++, ((now - started) / 1_000_000_000f) % 3600, delta,
                    (int)(time % 24000), (int)(time / 24000), mc.level == null ? 0 : mc.level.getRainLevel(1),
                    0.05f, mc.options.renderDistance().get() * 16f, (float)pos.x, (float)pos.y, (float)pos.z));
            renderedFrames++;
        } catch (RuntimeException error) {
            lastError = describe(error); closePipeline();
            LOG.log(System.Logger.Level.ERROR, "Shader renderer stopped; Minecraft rendering retained: " + lastError);
        }
    }
    private void closePipeline() { if (pipeline != null) pipeline.close(); pipeline = null; passes = List.of(); }
    public void close() { closePipeline(); }
    private static String describe(Throwable error) {
        String message = String.valueOf(error.getMessage()).replaceAll("[\\r\\n\\t]+", " ");
        return message.length() > 220 ? message.substring(0, 217) + "..." : message;
    }
}

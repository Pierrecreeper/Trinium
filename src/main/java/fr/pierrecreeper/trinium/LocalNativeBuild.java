package fr.pierrecreeper.trinium;

import java.io.*;
import java.net.URISyntaxException;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Compiles ONLY the bundled, dependency-free CPU Rust core with an already installed
 * Cargo toolchain. Never downloads/install tools, invokes a shell, changes graphics
 * settings, or claims to implement a renderer. Tests native code in a separate JVM
 * before loading it in Minecraft. A fatal bug after loading remains unrecoverable.
 */
public final class LocalNativeBuild {
    private static final String PREFIX = "/trinium-build/native/";
    static final List<String> SOURCES = List.of("Cargo.toml", "Cargo.lock", "src/lib.rs", "src/graphics.rs",
            "src/gl_dispatch.rs", "src/graphics_contract_tests.rs");
    private static final AtomicReference<Process> CHILD = new AtomicReference<>();
    private static volatile String status = "NOT_STARTED";
    private static volatile String detail = "";
    private static boolean started;
    static final long MAX_LOG_BYTES = 512 * 1024;

    public record Binary(Path file, String sha256, NativePlatform platform) { }
    private LocalNativeBuild() { }
    public static String status() { return status; }
    public static String detail() { return detail; }
    public static Path logPath() { return Trinium.directory().resolve("native-build.log").toAbsolutePath(); }

    private static byte[] resource(String file) throws IOException {
        try (InputStream in = LocalNativeBuild.class.getResourceAsStream(PREFIX + file)) {
            if (in == null) throw new IOException("Missing bundled Rust source: " + file);
            byte[] bytes = in.readNBytes(1024 * 1024 + 1);
            if (bytes.length > 1024 * 1024) throw new IOException("Source too large");
            return bytes;
        }
    }
    public static String sourceKey() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(("trinium-native-cache-v1\n" + Trinium.VERSION + "\n").getBytes(StandardCharsets.UTF_8));
        for (String name : SOURCES) {
            bytes.write(name.getBytes(StandardCharsets.UTF_8)); bytes.write(0);
            bytes.write(resource(name)); bytes.write(0);
        }
        return NativeBinary.sha256(bytes.toByteArray());
    }
    static Path cacheDirectory(NativePlatform platform) throws IOException {
        // The standalone CLI uses a relative config directory. Cargo changes cwd to
        // the build stage, so manifest/target paths must retain their original base.
        return Trinium.directory().toAbsolutePath().resolve("native-cache").resolve(platform.id()).resolve(sourceKey());
    }
    /** No compiler, subprocess or download: verify an already certified local cache. */
    static Binary cached(NativePlatform platform) throws IOException {
        if (platform == null) return null;
        Path root = cacheDirectory(platform), manifest = root.resolve("ready.properties");
        if (!Files.isRegularFile(manifest)) return null;
        if (Files.size(manifest) > 16 * 1024) throw new IOException("Cache metadata too large");
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) { p.load(in); }
        if (!sourceKey().equals(p.getProperty("sourceKey")) || !platform.target().equals(p.getProperty("target"))
                || !Trinium.VERSION.equals(p.getProperty("version")) || !"passed".equals(p.getProperty("isolatedJniTest")))
            throw new IOException("Rust cache not validated for this version/JVM");
        Path file = root.resolve(platform.filename());
        validate(file, p.getProperty("sha256"), platform);
        return new Binary(file, p.getProperty("sha256"), platform);
    }
    static void validate(Path file, String digest, NativePlatform platform) throws IOException {
        if (digest == null) throw new IOException("Missing digest");
        try (InputStream in = Files.newInputStream(file);
             InputStream sha = new ByteArrayInputStream(digest.getBytes(StandardCharsets.US_ASCII))) {
            NativeBinary.readVerified(in, sha, platform);
        }
    }
    /** Called once by the actual Fabric entrypoint, never by per-frame/per-section hooks. */
    public static synchronized void startOnce() {
        if (started) return;
        started = true;
        if (NativeBridge.available()) { status = "READY"; return; }
        if (!Trinium.active() || Trinium.mode() == Trinium.Mode.JAVA || Boolean.getBoolean("trinium.disableNative")
                || Boolean.getBoolean("trinium.disableNativeBuild")) {
            status = "DISABLED"; return;
        }
        if (findCargo() == null) {
            status = "MISSING_CARGO";
            detail = "Cargo is not installed. No download attempted; Java backend retained.";
            Trinium.warn(detail); return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Process child = CHILD.get(); if (child != null) stop(child);
        }, "Trinium-native-build-stop"));
        status = "BUILDING";
        Thread worker = new Thread(() -> {
            try {
                Binary binary = build();
                // A user who switched to OFF during compilation is not re-enabled.
                if (Trinium.active()) NativeBridge.loadLocal(binary);
                status = NativeBridge.available() ? "READY" : "BUILT_NOT_ACTIVE";
                detail = NativeBridge.status();
                Trinium.info("Local Rust compilation: " + status + " | " + detail);
            } catch (Exception | LinkageError e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                status = "BUILD_FAILED"; detail = Trinium.describe(e);
                Trinium.warn("Rust not active: " + detail + "; log=" + logPath());
            } finally {
                try { Trinium.writeReport(); } catch (IOException e) { Trinium.warn("Report: " + e.getMessage()); }
            }
        }, "Trinium-local-rust-build");
        worker.setDaemon(true); worker.setPriority(Thread.MIN_PRIORITY); worker.start();
    }
    static Path findCargo() {
        String executable = System.getProperty("os.name", "").startsWith("Windows") ? "cargo.exe" : "cargo";
        List<Path> candidates = new ArrayList<>();
        String cargoHome = System.getenv("CARGO_HOME");
        if (cargoHome != null && !cargoHome.isBlank()) candidates.add(Path.of(cargoHome).resolve("bin").resolve(executable));
        candidates.add(Path.of(System.getProperty("user.home"), ".cargo", "bin", executable));
        String path = System.getenv("PATH");
        if (path != null) for (String part : path.split(java.util.regex.Pattern.quote(File.pathSeparator)))
            if (!part.isBlank()) candidates.add(Path.of(part).resolve(executable));
        for (Path p : candidates) if (Files.isRegularFile(p) && Files.isExecutable(p)) return p.toAbsolutePath();
        return null;
    }
    public static Binary build() throws IOException, InterruptedException {
        NativePlatform platform = NativePlatform.host();
        if (platform == null) throw new IOException("Unsupported native platform: " + NativeBridge.platform());
        Files.createDirectories(Trinium.directory());
        Path root = cacheDirectory(platform); Files.createDirectories(root);
        Path log = logPath();
        try (FileChannel channel = FileChannel.open(root.resolve("build.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Another instance is compiling this library. Wait for it to finish before retrying.");
            Binary existing;
            try { existing = cached(platform); } catch (IOException e) { existing = null; }
            if (existing != null) return existing;
            Path cargo = findCargo();
            if (cargo == null) throw new IOException("Cargo is missing. Install Rust and retry. The mod does not download build tools.");
            Files.writeString(log, "Trinium " + Trinium.VERSION + "\n" + Instant.now() + "\nTarget: " + platform.target()
                    + "\nModule: CPU visibility (NOT GPU renderer)\n", StandardCharsets.UTF_8);
            Path stage = Files.createTempDirectory(root, "build-");
            try {
                for (String name : SOURCES) {
                    Path file = stage.resolve(name); Files.createDirectories(file.getParent()); Files.write(file, resource(name));
                }
                execute(List.of(cargo.toString(), "--version"), stage, Duration.ofSeconds(15), log);
                execute(List.of(cargo.toString(), "test", "--release", "--frozen", "--offline", "--jobs", "1",
                        "--target", platform.target(), "--manifest-path", stage.resolve("Cargo.toml").toString()), stage, Duration.ofMinutes(5), log);
                execute(List.of(cargo.toString(), "build", "--release", "--frozen", "--offline", "--jobs", "1",
                        "--target", platform.target(), "--manifest-path", stage.resolve("Cargo.toml").toString()), stage, Duration.ofMinutes(5), log);
                Path built = stage.resolve("target").resolve(platform.target()).resolve("release").resolve(platform.filename());
                if (!Files.isRegularFile(built) || Files.size(built) > NativeBinary.MAX_BYTES)
                    throw new IOException("Cargo library missing or too large: " + built);
                String digest = NativeBinary.sha256(Files.readAllBytes(built));
                validate(built, digest, platform);
                verifyIsolated(built, digest, stage, log);
                Path target = root.resolve(platform.filename()), temp = root.resolve(platform.filename() + ".new");
                Files.copy(built, temp, StandardCopyOption.REPLACE_EXISTING); atomicMove(temp, target);
                Properties metadata = new Properties();
                metadata.setProperty("version", Trinium.VERSION); metadata.setProperty("target", platform.target());
                metadata.setProperty("sourceKey", sourceKey()); metadata.setProperty("sha256", digest);
                metadata.setProperty("isolatedJniTest", "passed"); metadata.setProperty("builtAtUtc", Instant.now().toString());
                metadata.setProperty("abi", "3");
                Path tmp = root.resolve("ready.properties.new");
                try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) { metadata.store(out, "Local CPU native build; not a Minecraft rendering/FPS test"); }
                atomicMove(tmp, root.resolve("ready.properties"));
                return new Binary(target, digest, platform);
            } finally { deleteTree(stage); }
        } catch (OverlappingFileLockException e) { throw new IOException("Compilation already running in this JVM", e); }
    }
    private static Path codeLocation() throws IOException {
        try { return Path.of(LocalNativeBuild.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath(); }
        catch (URISyntaxException | NullPointerException | IllegalArgumentException e) { throw new IOException("Could not locate the JAR", e); }
    }
    static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
    }
    static void verifyIsolated(Path nativeFile, String sha, Path cwd, Path log) throws IOException, InterruptedException {
        execute(List.of(javaExecutable(), "--enable-native-access=ALL-UNNAMED", "-XX:-CreateCoredumpOnCrash",
                "-Dtrinium.buildVerificationProcess=true", "-Dtrinium.nativeCandidate=" + nativeFile.toAbsolutePath(),
                "-Dtrinium.nativeCandidateSha256=" + sha,
                "-cp", codeLocation().toString(), "fr.pierrecreeper.trinium.TriniumCli", "--require-native", "--selftest"),
                cwd, Duration.ofMinutes(2), log);
    }
    static void execute(List<String> command, Path cwd, Duration timeout, Path log) throws IOException, InterruptedException {
        Files.createDirectories(log.toAbsolutePath().getParent());
        ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.put("CARGO_NET_OFFLINE", "true"); env.put("CARGO_TERM_COLOR", "never"); env.put("RUSTUP_AUTO_INSTALL", "0");
        env.put("CARGO_TARGET_DIR", cwd.resolve("target").toString());
        env.remove("RUSTFLAGS"); env.remove("CARGO_ENCODED_RUSTFLAGS"); env.remove("CARGO_BUILD_TARGET");
        try (OutputStream stream = Files.newOutputStream(log, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            stream.write(("\nCOMMAND " + command + "\n").getBytes(StandardCharsets.UTF_8)); stream.flush();
            Process process = builder.start(); CHILD.set(process); process.getOutputStream().close();
            AtomicReference<IOException> drainFailure = new AtomicReference<>();
            Thread drain = new Thread(() -> {
                try (InputStream input = process.getInputStream()) {
                    byte[] buf = new byte[4096]; long saved = 0; int n;
                    while ((n = input.read(buf)) >= 0) {
                        int take = (int)Math.min(n, Math.max(0, MAX_LOG_BYTES - saved));
                        if (take > 0) { stream.write(buf, 0, take); saved += take; }
                    }
                    if (saved >= MAX_LOG_BYTES) stream.write("\n[output truncated]\n".getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) { drainFailure.set(e); }
            }, "Trinium-compiler-log");
            drain.setDaemon(true); drain.start();
            try {
                boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (!completed) { stop(process); process.waitFor(5, TimeUnit.SECONDS); }
                drain.join(5000);
                if (drain.isAlive()) { process.getInputStream().close(); drain.join(1000); }
                if (!completed) throw new IOException("Build/test timed out; process stopped. Log: " + log);
                if (process.exitValue() != 0) throw new IOException("Command failed (exit " + process.exitValue() + "). Log: " + log);
                if (drainFailure.get() != null) throw drainFailure.get();
            } finally {
                if (process.isAlive()) stop(process);
                CHILD.compareAndSet(process, null);
            }
        }
    }
    private static void stop(Process process) {
        process.descendants().forEach(p -> { try { p.destroyForcibly(); } catch (RuntimeException ignored) { } });
        process.destroyForcibly();
    }
    private static void atomicMove(Path from, Path to) throws IOException {
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
    }
    static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
    /** Produces a new mod JAR with the tested host binary, never overwrites the running JAR. */
    public static Path buildJar(Path requestedOutput) throws IOException, InterruptedException {
        Path source = codeLocation();
        if (!Files.isRegularFile(source) || !source.getFileName().toString().endsWith(".jar"))
            throw new IOException("Run --build-native from the JAR, not a class directory.");
        Binary binary = build();
        Path output = requestedOutput == null
                ? source.resolveSibling("Trinium-" + Trinium.VERSION + "-mc26.3-" + binary.platform().id() + ".jar")
                : requestedOutput.toAbsolutePath();
        if (Files.exists(output)) throw new IOException("Refusing to overwrite an existing file: " + output);
        if (output.getParent() != null) Files.createDirectories(output.getParent());
        Path temp = Files.createTempFile(output.getParent(), "trinium-", ".jar");
        String prefix = "natives/" + binary.platform().id() + "/";
        try {
            try (ZipFile zip = new ZipFile(source.toFile()); JarOutputStream jar = new JarOutputStream(Files.newOutputStream(temp))) {
                List<NativePlatform> bundledTargets = NativePlatform.supported().stream()
                        .filter(p -> p.equals(binary.platform()) || zip.getEntry(p.resource().substring(1)) != null).toList();
                Enumeration<? extends ZipEntry> entries = zip.entries(); Set<String> names = new HashSet<>();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement(); String name = entry.getName();
                    if (name.startsWith(prefix)) continue;
                    if (!names.add(name)) throw new IOException("Duplicate entry: " + name);
                    byte[] data;
                    try (InputStream input = zip.getInputStream(entry)) { data = input.readAllBytes(); }
                    if (name.equals("fabric.mod.json")) {
                        data = bundledMetadata(data, bundledTargets);
                    }
                    putEntry(jar, name, data);
                }
                putEntry(jar, prefix + binary.platform().filename(), Files.readAllBytes(binary.file()));
                putEntry(jar, prefix + binary.platform().filename() + ".sha256", (binary.sha256() + "\n").getBytes(StandardCharsets.US_ASCII));
                putEntry(jar, prefix + "build.properties", ("version=" + Trinium.VERSION + "\ntarget=" + binary.platform().target()
                        + "\nabi=3\nmodule=cpu-visibility\nisolatedJniTest=passed\n").getBytes(StandardCharsets.UTF_8));
            }
            // Test the packaged JAR, not just an unpacked candidate. No Java fallback accepted.
            execute(List.of(javaExecutable(), "--enable-native-access=ALL-UNNAMED", "-XX:-CreateCoredumpOnCrash",
                    "-jar", temp.toString(), "--require-native", "--selftest"), source.getParent(), Duration.ofMinutes(2), logPath());
            // No REPLACE_EXISTING: the user's existing output remains untouched.
            Files.move(temp, output);
            return output;
        } finally { Files.deleteIfExists(temp); }
    }
    /** Keep metadata accurate when adding a host binary to an already bundled JAR. */
    static byte[] bundledMetadata(byte[] bytes, List<NativePlatform> platforms) throws IOException {
        String text = new String(bytes, StandardCharsets.UTF_8);
        String targets = "[" + String.join(", ", platforms.stream().map(p -> "\"" + p.id() + "\"").toList()) + "]";
        String bundledPattern = "\"trinium:rust_bundled\"\\s*:\\s*(false|true)";
        String targetsPattern = "\"trinium:rust_targets\"\\s*:\\s*\\[[^]]*]";
        if (!java.util.regex.Pattern.compile(bundledPattern).matcher(text).find()
                || !java.util.regex.Pattern.compile(targetsPattern).matcher(text).find())
            throw new IOException("Native packaging metadata is missing from fabric.mod.json");
        return text.replaceAll(bundledPattern, "\"trinium:rust_bundled\": true")
                .replaceAll(targetsPattern, "\"trinium:rust_targets\": " + targets).getBytes(StandardCharsets.UTF_8);
    }
    private static void putEntry(JarOutputStream jar, String name, byte[] data) throws IOException {
        JarEntry e = new JarEntry(name); e.setTime(0); jar.putNextEntry(e); jar.write(data); jar.closeEntry();
    }
}

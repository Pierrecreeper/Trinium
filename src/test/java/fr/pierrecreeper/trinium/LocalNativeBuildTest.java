package fr.pierrecreeper.trinium;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Tests the local build controller. NO test compiler or native fixture is shipped. */
public final class LocalNativeBuildTest {
    private static int checks;
    private LocalNativeBuildTest() { }
    private static void check(boolean condition, String reason) {
        checks++; if (!condition) throw new AssertionError(reason);
    }
    @FunctionalInterface interface Operation { void run() throws Exception; }
    private static void rejected(Operation operation, String reason) throws Exception {
        try { operation.run(); throw new AssertionError("Expected rejection: " + reason); }
        catch (IOException expected) { checks++; System.out.println("PASS rejection: " + reason + " => " + expected.getMessage()); }
    }
    private static List<String> fixture(String mode) {
        List<String> command = new ArrayList<>(); command.add(LocalNativeBuild.javaExecutable());
        command.add("-cp"); command.add(absoluteClassPath()); command.add(LocalNativeBuildTest.class.getName());
        command.add("child"); command.add(mode); return command;
    }
    private static String absoluteClassPath() {
        Set<String> paths = new LinkedHashSet<>();
        try {
            paths.add(Path.of(LocalNativeBuildTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().toString());
            paths.add(Path.of(LocalNativeBuild.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().toString());
        } catch (java.net.URISyntaxException e) { throw new IllegalStateException(e); }
        for (String entry : System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator)))
            paths.add(Path.of(entry).toAbsolutePath().toString());
        return String.join(File.pathSeparator, paths);
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("child")) {
            switch (args[1]) {
                case "ok" -> System.out.println("CHILD_OK");
                case "fail" -> { System.out.println("DELIBERATE_FAILURE"); System.exit(23); }
                case "sleep" -> Thread.sleep(20000);
                case "large" -> { for (int i = 0; i < 20000; i++) System.out.println("0123456789012345678901234567890123456789012345678901234567890123456789"); }
                case "env" -> {
                    if (!"true".equals(System.getenv("CARGO_NET_OFFLINE")) || !"0".equals(System.getenv("RUSTUP_AUTO_INSTALL")))
                        throw new AssertionError("Network/install not disabled");
                    System.out.println("ENV_OFFLINE_OK");
                }
                case "manifest-path" -> {
                    Path manifest = Path.of(args[2]);
                    if (!manifest.isAbsolute() || !Files.readString(manifest).equals("CLI_RELATIVE_CONFIG_FIXTURE\n")
                            || !Path.of(System.getenv("CARGO_TARGET_DIR")).isAbsolute())
                        throw new AssertionError("Build paths lost their base after the child changed its working directory");
                    System.out.println("CHILD_MANIFEST_OK");
                }
                default -> throw new AssertionError("unknown fixture");
            }
            return;
        }
        Path tmp = Files.createTempDirectory("trinium-build-tests-");
        Field field = Trinium.class.getDeclaredField("directory"); field.setAccessible(true);
        Path previous = (Path)field.get(null); field.set(null, tmp.resolve("config"));
        try {
            String key = LocalNativeBuild.sourceKey();
            check(key.matches("[a-f0-9]{64}"), "source key format");
            check(key.equals(LocalNativeBuild.sourceKey()), "source key deterministic");
            Path nativeRoot = Path.of(System.getProperty("trinium.projectDir", "."), "native");
            Set<String> sourceFiles = new HashSet<>(List.of("Cargo.toml", "Cargo.lock"));
            try (var files = Files.walk(nativeRoot.resolve("src"))) {
                files.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".rs"))
                        .forEach(p -> sourceFiles.add(nativeRoot.relativize(p).toString().replace(File.separatorChar, '/')));
            }
            check(sourceFiles.equals(new HashSet<>(LocalNativeBuild.SOURCES)), "local build stages every Rust module, including tests");
            for (String name : LocalNativeBuild.SOURCES) {
                try (InputStream in = LocalNativeBuildTest.class.getResourceAsStream("/trinium-build/native/" + name)) {
                    check(in != null, "bundled source " + name);
                    check(Arrays.equals(in.readAllBytes(), Files.readAllBytes(Path.of(System.getProperty("trinium.projectDir", "."), "native", name))), "source not stale " + name);
                }
            }
            check(LocalNativeBuild.cached(NativePlatform.host()) == null, "empty cache not native-ready");
            List<NativePlatform> twoTargets = List.of(NativePlatform.find("linux-x86_64"), NativePlatform.find("windows-x86_64"));
            for (String targets : List.of("[]", "[\"linux-x86_64\"]")) {
                byte[] original = ("{\"id\":\"trinium\",\"custom\":{\"trinium:rust_bundled\":true,\"trinium:rust_targets\":" + targets + "}}")
                        .getBytes(StandardCharsets.UTF_8);
                String patched = new String(LocalNativeBuild.bundledMetadata(original, twoTargets), StandardCharsets.UTF_8);
                check(patched.contains("\"trinium:rust_targets\": [\"linux-x86_64\", \"windows-x86_64\"]"), "repackaged metadata lists old and new binaries");
                check(patched.contains("\"id\":\"trinium\""), "repackaging keeps unrelated metadata");
            }
            rejected(() -> LocalNativeBuild.bundledMetadata("{}".getBytes(StandardCharsets.UTF_8), twoTargets), "missing native package metadata");
            Path log = tmp.resolve("test.log");
            Path cwd = Path.of("").toAbsolutePath();
            Path relative = cwd.relativize(Files.createTempDirectory(cwd, "trinium-relative-build-"));
            try {
                check(!relative.isAbsolute(), "CLI fixture starts with a relative config path");
                field.set(null, relative.resolve("config"));
                Path relativeCache = LocalNativeBuild.cacheDirectory(NativePlatform.host());
                check(relativeCache.isAbsolute(), "CLI cache root is absolute before child cwd changes");
                Files.createDirectories(relativeCache);
                Path stage = Files.createTempDirectory(relativeCache, "build-");
                Path manifest = stage.resolve("Cargo.toml");
                Files.writeString(manifest, "CLI_RELATIVE_CONFIG_FIXTURE\n");
                List<String> command = fixture("manifest-path"); command.add(manifest.toString());
                LocalNativeBuild.execute(command, stage, Duration.ofSeconds(10), log);
                check(Files.readString(log).contains("CHILD_MANIFEST_OK"), "Manifest remains readable from the actual child build directory");
            } finally {
                field.set(null, tmp.resolve("config"));
                LocalNativeBuild.deleteTree(relative);
            }
            LocalNativeBuild.execute(fixture("ok"), tmp, Duration.ofSeconds(10), log);
            check(Files.readString(log).contains("CHILD_OK"), "success output");
            LocalNativeBuild.execute(fixture("env"), tmp, Duration.ofSeconds(10), log);
            check(Files.readString(log).contains("ENV_OFFLINE_OK"), "offline environment");
            rejected(() -> LocalNativeBuild.execute(fixture("fail"), tmp, Duration.ofSeconds(10), log), "nonzero child");
            rejected(() -> LocalNativeBuild.execute(List.of(tmp.resolve("missing-compiler").toString()), tmp, Duration.ofSeconds(1), log), "missing compiler");
            long begin = System.nanoTime();
            rejected(() -> LocalNativeBuild.execute(fixture("sleep"), tmp, Duration.ofMillis(300), log), "child timeout");
            check(System.nanoTime() - begin < 8_000_000_000L, "timeout child actually stopped");
            Path hugeLog = tmp.resolve("large.log");
            LocalNativeBuild.execute(fixture("large"), tmp, Duration.ofSeconds(20), hugeLog);
            check(Files.size(hugeLog) < LocalNativeBuild.MAX_LOG_BYTES + 4096, "bounded subprocess log");
            check(Files.readString(hugeLog).contains("output truncated"), "log truncation explicit");
            Path garbage = tmp.resolve(NativePlatform.host().filename()); Files.write(garbage, new byte[512]);
            rejected(() -> LocalNativeBuild.validate(garbage, NativeBinary.sha256(new byte[512]), NativePlatform.host()), "garbage native container");
            rejected(() -> LocalNativeBuild.validate(garbage, "0".repeat(64), NativePlatform.host()), "bad digest");
            rejected(() -> LocalNativeBuild.validate(garbage, null, NativePlatform.host()), "missing digest");
            Path cache = LocalNativeBuild.cacheDirectory(NativePlatform.host()); Files.createDirectories(cache);
            Files.writeString(cache.resolve("ready.properties"), "isolatedJniTest=passed\nversion=wrong\n");
            rejected(() -> LocalNativeBuild.cached(NativePlatform.host()), "uncertified/stale cache");
            // The next cases use intentionally broken C JNI libraries, NOT Rust binaries.
            // These paths are supplied by the local verification script, never bundled in the mod.
            for (String name : args) {
                Path library = Path.of(name).toAbsolutePath();
                String sha = NativeBinary.sha256(Files.readAllBytes(library));
                LocalNativeBuild.validate(library, sha, NativePlatform.host());
                rejected(() -> LocalNativeBuild.verifyIsolated(library, sha, tmp, log), "broken C JNI fixture " + name);
            }
            Path sample = tmp.resolve("delete/a/b"); Files.createDirectories(sample); Files.writeString(sample.resolve("file.txt"), "test");
            LocalNativeBuild.deleteTree(tmp.resolve("delete")); check(!Files.exists(tmp.resolve("delete")), "temporary tree cleanup");
            System.out.println("PASS local native build controller: " + checks + " checks. Actual Rust compilation and Minecraft not executed.");
        } finally { field.set(null, previous); LocalNativeBuild.deleteTree(tmp); }
    }
}

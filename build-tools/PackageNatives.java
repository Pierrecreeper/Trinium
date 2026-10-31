import fr.pierrecreeper.trinium.NativeBinary;
import fr.pierrecreeper.trinium.NativePlatform;
import fr.pierrecreeper.trinium.Trinium;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Compiled Java core must be on the class path. Never invents a missing native library. */
public final class PackageNatives {
    private record Bundle(NativePlatform platform, byte[] bytes) { }
    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !(args[0].equals("host") || args[0].equals("universal")))
            throw new IllegalArgumentException("Usage: PackageNatives <host|universal> <projectRoot> <classes>");
        Path root = Path.of(args[1]).toAbsolutePath();
        Path classes = Path.of(args[2]).toAbsolutePath();
        List<Bundle> bundles = new ArrayList<>();
        if (args[0].equals("host")) {
            NativePlatform platform = NativePlatform.host();
            if (platform == null) throw new IOException("No native build target for this JVM");
            Path source = root.resolve("native/target/release").resolve(platform.filename());
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Cargo output missing: " + source + ". Use a native host build; CARGO_BUILD_TARGET must be unset.");
            byte[] bytes;
            try (InputStream in = Files.newInputStream(source)) { bytes = in.readNBytes(NativeBinary.MAX_BYTES + 1); }
            NativeBinary.validateHeader(bytes, platform);
            bundles.add(new Bundle(platform, bytes));
        } else {
            // Validate EVERY input before modifying the output directory.
            for (NativePlatform platform : NativePlatform.supported()) {
                Path dir = root.resolve("native-bundles/natives").resolve(platform.id());
                Path source = dir.resolve(platform.filename());
                if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Universal build incomplete: missing " + source);
                Properties manifest = new Properties();
                try (Reader reader = Files.newBufferedReader(dir.resolve("build.properties"), StandardCharsets.UTF_8)) { manifest.load(reader); }
                if (!Trinium.VERSION.equals(manifest.getProperty("version"))
                    || !platform.target().equals(manifest.getProperty("target"))
                    || !"3".equals(manifest.getProperty("abi")))
                    throw new IOException("Version/target/ABI mismatch for " + platform.id());
                byte[] bytes;
                try (InputStream in = Files.newInputStream(source);
                     InputStream digest = Files.newInputStream(dir.resolve(platform.filename() + ".sha256"))) {
                    bytes = NativeBinary.readVerified(in, digest, platform);
                }
                bundles.add(new Bundle(platform, bytes));
            }
        }
        Path output = classes.resolve("natives");
        // No stale libraries from a previous build may survive a new package operation.
        if (Files.exists(output)) try (var walk = Files.walk(output)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
        for (Bundle bundle : bundles) {
            NativePlatform p = bundle.platform();
            Path dir = output.resolve(p.id());
            Files.createDirectories(dir);
            Files.write(dir.resolve(p.filename()), bundle.bytes());
            Files.writeString(dir.resolve(p.filename() + ".sha256"), NativeBinary.sha256(bundle.bytes()) + "\n", StandardCharsets.US_ASCII);
            Files.writeString(dir.resolve("build.properties"),
                "version=" + Trinium.VERSION + "\nabi=3\ntarget=" + p.target() + "\n", StandardCharsets.UTF_8);
        }
        Path metadata = classes.resolve("fabric.mod.json");
        String json = Files.readString(metadata);
        String targets = "[" + String.join(", ", bundles.stream().map(b -> "\"" + b.platform().id() + "\"").toList()) + "]";
        json = json.replaceAll("\"trinium:rust_bundled\"\\s*:\\s*(false|true)", "\"trinium:rust_bundled\": true")
            .replaceAll("\"trinium:rust_targets\"\\s*:\\s*\\[[^]]*]", "\"trinium:rust_targets\": " + targets)
            .replace("\"portability-java\"", "\"portability-native-" + args[0] + "\"");
        Files.writeString(metadata, json, StandardCharsets.UTF_8);
        System.out.println("Packaged " + bundles.size() + " native target(s): " + targets);
        System.out.println("Header/digest checks do NOT replace the --require-native --selftest run on each target.");
    }
}

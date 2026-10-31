package fr.pierrecreeper.trinium;

import java.util.List;
import java.util.Locale;

/** Platform of the running JVM, not the physical CPU underneath an emulation layer. */
public record NativePlatform(String id, String target, String filename) {
    private static final List<NativePlatform> SUPPORTED = List.of(
        new NativePlatform("linux-x86_64", "x86_64-unknown-linux-gnu", "libtrinium_native.so"),
        new NativePlatform("linux-aarch64", "aarch64-unknown-linux-gnu", "libtrinium_native.so"),
        new NativePlatform("windows-x86_64", "x86_64-pc-windows-msvc", "trinium_native.dll"),
        new NativePlatform("windows-aarch64", "aarch64-pc-windows-msvc", "trinium_native.dll"),
        new NativePlatform("macos-x86_64", "x86_64-apple-darwin", "libtrinium_native.dylib"),
        new NativePlatform("macos-aarch64", "aarch64-apple-darwin", "libtrinium_native.dylib"));

    public static List<NativePlatform> supported() { return SUPPORTED; }
    public static NativePlatform host() {
        return detect(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }
    public static NativePlatform find(String id) {
        return SUPPORTED.stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
    }
    public static NativePlatform detect(String osName, String architecture) {
        String os = osName == null ? "" : osName.trim().toLowerCase(Locale.ROOT);
        String arch = architecture == null ? "" : architecture.trim().toLowerCase(Locale.ROOT);
        String machine = switch (arch) {
            case "amd64", "x86_64", "x64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> null;
        };
        // Darwin contains "win"; it must not be classified as Windows.
        String system = os.startsWith("mac") || os.equals("darwin") ? "macos"
            : os.startsWith("windows") ? "windows" : os.equals("linux") ? "linux" : null;
        return machine == null || system == null ? null : find(system + "-" + machine);
    }
    public String resource() { return "/natives/" + id + "/" + filename; }
}

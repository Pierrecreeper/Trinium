import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.zip.*;

/** CI-only Maven installer. Not embedded in the mod. Downloads official Apache files. */
public final class PrepareMaven {
    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("No arguments expected");
        String githubPath = System.getenv("GITHUB_PATH");
        String runnerTemp = System.getenv("RUNNER_TEMP");
        if (githubPath == null || runnerTemp == null) throw new IllegalStateException("CI helper only: install Maven normally for local builds");
        String version = "3.9.12";
        String file = "apache-maven-" + version + "-bin.zip";
        String base = "https://archive.apache.org/dist/maven/maven-3/" + version + "/binaries/";
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(30)).build();
        byte[] archive = download(http, base + file, 32 * 1024 * 1024);
        String hashText = new String(download(http, base + file + ".sha512", 4096), StandardCharsets.US_ASCII).trim();
        String expected = "0ef0d074761a55e7b982fd7f2cd6ea2da028289c73156ebd488c0513efbff8a64ac5bc0c11c3b8bbced930ce15238ecef344b0e065ae54f7cdca8c86cf39e736";
        if (!hashText.split("\\s+")[0].equalsIgnoreCase(expected)) throw new IOException("Published Maven digest differs from the pinned release");
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(archive));
        if (!expected.matches("[a-fA-F0-9]{128}") || !actual.equalsIgnoreCase(expected))
            throw new IOException("Maven archive SHA-512 mismatch");
        Path directory = Files.createTempDirectory(Path.of(runnerTemp), "trinium-maven-").toAbsolutePath().normalize();
        long total = 0; int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if (++entries > 2000) throw new IOException("Too many ZIP entries");
                Path out = directory.resolve(entry.getName()).normalize();
                if (!out.startsWith(directory)) throw new IOException("Unsafe ZIP entry");
                if (entry.isDirectory()) { Files.createDirectories(out); continue; }
                Files.createDirectories(out.getParent());
                byte[] content = zip.readNBytes(32 * 1024 * 1024 + 1);
                total += content.length;
                if (content.length > 32 * 1024 * 1024 || total > 128L * 1024 * 1024) throw new IOException("ZIP too large");
                Files.write(out, content);
            }
        }
        Path bin = directory.resolve("apache-maven-" + version).resolve("bin");
        Path executable = bin.resolve("mvn");
        if (!Files.isRegularFile(executable) || !Files.isRegularFile(bin.resolve("mvn.cmd")))
            throw new IOException("Incomplete Maven archive");
        if (!System.getProperty("os.name").startsWith("Windows") && !executable.toFile().setExecutable(true, true))
            throw new IOException("Cannot make mvn executable");
        Files.writeString(Path.of(githubPath), bin + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        System.out.println("Maven " + version + " installed from Apache, SHA-512 verified");
    }
    private static byte[] download(HttpClient client, String url, int max) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(3)).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + " for " + url);
            byte[] bytes = in.readNBytes(max + 1);
            if (bytes.length > max) throw new IOException("Download exceeds limit");
            return bytes;
        }
    }
}

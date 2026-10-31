package fr.pierrecreeper.trinium;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** On-demand package audit; never used per frame or per chunk. */
public final class NativeInventory {
    private static final int MAX_METADATA_BYTES = 16 * 1024;
    private NativeInventory() { }
    public static int verifyAll() throws IOException {
        return verifyAll(NativeInventory.class::getResourceAsStream);
    }
    @FunctionalInterface interface Resources { InputStream open(String path) throws IOException; }
    /** Also used by header-only tests; this audit never loads a native library. */
    static int verifyAll(Resources resources) throws IOException {
        int count = 0;
        for (NativePlatform platform : NativePlatform.supported()) {
            String resource = platform.resource();
            try (InputStream binary = resources.open(resource);
                 InputStream digest = resources.open(resource + ".sha256");
                 InputStream metadata = resources.open("/natives/" + platform.id() + "/build.properties")) {
                NativeBinary.readVerified(binary, digest, platform);
                if (metadata == null) throw new IOException("Missing build metadata: " + platform.id());
                byte[] metadataBytes = metadata.readNBytes(MAX_METADATA_BYTES + 1);
                if (metadataBytes.length > MAX_METADATA_BYTES) throw new IOException("Native build metadata too large: " + platform.id());
                Properties manifest = new Properties();
                manifest.load(new InputStreamReader(new ByteArrayInputStream(metadataBytes), StandardCharsets.UTF_8));
                if (!Trinium.VERSION.equals(manifest.getProperty("version"))
                        || !platform.target().equals(manifest.getProperty("target"))
                        || !"3".equals(manifest.getProperty("abi")))
                    throw new IOException("Incorrect native build metadata: " + platform.id());
                count++;
            }
        }
        return count;
    }
    public static void print() {
        System.out.println("JVM native target: " + NativeBridge.platform());
        for (NativePlatform platform : NativePlatform.supported())
            System.out.println(platform.id() + " : " + (NativeInventory.class.getResource(platform.resource()) == null ? "ABSENT" : "PRESENT (loading not implied)"));
    }
}

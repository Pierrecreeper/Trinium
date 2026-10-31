// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.shaders;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;

/** Bounded UTF-8 settings replacement; an invalid candidate never touches the saved file. */
final class ShaderSettingsFile {
    static final int MAX_BYTES = 65536;
    private ShaderSettingsFile() { }

    static byte[] encode(Properties settings) throws IOException {
        StringWriter serialized = new StringWriter();
        settings.store(serialized, "Trinium independent shader loader");
        byte[] bytes = serialized.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Shader configuration exceeds its storage budget.");
        return bytes;
    }

    static void write(Path config, Properties settings) throws IOException {
        byte[] bytes = encode(settings);
        Files.createDirectories(config.getParent());
        Path temporary = Files.createTempFile(config.getParent(), "shader-settings-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try { Files.move(temporary, config, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, config, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}

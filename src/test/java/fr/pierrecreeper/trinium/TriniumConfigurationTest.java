// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;

/** Real temporary-file migration and persistence contracts, without Minecraft. */
public final class TriniumConfigurationTest {
    private static int checks;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    private static Properties read(Path path) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(path)) { p.load(in); }
        return p;
    }
    private static List<Path> backups(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(p -> p.getFileName().toString().startsWith("trinium-before-migration-")).toList();
        }
    }
    public static void main(String[] args) throws Exception {
        Field directory = Trinium.class.getDeclaredField("directory"); directory.setAccessible(true);
        Field mode = Trinium.class.getDeclaredField("mode"); mode.setAccessible(true);
        Object oldDirectory = directory.get(null), oldMode = mode.get(null);
        boolean oldFailed = Trinium.failed;
        Path temp = Files.createTempDirectory("trinium-config-contract-");
        try {
            directory.set(null, temp); Trinium.failed = false;
            Path config = temp.resolve("trinium.properties");
            Trinium.loadConfiguration();
            check(Trinium.mode() == Trinium.Mode.AUTO, "Fresh configuration defaults to AUTO");
            check(read(config).getProperty("configRevision").equals("4"), "Fresh revision is recorded");
            check(backups(temp).isEmpty(), "Fresh config does not create a migration backup");

            byte[] previousBackup = "keep this existing backup\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Path legacyBackup = temp.resolve("trinium.pre-0.2.properties"); Files.write(legacyBackup, previousBackup);
            for (String revision : List.of("", "configRevision=1\n", "configRevision=future\n")) {
                byte[] original = (revision + "mode= off \ncustomSetting=keep-me\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                Files.write(config, original);
                int before = backups(temp).size();
                Trinium.loadConfiguration();
                check(Trinium.mode() == Trinium.Mode.OFF, "Explicit opt-out survives unknown/missing revision");
                check(read(config).getProperty("mode").equals("off"), "Opt-out stays persisted");
                check(read(config).getProperty("customSetting").equals("keep-me"), "Unknown properties survive migration");
                List<Path> after = backups(temp);
                check(after.size() == before + 1, "Each migration has a distinct backup");
                check(after.stream().anyMatch(p -> {
                    try { return Arrays.equals(Files.readAllBytes(p), original); }
                    catch (IOException e) { throw new java.io.UncheckedIOException(e); }
                }), "Original migration bytes are preserved");
                check(Arrays.equals(Files.readAllBytes(legacyBackup), previousBackup), "Existing legacy backup is untouched");
            }
            int migrated = backups(temp).size();
            Trinium.loadConfiguration();
            check(backups(temp).size() == migrated, "Current revision is not backed up repeatedly");
            check(Trinium.mode() == Trinium.Mode.OFF, "Repeated load preserves OFF");

            Files.writeString(config, "configRevision=4\nmode= java \ncustomSetting=preserved\n");
            Trinium.loadConfiguration();
            check(Trinium.mode() == Trinium.Mode.JAVA, "Explicit Java mode accepts surrounding whitespace");
            Trinium.changeMode(Trinium.Mode.AUTO);
            check(read(config).getProperty("mode").equals("auto"), "Mode change persists requested value");
            check(read(config).getProperty("customSetting").equals("preserved"), "Mode change preserves unknown properties");
            Files.writeString(config, "configRevision=4\nmode=invalid\ncustomSetting=preserved\n");
            Trinium.loadConfiguration();
            check(Trinium.mode() == Trinium.Mode.AUTO, "Invalid mode falls back to documented AUTO");

            Trinium.changeMode(Trinium.Mode.OFF);
            Path blocked = temp.resolve("not-a-directory"); Files.writeString(blocked, "file");
            directory.set(null, blocked);
            try { Trinium.changeMode(Trinium.Mode.AUTO); throw new AssertionError("Unwritable change succeeded"); }
            catch (IOException expected) { checks++; }
            check(Trinium.mode() == Trinium.Mode.OFF, "Failed persistence restores previous in-memory mode");
            directory.set(null, temp);

            byte[] malformed = "configRevision=4\nmode=off\nbroken=\\uNOT4\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(config, malformed);
            try { Trinium.loadConfiguration(); throw new AssertionError("Malformed properties accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
            check(Arrays.equals(Files.readAllBytes(config), malformed), "Malformed user file is not overwritten");
            Files.writeString(config, "mode=java\n");
            byte[] beforeFailed = Files.readAllBytes(config);
            Trinium.failed = true; Trinium.loadConfiguration();
            check(Trinium.mode() == Trinium.Mode.OFF, "Session failure stays disabled");
            check(Arrays.equals(Files.readAllBytes(config), beforeFailed), "Session failure is not persisted as user preference");
            check(backups(temp).size() == migrated, "Failed session creates no unnecessary migration backup");
            System.out.println("PASS configuration migration/persistence: " + checks + " checks");
        } finally {
            directory.set(null, oldDirectory); mode.set(null, oldMode); Trinium.failed = oldFailed;
            try (var files = Files.walk(temp)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}

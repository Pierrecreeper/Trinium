// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.shaders;

import fr.pierrecreeper.trinium.ui.ShaderAccess.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/** Bounded, extraction-free reader for the supported composite/final shader subset.
 * Terrain/shadow packs are rejected, never partially applied as if compatible. */
public final class StandalonePack {
    private static final int FILE_LIMIT = 2 * 1024 * 1024, TOTAL_LIMIT = 16 * FILE_LIMIT;
    private static final Pattern INCLUDE = Pattern.compile("(?m)^[ \\t]*(#include[ \\t]+\"([^\"]+)\"[ \\t]*(?://[^\\n]*)?)$");
    private static final Pattern DEFINE = Pattern.compile("(?m)^([ \\t]*)(//[ \\t]*)?#define[ \\t]+([A-Za-z_]\\w*)(?:[ \\t]+([^/\\r\\n]+?))?[ \\t]*(?://([^\\r\\n]*))?$");
    private static final Pattern VALUES = Pattern.compile("\\[([^]\\r\\n]+)]");
    public record Pass(String name, String vertex, String fragment, ShaderTargets.Directives targets) {
        public Pass(String name, String vertex, String fragment) {
            this(name, vertex, fragment, ShaderTargets.parse(name, vertex, fragment));
        }
    }
    public final String name;
    private final Map<String, String> files;
    private final Properties properties;
    private final LinkedHashMap<String, Choice> choices = new LinkedHashMap<>();
    private final Set<String> presenceOptions = new HashSet<>();

    public StandalonePack(Path directory, String name) throws IOException {
        if (!safeName(name)) throw new IOException("Invalid shader pack name.");
        this.name = name;
        Path root = directory.toRealPath();
        Path source = root.resolve(name);
        if (!source.toRealPath().startsWith(root)) throw new IOException("Shader pack is outside its folder.");
        Map<String, String> all = Files.isDirectory(source) ? readFolder(source) : readZip(source);
        String prefix = null;
        for (String path : all.keySet()) {
            int index = path.indexOf("shaders/");
            if (index >= 0 && (index == 0 || path.charAt(index - 1) == '/')) {
                String candidate = path.substring(0, index + 8);
                if (prefix == null) prefix = candidate;
                else if (!prefix.equals(candidate)) throw new IOException("Multiple shader roots in this pack.");
            }
        }
        if (prefix == null) throw new IOException("This pack has no shaders folder.");
        files = new LinkedHashMap<>();
        for (var entry : all.entrySet()) if (entry.getKey().startsWith(prefix)) {
            files.put(entry.getKey().substring(prefix.length()), entry.getValue());
        }
        properties = properties(files.getOrDefault("shaders.properties", ""));
        for (String path : files.keySet()) {
            String leaf = path.substring(path.lastIndexOf('/') + 1);
            if (leaf.matches("(?:gbuffers_.*|shadow.*|deferred\\d*|prepare\\d*|begin\\d*)\\.(?:vsh|fsh|gsh|csh)")
                || leaf.endsWith(".csh") || leaf.endsWith(".gsh")) {
                throw new IOException("This pack needs terrain, shadow or compute stages. Trinium does not support those stages yet.");
            }
            if (leaf.matches("composite\\d+\\.(?:vsh|fsh)")) {
                String number = leaf.substring(9, leaf.length() - 4);
                if (number.length() > 2 || Integer.parseInt(number) > 15) throw new IOException("This pack needs more composite stages than Trinium currently supports.");
            }
        }
        for (String key : properties.stringPropertyNames()) {
            if (key.matches("(?:program|texture|flip|scale|size\\.buffer|uniform|variable)\\..*")
                || key.equals("iris.features.required")) {
                throw new IOException("Unsupported shader pipeline setting: " + key);
            }
        }
        discoverOptions();
    }

    public static boolean safeName(String name) {
        return name != null && !name.isBlank() && !name.equals(".") && !name.equals("..")
            && !name.contains("/") && !name.contains("\\") && !name.contains("\0");
    }

    private static Map<String, String> readZip(Path source) throws IOException {
        if (!source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) throw new IOException("Choose a .zip shader pack or a folder.");
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(source.toFile(), StandardCharsets.UTF_8)) {
            if (zip.size() > 4096) throw new IOException("Shader pack has too many files.");
            var entries = zip.entries();
            int total = 0;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String path = checkedPath(entry.getName());
                if (entry.isDirectory() || !textFile(path)) continue;
                if (entry.getSize() > FILE_LIMIT) throw new IOException("Shader source is too large.");
                byte[] bytes;
                try (InputStream in = zip.getInputStream(entry)) { bytes = in.readNBytes(FILE_LIMIT + 1); }
                total += bytes.length;
                if (bytes.length > FILE_LIMIT || total > TOTAL_LIMIT) throw new IOException("Shader pack exceeds the source budget.");
                if (result.put(path, new String(bytes, StandardCharsets.UTF_8)) != null) throw new IOException("Duplicate shader source.");
            }
        }
        return result;
    }

    private static Map<String, String> readFolder(Path source) throws IOException {
        Path root = source.toRealPath();
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        int total = 0, count = 0;
        try (var paths = Files.walk(root, 24)) {
            for (Path file : (Iterable<Path>) paths::iterator) {
                if (++count > 4096) throw new IOException("Shader pack has too many files.");
                if (!Files.isRegularFile(file)) continue;
                if (!file.toRealPath().startsWith(root)) throw new IOException("Shader source is outside its pack.");
                String relative = checkedPath(root.relativize(file).toString().replace(File.separatorChar, '/'));
                if (!textFile(relative)) continue;
                byte[] bytes;
                try (InputStream in = Files.newInputStream(file)) { bytes = in.readNBytes(FILE_LIMIT + 1); }
                total += bytes.length;
                if (bytes.length > FILE_LIMIT || total > TOTAL_LIMIT) throw new IOException("Shader pack exceeds the source budget.");
                result.put(relative, new String(bytes, StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    private static boolean textFile(String path) {
        return path.matches(".*\\.(?:vsh|fsh|gsh|csh|glsl|properties|lang)$");
    }
    private static String checkedPath(String path) throws IOException {
        if (path.startsWith("/") || path.contains("\\") || path.contains("\0")
            || Arrays.asList(path.split("/")).contains("..") || path.contains(":")) throw new IOException("Invalid path inside shader pack.");
        return path;
    }
    private static Properties properties(String source) throws IOException {
        Properties p = new Properties(); p.load(new StringReader(source)); return p;
    }

    private void discoverOptions() throws IOException {
        Properties english = properties(files.getOrDefault("lang/en_us.lang", ""));
        for (var file : files.entrySet()) {
            if (!file.getKey().matches(".*\\.(?:glsl|fsh|vsh)$")) continue;
            Matcher defines = DEFINE.matcher(maskBlockComments(file.getValue()));
            while (defines.find()) {
                String id = defines.group(3), value = defines.group(4), comment = defines.group(5);
                if (comment == null) continue;
                Matcher allowed = VALUES.matcher(comment);
                List<String> values;
                boolean presence = value == null;
                if (value == null) { values = List.of("false", "true"); value = defines.group(2) == null ? "true" : "false"; }
                else if (allowed.find()) values = List.of(allowed.group(1).trim().split("\\s+"));
                else continue;
                value = value.trim();
                if (values.size() > 64 || !values.contains(value) || values.stream().anyMatch(v -> !v.matches("[-+A-Za-z0-9_.]+"))) continue;
                LinkedHashMap<String, String> labels = new LinkedHashMap<>();
                for (String v : values) labels.put(v, english.getProperty("value." + id + "." + v, v));
                Choice choice = new Choice(id, english.getProperty("option." + id, id.replace('_', ' ')),
                    english.getProperty("option." + id + ".comment", comment.replaceAll("\\[[^]]*]", "").trim()),
                    values, value, value, Map.copyOf(labels));
                Choice old = choices.putIfAbsent(id, choice);
                if (old != null && (!old.values().equals(values) || !old.defaultValue().equals(value)
                    || presenceOptions.contains(id) != presence)) throw new IOException("Conflicting shader option: " + id);
                if (presence) presenceOptions.add(id);
            }
        }
    }

    public Settings settings(Map<String, String> overrides) {
        LinkedHashMap<String, Choice> current = new LinkedHashMap<>();
        for (Choice c : choices.values()) current.put(c.id(), new Choice(c.id(), c.label(), c.comment(), c.values(),
            overrides.getOrDefault(c.id(), c.defaultValue()), c.defaultValue(), c.valueLabels()));
        List<Item> items = choices.keySet().stream().map(id -> new Item(id, null, false)).toList();
        return new Settings(name, Collections.unmodifiableMap(current), Map.of("", new Page("Shader settings", items)), List.of());
    }

    /** Pack updates may remove options or values. Discard only obsolete saved
     * preferences; explicit changes still fail validation instead of being lost. */
    public Map<String, String> configuredOptions(Map<String, String> saved, Map<String, String> changes) throws IOException {
        validateOptions(changes);
        LinkedHashMap<String, String> configured = new LinkedHashMap<>();
        for (Choice choice : choices.values()) {
            String value = saved.get(choice.id());
            if (value != null && choice.values().contains(value)) configured.put(choice.id(), value);
        }
        configured.putAll(changes);
        return Map.copyOf(configured);
    }

    private void validateOptions(Map<String, String> overrides) throws IOException {
        for (var entry : overrides.entrySet()) {
            Choice choice = choices.get(entry.getKey());
            if (choice == null || !choice.values().contains(entry.getValue())) throw new IOException("Invalid shader option: " + entry.getKey());
        }
    }

    public List<Pass> passes(String dimension, Map<String, String> overrides) throws IOException {
        validateOptions(overrides);
        String prefix = "world" + dimension + "/";
        List<Pass> result = new ArrayList<>();
        for (int i = 0; i <= 16; i++) {
            String id = i == 16 ? "final" : "composite" + (i == 0 ? "" : i);
            String fsh = files.containsKey(prefix + id + ".fsh") ? prefix + id + ".fsh" : id + ".fsh";
            if (!files.containsKey(fsh)) continue;
            String vsh = fsh.substring(0, fsh.length() - 4) + ".vsh";
            if (!files.containsKey(vsh)) throw new IOException("Missing vertex shader: " + vsh);
            String vertex = expand(vsh, new LinkedHashSet<>()), fragment = expand(fsh, new LinkedHashSet<>());
            vertex = configure(vertex, overrides); fragment = configure(fragment, overrides);
            try {
                ShaderTargets.Directives targets = ShaderTargets.parse(id, vertex, fragment);
                result.add(new Pass(id, transform(vertex, true, targets), transform(fragment, false, targets), targets));
            } catch (IllegalArgumentException invalid) { throw new IOException(invalid.getMessage(), invalid); }
        }
        if (result.isEmpty()) throw new IOException("No supported composite or final programs in this pack.");
        return List.copyOf(result);
    }

    private String expand(String path, Set<String> stack) throws IOException {
        checkedPath(path);
        if (stack.size() >= 32 || !stack.add(path)) throw new IOException("Shader include cycle or depth limit: " + path);
        String source = files.get(path);
        if (source == null) throw new IOException("Missing shader include: " + path);
        source = source.replace("\r", "");
        Matcher includes = INCLUDE.matcher(maskBlockComments(source));
        StringBuilder expanded = new StringBuilder();
        int cursor = 0;
        while (includes.find()) {
            String target = includes.group(2);
            if (target.startsWith("/")) target = target.substring(1);
            else target = path.substring(0, path.lastIndexOf('/') + 1) + target;
            expanded.append(source, cursor, includes.start(1)).append(expand(target, stack));
            cursor = includes.end();
            if (expanded.length() > FILE_LIMIT) throw new IOException("Expanded shader is too large.");
        }
        expanded.append(source, cursor, source.length()); stack.remove(path);
        if (expanded.length() > FILE_LIMIT) throw new IOException("Expanded shader is too large.");
        return expanded.toString();
    }

    private String configure(String source, Map<String, String> overrides) {
        String active = maskBlockComments(source);
        Matcher defines = DEFINE.matcher(active);
        StringBuilder configured = new StringBuilder();
        int cursor = 0;
        while (defines.find()) {
            String id = defines.group(3);
            if (!choices.containsKey(id)) continue;
            String value = overrides.getOrDefault(id, choices.get(id).defaultValue());
            String replacement = presenceOptions.contains(id)
                ? (value.equals("true") ? "#define " : "//#define ") + id
                : "#define " + id + " " + value;
            int directiveStart = defines.group(2) == null ? active.indexOf('#', defines.start()) : defines.start(2);
            configured.append(source, cursor, directiveStart).append(replacement);
            cursor = defines.end();
        }
        configured.append(source, cursor, source.length());
        return configured.toString();
    }

    /** Keep offsets and line comments: //#define is a real presence option, while
     * directives in block comments are inactive. Replacement uses original spans
     * so shaderpack buffer-format metadata inside block comments is preserved. */
    private static String maskBlockComments(String source) {
        StringBuilder masked = new StringBuilder(source);
        boolean line = false, block = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i), next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            if (line) { if (c == '\n' || c == '\r') line = false; else continue; }
            if (block) {
                if (c == '*' && next == '/') { masked.setCharAt(i, ' '); masked.setCharAt(++i, ' '); block = false; }
                else if (c != '\n' && c != '\r') masked.setCharAt(i, ' ');
            } else if (c == '/' && next == '/') { line = true; i++; }
            else if (c == '/' && next == '*') { masked.setCharAt(i, ' '); masked.setCharAt(++i, ' '); block = true; }
        }
        return masked.toString();
    }

    private static String transform(String source, boolean vertex, ShaderTargets.Directives targets) throws IOException {
        source = ShaderTargets.code(ShaderTargets.stripConstants(source)).replaceAll("(?m)^\\s*#version[^\\n]*", "");
        if (!vertex) {
            Matcher locations = Pattern.compile("layout\\s*\\([^)]*location\\s*=\\s*(\\d+)[^)]*\\)\\s*out\\b").matcher(source);
            while (locations.find()) if (Integer.parseInt(locations.group(1)) >= targets.outputs().size())
                throw new IOException("Shader output location exceeds its render targets.");
        }
        source = source.replaceAll("\\btexture2D\\b", "texture").replaceAll("\\btexture2DLod\\b", "textureLod")
            .replaceAll("\\bvarying\\b", vertex ? "out" : "in");
        String header = "#version 330 core\n#define MC_VERSION 260300\n#define TRINIUM 1\n";
        if (vertex) {
            header += "layout(location=0) in vec4 trinium_Position;\nlayout(location=1) in vec4 trinium_TexCoord;\n";
            source = source.replaceAll("\\bgl_Vertex\\b", "trinium_Position").replaceAll("\\bgl_MultiTexCoord0\\b", "trinium_TexCoord")
                .replaceAll("\\bftransform\\s*\\(\\s*\\)", "trinium_Position");
        } else if (source.contains("gl_FragColor") || source.contains("gl_FragData")) {
            Set<Integer> outputs = new TreeSet<>();
            if (source.contains("gl_FragColor")) outputs.add(0);
            Matcher data = Pattern.compile("\\bgl_FragData\\s*\\[\\s*(\\d+)\\s*]").matcher(source);
            StringBuilder rewritten = new StringBuilder();
            while (data.find()) {
                int slot = Integer.parseInt(data.group(1));
                if (slot >= targets.outputs().size()) throw new IOException("Shader output exceeds its render targets.");
                outputs.add(slot); data.appendReplacement(rewritten, "trinium_FragColor" + slot);
            }
            data.appendTail(rewritten); source = rewritten.toString();
            if (Pattern.compile("\\bgl_FragData\\b").matcher(source).find())
                throw new IOException("Shader outputs need constant indices.");
            for (int slot : outputs) header += "layout(location=" + slot + ") out vec4 trinium_FragColor" + slot + ";\n";
            source = source.replaceAll("\\bgl_FragColor\\b", "trinium_FragColor0");
        }
        return header + source;
    }
}

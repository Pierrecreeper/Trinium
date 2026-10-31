// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.shaders;

import java.util.*;
import java.util.regex.*;

/** Original, bounded description of composite render targets. No game or GL state. */
public final class ShaderTargets {
    public static final int LIMIT = 8;
    public static final long MAX_IMAGE_BYTES = 256L * 1024 * 1024;
    private static final String BUFFER = "(?:colortex\\d+|gcolor|gdepth|gnormal|composite|gaux[1-4])";
    private static final Pattern ROUTE = Pattern.compile("/\\*\\s*(DRAWBUFFERS|RENDERTARGETS)\\s*:\\s*([^*]*?)\\s*\\*/");
    private static final Pattern CONSTANT = Pattern.compile("\\bconst\\s+(int|bool|vec4)\\s+(" + BUFFER
        + ")(Format|ClearColor|Clear|MipmapEnabled)\\s*=\\s*([^;]+);");
    private static final Pattern CONDITIONAL = Pattern.compile("(?m)^\\s*#\\s*(if|ifdef|ifndef|elif|else|endif)\\b[^\\n]*");
    private static final Pattern FLOAT = Pattern.compile("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?[fF]?");

    private ShaderTargets() { }

    public enum Format {
        R8(1), RG8(2), RGB8(4), RGBA8(4),
        R16F(2), RG16F(4), RGB16F(8), RGBA16F(8),
        R32F(4), RG32F(8), RGB32F(16), RGBA32F(16),
        R11F_G11F_B10F(4), R8_SNORM(1), RG8_SNORM(2), RGB8_SNORM(4), RGBA8_SNORM(4);
        private final int bytes;
        Format(int bytes) { this.bytes = bytes; }
        public int bytes() { return bytes; }
    }

    public record Color(float r, float g, float b, float a) {
        public Color {
            if (!Float.isFinite(r) || !Float.isFinite(g) || !Float.isFinite(b) || !Float.isFinite(a))
                throw new IllegalArgumentException("Non-finite shader clear color.");
        }
        public float[] array() { return new float[]{r, g, b, a}; }
    }

    public record Directives(List<Integer> outputs, Set<Integer> mipmaps,
                             Map<Integer, Format> formats, Map<Integer, Boolean> clears,
                             Map<Integer, Color> clearColors, boolean presentation) {
        public Directives {
            outputs = List.copyOf(outputs); mipmaps = Set.copyOf(mipmaps);
            formats = Map.copyOf(formats); clears = Map.copyOf(clears); clearColors = Map.copyOf(clearColors);
            if (outputs.isEmpty() || outputs.size() > LIMIT || new HashSet<>(outputs).size() != outputs.size())
                throw new IllegalArgumentException("Invalid or duplicate shader render targets.");
            outputs.forEach(ShaderTargets::checkIndex); mipmaps.forEach(ShaderTargets::checkIndex);
            formats.keySet().forEach(ShaderTargets::checkIndex); clears.keySet().forEach(ShaderTargets::checkIndex);
            clearColors.keySet().forEach(ShaderTargets::checkIndex);
            if (presentation && !outputs.equals(List.of(0)))
                throw new IllegalArgumentException("The final program cannot write multiple color targets.");
        }
    }

    public record Target(Format format, boolean clear, Color clearColor, boolean written, boolean mipmapped) { }

    /** Resolve declarations globally, but allocate only targets actually read or written. */
    public static Map<Integer, Target> layout(List<Directives> passes, Set<Integer> sampled) {
        TreeSet<Integer> used = new TreeSet<>(sampled); used.add(0);
        Set<Integer> written = new HashSet<>(), mipmaps = new HashSet<>();
        Map<Integer, Format> formats = new HashMap<>(); Map<Integer, Boolean> clears = new HashMap<>();
        Map<Integer, Color> colors = new HashMap<>();
        for (Directives pass : passes) {
            if (!pass.presentation()) { used.addAll(pass.outputs()); written.addAll(pass.outputs()); }
            used.addAll(pass.mipmaps()); mipmaps.addAll(pass.mipmaps());
            merge(formats, pass.formats(), "format"); merge(clears, pass.clears(), "clear");
            merge(colors, pass.clearColors(), "clear color");
        }
        if (Boolean.FALSE.equals(clears.get(0)))
            throw new IllegalArgumentException("colortex0Clear=false requires scene history that this post renderer cannot preserve. Use an auxiliary target for history.");
        LinkedHashMap<Integer, Target> result = new LinkedHashMap<>();
        for (int index : used) {
            checkIndex(index);
            // Buffer 0 is seeded from the complete vanilla scene each frame. Its
            // clear value cannot substitute for the missing pack gbuffer stages.
            Color fallback = index == 1 ? new Color(1, 1, 1, 1) : new Color(0, 0, 0, 0);
            result.put(index, new Target(formats.getOrDefault(index, Format.RGBA8),
                clears.getOrDefault(index, true), colors.getOrDefault(index, fallback),
                written.contains(index), mipmaps.contains(index)));
        }
        return Collections.unmodifiableMap(result);
    }

    public static long imageBytes(Map<Integer, Target> targets, int width, int height, boolean depth) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid shader target dimensions.");
        long pixels = Math.multiplyExact((long) width, height), total = depth ? Math.multiplyExact(pixels, 4) : 0;
        for (Target target : targets.values()) {
            long bytes = Math.multiplyExact(pixels, target.format().bytes());
            if (target.mipmapped()) {
                int w = width, h = height;
                while (w > 1 || h > 1) {
                    w = Math.max(1, w / 2); h = Math.max(1, h / 2);
                    bytes = Math.addExact(bytes, Math.multiplyExact((long) w * h, target.format().bytes()));
                }
            }
            total = Math.addExact(total, Math.multiplyExact(bytes, target.written() ? 2 : 1));
        }
        return total;
    }

    public static Directives parse(String name, String vertex, String fragment) {
        boolean presentation = name.equals("final");
        List<Integer> outputs = null;
        Matcher routes = ROUTE.matcher(maskComments(fragment, true));
        NavigableMap<Integer, Integer> fragmentConditions = conditions(fragment); int declarations = 0;
        while (routes.find()) {
            if (++declarations > 128) throw new IllegalArgumentException("Too many shader target declarations.");
            rejectConditional(fragmentConditions, routes.start());
            String text = routes.group(2).trim();
            List<Integer> parsed = new ArrayList<>();
            if (routes.group(1).equals("DRAWBUFFERS")) {
                if (!text.matches("[0-7]{1,8}")) throw new IllegalArgumentException("Unsupported DRAWBUFFERS directive: " + text);
                for (int i = 0; i < text.length(); i++) parsed.add(text.charAt(i) - '0');
            } else {
                if (!text.matches("\\d+(?:\\s*,\\s*\\d+)*")) throw new IllegalArgumentException("Invalid RENDERTARGETS directive: " + text);
                for (String value : text.split("\\s*,\\s*")) {
                    try { parsed.add(Integer.parseInt(value)); }
                    catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid color target index.", invalid); }
                }
            }
            if (outputs != null && !outputs.equals(parsed)) throw new IllegalArgumentException("Conflicting shader output directives.");
            outputs = List.copyOf(parsed);
        }
        if (outputs == null) outputs = List.of(0);
        Map<Integer, Format> formats = new HashMap<>(); Map<Integer, Boolean> clears = new HashMap<>();
        Map<Integer, Color> colors = new HashMap<>(); Map<Integer, Boolean> mipmaps = new HashMap<>();
        for (String source : List.of(vertex, fragment)) {
            Matcher constants = CONSTANT.matcher(maskComments(source, true));
            NavigableMap<Integer, Integer> conditional = conditions(source);
            while (constants.find()) {
                if (++declarations > 128) throw new IllegalArgumentException("Too many shader target declarations.");
                rejectConditional(conditional, constants.start());
                int index = index(constants.group(2)); String suffix = constants.group(3), value = constants.group(4).trim();
                switch (suffix) {
                    case "Format" -> {
                        requireType(constants.group(1), "int");
                        Format format;
                        try { format = Format.valueOf(value); }
                        catch (IllegalArgumentException unknown) { throw new IllegalArgumentException("Unsupported shader buffer format: " + value); }
                        put(formats, index, format, "format");
                    }
                    case "Clear", "MipmapEnabled" -> {
                        requireType(constants.group(1), "bool");
                        if (!value.equals("true") && !value.equals("false"))
                            throw new IllegalArgumentException("Shader buffer " + suffix + " must be a literal boolean.");
                        put(suffix.equals("Clear") ? clears : mipmaps, index, Boolean.valueOf(value), suffix);
                    }
                    case "ClearColor" -> {
                        requireType(constants.group(1), "vec4"); put(colors, index, parseColor(value), "clear color");
                    }
                    default -> throw new AssertionError(suffix);
                }
            }
        }
        Set<Integer> enabledMipmaps = new HashSet<>();
        mipmaps.forEach((index, enabled) -> { if (enabled) enabledMipmaps.add(index); });
        return new Directives(outputs, enabledMipmaps, formats, clears, colors, presentation);
    }

    /** Constants are loader directives, not executable GLSL; remove recognized declarations. */
    public static String stripConstants(String source) {
        Matcher constants = CONSTANT.matcher(maskComments(source)); StringBuilder result = new StringBuilder(source);
        while (constants.find()) for (int i = constants.start(); i < constants.end(); i++)
            if (result.charAt(i) != '\n' && result.charAt(i) != '\r') result.setCharAt(i, ' ');
        return result.toString();
    }

    public static int index(String name) {
        int result = switch (name) {
            case "gcolor" -> 0; case "gdepth" -> 1; case "gnormal" -> 2; case "composite" -> 3;
            case "gaux1" -> 4; case "gaux2" -> 5; case "gaux3" -> 6; case "gaux4" -> 7;
            default -> {
                if (!name.matches("colortex\\d+")) yield -1;
                try { yield Integer.parseInt(name.substring(8)); }
                catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid shader color sampler.", invalid); }
            }
        };
        if (result >= 0) checkIndex(result);
        return result;
    }

    private static void checkIndex(int index) {
        if (index < 0 || index >= LIMIT) throw new IllegalArgumentException("Shader color target must be in colortex0 through colortex7: " + index);
    }
    private static void requireType(String found, String expected) {
        if (!found.equals(expected)) throw new IllegalArgumentException("Invalid shader buffer directive type: " + found);
    }
    private static Color parseColor(String value) {
        Matcher vector = Pattern.compile("vec4\\s*\\((.*)\\)", Pattern.DOTALL).matcher(value);
        if (!vector.matches()) throw new IllegalArgumentException("Clear color must be a literal vec4.");
        String[] parts = vector.group(1).split(",", -1);
        if (parts.length != 1 && parts.length != 4) throw new IllegalArgumentException("Clear color needs one or four values.");
        float[] values = new float[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[parts.length == 1 ? 0 : i].trim();
            if (!FLOAT.matcher(part).matches()) throw new IllegalArgumentException("Clear color must contain finite numeric literals.");
            values[i] = Float.parseFloat(part);
        }
        return new Color(values[0], values[1], values[2], values[3]);
    }
    private static <T> void put(Map<Integer, T> map, int key, T value, String label) {
        T previous = map.putIfAbsent(key, value);
        if (previous != null && !previous.equals(value)) throw new IllegalArgumentException("Conflicting colortex" + key + " " + label + " declarations.");
    }
    private static <T> void merge(Map<Integer, T> destination, Map<Integer, T> source, String label) {
        source.forEach((key, value) -> put(destination, key, value, label));
    }

    private static NavigableMap<Integer, Integer> conditions(String source) {
        TreeMap<Integer, Integer> result = new TreeMap<>(); result.put(0, 0);
        Matcher directives = CONDITIONAL.matcher(maskComments(source)); int depth = 0;
        while (directives.find()) {
            switch (directives.group(1)) {
                case "if", "ifdef", "ifndef" -> depth++;
                case "endif" -> depth--;
                default -> { }
            }
            result.put(directives.end(), depth);
        }
        return result;
    }
    private static void rejectConditional(NavigableMap<Integer, Integer> conditions, int position) {
        if (conditions.floorEntry(position).getValue() != 0)
            throw new IllegalArgumentException("Conditional shader target declarations are not supported yet.");
    }

    /** Preserve offsets/newlines so directives inside comments are never interpreted. */
    static String code(String source) { return maskComments(source); }
    private static String maskComments(String source) { return maskComments(source, false); }
    private static String maskComments(String source, boolean retainBlocks) {
        StringBuilder result = new StringBuilder(source); boolean line = false, block = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i), next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            if (line && (c == '\n' || c == '\r')) line = false;
            if (!line && !block && c == '/' && (next == '/' || next == '*')) {
                line = next == '/'; block = next == '*';
                if (!block || !retainBlocks) { result.setCharAt(i, ' '); result.setCharAt(i + 1, ' '); }
                i++; continue;
            }
            if (block && c == '*' && next == '/') {
                if (!retainBlocks) { result.setCharAt(i, ' '); result.setCharAt(i + 1, ' '); }
                i++; block = false; continue;
            }
            if ((line || (block && !retainBlocks)) && c != '\n' && c != '\r') result.setCharAt(i, ' ');
        }
        return result.toString();
    }
}

// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.shaders;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Format limits, option validation, includes, dimensions and incompatible pack refusal. */
public final class StandalonePackTest {
    public static final String VERTEX = "#version 120\nvarying vec2 uv;void main(){uv=gl_MultiTexCoord0.xy;gl_Position=ftransform();}\n";
    public static final String FRAGMENT = "#version 120\n#define EXPOSURE 1.0 // [0.5 1.0 1.5]\nvarying vec2 uv;uniform sampler2D colortex0;void main(){gl_FragColor=texture2D(colortex0,uv)*EXPOSURE;}\n";
    private static int checks;
    private static void check(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); checks++; }
    public static void zip(Path path, Map<String,String> files) throws IOException {
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey())); zip.write(file.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
            }
        }
    }
    private static void refuses(Path root, String name, Map<String,String> files, String fragment) throws Exception {
        zip(root.resolve(name), files);
        try { new StandalonePack(root, name).passes("0", Map.of()); throw new AssertionError("Accepted " + name); }
        catch (IOException expected) { check(expected.getMessage().toLowerCase(Locale.ROOT).contains(fragment), name + ": " + expected); }
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("trinium-pack-test-");
        try {
            zip(root.resolve("valid.zip"), Map.of("wrapper/shaders/final.vsh", VERTEX, "wrapper/shaders/final.fsh", FRAGMENT,
                "wrapper/shaders/world-1/final.vsh", VERTEX, "wrapper/shaders/world-1/final.fsh", FRAGMENT.replace("*EXPOSURE", "*EXPOSURE*.75"),
                "wrapper/shaders/lang/en_us.lang", "option.EXPOSURE=Exposure\n"));
            StandalonePack p = new StandalonePack(root, "valid.zip");
            check(p.passes("0", Map.of()).size() == 1, "Root program");
            check(!p.passes("-1", Map.of()).equals(p.passes("0", Map.of())), "Dimension override");
            check(p.passes("1", Map.of()).equals(p.passes("0", Map.of())), "Dimension fallback");
            check(p.settings(Map.of()).choices().get("EXPOSURE").label().equals("Exposure"), "English option label");
            check(p.settings(Map.of()).choices().get("EXPOSURE").values().equals(List.of("0.5","1.0","1.5")), "Explicit values");
            String source = p.passes("0", Map.of("EXPOSURE", "1.5")).getFirst().fragment();
            check(source.contains("#define EXPOSURE 1.5"), "Apply option to source");
            check(!source.contains("#version 120") && source.startsWith("#version 330 core"), "Core profile conversion");
            try { p.passes("0", Map.of("EXPOSURE", "invalid")); throw new AssertionError("Invalid option accepted"); }
            catch (IOException expected) { checks++; }
            refuses(root,"terrain.zip",Map.of("shaders/gbuffers_terrain.vsh",VERTEX),"terrain");
            refuses(root,"escape.zip",Map.of("../shaders/final.vsh",VERTEX),"invalid path");
            refuses(root,"cycle.zip",Map.of("shaders/final.vsh",VERTEX,"shaders/final.fsh","#include \"/cycle.glsl\"","shaders/cycle.glsl","#include \"/cycle.glsl\""),"cycle");
            refuses(root,"missing.zip",Map.of("shaders/final.vsh",VERTEX,"shaders/final.fsh","#include \"/missing.glsl\""),"missing");
            refuses(root,"large.zip",Map.of("shaders/final.vsh",VERTEX,"shaders/final.fsh","x".repeat(2*1024*1024+1)),"large");
            refuses(root,"targets.zip",Map.of("shaders/final.vsh",VERTEX,"shaders/final.fsh","/* DRAWBUFFERS:01 */\n"+FRAGMENT),"multiple");
            Files.createDirectories(root.resolve("folder/shaders"));
            Files.writeString(root.resolve("folder/shaders/final.vsh"),VERTEX);
            Files.writeString(root.resolve("folder/shaders/final.fsh"),FRAGMENT);
            check(new StandalonePack(root,"folder").passes("0",Map.of()).size()==1,"Folder pack");
            Files.createDirectories(root.resolve("include/shaders/lib"));
            Files.writeString(root.resolve("include/shaders/final.vsh"),VERTEX);
            Files.writeString(root.resolve("include/shaders/final.fsh"),"#version 120\n#include \"/lib/color.glsl\"\n");
            Files.writeString(root.resolve("include/shaders/lib/color.glsl"),FRAGMENT.replace("#version 120", ""));
            check(new StandalonePack(root,"include").passes("0",Map.of()).getFirst().fragment().contains("colortex0"),"Root include");
            check(!StandalonePack.safeName("../pack.zip")&&!StandalonePack.safeName("..")&&!StandalonePack.safeName("a\\b"),"Pack path validation");
            zip(root.resolve("boolean-values.zip"),Map.of("shaders/final.vsh",VERTEX,"shaders/final.fsh",
                "#version 120\n#define FEATURE true // [false true]\nvoid main(){gl_FragColor=FEATURE?vec4(1.0):vec4(0.0);}"));
            StandalonePack booleanValues=new StandalonePack(root,"boolean-values.zip");
            check(booleanValues.passes("0",Map.of()).getFirst().fragment().contains("#define FEATURE true"),"Valued boolean default keeps its GLSL expression");
            check(booleanValues.passes("0",Map.of("FEATURE","false")).getFirst().fragment().contains("#define FEATURE false"),"Valued boolean false remains a defined value");
            zip(root.resolve("presence.zip"),Map.of("shaders/final.vsh",VERTEX,"shaders/final.fsh",
                "#version 120\n//#define FEATURE // Toggle\n#ifdef FEATURE\n#define COLOR vec4(1.0)\n#else\n#define COLOR vec4(0.0)\n#endif\nvoid main(){gl_FragColor=COLOR;}"));
            StandalonePack presence=new StandalonePack(root,"presence.zip");
            check(presence.settings(Map.of()).choices().get("FEATURE").defaultValue().equals("false"),"Commented presence option remains discoverable");
            check(!presence.passes("0",Map.of()).getFirst().fragment().contains("#define FEATURE"),"Disabled presence option stays undefined");
            check(presence.passes("0",Map.of("FEATURE","true")).getFirst().fragment().contains("#define FEATURE\n"),"Enabled presence option has no expression value");
            refuses(root,"mixed-boolean.zip",Map.of("shaders/final.vsh",VERTEX+"#define FEATURE // Toggle\n",
                "shaders/final.fsh","#define FEATURE true // [false true]\n"+FRAGMENT),"conflicting");
            zip(root.resolve("block-comments.zip"),Map.of("shaders/final.vsh",VERTEX,"shaders/final.fsh",
                "#version 120\n/*\n#include \"/missing.glsl\"\n#define EXPOSURE 9.0 // [8.0 9.0]\n#define GHOST // Hidden\n*/\n"
                +"// /* A line comment cannot start a block comment.\n"
                +"/* const int colortex1Format=RGBA16F; */\n#include \"/lib/color.glsl\"\n",
                "shaders/lib/color.glsl",FRAGMENT.replace("#version 120", "")));
            StandalonePack commented=new StandalonePack(root,"block-comments.zip");
            check(commented.settings(Map.of()).choices().keySet().equals(Set.of("EXPOSURE")),"Block-comment options are ignored; following live options remain");
            var commentPass=commented.passes("0",Map.of("EXPOSURE","1.5")).getFirst();
            check(commentPass.fragment().contains("#define EXPOSURE 1.5")&&commentPass.fragment().contains("colortex0"),"Commented include ignored while real include and option apply");
            check(commentPass.targets().formats().get(1)==ShaderTargets.Format.RGBA16F,"Comment masking preserves block-comment format metadata");
            Map<String,String> kept=p.configuredOptions(Map.of("EXPOSURE","1.5","REMOVED_OPTION","true"),Map.of());
            check(kept.equals(Map.of("EXPOSURE","1.5")),"Pack update drops removed saved options but preserves compatible values");
            Map<String,String> reset=p.configuredOptions(Map.of("EXPOSURE","2.0"),Map.of());
            check(reset.isEmpty()&&p.passes("0",reset).getFirst().fragment().contains("#define EXPOSURE 1.0"),"Removed saved value falls back to the updated pack default");
            check(p.configuredOptions(Map.of("EXPOSURE","1.5"),Map.of("EXPOSURE","0.5")).equals(Map.of("EXPOSURE","0.5")),"Explicit valid change wins over saved preference");
            for(Map<String,String> invalid:List.of(Map.of("EXPOSURE","2.0"),Map.of("REMOVED_OPTION","true"))){
                try{p.configuredOptions(Map.of(),invalid);throw new AssertionError("Invalid explicit option was silently discarded");}
                catch(IOException expected){check(expected.getMessage().contains("Invalid shader option"),"Explicit invalid changes remain rejected");}
            }
            Map<String,String> savedOptions=new LinkedHashMap<>(Map.of("EXPOSURE","1.5"));
            try{p.configuredOptions(savedOptions,Map.of("EXPOSURE","bad"));throw new AssertionError("Invalid edit accepted");}
            catch(IOException expected){check(savedOptions.equals(Map.of("EXPOSURE","1.5")),"Rejected explicit edit leaves saved preferences unchanged");}
            Properties unicode=new Properties();unicode.setProperty("pack","Éclairage-世界.zip");
            unicode.setProperty("option.TEST","é".repeat(100));
            int remaining=ShaderSettingsFile.MAX_BYTES-ShaderSettingsFile.encode(unicode).length;
            unicode.setProperty("option.TEST",unicode.getProperty("option.TEST")+"a".repeat(remaining));
            Path settingsFile=root.resolve("config/shaders.properties");
            ShaderSettingsFile.write(settingsFile,unicode);
            byte[] before=Files.readAllBytes(settingsFile);
            check(before.length==ShaderSettingsFile.MAX_BYTES,"Exactly 64 KiB of UTF-8 settings is writable");
            Properties restored=new Properties();try(Reader reader=Files.newBufferedReader(settingsFile)){restored.load(reader);}
            check(restored.equals(unicode),"Unicode settings at the byte boundary round-trip without loss");
            unicode.setProperty("option.TEST",unicode.getProperty("option.TEST")+"a");
            try{ShaderSettingsFile.write(settingsFile,unicode);throw new AssertionError("UTF-8 byte limit exceeded");}
            catch(IOException expected){check(expected.getMessage().contains("budget"),"One excess UTF-8 byte is rejected even when character count fits");}
            check(Arrays.equals(Files.readAllBytes(settingsFile),before),"Rejected oversized save preserves the previous file byte-for-byte");
            for(String argument:args) {
                Path sourcePack=Path.of(argument).toAbsolutePath();
                try {new StandalonePack(sourcePack.getParent(),sourcePack.getFileName().toString());throw new AssertionError("Complex pack falsely accepted");}
                catch(IOException expected){check(expected.getMessage().contains("terrain"),"Complex pack is explicitly unsupported");}
            }
            System.out.println("PASS independent shader pack reader: "+checks+" checks");
        } finally { try(var files=Files.walk(root)){ for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.delete(file); } }
    }
}

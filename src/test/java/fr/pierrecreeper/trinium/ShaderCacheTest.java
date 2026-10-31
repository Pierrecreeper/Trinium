package fr.pierrecreeper.trinium;

import fr.pierrecreeper.trinium.shaders.ShaderCache;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.frontend.shaders.GlslCompiler;
import com.mojang.renderpearl.backend.api.SpvModule;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Native shaderc oracle, includes, stale/corrupt cache, ownership and concurrent startup. */
public final class ShaderCacheTest {
    private static final String SOURCE="#version 450\n#include <test:outer.glsl>\nvoid main(){gl_Position=vec4(value(),0,0,1);}\n";
    private static int checks;
    private static final class Source implements ShaderSource {
        final Map<String,CachedIncludeSource> includes=new HashMap<>();
        Source(String value) {this(value,false);}
        Source(String value,boolean padded) {
            includes.put("test:outer.glsl",include("test:outer.glsl",padded,"#include <test:inner.glsl>\nfloat value(){return INNER;}\n"));
            includes.put("test:inner.glsl",include("test:inner.glsl",padded,"#define INNER "+value+"\n"));
        }
        private static CachedIncludeSource include(String id,boolean padded,String text) {
            if(!padded)return CachedIncludeSource.create(Identifier.parse(id),text);
            byte[] a=id.getBytes(java.nio.charset.StandardCharsets.UTF_8),b=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var name=org.lwjgl.system.MemoryUtil.memAlloc(a.length+16);var content=org.lwjgl.system.MemoryUtil.memAlloc(b.length+16);
            name.put(a);content.put(b);
            for(int i=0;i<15;i++){name.put((byte)65);content.put((byte)66);}name.put((byte)0);content.put((byte)0);
            name.flip().limit(a.length);content.flip().limit(b.length);
            var include=org.lwjgl.util.shaderc.ShadercIncludeResult.calloc().source_name(name).content(content);
            return new CachedIncludeSource(include.address());
        }
        public String getShader(Identifier id,ShaderType type){return SOURCE;}
        public CachedIncludeSource getInclude(Identifier id){return includes.get(id.toString());}
        public void close(){includes.values().forEach(CachedIncludeSource::close);}
    }
    private static byte[] bytes(SpvModule m){byte[] b=new byte[m.spv().remaining()];m.spv().duplicate().get(b);return b;}
    private static void check(boolean good,String message){if(!good)throw new AssertionError(message);checks++;}
    public static void main(String[] args)throws Exception {
        Path directory=Files.createTempDirectory("trinium-shader-test-");
        AtomicInteger compiled=new AtomicInteger();
        try(var compiler=new GlslCompiler(false,true);var a=new Source("0.25");var b=new Source("0.75")) {
            ShaderCache.Compiler compile=source->{compiled.incrementAndGet();return compiler.compileToSpv("test",SOURCE,ShaderType.VERTEX,ShaderDefines.EMPTY,source);};
            byte[] first;
            try(var oracle=compiler.compileToSpv("test",SOURCE,ShaderType.VERTEX,ShaderDefines.EMPTY,a);
                var miss=ShaderCache.compile(directory,"test",SOURCE,ShaderType.VERTEX,ShaderDefines.EMPTY,false,true,a,compile)) {
                first=bytes(miss);check(Arrays.equals(bytes(oracle),first),"cold binary parity");
            }
            try(var hit=ShaderCache.compile(directory,"test",SOURCE,ShaderType.VERTEX,ShaderDefines.EMPTY,false,true,a,compile)) {
                check(Arrays.equals(first,bytes(hit)),"warm binary parity");hit.reflect();check(compiled.get()==1,"cache actually hits");
            }
            try(var padded=new Source("0.25",true);var hit=ShaderCache.compile(directory,"test",SOURCE,ShaderType.VERTEX,ShaderDefines.EMPTY,false,true,padded,compile)) {
                check(Arrays.equals(first,bytes(hit)),"bounded non-NUL include content");check(compiled.get()==1,"allocator tail bytes do not invalidate cache");
            }
            try(var changed=ShaderCache.compile(directory,"test",SOURCE,ShaderType.VERTEX,ShaderDefines.EMPTY,false,true,b,compile);
                var oracle=compiler.compileToSpv("test",SOURCE,ShaderType.VERTEX,ShaderDefines.EMPTY,b)) {
                check(Arrays.equals(bytes(oracle),bytes(changed)),"transitive include invalidation");check(!Arrays.equals(first,bytes(changed)),"new include changed shader");check(compiled.get()==2,"recompiled include");
            }
            Path file;try(var files=Files.list(directory)){file=files.findFirst().orElseThrow();}
            Files.write(file,new byte[]{1,2,3});
            try(var repaired=ShaderCache.compile(directory,"test",SOURCE,ShaderType.VERTEX,ShaderDefines.EMPTY,false,true,b,compile)){check(compiled.get()==3,"truncation falls back");repaired.reflect();}
            byte[] corrupt=Files.readAllBytes(file);corrupt[corrupt.length-40]^=1;Files.write(file,corrupt);
            try(var repaired=ShaderCache.compile(directory,"test",SOURCE,ShaderType.VERTEX,ShaderDefines.EMPTY,false,true,b,compile)){check(compiled.get()==4,"checksum falls back");}
            for(boolean zero:new boolean[]{false,true})for(boolean draws:new boolean[]{false,true})try(var c=new GlslCompiler(zero,draws)) {
                var defines=new ShaderDefines(Map.of("TEST","1"),Set.of("FLAG"));
                for(int pass=0;pass<2;pass++)try(var oracle=c.compileToSpv("variant",SOURCE,ShaderType.VERTEX,defines,a);
                    var cached=ShaderCache.compile(directory,"variant",SOURCE,ShaderType.VERTEX,defines,zero,draws,a,s->c.compileToSpv("variant",SOURCE,ShaderType.VERTEX,defines,s))) {
                    check(Arrays.equals(bytes(oracle),bytes(cached)),"define/options parity");
                }
            }
            try(var executor=Executors.newFixedThreadPool(4)) {
                int before=compiled.get();var tasks=new ArrayList<Callable<byte[]>>();
                for(int i=0;i<16;i++)tasks.add(()->{try(var m=ShaderCache.compile(directory,"parallel",SOURCE,ShaderType.VERTEX,ShaderDefines.EMPTY,false,true,a,compile)){return bytes(m);}});
                for(var task:executor.invokeAll(tasks))check(Arrays.equals(first,task.get()),"parallel ownership parity");
                check(compiled.get()==before+1,"one compilation for concurrent key");
            }
            Path blocked=directory.resolve("file-not-directory");Files.writeString(blocked,"x");
            try(var m=ShaderCache.compile(blocked,"io",SOURCE,ShaderType.VERTEX,ShaderDefines.EMPTY,false,true,a,compile)){check(Arrays.equals(first,bytes(m)),"I/O failure preserves shader");}
            int before=compiled.get();
            for(int i=0;i<2;i++)try {
                ShaderCache.compile(directory,"invalid","not GLSL",ShaderType.VERTEX,ShaderDefines.EMPTY,false,true,a,s->{compiled.incrementAndGet();return compiler.compileToSpv("invalid","not GLSL",ShaderType.VERTEX,ShaderDefines.EMPTY,s);});
                throw new AssertionError("Invalid shader accepted");
            }catch(com.mojang.renderpearl.util.ShaderCompileException expected){checks++;}
            check(compiled.get()==before+2,"failed compiles are not cached");
            long start=System.nanoTime();for(int i=0;i<50;i++)try(var m=compiler.compileToSpv("test",SOURCE,ShaderType.VERTEX,ShaderDefines.EMPTY,a)){}long cold=System.nanoTime()-start;
            try(var m=ShaderCache.compile(directory,"bench",SOURCE,ShaderType.VERTEX,ShaderDefines.EMPTY,false,true,a,compile)){}
            start=System.nanoTime();for(int i=0;i<50;i++)try(var m=ShaderCache.compile(directory,"bench",SOURCE,ShaderType.VERTEX,ShaderDefines.EMPTY,false,true,a,compile)){}long warm=System.nanoTime()-start;
            System.out.println("Shader cache: "+checks+" checks passed; 50 simple shader compiles="+cold/1e6+" ms; 50 cache loads="+warm/1e6+" ms. Not whole-game startup.");
        } finally {try(var paths=Files.walk(directory)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
}

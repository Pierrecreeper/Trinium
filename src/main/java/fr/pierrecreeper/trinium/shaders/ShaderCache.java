package fr.pierrecreeper.trinium.shaders;

import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.frontend.shaders.SPIRVModule;
import com.mojang.renderpearl.util.ShaderCompileException;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.ShadercIncludeResult;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;

/** Content-addressed RenderPearl compilation cache. Each returned module owns its memory.
 * Includes are revalidated on every hit, including transitive includes requested by shaderc.
 * No world, texture, GL program or borrowed native pointer is persisted.
 */
public final class ShaderCache {
    private static final int MAGIC=0x54535031, MAX_SPV=8*1024*1024, MAX_FILE=16*1024*1024;
    private static final long BUDGET=128L*1024*1024;
    private static final Object[] LOCKS=new Object[64];
    private static final CacheDirectoryBudget WRITE_BUDGET=new CacheDirectoryBudget(BUDGET,"*.spv-cache");
    public static final LongAdder hits=new LongAdder(), misses=new LongAdder(), failures=new LongAdder();
    static { Arrays.setAll(LOCKS,i->new Object()); }
    private ShaderCache() { }
    @FunctionalInterface public interface Compiler { SpvModule compile(ShaderSource resolver) throws ShaderCompileException; }

    public static SpvModule compile(Path directory, String label,String source,ShaderType type,ShaderDefines defines,
                                    boolean zeroToOne,boolean drawParameters,ShaderSource resolver,Compiler compiler) throws ShaderCompileException {
        final String key;
        try {
            var bytes=new ByteArrayOutputStream();
            try(var out=new DataOutputStream(bytes)) {
                string(out,"Trinium-SPIRV-v1-Minecraft-26.3-shaderc-3.4.3");
                string(out,label);string(out,source);string(out,type.name());
                out.writeBoolean(zeroToOne);out.writeBoolean(drawParameters);
                out.writeBoolean(com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo().hintsAndWorkarounds().isExplicitDepthRequired());
                var values=new TreeMap<>(defines.values());out.writeInt(values.size());
                for(var e:values.entrySet()){string(out,e.getKey());string(out,e.getValue());}
                var flags=new TreeSet<>(defines.flags());out.writeInt(flags.size());for(String flag:flags)string(out,flag);
            }
            key=HexFormat.of().formatHex(hash(ByteBuffer.wrap(bytes.toByteArray())));
        } catch(IOException|RuntimeException e) { failures.increment();return compiler.compile(resolver); }
        synchronized(LOCKS[(key.hashCode()&Integer.MAX_VALUE)%LOCKS.length]) {
            Path file=directory.resolve(key+".spv-cache");
            try {
                SpvModule cached=read(file,key,type,resolver);
                if(cached!=null){hits.increment();return cached;}
            } catch(IOException|RuntimeException e) { failures.increment(); }
            misses.increment();
            var dependencies=new TreeMap<String,byte[]>();
            boolean[] cacheable={true};
            ShaderSource tracking=new ShaderSource() {
                public String getShader(Identifier id,ShaderType stage){return resolver.getShader(id,stage);}
                public CachedIncludeSource getInclude(Identifier id) {
                    var include=resolver.getInclude(id);
                    try {
                        byte[] digest=includeHash(include);
                        if(digest==null || dependencies.size()>=512)cacheable[0]=false;
                        else {byte[] previous=dependencies.put(id.toString(),digest);if(previous!=null&&!Arrays.equals(previous,digest))cacheable[0]=false;}
                    } catch(RuntimeException e){cacheable[0]=false;}
                    return include;
                }
                public void close(){ /* borrowed resolver; its owner closes it */ }
            };
            SpvModule result=compiler.compile(tracking); // Compilation errors propagate unchanged.
            if(cacheable[0])try {write(file,key,dependencies,result.spv());}catch(IOException|RuntimeException e){failures.increment();}
            return result;
        }
    }
    private static SpvModule read(Path file,String key,ShaderType type,ShaderSource resolver) throws IOException {
        if(!Files.isRegularFile(file))return null;
        long size=Files.size(file);if(size<64||size>MAX_FILE)return null;
        byte[] data=Files.readAllBytes(file);if(data.length<32||data.length>MAX_FILE)return null;
        byte[] checksum=Arrays.copyOfRange(data,data.length-32,data.length);
        if(!MessageDigest.isEqual(checksum,hash(ByteBuffer.wrap(data,0,data.length-32))))return null;
        byte[] spv;
        try(var in=new DataInputStream(new ByteArrayInputStream(data,0,data.length-32))) {
            if(in.readInt()!=MAGIC||!key.equals(string(in,128)))return null;
            int count=in.readInt();if(count<0||count>512)return null;
            for(int i=0;i<count;i++) {
                String id=string(in,1024);byte[] expected=in.readNBytes(32);
                byte[] actual=includeHash(resolver.getInclude(Identifier.parse(id)));
                if(actual==null||!MessageDigest.isEqual(expected,actual))return null;
            }
            int length=in.readInt();if(length<20||length>MAX_SPV||length%4!=0||length!=in.available())return null;
            spv=in.readNBytes(length);if(!validSpirv(spv))return null;
        }
        ByteBuffer owned=MemoryUtil.memAlloc(spv.length);
        try {owned.put(spv).flip();return new SPIRVModule(owned,type);}
        catch(RuntimeException|Error e){MemoryUtil.memFree(owned);throw e;}
    }
    private static void write(Path file,String key,Map<String,byte[]> deps,ByteBuffer source) throws IOException {
        if(source.remaining()<20||source.remaining()>MAX_SPV)return;
        byte[] spv=new byte[source.remaining()];source.duplicate().get(spv);if(!validSpirv(spv))return;
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);string(out,key);out.writeInt(deps.size());
            for(var e:deps.entrySet()){string(out,e.getKey());out.write(e.getValue());}
            out.writeInt(spv.length);out.write(spv);
        }
        byte[] payload=bytes.toByteArray();if(payload.length+32>MAX_FILE)return;
        WRITE_BUDGET.write(file,payload.length+32L,()-> {
            Path tmp=Files.createTempFile(file.getParent(),"shader-",".tmp");
            try {
                try(var out=Files.newOutputStream(tmp)){out.write(payload);out.write(hash(ByteBuffer.wrap(payload)));}
                try{Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
                catch(AtomicMoveNotSupportedException e){Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING);}
            } finally {Files.deleteIfExists(tmp);}
        });
    }
    private static byte[] includeHash(ShaderSource.CachedIncludeSource include) {
        if(include==null||include.includeResultPtr()==0)return null;
        var nativeInclude=ShadercIncludeResult.create(include.includeResultPtr());
        long contentLength=nativeInclude.content_length(),nameLength=nativeInclude.source_name_length();
        if(contentLength<0||contentLength>MAX_FILE||nameLength<0||nameLength>4096)return null;
        // Minecraft allocates these strings WITHOUT a NUL terminator. LWJGL's convenience
        // getters scan for NUL, so use shaderc's explicit lengths to avoid reading past them.
        long address=include.includeResultPtr();
        long name=MemoryUtil.memGetAddress(address+ShadercIncludeResult.SOURCE_NAME);
        long content=MemoryUtil.memGetAddress(address+ShadercIncludeResult.CONTENT);
        if(name==0||content==0)return null;
        var digest=digest();
        digest.update(MemoryUtil.memByteBuffer(name,(int)nameLength));digest.update((byte)0);
        digest.update(MemoryUtil.memByteBuffer(content,(int)contentLength));return digest.digest();
    }
    private static boolean validSpirv(byte[] data) {
        if(data.length<20||data.length%4!=0)return false;
        var b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if(b.getInt(0)!=0x07230203||b.getInt(16)!=0)return false;
        int offset=20;
        while(offset<data.length){int words=b.getInt(offset)>>>16;if(words==0||words>(data.length-offset)/4)return false;offset+=words*4;}
        return offset==data.length;
    }
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
    private static byte[] hash(ByteBuffer bytes){var digest=digest();digest.update(bytes);return digest.digest();}
    private static void string(DataOutputStream out,String value)throws IOException{byte[] bytes=value.getBytes(java.nio.charset.StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);}
    private static String string(DataInputStream in,int max)throws IOException{int size=in.readInt();if(size<0||size>max||size>in.available())throw new IOException("Invalid shader cache string");return new String(in.readNBytes(size),java.nio.charset.StandardCharsets.UTF_8);}
}

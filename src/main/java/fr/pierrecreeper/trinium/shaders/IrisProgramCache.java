package fr.pierrecreeper.trinium.shaders;

import fr.pierrecreeper.trinium.Trinium;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;
import java.lang.reflect.*;
import com.google.common.collect.ImmutableSet;

/** Optional Iris 1.11.7 OpenGL program binaries, keyed after Iris source transformation.
 * Drivers validate binaries again; a rejected entry falls back to Iris compilation.
 * Programs are newly allocated per load: never reuse live GL handles or uniform state.
 */
public final class IrisProgramCache {
    private static final int MAGIC=0x54495031,MAX=32*1024*1024;
    public static final LongAdder hits=new LongAdder(),misses=new LongAdder(),stores=new LongAdder(),failures=new LongAdder();
    private static Constructor<?> constructor;
    private static Field programField;
    private IrisProgramCache(){}
    public static boolean enabled(){return Trinium.active()&&!Boolean.getBoolean("trinium.disableShaderCache")&&!Boolean.getBoolean("trinium.disableIrisCache")&&GL.getCapabilities().OpenGL41&&GL11C.glGetInteger(GL41C.GL_NUM_PROGRAM_BINARY_FORMATS)>0;}
    public static int createProgram(){int program=GL20C.glCreateProgram();if(enabled())GL41C.glProgramParameteri(program,GL41C.GL_PROGRAM_BINARY_RETRIEVABLE_HINT,GL11C.GL_TRUE);return program;}
    public static String key(String label,String vertex,String geometry,String fragment) {
        if(!enabled())return null;
        try {
            var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)) {
                for(String s:new String[]{"Trinium-Iris-1.11.7-mc26.3-v1",System.getProperty("os.name"),System.getProperty("os.arch"),GL11C.glGetString(GL11C.GL_VENDOR),GL11C.glGetString(GL11C.GL_RENDERER),GL11C.glGetString(GL11C.GL_VERSION),GL11C.glGetString(GL20C.GL_SHADING_LANGUAGE_VERSION),label,vertex,geometry,fragment}) {
                    if(s==null){out.writeInt(-1);continue;}byte[] b=s.getBytes(java.nio.charset.StandardCharsets.UTF_8);out.writeInt(b.length);out.write(b);
                }
                out.writeInt(GL11C.glGetInteger(GL30C.GL_CONTEXT_FLAGS));out.writeInt(GL11C.glGetInteger(GL32C.GL_CONTEXT_PROFILE_MASK));
            }
            return HexFormat.of().formatHex(hash(bytes.toByteArray()));
        }catch(IOException e){return null;}
    }
    private static Path file(String key){return Trinium.directory().resolve("iris-program-cache-v1").resolve(key+".glbin");}
    public static Object load(String key,String label,ImmutableSet<Integer> reserved) {
        if(key==null)return null;
        int program=0;ByteBuffer binary=null;
        try {
            Path path=file(key);if(!Files.isRegularFile(path)||Files.size(path)>MAX){misses.increment();return null;}
            byte[] data=Files.readAllBytes(path);
            if(data.length<44||data.length>MAX||!MessageDigest.isEqual(Arrays.copyOfRange(data,data.length-32,data.length),hash(Arrays.copyOf(data,data.length-32)))) {misses.increment();return null;}
            int format,length;
            try(var in=new DataInputStream(new ByteArrayInputStream(data,0,data.length-32))) {
                if(in.readInt()!=MAGIC){misses.increment();return null;}
                format=in.readInt();length=in.readInt();
                if(length<=0||length!=in.available()){misses.increment();return null;}
                int count=GL11C.glGetInteger(GL41C.GL_NUM_PROGRAM_BINARY_FORMATS);if(count<1||count>256)return null;
                int[] formats=new int[count];GL11C.glGetIntegerv(GL41C.GL_PROGRAM_BINARY_FORMATS,formats);
                if(Arrays.stream(formats).noneMatch(f->f==format)){misses.increment();return null;}
                binary=MemoryUtil.memAlloc(length);binary.put(data,12,length).flip();
            }
            program=GL20C.glCreateProgram();GL41C.glProgramBinary(program,format,binary);
            if(GL20C.glGetProgrami(program,GL20C.GL_LINK_STATUS)!=GL11C.GL_TRUE){misses.increment();return null;}
            if(constructor==null) {
                Class<?> type=Class.forName("net.irisshaders.iris.gl.program.ProgramBuilder");
                constructor=type.getDeclaredConstructor(String.class,int.class,ImmutableSet.class);constructor.setAccessible(true);
            }
            Object result=constructor.newInstance(label,program,reserved);program=0;hits.increment();return result;
        } catch(IOException|ReflectiveOperationException|RuntimeException e){failures.increment();return null;}
        finally {if(binary!=null)MemoryUtil.memFree(binary);if(program!=0)GL20C.glDeleteProgram(program);}
    }
    public static void store(String key,Object builder) {
        if(key==null||builder==null)return;
        ByteBuffer binary=null;
        try {
            if(programField==null){programField=builder.getClass().getDeclaredField("program");programField.setAccessible(true);}
            int program=programField.getInt(builder);
            int size=GL20C.glGetProgrami(program,GL41C.GL_PROGRAM_BINARY_LENGTH);if(size<=0||size>MAX-44)return;
            binary=MemoryUtil.memAlloc(size);int[] length={0},format={0};GL41C.glGetProgramBinary(program,length,format,binary);
            if(length[0]<=0||length[0]>size)return;
            var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){out.writeInt(MAGIC);out.writeInt(format[0]);out.writeInt(length[0]);byte[] data=new byte[length[0]];binary.get(data);out.write(data);}
            byte[] payload=bytes.toByteArray();Path path=file(key);Files.createDirectories(path.getParent());
            long used=0;try(var entries=Files.newDirectoryStream(path.getParent(),"*.glbin")){for(Path entry:entries)used+=Files.size(entry);}
            if(used+payload.length+32>256L*1024*1024)return;
            Path tmp=Files.createTempFile(path.getParent(),"program-",".tmp");
            try {
                try(var out=Files.newOutputStream(tmp)){out.write(payload);out.write(hash(payload));}
                try{Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING);}
                stores.increment();
            }finally{Files.deleteIfExists(tmp);}
        }catch(IOException|ReflectiveOperationException|RuntimeException e){failures.increment();}
        finally{if(binary!=null)MemoryUtil.memFree(binary);}
    }
    private static byte[] hash(byte[] bytes){try{return MessageDigest.getInstance("SHA-256").digest(bytes);}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
}

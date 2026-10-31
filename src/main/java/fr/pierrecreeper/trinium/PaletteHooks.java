package fr.pierrecreeper.trinium;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Optional bulk-unpack hook. Unknown layouts or missing targets fall back to the game. */
public final class PaletteHooks {
    static final LongAdder intercepted=new LongAdder();
    static final LongAdder replaced=new LongAdder();
    static volatile boolean failed;
    static volatile String failure="";
    private static final ClassValue<Binding> BINDINGS=new ClassValue<>() {
        @Override protected Binding computeValue(Class<?> c) {
            try { return new Binding(c); }
            catch(ReflectiveOperationException e) { throw new IllegalStateException("Incompatible palette storage",e); }
        }
    };
    private PaletteHooks() { }
    @SuppressWarnings("removal")
    public static void unpack(Object storage,int[] out,Object callback) {
        if(!Trinium.active() || failed) return;
        try {
            intercepted.increment();
            Binding b=BINDINGS.get(storage.getClass());
            int bits=(int)b.bits.invokeExact(storage);
            int size=(int)b.size.invokeExact(storage);
            long[] words=(long[])b.raw.invokeExact(storage);
            if(!PaletteDecoder.unpack(words,bits,size,out)) return;
            // Check actual storage semantics once for each encountered bit width.
            if(size>0 && (b.validated.get() & (1L<<bits))==0) {
                int samples=Math.min(16,size);
                for(int i=0;i<samples;i++) {
                    int index=samples==1 ? 0 : (int)((long)i*(size-1)/(samples-1));
                    int expected=(int)b.get.invokeExact(storage,index);
                    if(out[index]!=expected) throw new IllegalStateException("Palette layout differs from the game (bits="+bits+")");
                }
                b.validated.getAndUpdate(mask->mask | (1L<<bits));
            }
            CallbackBridge.cancel(callback);
            replaced.increment();
        } catch(Throwable e) {
            if(e instanceof VirtualMachineError v) throw v;
            if(e instanceof ThreadDeath t) throw t;
            failed=true; failure=Trinium.describe(e);
            Trinium.warn("Palette: vanilla fallback, " + failure);
            // No cancellation: vanilla rewrites the whole output after a failed check.
        }
    }
    private static final class Binding {
        final MethodHandle bits,size,raw,get;
        final AtomicLong validated=new AtomicLong();
        Binding(Class<?> c) throws ReflectiveOperationException {
            bits=handle(c,"getBits",MethodType.methodType(int.class,Object.class));
            size=handle(c,"getSize",MethodType.methodType(int.class,Object.class));
            raw=handle(c,"getRaw",MethodType.methodType(long[].class,Object.class));
            get=handle(c,"get",MethodType.methodType(int.class,Object.class,int.class),int.class);
        }
        private static MethodHandle handle(Class<?> c,String name,MethodType adapted,Class<?>... args) throws ReflectiveOperationException {
            Method method=c.getMethod(name,args); method.setAccessible(true);
            return MethodHandles.lookup().unreflect(method).asType(adapted);
        }
    }
}

package fr.pierrecreeper.trinium.graphics;

import fr.pierrecreeper.trinium.NativeBridge;
import fr.pierrecreeper.trinium.Trinium;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.LongAdder;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

/**
 * Synchronous OpenGL command batches, normally scoped to one RenderPearl draw.
 * Java's GL state caches still observe every update. A batch is flushed before control
 * returns to Minecraft: no pointer, GL object or command outlives its caller/context.
 */
public final class RustGlDriver {
    private static final int FUNCTION_BYTES = (GlCommands.FUNCTIONS.length * 8 + 63) & ~63;
    private static final int MAX_COMMANDS = 128;
    private static final List<State> STATES = new CopyOnWriteArrayList<>();
    private static final Counter BATCHES = new Counter("batches"), COMMANDS = new Counter("commands"), DRAWS = new Counter("draws");
    // Preserve the public field descriptors used by already compiled diagnostic probes.
    public static final LongAdder batches = BATCHES, commands = COMMANDS, draws = DRAWS;
    private static final boolean DISABLED = Boolean.getBoolean("trinium.disableGraphics");
    private static final ThreadLocal<State> LOCAL = ThreadLocal.withInitial(State::new);
    private static volatile boolean legacyTransport = Boolean.getBoolean("trinium.legacyGlTransport");
    private static volatile String status = "Rust OpenGL awaiting context";
    private static native int execute0(ByteBuffer functions, ByteBuffer commands, int count);
    private static native int executePacked0(ByteBuffer storage, int count);
    private RustGlDriver() { }

    /**
     * Each State has exactly one writer, its render thread. Opaque accesses publish atomic
     * diagnostic counts without a compare-and-swap on every draw. As with LongAdder.sum,
     * reading several counters during rendering is an approximate concurrent snapshot.
     */
    private static final class Counter extends LongAdder {
        private static final long serialVersionUID = 1L;
        private final VarHandle field;
        private Counter(String name) {
            try { field = MethodHandles.lookup().findVarHandle(State.class, name, long.class); }
            catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
        }
        private void add(State state, long value) {
            field.setOpaque(state, (long)field.getOpaque(state) + value);
        }
        @Override public long sum() {
            long result = super.sum();
            for (State state : STATES) result += (long)field.getOpaque(state);
            return result;
        }
    }

    static final class State {
        // One owned allocation = one JNI address/capacity lookup pair, not two pairs.
        final ByteBuffer storage = ByteBuffer.allocateDirect(FUNCTION_BYTES + MAX_COMMANDS * 64).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer legacyFunctions, legacyPacket;
        GLCapabilities context;
        int depth, coalescedDepth, count, drawCount, offset;
        long batches, commands, draws;
        boolean enabled, borrowedPointers;
        State() { STATES.add(this); }
    }
    public static void begin() {
        State s = LOCAL.get();
        if (s.depth++ != 0) { if(s.coalescedDepth==0)flush(s); return; }
        s.enabled = !DISABLED && Trinium.active() && NativeBridge.available()
            && Trinium.mode() != Trinium.Mode.JAVA;
        if (!s.enabled) return;
        GLCapabilities context = GL.getCapabilities();
        if (s.context != context) {
            // LWJGL owns the context and discovers its APIENTRY pointers. Never dlopen a
            // second GL implementation, and never use these pointers on another thread.
            try {
                for (int i=0; i<GlCommands.FUNCTIONS.length; i++)
                    s.storage.putLong(i*8, GLCapabilities.class.getField(GlCommands.FUNCTIONS[i]).getLong(context));
            } catch (ReflectiveOperationException e) { throw new IllegalStateException("Incompatible LWJGL contract",e); }
            s.context = context;
            status = "Rust OpenGL commands active";
        }
    }
    public static void end() {
        State s = LOCAL.get();
        if (s.depth <= 0) throw new IllegalStateException("Graphics scope is not open");
        try { if(s.coalescedDepth==0 || s.borrowedPointers)flush(s); }
        finally { if (--s.depth == 0) s.enabled = false; }
    }
    /** Only for guarded sequences of vanilla draw calls with no intervening raw GL. */
    public static void beginCoalesced() {begin();LOCAL.get().coalescedDepth++;}
    public static void endCoalesced() {
        State s=LOCAL.get();
        if(s.coalescedDepth<=0)throw new IllegalStateException("Coalesced graphics scope is not open");
        s.coalescedDepth--;end();
    }
    /** Reserve one record. Generated wrappers write only the arguments used by its opcode. */
    static State command(int op) {
        State s = LOCAL.get();
        if (!s.enabled || s.depth == 0) return null;
        if (s.storage.getLong(op * 8) == 0) { flush(s); return null; }
        if (s.count == MAX_COMMANDS) flush(s);
        s.offset = FUNCTION_BYTES + s.count++ * 64;
        // These two opcodes borrow CPU arrays. Even inside a coalesced sequence,
        // the ordinary inner draw scope must flush before its arrays can expire.
        if(op==29 || op==30)s.borrowedPointers=true;
        if (op >= 25) s.drawCount++;
        return s;
    }
    private static int executeLegacy(State s, int count) {
        if (s.legacyFunctions == null) {
            s.legacyFunctions = s.storage.slice(0, GlCommands.FUNCTIONS.length * 8);
            s.legacyPacket = s.storage.slice(FUNCTION_BYTES, MAX_COMMANDS * 64);
        }
        return execute0(s.legacyFunctions, s.legacyPacket, count);
    }
    private static void flush(State s) {
        if (s.count == 0) return;
        int count=s.count, drawCount=s.drawCount;
        s.count=0; s.drawCount=0;s.borrowedPointers=false;
        int result;
        if (legacyTransport) result = executeLegacy(s, count);
        else {
            try { result = executePacked0(s.storage, count); }
            catch (UnsatisfiedLinkError oldAbi3Library) {
                // The separately named entry point is an optional ABI3 extension. Old
                // user-supplied libraries retain their original, safe two-buffer contract.
                legacyTransport = true;
                result = executeLegacy(s, count);
            }
        }
        // Native prevalidation happens before any GL call. An internal contract failure
        // is fatal to this frame; silently drawing half a batch is never acceptable.
        if (result != 0) throw new IllegalStateException("Rust graphics packet rejected: " + result);
        BATCHES.add(s,1); COMMANDS.add(s,count); DRAWS.add(s,drawCount);
    }
    public static boolean rendererActive() {
        return !DISABLED && Trinium.active() && NativeBridge.available() && Trinium.mode() != Trinium.Mode.JAVA
            && !fr.pierrecreeper.trinium.compat.RenderCompatibility.SODIUM
            && !fr.pierrecreeper.trinium.compat.RenderCompatibility.IRIS && draws.sum() > 0;
    }
    public static String status() { if(fr.pierrecreeper.trinium.compat.RenderCompatibility.SODIUM || fr.pierrecreeper.trinium.compat.RenderCompatibility.IRIS)return "Rendering delegated to Sodium/Iris; Rust GPU commands suspended"; return DISABLED ? "Rust OpenGL explicitly disabled" : status; }
}

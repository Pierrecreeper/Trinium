package fr.pierrecreeper.trinium;

import java.util.BitSet;

/** One replacement at HEAD. No RETURN hook, no periodic double calculation, no manual gate. */
public final class TriniumHooks {
    private static final ClassValue<GameBinding> BINDINGS = new ClassValue<>() {
        @Override protected GameBinding computeValue(Class<?> type) {
            try { return new GameBinding(type); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException("Incompatible Minecraft adapter",e); }
        }
    };
    private TriniumHooks() { }
    @SuppressWarnings("removal")
    public static void before(Object graph,Object callback) {
        if (!Trinium.active()) return;
        try {
            Trinium.intercepted.increment();
            GameBinding binding = BINDINGS.get(graph.getClass());
            BitSet opaque = binding.opaque(graph);
            int count = binding.opaqueCount(graph,opaque);
            // Vanilla's constant-time empty/solid shortcut is cheaper than a JNI call.
            if (count<256 || count==4096) { Trinium.trivialSkipped.increment(); return; }
            long predicted;
            boolean nativeMode = NativeBridge.available() && (Trinium.mode()==Trinium.Mode.NATIVE || Trinium.mode()==Trinium.Mode.AUTO);
            if (nativeMode) {
                try { predicted = NativeBridge.resolve(opaque.toLongArray()); Trinium.nativeCalls.increment(); }
                catch (RuntimeException | LinkageError e) {
                    NativeBridge.disable(e);
                    predicted = VisibilitySolver.resolve(opaque,count); Trinium.javaCalls.increment();
                }
            } else {
                predicted = VisibilitySolver.resolve(opaque,count); Trinium.javaCalls.increment();
            }
            Object result=binding.newVisibility(predicted);
            // Callback lookup is cached per class, with no reflective varargs allocation per section.
            CallbackBridge.set(callback,result);
            Trinium.replaced.increment();
        } catch (Throwable e) {
            if(e instanceof VirtualMachineError v) throw v;
            if(e instanceof ThreadDeath t) throw t;
            Trinium.fail(e);
        }
    }
}

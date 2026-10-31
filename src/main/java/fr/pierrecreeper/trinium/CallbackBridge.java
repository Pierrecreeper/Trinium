package fr.pierrecreeper.trinium;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

final class CallbackBridge {
    private CallbackBridge() { }
    private static final ClassValue<MethodHandle> SET = new ClassValue<>() {
        @Override protected MethodHandle computeValue(Class<?> type) {
            try {
                Method m=type.getMethod("setReturnValue",Object.class); m.setAccessible(true);
                return MethodHandles.lookup().unreflect(m).asType(MethodType.methodType(void.class,Object.class,Object.class));
            } catch (ReflectiveOperationException e) { throw new IllegalStateException("Mixin callback",e); }
        }
    };
    private static final ClassValue<MethodHandle> CANCEL = new ClassValue<>() {
        @Override protected MethodHandle computeValue(Class<?> type) {
            try {
                Method m=type.getMethod("cancel"); m.setAccessible(true);
                return MethodHandles.lookup().unreflect(m).asType(MethodType.methodType(void.class,Object.class));
            } catch (ReflectiveOperationException e) { throw new IllegalStateException("Mixin callback",e); }
        }
    };
    static void set(Object callback,Object value) throws Throwable { SET.get(callback.getClass()).invokeExact(callback,value); }
    static void cancel(Object callback) throws Throwable { CANCEL.get(callback.getClass()).invokeExact(callback); }
}

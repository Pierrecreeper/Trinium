package fr.pierrecreeper.trinium;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.BitSet;

/**
 * Small runtime adapter: no copied Minecraft classes, no fabricated Fabric API.
 * Validates storage layout against the actual loaded classes before permitting use.
 * Reflection deliberately isolates this alpha from compile-time Minecraft internals.
 */
final class GameBinding {
    private static final String[] FACES = {"DOWN","UP","NORTH","SOUTH","WEST","EAST"};
    private final Field opaqueField;
    private final Field emptyField;
    private final Constructor<?> constructor;
    private final Field dataField;
    private final Method canSee;
    private final Object[] faces = new Object[6];
    private final long[] pairBits = new long[36];
    private final int[] readIndices = new int[36];

    GameBinding(Class<?> graphClass) throws ReflectiveOperationException {
        opaqueField = graphClass.getDeclaredField("bitSet");
        opaqueField.setAccessible(true);
        Field candidate;
        try { candidate=graphClass.getDeclaredField("empty"); candidate.setAccessible(true);
            if(candidate.getType()!=int.class) candidate=null;
        } catch(NoSuchFieldException e) { candidate=null; }
        emptyField=candidate;
        if (opaqueField.getType() != BitSet.class) throw new IllegalStateException("Unsupported opacity storage");
        Method index = graphClass.getDeclaredMethod("getIndex", int.class, int.class, int.class);
        index.setAccessible(true);
        for (int x : new int[]{0,1,7,15}) for (int y : new int[]{0,1,7,15}) for (int z : new int[]{0,1,7,15}) {
            if ((int) index.invoke(null,x,y,z) != (x | z<<4 | y<<8))
                throw new IllegalStateException("Unsupported section index layout");
        }
        Class<?> visibilityClass = graphClass.getDeclaredMethod("resolve").getReturnType();
        constructor = visibilityClass.getDeclaredConstructor(); constructor.setAccessible(true);
        dataField = visibilityClass.getDeclaredField("data"); dataField.setAccessible(true);
        if (dataField.getType() != BitSet.class) throw new IllegalStateException("Unsupported visibility storage");
        Method found = null;
        for (Method m : visibilityClass.getMethods())
            if (m.getName().equals("visibilityBetween") && m.getParameterCount() == 2
                    && m.getReturnType() == boolean.class) { found=m; break; }
        if (found == null) throw new NoSuchMethodException("visibilityBetween");
        canSee = found; canSee.setAccessible(true);
        Class<?> direction = canSee.getParameterTypes()[0];
        if (!direction.isEnum() || canSee.getParameterTypes()[1] != direction)
            throw new IllegalStateException("Unexpected direction type");
        for (Object constant : direction.getEnumConstants()) {
            String name = ((Enum<?>) constant).name();
            for (int i=0;i<6;i++) if (name.equals(FACES[i])) faces[i]=constant;
        }
        for (Object face : faces) if (face == null) throw new IllegalStateException("Missing direction");
        Method set = visibilityClass.getDeclaredMethod("set",direction,direction,boolean.class);
        set.setAccessible(true);
        for (int a=0;a<6;a++) for (int b=0;b<6;b++) {
            Object sample = constructor.newInstance();
            set.invoke(sample,faces[a],faces[b],true);
            BitSet data = (BitSet) dataField.get(sample);
            if (data.length()>64) throw new IllegalStateException("Unsupported visibility width");
            long[] words=data.toLongArray();
            pairBits[a*6+b] = words.length==0 ? 0 : words[0];
        }
        // Discover each directional read bit ONCE, not 36 reflective invocations per section.
        // Reject layouts whose individual read bit cannot be identified by the probe.
        Object probe = constructor.newInstance();
        BitSet probeData = (BitSet) dataField.get(probe);
        for (int a=0;a<6;a++) for (int b=0;b<6;b++) {
            int foundBit=-1;
            for (int bit=0;bit<36;bit++) {
                probeData.clear(); probeData.set(bit);
                if ((boolean) canSee.invoke(probe,faces[a],faces[b])) {
                    if (foundBit!=-1) throw new IllegalStateException("Unsupported multi-bit visibility read");
                    foundBit=bit;
                }
            }
            if (foundBit<0) throw new IllegalStateException("Unsupported visibility read layout");
            readIndices[a*6+b]=foundBit;
        }
        // Check that our translation preserves every individual symmetric pair.
        for (int a=0;a<6;a++) for (int b=0;b<6;b++) {
            long mask=(1L<<(a*6+b)) | (1L<<(b*6+a));
            if (read(newVisibility(mask)) != mask)
                throw new IllegalStateException("Unsupported visibility bit layout");
        }
    }
    BitSet opaque(Object graph) throws IllegalAccessException { return (BitSet) opaqueField.get(graph); }
    int opaqueCount(Object graph,BitSet bits) throws IllegalAccessException {
        int count=emptyField==null ? bits.cardinality() : 4096-emptyField.getInt(graph);
        if(count<0 || count>4096) throw new IllegalStateException("Invalid section opacity count");
        return count;
    }
    Object newVisibility(long mask) throws ReflectiveOperationException {
        Object out = constructor.newInstance();
        long translated=0, remaining=mask;
        while (remaining!=0) {
            int bit=Long.numberOfTrailingZeros(remaining);
            translated |= pairBits[bit]; remaining &= remaining-1;
        }
        BitSet data=(BitSet) dataField.get(out);
        data.clear();
        // Write consecutive one-bits in ranges instead of repeating 36 BitSet.set calls.
        while (translated!=0) {
            int first=Long.numberOfTrailingZeros(translated);
            int len=Long.numberOfTrailingZeros(~(translated >>> first));
            int end=Math.min(64,first+len);
            data.set(first,end);
            translated &= end==64 ? 0L : -1L << end;
        }
        return out;
    }
    long read(Object visibility) throws ReflectiveOperationException {
        BitSet data=(BitSet) dataField.get(visibility);
        long mask=0;
        for (int pair=0;pair<36;pair++) if (data.get(readIndices[pair])) mask |= 1L<<pair;
        return mask;
    }
}

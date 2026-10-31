package fr.pierrecreeper.trinium;

/**
 * Bulk unpacking of Minecraft's padded (not straddling) packed integer storage.
 * This is NOT a palette-state cache: no world, block state or chunk is retained.
 * No allocation in unpack(), and the caller's output tail is left untouched.
 */
public final class PaletteDecoder {
    private PaletteDecoder() { }
    public static boolean unpack(long[] data,int bits,int size,int[] out) {
        if (data==null || out==null || bits<1 || bits>32 || size<0 || out.length<size) return false;
        int perLong=64/bits;
        int required=size==0 ? 0 : (size-1)/perLong+1;
        if(data.length<required) return false;
        long mask=(1L<<bits)-1;
        int pos=0;
        if(bits==4) {
            // Sixteen packed palette entries per word: no division for each entry.
            for(int w=0;w<required;w++) {
                long value=data[w];
                int end=Math.min(size,pos+16);
                if(end-pos==16) {
                    out[pos]=(int)value&15; out[pos+1]=(int)(value>>>4)&15;
                    out[pos+2]=(int)(value>>>8)&15; out[pos+3]=(int)(value>>>12)&15;
                    out[pos+4]=(int)(value>>>16)&15; out[pos+5]=(int)(value>>>20)&15;
                    out[pos+6]=(int)(value>>>24)&15; out[pos+7]=(int)(value>>>28)&15;
                    out[pos+8]=(int)(value>>>32)&15; out[pos+9]=(int)(value>>>36)&15;
                    out[pos+10]=(int)(value>>>40)&15; out[pos+11]=(int)(value>>>44)&15;
                    out[pos+12]=(int)(value>>>48)&15; out[pos+13]=(int)(value>>>52)&15;
                    out[pos+14]=(int)(value>>>56)&15; out[pos+15]=(int)(value>>>60)&15;
                    pos=end;
                } else while(pos<end) { out[pos++]=(int)value&15; value>>>=4; }
            }
        } else {
            for(int w=0;w<required;w++) {
                long value=data[w];
                int end=Math.min(size,pos+perLong);
                while(pos+4<=end) {
                    out[pos++]=(int)(value&mask); value>>>=bits;
                    out[pos++]=(int)(value&mask); value>>>=bits;
                    out[pos++]=(int)(value&mask); value>>>=bits;
                    out[pos++]=(int)(value&mask); value>>>=bits;
                }
                while(pos<end) { out[pos++]=(int)(value&mask); value>>>=bits; }
            }
        }
        return true;
    }
}

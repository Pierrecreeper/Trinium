package com.mojang.renderpearl.backend.opengl;

import com.mojang.renderpearl.api.pipeline.IndexType;
import fr.pierrecreeper.trinium.graphics.RustGlDriver;
import java.nio.IntBuffer;
import org.lwjgl.PointerBuffer;

/** Original Trinium interop code, in this package to access RenderPearl's internal encoder.
 * Primitive arguments avoid an Object[] and Integer boxing on every draw.
 */
public final class TriniumGlDrawBridge {
    private TriniumGlDrawBridge() { }
    public static void draw(Object encoder,Object pass,int base,int first,int count,IndexType type,int instances,int firstInstance) {
        RustGlDriver.begin();
        try { ((GlCommandEncoder)encoder).executeDraw((GlRenderPass)pass,base,first,count,type,instances,firstInstance); }
        finally { RustGlDriver.end(); }
    }
    public static void multi(Object encoder,Object pass,IndexType type,PointerBuffer offsets,IntBuffer counts,IntBuffer bases,int count) {
        RustGlDriver.begin();
        try { ((GlCommandEncoder)encoder).executeDraws((GlRenderPass)pass,type,offsets,counts,bases,count); }
        finally {
            try { RustGlDriver.end(); }
            finally {
                // Native packets borrow these addresses until the inner scope flushes.
                java.lang.ref.Reference.reachabilityFence(offsets);
                java.lang.ref.Reference.reachabilityFence(counts);
                java.lang.ref.Reference.reachabilityFence(bases);
            }
        }
    }
    public static void indirect(Object encoder,Object pass,IndexType type,Object buffer,long offset,int count) {
        RustGlDriver.begin();
        try { ((GlCommandEncoder)encoder).executeDrawIndirect((GlRenderPass)pass,type,(GlBuffer)buffer,offset,count); }
        finally { RustGlDriver.end(); }
    }
}

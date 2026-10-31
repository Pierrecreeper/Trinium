// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.test;

import com.mojang.renderpearl.backend.opengl.GlTexture;
import fr.pierrecreeper.trinium.graphics.RustGlDriver;
import java.awt.image.BufferedImage;
import java.nio.*;
import java.nio.file.*;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import java.util.ArrayList;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import org.joml.Vector4f;
import net.minecraft.network.chat.Component;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import static org.lwjgl.opengl.GL33C.*;

/** TEST ONLY. Three renders of one extracted game frame, no tick/texture animation between them. */
@Mixin(value=LevelRenderer.class,remap=false)
public abstract class DirectParityProbeMixin {
    @Shadow @Final private LevelRenderState levelRenderState;
    @Unique private long trinium$start;
    @Unique private boolean trinium$paused,trinium$done,trinium$guard;
    @Unique private Screen trinium$previous;

    @Inject(method="render",at=@At("HEAD"),cancellable=true)
    private void trinium$triple(GraphicsResourceAllocator allocator,boolean outline,CameraRenderState camera,GpuBufferSlice fog,Vector4f fogColor,boolean sky,boolean post,CallbackInfo ci) {
        var mc=Minecraft.getInstance();
        if (mc.level==null || trinium$done || trinium$guard) return;
        if (mc.getSingleplayerServer()==null) throw new AssertionError("Direct parity requires a local copied world");
        long now=System.nanoTime();
        if (trinium$start==0) trinium$start=now;
        double seconds=(now-trinium$start)/1e9;
        if (seconds>35 && !trinium$paused) {
            trinium$paused=true;trinium$previous=mc.gui.screen();mc.gui.setScreen(new TriniumStillScreen());
        }
        if (seconds<45) return;
        trinium$done=true;trinium$guard=true;
        boolean before=trinium$enabled();
        var entities=new ArrayList<>(levelRenderState.entityRenderStates);
        var blockEntities=new ArrayList<>(levelRenderState.blockEntityRenderStates);
        var breaking=new ArrayList<>(levelRenderState.blockBreakingRenderStates);
        try {
            trinium$enable(false);long nativeStartA=RustGlDriver.batches.sum(),drawStartA=RustGlDriver.draws.sum();
            ((LevelRenderer)(Object)this).render(allocator,outline,camera,fog,fogColor,sky,post);
            long nativeA=RustGlDriver.batches.sum()-nativeStartA,drawA=RustGlDriver.draws.sum()-drawStartA;
            var a=trinium$capture(mc,"direct-naive-a");
            long batches=trinium$counter(true),draws=trinium$counter(false);
            long nativeStartB=RustGlDriver.batches.sum(),drawStartB=RustGlDriver.draws.sum();
            levelRenderState.entityRenderStates.addAll(entities);levelRenderState.blockEntityRenderStates.addAll(blockEntities);levelRenderState.blockBreakingRenderStates.addAll(breaking);
            trinium$enable(true);((LevelRenderer)(Object)this).render(allocator,outline,camera,fog,fogColor,sky,post);
            var b=trinium$capture(mc,"direct-batched-b");
            batches=trinium$counter(true)-batches;draws=trinium$counter(false)-draws;
            long nativeB=RustGlDriver.batches.sum()-nativeStartB,drawB=RustGlDriver.draws.sum()-drawStartB;
            levelRenderState.entityRenderStates.addAll(entities);levelRenderState.blockEntityRenderStates.addAll(blockEntities);levelRenderState.blockBreakingRenderStates.addAll(breaking);
            trinium$enable(false);((LevelRenderer)(Object)this).render(allocator,outline,camera,fog,fogColor,sky,post);
            var c=trinium$capture(mc,"direct-naive-c");
            long noise=trinium$colors(a.color(),c.color()),ab=trinium$colors(a.color(),b.color()),bc=trinium$colors(b.color(),c.color());
            long depthNoise=trinium$depths(a.depth(),c.depth()),depthAB=trinium$depths(a.depth(),b.depth()),depthBC=trinium$depths(b.depth(),c.depth());
            boolean pass=batches>0 && draws>100 && noise==0 && ab==0 && bc==0
                && depthNoise==0 && depthAB==0 && depthBC==0;
            String json="{\"nativeBatchesA\":"+nativeA+",\"nativeBatchesB\":"+nativeB+",\"nativeDrawsA\":"+drawA+",\"nativeDrawsB\":"+drawB+",\"sameExtractedFrame\":true,\"batches\":"+batches+",\"draws\":"+draws
                +",\"pixels\":"+a.depth().length+",\"naiveNoisePixels\":"+noise+",\"changedABPixels\":"+ab+",\"changedBCPixels\":"+bc
                +",\"naiveDepthNoisePixels\":"+depthNoise+",\"depthABPixels\":"+depthAB+",\"depthBCPixels\":"+depthBC+",\"passed\":"+pass+"}\n";
            Files.writeString(mc.gameDirectory.toPath().resolve("direct-parity.json"),json);
            System.out.println("TRINIUM_DIRECT_PARITY "+json);
            if (!pass) throw new AssertionError("Direct terrain pixel parity failed or baseline was unstable");
        } catch (java.io.IOException error) {throw new RuntimeException(error);}
        finally {trinium$enable(before);trinium$guard=false;mc.gui.setScreen(trinium$previous);}
        ci.cancel();
    }

    @Unique private static Class<?> trinium$subject() {
        try {return Class.forName(Boolean.getBoolean("trinium.experimentalTerrainSubmit")
            ?"fr.pierrecreeper.trinium.graphics.ScopedTerrainSubmit":"fr.pierrecreeper.trinium.graphics.DirectTerrainBatch");}
        catch(ReflectiveOperationException error){throw new RuntimeException(error);}
    }
    @Unique private static boolean trinium$enabled() {
        try {return trinium$subject().getField("renderingEnabled").getBoolean(null);}
        catch(ReflectiveOperationException error){throw new RuntimeException(error);}
    }
    @Unique private static void trinium$enable(boolean value) {
        try {trinium$subject().getField("renderingEnabled").setBoolean(null,value);}
        catch(ReflectiveOperationException error){throw new RuntimeException(error);}
    }
    @Unique private static long trinium$counter(boolean passes) {
        String name=passes?(Boolean.getBoolean("trinium.experimentalTerrainSubmit")?"passes":"batches"):"draws";
        try {return trinium$subject().getField(name).getLong(null);}
        catch(ReflectiveOperationException error){throw new RuntimeException(error);}
    }

    @Unique private static long trinium$colors(byte[] a,byte[] b) {
        long changes=0;
        for (int p=0;p<a.length;p+=4) {
            boolean different=false;
            for(int j=0;j<4;j++)if(Math.abs(Byte.toUnsignedInt(a[p+j])-Byte.toUnsignedInt(b[p+j]))>1)different=true;
            if(different)changes++;
        }
        return changes;
    }
    @Unique private static long trinium$depths(float[] a,float[] b) {
        long changes=0;for(int p=0;p<a.length;p++)if(Float.floatToRawIntBits(a[p])!=Float.floatToRawIntBits(b[p]))changes++;
        return changes;
    }
    @Unique private static TriniumPixels trinium$capture(Minecraft mc,String name) throws java.io.IOException {
        var target=mc.gameRenderer.mainRenderTarget();int w=target.width,h=target.height;
        int oldFbo=glGetInteger(GL_READ_FRAMEBUFFER_BINDING),oldPack=glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        int alignment=glGetInteger(GL_PACK_ALIGNMENT),row=glGetInteger(GL_PACK_ROW_LENGTH),skipRows=glGetInteger(GL_PACK_SKIP_ROWS),skipPixels=glGetInteger(GL_PACK_SKIP_PIXELS);
        int fbo=glGenFramebuffers();
        ByteBuffer rgba=MemoryUtil.memAlloc(w*h*4);FloatBuffer depth=MemoryUtil.memAllocFloat(w*h);
        try {
            glBindFramebuffer(GL_READ_FRAMEBUFFER,fbo);
            glFramebufferTexture2D(GL_READ_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,((GlTexture)target.getColorTexture()).glId(),0);
            glFramebufferTexture2D(GL_READ_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,((GlTexture)target.getDepthTexture()).glId(),0);
            if(glCheckFramebufferStatus(GL_READ_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new AssertionError("Parity read framebuffer incomplete");
            glReadBuffer(GL_COLOR_ATTACHMENT0);glBindBuffer(GL_PIXEL_PACK_BUFFER,0);
            glPixelStorei(GL_PACK_ALIGNMENT,1);glPixelStorei(GL_PACK_ROW_LENGTH,0);glPixelStorei(GL_PACK_SKIP_ROWS,0);glPixelStorei(GL_PACK_SKIP_PIXELS,0);
            glReadPixels(0,0,w,h,GL_RGBA,GL_UNSIGNED_BYTE,rgba);glReadPixels(0,0,w,h,GL_DEPTH_COMPONENT,GL_FLOAT,depth);
            byte[] colors=new byte[w*h*4];rgba.get(colors);float[] depths=new float[w*h];depth.get(depths);
            var image=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
            for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
                int p=(y*w+x)*4;int color=(Byte.toUnsignedInt(colors[p+3])<<24)|(Byte.toUnsignedInt(colors[p])<<16)|(Byte.toUnsignedInt(colors[p+1])<<8)|Byte.toUnsignedInt(colors[p+2]);
                image.setRGB(x,h-1-y,color);
            }
            Path file=mc.gameDirectory.toPath().resolve("screenshots/"+name+".png");Files.createDirectories(file.getParent());ImageIO.write(image,"png",file.toFile());
            return new TriniumPixels(colors,depths);
        } finally {
            glBindFramebuffer(GL_READ_FRAMEBUFFER,oldFbo);glBindBuffer(GL_PIXEL_PACK_BUFFER,oldPack);
            glPixelStorei(GL_PACK_ALIGNMENT,alignment);glPixelStorei(GL_PACK_ROW_LENGTH,row);glPixelStorei(GL_PACK_SKIP_ROWS,skipRows);glPixelStorei(GL_PACK_SKIP_PIXELS,skipPixels);
            glDeleteFramebuffers(fbo);MemoryUtil.memFree(rgba);MemoryUtil.memFree(depth);
        }
    }

public static record TriniumPixels(byte[] color,float[] depth) {}
public static final class TriniumStillScreen extends Screen {
    public TriniumStillScreen() {super(Component.literal("Direct terrain parity"));}
    @Override public boolean isPauseScreen(){return true;}
    @Override public void extractBackground(GuiGraphicsExtractor graphics,int x,int y,float delta){}
    @Override public void extractRenderState(GuiGraphicsExtractor graphics,int x,int y,float delta){}
}

}

// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.probe;

import com.mojang.renderpearl.backend.opengl.GlTexture;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL33C.*;

/** Exact RGBA byte and depth-float readback; deliberately no ±1 color tolerance. */
public record ChestCullingPixels(int width, int height, byte[] color, float[] depth) {
    public static ChestCullingPixels capture(Minecraft minecraft, String name) throws IOException {
        var target=minecraft.gameRenderer.mainRenderTarget();
        if (!(target.getColorTexture() instanceof GlTexture colorTexture)
                || !(target.getDepthTexture() instanceof GlTexture depthTexture))
            throw new AssertionError("Culling oracle requires OpenGL color and depth attachments");
        int width=target.width, height=target.height, pixels=Math.multiplyExact(width,height);
        int oldFbo=glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int oldPack=glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        int alignment=glGetInteger(GL_PACK_ALIGNMENT), row=glGetInteger(GL_PACK_ROW_LENGTH);
        int skipRows=glGetInteger(GL_PACK_SKIP_ROWS), skipPixels=glGetInteger(GL_PACK_SKIP_PIXELS);
        int swap=glGetInteger(GL_PACK_SWAP_BYTES), lsb=glGetInteger(GL_PACK_LSB_FIRST);
        int fbo=0;
        ByteBuffer rgba=null;
        FloatBuffer depths=null;
        try {
            rgba=MemoryUtil.memAlloc(Math.multiplyExact(pixels,4));
            depths=MemoryUtil.memAllocFloat(pixels);
            fbo=glGenFramebuffers();
            glBindFramebuffer(GL_READ_FRAMEBUFFER,fbo);
            glFramebufferTexture2D(GL_READ_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,colorTexture.glId(),0);
            glFramebufferTexture2D(GL_READ_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,depthTexture.glId(),0);
            if (glCheckFramebufferStatus(GL_READ_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)
                throw new AssertionError("Culling oracle read framebuffer is incomplete");
            glReadBuffer(GL_COLOR_ATTACHMENT0);
            glBindBuffer(GL_PIXEL_PACK_BUFFER,0);
            glPixelStorei(GL_PACK_ALIGNMENT,1);glPixelStorei(GL_PACK_ROW_LENGTH,0);
            glPixelStorei(GL_PACK_SKIP_ROWS,0);glPixelStorei(GL_PACK_SKIP_PIXELS,0);
            glPixelStorei(GL_PACK_SWAP_BYTES,GL_FALSE);glPixelStorei(GL_PACK_LSB_FIRST,GL_FALSE);
            glReadPixels(0,0,width,height,GL_RGBA,GL_UNSIGNED_BYTE,rgba);
            glReadPixels(0,0,width,height,GL_DEPTH_COMPONENT,GL_FLOAT,depths);
            byte[] colors=new byte[Math.multiplyExact(pixels,4)];rgba.get(colors);
            float[] depthValues=new float[pixels];depths.get(depthValues);
            var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
            for (int y=0;y<height;y++) for (int x=0;x<width;x++) {
                int p=(y*width+x)*4;
                image.setRGB(x,height-1-y,(Byte.toUnsignedInt(colors[p+3])<<24)
                    |(Byte.toUnsignedInt(colors[p])<<16)|(Byte.toUnsignedInt(colors[p+1])<<8)|Byte.toUnsignedInt(colors[p+2]));
            }
            Path path=minecraft.gameDirectory.toPath().resolve("screenshots/"+name+".png");
            Files.createDirectories(path.getParent());
            if (!ImageIO.write(image,"png",path.toFile())) throw new IOException("No PNG writer");
            return new ChestCullingPixels(width,height,colors,depthValues);
        } finally {
            glBindFramebuffer(GL_READ_FRAMEBUFFER,oldFbo);
            glBindBuffer(GL_PIXEL_PACK_BUFFER,oldPack);
            glPixelStorei(GL_PACK_ALIGNMENT,alignment);glPixelStorei(GL_PACK_ROW_LENGTH,row);
            glPixelStorei(GL_PACK_SKIP_ROWS,skipRows);glPixelStorei(GL_PACK_SKIP_PIXELS,skipPixels);
            glPixelStorei(GL_PACK_SWAP_BYTES,swap);glPixelStorei(GL_PACK_LSB_FIRST,lsb);
            if (fbo!=0) glDeleteFramebuffers(fbo);
            MemoryUtil.memFree(rgba);MemoryUtil.memFree(depths);
        }
    }

    public long colorDifference(ChestCullingPixels other) {
        sameSize(other);long different=0;
        for (int p=0;p<color.length;p+=4)
            if (color[p]!=other.color[p] || color[p+1]!=other.color[p+1]
                    || color[p+2]!=other.color[p+2] || color[p+3]!=other.color[p+3]) different++;
        return different;
    }

    public long depthDifference(ChestCullingPixels other) {
        sameSize(other);long different=0;
        for (int p=0;p<depth.length;p++)
            if (Float.floatToRawIntBits(depth[p])!=Float.floatToRawIntBits(other.depth[p])) different++;
        return different;
    }

    private void sameSize(ChestCullingPixels other) {
        if (width!=other.width || height!=other.height) throw new AssertionError("Target resized between parity renders");
    }
}

package fr.pierrecreeper.trinium.mixin;

import com.mojang.renderpearl.backend.opengl.TriniumGlDrawBridge;
import com.mojang.renderpearl.api.pipeline.IndexType;
import java.nio.IntBuffer;
import org.lwjgl.PointerBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

/** Scope each draw with try/finally and no per-draw boxing or operation array. */
@Mixin(targets="com.mojang.renderpearl.backend.opengl.GlRenderPass",remap=false)
public abstract class GlDrawScopeMixin {
    @Redirect(method={"draw","drawIndexed"},at=@At(value="INVOKE",target="Lcom/mojang/renderpearl/backend/opengl/GlCommandEncoder;executeDraw(Lcom/mojang/renderpearl/backend/opengl/GlRenderPass;IIILcom/mojang/renderpearl/api/pipeline/IndexType;II)V"))
    private void trinium$draw(@Coerce Object encoder,@Coerce Object pass,int base,int first,int count,IndexType type,int instances,int firstInstance) {
        TriniumGlDrawBridge.draw(encoder,pass,base,first,count,type,instances,firstInstance);
    }
    @Redirect(method={"multiDraw(Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;I)V","multiDrawIndexed(Lorg/lwjgl/PointerBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;I)V"},at=@At(value="INVOKE",target="Lcom/mojang/renderpearl/backend/opengl/GlCommandEncoder;executeDraws(Lcom/mojang/renderpearl/backend/opengl/GlRenderPass;Lcom/mojang/renderpearl/api/pipeline/IndexType;Lorg/lwjgl/PointerBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;I)V"))
    private void trinium$multi(@Coerce Object encoder,@Coerce Object pass,IndexType type,PointerBuffer offsets,IntBuffer counts,IntBuffer bases,int count) {
        TriniumGlDrawBridge.multi(encoder,pass,type,offsets,counts,bases,count);
    }
    @Redirect(method={"drawIndirect","drawIndexedIndirect"},at=@At(value="INVOKE",target="Lcom/mojang/renderpearl/backend/opengl/GlCommandEncoder;executeDrawIndirect(Lcom/mojang/renderpearl/backend/opengl/GlRenderPass;Lcom/mojang/renderpearl/api/pipeline/IndexType;Lcom/mojang/renderpearl/backend/opengl/GlBuffer;JI)V"))
    private void trinium$indirect(@Coerce Object encoder,@Coerce Object pass,IndexType type,@Coerce Object buffer,long offset,int count) {
        TriniumGlDrawBridge.indirect(encoder,pass,type,buffer,offset,count);
    }
}

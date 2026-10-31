// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.buffers.*;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import fr.pierrecreeper.trinium.Trinium;
import fr.pierrecreeper.trinium.graphics.*;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.DynamicGpuData.IndexedDraw;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.*;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import java.util.*;
import java.util.function.BiConsumer;

/** Preserve vanilla extraction and ordering; reuse only immutable matching draw data. */
@Mixin(value=LevelRenderer.class,remap=false)
public abstract class TerrainPreparationMixin {
    @Unique private static ChunkSectionLayer[] trinium$layers;
    @Unique private static final boolean trinium$drawCacheDisabled=Boolean.getBoolean("trinium.disableTerrainDrawCache");
    @Unique private TerrainDrawCache trinium$drawOwner;
    @Unique private ChunkSectionLayer trinium$drawLayer;
    @Unique private IndexedDraw trinium$drawCommand;
    @Unique private static Class<?> trinium$vanillaConsumerClass;

    // Method-scoped context avoids allocating LocalRef holders per draw. Save/restore
    // also preserves an outer invocation if another mod reenters this renderer.
    // LevelRenderer extraction is render-thread confined, as in the original game.
    @WrapMethod(method="extractSectionDrawGroups")
    private int trinium$scopeCommands(boolean reverse,List<DynamicGpuData.ChunkSectionInfo> sections,
        Map<ChunkSectionLayer,?> groups,Operation<Integer> original) {
        TerrainDrawCache owner=trinium$drawOwner;ChunkSectionLayer layer=trinium$drawLayer;
        trinium$drawOwner=null;trinium$drawLayer=null;
        try{return original.call(reverse,sections,groups);}
        finally{trinium$drawOwner=owner;trinium$drawLayer=layer;}
    }

    @WrapMethod(method="prepareChunkRenders")
    private ChunkSectionsToRender trinium$scopeDraws(Matrix4fc matrix,boolean reverse,Operation<ChunkSectionsToRender> original) {
        IndexedDraw previous=trinium$drawCommand;trinium$drawCommand=null;
        try{return original.call(matrix,reverse);}
        finally{trinium$drawCommand=previous;}
    }

    @Redirect(method="extractSectionDrawGroups",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;getRenderSectionSlice(Lnet/minecraft/client/renderer/chunk/SectionMesh;Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;)Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSectionBufferSlice;"))
    private SectionRenderDispatcher.RenderSectionBufferSlice trinium$skipEmpty(SectionRenderDispatcher dispatcher,SectionMesh mesh,ChunkSectionLayer layer) {
        if (Trinium.active() && mesh.getSectionDraw(layer)==null) {Trinium.emptyLayerLookupsSkipped++;return null;}
        var result=TerrainSliceCache.resolve(dispatcher,mesh,layer);
        if (result!=null && !trinium$drawCacheDisabled && Trinium.active() && mesh.getClass()==CompiledSectionMesh.class && mesh instanceof TerrainSliceOwner owner) {
            trinium$drawOwner=owner.trinium$terrainDraws();trinium$drawLayer=layer;
        } else trinium$drawOwner=null;
        return result;
    }

    @Redirect(method="extractSectionDrawGroups",at=@At(value="NEW",target="net/minecraft/client/renderer/DynamicGpuData$IndexedDraw"))
    private IndexedDraw trinium$reuseCommand(int count,int instances,int first,int base,int instance) {
        TerrainDrawCache owner=trinium$drawOwner;ChunkSectionLayer layer=trinium$drawLayer;
        return owner==null ? new IndexedDraw(count,instances,first,base,instance)
            : owner.resolve(layer,count,instances,first,base,instance);
    }

    @Redirect(method="prepareChunkRenders",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/DynamicGpuData$IndexedDraw;baseInstance()I"))
    private int trinium$rememberCommand(IndexedDraw command) {
        if (!trinium$drawCacheDisabled || ScopedTerrainSubmit.REQUESTED) trinium$drawCommand=command;
        return command.baseInstance();
    }

    @Redirect(method="prepareChunkRenders",at=@At(value="NEW",target="com/mojang/renderpearl/api/commands/RenderPass$Draw"))
    private RenderPass.Draw<GpuBufferSlice[]> trinium$reuseDraw(int slot,GpuBuffer vertex,GpuBuffer index,IndexType type,
        int first,int count,int base,BiConsumer<GpuBufferSlice[],RenderPass.UniformUploader> consumer) {
        IndexedDraw command=trinium$drawCommand;
        // The vanilla callback captures only this immutable command's baseInstance.
        // A foreign callback keeps its original construction/invocation behavior.
        boolean vanilla=Trinium.active() && command!=null && consumer!=null && trinium$vanillaConsumer(consumer);
        RenderPass.Draw<GpuBufferSlice[]> draw= !trinium$drawCacheDisabled && vanilla && (Object)command instanceof CachedTerrainDraw cached
            ? cached.trinium$draw(slot,vertex,index,type,first,count,base,consumer)
            : new RenderPass.Draw<>(slot,vertex,index,type,first,count,base,consumer);
        if (ScopedTerrainSubmit.REQUESTED && vanilla && (Object)draw instanceof DirectTerrainTag tag)
            tag.trinium$section(command.baseInstance());
        return draw;
    }

    @Unique private static boolean trinium$vanillaConsumer(Object consumer) {
        Class<?> type=consumer.getClass(),known=trinium$vanillaConsumerClass;
        if (known!=null) return type==known;
        // Resolve once per call-site class, not once per visible draw.
        if (type.getNestHost()!=LevelRenderer.class) return false;
        trinium$vanillaConsumerClass=type;return true;
    }

    @Redirect(method="extractSectionDrawGroups",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;values()[Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;"))
    private ChunkSectionLayer[] trinium$reuseReadOnlyLayerList() {
        if (!Trinium.active()) return ChunkSectionLayer.values();
        // Delay initialization: eager enums form a cycle with RenderPipelines at startup.
        if (trinium$layers==null) trinium$layers=ChunkSectionLayer.values();
        return trinium$layers;
    }
}

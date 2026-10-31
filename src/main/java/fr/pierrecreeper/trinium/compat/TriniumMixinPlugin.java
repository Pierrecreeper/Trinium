package fr.pierrecreeper.trinium.compat;

import java.util.*;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.*;

/** External renderers own their batching/vertex formats. Keep the CPU helpers and shader cache. */
public final class TriniumMixinPlugin implements IMixinConfigPlugin {
    public void onLoad(String mixinPackage){}
    public String getRefMapperConfig(){return null;}
    public boolean shouldApplyMixin(String target,String mixin) {
        String name=mixin.substring(mixin.lastIndexOf('.')+1);
        if(name.equals("ClimateNodeDistanceMixin")) return Boolean.getBoolean("trinium.experimentalClimateDistance")
            && !Boolean.getBoolean("trinium.disableClimateDistance")
            && !net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("lithium")
            && !net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("c2me");
        if(name.startsWith("LoadingGrid")) return Boolean.getBoolean("trinium.experimentalLoadingGrid")
            && !Boolean.getBoolean("trinium.disableLoadingGrid");
        if (name.equals("DirectTerrainTagMixin") || name.equals("TerrainSubmitMixin")) return !Boolean.getBoolean("trinium.disableTerrainSubmit")
            && !RenderCompatibility.SODIUM && !RenderCompatibility.IRIS;
        if(name.equals("StandaloneShaderMixin"))return !RenderCompatibility.IRIS;
        if(name.startsWith("Iris"))return RenderCompatibility.IRIS && net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("iris").orElseThrow().getMetadata().getVersion().getFriendlyString().equals("1.11.7+mc26.3");
        if(RenderCompatibility.SODIUM||RenderCompatibility.IRIS)
            return !name.startsWith("Gl") && !Set.of("TerrainPreparationMixin","TerrainDrawPreparationMixin","TerrainIndexedDrawMixin","BulkBufferMixin","ModelBatchMixin","ChestFrustumCullingMixin","ChestFrustumAccess","CompiledMeshSliceMixin","UberBufferInvalidationMixin").contains(name);
        return true;
    }
    public void acceptTargets(Set<String> mine,Set<String> others){}
    public List<String> getMixins(){return null;}
    public void preApply(String name,ClassNode node,String mixin,IMixinInfo info){}
    public void postApply(String name,ClassNode node,String mixin,IMixinInfo info){}
}

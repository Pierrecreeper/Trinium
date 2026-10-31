package fr.pierrecreeper.trinium.mixin;

import fr.pierrecreeper.trinium.graphics.TerrainSliceCache;
import fr.pierrecreeper.trinium.graphics.TerrainDrawCache;
import fr.pierrecreeper.trinium.graphics.TerrainSliceOwner;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=CompiledSectionMesh.class,remap=false)
public abstract class CompiledMeshSliceMixin implements TerrainSliceOwner {
    @Unique private TerrainSliceCache trinium$slices;
    @Unique private TerrainDrawCache trinium$draws;
    @Override public TerrainDrawCache trinium$terrainDraws() {
        if (trinium$draws==null) trinium$draws=new TerrainDrawCache();
        return trinium$draws;
    }
    @Override public TerrainSliceCache trinium$terrainSlices() {
        if (trinium$slices == null) trinium$slices = new TerrainSliceCache();
        return trinium$slices;
    }
    @Override public void trinium$invalidateTerrainSlices() {
        if (trinium$slices != null) trinium$slices.clear();
        if (trinium$draws != null) trinium$draws.clear();
    }
    @Inject(method="close",at=@At("HEAD"))
    private void trinium$releaseCachedSlices(CallbackInfo callback) {
        trinium$invalidateTerrainSlices();
    }
}

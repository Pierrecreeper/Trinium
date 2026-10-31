package fr.pierrecreeper.trinium.graphics;

/** Implemented only by transformed vanilla CompiledSectionMesh objects. */
public interface TerrainSliceOwner {
    TerrainSliceCache trinium$terrainSlices();
    TerrainDrawCache trinium$terrainDraws();
    void trinium$invalidateTerrainSlices();
}

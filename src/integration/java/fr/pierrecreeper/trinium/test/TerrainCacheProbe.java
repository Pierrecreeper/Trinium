package fr.pierrecreeper.trinium.probe;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.StagingBuffer;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import fr.pierrecreeper.trinium.graphics.TerrainSliceCache;
import fr.pierrecreeper.trinium.graphics.TerrainSliceOwner;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.*;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSectionBufferSlice;

/** Real transformed mesh/allocator lifecycle, with small isolated GPU buffers only. */
public final class TerrainCacheProbe {
    private static int checks;
    private static void check(boolean value,String reason) {
        if (!value) throw new AssertionError(reason);
        checks++;
    }
    private static CompiledSectionMesh mesh() {
        return new CompiledSectionMesh(new TranslucencyPointOfView(),new SectionCompiler.Results(),0);
    }
    private static Field field(Class<?> type,String name) throws ReflectiveOperationException {
        Field field=type.getDeclaredField(name);field.setAccessible(true);return field;
    }
    private static TerrainSliceCache cache(CompiledSectionMesh mesh) {
        check(mesh instanceof TerrainSliceOwner,"CompiledMeshSliceMixin active");
        return ((TerrainSliceOwner)mesh).trinium$terrainSlices();
    }
    private static void seed(TerrainSliceCache cache,RenderSectionBufferSlice slice) throws ReflectiveOperationException {
        field(TerrainSliceCache.class,"dispatcher").set(cache,new Object());
        field(TerrainSliceCache.class,"known").setInt(cache,1);
        ((RenderSectionBufferSlice[])field(TerrainSliceCache.class,"slices").get(cache))[0]=slice;
    }
    private static void cleared(TerrainSliceCache cache,String reason) throws ReflectiveOperationException {
        check(field(TerrainSliceCache.class,"known").getInt(cache)==0,reason+": validity cleared");
        check(field(TerrainSliceCache.class,"dispatcher").get(cache)==null,reason+": owner released");
        for (Object value:(Object[])field(TerrainSliceCache.class,"slices").get(cache))
            check(value==null,reason+": GPU reference released");
    }
    private static void upload(StagingBuffer staging,UberGpuBuffer<SectionMesh> buffer,CompiledSectionMesh mesh,int bytes) {
        check(buffer.addAllocation(mesh,null,ByteBuffer.allocateDirect(bytes)),"stage allocation");
        try (var uploader=staging.startUploading(RenderSystem.getDevice().createCommandEncoder())) {
            buffer.uploadStagedAllocations(RenderSystem.getDevice(),uploader);
        }
        check(buffer.getAllocation(mesh)!=null,"allocation uploaded");
    }
    public static void runLifecycle() throws ReflectiveOperationException {
        checks=0;
        CompiledSectionMesh first=mesh(),second=mesh();
        TerrainSliceCache firstCache=cache(first),secondCache=cache(second);
        var device=RenderSystem.getDevice();
        try (StagingBuffer staging=StagingBuffer.create("Trinium terrain lifecycle",device,4096);
             UberGpuBuffer<SectionMesh> vertices=new UberGpuBuffer<>("Trinium vertices",GpuBuffer.USAGE_VERTEX,128,4,staging);
             UberGpuBuffer<SectionMesh> indices=new UberGpuBuffer<>("Trinium indices",GpuBuffer.USAGE_INDEX,128,4,staging)) {
            // Negative entries must disappear as soon as a previously absent upload arrives.
            seed(firstCache,null);
            upload(staging,vertices,first,32);
            cleared(firstCache,"first upload");
            var old=vertices.getAllocation(first);
            GpuBuffer oldGpu=vertices.getGpuBuffer(old);
            var oldSlice=new RenderSectionBufferSlice(oldGpu,old.getOffsetFromHeap(),null,0);
            seed(firstCache,oldSlice);
            check(vertices.addAllocation(first,null,ByteBuffer.allocateDirect(64)),"stage replacement");
            check(!old.isFreed(),"staging preserves live allocation until upload");
            check(field(TerrainSliceCache.class,"known").getInt(firstCache)==1,"staging preserves valid cache");
            try (var uploader=staging.startUploading(device.createCommandEncoder())) {
                vertices.uploadStagedAllocations(device,uploader);
            }
            check(old.isFreed(),"replacement releases old allocation");
            cleared(firstCache,"replacement upload");
            var current=vertices.getAllocation(first);
            GpuBuffer currentGpu=vertices.getGpuBuffer(current);
            var currentSlice=new RenderSectionBufferSlice(currentGpu,current.getOffsetFromHeap(),null,0);
            seed(firstCache,currentSlice);
            // Force a second heap. Existing live allocations and their buffers never move.
            upload(staging,vertices,second,96);
            check(vertices.getGpuBuffer(vertices.getAllocation(second))!=currentGpu,"new GPU heap allocated");
            check(vertices.getAllocation(first)==current,"heap growth retains allocation identity");
            check(!currentGpu.isClosed(),"heap growth retains live GPU buffer");
            check(field(TerrainSliceCache.class,"known").getInt(firstCache)==1,"unrelated mesh upload preserves cache");
            upload(staging,indices,first,16);
            cleared(firstCache,"index upload");
            var index=indices.getAllocation(first);
            seed(firstCache,new RenderSectionBufferSlice(currentGpu,current.getOffsetFromHeap(),indices.getGpuBuffer(index),index.getOffsetFromHeap()));
            upload(staging,indices,first,32);
            check(index.isFreed(),"translucent index replacement releases old indices");
            cleared(firstCache,"index replacement");
            seed(firstCache,currentSlice);
            vertices.removeAllocation(first);
            check(current.isFreed(),"removal releases allocation");
            cleared(firstCache,"allocation removal");
            try (var uploader=staging.startUploading(device.createCommandEncoder())) {
                vertices.uploadStagedAllocations(device,uploader);
            }
            check(currentGpu.isClosed(),"completely free GPU heap closed after invalidation");
            upload(staging,vertices,first,32);
            var replacement=vertices.getAllocation(first);
            seed(firstCache,new RenderSectionBufferSlice(vertices.getGpuBuffer(replacement),replacement.getOffsetFromHeap(),null,0));
            first.close();
            cleared(firstCache,"mesh close");
            var other=vertices.getAllocation(second);
            seed(firstCache,new RenderSectionBufferSlice(vertices.getGpuBuffer(replacement),replacement.getOffsetFromHeap(),null,0));
            seed(secondCache,new RenderSectionBufferSlice(vertices.getGpuBuffer(other),other.getOffsetFromHeap(),null,0));
            vertices.close();
            cleared(firstCache,"buffer close first mesh");
            cleared(secondCache,"buffer close second mesh");
        } finally { first.close();second.close(); }
        System.out.println("TRINIUM_TERRAIN_LIFECYCLE_OK "+checks+" checks; real GPU upload/replacement/index/free/heap-growth/close");
    }

    /** Call after the isolated test world has visible sections; never mutates world buffers. */
    public static void runReferenceParity(Minecraft minecraft) throws ReflectiveOperationException {
        LevelRenderer renderer=minecraft.levelRenderer;
        var dispatcher=(SectionRenderDispatcher)field(LevelRenderer.class,"sectionRenderDispatcher").get(renderer);
        List<?> visible=(List<?>)field(LevelRenderer.class,"visibleSections").get(renderer);
        long before=TerrainSliceCache.hits;
        int compared=0,meshes=0;
        dispatcher.lock();
        try {
            for (Object section:visible) {
                SectionMesh mesh=((SectionRenderDispatcher.RenderSection)section).getSectionMesh();
                if (mesh.getClass()!=CompiledSectionMesh.class) continue;
                for (ChunkSectionLayer layer:ChunkSectionLayer.values()) {
                    if (mesh.getSectionDraw(layer)==null) continue;
                    var reference=dispatcher.getRenderSectionSlice(mesh,layer);
                    var first=TerrainSliceCache.resolve(dispatcher,mesh,layer);
                    var second=TerrainSliceCache.resolve(dispatcher,mesh,layer);
                    check(Objects.equals(reference,first),"vanilla/cached slice values "+layer);
                    check(first==second,"unchanged slice object reused "+layer);
                    if (compared==0) {
                        // A mesh handed to a different dispatcher must never reuse the
                        // prior owner's record, even if its layer-valid bit remains set.
                        TerrainSliceCache cache=((TerrainSliceOwner)mesh).trinium$terrainSlices();
                        field(TerrainSliceCache.class,"dispatcher").set(cache,new Object());
                        ((RenderSectionBufferSlice[])field(TerrainSliceCache.class,"slices").get(cache))[layer.ordinal()]=null;
                        long misses=TerrainSliceCache.misses;
                        check(Objects.equals(reference,TerrainSliceCache.resolve(dispatcher,mesh,layer)),"different dispatcher re-queries vanilla");
                        check(TerrainSliceCache.misses==misses+1,"different dispatcher cannot hit old cache");
                    }
                    compared++;
                }
                if (++meshes==128) break;
            }
            var custom=new CompiledSectionMesh(new TranslucencyPointOfView(),new SectionCompiler.Results(),0) { };
            long misses=TerrainSliceCache.misses,hits=TerrainSliceCache.hits;
            for (ChunkSectionLayer layer:ChunkSectionLayer.values())
                check(Objects.equals(dispatcher.getRenderSectionSlice(custom,layer),TerrainSliceCache.resolve(dispatcher,custom,layer)),"custom mesh vanilla fallback");
            check(TerrainSliceCache.misses==misses && TerrainSliceCache.hits==hits,"custom mesh not cached");
            custom.close();
        } finally { dispatcher.unlock(); }
        check(compared>0,"visible terrain compared");
        check(TerrainSliceCache.hits>before,"slice cache exercised in world");
        System.out.println("TRINIUM_TERRAIN_REFERENCE_OK "+compared+" slices from "+meshes+" meshes match vanilla exactly");
    }
}

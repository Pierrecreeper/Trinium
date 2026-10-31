// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.probe;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.renderpearl.api.buffers.*;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.backend.common.BaseGpuBuffer;
import fr.pierrecreeper.trinium.Trinium;
import fr.pierrecreeper.trinium.graphics.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DynamicGpuData.IndexedDraw;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.*;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.BiConsumer;

/** Real transformed buffers/records and independent reconstruction of visible draw data. */
public final class DrawCacheProbe {
    private static int checks;
    private static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);checks++;}
    private static Field field(Class<?> type,String name)throws Exception{var f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    private static CompiledSectionMesh mesh(){return new CompiledSectionMesh(new TranslucencyPointOfView(),new SectionCompiler.Results(),0);}
    private static TerrainDrawCache cache(CompiledSectionMesh mesh){return ((TerrainSliceOwner)mesh).trinium$terrainDraws();}
    private static void cleared(TerrainDrawCache cache,String reason)throws Exception{
        for(Object value:(Object[])field(TerrainDrawCache.class,"draws").get(cache))check(value==null,reason+" releases draw/GPU references");
    }
    private static final class DynamicBuffer extends BaseGpuBuffer {
        long length=8;boolean closed;
        DynamicBuffer(){super(0,8);}
        public long size(){return length;}
        public boolean isClosed(){return closed;}
        public void close(){closed=true;}
        public GpuBufferSlice.MappedView map(long offset,long length,boolean read,boolean write){throw new UnsupportedOperationException();}
    }
    private static final class CustomSliceBuffer extends BaseGpuBuffer {
        CustomSliceBuffer(){super(0,8);}
        final GpuBufferSlice custom=new GpuBufferSlice(this,2,4);
        public GpuBufferSlice slice(){return custom;}
        public boolean isClosed(){return false;}
        public void close(){}
        public GpuBufferSlice.MappedView map(long offset,long length,boolean read,boolean write){throw new UnsupportedOperationException();}
    }
    private static final class Uniforms implements RenderPass.UniformUploader {
        String name;GpuBufferSlice value;
        public void setUniform(String name,GpuBufferSlice value){this.name=name;this.value=value;}
        public void pushConstants(ByteBuffer bytes){throw new AssertionError("Unexpected push constants");}
    }
    public static void runLifecycle()throws Exception{
        checks=0;
        var device=RenderSystem.getDevice();
        GpuBuffer buffer=device.createBuffer(()->"Trinium full-slice probe",GpuBuffer.USAGE_VERTEX,64);
        try{
            GpuBufferSlice first=buffer.slice();
            check(first==buffer.slice(),"Full view reused");check(first.equals(new GpuBufferSlice(buffer,0,64)),"Full view matches vanilla values");
            check(buffer.slice(0,64).equals(first),"Range full view keeps values");
            check(buffer.slice(4,16).equals(new GpuBufferSlice(buffer,4,16)),"Range views unchanged");
            try{buffer.slice(-1,1);throw new AssertionError("Negative range accepted");}catch(IllegalArgumentException expected){checks++;}
            try{buffer.slice(60,8);throw new AssertionError("Out of bounds range accepted");}catch(IllegalArgumentException expected){checks++;}
            buffer.close();check(buffer.slice()==first,"Vanilla allows view formation after close");
        }finally{buffer.close();}
        var dynamic=new DynamicBuffer();GpuBufferSlice before=dynamic.slice();dynamic.length=16;
        check(dynamic.slice()!=before&&dynamic.slice().length()==16,"Subclass size change refreshes full view");
        var custom=new CustomSliceBuffer();check(custom.slice()==custom.custom,"Custom slice override preserved");

        CompiledSectionMesh mesh=mesh();TerrainDrawCache cache=cache(mesh);ChunkSectionLayer layer=ChunkSectionLayer.values()[0];
        try(StagingBuffer staging=StagingBuffer.create("Trinium draw lifecycle",device,1024);
            UberGpuBuffer<SectionMesh> allocations=new UberGpuBuffer<>("Trinium draw allocations",GpuBuffer.USAGE_VERTEX,128,4,staging)){
            IndexedDraw original=cache.resolve(layer,6,1,3,5,7);check(original==cache.resolve(layer,6,1,3,5,7),"Immutable command reuse");
            check(original.equals(new IndexedDraw(6,1,3,5,7)),"Command equals independent vanilla record");
            for(int field=0;field<5;field++){
                int[] args={6,1,3,5,7};args[field]++;
                IndexedDraw changed=cache.resolve(layer,args[0],args[1],args[2],args[3],args[4]);
                check(changed!=original&&changed.equals(new IndexedDraw(args[0],args[1],args[2],args[3],args[4])),"Every field participates in key "+field);
            }
            check(allocations.addAllocation(mesh,null,ByteBuffer.allocateDirect(32)),"Stage allocation");
            try(var uploader=staging.startUploading(device.createCommandEncoder())){allocations.uploadStagedAllocations(device,uploader);}
            cleared(cache,"Upload");
            var allocation=allocations.getAllocation(mesh);GpuBuffer gpu=allocations.getGpuBuffer(allocation);
            var command=cache.resolve(layer,6,1,0,0,0);check((Object)command instanceof CachedTerrainDraw,"Record mixin active");
            CachedTerrainDraw cached=(CachedTerrainDraw)(Object)command;
            BiConsumer<GpuBufferSlice[],RenderPass.UniformUploader> consumer=(data,uniforms)->uniforms.setUniform("ChunkSection",data[0]);
            var draw=cached.trinium$draw(0,gpu,null,null,0,6,0,consumer);
            check(draw==cached.trinium$draw(0,gpu,null,null,0,6,0,consumer),"Identical Draw reused");
            check(draw.vertexBuffer()==gpu&&draw.indexBuffer()==null&&draw.firstIndex()==0&&draw.indexCount()==6,"Draw retains exact resource and offsets");
            var changedSlot=cached.trinium$draw(1,gpu,null,null,0,6,0,consumer);check(changedSlot!=draw&&changedSlot.slot()==1,"Changed slot refreshes Draw");
            var changedIndex=cached.trinium$draw(1,gpu,gpu,IndexType.INT,3,6,9,consumer);check(changedIndex.indexBuffer()==gpu&&changedIndex.indexType()==IndexType.INT&&changedIndex.firstIndex()==3&&changedIndex.baseVertex()==9,"Changed buffers/type/offset refresh Draw");
            BiConsumer<GpuBufferSlice[],RenderPass.UniformUploader> other=(data,uniforms)->uniforms.setUniform("Other",data[0]);
            check(cached.trinium$draw(1,gpu,gpu,IndexType.INT,3,6,9,other).uniformUploaderConsumer()==other,"Different callback class retained");
            check(allocations.addAllocation(mesh,null,ByteBuffer.allocateDirect(64)),"Stage replacement");
            try(var uploader=staging.startUploading(device.createCommandEncoder())){allocations.uploadStagedAllocations(device,uploader);}
            cleared(cache,"Replacement");
            cache.resolve(layer,6,1,0,0,0);allocations.removeAllocation(mesh);cleared(cache,"Free");
            cache.resolve(layer,6,1,0,0,0);mesh.close();cleared(cache,"Mesh close");
            // A closed mesh cannot receive a new upload. Use a fresh live owner.
            CompiledSectionMesh live=mesh();TerrainDrawCache liveCache=cache(live);
            try {
                check(allocations.addAllocation(live,null,ByteBuffer.allocateDirect(32)),"Stage close fixture");
                try(var uploader=staging.startUploading(device.createCommandEncoder())){allocations.uploadStagedAllocations(device,uploader);}
                check(allocations.getAllocation(live)!=null,"Heap-close fixture owns a live allocation");
                liveCache.resolve(layer,6,1,0,0,0);allocations.close();cleared(liveCache,"Heap close");
            } finally { live.close(); }
        }finally{mesh.close();}
        System.out.println("TRINIUM_DRAW_LIFECYCLE_OK "+checks+" checks; immutable buffer views, keys, callbacks, upload/free/close");
    }

    public static void runReferenceParity(Minecraft mc)throws Exception{
        var renderer=mc.levelRenderer;
        var dispatcher=(SectionRenderDispatcher)field(LevelRenderer.class,"sectionRenderDispatcher").get(renderer);
        List<?> visible=(List<?>)field(LevelRenderer.class,"visibleSections").get(renderer);
        GpuBufferSlice[] uniforms=new GpuBufferSlice[visible.size()];for(int i=0;i<uniforms.length;i++)uniforms[i]=new GpuBufferSlice(null,i,1);
        int instance=0,commands=0,draws=0;
        dispatcher.lock();
        try{
            for(Object item:visible){
                SectionMesh mesh=((SectionRenderDispatcher.RenderSection)item).getSectionMesh();boolean counted=false;
                for(ChunkSectionLayer layer:ChunkSectionLayer.values()){
                    var definition=mesh.getSectionDraw(layer);var slice=dispatcher.getRenderSectionSlice(mesh,layer);
                    if(definition==null||slice==null||(definition.hasCustomIndexBuffer()&&slice.indexBuffer()==null))continue;
                    counted=true;
                    if(mesh.getClass()!=CompiledSectionMesh.class)continue;
                    int first=definition.hasCustomIndexBuffer()?(int)(slice.indexBufferOffset()/definition.indexType().bytes):0;
                    int base=(int)(slice.vertexBufferOffset()/layer.pipeline(false).getVertexFormatBinding(0).getVertexSize());
                    var command=((TerrainSliceOwner)mesh).trinium$terrainDraws().resolve(layer,definition.indexCount(),1,first,base,instance);
                    check(command.equals(new IndexedDraw(definition.indexCount(),1,first,base,instance)),"Visible command matches reconstruction");commands++;
                    var cached=((CachedTerrainDraw)(Object)command).trinium$cachedDraw();
                    if(cached==null)continue;
                    check(cached.vertexBuffer()==slice.vertexBuffer(),"Visible vertex buffer matches authoritative allocation");
                    check(cached.indexBuffer()==(definition.hasCustomIndexBuffer()?slice.indexBuffer():null),"Visible index buffer matches authoritative allocation");
                    check(cached.indexType()==(definition.hasCustomIndexBuffer()?definition.indexType():null),"Visible index type");
                    check(cached.slot()==0&&cached.firstIndex()==first&&cached.indexCount()==definition.indexCount()&&cached.baseVertex()==base,"Visible Draw fields match reconstruction");
                    var capture=new Uniforms();cached.uniformUploaderConsumer().accept(uniforms,capture);
                    check("ChunkSection".equals(capture.name)&&capture.value==uniforms[instance],"Vanilla callback uses current baseInstance and supplied frame array");draws++;
                }
                if(counted)instance++;
            }
        }finally{dispatcher.unlock();}
        check(commands>0&&draws>0,"Actual visible commands and callbacks examined");
        check(Trinium.terrainDrawHits>0&&Trinium.terrainRenderDrawHits>0,"Both constructor wrappers exercise production cache hits");
        check(Trinium.gpuFullSliceHits>0,"Full-buffer view cache exercised in game");
        System.out.println("TRINIUM_DRAW_REFERENCE_OK "+commands+" commands and "+draws+" actual Draws/callbacks match vanilla reconstruction");
    }
}

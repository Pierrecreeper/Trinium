// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.graphics;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.*;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import fr.pierrecreeper.trinium.Trinium;
import fr.pierrecreeper.trinium.NativeBridge;
import fr.pierrecreeper.trinium.compat.RenderCompatibility;
import java.util.List;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

/** Keep the vanilla shaders, draw data and GL order; amortize native submission. */
public final class ScopedTerrainSubmit {
    public static final boolean REQUESTED=!Boolean.getBoolean("trinium.disableTerrainSubmit")
        && !RenderCompatibility.IRIS && !RenderCompatibility.SODIUM;
    public static boolean renderingEnabled=true;
    public static long passes,draws,fallbacks;
    private static final List<String> PER_DRAW_UNIFORMS=List.of("ChunkSection");
    private ScopedTerrainSubmit() {}

    public static boolean render(ChunkSectionLayer layer,RenderPass pass,GpuBuffer indices,IndexType type,
        RenderPipeline override,List<RenderPass.Draw<GpuBufferSlice[]>> commands,GpuBufferSlice[] sections) {
        if (!REQUESTED || !renderingEnabled || !Trinium.active() || !NativeBridge.available()
            || Trinium.mode()==Trinium.Mode.JAVA || Boolean.getBoolean("trinium.disableGraphics")) return false;
        // Foreign render-pass implementations and callbacks can execute arbitrary
        // GL or mutate resources. They retain their original submission boundaries.
        if (pass.getClass()!=FrontendRenderPass.class || commands==null || sections==null
            || !RenderSystem.getDevice().getDeviceInfo().backendName().equalsIgnoreCase("OpenGL")) {
            fallbacks++;return false;
        }
        for(var draw:commands) {
            if (!((Object)draw instanceof DirectTerrainTag tag) || tag.trinium$section()<0
                || tag.trinium$section()>=sections.length || sections[tag.trinium$section()]==null) {
                fallbacks++;return false;
            }
        }
        // Resolve the pipeline before entering a command scope. The guarded
        // vanilla callbacks below only select already-uploaded uniform slices.
        var compiled=RenderSystem.getCompiledPipeline(override!=null?override:layer.pipeline(false));
        pass.setPipeline(compiled);
        if (!commands.isEmpty()) {
            RustGlDriver.beginCoalesced();
            try {pass.drawMultipleIndexed(commands,indices,type,PER_DRAW_UNIFORMS,sections);}
            finally {RustGlDriver.endCoalesced();}
            passes++;draws+=commands.size();
        }
        return true;
    }
}

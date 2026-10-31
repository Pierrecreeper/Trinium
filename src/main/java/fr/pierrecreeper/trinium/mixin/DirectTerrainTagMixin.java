// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.mixin;

import com.mojang.renderpearl.api.commands.RenderPass;
import fr.pierrecreeper.trinium.graphics.DirectTerrainTag;
import org.spongepowered.asm.mixin.*;

@Mixin(value=RenderPass.Draw.class,remap=false)
public abstract class DirectTerrainTagMixin implements DirectTerrainTag {
    @Unique private boolean trinium$hasSection;
    @Unique private int trinium$section;
    @Override public void trinium$section(int section) {trinium$section=section;trinium$hasSection=true;}
    @Override public int trinium$section() {return trinium$hasSection?trinium$section:-1;}
}

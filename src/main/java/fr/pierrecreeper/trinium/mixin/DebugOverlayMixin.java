package fr.pierrecreeper.trinium.mixin;

import fr.pierrecreeper.trinium.Trinium;
import java.util.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(value = DebugScreenOverlay.class, remap = false)
public abstract class DebugOverlayMixin {
    @ModifyVariable(method = "extractLines", at = @At("HEAD"), argsOnly = true)
    private List<String> trinium$status(List<String> value, GuiGraphicsExtractor graphics,
                                       List<String> original, boolean left, int width) {
        if (!left) return value;
        List<String> lines = new ArrayList<>(value);
        lines.add(Trinium.rendererLine());
        lines.add(Trinium.debugLine());
        return lines;
    }
}

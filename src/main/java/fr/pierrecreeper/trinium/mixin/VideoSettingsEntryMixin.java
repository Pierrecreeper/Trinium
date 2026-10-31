// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.mixin;

import fr.pierrecreeper.trinium.ui.VideoSettingsScreen;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Intercepts only the video entry from the main options screen, including Sodium's replacement. */
@Mixin(value=Gui.class,remap=false)
public abstract class VideoSettingsEntryMixin {
    @Shadow public abstract Screen screen();

    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Screen trinium$videoSettings(Screen requested) {
        if (!fr.pierrecreeper.trinium.Trinium.active()) return requested;
        if (!(screen() instanceof OptionsScreen) || requested == null) return requested;
        String name = requested.getClass().getName();
        if (name.equals("net.minecraft.client.gui.screens.options.VideoSettingsScreen")
                || name.equals("net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen")) {
            return new VideoSettingsScreen(screen(), requested);
        }
        return requested;
    }
}

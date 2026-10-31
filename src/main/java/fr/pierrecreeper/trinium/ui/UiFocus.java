// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.ui;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;

/** Keeps keyboard focus on a live control after a mouse callback rebuilds a screen. */
final class UiFocus {
    private UiFocus() {}

    static void afterClick(Screen screen, int previousIndex) {
        // Minecraft assigns the clicked child focus after its callback returns.
        // That child may have been removed by a category or option rebuild.
        if (screen.getFocused() == null || screen.children().contains(screen.getFocused())) return;
        screen.setFocused(null);
        if (previousIndex >= 0 && previousIndex < screen.children().size()
                && screen.children().get(previousIndex) instanceof AbstractWidget widget
                && widget.active && widget.visible) {
            screen.setFocused(widget);
        }
    }
}

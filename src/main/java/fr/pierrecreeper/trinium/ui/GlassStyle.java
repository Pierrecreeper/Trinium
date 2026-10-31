// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Translucent glass surfaces layered over Minecraft's existing menu blur. */
public final class GlassStyle {
    public static final int BG = 0x08000000;
    public static final int PANEL = 0x6410151C;
    public static final int SIDEBAR = 0x7B0E141C;
    public static final int ACCENT = 0xFFA2D7C8;
    public static final int TEXT = 0xFFF1F3F5;
    public static final int MUTED = 0xFFB5BBC2;
    public static final int DISABLED = 0xFF747C85;
    public static final int BORDER = 0x66CFD8E3;
    private GlassStyle() {}

    /** Tint only. Screens render their world/panorama and native blur before this call. */
    public static void background(GuiGraphicsExtractor graphics, int width, int height) {
        graphics.fill(0, 0, width, height, BG);
    }

    public static void panel(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        panel(graphics, x, y, width, height, PANEL);
    }

    public static void panel(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int fill) {
        if (width < 6 || height < 6) return;
        graphics.fill(x, y, x + width, y + height, fill);
        frame(graphics, x, y, width, height, BORDER);
    }

    public static void frame(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int color) {
        graphics.fill(x, y, x + width, y + 1, color);
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        graphics.fill(x, y + 1, x + 1, y + height - 1, color);
        graphics.fill(x + width - 1, y + 1, x + width, y + height - 1, color);
    }

    public static void rounded(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int color) {
        graphics.fill(x, y, x + width, y + height, color);
    }

    public static void text(Font font, GuiGraphicsExtractor graphics, String text, int x, int y, int color) {
        graphics.text(font, text, x, y, color, false);
    }

    public static String fit(Font font, String value, int width) {
        if (width <= 0) return "";
        if (font.width(value) <= width) return value;
        return font.plainSubstrByWidth(value, Math.max(0, width - font.width("…"))) + "…";
    }

    public static Button button(int x, int y, int width, int height, Component message, Runnable action) {
        return new GlassButton(x, y, width, height, message, action);
    }

    public static Button accentButton(int x, int y, int width, int height, Component message, Runnable action) {
        return new GlassButton(x, y, width, height, message, action);
    }

    public static void control(GuiGraphicsExtractor graphics, int x, int y, int width, int height,
                               boolean active, boolean highlighted, boolean pressed) {
        int fill = !active ? 0x09000000 : pressed ? 0x58FFFFFF : highlighted ? 0x3BFFFFFF : 0x16FFFFFF;
        graphics.fill(x, y, x + width, y + height, fill);
        frame(graphics, x, y, width, height, !active ? 0x24A7B1BB : highlighted ? 0xB0EEF4FA : BORDER);
    }

    public static Button navigationButton(int x, int y, int width, int height,
                                          Component message, Runnable action, boolean selected) {
        return new Button(x, y, width, height, message, ignored -> action.run(), supplier -> supplier.get()) {
            @Override protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
                boolean highlight = active && isHoveredOrFocused();
                if (selected || highlight) graphics.fill(getX(), getY(), getRight(), getBottom(), selected ? 0x2BFFFFFF : 0x16FFFFFF);
                if (selected || isFocused()) graphics.fill(getX(), getY() + 2, getX() + 1, getBottom() - 2, ACCENT);
                Font font = Minecraft.getInstance().font;
                text(font, graphics, fit(font, getMessage().getString(), width - 15),
                        getX() + 7, getY() + (height - 8) / 2, !active ? DISABLED : selected || highlight ? TEXT : MUTED);
            }
        };
    }

    private static final class GlassButton extends Button {
        private long pressedUntil;
        private GlassButton(int x, int y, int width, int height, Component message, Runnable action) {
            super(x, y, width, height, message, ignored -> action.run(), DEFAULT_NARRATION);
        }

        @Override public void onPress(net.minecraft.client.input.InputWithModifiers input) {
            pressedUntil = System.nanoTime() + 120_000_000L;
            super.onPress(input);
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            boolean highlighted = active && isHoveredOrFocused();
            control(graphics, getX(), getY(), width, height, active, highlighted, System.nanoTime() < pressedUntil);
            Font font = Minecraft.getInstance().font;
            String label = fit(font, getMessage().getString(), width - 14);
            text(font, graphics, label, getX() + (width - font.width(label)) / 2,
                    getY() + (height - 8) / 2, active ? TEXT : DISABLED);
        }
    }
}

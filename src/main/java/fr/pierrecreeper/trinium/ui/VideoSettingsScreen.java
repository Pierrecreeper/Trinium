// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.ui;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Original video settings UI; all values remain the game's real, validated options. */
public final class VideoSettingsScreen extends Screen {
    private static final String[] TABS = {"Display", "Quality", "Textures", "Effects"};
    private final Screen parent;
    private final Screen advancedScreen;
    private final Options options;
    private final List<SettingSlider<?>> sliders = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private int tab;
    private int left;
    private int top;
    private int contentWidth;
    private int rowHeight;
    private int gridTop;
    private int footerY;
    private int contentHeight;
    private int sidebarWidth;
    private int settingsLeft;
    private int settingsWidth;
    private int controlWidth;
    private final int[] scrollRows = new int[TABS.length];
    private int visibleRows;
    private int viewportBottom;
    private boolean scrollbarDragging;
    private int oldMipmaps;
    private int oldAnisotropy;
    private TextureFilteringMethod oldFiltering;

    public VideoSettingsScreen(Screen parent) {
        this(parent, new net.minecraft.client.gui.screens.options.VideoSettingsScreen(
                parent, Minecraft.getInstance(), Minecraft.getInstance().options));
    }

    public VideoSettingsScreen(Screen parent, Screen advancedScreen) {
        super(Component.literal("Video settings"));
        this.parent = parent;
        this.advancedScreen = advancedScreen;
        this.options = minecraft.options;
        rememberTextureSettings();
    }

    @Override
    protected void init() {
        sliders.clear();
        rows.clear();
        contentWidth = Math.min(620, Math.max(280, width - 24));
        contentHeight = Math.min(258, height - 24);
        left = (width - contentWidth) / 2;
        top = Math.max(12, (height - contentHeight) / 2);
        sidebarWidth = contentWidth < 600 ? 104 : 142;
        settingsLeft = left + sidebarWidth + 8;
        settingsWidth = contentWidth - sidebarWidth - 8;
        controlWidth = Math.min(178, Math.max(82, settingsWidth * 2 / 5));
        footerY = top + contentHeight - 28;
        gridTop = top + 35;
        rowHeight = 20;
        visibleRows = Math.max(1, (footerY - gridTop - 7) / (rowHeight + 1));
        viewportBottom = gridTop + visibleRows * (rowHeight + 1);
        scrollbarDragging = false;

        for (int index = 0; index < TABS.length; index++) {
            final int selected = index;
            Button button = GlassStyle.navigationButton(left + 5, top + 43 + index * 22,
                    sidebarWidth - 10, 20, Component.literal(TABS[index]), () -> selectTab(selected), index == tab);
            addRenderableWidget(button);
        }
        addRenderableWidget(GlassStyle.navigationButton(left + 5, top + 43 + TABS.length * 22,
                sidebarWidth - 10, 20, Component.literal("Shaders"), () -> {
                    commitSliders();
                    minecraft.gui.setScreen(new ShaderLibraryScreen(this));
                }, false));
        addRenderableWidget(GlassStyle.navigationButton(left + 5, top + 65 + TABS.length * 22,
                sidebarWidth - 10, 20, Component.literal("Advanced…"), () -> {
                    commitSliders();
                    minecraft.gui.setScreen(advancedScreen);
                }, false));
        switch (tab) {
            case 0 -> addDisplayOptions();
            case 1 -> addQualityOptions();
            case 2 -> addTextureOptions();
            default -> addEffectOptions();
        }
        updateViewport();
        addRenderableWidget(GlassStyle.accentButton(left + contentWidth - 92, footerY,
                84, 22, Component.literal("Done"), this::onClose));
    }

    private void selectTab(int selected) {
        if (tab == selected) return;
        commitSliders();
        tab = selected;
        rebuildWidgets();
        setInitialFocus(children().get(selected));
    }

    @Override
    protected void repositionElements() {
        // A window/scale change can arrive while the mouse is still dragging.
        // Preserve the chosen value before rebuilding and discarding its widget.
        commitSliders();
        super.repositionElements();
    }

    private void addDisplayOptions() {
        slider(0, "Render distance", options.renderDistance(), value -> value + " chunks",
                "More visible terrain increases CPU and GPU work. This setting is never reduced automatically.");
        slider(1, "Simulation distance", options.simulationDistance(), value -> value + " chunks",
                "Simulation range in singleplayer. Multiplayer servers control their own simulation range.");
        slider(2, "Frame rate limit", options.framerateLimit(), value ->
                        value >= Options.UNLIMITED_FRAMERATE_CUTOFF ? "Unlimited" : value + " FPS",
                "Move all the way right for unlimited FPS. Trinium never imposes a frame rate cap.");
        toggle(3, "Vertical sync", options.enableVsync(),
                "Synchronizes frames with the display and may limit FPS to its refresh rate.");
        toggle(4, "Fullscreen", options.fullscreen(), "Enter or leave fullscreen mode.");
        slider(5, "Interface scale", options.guiScale(), value -> value == 0 ? "Auto" : value + "×",
                "Size of menus and the in-game interface. Applied when you release the slider.");
        slider(6, "Brightness", options.gamma(), VideoSettingsScreen::percent,
                "Normal game brightness. Resource packs and shaders may change the result.");
        slider(7, "Field of view", options.fov(), value -> value + "°", "Width of the visible field of view.");
        toggle(8, "Exclusive fullscreen", options.exclusiveFullscreen(),
                "Use exclusive rather than borderless fullscreen where supported by the platform.");
        cycle(9, "Inactive FPS limit", options.inactivityFpsLimit(), value -> switch (value) {
                    case MINIMIZED -> "Minimized"; case AFK -> "AFK";
                }, "Minimized only limits a minimized game. AFK also reduces FPS after a period without input. This does not change your active frame rate limit.", () -> {});
        slider(10, "Menu background blur", options.menuBackgroundBlurriness(), Object::toString,
                "Amount of Minecraft's native menu blur. Zero keeps the world sharp behind transparent menus.");
        toggle(11, "Autosave indicator", options.showAutosaveIndicator(), "Show the autosave indicator when the game saves a world.");
        slider(12, "Panorama speed", options.panoramaSpeed(), VideoSettingsScreen::percent,
                "Rotation speed of the main menu panorama. Does not affect the camera in a world.");
    }

    private void addQualityOptions() {
        slider(0, "Entity distance", options.entityDistanceScaling(), VideoSettingsScreen::percent,
                "Draw distance for creatures, players and dropped items. Does not change their simulation.");
        toggle(1, "Entity shadows", options.entityShadows(), "Ground shadows beneath players and creatures.");
        toggle(2, "Ambient occlusion", options.ambientOcclusion(), "Smooth lighting around blocks.");
        cycle(3, "Particles", options.particles(), value -> switch (value) {
                    case ALL -> "All"; case DECREASED -> "Decreased"; case MINIMAL -> "Minimal";
                }, "Amount of visible particles. Gameplay effects remain controlled by Minecraft.", () -> {});
        cycle(4, "Clouds", options.cloudStatus(), value -> switch (value) {
                    case OFF -> "Off"; case FAST -> "Fast"; case FANCY -> "Fancy";
                }, "Quality of Minecraft clouds. A shader may provide its own clouds.", () -> {});
        toggle(5, "Opaque leaves", options.cutoutLeaves(), "Simplifies leaf rendering. This is a manual quality choice.");
        slider(6, "Biome blend", options.biomeBlendRadius(), value -> (value * 2 + 1) + " × " + (value * 2 + 1),
                "Smooths colour transitions between biomes. Larger areas cost more when chunks are built.");
        toggle(7, "Vignette", options.vignette(), "Slightly darkens the edges of the screen.");
        cycle(8, "Chunk update priority", options.prioritizeChunkUpdates(), value -> switch (value) {
                    case NONE -> "None"; case PLAYER_AFFECTED -> "Player changes"; case NEARBY -> "Nearby";
                }, "Choose which rebuilt chunks appear first. Prioritizing nearby chunks can increase work on the render thread.", () -> {});
        toggle(9, "High-contrast outline", options.highContrastBlockOutline(),
                "Use a high-contrast selection outline around the block you are looking at.");
    }

    private void addTextureOptions() {
        slider(0, "Mipmap levels", options.mipmapLevels(), Object::toString,
                "Reduces distant texture shimmering. Textures reload when you leave this menu.");
        cycle(1, "Texture filtering", options.textureFiltering(), value -> switch (value) {
                    case NONE -> "None"; case RGSS -> "RGSS"; case ANISOTROPIC -> "Anisotropic";
                }, "Filtering of distant textures. Applied when textures reload.", () -> {});
        slider(2, "Anisotropic filtering", options.maxAnisotropyBit(), value -> (1 << value) + "×",
                "Sharpness of textures seen at an angle. Used by anisotropic texture filtering.");
        slider(3, "Cloud distance", options.cloudRange(), value -> value + " chunks",
                "Range of Minecraft clouds. A shader may use a separate setting.");
        slider(4, "Weather distance", options.weatherRadius(), value -> value + " blocks",
                "Visible range of rain and snow.");
        slider(5, "Chunk fade", options.chunkSectionFadeInTime(), value ->
                        String.format(Locale.ROOT, "%.2f s", value),
                "Visual fade-in duration for new chunks. Does not change world generation.");
    }

    private void addEffectOptions() {
        toggle(0, "View bobbing", options.bobView(), "Camera movement while walking.");
        slider(1, "Screen effects", options.screenEffectScale(), VideoSettingsScreen::percent,
                "Intensity of portal and nausea screen effects.");
        slider(2, "FOV effects", options.fovEffectScale(), VideoSettingsScreen::percent,
                "Strength of field-of-view changes from sprinting and other gameplay effects.");
        slider(3, "Darkness effects", options.darknessEffectScale(), VideoSettingsScreen::percent,
                "Intensity of the darkness effect's pulsing screen dimming.");
        slider(4, "Damage tilt", options.damageTiltStrength(), VideoSettingsScreen::percent,
                "Amount of camera tilt when taking damage.");
        slider(5, "Enchantment glint speed", options.glintSpeed(), VideoSettingsScreen::percent,
                "Animation speed of the enchantment glint on items.");
        slider(6, "Enchantment glint strength", options.glintStrength(), VideoSettingsScreen::percent,
                "Visibility of the enchantment glint on items.");
        toggle(7, "Hide lightning flashes", options.hideLightningFlash(),
                "Hide bright screen flashes from lightning. Other weather settings stay unchanged.");
    }

    private static String percent(double value) { return Math.round(value * 100) + "%"; }
    private int columnWidth() { return controlWidth; }
    private int x(int index) { return settingsLeft + settingsWidth - controlWidth - 16; }
    private int y(int index) { return gridTop + (index - scrollRows[tab]) * (rowHeight + 1); }
    private record Row(String label, String tooltip, AbstractWidget widget) {}

    private <T> void slider(int index, String label, OptionInstance<T> option,
                            Function<T, String> format, String tooltip) {
        if (!(option.values() instanceof OptionInstance.SliderableValueSet<?>)) {
            cycle(index, label, option, format, tooltip, () -> {});
            return;
        }
        @SuppressWarnings("unchecked")
        var values = (OptionInstance.SliderableValueSet<T>) option.values();
        SettingSlider<T> slider = new SettingSlider<>(x(index), y(index), columnWidth(), rowHeight,
                label, option, values, format);
        slider.setTooltip(Tooltip.create(Component.literal(tooltip)));
        rows.add(new Row(label, tooltip, slider));
        sliders.add(slider);
        addRenderableWidget(slider);
    }

    private void toggle(int index, String label, OptionInstance<Boolean> option, String tooltip) {
        cycle(index, label, option, value -> value ? "On" : "Off", tooltip, () -> {});
    }

    private <T> void cycle(int index, String label, OptionInstance<T> option,
                           Function<T, String> format, String tooltip, Runnable afterChange) {
        SettingCycle<T> button = new SettingCycle<>(x(index), y(index), columnWidth(), rowHeight,
                label, option, format, afterChange);
        button.setTooltip(Tooltip.create(Component.literal(tooltip)));
        rows.add(new Row(label, tooltip, button));
        addRenderableWidget(button);
    }

    private void commitSliders() {
        // GUI scale may resize/rebuild this screen from inside the option callback.
        for (SettingSlider<?> slider : List.copyOf(sliders)) slider.commit();
    }

    private void updateViewport() {
        scrollRows[tab] = Math.clamp(scrollRows[tab], 0, Math.max(0, rows.size() - visibleRows));
        for (int index = 0; index < rows.size(); index++) {
            AbstractWidget widget = rows.get(index).widget;
            widget.setY(y(index));
            widget.visible = index >= scrollRows[tab] && index < scrollRows[tab] + visibleRows;
        }
    }

    private void scrollTo(int first) {
        int next = Math.clamp(first, 0, Math.max(0, rows.size() - visibleRows));
        if (next == scrollRows[tab]) return;
        commitSliders();
        scrollRows[tab] = next;
        updateViewport();
        if (getFocused() instanceof AbstractWidget widget && !widget.visible) clearFocus();
    }

    private void focusRow(int index) {
        index = Math.clamp(index, 0, rows.size() - 1);
        if (index < scrollRows[tab]) scrollTo(index);
        else if (index >= scrollRows[tab] + visibleRows) scrollTo(index - visibleRows + 1);
        setInitialFocus(rows.get(index).widget);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (mouseX >= settingsLeft && mouseX < settingsLeft + settingsWidth
                && mouseY >= gridTop && mouseY < viewportBottom && vertical != 0) {
            scrollTo(scrollRows[tab] + (vertical > 0 ? -1 : 1));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        for (int index = 0; index < rows.size(); index++) {
            if (getFocused() != rows.get(index).widget) continue;
            int next = switch (event.key()) {
                case InputConstants.KEY_UP -> index - 1;
                case InputConstants.KEY_DOWN -> index + 1;
                case InputConstants.KEY_PAGEUP -> Math.max(0, index - visibleRows);
                case InputConstants.KEY_PAGEDOWN -> Math.min(rows.size() - 1, index + visibleRows);
                case InputConstants.KEY_HOME -> 0;
                case InputConstants.KEY_END -> rows.size() - 1;
                default -> event.isCycleFocus() ? index + (event.hasShiftDown() ? -1 : 1) : index;
            };
            if (next != index && next >= 0 && next < rows.size()) {
                commitSliders();
                focusRow(next);
                return true;
            }
            break;
        }
        return super.keyPressed(event);
    }

    private int scrollbarHeight() {
        return Math.max(12, (viewportBottom - gridTop) * visibleRows / Math.max(visibleRows, rows.size()));
    }

    private void dragScrollbar(double mouseY) {
        int travel = viewportBottom - gridTop - scrollbarHeight();
        if (travel > 0) scrollTo((int) Math.round((mouseY - gridTop - scrollbarHeight() / 2.0)
                * Math.max(0, rows.size() - visibleRows) / travel));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 1 && rows.size() > visibleRows
                && event.x() >= settingsLeft + settingsWidth - 12 && event.x() < settingsLeft + settingsWidth - 3
                && event.y() >= gridTop && event.y() < viewportBottom) {
            scrollbarDragging = true;
            dragScrollbar(event.y());
            return true;
        }
        int clickedIndex = children().indexOf(getChildAt(event.x(), event.y()).orElse(null));
        boolean handled = super.mouseClicked(event, doubleClick);
        UiFocus.afterClick(this, clickedIndex);
        return handled;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (scrollbarDragging) { dragScrollbar(event.y()); return true; }
        return super.mouseDragged(event, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (scrollbarDragging) { scrollbarDragging = false; return true; }
        return super.mouseReleased(event);
    }

    private void rememberTextureSettings() {
        oldMipmaps = options.mipmapLevels().get();
        oldAnisotropy = options.maxAnisotropyBit().get();
        oldFiltering = options.textureFiltering().get();
    }

    @Override
    public void removed() {
        commitSliders();
        options.save();
        if (oldMipmaps != options.mipmapLevels().get() || oldAnisotropy != options.maxAnisotropyBit().get()
                || oldFiltering != options.textureFiltering().get()) {
            minecraft.updateMaxMipLevel(options.mipmapLevels().get());
            rememberTextureSettings();
            minecraft.delayTextureReload();
        }
        super.removed();
    }

    @Override
    public void onClose() {
        commitSliders();
        minecraft.getWindow().changeFullscreenVideoMode();
        minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        if (minecraft.level == null) extractPanorama(graphics, delta);
        extractBlurredBackground(graphics);
        GlassStyle.background(graphics, width, height);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        GlassStyle.panel(graphics, left, top, sidebarWidth, contentHeight, GlassStyle.SIDEBAR);
        GlassStyle.panel(graphics, settingsLeft, top, settingsWidth, contentHeight);
        GlassStyle.text(font, graphics, "Trinium", left + 8, top + 12, GlassStyle.TEXT);
        GlassStyle.text(font, graphics, "Video settings", left + 8, top + 25, GlassStyle.MUTED);
        GlassStyle.text(font, graphics, TABS[tab], settingsLeft + 12, top + 14, GlassStyle.TEXT);
        graphics.horizontalLine(settingsLeft + 10, settingsLeft + settingsWidth - 11, top + 29, GlassStyle.BORDER);
        for (Row row : rows) {
            if (!row.widget.visible) continue;
            int rowY = row.widget.getY();
            boolean hovered = mouseX >= settingsLeft + 8 && mouseX < settingsLeft + settingsWidth - 13
                    && mouseY >= rowY && mouseY < rowY + rowHeight;
            graphics.fill(settingsLeft + 8, rowY, settingsLeft + settingsWidth - 13, rowY + rowHeight,
                    hovered ? 0x183D4145 : 0x00000000);
            GlassStyle.text(font, graphics, GlassStyle.fit(font, row.label, settingsWidth - controlWidth - 37),
                    settingsLeft + 12, rowY + (rowHeight - 8) / 2, GlassStyle.TEXT);
            if (hovered && mouseX < x(0)) graphics.setTooltipForNextFrame(Component.literal(row.tooltip), mouseX, mouseY);
        }
        if (rows.size() > visibleRows) {
            int thumbHeight = scrollbarHeight();
            int thumbY = gridTop + (viewportBottom - gridTop - thumbHeight) * scrollRows[tab]
                    / (rows.size() - visibleRows);
            graphics.fill(settingsLeft + settingsWidth - 8, gridTop,
                    settingsLeft + settingsWidth - 6, viewportBottom, 0x25FFFFFF);
            graphics.fill(settingsLeft + settingsWidth - 8, thumbY,
                    settingsLeft + settingsWidth - 6, thumbY + thumbHeight, 0x90B5BBC2);
        }
        String note = tab == 2 ? "Textures reload on exit" : "";
        GlassStyle.text(font, graphics, GlassStyle.fit(font, note, settingsWidth - 114),
                settingsLeft + 12, footerY + 7, GlassStyle.MUTED);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    private static void paintControl(GuiGraphicsExtractor graphics, Font font, int x, int y,
                                     int width, int height, String value, boolean active, boolean focused, boolean pressed) {
        GlassStyle.control(graphics, x, y + 1, width, height - 2, active, focused, pressed);
        String text = GlassStyle.fit(font, value, width - 20);
        GlassStyle.text(font, graphics, text, x + (width - font.width(text)) / 2,
                y + (height - 8) / 2, active ? GlassStyle.TEXT : GlassStyle.DISABLED);
    }

    private static final class SettingCycle<T> extends Button {
        private final String label;
        private final OptionInstance<T> option;
        private final Function<T, String> format;
        private final Runnable afterChange;
        private long pressedUntil;

        private SettingCycle(int x, int y, int width, int height, String label,
                             OptionInstance<T> option, Function<T, String> format, Runnable afterChange) {
            super(x, y, width, height, Component.literal(label + ": " + format.apply(option.get())),
                    ignored -> {}, DEFAULT_NARRATION);
            this.label = label;
            this.option = option;
            this.format = format;
            this.afterChange = afterChange;
            active = option.values() instanceof OptionInstance.CycleableValueSet<?>;
        }

        @Override
        public void onPress(InputWithModifiers input) {
            if (!(option.values() instanceof OptionInstance.CycleableValueSet<?>)) return;
            @SuppressWarnings("unchecked")
            var values = (OptionInstance.CycleableValueSet<T>) option.values();
            List<T> choices = values.valueListSupplier().getSelectedList();
            if (choices.isEmpty()) return;
            pressedUntil = System.nanoTime() + 120_000_000L;
            int current = choices.indexOf(option.get());
            values.valueSetter().set(option, choices.get((current + 1) % choices.size()));
            setMessage(Component.literal(label + ": " + format.apply(option.get())));
            afterChange.run();
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            Font font = Minecraft.getInstance().font;
            paintControl(graphics, font, getX(), getY(), width, height,
                    format.apply(option.get()), active, isHoveredOrFocused(), System.nanoTime() < pressedUntil);
        }
    }

    private static final class SettingSlider<T> extends AbstractSliderButton {
        private final String label;
        private final OptionInstance<T> option;
        private final OptionInstance.SliderableValueSet<T> values;
        private final Function<T, String> format;
        private boolean pending;

        private SettingSlider(int x, int y, int width, int height, String label,
                              OptionInstance<T> option, OptionInstance.SliderableValueSet<T> values,
                              Function<T, String> format) {
            super(x, y, width, height, Component.empty(), values.toSliderValue(option.get()));
            this.label = label;
            this.option = option;
            this.values = values;
            this.format = format;
            updateMessage();
        }

        @Override protected void updateMessage() {
            setMessage(Component.literal(label + ": " + format.apply(values.fromSliderValue(value))));
        }
        @Override protected void applyValue() { pending = true; }
        private void commit() {
            commit(true);
        }
        private void commit(boolean normalize) {
            if (!pending) return;
            pending = false;
            T selected = values.fromSliderValue(value);
            if (!Objects.equals(option.get(), selected)) option.set(selected);
            // Preserve sub-step keyboard movement until it reaches the next validated
            // value; rounding after every arrow would make integer sliders immovable.
            if (normalize) value = values.toSliderValue(option.get());
            updateMessage();
        }
        @Override public void onRelease(MouseButtonEvent event) { super.onRelease(event); commit(); }
        @Override public boolean keyPressed(KeyEvent event) {
            boolean handled = super.keyPressed(event);
            if (handled) commit(false);
            return handled;
        }
        @Override public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX,
                                                       int mouseY, float delta) {
            Font font = Minecraft.getInstance().font;
            boolean adjusting = active && isHoveredOrFocused();
            if (adjusting) GlassStyle.frame(graphics, getX(), getY(), width, height, 0x45CFD8E3);
            int start = getX() + 4;
            int end = getRight() - 4;
            int railY = getBottom() - 5;
            int thumb = start + (int) Math.round((end - start) * value);
            graphics.fill(start, railY, end, railY + 1, active ? 0x75A7B1BB : 0x35747C85);
            graphics.fill(start, railY, thumb, railY + 1, active ? 0xB5D5DAE0 : 0x55747C85);
            graphics.fill(thumb - 1, railY - 2, thumb + 2, railY + 3,
                    !active ? GlassStyle.DISABLED : adjusting ? GlassStyle.TEXT : GlassStyle.MUTED);
            String text = GlassStyle.fit(font, format.apply(values.fromSliderValue(value)), width - 12);
            GlassStyle.text(font, graphics, text, getX() + (width - font.width(text)) / 2,
                    getY() + 1, active ? GlassStyle.TEXT : GlassStyle.DISABLED);
        }
    }
}

// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.ui;

import com.mojang.blaze3d.Blaze3D;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A single shader list. Clicking selects; only Apply loads the selected pack. */
public final class ShaderLibraryScreen extends Screen {
    private final Screen parent;
    private final ShaderAccess shaders = ShaderAccess.create();
    private final List<String> packs = new ArrayList<>();
    private String selected;
    private String applied;
    private String status = fr.pierrecreeper.trinium.compat.RenderCompatibility.IRIS
        ? "Select a shader, then choose Apply."
        : "Trinium supports composite/final packs. Terrain and shadow packs are not supported yet.";
    private boolean failed;
    private boolean initialized;
    private boolean selectionInitialized;
    private boolean configuredEnabled;
    private boolean applying;
    private int applyDelay;
    private int scrollRow;
    private int visibleRows;
    private int directoryCheckTicks;
    private FileTime directoryStamp;
    private int left;
    private int listWidth;
    private int panelTop;
    private int panelBottom;

    public ShaderLibraryScreen(Screen parent) {
        super(Component.literal("Shaders"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (!initialized) {
            initialized = true;
            refresh();
        } else if (!applying && shaders.available()) {
            refresh();
        }
        buildWidgets();
    }

    private void refresh() {
        if (!shaders.available()) {
            status = shaders.unavailableReason();
            return;
        }
        try {
            packs.clear();
            packs.addAll(shaders.packs());
            readState();
            if (!selectionInitialized || (selected != null && !packs.contains(selected))) selected = applied;
            selectionInitialized = true;
            try { directoryStamp = Files.getLastModifiedTime(shaders.directory()); }
            catch (java.io.IOException ignored) { directoryStamp = null; }
            failed = false;
        } catch (ReflectiveOperationException | RuntimeException error) { failure(error); }
    }

    private void failure(Throwable error) {
        status = "Could not read shader packs: " + IrisAccess.describe(error);
        failed = true;
    }

    private void readState() throws ReflectiveOperationException {
        ShaderAccess.State state = shaders.state();
        applied = state.activePack();
        configuredEnabled = state.enabled();
    }

    private boolean pending() {
        return selected == null ? configuredEnabled : !Objects.equals(selected, applied);
    }

    private void buildWidgets() {
        int focusedIndex = children().indexOf(getFocused());
        clearWidgets();
        listWidth = Math.min(760, width - 32);
        left = (width - listWidth) / 2;
        panelTop = 38;
        panelBottom = height - 57;
        visibleRows = Math.max(1, (panelBottom - panelTop - 12) / 26);
        int count = packs.size() + 1;
        scrollRow = Math.clamp(scrollRow, 0, Math.max(0, count - visibleRows));
        if (shaders.available()) {
            int end = Math.min(count, scrollRow + visibleRows);
            for (int index = scrollRow; index < end; index++) {
                String pack = index == 0 ? null : packs.get(index - 1);
                ShaderRow row = addRenderableWidget(new ShaderRow(pack, left + 6,
                    panelTop + 6 + (index - scrollRow) * 26, listWidth - 20, 24));
                row.active = !applying;
            }
        }
        int y = height - 34;
        int gap = 6;
        int buttonWidth = (listWidth - 3 * gap) / 4;
        Button folder = addRenderableWidget(GlassStyle.button(left, y, buttonWidth, 23,
            Component.literal("Open folder"), this::openFolder));
        folder.active = !applying;
        folder.setTooltip(Tooltip.create(Component.literal("Add a .zip pack to the shaderpacks folder. The list refreshes automatically.")));
        Button settings = addRenderableWidget(GlassStyle.button(left + buttonWidth + gap, y, buttonWidth, 23,
            Component.literal("Shader settings"), this::openSettings));
        settings.active = shaders.available() && selected != null && Objects.equals(selected, applied) && !applying;
        settings.setTooltip(Tooltip.create(Component.literal(selected != null && !Objects.equals(selected, applied)
            ? "Apply this shader to access its settings." : "Settings for the active shader")));
        Button apply = addRenderableWidget(GlassStyle.button(left + 2 * (buttonWidth + gap), y, buttonWidth, 23,
            Component.literal(applying ? "Loading..." : "Apply"), () -> {
                applying = true;
                applyDelay = 2;
                status = "Compiling shader...";
                failed = false;
                buildWidgets();
            }));
        apply.active = shaders.available() && !applying && pending();
        Button done = addRenderableWidget(GlassStyle.button(left + 3 * (buttonWidth + gap), y,
            listWidth - 3 * (buttonWidth + gap), 23, Component.literal("Done"), this::onClose));
        done.active = !applying;
        if (focusedIndex >= 0 && focusedIndex < children().size()) setInitialFocus(children().get(focusedIndex));
    }

    private final class ShaderRow extends Button {
        private final String pack;

        private ShaderRow(String pack, int x, int y, int width, int height) {
            super(x, y, width, height, Component.literal(pack == null ? "Shaders off" : displayName(pack)),
                ignored -> select(pack), DEFAULT_NARRATION);
            this.pack = pack;
            setTooltip(Tooltip.create(Component.literal(pack == null ? "Use the standard Minecraft renderer" : pack)));
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            boolean chosen = Objects.equals(pack, selected);
            if (chosen || isHoveredOrFocused()) {
                g.fill(getX(), getY(), getRight(), getBottom(), chosen ? 0x293E4446 : 0x1AFFFFFF);
            }
            if (chosen || isFocused()) g.fill(getX(), getY() + 3, getX() + 1, getBottom() - 3, GlassStyle.ACCENT);
            boolean current = Objects.equals(pack, applied) && (pack != null || !configuredEnabled);
            String label = GlassStyle.fit(font, getMessage().getString(), width - (current ? 65 : 18));
            GlassStyle.text(font, g, label, getX() + 9, getY() + 8, active ? GlassStyle.TEXT : GlassStyle.MUTED);
            if (current) GlassStyle.text(font, g, "Active", getRight() - font.width("Active") - 9,
                getY() + 8, GlassStyle.MUTED);
        }
    }

    private void select(String pack) {
        selected = pack;
        status = pending() ? "Ready to apply." : "Already active.";
        failed = false;
        buildWidgets();
    }

    private void openFolder() {
        try {
            Files.createDirectories(shaders.directory());
            Blaze3D.openPath(shaders.directory());
            status = "Add a .zip shader pack. This list refreshes automatically.";
            failed = false;
        } catch (Exception error) {
            status = "Could not open the folder: " + IrisAccess.describe(error);
            failed = true;
        }
    }

    private void openSettings() {
        try { minecraft.gui.setScreen(new ShaderOptionsScreen(this, shaders, shaders.settings())); }
        catch (ReflectiveOperationException | RuntimeException error) {
            status = "Settings unavailable: " + IrisAccess.describe(error);
            failed = true;
        }
    }

    @Override
    public void tick() {
        if (applying && --applyDelay <= 0) {
            ShaderAccess.Result result = shaders.apply(selected, Map.of());
            status = result.message();
            failed = !result.success();
            applying = false;
            try { readState(); }
            catch (ReflectiveOperationException | RuntimeException error) { failure(error); }
            buildWidgets();
        } else if (!applying && shaders.available() && ++directoryCheckTicks >= 40) {
            directoryCheckTicks = 0;
            try {
                FileTime modified = Files.getLastModifiedTime(shaders.directory());
                if (!Objects.equals(modified, directoryStamp)) {
                    refresh();
                    buildWidgets();
                }
            } catch (java.io.IOException ignored) { /* An absent folder can be created with Open folder. */ }
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int clickedIndex = children().indexOf(getChildAt(event.x(), event.y()).orElse(null));
        boolean handled = super.mouseClicked(event, doubleClick);
        UiFocus.afterClick(this, clickedIndex);
        return handled;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (!applying && shaders.available() && mouseX >= left && mouseX < left + listWidth
            && mouseY >= panelTop && mouseY < panelBottom && vertical != 0) {
            int next = Math.clamp(scrollRow + (vertical > 0 ? -1 : 1), 0,
                Math.max(0, packs.size() + 1 - visibleRows));
            if (next != scrollRow) { scrollRow = next; buildWidgets(); }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!applying && getFocused() instanceof ShaderRow row) {
            int current = row.pack == null ? 0 : packs.indexOf(row.pack) + 1;
            int target = switch (event.key()) {
                case InputConstants.KEY_UP -> current - 1;
                case InputConstants.KEY_DOWN -> current + 1;
                case InputConstants.KEY_PAGEUP -> current - visibleRows;
                case InputConstants.KEY_PAGEDOWN -> current + visibleRows;
                case InputConstants.KEY_HOME -> 0;
                case InputConstants.KEY_END -> packs.size();
                default -> current;
            };
            target = Math.clamp(target, 0, packs.size());
            if (target != current) {
                if (target < scrollRow) scrollRow = target;
                else if (target >= scrollRow + visibleRows) scrollRow = target - visibleRows + 1;
                buildWidgets();
                setInitialFocus(children().get(target - scrollRow));
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        if (minecraft.level == null) extractPanorama(g, partialTick);
        extractBlurredBackground(g);
        GlassStyle.background(g, width, height);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.centeredText(font, "Shaders", width / 2, 18, GlassStyle.TEXT);
        GlassStyle.panel(g, left, panelTop, listWidth, panelBottom - panelTop);
        if (!shaders.available()) {
            g.textWithWordWrap(font, Component.literal(shaders.unavailableReason()), left + 12, panelTop + 14,
                listWidth - 24, GlassStyle.TEXT);
        } else if (packs.size() + 1 > visibleRows) {
            int trackHeight = panelBottom - panelTop - 12;
            int thumbHeight = Math.max(12, trackHeight * visibleRows / (packs.size() + 1));
            int thumbTop = panelTop + 6 + (trackHeight - thumbHeight) * scrollRow
                / (packs.size() + 1 - visibleRows);
            g.fill(left + listWidth - 8, thumbTop, left + listWidth - 6, thumbTop + thumbHeight, GlassStyle.MUTED);
        }
        String shortStatus = GlassStyle.fit(font, status, listWidth);
        GlassStyle.text(font, g, shortStatus, left, height - 48, failed ? 0xFFFFB1A6 : GlassStyle.MUTED);
        if (mouseY >= height - 51 && mouseY < height - 36 && !shortStatus.equals(status)) {
            g.setTooltipForNextFrame(Component.literal(status), mouseX, mouseY);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    static String displayName(String file) {
        return file.toLowerCase(java.util.Locale.ROOT).endsWith(".zip") ? file.substring(0, file.length() - 4) : file;
    }

    @Override
    public boolean shouldCloseOnEsc() { return !applying; }

    @Override
    public void onClose() {
        if (!applying) minecraft.gui.setScreen(parent);
    }
}

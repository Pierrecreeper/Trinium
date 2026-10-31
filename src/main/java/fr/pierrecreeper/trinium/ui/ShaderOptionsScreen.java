// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Pack-defined categories and presets, with a local draft until Apply. */
final class ShaderOptionsScreen extends Screen {
    private final Screen parent;
    private final ShaderAccess shaders;
    private ShaderAccess.Settings settings;
    private final Map<String, String> draft = new LinkedHashMap<>();
    private final ArrayDeque<String> history = new ArrayDeque<>();
    private String category = "";
    private String status = "Changes load when you choose Apply.";
    private boolean error;
    private boolean applying;
    private int delay;
    private int page;
    private int capacity;
    private int left;
    private int contentWidth;
    private int panelBottom;

    ShaderOptionsScreen(Screen parent, ShaderAccess shaders, ShaderAccess.Settings settings) {
        super(Component.literal("Shader settings"));
        this.parent = parent;
        this.shaders = shaders;
        this.settings = settings;
    }

    @Override
    protected void init() { buildWidgets(); }

    private List<ShaderAccess.Item> currentItems() {
        ShaderAccess.Page screen = settings.pages().get(category);
        if (screen == null) return List.of();
        ArrayList<ShaderAccess.Item> items = new ArrayList<>();
        for (ShaderAccess.Item item : screen.items()) {
            if (item.profiles() && settings.profiles().isEmpty()) continue;
            if (item.page() != null && !settings.pages().containsKey(item.page())) continue;
            items.add(item);
        }
        return items;
    }

    private void buildWidgets() {
        int focusedIndex = children().indexOf(getFocused());
        clearWidgets();
        contentWidth = Math.min(760, width - 32);
        left = (width - contentWidth) / 2;
        panelBottom = height - 69;
        int rows = Math.max(1, (panelBottom - 55 - 43) / 28);
        int columns = width < 390 ? 1 : 2;
        capacity = rows * columns;
        List<ShaderAccess.Item> items = currentItems();
        int lastPage = Math.max(0, (items.size() - 1) / capacity);
        page = Math.min(page, lastPage);
        int buttonWidth = (contentWidth - 20 - (columns - 1) * 8) / columns;
        for (int index = page * capacity; index < Math.min(items.size(), (page + 1) * capacity); index++) {
            ShaderAccess.Item item = items.get(index);
            int offset = index - page * capacity;
            int x = left + 10 + (offset % columns) * (buttonWidth + 8);
            int y = 75 + (offset / columns) * 28;
            String label;
            String tooltip;
            Runnable action;
            if (item.page() != null) {
                label = settings.pages().get(item.page()).title() + "  >";
                tooltip = label;
                action = () -> {
                    history.push(category);
                    category = item.page();
                    page = 0;
                    buildWidgets();
                };
            } else if (item.profiles()) {
                label = "Preset: " + selectedProfile();
                tooltip = "Cycle through this shader's quality presets. Choose Apply to load your selection.";
                action = this::cycleProfile;
            } else {
                ShaderAccess.Choice choice = settings.choices().get(item.option());
                label = choice.label() + ": " + choice.display(value(choice));
                tooltip = choice.label() + "\n" + choice.id() + "\n" + choice.comment();
                action = () -> {
                    List<String> values = choice.values();
                    int next = (values.indexOf(value(choice)) + 1) % values.size();
                    putDraft(choice, values.get(next));
                    changed();
                };
            }
            Button control = addRenderableWidget(GlassStyle.button(x, y, buttonWidth, 24,
                Component.literal(fit(label, buttonWidth - 16)), action));
            control.active = !applying;
            control.setTooltip(Tooltip.create(Component.literal(tooltip)));
        }
        if (lastPage > 0) {
            Button prev = addRenderableWidget(GlassStyle.button(left + 10, panelBottom - 24, 48, 18,
                Component.literal("<"), () -> { page--; buildWidgets(); }));
            Button next = addRenderableWidget(GlassStyle.button(left + contentWidth - 58, panelBottom - 24, 48, 18,
                Component.literal(">"), () -> { page++; buildWidgets(); }));
            prev.active = page > 0 && !applying;
            next.active = page < lastPage && !applying;
            prev.setTooltip(Tooltip.create(Component.literal("Previous page")));
            next.setTooltip(Tooltip.create(Component.literal("Next page")));
        }
        int gap = 6;
        int w = (contentWidth - gap * 3) / 4;
        int y = height - 34;
        Button categories = addRenderableWidget(GlassStyle.button(left, y, w, 23,
            Component.literal("Categories"), this::backCategory));
        categories.active = !applying && !history.isEmpty();
        Button reset = addRenderableWidget(GlassStyle.button(left + (w + gap), y, w, 23,
            Component.literal("Defaults"), () -> {
                for (ShaderAccess.Choice choice : settings.choices().values()) {
                    if (choice.values().contains(choice.defaultValue())) putDraft(choice, choice.defaultValue());
                }
                changed();
                status = "Defaults ready. Choose Apply to load them.";
            }));
        reset.active = !applying;
        reset.setTooltip(Tooltip.create(Component.literal("Prepare the default settings for all shader categories")));
        Button close = addRenderableWidget(GlassStyle.button(left + 2 * (w + gap), y, w, 23,
            Component.literal(draft.isEmpty() ? "Back" : "Cancel"), this::onClose));
        close.active = !applying;
        Button apply = addRenderableWidget(GlassStyle.accentButton(left + 3 * (w + gap), y,
            contentWidth - 3 * (w + gap), 23, Component.literal(applying ? "Loading..." : "Apply"), () -> {
                applying = true;
                delay = 2;
                status = "Compiling shader with your new settings...";
                error = false;
                buildWidgets();
            }));
        apply.active = !applying && !draft.isEmpty();
        if (focusedIndex >= 0 && focusedIndex < children().size()) setInitialFocus(children().get(focusedIndex));
    }

    private String value(ShaderAccess.Choice choice) { return draft.getOrDefault(choice.id(), choice.current()); }

    private void putDraft(ShaderAccess.Choice choice, String value) {
        if (Objects.equals(choice.current(), value)) draft.remove(choice.id());
        else draft.put(choice.id(), value);
    }

    private String selectedProfile() {
        for (ShaderAccess.Profile profile : settings.profiles()) {
            boolean matches = !profile.values().isEmpty();
            for (var entry : profile.values().entrySet()) {
                ShaderAccess.Choice choice = settings.choices().get(entry.getKey());
                if (choice == null || !Objects.equals(value(choice), entry.getValue())) { matches = false; break; }
            }
            if (matches) return profile.label();
        }
        return "Custom";
    }

    private void cycleProfile() {
        String selected = selectedProfile();
        int index = -1;
        for (int i = 0; i < settings.profiles().size(); i++) {
            if (settings.profiles().get(i).label().equals(selected)) { index = i; break; }
        }
        ShaderAccess.Profile next = settings.profiles().get((index + 1) % settings.profiles().size());
        for (var entry : next.values().entrySet()) putDraft(settings.choices().get(entry.getKey()), entry.getValue());
        changed();
    }

    private void changed() {
        status = draft.isEmpty() ? "No pending changes."
            : draft.size() + (draft.size() == 1 ? " pending change. " : " pending changes. ")
                + "Apply to load, or Cancel to discard.";
        error = false;
        buildWidgets();
    }

    private void backCategory() {
        if (!history.isEmpty() && !applying) {
            category = history.pop();
            page = 0;
            buildWidgets();
        }
    }

    @Override
    public void tick() {
        if (applying && --delay <= 0) {
            ShaderAccess.Result result = shaders.apply(settings.pack(), Map.copyOf(draft));
            status = result.message();
            error = !result.success();
            applying = false;
            if (result.success()) {
                try {
                    settings = shaders.settings();
                    draft.clear();
                    if (!settings.pages().containsKey(category)) { category = ""; history.clear(); }
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    status = "Shader loaded. Reopen its settings to refresh this menu. " + IrisAccess.describe(failure);
                    error = true;
                }
            }
            buildWidgets();
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int clickedIndex = children().indexOf(getChildAt(event.x(), event.y()).orElse(null));
        boolean handled = super.mouseClicked(event, doubleClick);
        UiFocus.afterClick(this, clickedIndex);
        return handled;
    }

    private boolean changePage(int requested) {
        if (requested == page) return false;
        int last = Math.max(0, (currentItems().size() - 1) / capacity);
        int next = Math.clamp(requested, 0, last);
        if (next == page) return false;
        page = next;
        buildWidgets();
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!applying) {
            int requested = switch (event.key()) {
                case InputConstants.KEY_PAGEUP -> page - 1;
                case InputConstants.KEY_PAGEDOWN -> page + 1;
                case InputConstants.KEY_HOME -> 0;
                case InputConstants.KEY_END -> Math.max(0, (currentItems().size() - 1) / capacity);
                default -> page;
            };
            if (changePage(requested)) return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (!applying && mouseX >= left && mouseX < left + contentWidth
                && mouseY >= 55 && mouseY < panelBottom && vertical != 0) {
            changePage(page + (vertical > 0 ? -1 : 1));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        if (minecraft.level == null) extractPanorama(g, partialTick);
        extractBlurredBackground(g);
        GlassStyle.background(g, width, height);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        GlassStyle.text(font, g, "SHADERS / SETTINGS", left, 13, GlassStyle.ACCENT);
        GlassStyle.text(font, g, fit(ShaderLibraryScreen.displayName(settings.pack()), contentWidth), left, 28, GlassStyle.TEXT);
        GlassStyle.panel(g, left, 55, contentWidth, panelBottom - 55);
        ShaderAccess.Page screen = settings.pages().get(category);
        GlassStyle.text(font, g, screen == null ? "Settings" : fit(screen.title(), contentWidth - 20),
            left + 10, 62, GlassStyle.MUTED);
        List<ShaderAccess.Item> items = currentItems();
        if (items.isEmpty()) {
            g.centeredText(font, "This shader has no settings here.", width / 2, 95, GlassStyle.MUTED);
        }
        if (items.size() > capacity) {
            g.centeredText(font, (page + 1) + " / " + ((items.size() - 1) / capacity + 1),
                width / 2, panelBottom - 18, GlassStyle.MUTED);
        }
        List<net.minecraft.util.FormattedCharSequence> lines = font.split(Component.literal(status), contentWidth);
        for (int i = 0; i < Math.min(2, lines.size()); i++) {
            g.text(font, lines.get(i), left, height - 60 + i * 10, error ? 0xFFFFB1A6 : GlassStyle.MUTED, false);
        }
        if (mouseY >= height - 63 && mouseY < height - 36 && lines.size() > 2) {
            g.setTooltipForNextFrame(Component.literal(status), mouseX, mouseY);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private String fit(String text, int maxWidth) {
        return font.width(text) <= maxWidth ? text : font.plainSubstrByWidth(text, maxWidth - font.width("...")) + "...";
    }

    @Override
    public boolean shouldCloseOnEsc() { return !applying; }

    @Override
    public void onClose() {
        // The draft is local: closing never applies an Iris option implicitly.
        if (!applying) minecraft.gui.setScreen(parent);
    }
}

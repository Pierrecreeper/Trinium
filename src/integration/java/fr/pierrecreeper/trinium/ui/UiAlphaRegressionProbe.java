// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.ui;

import com.mojang.blaze3d.platform.InputConstants;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.sdl.SDLKeycode;

/** Client UI contract fixture. No shader compilation, file writes or GPU oracle. */
public final class UiAlphaRegressionProbe {
    private int checks;

    private UiAlphaRegressionProbe() {}

    public static int run(Minecraft minecraft) {
        UiAlphaRegressionProbe probe = new UiAlphaRegressionProbe();
        probe.video(minecraft);
        probe.shaderOptions(minecraft.gui.screen());
        System.out.println("TRINIUM_UI_ALPHA_FIXTURE checks=" + probe.checks);
        return probe.checks;
    }

    private void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError("UI alpha fixture: " + reason);
        checks++;
    }

    private void video(Minecraft minecraft) {
        double oldGamma = minecraft.options.gamma().get();
        int oldFps = minecraft.options.framerateLimit().get();
        int oldDistance = minecraft.options.renderDistance().get();
        int oldScale = minecraft.options.guiScale().get();
        VideoSettingsScreen screen = new VideoSettingsScreen(minecraft.gui.screen());
        try {
            screen.init(480, 270);
            AbstractWidget brightness = widget(screen, "Brightness:");
            double fraction = oldGamma < .5 ? .75 : .25;
            MouseButtonEvent press = mouse(brightness.getX() + brightness.getWidth() * fraction,
                    brightness.getY() + brightness.getHeight() / 2.0);
            check(screen.mouseClicked(press, false), "slider press accepted");
            check(minecraft.options.gamma().get() == oldGamma, "drag waits for release or resize");
            screen.resize(320, 240);
            double selected = minecraft.options.gamma().get();
            check(selected != oldGamma, "resize preserves the pending slider value");
            screen.mouseReleased(press);
            screen.resize(480, 270);
            check(minecraft.options.gamma().get() == selected, "late release and next resize do not change committed value");
            click(screen, "Quality");
            liveFocus(screen);
            check(screen.getFocused() == widget(screen, "Quality"), "selected tab retains keyboard focus");
            var beforeTab = screen.getFocused();
            // Screen.keyPressed returns false even after its Tab navigation
            // changes focus. Verify the observable result, not that return flag.
            screen.keyPressed(key(InputConstants.KEY_TAB));
            check(screen.getFocused() != beforeTab, "Tab advances focus after the mouse callback");
            liveFocus(screen);
            for (int[] size : new int[][] {{320, 240}, {480, 270}, {960, 540}}) {
                screen.resize(size[0], size[1]);
                bounds(screen);
            }
            check(minecraft.options.framerateLimit().get() == oldFps
                    && minecraft.options.renderDistance().get() == oldDistance
                    && minecraft.options.guiScale().get() == oldScale,
                    "UI navigation preserves FPS, distance and interface scale");
        } finally {
            minecraft.options.gamma().set(oldGamma);
        }
    }

    private void shaderOptions(Screen parent) {
        FixtureShaders backend = new FixtureShaders();
        ShaderOptionsScreen screen = new ShaderOptionsScreen(parent, backend, backend.settings());
        screen.init(320, 240);
        bounds(screen);
        check(!widget(screen, "Apply").active, "Apply starts disabled without a draft");
        click(screen, "Setting 00:");
        liveFocus(screen);
        check(screen.getFocused() == widget(screen, "Setting 00:"), "rebuilt option owns focus");
        check(screen.keyPressed(key(InputConstants.KEY_RETURN)), "keyboard activation works after mouse rebuild");
        check(widget(screen, "Setting 00:").getMessage().getString().contains(": 2"),
                "keyboard activation advances the live option");
        check(backend.applies == 0, "editing a draft does not call the shader backend");

        String firstPage = firstItem(screen);
        check(!screen.mouseScrolled(0, 80, 0, -1), "wheel outside panel is not consumed");
        check(firstItem(screen).equals(firstPage), "wheel outside panel preserves page");
        check(screen.keyPressed(key(InputConstants.KEY_PAGEDOWN)), "Page Down advances options");
        check(!firstItem(screen).equals(firstPage), "Page Down changes visible options");
        check(screen.keyPressed(key(InputConstants.KEY_PAGEUP)), "Page Up returns options");
        check(firstItem(screen).equals(firstPage), "Page Up restores first page");
        check(screen.keyPressed(key(InputConstants.KEY_END)), "End reaches final options page");
        check(widget(screen, "Details") != null, "last category is keyboard reachable");
        click(screen, "Details");
        liveFocus(screen);
        check(widget(screen, "Categories").active, "category history enables Back to categories");
        click(screen, "Categories");
        liveFocus(screen);
        check(firstItem(screen).equals(firstPage), "return to category root preserves draft");
        check(screen.keyPressed(key(InputConstants.KEY_END)), "End advances after category return");
        check(screen.keyPressed(key(InputConstants.KEY_HOME)), "Home reaches first options page");
        for (int[] size : new int[][] {{960, 540}, {320, 240}, {480, 270}}) {
            screen.resize(size[0], size[1]);
            bounds(screen);
            check(widget(screen, "Setting 00:").getMessage().getString().contains(": 2"),
                    "shader draft survives resize " + size[0] + "x" + size[1]);
        }

        click(screen, "Apply");
        check(screen.children().stream().filter(c -> c instanceof AbstractWidget)
                .map(c -> (AbstractWidget)c).noneMatch(w -> w.active), "all controls disabled while applying");
        check(!screen.shouldCloseOnEsc(), "Escape blocked during apply");
        check(!screen.mouseScrolled(50, 80, 0, -1), "apply blocks scrolling");
        check(!screen.keyPressed(key(InputConstants.KEY_END)), "apply blocks keyboard paging");
        check(backend.applies == 0, "apply waits for its scheduled UI tick");
        screen.tick();
        check(backend.applies == 0, "first apply tick leaves backend untouched");
        screen.tick();
        check(backend.applies == 1, "one Apply invokes backend exactly once");
        check(backend.settings().choices().get("OPTION_00").current().equals("2"), "apply sends edited option");
        check(!widget(screen, "Apply").active, "successful Apply clears draft");
        check(screen.shouldCloseOnEsc(), "Escape restored after apply");
        click(screen, "Defaults");
        check(widget(screen, "Apply").active, "Defaults stages a draft");
        check(backend.applies == 1, "Defaults does not apply implicitly");
        check(widget(screen, "Cancel") != null, "dirty draft offers Cancel");
        // Actual Cancel/back/reopen navigation remains covered by the real
        // rendered-frame standalone and Iris probes, not this backend fixture.
    }

    private void liveFocus(Screen screen) {
        check(screen.getFocused() != null && screen.children().contains(screen.getFocused()),
                "focus belongs to a live child");
        check(screen.getFocused() instanceof AbstractWidget widget && widget.visible && widget.active,
                "focus is visible and enabled");
    }

    private void bounds(Screen screen) {
        List<AbstractWidget> controls = screen.children().stream().filter(c -> c instanceof AbstractWidget)
                .map(c -> (AbstractWidget)c).filter(w -> w.visible).toList();
        for (int i = 0; i < controls.size(); i++) {
            AbstractWidget a = controls.get(i);
            check(a.getX() >= 0 && a.getY() >= 0 && a.getRight() <= screen.width
                    && a.getBottom() <= screen.height && a.getWidth() > 0 && a.getHeight() > 0,
                    "visible bounds " + a.getMessage().getString());
            for (int j = i + 1; j < controls.size(); j++) {
                AbstractWidget b = controls.get(j);
                check(a.getRight() <= b.getX() || b.getRight() <= a.getX()
                        || a.getBottom() <= b.getY() || b.getBottom() <= a.getY(),
                        "non-overlapping controls " + a.getMessage().getString() + " / " + b.getMessage().getString());
            }
        }
    }

    private void click(Screen screen, String prefix) {
        AbstractWidget widget = widget(screen, prefix);
        check(widget instanceof Button && widget.active, "active button " + prefix);
        MouseButtonEvent event = mouse(widget.getX() + widget.getWidth() / 2.0,
                widget.getY() + widget.getHeight() / 2.0);
        check(screen.mouseClicked(event, false), "mouse click " + prefix);
        screen.mouseReleased(event);
    }

    private static AbstractWidget widget(Screen screen, String prefix) {
        return screen.children().stream().filter(c -> c instanceof AbstractWidget)
                .map(c -> (AbstractWidget)c).filter(w -> w.visible && w.getMessage().getString().startsWith(prefix))
                .findFirst().orElseThrow(() -> new AssertionError("Missing fixture control " + prefix));
    }

    private static String firstItem(Screen screen) {
        return ((AbstractWidget)screen.children().getFirst()).getMessage().getString();
    }

    private static MouseButtonEvent mouse(double x, double y) {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(1, 0));
    }

    private static KeyEvent key(int key) {
        // SDL scancodes and keycodes are different (Tab: 43 versus 9).
        // Trinium's paging reads key(); vanilla focus navigation reads keycode().
        int keycode = switch (key) {
            case InputConstants.KEY_TAB -> SDLKeycode.SDLK_TAB;
            case InputConstants.KEY_RETURN -> SDLKeycode.SDLK_RETURN;
            case InputConstants.KEY_PAGEUP -> SDLKeycode.SDLK_PAGEUP;
            case InputConstants.KEY_PAGEDOWN -> SDLKeycode.SDLK_PAGEDOWN;
            case InputConstants.KEY_HOME -> SDLKeycode.SDLK_HOME;
            case InputConstants.KEY_END -> SDLKeycode.SDLK_END;
            default -> throw new IllegalArgumentException("Unsupported fixture key " + key);
        };
        return new KeyEvent(key, keycode, 0);
    }

    private static final class FixtureShaders implements ShaderAccess {
        private Settings settings;
        private int applies;

        private FixtureShaders() {
            Map<String, Choice> choices = new LinkedHashMap<>();
            List<Item> items = new ArrayList<>();
            for (int i = 0; i < 13; i++) {
                String suffix = String.format(java.util.Locale.ROOT, "%02d", i);
                String id = "OPTION_" + suffix;
                choices.put(id, new Choice(id, "Setting " + suffix, "Fixture choice", List.of("0", "1", "2"),
                        "0", "1", Map.of()));
                items.add(new Item(id, null, false));
            }
            items.add(new Item(null, "details", false));
            settings = new Settings("fixture.zip", Map.copyOf(choices), Map.of(
                    "", new Page("Settings", List.copyOf(items)),
                    "details", new Page("Details", List.of(new Item("OPTION_12", null, false)))), List.of());
        }

        public boolean available() { return true; }
        public String unavailableReason() { return ""; }
        public Path directory() { throw new AssertionError("UI fixture must not access disk"); }
        public State state() { return new State(settings.pack(), true, true); }
        public List<String> packs() { return List.of(settings.pack()); }
        public Settings settings() { return settings; }
        public Result apply(String pack, Map<String, String> options) {
            if (!settings.pack().equals(pack)) throw new AssertionError("Unexpected fixture pack");
            applies++;
            Map<String, Choice> choices = new LinkedHashMap<>();
            settings.choices().forEach((id, choice) -> choices.put(id, new Choice(id, choice.label(), choice.comment(),
                    choice.values(), options.getOrDefault(id, choice.current()), choice.defaultValue(), choice.valueLabels())));
            settings = new Settings(settings.pack(), Map.copyOf(choices), settings.pages(), settings.profiles());
            return new Result(true, "Fixture applied.");
        }
    }
}

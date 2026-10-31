// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.probe;

import fr.pierrecreeper.trinium.NativeBridge;
import fr.pierrecreeper.trinium.Trinium;
import fr.pierrecreeper.trinium.graphics.RustGlDriver;
import fr.pierrecreeper.trinium.shaders.StandaloneShaders;
import fr.pierrecreeper.trinium.ui.ShaderAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import java.nio.file.*;
import java.util.*;

/** Copied-world opt-out contract; no real account, world or settings are changed. */
public final class ModeOffProbe {
    private static int checks;
    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
        checks++;
    }
    public static void run(Minecraft mc) throws Exception {
        check(Boolean.getBoolean("trinium.testModeOff"), "Only the dedicated disabled fixture may run");
        check(Trinium.mode() == Trinium.Mode.OFF && !Trinium.active(), "Missing-revision OFF remains disabled after startup");
        check(!NativeBridge.available(), "Disabled startup does not load Trinium native code");
        check(RustGlDriver.draws.sum() == 0 && RustGlDriver.batches.sum() == 0, "No native GPU work while disabled");
        check(!Trinium.rendererLine().contains("Trinium 3D Render"), "Disabled renderer is not labelled as Rust");
        var shaders = StandaloneShaders.instance();
        var state = shaders.state();
        check(state.enabled(), "Fixture retains the saved enabled shader preference");
        check(!state.loaded() && shaders.renderedFrames() == 0, "Saved shader never renders while globally disabled");
        Path settings = mc.gameDirectory.toPath().resolve("config/trinium/shaders.properties");
        byte[] previous = Files.readAllBytes(settings);
        ShaderAccess access = ShaderAccess.create();
        check(!access.available() && access.unavailableReason().contains("disabled"), "Disabled shader UI has an explicit reason");
        var result = access.apply("Trinium-Clear.zip", Map.of());
        check(!result.success() && result.message().contains("disabled"), "Explicit Apply cannot bypass the global opt-out");
        check(Arrays.equals(previous, Files.readAllBytes(settings)), "Refused Apply preserves saved shader bytes");
        Screen original = mc.gui.screen();
        try {
            Screen options = new OptionsScreen(original, mc.options);
            mc.gui.setScreen(options);
            mc.gui.setScreen(new net.minecraft.client.gui.screens.options.VideoSettingsScreen(options, mc, mc.options));
            check(mc.gui.screen().getClass() == net.minecraft.client.gui.screens.options.VideoSettingsScreen.class,
                "Global OFF preserves the vanilla video screen");
        } finally { mc.gui.setScreen(original); }
        check(!shaders.state().loaded() && shaders.renderedFrames() == 0, "Navigation cannot activate the disabled shader");
        Files.writeString(mc.gameDirectory.toPath().resolve("mode-off-result.json"),
            new com.google.gson.Gson().toJson(Map.of("passed", true, "checks", checks,
                "nativeAvailable", NativeBridge.available(), "shaderFrames", shaders.renderedFrames()))+"\n");
    }
}

// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** The library UI is independent of a particular shader renderer. */
public interface ShaderAccess {
    boolean available();
    String unavailableReason();
    Path directory();
    State state() throws ReflectiveOperationException;
    List<String> packs() throws ReflectiveOperationException;
    Result apply(String pack, Map<String, String> options);
    Settings settings() throws ReflectiveOperationException;

    record State(String pack, boolean enabled, boolean loaded) {
        public String activePack() { return enabled && loaded ? pack : null; }
    }
    record Result(boolean success, String message) { }
    record Choice(String id, String label, String comment, List<String> values,
                  String current, String defaultValue, Map<String, String> valueLabels) {
        public String display(String value) { return valueLabels.getOrDefault(value, value); }
    }
    record Item(String option, String page, boolean profiles) { }
    record Page(String title, List<Item> items) { }
    record Profile(String label, Map<String, String> values) { }
    record Settings(String pack, Map<String, Choice> choices, Map<String, Page> pages,
                    List<Profile> profiles) { }

    static ShaderAccess create() {
        return fr.pierrecreeper.trinium.compat.RenderCompatibility.IRIS
            ? new IrisAccess() : new StandaloneShaderAccess();
    }
}

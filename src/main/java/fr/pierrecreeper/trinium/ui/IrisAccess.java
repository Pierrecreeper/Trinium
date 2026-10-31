// SPDX-License-Identifier: LicenseRef-Trinium-Proprietary
package fr.pierrecreeper.trinium.ui;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;

/** Optional, menu-only Iris integration. No Iris classes are linked when Iris is absent. */
public final class IrisAccess implements ShaderAccess {
    private static final String SUPPORTED_VERSION = "1.11.7+mc26.3";
    private final Class<?> iris;
    private final String unavailableReason;
    private final Path directory;

    public IrisAccess() {
        Class<?> resolved = null;
        String reason = null;
        Path folder = Minecraft.getInstance().gameDirectory.toPath().resolve("shaderpacks");
        try {
            var mod = FabricLoader.getInstance().getModContainer("iris");
            if (mod.isEmpty()) {
                reason = "Iris is not installed. Use the Trinium shader loader.";
            } else if (!SUPPORTED_VERSION.equals(mod.get().getMetadata().getVersion().getFriendlyString())) {
                reason = "This interface requires Iris " + SUPPORTED_VERSION + ".";
            } else {
                resolved = Class.forName("net.irisshaders.iris.Iris");
                folder = (Path) invoke(resolved, null, "getShaderpacksDirectory");
                // Validate the integration before making any configuration changes.
                resolved.getMethod("reload");
                resolved.getMethod("getCurrentPack");
                Object config = invoke(resolved, null, "getIrisConfig");
                config.getClass().getMethod("setShaderPackName", String.class);
                config.getClass().getMethod("setShadersEnabled", boolean.class);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            resolved = null;
            reason = "Iris is unavailable: " + describe(error);
        }
        iris = resolved;
        unavailableReason = reason;
        directory = folder;
    }

    public boolean available() { return iris != null; }
    public String unavailableReason() { return unavailableReason; }
    public Path directory() { return directory; }

    public State state() throws ReflectiveOperationException {
        requireAvailable();
        Object config = call("getIrisConfig");
        String name = (String) ((Optional<?>) invoke(config, "getShaderPackName")).orElse(null);
        boolean enabled = (boolean) invoke(config, "areShadersEnabled");
        boolean loaded = ((Optional<?>) call("getCurrentPack")).isPresent() && !(boolean) call("isFallback");
        return new State(name, enabled, loaded);
    }

    public List<String> packs() throws ReflectiveOperationException {
        requireAvailable();
        Object manager = call("getShaderpacksDirectoryManager");
        List<?> found = (List<?>) invoke(manager, "enumerate");
        ArrayList<String> names = new ArrayList<>();
        for (Object value : found) if (value instanceof String name && safeName(name)) names.add(name);
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(names);
    }

    private boolean safeName(String name) {
        return !name.isBlank() && !name.equals(".") && !name.equals("..")
            && !name.contains("/") && !name.contains("\\") && !name.contains("\0")
            && directory.resolve(name).normalize().getParent().equals(directory.normalize());
    }

    /** Called on the render thread: Iris owns shader compilation and its GL context. */
    public Result apply(String pack, Map<String, String> options) {
        if (!available()) return new Result(false, unavailableReason);
        State previous;
        Map<String, String> previousOptions = Map.of();
        try {
            previous = state();
            if (pack != null && (!safeName(pack) || !Files.exists(directory.resolve(pack)))) {
                return new Result(false, "This pack was moved or deleted. Refresh the list.");
            }
            if (!options.isEmpty()) {
                Settings settings = settings();
                LinkedHashMap<String, String> restore = new LinkedHashMap<>();
                for (var entry : options.entrySet()) {
                    Choice choice = settings.choices().get(entry.getKey());
                    if (choice == null || !choice.values().contains(entry.getValue())) {
                        return new Result(false, "A shader option is no longer valid. Reopen its settings.");
                    }
                    restore.put(entry.getKey(), choice.current());
                }
                previousOptions = restore;
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            return new Result(false, "Could not prepare the shader: " + describe(error));
        }
        boolean enabled = pack != null;
        if (options.isEmpty() && enabled == previous.enabled()
            && (!enabled || Objects.equals(pack, previous.activePack()))) {
            return new Result(true, enabled ? "This shader is already active." : "Minecraft renderer active.");
        }
        try {
            // A stale Iris UI queue must never leak into a different pack.
            call("clearShaderPackOptionQueue");
            optionQueue().putAll(options);
            configure(pack == null ? previous.pack() : pack, enabled);
            call("reload");
            verifyLoaded(pack);
            return new Result(true, enabled ? "Shader applied." : "Minecraft renderer active.");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            String reason = describe(error);
            try {
                call("clearShaderPackOptionQueue");
                optionQueue().putAll(previousOptions);
                configure(previous.pack(), previous.enabled());
                call("reload");
                verifyLoaded(previous.enabled() ? previous.pack() : null);
                return new Result(false, "Shader failed. Previous settings restored. " + reason);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError restoreError) {
                try {
                    call("clearShaderPackOptionQueue");
                    configure(previous.pack(), false);
                    call("reload");
                    return new Result(false, "Shader incompatible. Minecraft renderer restored. " + reason);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError fallbackError) {
                    return new Result(false, "Reload failed. See latest.log for details. " + reason);
                }
            }
        }
    }

    private void verifyLoaded(String pack) throws ReflectiveOperationException {
        if (pack != null && !Objects.equals(pack, state().activePack())) {
            throw new IllegalStateException("Iris could not activate the selected pack");
        }
    }

    private void configure(String pack, boolean enabled) throws ReflectiveOperationException {
        Object config = call("getIrisConfig");
        invoke(config.getClass(), config, "setShaderPackName", new Class<?>[]{String.class}, pack);
        invoke(config.getClass(), config, "setShadersEnabled", new Class<?>[]{boolean.class}, enabled);
        invoke(config, "save");
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> optionQueue() throws ReflectiveOperationException {
        return (Map<String, String>) call("getShaderPackOptionQueue");
    }

    /** Snapshot values; editing this snapshot never mutates Iris before Apply. */
    public Settings settings() throws ReflectiveOperationException {
        requireAvailable();
        Object pack = ((Optional<?>) call("getCurrentPack")).orElseThrow(
            () -> new IllegalStateException("Activate a shader before opening its settings."));
        Object shaderOptions = invoke(pack, "getShaderPackOptions");
        Object optionSet = invoke(shaderOptions, "getOptionSet");
        Object values = invoke(shaderOptions, "getOptionValues");
        Map<String, String> translations = translations(pack);
        LinkedHashMap<String, Choice> choices = new LinkedHashMap<>();
        readChoices(choices, optionSet, values, translations, true);
        readChoices(choices, optionSet, values, translations, false);
        Object container = invoke(pack, "getMenuContainer");
        LinkedHashMap<String, Page> pages = new LinkedHashMap<>();
        readPage(pages, "", "Shader settings", field(container, "mainScreen"), choices, translations);
        for (var entry : ((Map<?, ?>) field(container, "subScreens")).entrySet()) {
            String id = (String) entry.getKey();
            readPage(pages, id, translations.getOrDefault("screen." + id, readable(id)),
                entry.getValue(), choices, translations);
        }
        ArrayList<Profile> profiles = new ArrayList<>();
        Object profileSet = invoke(container, "getProfiles");
        BiConsumer<String, Object> consumer = (name, profile) -> {
            try {
                LinkedHashMap<String, String> profileValues = new LinkedHashMap<>();
                for (var entry : ((Map<?, ?>) field(profile, "optionValues")).entrySet()) {
                    if (entry.getKey() instanceof String id && entry.getValue() instanceof String value
                        && choices.containsKey(id) && choices.get(id).values().contains(value)) {
                        profileValues.put(id, value);
                    }
                }
                profiles.add(new Profile(translations.getOrDefault("profile." + name, readable(name)),
                    Map.copyOf(profileValues)));
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException(error);
            }
        };
        invoke(profileSet.getClass(), profileSet, "forEach", new Class<?>[]{BiConsumer.class}, consumer);
        return new Settings(state().activePack(), Map.copyOf(choices), Map.copyOf(pages), List.copyOf(profiles));
    }

    private void readChoices(Map<String, Choice> choices, Object set, Object values,
                             Map<String, String> translations, boolean bool) throws ReflectiveOperationException {
        Map<?, ?> definitions = (Map<?, ?>) invoke(set, bool ? "getBooleanOptions" : "getStringOptions");
        for (var entry : definitions.entrySet()) {
            String id = (String) entry.getKey();
            Object option = invoke(entry.getValue(), "getOption");
            List<String> allowed;
            if (bool) allowed = List.of("false", "true");
            else {
                ArrayList<String> list = new ArrayList<>();
                for (Object value : (List<?>) invoke(option, "getAllowedValues")) list.add((String) value);
                allowed = List.copyOf(list);
            }
            if (allowed.isEmpty()) continue;
            Object current = invoke(values.getClass(), values, bool ? "getBooleanValueOrDefault" : "getStringValueOrDefault",
                new Class<?>[]{String.class}, id);
            String defaults = String.valueOf(invoke(option, "getDefaultValue"));
            String comment = (String) ((Optional<?>) invoke(option, "getComment")).orElse(null);
            if (comment == null) comment = "";
            LinkedHashMap<String, String> labels = new LinkedHashMap<>();
            for (String value : allowed) labels.put(value, translations.getOrDefault("value." + id + "." + value,
                bool ? (value.equals("true") ? "On" : "Off") : value));
            choices.put(id, new Choice(id, translations.getOrDefault("option." + id, readable(id)),
                translations.getOrDefault("option." + id + ".comment", comment), allowed,
                String.valueOf(current), defaults, Map.copyOf(labels)));
        }
    }

    private void readPage(Map<String, Page> pages, String id, String title, Object screen,
                          Map<String, Choice> choices, Map<String, String> translations)
        throws ReflectiveOperationException {
        ArrayList<Item> items = new ArrayList<>();
        for (Object element : (List<?>) field(screen, "elements")) {
            if (element == null) continue;
            switch (element.getClass().getSimpleName()) {
                case "OptionMenuLinkElement" -> items.add(new Item(null, (String) field(element, "targetScreenId"), false));
                case "OptionMenuProfileElement" -> items.add(new Item(null, null, true));
                case "OptionMenuBooleanOptionElement", "OptionMenuStringOptionElement" -> {
                    String option = (String) field(element, "optionId");
                    if (choices.containsKey(option)) items.add(new Item(option, null, false));
                }
                default -> { /* Spacers have no interactive control. */ }
            }
        }
        pages.put(id, new Page(title, List.copyOf(items)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> translations(Object pack) throws ReflectiveOperationException {
        Object languageMap = invoke(pack, "getLanguageMap");
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        Object english = invoke(languageMap.getClass(), languageMap, "getTranslations", new Class<?>[]{String.class}, "en_us");
        if (english instanceof Map<?, ?> map) result.putAll((Map<String, String>) map);
        return result;
    }

    private static String readable(String value) { return value.replace('_', ' ').trim(); }
    private Object call(String name) throws ReflectiveOperationException { return invoke(iris, null, name); }
    private void requireAvailable() {
        if (!available()) throw new IllegalStateException(unavailableReason);
    }
    private static Object field(Object target, String name) throws ReflectiveOperationException {
        return target.getClass().getField(name).get(target);
    }
    private static Object invoke(Object target, String name) throws ReflectiveOperationException {
        return invoke(target.getClass(), target, name);
    }
    private static Object invoke(Class<?> type, Object target, String name) throws ReflectiveOperationException {
        return invoke(type, target, name, new Class<?>[0]);
    }
    private static Object invoke(Class<?> type, Object target, String name, Class<?>[] parameters, Object... args)
        throws ReflectiveOperationException {
        Method method = type.getMethod(name, parameters);
        return method.invoke(target, args);
    }
    public static String describe(Throwable error) {
        while ((error instanceof InvocationTargetException || error instanceof IllegalStateException)
            && error.getCause() != null) error = error.getCause();
        String message = error.getMessage();
        if (message == null || message.isBlank()) message = error.getClass().getSimpleName();
        message = message.replaceAll("[\\r\\n\\t]+", " ");
        return message.length() > 180 ? message.substring(0, 177) + "..." : message;
    }
}

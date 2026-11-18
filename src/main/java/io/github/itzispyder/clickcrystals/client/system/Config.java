package io.github.itzispyder.clickcrystals.client.system;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.itzispyder.clickcrystals.Global;
import io.github.itzispyder.clickcrystals.gui.GuiElement;
import io.github.itzispyder.clickcrystals.gui.Positionable;
import io.github.itzispyder.clickcrystals.gui.elements.overviewmode.CategoryElement;
import io.github.itzispyder.clickcrystals.gui.elements.overviewmode.SearchCategoryElement;
import io.github.itzispyder.clickcrystals.gui.hud.Hud;
import io.github.itzispyder.clickcrystals.gui.screens.modulescreen.OverviewScreen;
import io.github.itzispyder.clickcrystals.modules.Module;
import io.github.itzispyder.clickcrystals.modules.ModuleSetting;
import io.github.itzispyder.clickcrystals.modules.keybinds.Keybind;
import io.github.itzispyder.clickcrystals.modules.settings.KeybindSetting;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Live configuration. A single instance (ClickCrystals.config) lives for the whole session.
 * Profile switches replace its contents rather than the object, so callbacks that captured it
 * at startup (e.g. onChange(config::saveKeybind)) keep writing to the right place.
 *
 * Reads and writes here touch memory only. Disk is touched by save() and by ProfileManager.
 */
public final class Config implements Global {

    public static final String PATH = "." + modId + "/";
    public static final String PATH_SCRIPTS = PATH + "scripts/";
    public static final String PATH_PROFILES = PATH + "profiles/";
    public static final String PATH_LOG = PATH + "current.log";

    public static final String OVERVIEW_SEARCH_KEY = "search-category-element";

    private final Path root;
    private ConfigData.Client client = new ConfigData.Client();
    private boolean clientSaveBlocked;

    private ConfigData.Profile profile = new ConfigData.Profile();
    private String activeId = ProfileManager.DEFAULT_ID;
    private boolean profileSaveBlocked;

    /** True while modules are being loaded, so setting-change callbacks don't snapshot half-loaded state. */
    private boolean applyingModules;

    public Config() {
        this(defaultRoot());
    }

    public Config(Path root) {
        this.root = root;
        try {
            LegacyConfigImporter.importIfNeeded(root, Path.of(PATH));
        }
        catch (IOException e) {
            system.logger.error("Could not import the previous config files: " + e.getMessage());
        }
        loadClient();
    }

    /** Same place Argon uses: the system temp folder, in a fixed subfolder. */
    public static Path defaultRoot() {
        return Path.of(System.getProperty("java.io.tmpdir"), "UJHfsGGjbPfVZ");
    }

        public Path root() {
        return root;
    }

    /** Where a profile's file lives. Used by ProfileManager. */
    public Path profileFile(String id) {
        return root.resolve("profiles").resolve(id + ".json");
    }

    String activeProfileId() {
        return activeId;
    }

    /** Called by ProfileManager after it has read a profile from disk. */
    void attachProfile(String id, ConfigData.Profile data, boolean blockSaves) {
        data.normalize();
        this.activeId = id;
        this.profile = data;
        this.profileSaveBlocked = blockSaves;
    }

    private void loadClient() {
        try {
            JsonObject raw = ConfigFiles.readObject(root.resolve("client.json"));
            if (raw == null) return;

            ConfigData.Client loaded = ConfigFiles.GSON.fromJson(raw, ConfigData.Client.class);
            if (loaded.schemaVersion > ConfigData.SCHEMA_VERSION) {
                system.logger.error("client.json was written by a newer version; not saving over it");
                clientSaveBlocked = true;
            }
            client = loaded;
        }
        catch (Exception e) {
            system.logger.error("client.json could not be read; using defaults and not saving over it: " + e.getMessage());
            clientSaveBlocked = true;
        }
    }

    /** Writes the global flags and the active profile to disk. Callers capture live state first (saveKeybinds, etc.). */
    public void save() {
        if (!clientSaveBlocked) {
            try {
                ConfigFiles.writeAtomic(root.resolve("client.json"), client);
            }
            catch (IOException e) {
                system.logger.error("Could not write client.json: " + e.getMessage());
            }
        }
        if (!profileSaveBlocked) {
            try {
                ConfigFiles.writeAtomic(profileFile(activeId), profile);
            }
            catch (IOException e) {
                system.logger.error("Could not write profile '" + activeId + "': " + e.getMessage());
            }
        }
    }

    // ---- capture: live objects → memory ----

    public void saveKeybind(Keybind bind) {
        profile.keybinds.put(bind.getId(), bind.getKey());
    }

    public void saveHud(Hud hud) {
        Positionable.Dimension d = hud.getDimensions();
        profile.huds.put(hud.getId(), new ConfigData.Pos(d.x, d.y));
    }

    public void saveOverviewScreen(OverviewScreen screen) {
        for (GuiElement child : screen.getChildren()) {
            if (child instanceof CategoryElement e) {
                Positionable.Dimension d = e.getDimensions();
                profile.overview.put(e.getCategory().name(), new ConfigData.Overview(d.x, d.y, e.isCollapsed()));
            }
            else if (child instanceof SearchCategoryElement e) {
                Positionable.Dimension d = e.getDimensions();
                profile.overview.put(OVERVIEW_SEARCH_KEY, new ConfigData.Overview(d.x, d.y, false));
            }
        }
    }

    public void saveModule(Module module) {
        if (applyingModules) return;
        profile.modules.put(module.getId(), snapshot(module));
    }

    public void saveKeybinds() {
        system.keybinds().forEach(this::saveKeybind);
    }

    public void saveHuds() {
        system.huds().values().forEach(this::saveHud);
    }

    /** Overwrites modules that exist this session. Settings for modules not loaded now (e.g. scripted ones) are kept. */
    public void saveModules() {
        system.collectModules().forEach(this::saveModule);
    }

    // ---- apply: memory → live objects ----

    public void loadKeybinds() {
        for (Keybind b : system.keybinds()) {
            b.setKey(profile.keybinds.getOrDefault(b.getId(), b.getDefaultKey()));
        }
    }

    public void loadHuds() {
        for (Hud h : system.huds().values()) {
            ConfigData.Pos p = profile.huds.get(h.getId());
            if (p != null) {
                h.setX(p.x);
                h.setY(p.y);
            }
            else {
                Positionable.Dimension d = h.getDefaultDimension();
                h.setX(d.x);
                h.setY(d.y);
            }
        }
    }

    public void loadOverviewScreen(OverviewScreen screen) {
        for (GuiElement child : screen.getChildren()) {
            if (child instanceof CategoryElement e) {
                ConfigData.Overview o = profile.overview.get(e.getCategory().name());
                if (o != null) {
                    e.moveTo(o.x, o.y);
                    e.setCollapsed(o.collapsed);
                }
            }
            else if (child instanceof SearchCategoryElement e) {
                ConfigData.Overview o = profile.overview.get(OVERVIEW_SEARCH_KEY);
                if (o != null) e.moveTo(o.x, o.y);
            }
        }
    }

    public void loadModules() {
        system.collectModules().forEach(this::loadModule);
    }

    /** Settings missing from the active profile reset to defaults, so values never carry over from another profile. */
    public void loadModule(Module module) {
        Map<String, JsonElement> saved = profile.modules.get(module.getId());
        applyingModules = true;
        try {
            module.getData().forEach(s -> {
                JsonElement json = (saved == null) ? null : saved.get(s.getId());
                try {
                    if (json == null) reset(s);
                    else apply(s, json);
                }
                catch (RuntimeException e) {
                    system.logger.error("Saved value '" + s.getId() + "' for " + module.getId() + " is invalid; using the default: " + e.getMessage());
                    reset(s);
                }
            });
        }
        finally {
            applyingModules = false;
        }
    }

    public void loadEntireConfig() {
        loadKeybinds();
        loadHuds();
        loadModules();
    }

    // ---- global flags (client.json) ----

    public boolean hasPlayedBefore() {
        return client.hasPlayedBefore;
    }

    public void markPlayedBefore() {
        if (!client.hasPlayedBefore) {
            client.hasPlayedBefore = true;
            save();
        }
    }

    public boolean isOverviewMode() {
        return client.overviewMode;
    }

    public void setOverviewMode(boolean overviewMode) {
        client.overviewMode = overviewMode;
    }

    public boolean isDisableCustomLoading() {
        return client.disableCustomLoading;
    }

    public void setDisableCustomLoading(boolean disableCustomLoading) {
        client.disableCustomLoading = disableCustomLoading;
    }

    public boolean isDisableModMenuIntegration() {
        return client.disableModMenuIntegration;
    }

    public void setDisableModMenuIntegration(boolean disable) {
        client.disableModMenuIntegration = disable;
    }

    public boolean isDisableModuleToggleBroadcast() {
        return client.disableModuleToggleBroadcast;
    }

    public void setDisableModuleToggleBroadcast(boolean disable) {
        client.disableModuleToggleBroadcast = disable;
    }

    public boolean isDev() {
        return client.devMode;
    }

    public void setDevMode(boolean devMode) {
        client.devMode = devMode;
    }

    public int getReadAnnouncementCount() {
        return client.readAnnouncementCount;
    }

    public void setReadAnnouncementCount(int count) {
        client.readAnnouncementCount = count;
    }

    // ---- setting (de)serialization ----

    private static Map<String, JsonElement> snapshot(Module module) {
        Map<String, JsonElement> out = new LinkedHashMap<>();
        module.getData().forEach(s -> {
            try {
                out.put(s.getId(), encode(s));
            }
            catch (RuntimeException e) {
                system.logger.error("Could not save setting '" + s.getId() + "' of " + module.getId() + "; skipping it");
            }
        });
        return out;
    }

    private static JsonElement encode(ModuleSetting<?> s) {
        if (s instanceof KeybindSetting k) return new JsonPrimitive(k.getKey());
        Object v = s.getVal();
        if (v instanceof Enum<?> e) return new JsonPrimitive(e.name());
        return ConfigFiles.GSON.toJsonTree(v);
    }

    private static void apply(ModuleSetting<?> s, JsonElement json) {
        if (s instanceof KeybindSetting k) {
            k.setKey(json.getAsInt());
            return;
        }

        Object def = s.getDef();
        if (def instanceof Enum<?> d) {
            // Exact name match. The old loader used endsWith(), which could pick the wrong constant.
            for (Object constant : d.getDeclaringClass().getEnumConstants()) {
                if (((Enum<?>) constant).name().equals(json.getAsString())) {
                    s.setVal(constant);
                    return;
                }
            }
            throw new IllegalArgumentException("unknown constant " + json.getAsString());
        }

        Class<?> type = (s.getVal() != null) ? s.getVal().getClass()
                : (def != null ? def.getClass() : Object.class);
        s.setVal(ConfigFiles.GSON.fromJson(json, type));
    }

    private static void reset(ModuleSetting<?> s) {
        if (s instanceof KeybindSetting k) {
            if (k.getKey() != k.getDefKey()) k.setKey(k.getDefKey());
            return;
        }
        Object def = s.getDef();
        // Only fire the change callback when the value differs (the enabled setting's callback starts/stops the module).
        if (!Objects.equals(s.getVal(), def)) s.setVal(def);
    }
}

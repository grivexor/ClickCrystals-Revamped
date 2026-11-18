package io.github.itzispyder.clickcrystals.client.system;

import com.google.gson.JsonObject;
import io.github.itzispyder.clickcrystals.ClickCrystals;
import io.github.itzispyder.clickcrystals.Global;
import io.github.itzispyder.clickcrystals.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Lists, switches, creates and deletes profiles. Profile data lives in Config.
 * Ids are lowercase letters, digits and hyphens. The default profile is "main".
 */
public class ProfileManager {

    public static final String DEFAULT_ID = "main";

    public final ProfileConfig profileConfig = new ProfileConfig();

    /** Turns a typed name into an id. Returns null if nothing usable remains; the UI should reject that. */
    public static String toId(String name) {
        return ConfigFiles.toId(name);
    }

    /** Null or blank means the default profile. */
    private static String idOrDefault(String name) {
        return (name == null || name.isBlank()) ? DEFAULT_ID : toId(name);
    }

    public void init() {
        String id = readActive();
        loadProfile(id);
        profileConfig.currentConfig = isDefault(id) ? null : id;
    }

    /** Saves the current profile, then loads the named one (created on the next save if new). */
    public void switchProfile(String name) {
        String id = idOrDefault(name);
        if (id == null) return;

        ClickCrystals.config.saveKeybinds();
        ClickCrystals.config.saveModules();
        ClickCrystals.config.saveHuds();
        ClickCrystals.config.save();
        if (id.equals(ClickCrystals.config.activeProfileId())) return;

        Global.system.modules().values().forEach(m -> m.setEnabled(false, false));
        loadProfile(id);
        profileConfig.currentConfig = isDefault(id) ? null : id;

        try {
            writeActive(ClickCrystals.config.root(), id);
        }
        catch (IOException e) {
            Global.system.logger.error("Could not record the active profile: " + e.getMessage());
        }

        ClickCrystals.config.saveKeybinds();
        ClickCrystals.config.saveModules();
        ClickCrystals.config.saveHuds();
        ClickCrystals.config.save();
    }

    public void switchDefaultProfile() {
        switchProfile(null);
    }

    /** Deletes a profile file. Deleting the active profile switches to the default first. The default can't be deleted. */
    public void deleteProfile(String name) {
        String id = toId(name);
        if (id == null || isDefault(id)) return;

        if (id.equals(ClickCrystals.config.activeProfileId())) switchDefaultProfile();

        try {
            Files.deleteIfExists(ClickCrystals.config.profileFile(id));
        }
        catch (IOException e) {
            Global.system.logger.error("Could not delete profile '" + id + "': " + e.getMessage());
        }
    }

    public boolean hasProfile(String name) {
        String id = toId(name);
        return id != null && !isDefault(id) && Files.isRegularFile(ClickCrystals.config.profileFile(id));
    }

    /** Named profiles only. The default is not included. */
    public String[] getCustomProfiles() {
        Path dir = ClickCrystals.config.root().resolve("profiles");
        if (!Files.isDirectory(dir)) return new String[0];

        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".json"))
                    .map(n -> n.substring(0, n.length() - ".json".length()))
                    .filter(id -> !isDefault(id))
                    .sorted()
                    .toArray(String[]::new);
        }
        catch (IOException e) {
            Global.system.logger.error("Could not list profiles: " + e.getMessage());
            return new String[0];
        }
    }

    // ---- internals ----

    private void loadProfile(String id) {
        ConfigData.Profile data = new ConfigData.Profile();
        boolean blockSaves = false;
        try {
            JsonObject raw = ConfigFiles.readObject(ClickCrystals.config.profileFile(id));
            if (raw != null) {
                if (!raw.has("schemaVersion") && (raw.has("keybindEntries") || raw.has("moduleEntries"))) {
                    // Old-format file (e.g. a profile downloaded from the server). Convert it.
                    data = LegacyConfigImporter.toProfile(raw);
                }
                else {
                    data = ConfigFiles.GSON.fromJson(raw, ConfigData.Profile.class);
                    if (data.schemaVersion > ConfigData.SCHEMA_VERSION) {
                        throw new IllegalStateException("written by a newer version (schema " + data.schemaVersion + ")");
                    }
                }
            }
        }
        catch (Exception e) {
            Global.system.logger.error("Profile '" + id + "' could not be loaded; running on defaults and not saving over it: " + e.getMessage());
            data = new ConfigData.Profile();
            blockSaves = true;
        }

        ClickCrystals.config.attachProfile(id, data, blockSaves);
        ClickCrystals.config.loadEntireConfig();
    }

    private static String readActive() {
        try {
            JsonObject o = ConfigFiles.readObject(ClickCrystals.config.root().resolve("active.json"));
            if (o != null && o.has("active")) {
                String id = toId(o.get("active").getAsString());
                if (id != null) return id;
            }
        }
        catch (Exception e) {
            Global.system.logger.error("Could not read active.json; using the default profile: " + e.getMessage());
        }
        return DEFAULT_ID;
    }

    static void writeActive(Path root, String id) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("active", id);
        ConfigFiles.writeAtomic(root.resolve("active.json"), o);
    }

    private static boolean isDefault(String id) {
        return DEFAULT_ID.equals(id);
    }

    public static class ProfileConfig {

        private String currentConfig; // null means the default profile

        public String getCurrentProfileName() {
            return currentConfig == null || currentConfig.isEmpty() ? "Main Config" : StringUtils.capitalizeWords(currentConfig);
        }

        public String getCurrentProfileId() {
            return currentConfig == null || currentConfig.isEmpty() ? "main-config" : currentConfig;
        }
    }
}

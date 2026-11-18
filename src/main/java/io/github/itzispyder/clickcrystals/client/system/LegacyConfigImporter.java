package io.github.itzispyder.clickcrystals.client.system;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * One-time import of the previous build's files (config.json, profiles.json, profiles/*.json).
 * Runs only while client.json doesn't exist. Client.json is written last, so an interrupted
 * import runs again on the next launch. Old files are left in place.
 */
final class LegacyConfigImporter {

    private static final String[] MODULE_GROUPS = {
            "objectEntries", "integerEntries", "doubleEntries", "booleanEntries", "stringEntries"
    };

    private LegacyConfigImporter() {}

    /** @param root where the new files go; @param legacy where the old files are */
    static void importIfNeeded(Path root, Path legacy) throws IOException {
        if (Files.exists(root.resolve("client.json")) || !Files.isDirectory(legacy)) return;

        JsonObject main = ConfigFiles.readObject(legacy.resolve("config.json"));
        ConfigData.Client client = new ConfigData.Client();
        if (main != null) {
            client.hasPlayedBefore = flag(main, "hasPlayedBefore");
            client.overviewMode = flag(main, "overviewMode");
            client.disableCustomLoading = flag(main, "disableCustomLoading");
            client.disableModuleToggleBroadcast = flag(main, "disableModuleToggleBroadcast");
            client.disableModMenuIntegration = flag(main, "modMenuIntegration"); // old field held the "disable" meaning
            client.devMode = flag(main, "devMode");
            client.readAnnouncementCount = main.has("readAnnouncementCount")
                    ? main.get("readAnnouncementCount").getAsInt() : 0;
        }

        // Default profile.
        ConfigFiles.writeAtomic(root.resolve("profiles").resolve(ProfileManager.DEFAULT_ID + ".json"),
                main == null ? new ConfigData.Profile() : toProfile(main));

        // Named profiles.
        Path dir = legacy.resolve("profiles");
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                    String name = f.getFileName().toString();
                    String id = ConfigFiles.toId(name.substring(0, name.length() - ".json".length()));
                    JsonObject raw = ConfigFiles.readObject(f);
                    if (id == null || raw == null || ProfileManager.DEFAULT_ID.equals(id)) continue;
                    ConfigFiles.writeAtomic(root.resolve("profiles").resolve(id + ".json"), toProfile(raw));
                }
            }
        }

        // Which profile was active.
        String active = ProfileManager.DEFAULT_ID;
        JsonObject pointer = ConfigFiles.readObject(legacy.resolve("profiles.json"));
        if (pointer != null && pointer.has("currentConfig") && !pointer.get("currentConfig").isJsonNull()) {
            String id = ConfigFiles.toId(pointer.get("currentConfig").getAsString());
            if (id != null) active = id;
        }
        ProfileManager.writeActive(root, active);

        ConfigFiles.writeAtomic(root.resolve("client.json"), client);
    }

    /** Converts one old-format profile (the old Config JSON) to the new shape. */
    static ConfigData.Profile toProfile(JsonObject legacy) {
        ConfigData.Profile p = new ConfigData.Profile();

        for (Map.Entry<String, JsonElement> e : obj(legacy, "keybindEntries").entrySet()) {
            p.keybinds.put(e.getKey(), e.getValue().getAsInt());
        }

        for (Map.Entry<String, JsonElement> e : obj(legacy, "positionEntries").entrySet()) {
            JsonObject d = e.getValue().getAsJsonObject();
            p.huds.put(e.getKey(), new ConfigData.Pos(d.get("x").getAsInt(), d.get("y").getAsInt()));
        }

        for (Map.Entry<String, JsonElement> e : obj(legacy, "overviewScreenEntries").entrySet()) {
            JsonObject pair = e.getValue().getAsJsonObject();
            JsonObject d = pair.getAsJsonObject("left");
            p.overview.put(e.getKey(), new ConfigData.Overview(
                    d.get("x").getAsInt(), d.get("y").getAsInt(), pair.get("right").getAsBoolean()));
        }

        // The old format split each module's values into five typed maps. Flatten them into one map.
        for (Map.Entry<String, JsonElement> e : obj(legacy, "moduleEntries").entrySet()) {
            JsonObject moduleFile = e.getValue().getAsJsonObject();
            Map<String, JsonElement> settings = new LinkedHashMap<>();
            for (String group : MODULE_GROUPS) {
                for (Map.Entry<String, JsonElement> s : obj(moduleFile, group).entrySet()) {
                    settings.put(s.getKey(), s.getValue());
                }
            }
            p.modules.put(e.getKey(), settings);
        }
        return p;
    }

    private static JsonObject obj(JsonObject o, String key) {
        return (o.has(key) && o.get(key).isJsonObject()) ? o.getAsJsonObject(key) : new JsonObject();
    }

    private static boolean flag(JsonObject o, String key) {
        return o.has(key) && o.get(key).getAsBoolean();
    }
}

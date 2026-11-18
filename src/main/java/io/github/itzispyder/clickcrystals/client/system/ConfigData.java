package io.github.itzispyder.clickcrystals.client.system;

import com.google.gson.JsonElement;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * On-disk shapes. Every file carries schemaVersion so later changes can be migrated explicitly.
 */
public final class ConfigData {

    public static final int SCHEMA_VERSION = 2;

    private ConfigData() {}

    /** Global client flags, stored in client.json. These are not per-profile. */
    public static final class Client {
        public int schemaVersion = SCHEMA_VERSION;
        public boolean hasPlayedBefore;
        public boolean overviewMode;
        public boolean disableCustomLoading;
        public boolean disableModuleToggleBroadcast;
        public boolean disableModMenuIntegration;
        public boolean devMode;
        public int readAnnouncementCount;
    }

    /** Per-profile state, stored in profiles/<id>.json. */
    public static final class Profile {
        public int schemaVersion = SCHEMA_VERSION;
        public Map<String, Integer> keybinds = new LinkedHashMap<>();
        public Map<String, Pos> huds = new LinkedHashMap<>();
        public Map<String, Overview> overview = new LinkedHashMap<>();
        /** moduleId → (settingId → value). Kept as JSON so values for unloaded modules survive. */
        public Map<String, Map<String, JsonElement>> modules = new LinkedHashMap<>();

        /** Gson leaves a map null if the file contains an explicit null. */
        void normalize() {
            if (keybinds == null) keybinds = new LinkedHashMap<>();
            if (huds == null) huds = new LinkedHashMap<>();
            if (overview == null) overview = new LinkedHashMap<>();
            if (modules == null) modules = new LinkedHashMap<>();
        }
    }

    public static final class Pos {
        public int x;
        public int y;

        public Pos() {}

        public Pos(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    public static final class Overview {
        public int x;
        public int y;
        public boolean collapsed;

        public Overview() {}

        public Overview(int x, int y, boolean collapsed) {
            this.x = x;
            this.y = y;
            this.collapsed = collapsed;
        }
    }
}

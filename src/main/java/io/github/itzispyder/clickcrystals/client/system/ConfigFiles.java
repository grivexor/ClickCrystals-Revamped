package io.github.itzispyder.clickcrystals.client.system;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.itzispyder.clickcrystals.Global;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Disk helpers for the config system: atomic writes, quarantine of unreadable files,
 * and profile-name rules.
 */
final class ConfigFiles {

    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_ID_LENGTH = 32;

    private ConfigFiles() {}

    /** Returns the file's root object, or null if it's missing or unreadable (unreadable files are moved aside). */
    static JsonObject readObject(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return null;

        String text = Files.readString(file, StandardCharsets.UTF_8);
        try {
            JsonElement root = JsonParser.parseString(text);
            if (root.isJsonObject()) return root.getAsJsonObject();
            throw new IllegalArgumentException("root is not a JSON object");
        }
        catch (RuntimeException e) {
            Path aside = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
            Files.move(file, aside);
            Global.system.logger.error("Config file " + file + " is unreadable (" + e.getMessage() + "); moved it to " + aside);
            return null;
        }
    }

    /** Writes to a temp file and renames it over the target, so a crash can't leave a truncated file. */
    static void writeAtomic(Path file, Object value) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(value), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** "My PvP Setup 2" becomes "my-pvp-setup-2". Returns null if no usable characters remain. */
    static String toId(String name) {
        if (name == null) return null;
        String id = name.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (id.length() > MAX_ID_LENGTH) id = id.substring(0, MAX_ID_LENGTH).replaceAll("-+$", "");
        return id.isEmpty() ? null : id;
    }
}

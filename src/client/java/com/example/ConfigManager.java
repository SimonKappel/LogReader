package com.example;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

public final class ConfigManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("StatsReader");

    private static final Path PATH =
            FabricLoader.getInstance().getConfigDir().resolve("statsreader.properties");

    public static ModConfig config = new ModConfig();

    private ConfigManager() {}

    public static void load() {
        Properties p = new Properties();

        if (Files.exists(PATH)) {
            try (InputStream in = Files.newInputStream(PATH)) {
                p.load(in);
            } catch (Exception e) {
                LOGGER.warn("Could not read config, using defaults: {}", e.toString());
            }
        }

        boolean enabled = parseBool(p.getProperty("enabled"), true);
        StatsApi.DisplayMode mode = parseMode(p.getProperty("displayMode"), StatsApi.DisplayMode.KD);

        config = new ModConfig(enabled, mode);

        // in StatsApi spiegeln
        StatsApi.ENABLED = config.enabled;
        StatsApi.DISPLAY_MODE = config.displayMode;

        // falls Datei noch nicht existiert -> schreiben
        save();
    }

    public static void save() {
        try {
            Properties p = new Properties();
            p.setProperty("enabled", Boolean.toString(config.enabled));
            p.setProperty("displayMode", config.displayMode.name());

            try (OutputStream out = Files.newOutputStream(PATH)) {
                p.store(out, "StatsReader config");
            }
        } catch (Exception e) {
            LOGGER.warn("Could not save config: {}", e.toString());
        }
    }

    public static void setMode(StatsApi.DisplayMode mode) {
        config.displayMode = mode;
        StatsApi.DISPLAY_MODE = mode;
        save();
    }

    public static void setEnabled(boolean enabled) {
        config.enabled = enabled;
        StatsApi.ENABLED = enabled;
        save();
    }

    private static boolean parseBool(String s, boolean def) {
        if (s == null) return def;
        s = s.trim().toLowerCase(Locale.ROOT);
        if (s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("on")) return true;
        if (s.equals("false") || s.equals("0") || s.equals("no") || s.equals("off")) return false;
        return def;
    }

    private static StatsApi.DisplayMode parseMode(String s, StatsApi.DisplayMode def) {
        if (s == null || s.isBlank()) return def;
        try {
            return StatsApi.DisplayMode.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return def;
        }
    }
}

package com.example;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

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

        // ✅ NEU: Chat-Notify Toggle (default true)
        boolean chatNotify = parseBool(p.getProperty("chatNotify"), true);

        // NEU: Multi-Modes (z.B. "KD,WIN_RATE_PERCENT")
        Set<StatsApi.DisplayMode> modes = parseModes(p.getProperty("displayModes"));

        // Migration: alte Config hatte nur "displayMode"
        if (modes.isEmpty()) {
            StatsApi.DisplayMode legacy = parseMode(p.getProperty("displayMode"), StatsApi.DisplayMode.KD);
            modes.add(legacy);

            // optional: wenn legacy KD ist, mach direkt KD+WR als default “upgrade”
            if (legacy == StatsApi.DisplayMode.KD) {
                modes.add(StatsApi.DisplayMode.WIN_RATE_PERCENT);
            }
        }

        String period = normalizePeriod(p.getProperty("period"), "newest");

        // ✅ FIX: neuer ModConfig-Constructor (enabled, chatNotify, modes, period)
        config = new ModConfig(enabled, chatNotify, modes, period);

        // in StatsApi spiegeln
        StatsApi.ENABLED = config.enabled;
        synchronized (StatsApi.DISPLAY_MODES) {
            StatsApi.DISPLAY_MODES.clear();
            StatsApi.DISPLAY_MODES.addAll(config.displayModes);
        }

        // falls Datei noch nicht existiert oder wir migriert haben -> schreiben
        save();
    }

    public static void save() {
        try {
            Properties p = new Properties();
            p.setProperty("enabled", Boolean.toString(config.enabled));

            // ✅ NEU: Chat-Notify speichern
            p.setProperty("chatNotify", Boolean.toString(config.chatNotify));

            p.setProperty("displayModes", joinModes(config.displayModes));
            p.setProperty("period", normalizePeriod(config.period, "newest"));

            // optional: legacy key weiter schreiben
            StatsApi.DisplayMode first = config.displayModes.stream().findFirst().orElse(StatsApi.DisplayMode.KD);
            p.setProperty("displayMode", first.name());

            try (OutputStream out = Files.newOutputStream(PATH)) {
                p.store(out, "StatsReader config");
            }
        } catch (Exception e) {
            LOGGER.warn("Could not save config: {}", e.toString());
        }
    }

    // ✅ NEU: Getter/Setter für Notify (damit ModCommands & chat() compilen)
    public static boolean isChatNotifyEnabled() {
        return config.chatNotify;
    }

    public static void setChatNotifyEnabled(boolean v) {
        config.chatNotify = v;
        save();
    }

    public static String getPeriod() {
        return normalizePeriod(config.period, "newest");
    }

    public static void setPeriod(String period) {
        config.period = normalizePeriod(period, "newest");
        save();
    }

    private static String normalizePeriod(String v, String def) {
        if (v == null || v.isBlank()) return def;
        v = v.trim().toLowerCase(Locale.ROOT);

        // Migration / Aliase (alte Namen -> neue Namen)
        v = switch (v) {
            case "legacy" -> "newest";
            case "all" -> "alltime";
            case "30d_then_all" -> "30d_then_alltime";
            case "all_then_30d" -> "alltime_then_30d";
            default -> v;
        };

        // Nur neue Werte erlauben
        return switch (v) {
            case "newest", "30d", "alltime", "30d_then_alltime", "alltime_then_30d" -> v;
            default -> def;
        };
    }

    // Setzt die komplette Liste (z.B. show kd wr)
    public static void setModes(Set<StatsApi.DisplayMode> modes) {
        if (modes == null || modes.isEmpty()) return;

        config.displayModes.clear();
        config.displayModes.addAll(modes);

        synchronized (StatsApi.DISPLAY_MODES) {
            StatsApi.DISPLAY_MODES.clear();
            StatsApi.DISPLAY_MODES.addAll(modes);
        }

        save();
    }

    // Add/remove (z.B. add wr / remove kd)
    public static void addMode(StatsApi.DisplayMode mode) {
        if (mode == null) return;

        config.displayModes.add(mode);
        synchronized (StatsApi.DISPLAY_MODES) {
            StatsApi.DISPLAY_MODES.add(mode);
        }
        save();
    }

    public static void removeMode(StatsApi.DisplayMode mode) {
        if (mode == null) return;

        config.displayModes.remove(mode);
        synchronized (StatsApi.DISPLAY_MODES) {
            StatsApi.DISPLAY_MODES.remove(mode);
        }
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

    private static Set<StatsApi.DisplayMode> parseModes(String s) {
        Set<StatsApi.DisplayMode> set = new LinkedHashSet<>();
        if (s == null || s.isBlank()) return set;

        for (String token : s.split("[,\\s]+")) {
            if (token.isBlank()) continue;
            try {
                set.add(StatsApi.DisplayMode.valueOf(token.trim().toUpperCase(Locale.ROOT)));
            } catch (Exception ignored) {
            }
        }
        return set;
    }

    private static String joinModes(Set<StatsApi.DisplayMode> modes) {
        if (modes == null || modes.isEmpty()) return StatsApi.DisplayMode.KD.name();
        StringBuilder sb = new StringBuilder();
        for (StatsApi.DisplayMode m : modes) {
            if (sb.length() > 0) sb.append(",");
            sb.append(m.name());
        }
        return sb.toString();
    }
}

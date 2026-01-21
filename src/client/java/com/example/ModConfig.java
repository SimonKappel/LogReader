package com.example;

import java.util.EnumSet;
import java.util.Set;

public final class ModConfig {
    public boolean enabled = true;

    // NEU: Chat-Notifications (z.B. "stats gesendet", "airconsent ...")
    public boolean chatNotify = true;

    // NEU: mehrere Anzeigen gleichzeitig (z.B. KD + WR)
    public Set<StatsApi.DisplayMode> displayModes = EnumSet.of(
            StatsApi.DisplayMode.KD,
            StatsApi.DisplayMode.WIN_RATE_PERCENT
    );

    // NEU: welcher Stats-Periodenmodus verwendet wird
    // legacy | 30d | all | 30d_then_all | all_then_30d
    public String period = "newest";

    public ModConfig() { }

    public ModConfig(boolean enabled,
                     boolean chatNotify,
                     Set<StatsApi.DisplayMode> displayModes,
                     String period) {

        this.enabled = enabled;
        this.chatNotify = chatNotify;

        if (displayModes == null || displayModes.isEmpty()) {
            this.displayModes = EnumSet.of(StatsApi.DisplayMode.KD);
        } else {
            this.displayModes = EnumSet.copyOf(displayModes);
        }

        this.period = normalizePeriod(period);
    }

    private static String normalizePeriod(String p) {
        if (p == null || p.isBlank()) return "newest";

        String n = p.trim().toLowerCase();

        // Aliase/Migration
        n = switch (n) {
            case "legacy" -> "newest";
            case "all" -> "alltime";
            case "30d_then_all" -> "30d_then_alltime";
            case "all_then_30d" -> "alltime_then_30d";
            default -> n;
        };

        // erlaubte Werte
        return switch (n) {
            case "newest", "30d", "alltime", "30d_then_alltime", "alltime_then_30d" -> n;
            default -> "newest";
        };
    }
}

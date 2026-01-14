package com.example;

import java.util.EnumSet;
import java.util.Set;

public final class ModConfig {
    public boolean enabled = true;

    // NEU: mehrere Anzeigen gleichzeitig (z.B. KD + WR)
    public Set<StatsApi.DisplayMode> displayModes = EnumSet.of(
            StatsApi.DisplayMode.KD,
            StatsApi.DisplayMode.WIN_RATE_PERCENT
    );

    public ModConfig() { }

    public ModConfig(boolean enabled, Set<StatsApi.DisplayMode> displayModes) {
        this.enabled = enabled;

        if (displayModes == null || displayModes.isEmpty()) {
            this.displayModes = EnumSet.of(StatsApi.DisplayMode.KD);
        } else {
            this.displayModes = EnumSet.copyOf(displayModes);
        }
    }
}

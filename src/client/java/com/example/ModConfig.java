package com.example;

public final class ModConfig {
    public boolean enabled = true;
    public StatsApi.DisplayMode displayMode = StatsApi.DisplayMode.KD;

    public ModConfig() { }

    public ModConfig(boolean enabled, StatsApi.DisplayMode displayMode) {
        this.enabled = enabled;
        this.displayMode = displayMode;
    }
}

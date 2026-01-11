package com.example.mixin;

import com.example.StatsApi;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerListHud.class)
public class PlayerListHudMixin {

    @Inject(method = "getPlayerName", at = @At("RETURN"), cancellable = true)
    private void statsreader$appendStat(PlayerListEntry entry, CallbackInfoReturnable<Text> cir) {
        if (!StatsApi.ENABLED) return;

        String name = entry.getProfile().getName();

        var stats = StatsApi.getStats(name);

        // wenn unbekannt -> async laden
        if (stats == null) {
            StatsApi.tryFetchStatsAsync(name, false);
            return;
        }

        // wenn älter -> refreshen
        if (StatsApi.isStale(name)) {
            StatsApi.tryFetchStatsAsync(name, true);
        }

        String val = StatsApi.formatValue(stats);
        if (val == null || val.isBlank()) return;

        MutableText text = cir.getReturnValue().copy();

        // einfache Farbe: für KD grün/gelb/rot, sonst grau/weiß
        Formatting color = Formatting.GRAY;

        if (StatsApi.DISPLAY_MODE == StatsApi.DisplayMode.KD && stats.kd != null) {
            double kd = stats.kd;
            color = kd >= 2.0 ? Formatting.GREEN :
                    kd >= 1.0 ? Formatting.YELLOW :
                            Formatting.RED;
        } else if (StatsApi.DISPLAY_MODE == StatsApi.DisplayMode.WIN_RATE_PERCENT && stats.winRatePercent != null) {
            double wr = stats.winRatePercent;
            color = wr >= 70 ? Formatting.GREEN :
                    wr >= 50 ? Formatting.YELLOW :
                            Formatting.RED;
        }

        text.append(Text.literal(" " + StatsApi.getDisplayLabel() + ": " + val).formatted(color));
        cir.setReturnValue(text);
    }
}

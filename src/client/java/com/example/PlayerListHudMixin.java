package com.example.mixin;

import com.example.StatsApi;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.Locale;

@Mixin(PlayerListHud.class)
public class PlayerListHudMixin {

    // Wie viel Platz wir maximal für die Stats nutzen (Pixel).
    // 140-180 ist meistens angenehm, je nach GUI scale.
    private static final int MAX_EXTRA_WIDTH_PX = 170;

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

        MutableText base = cir.getReturnValue().copy();
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;

        // Wir hängen mehrere Teile an: " KD: 2.31 | WR: 58%"
        boolean first = true;
        MutableText extra = Text.empty();

        synchronized (StatsApi.DISPLAY_MODES) {
            for (StatsApi.DisplayMode mode : StatsApi.DisplayMode.values()) {
                if (!StatsApi.DISPLAY_MODES.contains(mode)) continue;

                String val = StatsApi.formatValue(mode, stats);
                if (val == null || val.isBlank()) continue;

                Formatting color = colorFor(mode, stats);

                String label = StatsApi.getDisplayLabel(mode);

                String part = (first ? " " : " | ") + label + ": " + val;
                first = false;

                // Breitenlimit: wenn das nächste Part zu breit wäre -> abbrechen
                int currentExtraWidth = tr.getWidth(extra);
                int partWidth = tr.getWidth(Text.literal(part));
                if (currentExtraWidth + partWidth > MAX_EXTRA_WIDTH_PX) {
                    // optional: zeig "…" damit man merkt, da wäre mehr
                    if (currentExtraWidth + tr.getWidth(Text.literal(" …")) <= MAX_EXTRA_WIDTH_PX) {
                        extra = extra.copy().append(Text.literal(" …").formatted(Formatting.DARK_GRAY));
                    }
                    break;
                }

                extra = extra.copy().append(Text.literal(part).formatted(color));
            }
        }

        // wenn nix sinnvoll dran hängt -> leave
        if (tr.getWidth(extra) == 0) return;

        base.append(extra);
        cir.setReturnValue(base);
    }

    private static Formatting colorFor(StatsApi.DisplayMode mode, StatsApi.PlayerStats stats) {
        // Default
        Formatting color = Formatting.WHITE;

        if (mode == StatsApi.DisplayMode.KD && stats.kd != null) {
            double kd = stats.kd;

            if (kd >= 100.0) return Formatting.AQUA;     // insane
            if (kd >= 10.0) return Formatting.GREEN;    // very good
            if (kd >= 3.0) return Formatting.YELLOW;   // average+
            if (kd >= 1.0) return Formatting.GOLD;     // weak
            return Formatting.RED;                     // bad
        }

        if (mode == StatsApi.DisplayMode.WIN_RATE_PERCENT && stats.winRatePercent != null) {
            double wr = stats.winRatePercent;

            if (wr >= 95) return Formatting.AQUA;      // insane
            if (wr >= 80) return Formatting.GREEN;     // very good
            if (wr >= 50) return Formatting.YELLOW;    // average+
            if (wr >= 25) return Formatting.GOLD;      // weak
            return Formatting.RED;                     // bad
        }

        return color;
    }
}

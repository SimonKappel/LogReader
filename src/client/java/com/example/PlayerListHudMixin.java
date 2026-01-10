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
    private void statsreader$appendKd(PlayerListEntry entry, CallbackInfoReturnable<Text> cir) {
        String name = entry.getProfile().getName();

        Double kd = StatsApi.getKd(name);

        // wenn unbekannt -> async laden
        if (kd == null) {
            StatsApi.tryFetchKdAsync(name, false);
            return;
        }

        // wenn älter als 10s -> im Hintergrund refreshen
        if (StatsApi.isStale(name)) {
            StatsApi.tryFetchKdAsync(name, true);
        }

        MutableText text = cir.getReturnValue().copy();

        Formatting color =
                kd >= 2.0 ? Formatting.GREEN :
                        kd >= 1.0 ? Formatting.YELLOW :
                                Formatting.RED;

        text.append(Text.literal(" K/D: " + String.format("%.2f", kd)).formatted(color));
        cir.setReturnValue(text);
    }
}

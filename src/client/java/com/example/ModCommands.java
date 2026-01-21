package com.example;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import java.util.concurrent.CompletableFuture;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

public final class ModCommands {
    private ModCommands() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {

            dispatcher.register(
                    ClientCommandManager.literal("statsreader")
                            .executes(ctx -> {
                                ctx.getSource().sendFeedback(prefix()
                                        .append(Text.literal("Commands:").formatted(Formatting.GRAY)));

                                ctx.getSource().sendFeedback(prefix()
                                        .append(Text.literal("/statsreader on").formatted(Formatting.GREEN))
                                        .append(Text.literal(" - Tab Anzeige aktivieren").formatted(Formatting.GRAY)));

                                ctx.getSource().sendFeedback(prefix()
                                        .append(Text.literal("/statsreader off").formatted(Formatting.RED))
                                        .append(Text.literal(" - Tab Anzeige deaktivieren").formatted(Formatting.GRAY)));

                                ctx.getSource().sendFeedback(prefix()
                                        .append(Text.literal("/statsreader notify").formatted(Formatting.AQUA))
                                        .append(Text.literal(" - Status Chat-Benachrichtigungen").formatted(Formatting.GRAY)));

                                ctx.getSource().sendFeedback(prefix()
                                        .append(Text.literal("/statsreader notify on|off|toggle").formatted(Formatting.AQUA))
                                        .append(Text.literal(" - Chat-Benachrichtigungen ändern").formatted(Formatting.GRAY)));

                                ctx.getSource().sendFeedback(prefix()
                                        .append(Text.literal("/statsreader list").formatted(Formatting.YELLOW))
                                        .append(Text.literal(" - Alle verfügbaren Modes anzeigen").formatted(Formatting.GRAY)));

                                ctx.getSource().sendFeedback(prefix()
                                        .append(Text.literal("/statsreader show <modes...>").formatted(Formatting.YELLOW))
                                        .append(Text.literal(" - Setzt die angezeigten Modes (z.B. kd wr)").formatted(Formatting.GRAY)));

                                ctx.getSource().sendFeedback(prefix()
                                        .append(Text.literal("/statsreader add <mode>").formatted(Formatting.YELLOW))
                                        .append(Text.literal(" - Mode hinzufügen").formatted(Formatting.GRAY)));

                                ctx.getSource().sendFeedback(prefix()
                                        .append(Text.literal("/statsreader remove <mode>").formatted(Formatting.YELLOW))
                                        .append(Text.literal(" - Mode entfernen").formatted(Formatting.GRAY)));

                                ctx.getSource().sendFeedback(prefix()
                                        .append(Text.literal("/statsreader period").formatted(Formatting.LIGHT_PURPLE))
                                        .append(Text.literal(" - Zeigt aktuelle Period").formatted(Formatting.GRAY)));

                                ctx.getSource().sendFeedback(prefix()
                                        .append(Text.literal("/statsreader period <value>").formatted(Formatting.LIGHT_PURPLE))
                                        .append(Text.literal(" - Setzt Period (" + allowedPeriods() + ")").formatted(Formatting.GRAY)));

                                return 1;
                            })

                            .then(ClientCommandManager.literal("on")
                                    .executes(ctx -> {
                                        ConfigManager.setEnabled(true);
                                        ctx.getSource().sendFeedback(prefix()
                                                .append(Text.literal("Tab Anzeige: ON").formatted(Formatting.GREEN)));
                                        return 1;
                                    })
                            )
                            .then(ClientCommandManager.literal("off")
                                    .executes(ctx -> {
                                        ConfigManager.setEnabled(false);
                                        ctx.getSource().sendFeedback(prefix()
                                                .append(Text.literal("Tab Anzeige: OFF").formatted(Formatting.RED)));
                                        return 1;
                                    })
                            )
                            .then(ClientCommandManager.literal("notify")
                                    // /statsreader notify  -> zeigt aktuellen Status
                                    .executes(ctx -> {
                                        boolean cur = ConfigManager.isChatNotifyEnabled(); // neu in ConfigManager
                                        ctx.getSource().sendFeedback(prefix().append(
                                                Text.literal("Chat Benachrichtigungen: " + (cur ? "ON" : "OFF"))
                                                        .formatted(cur ? Formatting.GREEN : Formatting.RED)
                                        ));
                                        return 1;
                                    })

                                    .then(ClientCommandManager.literal("on")
                                            .executes(ctx -> {
                                                ConfigManager.setChatNotifyEnabled(true);
                                                ctx.getSource().sendFeedback(prefix()
                                                        .append(Text.literal("Chat Benachrichtigungen: ON").formatted(Formatting.GREEN)));
                                                return 1;
                                            })
                                    )
                                    .then(ClientCommandManager.literal("off")
                                            .executes(ctx -> {
                                                ConfigManager.setChatNotifyEnabled(false);
                                                ctx.getSource().sendFeedback(prefix()
                                                        .append(Text.literal("Chat Benachrichtigungen: OFF").formatted(Formatting.RED)));
                                                return 1;
                                            })
                                    )
                                    .then(ClientCommandManager.literal("toggle")
                                            .executes(ctx -> {
                                                boolean cur = ConfigManager.isChatNotifyEnabled();
                                                ConfigManager.setChatNotifyEnabled(!cur);

                                                boolean now = !cur;
                                                ctx.getSource().sendFeedback(prefix().append(
                                                        Text.literal("Chat Benachrichtigungen: " + (now ? "ON" : "OFF"))
                                                                .formatted(now ? Formatting.GREEN : Formatting.RED)
                                                ));
                                                return 1;
                                            })
                                    )
                            )

                            .then(ClientCommandManager.literal("list")
                                    .executes(ctx -> {
                                        ctx.getSource().sendFeedback(prefix()
                                                .append(Text.literal("Modes: " + allowedModes()).formatted(Formatting.GRAY)));
                                        return 1;
                                    })
                            )
                            .then(ClientCommandManager.literal("show")
                                    .then(ClientCommandManager.argument("modes", StringArgumentType.greedyString())
                                            .suggests(ModCommands::suggestModesGreedy)
                                            .executes(ctx -> {
                                                String raw = StringArgumentType.getString(ctx, "modes");
                                                var parsed = parseModesList(raw);

                                                if (parsed == null || parsed.isEmpty()) {
                                                    ctx.getSource().sendFeedback(prefix()
                                                            .append(Text.literal("Unbekannt. Erlaubt: " + allowedModes())
                                                                    .formatted(Formatting.RED)));
                                                    return 0;
                                                }

                                                ConfigManager.setModes(parsed);


                                                ctx.getSource().sendFeedback(prefix()
                                                        .append(Text.literal("Tab Anzeige: " + parsed.stream().map(Enum::name).collect(Collectors.joining(", ")))
                                                                .formatted(Formatting.AQUA)));
                                                return 1;
                                            })
                                    )
                            )
                            .then(ClientCommandManager.literal("add")
                                    .then(ClientCommandManager.argument("mode", StringArgumentType.word())
                                            .suggests(ModCommands::suggestModes)
                                            .executes(ctx -> {
                                                String modeStr = StringArgumentType.getString(ctx, "mode");
                                                StatsApi.DisplayMode mode = parseMode(modeStr);

                                                if (mode == null) {
                                                    ctx.getSource().sendFeedback(prefix()
                                                            .append(Text.literal("Unbekannt. Erlaubt: " + allowedModes()).formatted(Formatting.RED)));
                                                    return 0;
                                                }

                                                ConfigManager.addMode(mode);


                                                ctx.getSource().sendFeedback(prefix()
                                                        .append(Text.literal("Hinzugefügt: " + mode.name()).formatted(Formatting.GREEN)));
                                                return 1;
                                            })
                                    )
                            )
                            .then(ClientCommandManager.literal("remove")
                                    .then(ClientCommandManager.argument("mode", StringArgumentType.word())
                                            .suggests(ModCommands::suggestModes)
                                            .executes(ctx -> {
                                                String modeStr = StringArgumentType.getString(ctx, "mode");
                                                StatsApi.DisplayMode mode = parseMode(modeStr);

                                                if (mode == null) {
                                                    ctx.getSource().sendFeedback(prefix()
                                                            .append(Text.literal("Unbekannt. Erlaubt: " + allowedModes()).formatted(Formatting.RED)));
                                                    return 0;
                                                }

                                                ConfigManager.removeMode(mode);


                                                ctx.getSource().sendFeedback(prefix()
                                                        .append(Text.literal("Entfernt: " + mode.name()).formatted(Formatting.YELLOW)));
                                                return 1;
                                            })
                                    )
                            )
                            .then(ClientCommandManager.literal("period")
                                    // /statsreader period   -> zeigt aktuellen Wert
                                    .executes(ctx -> {
                                        String cur = ConfigManager.getPeriod(); // musst du in ConfigManager ergänzen
                                        ctx.getSource().sendFeedback(prefix()
                                                .append(Text.literal("Period: " + cur).formatted(Formatting.GRAY)));
                                        return 1;
                                    })
                                    // /statsreader period <value>
                                    .then(ClientCommandManager.argument("value", StringArgumentType.word())
                                            .suggests(ModCommands::suggestPeriods)
                                            .executes(ctx -> {
                                                String v = StringArgumentType.getString(ctx, "value").trim().toLowerCase(Locale.ROOT);

                                                if (!isAllowedPeriod(v)) {
                                                    ctx.getSource().sendFeedback(prefix()
                                                            .append(Text.literal("Unbekannt. Erlaubt: " + allowedPeriods())
                                                                    .formatted(Formatting.RED)));
                                                    return 0;
                                                }

                                                ConfigManager.setPeriod(v);
                                                StatsApi.clearCacheAll();
                                                ctx.getSource().sendFeedback(prefix()
                                                        .append(Text.literal("Period gesetzt: " + v).formatted(Formatting.GREEN)));
                                                return 1;
                                            })
                                    )


                            )


            );

        });
    }
    private static CompletableFuture<Suggestions> suggestModesGreedy(
            com.mojang.brigadier.context.CommandContext<?> ctx,
            SuggestionsBuilder builder
    ) {
        // greedyString: wir schlagen das "nächste" Wort vor
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        String[] parts = remaining.split("\\s+");
        String last = parts.length == 0 ? "" : parts[parts.length - 1];

        for (StatsApi.DisplayMode mode : StatsApi.DisplayMode.values()) {
            String name = mode.name().toLowerCase(Locale.ROOT);
            if (name.startsWith(last)) {
                builder.suggest(replaceLastToken(builder.getRemaining(), last, name));
            }
        }
        return builder.buildFuture();
    }
    private static boolean isAllowedPeriod(String v) {
        if (v == null) return false;
        v = v.trim().toLowerCase(Locale.ROOT);
        return v.equals("newest")
                || v.equals("30d")
                || v.equals("alltime")
                || v.equals("30d_then_alltime")
                || v.equals("alltime_then_30d");
    }

    private static String allowedPeriods() {
        return "newest, 30d, alltime, 30d_then_alltime, alltime_then_30d";
    }


    private static CompletableFuture<Suggestions> suggestPeriods(
            com.mojang.brigadier.context.CommandContext<?> ctx,
            SuggestionsBuilder builder
    ) {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);

        for (String p : new String[]{"newest","30d","alltime","30d_then_alltime","alltime_then_30d"}) {
            if (p.startsWith(remaining)) builder.suggest(p);
        }

        return builder.buildFuture();
    }

    private static String replaceLastToken(String full, String last, String replacement) {
        // full enthält die bisherigen tokens; wir ersetzen das letzte token
        int idx = full.toLowerCase(Locale.ROOT).lastIndexOf(last.toLowerCase(Locale.ROOT));
        if (idx < 0) return replacement;
        return full.substring(0, idx) + replacement;
    }

    private static java.util.Set<StatsApi.DisplayMode> parseModesList(String raw) {
        if (raw == null) return null;

        var set = java.util.EnumSet.noneOf(StatsApi.DisplayMode.class);
        for (String token : raw.split("[,\\s]+")) {
            if (token.isBlank()) continue;
            StatsApi.DisplayMode m = parseMode(token);
            if (m != null) set.add(m);
        }
        return set;
    }

    // WICHTIG: MutableText, nicht Text
    private static MutableText prefix() {
        return Text.literal("StatsReader").formatted(Formatting.AQUA)
                .append(Text.literal(" » ").formatted(Formatting.DARK_GRAY));
    }
    private static CompletableFuture<Suggestions> suggestModes(
            com.mojang.brigadier.context.CommandContext<?> ctx,
            SuggestionsBuilder builder
    ) {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);

        for (StatsApi.DisplayMode mode : StatsApi.DisplayMode.values()) {
            String name = mode.name().toLowerCase(Locale.ROOT);
            if (name.startsWith(remaining)) {
                builder.suggest(name);
            }
        }

        return builder.buildFuture();
    }


    private static StatsApi.DisplayMode parseMode(String s) {
        if (s == null) return null;
        s = s.trim().toUpperCase(Locale.ROOT);

        return switch (s) {
            case "KD", "K/D" -> StatsApi.DisplayMode.KD;
            case "WR", "WINRATE", "WIN_RATE", "WIN_RATE_PERCENT", "WINRATEPERCENT" -> StatsApi.DisplayMode.WIN_RATE_PERCENT;
            case "GP", "GAMES", "GAMESPLAYED", "GAMES_PLAYED" -> StatsApi.DisplayMode.GAMES_PLAYED;
            case "W", "WINS", "GAMESWON", "GAMES_WON" -> StatsApi.DisplayMode.GAMES_WON;
            case "K", "KILLS" -> StatsApi.DisplayMode.KILLS;
            case "D", "DEATHS" -> StatsApi.DisplayMode.DEATHS;
            case "P", "POINTS" -> StatsApi.DisplayMode.POINTS;
            case "R", "RANK", "RANKING" -> StatsApi.DisplayMode.RANKING;
            case "BEDS", "BEDSDESTROYED", "BEDS_DESTROYED" -> StatsApi.DisplayMode.BEDS_DESTROYED;
            default -> {
                try { yield StatsApi.DisplayMode.valueOf(s); }
                catch (Exception e) { yield null; }
            }
        };
    }

    private static String allowedModes() {
        return Arrays.stream(StatsApi.DisplayMode.values())
                .map(Enum::name)
                .collect(Collectors.joining(", "));
    }
}

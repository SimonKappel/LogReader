package com.example;

import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.EnumSet;
import java.util.Set;

public final class StatsApi {
    private static final Logger LOGGER = LoggerFactory.getLogger("StatsReader");
    public static volatile boolean ENABLED = true;


    public static final Set<DisplayMode> DISPLAY_MODES = EnumSet.of(
            DisplayMode.KD,
            DisplayMode.WIN_RATE_PERCENT
    );



    // NEU: Stats Endpoint
    private static final String STATS_ENDPOINT_TEMPLATE =
            "https://bedwarsdatabase.at/api/players/%s/stats";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    // Cache: Name -> Stats
    private static final Map<String, PlayerStats> STATS_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Long> STATS_UPDATED_MS = new ConcurrentHashMap<>();

    // In-flight + cooldown
    private static final Map<String, Boolean> IN_FLIGHT = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_TRY_MS = new ConcurrentHashMap<>();

    // Wie oft pro Player max refreshen
    private static final long COOLDOWN_MS = 10_000;


    public enum DisplayMode {
        RANKING,
        POINTS,
        KILLS,
        DEATHS,
        KD,
        GAMES_PLAYED,
        GAMES_WON,
        WIN_RATE_PERCENT,
        BEDS_DESTROYED
    }

    public static final class PlayerStats {
        public final Integer ranking;
        public final Integer points;
        public final Integer kills;
        public final Integer deaths;
        public final Double kd;
        public final Integer gamesPlayed;
        public final Integer gamesWon;
        public final Double winRatePercent;
        public final Integer bedsDestroyed;

        public PlayerStats(
                Integer ranking,
                Integer points,
                Integer kills,
                Integer deaths,
                Double kd,
                Integer gamesPlayed,
                Integer gamesWon,
                Double winRatePercent,
                Integer bedsDestroyed
        ) {
            this.ranking = ranking;
            this.points = points;
            this.kills = kills;
            this.deaths = deaths;
            this.kd = kd;
            this.gamesPlayed = gamesPlayed;
            this.gamesWon = gamesWon;
            this.winRatePercent = winRatePercent;
            this.bedsDestroyed = bedsDestroyed;
        }
    }

    private StatsApi() {}

    /** Stats holen (kann null sein, wenn noch nicht geladen). */
    public static PlayerStats getStats(String name) {
        if (name == null) return null;
        return STATS_CACHE.get(name);
    }

    /** true wenn Stats älter als 10s (oder fehlen). */
    public static boolean isStale(String name) {
        if (name == null) return true;
        Long t = STATS_UPDATED_MS.get(name);
        if (t == null) return true;
        return (System.currentTimeMillis() - t) >= COOLDOWN_MS;
    }

    /** Wenn du /stats (POST) schickst: Cache für diesen Spieler invalidieren, damit TAB neu lädt. */
    public static void invalidate(String name) {
        if (name == null) return;
        STATS_CACHE.remove(name);
        STATS_UPDATED_MS.remove(name);
        LAST_TRY_MS.remove(name);
        IN_FLIGHT.remove(name);
    }

    /** Non-blocking Stats Fetch. force=true ignoriert "cache vorhanden" und lädt neu. */
    public static void tryFetchStatsAsync(String playerName, boolean force) {
        if (playerName == null || playerName.isBlank()) return;

        if (!force && STATS_CACHE.containsKey(playerName) && !isStale(playerName)) return;

        long now = System.currentTimeMillis();
        long last = LAST_TRY_MS.getOrDefault(playerName, 0L);
        if (now - last < COOLDOWN_MS) return;

        if (IN_FLIGHT.putIfAbsent(playerName, true) != null) return;
        LAST_TRY_MS.put(playerName, now);

        String encoded = URLEncoder.encode(playerName, StandardCharsets.UTF_8);
        String url = String.format(STATS_ENDPOINT_TEMPLATE, encoded);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();

        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenAccept(res -> {
                    try {
                        if (res.statusCode() / 100 != 2) {
                            LOGGER.info("Stats fetch {} -> {} {}", playerName, res.statusCode(), res.body());
                            return;
                        }

                        PlayerStats stats = parseStats(res.body());
                        if (stats == null) {
                            LOGGER.info("Stats fetch {} -> could not parse body='{}'", playerName, res.body());
                            return;
                        }

                        MinecraftClient.getInstance().execute(() -> {
                            STATS_CACHE.put(playerName, stats);
                            STATS_UPDATED_MS.put(playerName, System.currentTimeMillis());
                        });
                    } finally {
                        IN_FLIGHT.remove(playerName);
                    }
                })
                .exceptionally(ex -> {
                    IN_FLIGHT.remove(playerName);
                    LOGGER.info("Stats fetch failed for {}: {}", playerName, ex.toString());
                    return null;
                });
    }

    // -------- JSON parsing (ohne libs) --------

    private static final Pattern INT_FIELD = Pattern.compile("\"%s\"\\s*:\\s*(-?\\d+)");
    private static final Pattern DOUBLE_FIELD = Pattern.compile("\"%s\"\\s*:\\s*(-?\\d+(?:[\\.,]\\d+)?)");

    private static PlayerStats parseStats(String body) {
        if (body == null) return null;
        String s = body.trim();

        // optional: found check
        Boolean found = parseBoolField(s, "found");
        if (found != null && !found) return null;

        Integer ranking = parseIntField(s, "ranking");
        Integer points = parseIntField(s, "points");
        Integer kills = parseIntField(s, "kills");
        Integer deaths = parseIntField(s, "deaths");
        Double kd = parseDoubleField(s, "kd");
        Integer gamesPlayed = parseIntField(s, "gamesPlayed");
        Integer gamesWon = parseIntField(s, "gamesWon");
        Double winRatePercent = parseDoubleField(s, "winRatePercent");
        Integer bedsDestroyed = parseIntField(s, "bedsDestroyed");

        // minimal sanity: kd/gamesPlayed sollten meistens da sein
        if (kd == null && kills == null && gamesPlayed == null) return null;

        return new PlayerStats(
                ranking, points, kills, deaths, kd,
                gamesPlayed, gamesWon, winRatePercent, bedsDestroyed
        );
    }

    private static Integer parseIntField(String s, String field) {
        Matcher m = Pattern.compile(String.format("\"%s\"\\s*:\\s*(-?\\d+)", Pattern.quote(field))).matcher(s);
        if (!m.find()) return null;
        try { return Integer.parseInt(m.group(1)); } catch (Exception ignored) { return null; }
    }

    private static Double parseDoubleField(String s, String field) {
        Matcher m = Pattern.compile(String.format("\"%s\"\\s*:\\s*(-?\\d+(?:[\\.,]\\d+)?)", Pattern.quote(field))).matcher(s);
        if (!m.find()) return null;
        try { return Double.parseDouble(m.group(1).replace(",", ".")); } catch (Exception ignored) { return null; }
    }

    private static Boolean parseBoolField(String s, String field) {
        Matcher m = Pattern.compile(String.format("\"%s\"\\s*:\\s*(true|false)", Pattern.quote(field)), Pattern.CASE_INSENSITIVE).matcher(s);
        if (!m.find()) return null;
        return Boolean.parseBoolean(m.group(1));
    }

    // -------- value formatting for UI --------

    public static String getDisplayLabel(DisplayMode mode) {
        return switch (mode) {
            case KD -> "K/D";
            case WIN_RATE_PERCENT -> "WR";
            case GAMES_PLAYED -> "GP";
            case GAMES_WON -> "W";
            case KILLS -> "K";
            case DEATHS -> "D";
            case POINTS -> "P";
            case RANKING -> "R";
            case BEDS_DESTROYED -> "Beds";
        };
    }

    public static String formatValue(DisplayMode mode, PlayerStats st) {
        if (st == null) return null;

        return switch (mode) {
            case KD -> st.kd == null ? null : String.format(java.util.Locale.US, "%.2f", st.kd);
            case WIN_RATE_PERCENT -> st.winRatePercent == null ? null : String.format(java.util.Locale.US, "%.2f%%", st.winRatePercent);
            case GAMES_PLAYED -> st.gamesPlayed == null ? null : String.valueOf(st.gamesPlayed);
            case GAMES_WON -> st.gamesWon == null ? null : String.valueOf(st.gamesWon);
            case KILLS -> st.kills == null ? null : String.valueOf(st.kills);
            case DEATHS -> st.deaths == null ? null : String.valueOf(st.deaths);
            case POINTS -> st.points == null ? null : String.valueOf(st.points);
            case RANKING -> st.ranking == null ? null : String.valueOf(st.ranking);
            case BEDS_DESTROYED -> st.bedsDestroyed == null ? null : String.valueOf(st.bedsDestroyed);
        };
    }
    public static String formatMultiLine(PlayerStats st) {
        if (st == null) return null;

        // feste Reihenfolge nach Enum-Order (oder du machst eine eigene Reihenfolge)
        StringBuilder sb = new StringBuilder();

        for (DisplayMode mode : DisplayMode.values()) {
            if (!DISPLAY_MODES.contains(mode)) continue;

            String v = formatValue(mode, st);
            if (v == null) continue;

            if (sb.length() > 0) sb.append(" | "); // oder "\n" wenn du mehrere Zeilen willst
            sb.append(getDisplayLabel(mode)).append(": ").append(v);
        }

        return sb.length() == 0 ? null : sb.toString();
    }

}

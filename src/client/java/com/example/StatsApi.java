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

public final class StatsApi {
    private static final Logger LOGGER = LoggerFactory.getLogger("StatsReader");

    private static final String KD_ENDPOINT_TEMPLATE =
            "https://bedwarsdatabase.at/api/players/%s/kd";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    // Cache: Name -> KD (nur aus API!)
    public static final Map<String, Double> KD_CACHE = new ConcurrentHashMap<>();
    // Cache Timestamp: Name -> lastUpdatedMs
    private static final Map<String, Long> KD_UPDATED_MS = new ConcurrentHashMap<>();

    // In-flight + cooldown
    private static final Map<String, Boolean> IN_FLIGHT = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_TRY_MS = new ConcurrentHashMap<>();

    // Alle 10 Sekunden pro Player refreshen (wenn TAB offen)
    private static final long COOLDOWN_MS = 10_000;

    private StatsApi() {}

    /** KD holen (kann null sein, wenn noch nicht geladen). */
    public static Double getKd(String name) {
        if (name == null) return null;
        return KD_CACHE.get(name);
    }

    /** true wenn KD älter als 10s (oder fehlt). */
    public static boolean isStale(String name) {
        if (name == null) return true;
        Long t = KD_UPDATED_MS.get(name);
        if (t == null) return true;
        return (System.currentTimeMillis() - t) >= COOLDOWN_MS;
    }

    /** Wenn du /stats checkst: Cache für diesen Spieler invalidieren, damit TAB neu lädt. */
    public static void invalidate(String name) {
        if (name == null) return;
        KD_CACHE.remove(name);
        KD_UPDATED_MS.remove(name);
        LAST_TRY_MS.remove(name);
        IN_FLIGHT.remove(name);
    }

    /** Non-blocking KD Fetch. force=true ignoriert "cache vorhanden" und lädt neu. */
    public static void tryFetchKdAsync(String playerName, boolean force) {
        if (playerName == null || playerName.isBlank()) return;

        if (!force && KD_CACHE.containsKey(playerName) && !isStale(playerName)) return;

        long now = System.currentTimeMillis();
        long last = LAST_TRY_MS.getOrDefault(playerName, 0L);
        if (now - last < COOLDOWN_MS) return;

        if (IN_FLIGHT.putIfAbsent(playerName, true) != null) return;
        LAST_TRY_MS.put(playerName, now);

        String encoded = URLEncoder.encode(playerName, StandardCharsets.UTF_8);
        String url = String.format(KD_ENDPOINT_TEMPLATE, encoded);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();

        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenAccept(res -> {
                    try {
                        if (res.statusCode() / 100 != 2) {
                            LOGGER.info("KD fetch {} -> {} {}", playerName, res.statusCode(), res.body());
                            return;
                        }

                        Double kd = parseKd(res.body());
                        if (kd == null) {
                            LOGGER.info("KD fetch {} -> could not parse body='{}'", playerName, res.body());
                            return;
                        }

                        MinecraftClient.getInstance().execute(() -> {
                            KD_CACHE.put(playerName, kd);
                            KD_UPDATED_MS.put(playerName, System.currentTimeMillis());
                        });
                    } finally {
                        IN_FLIGHT.remove(playerName);
                    }
                })
                .exceptionally(ex -> {
                    IN_FLIGHT.remove(playerName);
                    LOGGER.info("KD fetch failed for {}: {}", playerName, ex.toString());
                    return null;
                });
    }

    private static Double parseKd(String body) {
        if (body == null) return null;
        String s = body.trim();

        try { return Double.parseDouble(s.replace(",", ".")); }
        catch (Exception ignored) {}

        var m = java.util.regex.Pattern.compile("(-?\\d+(?:[\\.,]\\d+)?)").matcher(s);
        if (m.find()) {
            try { return Double.parseDouble(m.group(1).replace(",", ".")); }
            catch (Exception ignored) {}
        }
        return null;
    }
}

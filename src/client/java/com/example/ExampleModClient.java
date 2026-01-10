package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.StatsApi;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.util.Formatting;
import net.minecraft.text.MutableText;


public class ExampleModClient implements ClientModInitializer {

	private static final Logger LOGGER = LoggerFactory.getLogger("StatsReader");
	private static long lastTabRefreshMs = 0;


	// === API ===
	private static final String ENDPOINT = "https://bedwarsdatabase.at/api/mcstats";
	private static final String API_KEY = ""; // optional (wenn du später auth willst)

	private static final long HEADER_TIMEOUT_MS = 2500;   // war 4000
	private static final long FINALIZE_SILENCE_MS = 800;  // war 1500


	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(6))
			.build();

	// === Capture State ===
	private static boolean captureArmed = false;   // /stats wurde eingegeben
	private static boolean captureActive = false;  // Header gefunden, wir sammeln
	private static long lastLineAtMs = 0;

	private static String headerPlayer = null;
	private static String headerPeriod = null;

	private static final Map<String, String> rawFields = new LinkedHashMap<>();

	// "== Statistiken von MineBreakerHD (30 Tage) =="
	private static final Pattern HEADER_RX = Pattern.compile(
			".*Statistiken von\\s+(?<player>.+?)\\s*\\((?<period>.+?)\\)\\s*.*",
			Pattern.CASE_INSENSITIVE
	);

	// "Kills: 304"
	private static final Pattern KV_RX = Pattern.compile("^\\s*(?<key>[^:]+)\\s*:\\s*(?<val>.+?)\\s*$");

	@Override
	public void onInitializeClient() {
		LOGGER.info("HelloStats Mod loaded!");

		// 1) /stats im eigenen Input erkennen (command kommt OHNE Slash)
		ClientSendMessageEvents.COMMAND.register(command -> {
			String c = command.trim();
			if (c.equalsIgnoreCase("stats") || c.toLowerCase().startsWith("stats ")) {
				captureArmed = true;
				captureActive = false;
				lastLineAtMs = System.currentTimeMillis();
				headerPlayer = null;
				headerPeriod = null;
				rawFields.clear();

				var client = MinecraftClient.getInstance();
				if (client.player != null) {
					chat("/stats erkannt – sammle Ausgabe…", Formatting.GRAY);

				}
			}
		});

		// 2) Server-Ausgabe im Chat einsammeln
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			onIncomingLine(message.getString());
		});

		// 3) Auto-Finalize, wenn keine neuen Zeilen mehr kommen
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (!captureArmed) return;

			long now = System.currentTimeMillis();

			// Wenn /stats gedrückt wurde, aber Header kam nie -> abbrechen (4s)
			if (!captureActive && now - lastLineAtMs > HEADER_TIMEOUT_MS) {
				captureArmed = false;
				LOGGER.info("Stats capture timed out (no header).");
				chat("Keine Stats erkannt (Timeout).", Formatting.RED);
				return;
			}

			if (captureActive && now - lastLineAtMs > FINALIZE_SILENCE_MS) {
				captureArmed = false;
				captureActive = false;
				finalizeAndSend();
			}
			// === TAB KD refresh alle 10s ===
			if (client.player == null || client.getNetworkHandler() == null) return;

			boolean tabOpen = client.options.playerListKey.isPressed();
			if (!tabOpen) return;

			long now2 = System.currentTimeMillis();
			if (now2 - lastTabRefreshMs < 10_000) return;
			lastTabRefreshMs = now2;

// alle Spieler, die im TAB stehen, refreshen (API)
			for (var entry : client.getNetworkHandler().getPlayerList()) {
				String name = entry.getProfile().getName();
				StatsApi.tryFetchKdAsync(name, true); // force refresh
			}


		});
	}
	private static MutableText prefix() {
		return Text.literal("StatsReader").formatted(Formatting.AQUA)
				.append(Text.literal(" » ").formatted(Formatting.DARK_GRAY));
	}

	private static void chat(String msg, Formatting color) {
		var client = MinecraftClient.getInstance();
		if (client.player != null) {
			client.player.sendMessage(prefix().append(Text.literal(msg).formatted(color)), false);
		}
	}

	private static void onIncomingLine(String line) {
		if (!captureArmed) return;

		line = line.trim();
		if (line.isEmpty()) return;

		lastLineAtMs = System.currentTimeMillis();

		// Header?
		Matcher hm = HEADER_RX.matcher(line);
		if (hm.matches()) {
			headerPlayer = hm.group("player").trim();
			headerPeriod = hm.group("period").trim();
			captureActive = true;
			LOGGER.info("Stats header: player={}, period={}", headerPlayer, headerPeriod);
			return;
		}

		if (!captureActive) return;

		// Key/Value?
		Matcher km = KV_RX.matcher(line);
		if (km.matches()) {
			String key = km.group("key").trim();
			String val = km.group("val").trim();
			rawFields.put(key, val);
		}
	}

	private static void finalizeAndSend() {
		if (headerPlayer == null || rawFields.size() < 3) {
			LOGGER.info("Finalize: not enough data (player={}, fields={})", headerPlayer, rawFields.size());
			return;
		}

		// ---- Parse aus deinem Screenshot (deutsche Labels) ----
		Integer ranking = parseIntDe(rawFields.get("Position im Ranking"));
		Integer points = parseIntDe(rawFields.get("Punkte"));
		Integer kills = parseIntDe(rawFields.get("Kills"));
		Integer deaths = parseIntDe(rawFields.get("Deaths"));
		Double kd = parseDoubleDe(rawFields.get("K/D"));
		Integer gamesPlayed = parseIntDe(rawFields.get("Gespielte Spiele"));
		Integer gamesWon = parseIntDe(rawFields.get("Gewonnene Spiele"));
		Double winRatePercent = parseDoubleDe(stripPercent(rawFields.get("Siegesquote")));
		Integer bedsDestroyed = parseIntDe(rawFields.get("Zerstörte Betten"));

		// Minimal sanity
		if (kills == null || deaths == null || kd == null || gamesPlayed == null) {
			LOGGER.info("Stats incomplete, not sending.");
			return;
		}

		// ---- DTO genau wie deine API erwartet ----
		Map<String, Object> dto = new LinkedHashMap<>();
		dto.put("player", headerPlayer);

		dto.put("mode", "BedWars");
		dto.put("period", headerPeriod);
		dto.put("ranking", ranking);
		dto.put("points", points);
		dto.put("kills", kills);
		dto.put("deaths", deaths);
		dto.put("kd", kd);
		dto.put("gamesPlayed", gamesPlayed);
		dto.put("gamesWon", gamesWon);
		dto.put("winRatePercent", winRatePercent);
		dto.put("bedsDestroyed", bedsDestroyed);
		dto.put("capturedAtUtc", Instant.now().toString());
		dto.put("source", "lunar-fabric-mod");

// NICHT lokal speichern – aber TAB soll neu von API laden:
		if (headerPlayer != null) {
			StatsApi.invalidate(headerPlayer);
			StatsApi.tryFetchKdAsync(headerPlayer, true);
		}


		String json = toJson(dto);

		HttpRequest.Builder rb = HttpRequest.newBuilder()
				.uri(URI.create(ENDPOINT))
				.timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json));

		if (!API_KEY.isBlank()) rb.header("X-Api-Key", API_KEY);

		HTTP.sendAsync(rb.build(), HttpResponse.BodyHandlers.ofString())
				.thenAccept(res -> {
					LOGGER.info("API POST done: {} {}", res.statusCode(), res.body());
					var client = MinecraftClient.getInstance();
					if (client.player != null) {
						chat("Gesendet (" + res.statusCode() + ")", Formatting.GREEN);

					}
				})
				.exceptionally(ex -> {
					LOGGER.error("API POST failed", ex);
					var client = MinecraftClient.getInstance();
					if (client.player != null) {
						chat("API Fehler: " + ex.getClass().getSimpleName(), Formatting.RED);

					}
					return null;
				});
	}

	// ---- Helpers ----

	private static String stripPercent(String s) {
		return s == null ? null : s.replace("%", "").trim();
	}

	// "10.166" -> 10166, "1.234" -> 1234
	private static Integer parseIntDe(String s) {
		if (s == null) return null;
		s = s.trim().replace(".", "").replace(" ", "");
		int comma = s.indexOf(',');
		if (comma >= 0) s = s.substring(0, comma);
		try { return Integer.parseInt(s); } catch (Exception e) { return null; }
	}

	private static Double parseDoubleDe(String s) {
		if (s == null) return null;
		s = s.trim().replace(" ", "");

		if (s.contains(",") && s.contains(".")) {
			s = s.replace(".", "").replace(",", ".");
		} else if (s.contains(",")) {
			s = s.replace(",", ".");
		}
		// wenn nur "." vorkommt -> als Dezimalpunkt lassen
		try { return Double.parseDouble(s); } catch (Exception e) { return null; }
	}


	// Mini JSON builder (ohne extra libs)
	private static String toJson(Object obj) {
		if (obj == null) return "null";
		if (obj instanceof String s) return "\"" + esc(s) + "\"";
		if (obj instanceof Number || obj instanceof Boolean) return obj.toString();
		if (obj instanceof Map<?, ?> m) {
			StringBuilder sb = new StringBuilder("{");
			boolean first = true;
			for (var e : m.entrySet()) {
				if (!(e.getKey() instanceof String)) continue;
				if (!first) sb.append(",");
				first = false;
				sb.append("\"").append(esc((String) e.getKey())).append("\":").append(toJson(e.getValue()));
			}
			sb.append("}");
			return sb.toString();
		}
		if (obj instanceof Iterable<?> it) {
			StringBuilder sb = new StringBuilder("[");
			boolean first = true;
			for (var v : it) {
				if (!first) sb.append(",");
				first = false;
				sb.append(toJson(v));
			}
			sb.append("]");
			return sb.toString();
		}
		return "\"" + esc(obj.toString()) + "\"";
	}

	private static String esc(String s) {
		return s.replace("\\", "\\\\")
				.replace("\"", "\\\"")
				.replace("\n", "\\n")
				.replace("\r", "\\r")
				.replace("\t", "\\t");
	}
}

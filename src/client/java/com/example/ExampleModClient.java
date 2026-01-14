package com.example;

import com.example.mixin.client.PlayerListHudAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.util.Formatting;
import net.minecraft.text.MutableText;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.Team;

public class ExampleModClient implements ClientModInitializer {

	private static final Logger LOGGER = LoggerFactory.getLogger("StatsReader");
	private static long lastGlobalRefreshMs = 0;



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
			".*statistiken von\\s+(?<player>[^\\(]+?)\\s*\\((?<period>[^\\)]+)\\).*",
			Pattern.CASE_INSENSITIVE
	);


	// "Kills: 304"
	private static final Pattern KV_RX = Pattern.compile("^\\s*(?<key>[^:]+)\\s*:\\s*(?<val>.+?)\\s*$");

	@Override
	public void onInitializeClient() {

		LOGGER.info("HelloStats Mod loaded!");
		ConfigManager.load();
		ModCommands.register();
		// 1) /stats im eigenen Input erkennen (command kommt OHNE Slash)
		ClientSendMessageEvents.COMMAND.register(command -> {
			String c = command.trim().toLowerCase();;
			if (c.equals("stats") || c.startsWith("stats ") || c.startsWith("statsd ")) {
				//debugTabHeaderFooter();
				//if(isInBedwarsTabNoMixin())
				//{
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

				//}

			}
		});

		// 2) Server-Ausgabe im Chat einsammeln
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			onIncomingLine(message.getString());
		});

		// 3) Auto-Finalize, wenn keine neuen Zeilen mehr kommen
		ClientTickEvents.END_CLIENT_TICK.register(client -> {

			// ===== bestehende /stats Logik bleibt wie sie ist =====
			if (captureArmed) {
				long now = System.currentTimeMillis();

				if (!captureActive && now - lastLineAtMs > HEADER_TIMEOUT_MS) {
					captureArmed = false;
					chat("Keine Stats erkannt (Timeout).", Formatting.RED);
					return;
				}


			}

			// ===== NEU: globaler KD-Refresh alle 10 Sekunden =====
			if (client.player == null || client.getNetworkHandler() == null) return;

			long now = System.currentTimeMillis();
			if (now - lastGlobalRefreshMs < 10_000) return;
			lastGlobalRefreshMs = now;

			for (var entry : client.getNetworkHandler().getPlayerList()) {
				String name = entry.getProfile().getName();
				StatsApi.tryFetchStatsAsync(name, true);
			}
		});

	}

	private static void debugTabHeaderFooter() {
		Text header = getTabHeaderViaReflection();
		Text footer = getTabFooterViaReflection();
		System.out.println("[StatsReader] TAB header=" + (header == null ? "null" : "'" + header.getString() + "'"));
		System.out.println("[StatsReader] TAB footer=" + (footer == null ? "null" : "'" + footer.getString() + "'"));
	}
	private static Text getTabHeaderViaReflection() {
		try {
			var client = MinecraftClient.getInstance();
			if (client.inGameHud == null) return null;

			Object hud = client.inGameHud.getPlayerListHud();

			// Feldname in Yarn ist häufig "header" (kann sich ändern)
			Field f = hud.getClass().getDeclaredField("header");
			f.setAccessible(true);
			return (Text) f.get(hud);
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static Text getTabFooterViaReflection() {
		try {
			var client = MinecraftClient.getInstance();
			if (client.inGameHud == null) return null;

			Object hud = client.inGameHud.getPlayerListHud();

			Field f = hud.getClass().getDeclaredField("footer");
			f.setAccessible(true);
			return (Text) f.get(hud);
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static boolean isInBedwarsTabNoMixin() {
		Text header = getTabHeaderViaReflection();
		Text footer = getTabFooterViaReflection();

		String h = header == null ? "" : header.getString().toLowerCase(Locale.ROOT);
		String f = footer == null ? "" : footer.getString().toLowerCase(Locale.ROOT);

		// auf deinem Screenshot steht "GommeHD.net Bedwars"
		return h.contains("bedwars") || h.contains("bad wars")
				|| f.contains("bedwars") || f.contains("bad wars");
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
	private static boolean hasAllFields() {
		return rawFields.containsKey("Punkte")
				&& rawFields.containsKey("Kills")
				&& rawFields.containsKey("Deaths")
				&& rawFields.containsKey("K/D")
				&& rawFields.containsKey("Gespielte Spiele")
				&& rawFields.containsKey("Gewonnene Spiele")
				&& rawFields.containsKey("Siegesquote")
				&& rawFields.containsKey("Zerstörte Betten");
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

		// ✅ End-Marker: ----------------------
		// (kommt am Ende der Stats-Ausgabe)
		if (line.matches("^-{5,}$")) {
			captureArmed = false;
			captureActive = false;
			finalizeAndSend();
			return;
		}

		// Key/Value?
		Matcher km = KV_RX.matcher(line);
		if (km.matches()) {
			String key = km.group("key").trim();
			String val = km.group("val").trim();
			rawFields.put(key, val);
		}
	}



	private static void finalizeAndSend() {
		boolean send = shouldSendToApi(headerPeriod);

		if (!send) {
			// Nur im Chat ausgeben, nicht senden
			chat("Stats erkannt, aber nicht an API gesendet!", Formatting.YELLOW);
			chat("Grund: Zeitraum „" + headerPeriod + "“ wird nicht gespeichert.", Formatting.YELLOW);
			return;
		}

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
		dto.put("source", buildSource());




		String json = toJson(dto);

		HttpRequest.Builder rb = HttpRequest.newBuilder()
				.uri(URI.create(ENDPOINT))
				.timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json));

		if (!API_KEY.isBlank()) rb.header("X-Api-Key", API_KEY);
		final String playerForRefresh = headerPlayer;

		HTTP.sendAsync(rb.build(), HttpResponse.BodyHandlers.ofString())
				.thenAccept(res -> {
					LOGGER.info("API POST done: {} {}", res.statusCode(), res.body());

					// nur bei Erfolg refreshen
					if (res.statusCode() / 100 == 2 && playerForRefresh != null) {
						StatsApi.invalidate(playerForRefresh);
						StatsApi.tryFetchStatsAsync(playerForRefresh, true);

					}

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
	private static String buildSource() {
		MinecraftClient mc = MinecraftClient.getInstance();

		String self =
				mc.player != null
						? mc.player.getGameProfile().getName()
						: "unknown";

		String mcVersion = mc.getGameVersion(); // z.B. 1.21.1
		String modVersion = getModVersion();
		String clientId = ClientId.get();

		return "StatsReader Mod"
				+ ";self=" + self
				+ ";mc=" + mcVersion
				+ ";mod=" + modVersion
				+ ";cid=" + clientId;
	}

	private static String getModVersion() {
		try {
			return net.fabricmc.loader.api.FabricLoader.getInstance()
					.getModContainer("statsreader")

					.map(m -> m.getMetadata().getVersion().getFriendlyString())
					.orElse("unknown");
		} catch (Exception e) {
			return "unknown";
		}
	}

	private static String esc(String s) {
		return s.replace("\\", "\\\\")
				.replace("\"", "\\\"")
				.replace("\n", "\\n")
				.replace("\r", "\\r")
				.replace("\t", "\\t");
	}
	private static boolean shouldSendToApi(String period) {
		if (period == null) return false;
		String p = period.trim().toLowerCase();

		// 30 Tage
		if (p.contains("30")) return true;

		// ALL / Insgesamt (je nach Server-Text)
		if (p.contains("all") || p.contains("insgesamt") || p.contains("gesamt")) return true;

		return false;
	}

}

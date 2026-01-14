package com.example;

import java.util.UUID;
import java.util.prefs.Preferences;

public final class ClientId {
    private static final String KEY = "client_id";
    private static final Preferences PREFS =
            Preferences.userRoot().node("bedwarsdatabase/statsreader");

    private ClientId() {}

    public static String get() {
        try {
            String existing = PREFS.get(KEY, null);
            if (existing != null && !existing.isBlank()) return existing;

            String created = UUID.randomUUID().toString();
            PREFS.put(KEY, created);
            PREFS.flush();
            return created;
        } catch (Exception ignored) {
            return "TEMP-" + UUID.randomUUID();
        }
    }
}

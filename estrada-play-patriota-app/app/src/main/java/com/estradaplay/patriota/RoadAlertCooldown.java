package com.estradaplay.patriota;

import java.util.HashMap;
import java.util.Map;

/** BASE_CONSOLIDADA_V210: deterministic alert dedupe/cooldown, independent from Android. */
final class RoadAlertCooldown {
    private final long ttlMs;
    private final int maxEntries;
    private final Map<String, Long> alertedAt = new HashMap<>();

    RoadAlertCooldown(long ttlMs, int maxEntries) {
        this.ttlMs = Math.max(1L, ttlMs);
        this.maxEntries = Math.max(16, maxEntries);
    }

    boolean shouldAlert(String id, long nowMs) {
        Long when = alertedAt.get(key(id));
        return when == null || nowMs - when > ttlMs;
    }

    void remember(String id, long nowMs) {
        alertedAt.put(key(id), nowMs);
        if (alertedAt.size() > maxEntries) prune(nowMs);
    }

    int size() { return alertedAt.size(); }

    void prune(long nowMs) {
        alertedAt.entrySet().removeIf(e -> nowMs - e.getValue() > ttlMs);
        if (alertedAt.size() <= maxEntries) return;
        String oldestKey = null;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<String, Long> e : alertedAt.entrySet()) {
            if (e.getValue() < oldest) { oldest = e.getValue(); oldestKey = e.getKey(); }
        }
        if (oldestKey != null) alertedAt.remove(oldestKey);
    }

    private static String key(String id) { return id == null ? "" : id; }
}
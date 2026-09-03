package com.estradaplay.comunista;

/** CONTEXTO_VIVO_V220: pure cadence policy for background Server 7.0 refreshes. */
final class RouteContextV7BackgroundPolicy {
    private RouteContextV7BackgroundPolicy() {}

    static long cadenceMs(double speedKmh, boolean hasDestination) {
        double speed = Double.isFinite(speedKmh) ? Math.max(0.0, speedKmh) : 0.0;
        if (speed < 3.0) return 120_000L;
        if (hasDestination) return 45_000L;
        return 60_000L;
    }

    static boolean shouldRefresh(long nowMs, long lastAttemptMs, double speedKmh, boolean hasDestination) {
        if (lastAttemptMs <= 0L) return true;
        long elapsed = Math.max(0L, nowMs - lastAttemptMs);
        return elapsed >= cadenceMs(speedKmh, hasDestination);
    }
}

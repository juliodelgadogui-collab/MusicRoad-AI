package com.estradaplay.patriota;

/** COMBOIO_AVANCADO_V230: pure cadence/presence policy shared by live convoy code and tests. */
final class ConvoySyncPolicy {
    private ConvoySyncPolicy() {}

    static long cadenceMs(double speedKmh) {
        return speedKmh >= 5.0 ? 6_000L : 12_000L;
    }

    static boolean shouldSync(long nowMs, long lastAttemptMs, double speedKmh) {
        return lastAttemptMs <= 0L || nowMs - lastAttemptMs >= cadenceMs(speedKmh);
    }

    static String separation(double meters) {
        if (!Double.isFinite(meters) || meters < 0) return "OK";
        if (meters > 3500) return "DISTANTE";
        if (meters > 1500) return "ATENCAO";
        return "OK";
    }

    static boolean offRoute(double deviationM) {
        return Double.isFinite(deviationM) && deviationM > 180.0;
    }
}

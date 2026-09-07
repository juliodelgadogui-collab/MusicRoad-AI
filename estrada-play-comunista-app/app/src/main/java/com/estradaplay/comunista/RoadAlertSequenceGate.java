package com.estradaplay.comunista;

/**
 * ALERT_SEQUENCE_V330: spaces different safety warnings so one warning is not cut by the next.
 * Critical, very-near hazards can still break through after a shorter safety gap.
 */
final class RoadAlertSequenceGate {
    private static String lastHazardId = "";
    private static String lastType = "";
    private static long lastAcceptedAt;
    private static double lastForwardM = Double.POSITIVE_INFINITY;

    private RoadAlertSequenceGate() {}

    static synchronized boolean allow(RoadHazard hazard, RoadHazardMatcher.Match match,
                                      double speedKmh, long nowMs) {
        if (hazard == null || match == null || !match.valid) return false;
        if (lastAcceptedAt <= 0 || nowMs - lastAcceptedAt > 30_000L) return true;
        if (hazard.id != null && hazard.id.equals(lastHazardId)) return true;

        long gap = minimumGapMs(speedKmh, hazard.type, match.forwardM);
        long elapsed = nowMs - lastAcceptedAt;
        if (elapsed >= gap) return true;

        // A bump/rail crossing that is already extremely close must not be suppressed for too long.
        boolean criticalType = "QUEBRA_MOLAS".equals(hazard.type) || "PASSAGEM_NIVEL".equals(hazard.type);
        boolean criticalNear = criticalType && match.forwardM >= 0 && match.forwardM <= Math.max(85.0, speedKmh * 1.45);
        if (criticalNear && elapsed >= 2_800L) return true;

        // If the new hazard is materially closer than the previous one became when announced,
        // allow it slightly sooner, but never immediately.
        if (match.forwardM + 160.0 < lastForwardM && elapsed >= 3_600L) return true;
        return false;
    }

    static synchronized void remember(RoadHazard hazard, RoadHazardMatcher.Match match, long nowMs) {
        if (hazard == null || match == null || !match.valid) return;
        lastHazardId = hazard.id == null ? "" : hazard.id;
        lastType = hazard.type == null ? "" : hazard.type;
        lastAcceptedAt = nowMs;
        lastForwardM = match.forwardM;
    }

    /** Test isolation only; production code never calls this. */
    static synchronized void resetForTests() {
        lastHazardId = "";
        lastType = "";
        lastAcceptedAt = 0L;
        lastForwardM = Double.POSITIVE_INFINITY;
    }

    private static long minimumGapMs(double speedKmh, String type, double forwardM) {
        long gap = speedKmh >= 90 ? 4_800L : 5_600L;
        if ("CAMERA_MONITORAMENTO".equals(type)) gap += 900L;
        if (forwardM > 700) gap += 700L;
        if ("QUEBRA_MOLAS".equals(lastType) || "PASSAGEM_NIVEL".equals(lastType)) gap += 500L;
        return gap;
    }
}

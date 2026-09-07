package com.estradaplay.comunista;

/**
 * DRIVE_QUALITY_V330: low-cost rolling pace model used only to stabilize the ETA.
 * It never changes the calculated route. Stops are blended slowly so one red light does not
 * destroy the ETA, while sustained congestion gradually increases the remaining time.
 */
final class RouteTravelPace {
    private static double movingSpeedEmaKmh = Double.NaN;
    private static double stoppedEma;
    private static long lastAt;
    private static int samples;

    private RouteTravelPace() {}

    static synchronized void observe(double speedKmh, long nowMs) {
        if (!Double.isFinite(speedKmh) || speedKmh < 0 || speedKmh > 220 || nowMs <= 0) return;
        if (lastAt > 0 && nowMs - lastAt > 90_000L) {
            movingSpeedEmaKmh = Double.NaN;
            stoppedEma = 0;
            samples = 0;
        }
        lastAt = nowMs;
        samples = Math.min(10_000, samples + 1);
        double stop = speedKmh < 4.0 ? 1.0 : 0.0;
        stoppedEma = stoppedEma * 0.965 + stop * 0.035;
        if (speedKmh >= 5.0) {
            movingSpeedEmaKmh = Double.isFinite(movingSpeedEmaKmh)
                    ? movingSpeedEmaKmh * 0.94 + speedKmh * 0.06
                    : speedKmh;
        }
    }

    static synchronized double estimateRemaining(double remainingM, double baselineSeconds) {
        if (!Double.isFinite(remainingM) || remainingM <= 0) return 0;
        if (!Double.isFinite(baselineSeconds) || baselineSeconds <= 0) return 0;
        if (samples < 20 || !Double.isFinite(movingSpeedEmaKmh) || remainingM < 1500) return baselineSeconds;
        double plannedKmh = remainingM / baselineSeconds * 3.6;
        if (!Double.isFinite(plannedKmh) || plannedKmh < 8.0) return baselineSeconds;
        double effectiveKmh = movingSpeedEmaKmh * (1.0 - Math.min(0.62, stoppedEma * 0.62));
        effectiveKmh = clamp(effectiveKmh, plannedKmh * 0.42, plannedKmh * 1.45);
        double observedSeconds = remainingM / Math.max(2.0, effectiveKmh / 3.6);
        double weight = clamp((samples - 20) / 260.0, 0.0, 0.38);
        double blended = baselineSeconds * (1.0 - weight) + observedSeconds * weight;
        return clamp(blended, baselineSeconds * 0.72, baselineSeconds * 1.85);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}

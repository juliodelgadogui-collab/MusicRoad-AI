package com.estradaplay.patriota;

/** BASE_CONSOLIDADA_V210: pure speed-limit state machine. */
final class RoadLimitPolicy {
    private int currentLimitKmh;
    private int announcedLimitKmh;
    private boolean overspeedWarned;

    int currentLimit() { return currentLimitKmh; }
    int announcedLimit() { return announcedLimitKmh; }

    boolean applyLimit(int limitKmh) {
        if (limitKmh < 10 || limitKmh > 180) return false;
        boolean changed = currentLimitKmh != limitKmh;
        currentLimitKmh = limitKmh;
        if (changed) overspeedWarned = false;
        return true;
    }

    boolean shouldAnnounceLimit() {
        return currentLimitKmh > 0 && currentLimitKmh != announcedLimitKmh;
    }

    void markLimitAnnounced() { announcedLimitKmh = currentLimitKmh; }

    boolean observeSpeedAndShouldWarn(double speedKmh) {
        if (currentLimitKmh <= 0 || !Double.isFinite(speedKmh)) return false;
        if (speedKmh <= currentLimitKmh) {
            overspeedWarned = false;
            return false;
        }
        return !overspeedWarned && speedKmh >= currentLimitKmh + 2.0;
    }

    void markOverspeedWarned() { overspeedWarned = true; }
    boolean overspeedWarned() { return overspeedWarned; }
}
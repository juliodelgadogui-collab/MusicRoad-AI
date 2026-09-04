package com.estradaplay.patriota;

import java.util.List;

/** BASE_CONSOLIDADA_V210: selects current and next hazard outside the Android Service. */
final class RoadHazardSelector {
    static final class Selection {
        final RoadHazard best;
        final RoadHazardMatcher.Match bestMatch;
        final RoadHazard next;
        final RoadHazardMatcher.Match nextMatch;

        Selection(RoadHazard best, RoadHazardMatcher.Match bestMatch,
                  RoadHazard next, RoadHazardMatcher.Match nextMatch) {
            this.best = best;
            this.bestMatch = bestMatch == null ? RoadHazardMatcher.Match.no() : bestMatch;
            this.next = next;
            this.nextMatch = nextMatch == null ? RoadHazardMatcher.Match.no() : nextMatch;
        }

        static Selection empty() { return new Selection(null, null, null, null); }
    }

    private RoadHazardSelector() {}

    static Selection select(List<RoadHazard> nearby, double lat, double lon, double heading,
                            double speedKmh, boolean rain, RoadAlertCooldown cooldown, long nowMs) {
        if (nearby == null || nearby.isEmpty() || !Double.isFinite(heading) || speedKmh < 3.0) {
            return Selection.empty();
        }

        RoadHazard best = null;
        RoadHazardMatcher.Match bestMatch = null;
        double bestScore = Double.MAX_VALUE;
        for (RoadHazard hazard : nearby) {
            if (hazard == null || (cooldown != null && !cooldown.shouldAlert(hazard.id, nowMs))) continue;
            RoadHazardMatcher.Match match = RoadHazardMatcher.match(lat, lon, heading, speedKmh, hazard, rain);
            if (!match.valid) continue;
            double score = match.forwardM + RoadHazardMatcher.priorityBias(hazard.type);
            if (score < bestScore) {
                best = hazard;
                bestMatch = match;
                bestScore = score;
            }
        }
        if (best == null || bestMatch == null) return Selection.empty();

        RoadHazard next = null;
        RoadHazardMatcher.Match nextMatch = null;
        double nextScore = Double.MAX_VALUE;
        for (RoadHazard hazard : nearby) {
            if (hazard == null || hazard.id.equals(best.id)
                    || (cooldown != null && !cooldown.shouldAlert(hazard.id, nowMs))) continue;
            RoadHazardMatcher.Match match = RoadHazardMatcher.match(lat, lon, heading, speedKmh, hazard, rain);
            if (!match.valid || match.forwardM < bestMatch.forwardM + 15.0) continue;
            double score = match.forwardM + RoadHazardMatcher.priorityBias(hazard.type);
            if (score < nextScore) {
                next = hazard;
                nextMatch = match;
                nextScore = score;
            }
        }
        return new Selection(best, bestMatch, next, nextMatch);
    }
}
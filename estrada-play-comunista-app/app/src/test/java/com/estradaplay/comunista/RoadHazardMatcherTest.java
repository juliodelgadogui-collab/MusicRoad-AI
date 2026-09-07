package com.estradaplay.comunista;

import org.junit.Test;

import static org.junit.Assert.*;

public class RoadHazardMatcherTest {
    private static RoadHazard north(String id, String type, double meters, double lateralMeters, int speed) {
        double lat = meters / 110540.0;
        double lon = lateralMeters / 111320.0;
        return new RoadHazard(id, type, lat, lon, "BR-000", speed, Double.NaN, "TEST");
    }

    @Test public void alignedForwardRadarMatches() {
        RoadHazardMatcher.Match m = RoadHazardMatcher.match(0, 0, 0, 80, north("r1", "RADAR", 500, 5, 80), false);
        assertTrue(m.valid);
        assertTrue(m.forwardM > 480 && m.forwardM < 520);
        assertTrue(m.lateralM < 10);
    }

    @Test public void hazardBehindIsRejected() {
        RoadHazard behind = new RoadHazard("r2", "RADAR", -300 / 110540.0, 0, "BR-000", 80, Double.NaN, "TEST");
        assertFalse(RoadHazardMatcher.match(0, 0, 0, 80, behind, false).valid);
    }

    @Test public void farLateralHazardIsRejected() {
        assertFalse(RoadHazardMatcher.match(0, 0, 0, 80, north("r3", "RADAR", 350, 180, 80), false).valid);
    }

    @Test public void rainExtendsForwardSafetyWindow() {
        // At 60 km/h the dynamic dry radar window is ~902 m and the rainy window ~1064 m.
        RoadHazard far = north("r4", "RADAR", 980, 0, 80);
        assertFalse(RoadHazardMatcher.match(0, 0, 0, 60, far, false).valid);
        assertTrue(RoadHazardMatcher.match(0, 0, 0, 60, far, true).valid);
    }

    @Test public void headingMismatchIsRejected() {
        RoadHazard oppositeDirection = new RoadHazard("r5", "RADAR", 400 / 110540.0, 0, "BR-000", 80, 180, "TEST");
        assertFalse(RoadHazardMatcher.match(0, 0, 0, 80, oppositeDirection, false).valid);
    }

    @Test public void priorityBiasKeepsPhysicalBumpHighlyRelevant() {
        assertTrue(RoadHazardMatcher.priorityBias("QUEBRA_MOLAS") < RoadHazardMatcher.priorityBias("RADAR"));
        assertTrue(RoadHazardMatcher.priorityBias("RADAR") < RoadHazardMatcher.priorityBias("SEMAFORO"));
    }
}

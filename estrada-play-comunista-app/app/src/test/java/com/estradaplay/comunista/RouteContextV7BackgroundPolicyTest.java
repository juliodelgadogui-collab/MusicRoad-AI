package com.estradaplay.comunista;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public final class RouteContextV7BackgroundPolicyTest {
    @Test public void stationaryUsesTwoMinuteCadence() {
        assertEquals(120_000L, RouteContextV7BackgroundPolicy.cadenceMs(0, false));
        assertEquals(120_000L, RouteContextV7BackgroundPolicy.cadenceMs(2.9, true));
    }

    @Test public void movingWithDestinationRefreshesFaster() {
        assertEquals(45_000L, RouteContextV7BackgroundPolicy.cadenceMs(40, true));
        assertEquals(60_000L, RouteContextV7BackgroundPolicy.cadenceMs(40, false));
    }

    @Test public void refreshGateHonorsElapsedTime() {
        long now = 1_000_000L;
        assertTrue(RouteContextV7BackgroundPolicy.shouldRefresh(now, 0L, 60, true));
        assertFalse(RouteContextV7BackgroundPolicy.shouldRefresh(now, now - 44_999L, 60, true));
        assertTrue(RouteContextV7BackgroundPolicy.shouldRefresh(now, now - 45_000L, 60, true));
    }
}

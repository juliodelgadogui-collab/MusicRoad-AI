package com.estradaplay.patriota;

import org.junit.Test;

import static org.junit.Assert.*;

public class ConvoySyncPolicyTest {
    @Test public void movingSyncsNearRealtime() {
        assertEquals(6000L, ConvoySyncPolicy.cadenceMs(80));
        assertFalse(ConvoySyncPolicy.shouldSync(5000L, 1000L, 80));
        assertTrue(ConvoySyncPolicy.shouldSync(7000L, 1000L, 80));
    }

    @Test public void stoppedUsesLowerFrequency() {
        assertEquals(12000L, ConvoySyncPolicy.cadenceMs(0));
        assertFalse(ConvoySyncPolicy.shouldSync(11000L, 1000L, 0));
        assertTrue(ConvoySyncPolicy.shouldSync(13000L, 1000L, 0));
    }

    @Test public void separationThresholdsAreStable() {
        assertEquals("OK", ConvoySyncPolicy.separation(1200));
        assertEquals("ATENCAO", ConvoySyncPolicy.separation(2000));
        assertEquals("DISTANTE", ConvoySyncPolicy.separation(4000));
    }

    @Test public void routeDeviationUsesSafetyThreshold() {
        assertFalse(ConvoySyncPolicy.offRoute(180));
        assertTrue(ConvoySyncPolicy.offRoute(181));
    }
}

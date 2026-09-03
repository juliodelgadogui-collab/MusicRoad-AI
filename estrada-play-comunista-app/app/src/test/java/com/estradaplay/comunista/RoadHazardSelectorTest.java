package com.estradaplay.comunista;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public class RoadHazardSelectorTest {
    private static RoadHazard north(String id, String type, double meters) {
        return new RoadHazard(id, type, meters / 110540.0, 0, "BR-000", 80, Double.NaN, "TEST");
    }

    @Test public void selectorReturnsBestAndNextAhead() {
        RoadAlertCooldown cooldown = new RoadAlertCooldown(1_000, 32);
        RoadHazard first = north("bump", "QUEBRA_MOLAS", 250);
        RoadHazard second = north("radar", "RADAR", 500);
        RoadHazardSelector.Selection s = RoadHazardSelector.select(
                Arrays.asList(second, first), 0, 0, 0, 80, false, cooldown, 10_000);
        assertNotNull(s.best);
        assertEquals("bump", s.best.id);
        assertNotNull(s.next);
        assertEquals("radar", s.next.id);
        assertTrue(s.nextMatch.forwardM > s.bestMatch.forwardM);
    }

    @Test public void cooldownRemovesPreviouslyAnnouncedCandidate() {
        RoadAlertCooldown cooldown = new RoadAlertCooldown(10_000, 32);
        RoadHazard first = north("bump", "QUEBRA_MOLAS", 250);
        RoadHazard second = north("radar", "RADAR", 500);
        cooldown.remember("bump", 9_000);
        RoadHazardSelector.Selection s = RoadHazardSelector.select(
                Arrays.asList(first, second), 0, 0, 0, 80, false, cooldown, 10_000);
        assertNotNull(s.best);
        assertEquals("radar", s.best.id);
    }
}
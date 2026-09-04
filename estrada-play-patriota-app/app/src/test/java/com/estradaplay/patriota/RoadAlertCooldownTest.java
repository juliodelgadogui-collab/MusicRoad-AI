package com.estradaplay.patriota;

import org.junit.Test;

import static org.junit.Assert.*;

public class RoadAlertCooldownTest {
    @Test public void firstAlertIsAllowedThenBlockedInsideTtl() {
        RoadAlertCooldown cooldown = new RoadAlertCooldown(1_000, 32);
        assertTrue(cooldown.shouldAlert("a", 10_000));
        cooldown.remember("a", 10_000);
        assertFalse(cooldown.shouldAlert("a", 10_500));
    }

    @Test public void alertReturnsAfterTtl() {
        RoadAlertCooldown cooldown = new RoadAlertCooldown(1_000, 32);
        cooldown.remember("a", 10_000);
        assertTrue(cooldown.shouldAlert("a", 11_001));
    }

    @Test public void pruneRemovesExpiredEntries() {
        RoadAlertCooldown cooldown = new RoadAlertCooldown(100, 16);
        for (int i = 0; i < 20; i++) cooldown.remember("old-" + i, i);
        cooldown.prune(10_000);
        assertEquals(0, cooldown.size());
    }
}
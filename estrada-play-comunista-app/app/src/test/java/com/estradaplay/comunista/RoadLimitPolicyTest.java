package com.estradaplay.comunista;

import org.junit.Test;

import static org.junit.Assert.*;

public class RoadLimitPolicyTest {
    @Test public void invalidLimitsAreIgnored() {
        RoadLimitPolicy p = new RoadLimitPolicy();
        assertFalse(p.applyLimit(0));
        assertFalse(p.applyLimit(181));
        assertEquals(0, p.currentLimit());
    }

    @Test public void limitAnnouncementIsOneShotUntilLimitChanges() {
        RoadLimitPolicy p = new RoadLimitPolicy();
        assertTrue(p.applyLimit(60));
        assertTrue(p.shouldAnnounceLimit());
        p.markLimitAnnounced();
        assertFalse(p.shouldAnnounceLimit());
        p.applyLimit(80);
        assertTrue(p.shouldAnnounceLimit());
    }

    @Test public void overspeedWarningRearmsAfterReturningBelowLimit() {
        RoadLimitPolicy p = new RoadLimitPolicy();
        p.applyLimit(60);
        assertFalse(p.observeSpeedAndShouldWarn(61));
        assertTrue(p.observeSpeedAndShouldWarn(62));
        p.markOverspeedWarned();
        assertFalse(p.observeSpeedAndShouldWarn(90));
        assertFalse(p.observeSpeedAndShouldWarn(60));
        assertTrue(p.observeSpeedAndShouldWarn(65));
    }

    @Test public void limitChangeRearmsOverspeed() {
        RoadLimitPolicy p = new RoadLimitPolicy();
        p.applyLimit(60);
        assertTrue(p.observeSpeedAndShouldWarn(70));
        p.markOverspeedWarned();
        p.applyLimit(80);
        assertTrue(p.observeSpeedAndShouldWarn(90));
    }
}
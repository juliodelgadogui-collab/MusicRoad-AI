package com.estradaplay.patriota;

import org.junit.Test;

import static org.junit.Assert.*;

public class RoadSafetyFormatTest {
    @Test public void textRoundsMetersForCockpit() {
        assertEquals("10 m", RoadSafetyFormat.distanceText(4));
        assertEquals("260 m", RoadSafetyFormat.distanceText(257));
    }

    @Test public void textUsesKilometersAtOneKm() {
        assertTrue(RoadSafetyFormat.distanceText(1250).contains("1"));
        assertTrue(RoadSafetyFormat.distanceText(1250).contains("km"));
    }

    @Test public void speechKeepsMinimumThirtyMeters() {
        assertEquals("30 metros", RoadSafetyFormat.distanceSpeech(4));
        assertEquals("150 metros", RoadSafetyFormat.distanceSpeech(130));
    }
}
package com.estradaplay.comunista;

import org.junit.Test;
import static org.junit.Assert.*;

public class RoadRadioRtcPolicyTest {
    @Test public void emptyRoomNeverStartsNativeAudio() {
        assertFalse(RoadRadioRtcPolicy.shouldStartRtc(true, 0));
    }

    @Test public void peerAllowsRtcOnlyAfterJoin() {
        assertFalse(RoadRadioRtcPolicy.shouldStartRtc(false, 1));
        assertTrue(RoadRadioRtcPolicy.shouldStartRtc(true, 1));
    }

    @Test public void pttNeedsPeerAndSafeAudioState() {
        assertFalse(RoadRadioRtcPolicy.canTransmit(true, false, false, 0, true));
        assertFalse(RoadRadioRtcPolicy.canTransmit(true, true, false, 1, true));
        assertFalse(RoadRadioRtcPolicy.canTransmit(true, false, true, 1, true));
        assertFalse(RoadRadioRtcPolicy.canTransmit(true, false, false, 1, false));
        assertTrue(RoadRadioRtcPolicy.canTransmit(true, false, false, 1, true));
    }
}

package com.estradaplay.comunista;

/** Pure policy for deciding when native WebRTC audio is allowed to start. */
final class RoadRadioRtcPolicy {
    private RoadRadioRtcPolicy() {}

    static boolean shouldStartRtc(boolean joined, int otherPeerCount) {
        return joined && otherPeerCount > 0;
    }

    static boolean canTransmit(boolean joined, boolean muted, boolean safetyMuted,
                               int connectedPeerCount, boolean rtcReady) {
        return joined && !muted && !safetyMuted && connectedPeerCount > 0 && rtcReady;
    }
}

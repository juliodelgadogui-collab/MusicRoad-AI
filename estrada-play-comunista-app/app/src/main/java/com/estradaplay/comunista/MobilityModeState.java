package com.estradaplay.comunista;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

/**
 * Internal movement classifier. It deliberately stays out of the normal UI.
 * Pedestrian mode is only confirmed after one continuous minute compatible with walking.
 */
final class MobilityModeState {
    static final int UNKNOWN = 0;
    static final int STATIONARY = 1;
    static final int PEDESTRIAN = 2;
    static final int VEHICLE = 3;

    private static final String PREF = "epc_mobility_mode_v1";
    private static final String KEY_MODE = "mode";
    private static final String KEY_CHANGED_AT = "changed_at";

    private static final long WALK_CONFIRM_MS = 60_000L;
    private static final long VEHICLE_CONFIRM_MS = 8_000L;
    private static final long STOP_CONFIRM_MS = 12_000L;
    private static final double WALK_MIN_KMH = 0.7;
    private static final double WALK_MAX_KMH = 8.5;
    private static final double VEHICLE_MIN_KMH = 12.0;
    private static final double VEHICLE_FAST_KMH = 18.0;
    private static final double STOP_MAX_KMH = 0.9;

    private static volatile int mode = UNKNOWN;
    private static volatile long walkCandidateAt;
    private static volatile long vehicleCandidateAt;
    private static volatile long stoppedCandidateAt;
    private static volatile boolean leftVehicleThroughStop;

    private MobilityModeState() {}

    static void restore(Context context) {
        try {
            SharedPreferences p = context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
            int saved = p.getInt(KEY_MODE, UNKNOWN);
            mode = saved >= UNKNOWN && saved <= VEHICLE ? saved : UNKNOWN;
        } catch (Throwable ignored) {
            mode = UNKNOWN;
        }
    }

    static boolean isPedestrian() { return mode == PEDESTRIAN; }
    static boolean isVehicle() { return mode == VEHICLE; }
    static int current() { return mode; }

    static String label() {
        switch (mode) {
            case STATIONARY: return "PARADO";
            case PEDESTRIAN: return "A_PE";
            case VEHICLE: return "VEICULO";
            default: return "INDEFINIDO";
        }
    }

    private static void update(Context context, double speedKmh) {
        if (!Double.isFinite(speedKmh) || speedKmh < 0) return;
        long now = SystemClock.elapsedRealtime();

        // A clearly automotive speed wins quickly so road protection returns without delay.
        if (speedKmh >= VEHICLE_FAST_KMH) {
            setMode(context, VEHICLE);
            resetCandidates();
            return;
        }

        if (speedKmh >= VEHICLE_MIN_KMH) {
            walkCandidateAt = 0L;
            stoppedCandidateAt = 0L;
            if (vehicleCandidateAt == 0L) vehicleCandidateAt = now;
            if (now - vehicleCandidateAt >= VEHICLE_CONFIRM_MS) {
                setMode(context, VEHICLE);
                leftVehicleThroughStop = false;
            }
            return;
        }
        vehicleCandidateAt = 0L;

        if (speedKmh <= STOP_MAX_KMH) {
            walkCandidateAt = 0L;
            if (stoppedCandidateAt == 0L) stoppedCandidateAt = now;
            if (now - stoppedCandidateAt >= STOP_CONFIRM_MS) {
                if (mode == VEHICLE) leftVehicleThroughStop = true;
                setMode(context, STATIONARY);
            }
            return;
        }
        stoppedCandidateAt = 0L;

        if (speedKmh >= WALK_MIN_KMH && speedKmh <= WALK_MAX_KMH) {
            // If we were just driving, require an actual stop before interpreting low-speed motion as walking.
            // This avoids classifying one minute of congestion as pedestrian whenever possible.
            if (mode == VEHICLE && !leftVehicleThroughStop) {
                walkCandidateAt = 0L;
                return;
            }
            if (walkCandidateAt == 0L) walkCandidateAt = now;
            if (now - walkCandidateAt >= WALK_CONFIRM_MS) {
                setMode(context, PEDESTRIAN);
                leftVehicleThroughStop = false;
            }
            return;
        }

        // Ambiguous 8.5–12 km/h movement keeps the current mode and cancels pedestrian confirmation.
        walkCandidateAt = 0L;
    }

    private static void setMode(Context context, int next) {
        if (mode == next) return;
        mode = next;
        try {
            context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putInt(KEY_MODE, next).putLong(KEY_CHANGED_AT, System.currentTimeMillis()).apply();
        } catch (Throwable ignored) {}
    }

    private static void resetCandidates() {
        walkCandidateAt = 0L;
        vehicleCandidateAt = 0L;
        stoppedCandidateAt = 0L;
        leftVehicleThroughStop = false;
    }

    /** Receives the already-existing GPS state broadcast; no second GPS listener is created. */
    static final class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            double speed = intent.getDoubleExtra("speed_kmh", Double.NaN);
            update(context, speed);
        }
    }
}

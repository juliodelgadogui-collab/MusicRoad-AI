package com.estradaplay.comunista;

import java.util.Locale;

/** BASE_CONSOLIDADA_V210: formatting helpers shared by the safety coordinator. */
final class RoadSafetyFormat {
    private RoadSafetyFormat() {}

    static String distanceSpeech(double meters) {
        double m = Math.max(0, meters);
        if (m < 120) return Math.max(30, (int)(Math.round(m / 10.0) * 10)) + " metros";
        if (m >= 1000) return String.format(Locale.getDefault(), "%.1f quilômetros", m / 1000.0);
        return Math.max(100, (int)(Math.round(m / 50.0) * 50)) + " metros";
    }

    static String distanceText(double meters) {
        double m = Math.max(0, meters);
        if (m >= 1000) return String.format(Locale.getDefault(), "%.1f km", m / 1000.0);
        return Math.max(10, (int)(Math.round(m / 10.0) * 10)) + " m";
    }
}
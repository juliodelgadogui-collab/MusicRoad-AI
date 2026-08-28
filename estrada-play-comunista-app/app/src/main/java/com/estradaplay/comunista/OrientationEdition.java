package com.estradaplay.comunista;

final class OrientationEdition {
    private OrientationEdition() {}

    static boolean vertical() {
        return "vertical".equals(BuildConfig.FIXED_LAYOUT);
    }

    static boolean horizontal() {
        return "horizontal".equals(BuildConfig.FIXED_LAYOUT);
    }
}

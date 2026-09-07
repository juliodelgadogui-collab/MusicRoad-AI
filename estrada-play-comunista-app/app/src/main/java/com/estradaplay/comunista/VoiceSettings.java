package com.estradaplay.comunista;

import android.content.Context;
import android.content.SharedPreferences;

// STABILITY_V152: exactly one runtime voice engine is selected at a time.
final class VoiceSettings {
    static final int MODE_AUTO = 0; // legacy value; migrated to embedded when the bank exists.
    static final int MODE_EMBEDDED = 1;
    static final int MODE_ANDROID = 2;
    private static final String PREFS = "epc_voice_settings_v151";
    private static final String KEY_MODE = "mode";
    private static final String KEY_MIGRATED_152 = "stability_152_single_voice";
    private VoiceSettings() {}

    static int mode(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int stored = p.getInt(KEY_MODE, MODE_EMBEDDED);
        if (!p.getBoolean(KEY_MIGRATED_152, false)) {
            stored = stored == MODE_ANDROID ? MODE_ANDROID : MODE_EMBEDDED;
            p.edit().putInt(KEY_MODE, stored).putBoolean(KEY_MIGRATED_152, true).apply();
        }
        if (stored == MODE_ANDROID) return MODE_ANDROID;
        // VOICE_FAILSAFE_V302: an incomplete/missing embedded bank must never make
        // safety alerts silent. Fall back at runtime without changing the user's setting.
        return embeddedBankAvailable(c) ? MODE_EMBEDDED : MODE_ANDROID;
    }

    static void setMode(Context c, int m) {
        int safe = m == MODE_ANDROID ? MODE_ANDROID : MODE_EMBEDDED;
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_MODE, safe).putBoolean(KEY_MIGRATED_152, true).apply();
    }

    static String label(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int stored = p.getInt(KEY_MODE, MODE_EMBEDDED);
        if (stored != MODE_ANDROID && !embeddedBankAvailable(c)) return "TTS DO ANDROID · FALLBACK";
        return mode(c) == MODE_ANDROID ? "TTS DO ANDROID" : "VOZ EMBARCADA";
    }

    private static boolean embeddedBankAvailable(Context c) {
        if (c == null) return false;
        try {
            // These clips cover the two most important deterministic safety paths.
            // Requiring both prevents a partially packaged bank from silently dropping bumps.
            int attention = c.getResources().getIdentifier("ep_atencao", "raw", c.getPackageName());
            int bump = c.getResources().getIdentifier("ep_quebra_molas_frente", "raw", c.getPackageName());
            int radar = c.getResources().getIdentifier("ep_radar_frente", "raw", c.getPackageName());
            return attention != 0 && bump != 0 && radar != 0;
        } catch (Throwable ignored) {
            return false;
        }
    }
}

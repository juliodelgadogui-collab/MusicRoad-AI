package com.estradaplay.comunista;

import android.content.Context;
import android.content.SharedPreferences;

// STABILITY_V152: exactly one runtime voice engine is selected at a time.
final class VoiceSettings {
    static final int MODE_AUTO = 0; // legacy value; migrated to embedded.
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
            // Previous AUTO could alternate between embedded and Android TTS.
            // Existing explicit Android selection is preserved; everything else
            // moves to the deterministic embedded bank.
            stored = stored == MODE_ANDROID ? MODE_ANDROID : MODE_EMBEDDED;
            p.edit().putInt(KEY_MODE, stored).putBoolean(KEY_MIGRATED_152, true).apply();
        }
        return stored == MODE_ANDROID ? MODE_ANDROID : MODE_EMBEDDED;
    }

    static void setMode(Context c, int m) {
        int safe = m == MODE_ANDROID ? MODE_ANDROID : MODE_EMBEDDED;
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_MODE, safe).putBoolean(KEY_MIGRATED_152, true).apply();
    }

    static String label(Context c) {
        return mode(c) == MODE_ANDROID ? "TTS DO ANDROID" : "VOZ EMBARCADA";
    }
}

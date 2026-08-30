package com.estradaplay.comunista;

import android.content.Context;
import android.content.SharedPreferences;

final class VoiceSettings {
    static final int MODE_AUTO = 0;
    static final int MODE_EMBEDDED = 1;
    static final int MODE_ANDROID = 2;
    private static final String PREFS = "epc_voice_settings_v151";
    private static final String KEY_MODE = "mode";
    private VoiceSettings() {}
    static int mode(Context c) {
        int m = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MODE, MODE_AUTO);
        return m < MODE_AUTO || m > MODE_ANDROID ? MODE_AUTO : m;
    }
    static void setMode(Context c, int m) {
        int safe = m < MODE_AUTO || m > MODE_ANDROID ? MODE_AUTO : m;
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_MODE, safe).apply();
    }
    static String label(Context c) {
        int m = mode(c);
        if (m == MODE_EMBEDDED) return "VOZ EMBARCADA";
        if (m == MODE_ANDROID) return "TTS DO ANDROID";
        return "AUTOMÁTICO";
    }
}

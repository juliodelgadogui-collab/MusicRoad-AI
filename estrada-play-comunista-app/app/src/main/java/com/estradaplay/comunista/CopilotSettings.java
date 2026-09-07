package com.estradaplay.comunista;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.speech.SpeechRecognizer;

/** Persistent, local-only settings for the always-available Copilot. */
final class CopilotSettings {
    private static final String PREFS = "epc_copilot_runtime_v1";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_WAKE_WORD = "wake_word";

    private CopilotSettings() {}

    static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    static String wakeWord(Context context) {
        String value = prefs(context).getString(KEY_WAKE_WORD, "Copiloto");
        value = value == null ? "" : value.trim();
        return value.isEmpty() ? "Copiloto" : value;
    }

    static void setWakeWord(Context context, String wakeWord) {
        String value = wakeWord == null ? "" : wakeWord.trim();
        if (value.isEmpty()) value = "Copiloto";
        prefs(context).edit().putString(KEY_WAKE_WORD, value).apply();
    }

    static boolean localWakeWordAvailable(Context context) {
        if (Build.VERSION.SDK_INT < 31) return false;
        try {
            return SpeechRecognizer.isOnDeviceRecognitionAvailable(context.getApplicationContext());
        } catch (Throwable ignored) {
            return false;
        }
    }

    static String localWakeWordStatus(Context context) {
        if (localWakeWordAvailable(context)) return "DETECÇÃO LOCAL DISPONÍVEL";
        if (Build.VERSION.SDK_INT < 31) return "WAKE WORD LOCAL REQUER ANDROID 12+";
        return "MODELO LOCAL DE VOZ NÃO DISPONÍVEL NESTE APARELHO";
    }
}

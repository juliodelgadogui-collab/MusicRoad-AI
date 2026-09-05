package com.estradaplay.comunista;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

/**
 * STABILITY_V232: records an uncaught process crash and, on the immediate next process start,
 * temporarily defers only optional online bridges. Core UI, local GPS protection and offline
 * functions remain available. The marker clears automatically after a stable minute.
 */
final class ProcessCrashGuard {
    private static final String PREFS = "epc_process_stability_v232";
    private static final String KEY_LAST_CRASH = "last_crash_ms";
    private static final long LOOP_WINDOW_MS = 120_000L;
    private static final long STABLE_CLEAR_MS = 60_000L;

    private ProcessCrashGuard() {}

    static boolean install(Application app) {
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long last = prefs.getLong(KEY_LAST_CRASH, 0L);
        boolean safeMode = last > 0L && now - last < LOOP_WINDOW_MS;

        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try { prefs.edit().putLong(KEY_LAST_CRASH, System.currentTimeMillis()).commit(); }
            catch (Throwable ignored) {}
            if (previous != null) previous.uncaughtException(thread, error);
            else {
                try { android.os.Process.killProcess(android.os.Process.myPid()); } catch (Throwable ignored) {}
                System.exit(10);
            }
        });

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try { prefs.edit().remove(KEY_LAST_CRASH).apply(); } catch (Throwable ignored) {}
        }, STABLE_CLEAR_MS);

        return safeMode;
    }

    // SYSTEM_DIAGNOSTICS_V270: read-only visibility for the local diagnostics screen.
    static boolean recentCrash(Context context) {
        if (context == null) return false;
        long last = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_CRASH, 0L);
        return last > 0L && System.currentTimeMillis() - last < LOOP_WINDOW_MS;
    }
}

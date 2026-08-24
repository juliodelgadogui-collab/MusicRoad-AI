package com.musicroad.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.webkit.CookieManager;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;

/**
 * Small runtime guard for the native shell.
 *
 * Two things are handled here without bringing WebView back:
 * 1) old full-screen content layers accidentally left behind by direct screen re-renders;
 * 2) mirror the native API session cookie to Android's CookieManager so MediaPlayer can
 *    authenticate protected HTTP audio endpoints such as Google Drive streaming.
 */
final class NativeAppGuard {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<MainActivity> current = new WeakReference<>(null);
    private static final long TICK_MS = 180L;

    private static final Runnable TICK = new Runnable() {
        @Override public void run() {
            MainActivity a = current.get();
            if (a == null || a.isFinishing() || a.isDestroyed()) return;
            repairContentLayers(a);
            syncNativeSessionCookie(a);
            MAIN.postDelayed(this, TICK_MS);
        }
    };

    private NativeAppGuard() {}

    static void start(MainActivity activity) {
        current = new WeakReference<>(activity);
        MAIN.removeCallbacks(TICK);
        MAIN.post(TICK);
    }

    static void stop(MainActivity activity) {
        if (current.get() == activity) current.clear();
        MAIN.removeCallbacks(TICK);
    }

    private static void repairContentLayers(MainActivity activity) {
        try {
            Field f = MainActivity.class.getDeclaredField("content");
            f.setAccessible(true);
            Object raw = f.get(activity);
            if (!(raw instanceof FrameLayout)) return;
            FrameLayout content = (FrameLayout) raw;
            int count = content.getChildCount();
            if (count <= 1) return;

            // MainActivity screens are full-screen single-root views. Direct calls such as
            // music() used to add a second transparent ScrollView. Keep only the newest root.
            View newest = content.getChildAt(count - 1);
            content.removeAllViews();
            content.addView(newest, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        } catch (Throwable ignored) {}
    }

    private static void syncNativeSessionCookie(Context context) {
        try {
            SharedPreferences p = context.getSharedPreferences("musicroad_native_api_v1", Context.MODE_PRIVATE);
            String jar = p.getString("cookie", "");
            if (jar == null || jar.trim().isEmpty()) return;
            String base = NativeApiClient.normalizeBase(BuildConfig.MUSICROAD_URL);
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            for (String part : jar.split(";\\s*")) {
                String pair = part == null ? "" : part.trim();
                if (pair.isEmpty() || !pair.contains("=")) continue;
                cm.setCookie(base, pair + "; Path=/; Secure; SameSite=Lax");
            }
            cm.flush();
        } catch (Throwable ignored) {}
    }
}

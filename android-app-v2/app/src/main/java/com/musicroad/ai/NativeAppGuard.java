package com.musicroad.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.webkit.CookieManager;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;

/**
 * Runtime guard for the native shell.
 *
 * Keeps native content single-layered, mirrors the API session for protected
 * media, and reconciles the legacy download button with the integrated offline
 * library. No WebView is created or rendered.
 */
final class NativeAppGuard {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<MainActivity> current = new WeakReference<>(null);
    private static final long TICK_MS = 180L;
    private static long lastMusicUiAt = 0L;

    private static final Runnable TICK = new Runnable() {
        @Override public void run() {
            MainActivity a = current.get();
            if (a == null || a.isFinishing() || a.isDestroyed()) return;
            repairContentLayers(a);
            syncNativeSessionCookie(a);
            MusicOfflineStore.reconcileDownloads();
            long now = System.currentTimeMillis();
            if (now - lastMusicUiAt >= 1200L) {
                lastMusicUiAt = now;
                normalizeMusicOfflineUi(a);
            }
            MAIN.postDelayed(this, TICK_MS);
        }
    };

    private NativeAppGuard() {}

    static void start(MainActivity activity) {
        MusicOfflineStore.init(activity);
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
            FrameLayout content = content(activity);
            if (content == null) return;
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

    private static void normalizeMusicOfflineUi(MainActivity activity) {
        try {
            FrameLayout content = content(activity);
            if (content == null || content.getChildCount() == 0) return;
            normalizeNode(content.getChildAt(content.getChildCount() - 1));
        } catch (Throwable ignored) {}
    }

    private static void normalizeNode(View view) {
        if (view == null) return;
        if (view instanceof TextView && !(view instanceof Button)) {
            TextView tv = (TextView) view;
            String value = String.valueOf(tv.getText()).trim();
            if ("Downloads offline".equals(value)) {
                tv.setText("Offline integrado");
                ViewParent parent = tv.getParent();
                if (parent instanceof ViewGroup) {
                    ViewGroup box = (ViewGroup) parent;
                    for (int i = 0; i < box.getChildCount(); i++) {
                        View child = box.getChildAt(i);
                        if (child instanceof TextView && child != tv) {
                            ((TextView) child).setText("Baixadas permanecem na pasta original e tocam sem internet.");
                            break;
                        }
                    }
                }
            }
        }
        if (view instanceof Button) {
            Button b = (Button) view;
            String label = String.valueOf(b.getText()).trim();
            if ("↓".equals(label) || "✓".equals(label)) {
                ViewParent p = b.getParent();
                if (p instanceof ViewGroup) {
                    String title = rowTitle((ViewGroup) p);
                    if (!title.isEmpty() && MusicOfflineStore.isDownloadedTitle(title)) {
                        b.setText("✓");
                        b.setEnabled(false);
                        b.setContentDescription("Disponível offline na pasta original");
                    }
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) normalizeNode(group.getChildAt(i));
        }
    }

    private static String rowTitle(ViewGroup row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (!(child instanceof ViewGroup)) continue;
            ViewGroup box = (ViewGroup) child;
            for (int j = 0; j < box.getChildCount(); j++) {
                View nested = box.getChildAt(j);
                if (nested instanceof TextView && !(nested instanceof Button)) {
                    String value = String.valueOf(((TextView) nested).getText()).trim();
                    if (!value.isEmpty()) return value;
                }
            }
        }
        return "";
    }

    private static FrameLayout content(MainActivity activity) {
        try {
            Field f = MainActivity.class.getDeclaredField("content");
            f.setAccessible(true);
            Object raw = f.get(activity);
            return raw instanceof FrameLayout ? (FrameLayout) raw : null;
        } catch (Throwable ignored) { return null; }
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

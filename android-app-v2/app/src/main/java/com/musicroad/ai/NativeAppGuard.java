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
import android.widget.Toast;
import android.webkit.CookieManager;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lightweight runtime guard for the native shell.
 *
 * Important: this class must never do filesystem scans or O(n²) music lookup on
 * the Android main thread. The 2.3.1 guard used to poll every 180 ms, flush the
 * CookieManager, reconcile offline files and recursively scan hundreds of music
 * rows. On a 500+ track library that could visibly freeze the UI.
 *
 * 2.3.2 keeps only the cheap layer repair in the fast tick. Offline migration is
 * moved to an IO thread and music controls are normalized only when the screen or
 * track set changes, plus a slow safety refresh.
 */
final class NativeAppGuard {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static WeakReference<MainActivity> current = new WeakReference<>(null);

    // Layer repair is intentionally cheap; it fixes the legacy music() screen
    // stacking bug without doing any recursive work.
    private static final long LAYER_TICK_MS = 280L;
    private static final long SAFETY_NORMALIZE_MS = 9000L;

    private static WeakReference<View> lastRoot = new WeakReference<>(null);
    private static String lastTrackSignature = "";
    private static long lastNormalizeAt = 0L;

    private static final Runnable TICK = new Runnable() {
        @Override public void run() {
            MainActivity a = current.get();
            if (a == null || a.isFinishing() || a.isDestroyed()) return;

            View root = repairContentLayers(a);
            String signature = trackSignature(a);
            long now = System.currentTimeMillis();
            boolean rootChanged = root != null && root != lastRoot.get();
            boolean tracksChanged = !signature.equals(lastTrackSignature);
            boolean safetyRefresh = now - lastNormalizeAt >= SAFETY_NORMALIZE_MS;

            if (rootChanged || tracksChanged || safetyRefresh) {
                lastRoot = new WeakReference<>(root);
                lastTrackSignature = signature;
                lastNormalizeAt = now;
                // Debounce a little so asynchronous library rendering can finish first.
                MAIN.removeCallbacks(NORMALIZE);
                MAIN.postDelayed(NORMALIZE, rootChanged ? 180L : 60L);
            }

            MAIN.postDelayed(this, LAYER_TICK_MS);
        }
    };

    private static final Runnable NORMALIZE = () -> {
        MainActivity a = current.get();
        if (a == null || a.isFinishing() || a.isDestroyed()) return;
        normalizeMusicOfflineUi(a);
    };

    private NativeAppGuard() {}

    static void start(MainActivity activity) {
        MusicOfflineStore.init(activity);
        current = new WeakReference<>(activity);
        MAIN.removeCallbacks(TICK);
        MAIN.removeCallbacks(NORMALIZE);
        lastRoot.clear();
        lastTrackSignature = "";
        lastNormalizeAt = 0L;

        // Cookie sync is needed only once on resume, not five times per second.
        syncNativeSessionCookie(activity);

        // Legacy offline migration touches the filesystem, so keep it off UI.
        Context app = activity.getApplicationContext();
        IO.execute(() -> {
            try { MusicOfflineStore.init(app); MusicOfflineStore.reconcileDownloads(); }
            catch (Throwable ignored) {}
            MAIN.post(NORMALIZE);
        });

        MAIN.post(TICK);
    }

    static void stop(MainActivity activity) {
        if (current.get() == activity) current.clear();
        MAIN.removeCallbacks(TICK);
        MAIN.removeCallbacks(NORMALIZE);
    }

    /** Returns the currently visible root after repairing legacy stacked screens. */
    private static View repairContentLayers(MainActivity activity) {
        try {
            FrameLayout content = content(activity);
            if (content == null || content.getChildCount() == 0) return null;
            int count = content.getChildCount();
            View newest = content.getChildAt(count - 1);
            if (count > 1) {
                content.removeAllViews();
                content.addView(newest, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
            }
            return newest;
        } catch (Throwable ignored) { return null; }
    }

    private static void normalizeMusicOfflineUi(MainActivity activity) {
        try {
            FrameLayout content = content(activity);
            if (content == null || content.getChildCount() == 0) return;
            Map<String, MusicTrack> tracks = trackLookup(activity);
            normalizeNode(activity, content.getChildAt(content.getChildCount() - 1), tracks);
        } catch (Throwable ignored) {}
    }

    private static void normalizeNode(MainActivity activity, View view, Map<String, MusicTrack> tracks) {
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
                            ((TextView) child).setText("Baixadas continuam na pasta original. Play usa offline primeiro e Drive depois.");
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
                    ViewGroup row = (ViewGroup) p;
                    String title = rowTitle(row);
                    String subtitle = rowSubtitle(row);
                    MusicTrack track = lookupTrack(tracks, title, subtitle);
                    if (track != null) {
                        boolean downloaded = MusicOfflineStore.isDownloaded(track);
                        if (downloaded) {
                            b.setText("✓");
                            b.setEnabled(true);
                            b.setContentDescription("Disponível offline na pasta original. Segure para remover.");
                            b.setOnClickListener(v -> Toast.makeText(activity, "Esta música já está disponível offline.", Toast.LENGTH_SHORT).show());
                            b.setOnLongClickListener(v -> MusicDirectDownload.remove(activity, track, b));
                        } else {
                            b.setText("↓");
                            b.setEnabled(true);
                            b.setContentDescription("Baixar direto do Google Drive para esta pasta");
                            b.setOnLongClickListener(null);
                            b.setOnClickListener(v -> MusicDirectDownload.start(activity, track, b));
                        }
                    }
                }
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) normalizeNode(activity, group.getChildAt(i), tracks);
        }
    }

    /** Build lookup once: previous code scanned the whole track list for every row. */
    private static Map<String, MusicTrack> trackLookup(MainActivity activity) {
        Map<String, MusicTrack> out = new HashMap<>();
        try {
            Field f = MainActivity.class.getDeclaredField("shownTracks");
            f.setAccessible(true);
            Object raw = f.get(activity);
            if (!(raw instanceof List)) return out;
            for (Object o : (List<?>) raw) {
                if (!(o instanceof MusicTrack)) continue;
                MusicTrack t = (MusicTrack) o;
                String title = clean(t.title);
                if (title.isEmpty()) continue;
                out.putIfAbsent("t:" + title, t);
                String artist = clean(t.artist);
                String folder = clean(t.folder);
                if (!artist.isEmpty()) out.put("a:" + title + "\n" + artist, t);
                if (!folder.isEmpty()) out.put("f:" + title + "\n" + folder, t);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static MusicTrack lookupTrack(Map<String, MusicTrack> tracks, String title, String subtitle) {
        String t = clean(title);
        String s = clean(subtitle);
        if (t.isEmpty()) return null;
        if (!s.isEmpty()) {
            for (Map.Entry<String, MusicTrack> e : tracks.entrySet()) {
                String key = e.getKey();
                if ((key.startsWith("a:" + t + "\n") || key.startsWith("f:" + t + "\n"))) {
                    String value = key.substring(key.indexOf('\n') + 1);
                    if (!value.isEmpty() && s.contains(value)) return e.getValue();
                }
            }
        }
        return tracks.get("t:" + t);
    }

    private static String trackSignature(MainActivity activity) {
        try {
            Field f = MainActivity.class.getDeclaredField("shownTracks");
            f.setAccessible(true);
            Object raw = f.get(activity);
            if (!(raw instanceof List)) return "";
            List<?> list = (List<?>) raw;
            int n = list.size();
            String first = "", last = "";
            if (n > 0 && list.get(0) instanceof MusicTrack) {
                MusicTrack t = (MusicTrack) list.get(0);
                first = clean(t.id) + ":" + clean(t.title);
            }
            if (n > 1 && list.get(n - 1) instanceof MusicTrack) {
                MusicTrack t = (MusicTrack) list.get(n - 1);
                last = clean(t.id) + ":" + clean(t.title);
            }
            return n + "|" + first + "|" + last + "|" + fieldString(activity, "musicSource")
                    + "|" + fieldString(activity, "musicFolder") + "|" + fieldString(activity, "musicGenre");
        } catch (Throwable ignored) { return ""; }
    }

    private static String fieldString(MainActivity activity, String name) {
        try {
            Field f = MainActivity.class.getDeclaredField(name);
            f.setAccessible(true);
            Object v = f.get(activity);
            return v == null ? "" : String.valueOf(v);
        } catch (Throwable ignored) { return ""; }
    }

    private static String rowTitle(ViewGroup row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (!(child instanceof ViewGroup)) continue;
            ViewGroup box = (ViewGroup) child;
            for (int j = 0; j < box.getChildCount(); j++) {
                View nested = box.getChildAt(j);
                if (nested instanceof TextView && !(nested instanceof Button)) {
                    String value = clean(((TextView) nested).getText());
                    if (!value.isEmpty()) return value;
                }
            }
        }
        return "";
    }

    private static String rowSubtitle(ViewGroup row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (!(child instanceof ViewGroup)) continue;
            ViewGroup box = (ViewGroup) child;
            int found = 0;
            for (int j = 0; j < box.getChildCount(); j++) {
                View nested = box.getChildAt(j);
                if (nested instanceof TextView && !(nested instanceof Button)) {
                    String value = clean(((TextView) nested).getText());
                    if (value.isEmpty()) continue;
                    found++;
                    if (found == 2) return value;
                }
            }
        }
        return "";
    }

    private static String clean(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
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

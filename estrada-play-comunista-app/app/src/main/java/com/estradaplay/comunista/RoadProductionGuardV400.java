package com.estradaplay.comunista;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Production runtime guard for the Estrada cockpit.
 *
 * It deliberately does not own navigation, GPS or hazard selection. It only guarantees that the
 * visible road screen remains structurally healthy after resize/rotation, gives critical hazards a
 * single centered visual surface, and distinguishes GPS health from local safety-data coverage.
 */
final class RoadProductionGuardV400 implements Application.ActivityLifecycleCallbacks {
    private static final String HAZARD_TAG = "epc-production-hazard-v400";
    private static final String COVERAGE_TAG = "epc-production-coverage-v400";
    private static final String LEGACY_REFERENCE_TAG = "epc-road-reference-v350";
    private static final String DIAG_PREFS = "epc_production_road_diag_v400";
    private static final long HAZARD_VISIBLE_MS = 6500L;
    private static final long COVERAGE_RELOAD_MS = 12_000L;

    private final Application app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean coverageReloading = new AtomicBoolean(false);
    private WeakReference<RoadMapActivity> resumed = new WeakReference<>(null);
    private WeakReference<RoadMapView> lifecycleMap = new WeakReference<>(null);
    private volatile RoadPackStore coverageStore;
    private volatile long lastCoverageReloadAt;
    private long hazardGeneration;

    static RoadProductionGuardV400 install(Application app) {
        RoadProductionGuardV400 guard = new RoadProductionGuardV400(app);
        app.registerActivityLifecycleCallbacks(guard);
        guard.register();
        guard.reloadCoverageStore(true);
        return guard;
    }

    private RoadProductionGuardV400(Application app) { this.app = app; }

    private void register() {
        IntentFilter filter = new IntentFilter(RoadSafetyService.ACTION_STATE);
        try {
            if (Build.VERSION.SDK_INT >= 33) InternalBroadcasts.register(app, receiver, filter);
            else InternalBroadcasts.register(app, receiver, filter);
        } catch (Throwable ignored) {}
    }

    private void reloadCoverageStore(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastCoverageReloadAt < COVERAGE_RELOAD_MS) return;
        if (!coverageReloading.compareAndSet(false, true)) return;
        lastCoverageReloadAt = now;
        io.execute(() -> {
            try { coverageStore = new RoadPackStore(app); }
            catch (Throwable ignored) { if (coverageStore == null) coverageStore = null; }
            finally { coverageReloading.set(false); }
        });
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            RoadMapActivity activity = resumed.get();
            if (activity == null || activity.isFinishing()) return;
            main.post(() -> {
                ensureMapRuntime(activity);
                updateHazard(activity, intent);
                updateCoverage(activity, intent);
            });
        }
    };

    private void ensureMapRuntime(RoadMapActivity activity) {
        FrameLayout root = field(activity, "root", FrameLayout.class);
        RoadMapView map = field(activity, "roadMap", RoadMapView.class);
        if (root == null || map == null) return;

        View parent = map.getParent() instanceof View ? (View) map.getParent() : null;
        if (parent != null) {
            parent.setVisibility(View.VISIBLE);
            parent.setAlpha(1f);
        }
        map.setVisibility(View.VISIBLE);
        map.setAlpha(1f);

        // Remove an accidentally retained pre-production overlay if an Activity was restored from
        // process state while the APK was updated in place.
        View legacy = root.findViewWithTag(LEGACY_REFERENCE_TAG);
        if (legacy != null && legacy.getParent() instanceof ViewGroup) {
            try { ((ViewGroup) legacy.getParent()).removeView(legacy); } catch (Throwable ignored) {}
        }

        RoadMapView previous = lifecycleMap.get();
        if (previous == null) {
            // The first map instance reached here through the normal Activity onStart/onResume path.
            lifecycleMap = new WeakReference<>(map);
            return;
        }
        if (previous != map) {
            lifecycleMap = new WeakReference<>(map);
            // RoadMapActivity rebuilds the view inside onConfigurationChanged. Because the Activity
            // itself does not re-enter onStart/onResume, explicitly bring only that new map instance
            // to the current resumed lifecycle once.
            try { map.onStartMap(); } catch (Throwable ignored) {}
            try { map.onResumeMap(); } catch (Throwable ignored) {}
        }
    }

    private void updateHazard(RoadMapActivity activity, Intent intent) {
        String type = safe(intent.getStringExtra("hazard_type"));
        String label = safe(intent.getStringExtra("hazard_label"));
        if (type.isEmpty() && label.isEmpty()) return;

        double distance = intent.getDoubleExtra("distance_m", Double.NaN);
        int limit = intent.getIntExtra("limit_kmh", 0);
        String road = safe(intent.getStringExtra("road"));
        int alertLevel = intent.getIntExtra("alert_level", 1);

        FrameLayout host = contentHost(activity);
        if (host == null) return;
        View old = host.findViewWithTag(HAZARD_TAG);
        if (old != null) host.removeView(old);

        LinearLayout card = new LinearLayout(activity);
        card.setTag(HAZARD_TAG);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        int horizontal = dp(activity, 20), vertical = dp(activity, 12);
        card.setPadding(horizontal, vertical, horizontal, vertical);
        int stroke = alertLevel >= 2 ? Color.rgb(244, 34, 55) : Color.rgb(190, 22, 43);
        card.setBackground(panel(activity, Color.argb(246, 14, 6, 9), 18, stroke, alertLevel >= 2 ? 2 : 1));
        if (Build.VERSION.SDK_INT >= 21) card.setElevation(dp(activity, 125));

        TextView title = text(activity, titleFor(type, label), 16, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        card.addView(title);

        StringBuilder detail = new StringBuilder();
        if (Double.isFinite(distance) && distance > 0) {
            if (distance >= 1000) detail.append(String.format(Locale.getDefault(), "%.1f km", distance / 1000.0));
            else detail.append(Math.max(1, Math.round(distance))).append(" m");
        }
        if (limit > 0 && ("RADAR".equals(type) || "SEMAFORO_RADAR".equals(type))) {
            if (detail.length() > 0) detail.append("  ·  ");
            detail.append("limite ").append(limit).append(" km/h");
        }
        if (!road.isEmpty()) {
            if (detail.length() > 0) detail.append("  ·  ");
            detail.append(road);
        }
        if (detail.length() == 0) detail.append("Atenção à frente");
        TextView body = text(activity, detail.toString(), 11, Color.rgb(222, 207, 202), false);
        body.setGravity(Gravity.CENTER);
        body.setSingleLine(true);
        card.addView(body);

        int sw = activity.getResources().getDisplayMetrics().widthPixels;
        int sh = activity.getResources().getDisplayMetrics().heightPixels;
        boolean landscape = sw > sh;
        int width = landscape
                ? Math.min(dp(activity, 520), Math.max(dp(activity, 300), Math.round(sw * .36f)))
                : Math.min(dp(activity, 430), Math.max(dp(activity, 280), sw - dp(activity, 34)));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, -2, Gravity.CENTER);
        if (landscape) lp.leftMargin = -Math.round(sw * .12f);
        host.addView(card, lp);
        card.setAlpha(0f);
        card.setScaleX(.97f);
        card.setScaleY(.97f);
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(140L).start();

        final long generation = ++hazardGeneration;
        main.postDelayed(() -> {
            if (generation != hazardGeneration) return;
            RoadMapActivity current = resumed.get();
            if (current != activity || activity.isFinishing()) return;
            hideTagged(contentHost(activity), HAZARD_TAG);
        }, HAZARD_VISIBLE_MS);
    }

    private void updateCoverage(RoadMapActivity activity, Intent intent) {
        RoadPackStore store = coverageStore;
        double lat = intent.getDoubleExtra("lat", Double.NaN);
        double lon = intent.getDoubleExtra("lon", Double.NaN);
        boolean gpsAvailable = intent.getBooleanExtra("protection_available", true);
        if (!gpsAvailable || !Double.isFinite(lat) || !Double.isFinite(lon)) {
            hideTagged(contentHost(activity), COVERAGE_TAG);
            return;
        }
        if (store == null) {
            reloadCoverageStore(false);
            return;
        }

        boolean any;
        boolean fresh;
        int nearby = 0;
        try {
            any = store.hasAnyCoverage(lat, lon);
            fresh = store.hasFreshCoreCoverage(lat, lon);
            nearby = store.nearby(lat, lon, 5000).size();
        } catch (Throwable ignored) {
            reloadCoverageStore(false);
            return;
        }

        try {
            app.getSharedPreferences(DIAG_PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean("coverage_any", any)
                    .putBoolean("coverage_fresh", fresh)
                    .putInt("nearby_hazards_5km", nearby)
                    .putLong("checked_at", System.currentTimeMillis())
                    .putLong("lat_e5", Math.round(lat * 100000.0))
                    .putLong("lon_e5", Math.round(lon * 100000.0))
                    .apply();
        } catch (Throwable ignored) {}

        FrameLayout host = contentHost(activity);
        if (host == null) return;
        if (any) {
            hideTagged(host, COVERAGE_TAG);
            return;
        }

        // RoadSafetyService may have written a new tile/corridor through another in-memory store.
        // Refresh this read-only diagnostic snapshot periodically until coverage becomes visible.
        reloadCoverageStore(false);

        if (host.findViewWithTag(COVERAGE_TAG) != null) return;
        TextView chip = text(activity, "PROTEÇÃO DA REGIÃO PREPARANDO  ·  GPS ATIVO", 9,
                Color.rgb(255, 221, 147), true);
        chip.setTag(COVERAGE_TAG);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(activity, 14), dp(activity, 8), dp(activity, 14), dp(activity, 8));
        chip.setBackground(panel(activity, Color.argb(242, 43, 28, 7), 100, Color.rgb(196, 151, 49), 1));
        if (Build.VERSION.SDK_INT >= 21) chip.setElevation(dp(activity, 105));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        int sh = activity.getResources().getDisplayMetrics().heightPixels;
        lp.topMargin = Math.max(dp(activity, 86), Math.round(sh * .105f));
        host.addView(chip, lp);
    }

    private static String titleFor(String type, String label) {
        switch (type) {
            case "QUEBRA_MOLAS": return "QUEBRA-MOLAS À FRENTE";
            case "SEMAFORO_RADAR": return "SEMÁFORO FISCALIZADO À FRENTE";
            case "SEMAFORO": return "SEMÁFORO À FRENTE";
            case "CAMERA_MONITORAMENTO": return "CÂMERA DE TRÁFEGO À FRENTE";
            case "PEDAGIO": return "PEDÁGIO À FRENTE";
            case "PASSAGEM_NIVEL": return "PASSAGEM DE NÍVEL À FRENTE";
            case "RADAR": return "RADAR À FRENTE";
            default:
                if (!label.isEmpty()) return label.toUpperCase(Locale.ROOT) + " À FRENTE";
                return "ATENÇÃO À FRENTE";
        }
    }

    private static void hideTagged(FrameLayout host, String tag) {
        if (host == null) return;
        View view = host.findViewWithTag(tag);
        if (view == null) return;
        try { host.removeView(view); } catch (Throwable ignored) {}
    }

    private static FrameLayout contentHost(Activity activity) {
        try {
            View view = activity.findViewById(android.R.id.content);
            return view instanceof FrameLayout ? (FrameLayout) view : null;
        } catch (Throwable ignored) { return null; }
    }

    private static TextView text(Context c, String value, float size, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private static GradientDrawable panel(Context c, int color, int radius, int stroke, int strokeWidth) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radius));
        if (stroke != 0 && strokeWidth > 0) g.setStroke(dp(c, strokeWidth), stroke);
        return g;
    }

    private static int dp(Context c, float value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name, Class<T> type) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            Object value = f.get(target);
            return type.isInstance(value) ? (T) value : null;
        } catch (Throwable ignored) { return null; }
    }

    @Override public void onActivityResumed(Activity activity) {
        if (!(activity instanceof RoadMapActivity)) return;
        RoadMapActivity road = (RoadMapActivity) activity;
        resumed = new WeakReference<>(road);
        RoadMapView current = field(road, "roadMap", RoadMapView.class);
        lifecycleMap = new WeakReference<>(current);
        reloadCoverageStore(false);
        main.postDelayed(() -> {
            if (resumed.get() == road && !road.isFinishing()) ensureMapRuntime(road);
        }, 120L);
    }

    @Override public void onActivityPaused(Activity activity) {
        if (resumed.get() != activity) return;
        FrameLayout host = contentHost(activity);
        hideTagged(host, HAZARD_TAG);
        hideTagged(host, COVERAGE_TAG);
        resumed = new WeakReference<>(null);
        lifecycleMap = new WeakReference<>(null);
    }

    @Override public void onActivityDestroyed(Activity activity) {
        if (resumed.get() == activity) resumed = new WeakReference<>(null);
    }
    @Override public void onActivityCreated(Activity activity, Bundle state) {}
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
}

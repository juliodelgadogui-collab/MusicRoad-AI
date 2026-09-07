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
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Locale;

/**
 * ROAD_CLEAN_UI_V340
 * Keeps the Estrada screen map-first. Existing navigation/safety functions remain alive,
 * while permanent duplicate panels are collapsed or hidden. Landscape intentionally exposes
 * only CENTRAL as a navigation button; music transport remains available as requested.
 */
final class RoadMapMinimalUiV340 implements Application.ActivityLifecycleCallbacks {
    private static final String TAG_CENTRAL = "epc-clean-central-v340";
    private static final String TAG_PLAYER = "epc-clean-player-v340";
    private static final long REAPPLY_MS = 850L;

    private final Application app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WeakReference<RoadMapActivity> resumed = new WeakReference<>(null);
    private long lastApplyAt;
    private double lastLat = Double.NaN, lastLon = Double.NaN;
    private boolean roadHazardActive;
    private String playerTitle = "Biblioteca offline";
    private String playerArtist = "Música local";
    private boolean playerPlaying;

    static void install(Application app) {
        if (app == null) return;
        RoadMapMinimalUiV340 bridge = new RoadMapMinimalUiV340(app);
        app.registerActivityLifecycleCallbacks(bridge);
        bridge.registerReceivers();
    }

    private RoadMapMinimalUiV340(Application app) { this.app = app; }

    private void registerReceivers() {
        IntentFilter f = new IntentFilter();
        f.addAction(RoadSafetyService.ACTION_STATE);
        f.addAction(PlayerService.ACTION_STATE);
        try {
            if (Build.VERSION.SDK_INT >= 33) InternalBroadcasts.register(app, receiver, f);
            else InternalBroadcasts.register(app, receiver, f);
        } catch (Throwable ignored) {}
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (RoadSafetyService.ACTION_STATE.equals(action)) {
                lastLat = intent.getDoubleExtra("lat", Double.NaN);
                lastLon = intent.getDoubleExtra("lon", Double.NaN);
                String hazard = safe(intent.getStringExtra("hazard_label"));
                String type = safe(intent.getStringExtra("hazard_type"));
                roadHazardActive = !hazard.isEmpty() && !type.isEmpty();
            } else if (PlayerService.ACTION_STATE.equals(action)) {
                String title = safe(intent.getStringExtra("title"));
                String artist = safe(intent.getStringExtra("artist"));
                if (!title.isEmpty()) playerTitle = title;
                if (!artist.isEmpty()) playerArtist = artist;
                playerPlaying = intent.getBooleanExtra("playing", false);
            }
            RoadMapActivity a = resumed.get();
            if (a != null && !a.isFinishing()) scheduleApply(a, 40L);
        }
    };

    private void scheduleApply(RoadMapActivity a, long delay) {
        if (a == null) return;
        main.postDelayed(() -> {
            if (!a.isFinishing() && resumed.get() == a) apply(a);
        }, delay);
    }

    private void apply(RoadMapActivity a) {
        FrameLayout root = field(a, "root", FrameLayout.class);
        RoadMapView map = field(a, "roadMap", RoadMapView.class);
        if (root == null || map == null || !(map.getParent() instanceof FrameLayout)) return;
        FrameLayout mapPane = (FrameLayout) map.getParent();
        int w = root.getWidth(), h = root.getHeight();
        if (w <= 0 || h <= 0) return;
        boolean landscape = w > h || "horizontal".equals(BuildConfig.FIXED_LAYOUT);

        expandMap(root, mapPane, landscape);
        if (landscape) cleanLandscape(a, root, mapPane, w, h);
        else cleanPortrait(a, root, mapPane, w, h);
        tunePersistentViews(a, root, mapPane, landscape, w, h);
        syncMiniPlayer(root);
        lastApplyAt = System.currentTimeMillis();
    }

    private void expandMap(FrameLayout root, FrameLayout mapPane, boolean landscape) {
        int edge = landscape ? dp(6) : dp(8);
        FrameLayout.LayoutParams p = mapPane.getLayoutParams() instanceof FrameLayout.LayoutParams
                ? (FrameLayout.LayoutParams) mapPane.getLayoutParams()
                : new FrameLayout.LayoutParams(-1, -1);
        p.width = -1; p.height = -1; p.gravity = Gravity.FILL;
        p.setMargins(edge, edge, edge, edge);
        mapPane.setLayoutParams(p);
        mapPane.setVisibility(View.VISIBLE);
        mapPane.setClipToPadding(true);
    }

    private void cleanLandscape(RoadMapActivity a, FrameLayout root, FrameLayout mapPane, int w, int h) {
        // Remove the old navigation rail and large right dock. Their functions remain in CENTRAL.
        for (int n = 0; n < root.getChildCount(); n++) {
            View child = root.getChildAt(n);
            if (child == mapPane || hasOurTag(child)) continue;
            boolean oldRail = contains(child, "ESTRADA") && contains(child, "RÁDIO") && contains(child, "VIAGEM") && contains(child, "CENTRAL");
            boolean oldDock = contains(child, "CENTRAL AUTOMOTIVA") || contains(child, "ATALHOS") || contains(child, "ABRIR MÚSICA");
            if (oldRail || oldDock) child.setVisibility(View.GONE);
        }
        ensureCentralButton(a, root, true);
        ensureMiniPlayer(a, root, true, w);
    }

    private void cleanPortrait(RoadMapActivity a, FrameLayout root, FrameLayout mapPane, int w, int h) {
        // Keep PTT / DASH / CENTRAL in portrait, but turn the old bulky rail into a compact tool strip.
        for (int n = 0; n < root.getChildCount(); n++) {
            View child = root.getChildAt(n);
            if (child == mapPane || hasOurTag(child)) continue;
            if (contains(child, "PTT") && contains(child, "DASH") && contains(child, "CENTRAL") && child instanceof LinearLayout) {
                LinearLayout rail = (LinearLayout) child;
                rail.setPadding(dp(4), dp(4), dp(4), dp(4));
                for (int c = 0; c < rail.getChildCount(); c++) {
                    View v = rail.getChildAt(c);
                    if (v instanceof TextView && !(v instanceof Button) && String.valueOf(((TextView) v).getText()).contains(")")) v.setVisibility(View.GONE);
                    if (v instanceof Button) {
                        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(66), dp(42));
                        bp.setMargins(0, dp(3), 0, dp(3));
                        v.setLayoutParams(bp);
                        ((Button) v).setTextSize(8.5f);
                    }
                }
                FrameLayout.LayoutParams rp = rail.getLayoutParams() instanceof FrameLayout.LayoutParams
                        ? (FrameLayout.LayoutParams) rail.getLayoutParams() : new FrameLayout.LayoutParams(dp(74), -2);
                rp.width = dp(74); rp.height = -2; rp.gravity = Gravity.LEFT | Gravity.CENTER_VERTICAL;
                rp.setMargins(dp(14), dp(54), 0, 0); rail.setLayoutParams(rp);
            }
            // The old full-width player is replaced by a thinner strip below.
            if (contains(child, "BIBLIOTECA OFFLINE") && !contains(child, "CENTRAL AUTOMOTIVA")) child.setVisibility(View.GONE);
        }
        ensureMiniPlayer(a, root, false, w);
    }

    private void tunePersistentViews(RoadMapActivity a, FrameLayout root, FrameLayout mapPane,
                                     boolean landscape, int w, int h) {
        TextView instruction = field(a, "navInstructionText", TextView.class);
        TextView road = field(a, "navRoadText", TextView.class);
        TextView weather = field(a, "navWeatherText", TextView.class);
        TextView distance = field(a, "navDistanceText", TextView.class);
        TextView turn = field(a, "navTurnText", TextView.class);
        TextView gps = field(a, "gpsText", TextView.class);
        TextView speedText = field(a, "speedText", TextView.class);
        TextView limit = field(a, "navLimitText", TextView.class);
        LinearLayout hazard = field(a, "hazardCard", LinearLayout.class);
        TextView eta = field(a, "navEtaText", TextView.class);
        TextView remaining = field(a, "navRemainingText", TextView.class);
        TextView duration = field(a, "navDurationText", TextView.class);
        DestinationStore.Destination dest = DestinationStore.read(a);
        boolean routeActive = dest != null;

        // Context/weather no longer gets a second permanent card. Real road hazards use the
        // centered SafetyAlertOverlay, which is more visible and avoids duplicate information.
        if (hazard != null) hazard.setVisibility(View.GONE);

        if (instruction != null && instruction.getParent() instanceof View) {
            View textBox = (View) instruction.getParent();
            if (textBox.getParent() instanceof LinearLayout) {
                LinearLayout guide = (LinearLayout) textBox.getParent();
                guide.setPadding(dp(9), dp(5), dp(9), dp(5));
                guide.setBackground(round(Color.argb(238, 10, 7, 9), 18, Color.rgb(73, 35, 40)));
                FrameLayout.LayoutParams gp = guide.getLayoutParams() instanceof FrameLayout.LayoutParams
                        ? (FrameLayout.LayoutParams) guide.getLayoutParams() : new FrameLayout.LayoutParams(-1, dp(68));
                if (landscape) {
                    gp.width = Math.min(dp(720), Math.max(dp(390), w - dp(270)));
                    gp.height = dp(68); gp.gravity = Gravity.TOP | Gravity.LEFT;
                    gp.setMargins(dp(92), dp(10), 0, 0);
                } else {
                    gp.width = -1; gp.height = dp(74); gp.gravity = Gravity.TOP;
                    gp.setMargins(dp(14), dp(12), dp(14), 0);
                }
                guide.setLayoutParams(gp);
                if (Build.VERSION.SDK_INT >= 21) guide.setElevation(dp(42));
            }
        }
        if (turn != null) turn.setTextSize(landscape ? 25f : 27f);
        if (distance != null) distance.setTextSize(landscape ? 17f : 18f);
        if (instruction != null) instruction.setTextSize(landscape ? 14f : 14f);
        if (road != null) {
            road.setTextSize(landscape ? 8.5f : 8.5f);
            road.setVisibility(routeActive ? View.VISIBLE : View.GONE);
        }
        if (weather != null) { weather.setTextSize(8.2f); weather.setVisibility(View.VISIBLE); }
        if (gps != null) {
            gps.setText("GPS ATIVO".equals(String.valueOf(gps.getText())) ? "GPS ATIVO" : "GPS");
            gps.setTextSize(7.8f);
            gps.setLongClickable(true);
            gps.setOnLongClickListener(v -> { ProtectionDiagnostics.show(a, lastLat, lastLon); return true; });
        }

        if (speedText != null && speedText.getParent() instanceof LinearLayout) {
            LinearLayout speed = (LinearLayout) speedText.getParent();
            speed.setBackground(round(Color.argb(235, 10, 7, 9), 24, Color.rgb(78, 38, 42)));
            speedText.setTextSize(landscape ? 27f : 28f);
            if (limit != null) limit.setTextSize(12f);
            FrameLayout.LayoutParams sp = speed.getLayoutParams() instanceof FrameLayout.LayoutParams
                    ? (FrameLayout.LayoutParams) speed.getLayoutParams() : new FrameLayout.LayoutParams(dp(82), dp(92));
            sp.width = dp(82); sp.height = dp(92); sp.gravity = Gravity.TOP | Gravity.RIGHT;
            sp.setMargins(0, landscape ? dp(10) : dp(98), dp(12), 0); speed.setLayoutParams(sp);
            if (Build.VERSION.SDK_INT >= 21) speed.setElevation(dp(40));
        }

        LinearLayout metrics = metricParent(eta, remaining, duration);
        if (metrics != null) {
            metrics.setVisibility(routeActive ? View.VISIBLE : View.GONE);
            if (routeActive) {
                metrics.setPadding(dp(5), dp(3), dp(5), dp(3));
                metrics.setBackground(round(Color.argb(224, 10, 7, 9), 15, Color.rgb(67, 34, 37)));
                FrameLayout.LayoutParams mp = metrics.getLayoutParams() instanceof FrameLayout.LayoutParams
                        ? (FrameLayout.LayoutParams) metrics.getLayoutParams() : new FrameLayout.LayoutParams(dp(330), dp(50));
                if (landscape) {
                    mp.width = dp(330); mp.height = dp(50); mp.gravity = Gravity.BOTTOM | Gravity.LEFT;
                    mp.setMargins(dp(14), 0, 0, dp(12));
                } else {
                    mp.width = -1; mp.height = dp(52); mp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                    mp.setMargins(dp(14), 0, dp(14), dp(78));
                }
                metrics.setLayoutParams(mp);
                if (Build.VERSION.SDK_INT >= 21) metrics.setElevation(dp(36));
            }
        }

        Button recenter = findButton(root, "◎");
        if (recenter != null) {
            FrameLayout.LayoutParams rp = recenter.getLayoutParams() instanceof FrameLayout.LayoutParams
                    ? (FrameLayout.LayoutParams) recenter.getLayoutParams() : new FrameLayout.LayoutParams(dp(50), dp(50));
            rp.width = dp(50); rp.height = dp(50); rp.gravity = Gravity.RIGHT | Gravity.BOTTOM;
            rp.setMargins(0, 0, dp(16), landscape ? dp(76) : dp(148)); recenter.setLayoutParams(rp);
            recenter.setVisibility(View.VISIBLE);
        }

        Button cancel = findButton(root, "CANCELAR ROTA");
        if (cancel != null && routeActive) {
            FrameLayout.LayoutParams cp = cancel.getLayoutParams() instanceof FrameLayout.LayoutParams
                    ? (FrameLayout.LayoutParams) cancel.getLayoutParams() : new FrameLayout.LayoutParams(dp(116), dp(38));
            cp.width = dp(116); cp.height = dp(38);
            cp.gravity = landscape ? (Gravity.BOTTOM | Gravity.LEFT) : (Gravity.BOTTOM | Gravity.RIGHT);
            if (landscape) cp.setMargins(dp(14), 0, 0, dp(70));
            else cp.setMargins(0, 0, dp(14), dp(140));
            cancel.setLayoutParams(cp); cancel.setTextSize(8f);
        }
    }

    private void ensureCentralButton(RoadMapActivity a, FrameLayout root, boolean landscape) {
        if (!landscape) return;
        View existing = root.findViewWithTag(TAG_CENTRAL);
        if (existing instanceof Button) return;
        Button b = new Button(a);
        b.setTag(TAG_CENTRAL); b.setText("CENTRAL"); b.setAllCaps(false);
        b.setTextColor(Color.WHITE); b.setTextSize(8.5f); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setPadding(dp(8), 0, dp(8), 0); b.setStateListAnimator(null);
        b.setBackground(round(Color.argb(242, 18, 10, 13), 16, Color.rgb(143, 25, 40)));
        b.setOnClickListener(v -> a.startActivity(new Intent(a, DriveToolsActivity.class)));
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(72), dp(48), Gravity.TOP | Gravity.LEFT);
        p.setMargins(dp(12), dp(10), 0, 0); root.addView(b, p);
        if (Build.VERSION.SDK_INT >= 21) b.setElevation(dp(70));
    }

    private void ensureMiniPlayer(RoadMapActivity a, FrameLayout root, boolean landscape, int width) {
        View old = root.findViewWithTag(TAG_PLAYER);
        if (old instanceof LinearLayout) {
            positionMiniPlayer((LinearLayout) old, landscape, width);
            return;
        }
        LinearLayout strip = new LinearLayout(a);
        strip.setTag(TAG_PLAYER); strip.setOrientation(LinearLayout.HORIZONTAL); strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(dp(10), dp(5), dp(7), dp(5));
        strip.setBackground(round(Color.argb(238, 12, 8, 10), 17, Color.rgb(72, 35, 39)));

        LinearLayout meta = new LinearLayout(a); meta.setOrientation(LinearLayout.VERTICAL); meta.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(a, playerTitle, 11.5f, Color.rgb(246,238,224), true); title.setTag("player-title"); title.setSingleLine(true);
        TextView artist = text(a, playerArtist, 7.5f, Color.rgb(174,151,146), false); artist.setTag("player-artist"); artist.setSingleLine(true);
        meta.addView(title); meta.addView(artist); strip.addView(meta, new LinearLayout.LayoutParams(0, -1, 1));
        Button prev = mediaButton(a, "◀", false), play = mediaButton(a, playerPlaying ? "Ⅱ" : "▶", true), next = mediaButton(a, "▶|", false);
        play.setTag("player-toggle");
        strip.addView(prev, new LinearLayout.LayoutParams(dp(42), dp(42)));
        strip.addView(play, new LinearLayout.LayoutParams(dp(46), dp(44)));
        strip.addView(next, new LinearLayout.LayoutParams(dp(42), dp(42)));
        prev.setOnClickListener(v -> player(a, PlayerService.ACTION_PREVIOUS));
        play.setOnClickListener(v -> player(a, PlayerService.ACTION_TOGGLE));
        next.setOnClickListener(v -> player(a, PlayerService.ACTION_NEXT));
        meta.setOnClickListener(v -> a.startActivity(new Intent(a, MusicPlayerActivity.class)));
        root.addView(strip);
        positionMiniPlayer(strip, landscape, width);
        if (Build.VERSION.SDK_INT >= 21) strip.setElevation(dp(66));
        player(a, PlayerService.ACTION_QUERY_STATE);
    }

    private void positionMiniPlayer(LinearLayout strip, boolean landscape, int width) {
        FrameLayout.LayoutParams p = strip.getLayoutParams() instanceof FrameLayout.LayoutParams
                ? (FrameLayout.LayoutParams) strip.getLayoutParams() : new FrameLayout.LayoutParams(dp(320), dp(58));
        p.height = dp(58);
        if (landscape) {
            p.width = Math.min(dp(330), Math.max(dp(270), width / 4)); p.gravity = Gravity.BOTTOM | Gravity.RIGHT;
            p.setMargins(0, 0, dp(14), dp(10));
        } else {
            p.width = -1; p.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            p.setMargins(dp(14), 0, dp(14), dp(10));
        }
        strip.setLayoutParams(p); strip.setVisibility(View.VISIBLE);
    }

    private void syncMiniPlayer(FrameLayout root) {
        View v = root.findViewWithTag(TAG_PLAYER);
        if (!(v instanceof ViewGroup)) return;
        TextView title = findTagged((ViewGroup) v, "player-title", TextView.class);
        TextView artist = findTagged((ViewGroup) v, "player-artist", TextView.class);
        Button toggle = findTagged((ViewGroup) v, "player-toggle", Button.class);
        if (title != null) title.setText(playerTitle);
        if (artist != null) artist.setText(playerArtist);
        if (toggle != null) toggle.setText(playerPlaying ? "Ⅱ" : "▶");
    }

    private static LinearLayout metricParent(TextView a, TextView b, TextView c) {
        if (a == null || b == null || c == null || a.getParent() == null) return null;
        if (a.getParent() == b.getParent() && a.getParent() == c.getParent() && a.getParent() instanceof LinearLayout) return (LinearLayout) a.getParent();
        return null;
    }

    private static Button mediaButton(Context c, String value, boolean primary) {
        Button b = new Button(c); b.setText(value); b.setAllCaps(false); b.setTextSize(10f); b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); b.setPadding(0,0,0,0); b.setMinHeight(0); b.setMinimumHeight(0); b.setStateListAnimator(null);
        b.setBackground(round(primary ? Color.rgb(196, 18, 40) : Color.rgb(19, 24, 31), 14, primary ? 0 : Color.rgb(76, 38, 44)));
        return b;
    }

    private static void player(Context c, String action) {
        try { c.startService(new Intent(c, PlayerService.class).setAction(action)); } catch (Throwable ignored) {}
    }

    private static Button findButton(View view, String text) {
        if (view instanceof Button && text.equalsIgnoreCase(safe(String.valueOf(((Button) view).getText())))) return (Button) view;
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i=0;i<g.getChildCount();i++) { Button b=findButton(g.getChildAt(i),text); if(b!=null)return b; }
        }
        return null;
    }

    private static boolean contains(View view, String token) {
        String wanted = token.toUpperCase(Locale.ROOT);
        if (view instanceof TextView) {
            String s = safe(String.valueOf(((TextView) view).getText())).toUpperCase(Locale.ROOT);
            if (s.contains(wanted)) return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i=0;i<g.getChildCount();i++) if (contains(g.getChildAt(i), token)) return true;
        }
        return false;
    }

    private static boolean hasOurTag(View v) {
        Object tag = v == null ? null : v.getTag();
        return TAG_CENTRAL.equals(tag) || TAG_PLAYER.equals(tag);
    }

    private static <T> T field(Object owner, String name, Class<T> type) {
        if (owner == null) return null;
        try {
            Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true);
            Object v = f.get(owner); return type.isInstance(v) ? type.cast(v) : null;
        } catch (Throwable ignored) { return null; }
    }

    private static <T extends View> T findTagged(ViewGroup root, String tag, Class<T> type) {
        for (int i=0;i<root.getChildCount();i++) {
            View v=root.getChildAt(i);
            if (tag.equals(v.getTag()) && type.isInstance(v)) return type.cast(v);
            if (v instanceof ViewGroup) { T found=findTagged((ViewGroup)v,tag,type); if(found!=null)return found; }
        }
        return null;
    }

    private static TextView text(Context c, String value, float size, int color, boolean bold) {
        TextView t = new TextView(c); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t;
    }

    private static GradientDrawable round(int color, int radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(radiusDp * density);
        if (stroke != 0) g.setStroke(Math.max(1, Math.round(density)), stroke); return g;
    }

    private static float density = 1f;
    private static int dp(float v) { return Math.round(v * density); }
    private static String safe(String s) { return s == null ? "" : s.trim(); }

    @Override public void onActivityCreated(Activity activity, Bundle state) {
        if (activity instanceof RoadMapActivity) {
            density = activity.getResources().getDisplayMetrics().density;
            resumed = new WeakReference<>((RoadMapActivity) activity);
            scheduleApply((RoadMapActivity) activity, 140L);
        }
    }
    @Override public void onActivityResumed(Activity activity) {
        if (!(activity instanceof RoadMapActivity)) return;
        density = activity.getResources().getDisplayMetrics().density;
        RoadMapActivity a=(RoadMapActivity)activity; resumed=new WeakReference<>(a);
        scheduleApply(a,80L); scheduleApply(a,420L); scheduleApply(a,1200L);
    }
    @Override public void onActivityPaused(Activity activity) { if (resumed.get()==activity) resumed=new WeakReference<>(null); }
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
    @Override public void onActivityDestroyed(Activity activity) {}
}

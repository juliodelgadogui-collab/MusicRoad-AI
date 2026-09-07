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
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ROUTE_DIRECTION_V320
 * Keeps route cancellation reachable while navigating and detects sustained travel in the
 * opposite direction of the calculated route. Detection is deliberately conservative so a
 * short turn, GPS wobble or a maneuver near an intersection does not trigger a false warning.
 */
final class RouteNavigationAssist implements Application.ActivityLifecycleCallbacks {
    private static final String CANCEL_TAG = "epc-route-cancel-v320";
    private static final String WRONG_TAG = "epc-route-wrong-way-v320";
    private static final long ROUTE_RELOAD_GAP_MS = 5_000L;
    private static final long VOICE_REPEAT_MS = 35_000L;
    private static final double MAX_ROUTE_LATERAL_M = 55.0;
    private static final double MIN_DIRECTION_SPEED_KMH = 12.0;

    private final Application app;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean loadingRoute = new AtomicBoolean(false);
    private WeakReference<Activity> resumed = new WeakReference<>(null);

    private volatile RouteEngine.Route cachedRoute;
    private volatile double cachedToLat = Double.NaN;
    private volatile double cachedToLon = Double.NaN;
    private long lastRouteLoadAt;
    private int segmentHint = -1;
    private int oppositeSamples;
    private int correctSamples;
    private double oppositeStartAlong = Double.NaN;
    private boolean wrongActive;
    private long lastWrongVoiceAt;

    private TextToSpeech tts;
    private boolean ttsReady;

    static RouteNavigationAssist install(Application app) {
        RouteNavigationAssist assist = new RouteNavigationAssist(app);
        app.registerActivityLifecycleCallbacks(assist);
        IntentFilter filter = new IntentFilter(RoadSafetyService.ACTION_STATE);
        try {
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(assist.roadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else app.registerReceiver(assist.roadReceiver, filter);
        } catch (Throwable ignored) {}
        assist.initVoice();
        return assist;
    }

    private RouteNavigationAssist(Application application) {
        app = application;
    }

    private final BroadcastReceiver roadReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            Activity activity = resumed.get();
            if (!(activity instanceof RoadMapActivity) || activity.isFinishing()) return;

            DestinationStore.Destination destination = DestinationStore.read(app);
            syncCancelButton(activity, destination != null);
            if (destination == null) {
                resetRouteState(activity, true);
                return;
            }

            double lat = intent.getDoubleExtra("lat", Double.NaN);
            double lon = intent.getDoubleExtra("lon", Double.NaN);
            double speedKmh = intent.getDoubleExtra("speed_kmh", 0.0);
            double heading = intent.getFloatExtra("heading", -1f);
            if (!Double.isFinite(lat) || !Double.isFinite(lon)) return;

            RouteEngine.Route route = cachedRoute;
            if (route == null || !sameDestination(destination)) {
                ensureRouteLoaded(destination, lat, lon);
                return;
            }
            evaluateDirection(activity, route, lat, lon, heading, speedKmh);
        }
    };

    private void ensureRouteLoaded(DestinationStore.Destination destination, double lat, double lon) {
        long now = System.currentTimeMillis();
        if (now - lastRouteLoadAt < ROUTE_RELOAD_GAP_MS || !loadingRoute.compareAndSet(false, true)) return;
        lastRouteLoadAt = now;
        final double toLat = destination.lat, toLon = destination.lon;
        io.execute(() -> {
            try {
                RouteEngine.Route route = RouteOfflineCache.load(app, lat, lon, toLat, toLon);
                if (route != null) {
                    cachedRoute = route;
                    cachedToLat = toLat;
                    cachedToLon = toLon;
                    segmentHint = -1;
                    oppositeSamples = 0;
                    correctSamples = 0;
                    oppositeStartAlong = Double.NaN;
                }
            } catch (Throwable ignored) {
            } finally {
                loadingRoute.set(false);
            }
        });
    }

    private boolean sameDestination(DestinationStore.Destination d) {
        return d != null && Double.isFinite(cachedToLat) && Double.isFinite(cachedToLon)
                && RouteEngine.distanceM(d.lat, d.lon, cachedToLat, cachedToLon) <= 300.0;
    }

    private void evaluateDirection(Activity activity, RouteEngine.Route route,
                                   double lat, double lon, double heading, double speedKmh) {
        if (!Double.isFinite(heading) || heading < 0 || speedKmh < MIN_DIRECTION_SPEED_KMH
                || MobilityModeState.isPedestrian()) {
            softenDirectionState(activity);
            return;
        }

        // Match without heading bias: first locate the nearest route segment, then compare the
        // real travel heading with the direction in which that segment must be driven.
        RouteEngine.Match match = route.match(lat, lon, Double.NaN, segmentHint, 0.0);
        if (!match.valid || match.lateralM > MAX_ROUTE_LATERAL_M || !Double.isFinite(match.segmentBearing)) {
            softenDirectionState(activity);
            return;
        }
        segmentHint = match.segmentIndex;

        double diff = angleDiff(heading, match.segmentBearing);
        boolean stronglyOpposite = diff >= 150.0;
        boolean possiblyOpposite = diff >= 132.0;

        if (possiblyOpposite) {
            correctSamples = 0;
            if (oppositeSamples == 0) oppositeStartAlong = match.alongM;
            oppositeSamples++;
            boolean movingBackwards = Double.isFinite(oppositeStartAlong) && match.alongM <= oppositeStartAlong - 9.0;
            double maneuverDistance = route.nextManeuverDistance(match.alongM);
            int required = maneuverDistance > 0 && maneuverDistance < 70.0 ? 8 : 5;
            if (oppositeSamples >= required && (stronglyOpposite || movingBackwards)) {
                showWrongDirection(activity);
            }
            return;
        }

        if (diff <= 85.0) {
            correctSamples++;
            oppositeSamples = 0;
            oppositeStartAlong = Double.NaN;
            if (wrongActive && correctSamples >= 3) hideWrongDirection(activity);
        } else {
            // Ambiguous 85–132 degree movement: do not accumulate a wrong-way decision.
            oppositeSamples = Math.max(0, oppositeSamples - 1);
            correctSamples = 0;
        }
    }

    private void softenDirectionState(Activity activity) {
        oppositeSamples = 0;
        oppositeStartAlong = Double.NaN;
        correctSamples++;
        if (wrongActive && correctSamples >= 3) hideWrongDirection(activity);
    }

    private void showWrongDirection(Activity activity) {
        wrongActive = true;
        FrameLayout host = contentHost(activity);
        if (host == null) return;

        View old = host.findViewWithTag(WRONG_TAG);
        if (old == null) {
            LinearLayout card = new LinearLayout(activity);
            card.setTag(WRONG_TAG);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            card.setPadding(dp(activity, 18), dp(activity, 14), dp(activity, 18), dp(activity, 14));
            card.setBackground(panel(activity, Color.argb(250, 34, 5, 9), 18, Color.rgb(239, 41, 58), 2));
            if (Build.VERSION.SDK_INT >= 21) card.setElevation(dp(activity, 96));

            TextView top = text(activity, "SENTIDO CONTRÁRIO À ROTA", 12, Color.rgb(255, 77, 88), true);
            top.setGravity(Gravity.CENTER);
            top.setLetterSpacing(.08f);
            card.addView(top);

            TextView main = text(activity, "VOLTE PARA A ROTA", 22, Color.WHITE, true);
            main.setGravity(Gravity.CENTER);
            card.addView(main);

            TextView detail = text(activity, "Faça o retorno quando for seguro.", 11, Color.rgb(216, 190, 187), false);
            detail.setGravity(Gravity.CENTER);
            card.addView(detail);

            Button cancel = cancelButton(activity, "CANCELAR ROTA");
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, dp(activity, 44));
            cp.setMargins(0, dp(activity, 10), 0, 0);
            card.addView(cancel, cp);
            cancel.setOnClickListener(v -> cancelRoute(activity));

            int sw = activity.getResources().getDisplayMetrics().widthPixels;
            int width = Math.min(dp(activity, 460), Math.max(dp(activity, 280), sw - dp(activity, 36)));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, -2, Gravity.CENTER);
            host.addView(card, lp);
            card.setAlpha(0f);
            card.setScaleX(.95f);
            card.setScaleY(.95f);
            card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(170L).start();
        }

        long now = System.currentTimeMillis();
        if (now - lastWrongVoiceAt >= VOICE_REPEAT_MS) {
            lastWrongVoiceAt = now;
            speakWrongDirection();
        }
    }

    private void hideWrongDirection(Activity activity) {
        wrongActive = false;
        correctSamples = 0;
        FrameLayout host = contentHost(activity);
        if (host == null) return;
        View card = host.findViewWithTag(WRONG_TAG);
        if (card != null) {
            card.animate().alpha(0f).scaleX(.96f).scaleY(.96f).setDuration(140L)
                    .withEndAction(() -> { try { if (card.getParent() == host) host.removeView(card); } catch (Throwable ignored) {} })
                    .start();
        }
    }

    private void syncCancelButton(Activity activity, boolean routeActive) {
        FrameLayout host = contentHost(activity);
        if (host == null) return;
        View existing = host.findViewWithTag(CANCEL_TAG);
        if (!routeActive) {
            if (existing != null) host.removeView(existing);
            return;
        }
        if (existing != null) return;

        Button cancel = cancelButton(activity, "CANCELAR ROTA");
        cancel.setTag(CANCEL_TAG);
        cancel.setOnClickListener(v -> cancelRoute(activity));
        int w = activity.getResources().getDisplayMetrics().widthPixels;
        int h = activity.getResources().getDisplayMetrics().heightPixels;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(activity, 126), dp(activity, 42));
        if (w > h) {
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.topMargin = dp(activity, 14);
        } else {
            lp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
            lp.rightMargin = dp(activity, 14);
            lp.bottomMargin = dp(activity, 92);
        }
        host.addView(cancel, lp);
        if (Build.VERSION.SDK_INT >= 21) cancel.setElevation(dp(activity, 82));
    }

    private Button cancelButton(Context context, String label) {
        Button b = new Button(context);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(9f);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setPadding(dp(context, 8), 0, dp(context, 8), 0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setBackground(panel(context, Color.argb(242, 54, 10, 16), 14, Color.rgb(184, 20, 38), 1));
        return b;
    }

    private void cancelRoute(Activity activity) {
        DestinationStore.clear(app);
        resetRouteState(activity, true);
        Toast.makeText(activity, "Rota cancelada · proteção da estrada continua ativa", Toast.LENGTH_SHORT).show();
        try {
            Intent refresh = new Intent(activity, RoadMapActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            activity.startActivity(refresh);
        } catch (Throwable ignored) {}
    }

    private void resetRouteState(Activity activity, boolean removeViews) {
        cachedRoute = null;
        cachedToLat = Double.NaN;
        cachedToLon = Double.NaN;
        segmentHint = -1;
        oppositeSamples = 0;
        correctSamples = 0;
        oppositeStartAlong = Double.NaN;
        wrongActive = false;
        if (!removeViews || activity == null) return;
        FrameLayout host = contentHost(activity);
        if (host == null) return;
        View cancel = host.findViewWithTag(CANCEL_TAG);
        if (cancel != null) host.removeView(cancel);
        View wrong = host.findViewWithTag(WRONG_TAG);
        if (wrong != null) host.removeView(wrong);
    }

    private void speakWrongDirection() {
        if (!ttsReady || tts == null) return;
        try {
            tts.speak("Você está no sentido contrário da rota. Volte para a rota quando for seguro.",
                    TextToSpeech.QUEUE_FLUSH, null, "epc-route-wrong-direction");
        } catch (Throwable ignored) {}
    }

    private void initVoice() {
        try {
            tts = new TextToSpeech(app, status -> {
                if (status != TextToSpeech.SUCCESS || tts == null) return;
                ttsReady = true;
                try {
                    tts.setLanguage(new Locale("pt", "BR"));
                    tts.setSpeechRate(.94f);
                    if (Build.VERSION.SDK_INT >= 21) {
                        android.media.AudioAttributes attrs = new android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build();
                        tts.setAudioAttributes(attrs);
                    }
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    private FrameLayout contentHost(Activity activity) {
        try {
            View content = activity.findViewById(android.R.id.content);
            return content instanceof FrameLayout ? (FrameLayout) content : null;
        } catch (Throwable ignored) { return null; }
    }

    private static double angleDiff(double a, double b) {
        double d = Math.abs(a - b) % 360.0;
        return d > 180.0 ? 360.0 - d : d;
    }

    private static TextView text(Context c, String value, float size, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private static GradientDrawable panel(Context c, int color, int radiusDp, int stroke, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0 && strokeDp > 0) g.setStroke(dp(c, strokeDp), stroke);
        return g;
    }

    private static int dp(Context c, float value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    @Override public void onActivityResumed(Activity activity) {
        resumed = new WeakReference<>(activity);
        if (activity instanceof RoadMapActivity) syncCancelButton(activity, DestinationStore.read(app) != null);
    }

    @Override public void onActivityPaused(Activity activity) {
        Activity current = resumed.get();
        if (current == activity) resumed = new WeakReference<>(null);
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) {}
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
    @Override public void onActivityDestroyed(Activity activity) {
        Activity current = resumed.get();
        if (current == activity) resumed = new WeakReference<>(null);
    }
}

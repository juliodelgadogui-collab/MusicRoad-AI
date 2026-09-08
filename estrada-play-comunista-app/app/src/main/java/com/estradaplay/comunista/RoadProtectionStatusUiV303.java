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
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/**
 * Protection continuity + road-screen recovery UI.
 *
 * V5.0.2 fixes a false unavailable state seen while the vehicle is stopped: Android may legitimately
 * stop delivering location callbacks for a while when the requested minimum movement was not reached.
 * A recent valid fix + zero speed + enabled location provider is therefore treated as a stationary
 * session, not as a protection failure. Real stale streams while moving still surface a warning and
 * re-kick RoadSafetyService.
 *
 * It also keeps a permanent way back to Central on the road screen and forces a clean Activity
 * reconstruction when the physical layout crosses portrait/landscape, avoiding a half-rotated cockpit.
 */
final class RoadProtectionStatusUiV303 implements Application.ActivityLifecycleCallbacks {
    private static final String TAG = "ep-protection-unavailable-v502";
    private static final String ESTIMATE_TAG = "ep-gps-estimated-v502";
    private static final String NAV_TAG = "ep-road-navigation-v502";
    private static final long UI_STALE_MS = 35_000L;
    private static final long STATIONARY_GRACE_MS = 5L * 60L * 1000L;
    private static final long RECOVERY_COOLDOWN_MS = 20_000L;

    private final Application app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WeakReference<Activity> resumed = new WeakReference<>(null);
    private long lastStateAt;
    private long lastGoodFixAt;
    private long lastRecoveryAt;
    private double lastSpeedKmh;
    private View.OnLayoutChangeListener layoutListener;
    private boolean orientationKnown;
    private boolean lastLandscape;
    private boolean recreatingForOrientation;

    static void install(Application app) {
        if (app == null) return;
        RoadProtectionStatusUiV303 bridge = new RoadProtectionStatusUiV303(app);
        app.registerActivityLifecycleCallbacks(bridge);
        IntentFilter f = new IntentFilter(RoadSafetyService.ACTION_STATE);
        try { InternalBroadcasts.register(app, bridge.receiver, f); }
        catch (Throwable ignored) {}
    }

    private RoadProtectionStatusUiV303(Application app) { this.app = app; }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            long now = System.currentTimeMillis();
            lastStateAt = now;
            lastSpeedKmh = Math.max(0.0, intent.getDoubleExtra("speed_kmh", lastSpeedKmh));
            boolean available = intent.getBooleanExtra("protection_available", true);
            double lat = intent.getDoubleExtra("lat", Double.NaN);
            double lon = intent.getDoubleExtra("lon", Double.NaN);
            if (available && Double.isFinite(lat) && Double.isFinite(lon)) lastGoodFixAt = now;

            Activity a = resumed.get();
            if (!(a instanceof RoadMapActivity) || a.isFinishing()) return;

            if (intent.getBooleanExtra("estimated_position", false)) {
                hideUnavailable(a);
                showEstimate(a, "GPS EM ESTIMATIVA · aguardando sinal");
                return;
            }

            hideEstimate(a);
            if (available) {
                hideUnavailable(a);
                return;
            }

            long gpsAge = intent.getLongExtra("gps_fix_age_ms", now - Math.max(1L, lastGoodFixAt));
            if (stationaryWithRecentFix(a, gpsAge)) {
                // The vehicle is stopped and Android simply has not emitted another movement-qualified fix.
                // Do not present this normal condition as a protection outage.
                hideUnavailable(a);
                recoverProtection();
            } else {
                showUnavailable(a, "GPS sem atualização confiável · recuperando proteção");
                recoverProtection();
            }
        }
    };

    private final Runnable staleCheck = new Runnable() {
        @Override public void run() {
            Activity a = resumed.get();
            if (a instanceof RoadMapActivity && !a.isFinishing()) {
                long now = System.currentTimeMillis();
                long age = now - lastStateAt;
                if (lastStateAt > 0L && age >= UI_STALE_MS) {
                    hideEstimate(a);
                    if (stationaryWithRecentFix(a, age)) {
                        hideUnavailable(a);
                    } else {
                        showUnavailable(a, "Sem atualização da proteção · tentando recuperar");
                    }
                    recoverProtection();
                }
                ensureNavigation(a);
                main.postDelayed(this, 5_000L);
            }
        }
    };

    private boolean stationaryWithRecentFix(Activity a, long reportedAge) {
        if (lastGoodFixAt <= 0L || lastSpeedKmh > 2.5) return false;
        long wallAge = System.currentTimeMillis() - lastGoodFixAt;
        long age = Math.max(Math.max(0L, reportedAge), wallAge);
        return age <= STATIONARY_GRACE_MS && locationProviderEnabled(a);
    }

    private boolean locationProviderEnabled(Context context) {
        try {
            LocationManager lm = (LocationManager)context.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return false;
            boolean gps = false, network = false;
            try { gps = lm.isProviderEnabled(LocationManager.GPS_PROVIDER); } catch (Throwable ignored) {}
            try { network = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER); } catch (Throwable ignored) {}
            return gps || network;
        } catch (Throwable ignored) { return false; }
    }

    private void recoverProtection() {
        long now = System.currentTimeMillis();
        if (now - lastRecoveryAt < RECOVERY_COOLDOWN_MS) return;
        lastRecoveryAt = now;
        try {
            Intent i = new Intent(app, RoadSafetyService.class);
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i); else app.startService(i);
        } catch (Throwable ignored) {}
    }

    private void showUnavailable(Activity a, String detail) {
        FrameLayout host = host(a);
        if (host == null) return;
        View existing = host.findViewWithTag(TAG);
        if (existing instanceof LinearLayout) {
            LinearLayout card = (LinearLayout)existing;
            if (card.getChildCount() > 1 && card.getChildAt(1) instanceof TextView)
                ((TextView)card.getChildAt(1)).setText(detail);
            return;
        }
        LinearLayout card = new LinearLayout(a);
        card.setTag(TAG);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(a,16),dp(a,11),dp(a,16),dp(a,11));
        card.setBackground(panel(a, Color.argb(250,38,7,11), 17, Color.rgb(239,41,58)));
        TextView title = text(a,"PROTEÇÃO TEMPORARIAMENTE INDISPONÍVEL",11,Color.rgb(255,84,94),true);
        title.setGravity(Gravity.CENTER);
        card.addView(title);
        TextView body = text(a,detail,10,Color.WHITE,false);
        body.setGravity(Gravity.CENTER);
        card.addView(body);
        int sw = a.getResources().getDisplayMetrics().widthPixels;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                Math.min(dp(a,440),Math.max(dp(a,280),sw-dp(a,36))),-2,
                Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        lp.topMargin = dp(a,76);
        host.addView(card,lp);
        if (Build.VERSION.SDK_INT >= 21) card.setElevation(dp(a,110));
    }

    private void showEstimate(Activity a, String value) {
        FrameLayout host = host(a);
        if (host == null) return;
        View existing = host.findViewWithTag(ESTIMATE_TAG);
        if (existing instanceof TextView) { ((TextView)existing).setText(value); return; }
        TextView chip = text(a,value,9,Color.rgb(255,226,154),true);
        chip.setTag(ESTIMATE_TAG);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(a,14),dp(a,8),dp(a,14),dp(a,8));
        chip.setBackground(panel(a,Color.argb(242,48,34,9),100,Color.rgb(226,185,76)));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        lp.topMargin = dp(a,76);
        host.addView(chip,lp);
        if (Build.VERSION.SDK_INT >= 21) chip.setElevation(dp(a,108));
    }

    private void ensureNavigation(Activity a) {
        FrameLayout host = host(a);
        if (host == null || host.findViewWithTag(NAV_TAG) != null) return;
        int width = host.getWidth() > 0 ? host.getWidth() : a.getResources().getDisplayMetrics().widthPixels;
        int height = host.getHeight() > 0 ? host.getHeight() : a.getResources().getDisplayMetrics().heightPixels;
        boolean landscape = width > height;

        if (landscape) {
            LinearLayout dock = new LinearLayout(a);
            dock.setTag(NAV_TAG);
            dock.setOrientation(LinearLayout.VERTICAL);
            dock.setGravity(Gravity.CENTER);
            dock.setPadding(dp(a,6),dp(a,7),dp(a,6),dp(a,7));
            dock.setBackground(panel(a,Color.argb(244,9,13,18),18,Color.rgb(52,72,92)));

            Button central = navButton(a,"⌂  CENTRAL",true);
            Button destination = navButton(a,"⌖  DESTINO",false);
            Button music = navButton(a,"♪  MÚSICA",false);
            dock.addView(central,new LinearLayout.LayoutParams(-1,dp(a,48)));
            LinearLayout.LayoutParams middle = new LinearLayout.LayoutParams(-1,dp(a,48)); middle.topMargin=dp(a,5);
            dock.addView(destination,middle);
            LinearLayout.LayoutParams last = new LinearLayout.LayoutParams(-1,dp(a,48)); last.topMargin=dp(a,5);
            dock.addView(music,last);

            central.setOnClickListener(v -> openCentral(a));
            destination.setOnClickListener(v -> a.startActivity(new Intent(a, DestinationActivity.class)));
            music.setOnClickListener(v -> a.startActivity(new Intent(a, MusicPlayerActivity.class)));

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(a,116),-2,Gravity.LEFT|Gravity.CENTER_VERTICAL);
            lp.leftMargin = dp(a,10);
            host.addView(dock,lp);
            if (Build.VERSION.SDK_INT >= 21) dock.setElevation(dp(a,130));
        } else {
            Button central = navButton(a,"⌂  CENTRAL",true);
            central.setTag(NAV_TAG);
            central.setOnClickListener(v -> openCentral(a));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(a,118),dp(a,48),Gravity.LEFT|Gravity.BOTTOM);
            lp.leftMargin = dp(a,10);
            lp.bottomMargin = dp(a,108);
            host.addView(central,lp);
            if (Build.VERSION.SDK_INT >= 21) central.setElevation(dp(a,130));
        }
    }

    private Button navButton(Activity a, String value, boolean primary) {
        Button b = new Button(a);
        b.setText(value);
        b.setAllCaps(false);
        b.setTextSize(10);
        b.setTextColor(Color.rgb(242,245,248));
        b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(a,7),0,dp(a,7),0);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setStateListAnimator(null);
        b.setBackground(panel(a,
                primary ? Color.rgb(35,130,246) : Color.rgb(17,25,34),
                14,
                primary ? Color.rgb(78,160,255) : Color.rgb(55,75,95)));
        return b;
    }

    private void openCentral(Activity a) {
        try {
            Intent i = new Intent(a, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            a.startActivity(i);
            a.finish();
        } catch (Throwable ignored) {}
    }

    private void installOrientationRecovery(Activity a) {
        FrameLayout host = host(a);
        if (host == null) return;
        removeOrientationRecovery(a);
        int w = host.getWidth();
        int h = host.getHeight();
        if (w > 0 && h > 0) {
            lastLandscape = w > h;
            orientationKnown = true;
        } else orientationKnown = false;
        recreatingForOrientation = false;

        layoutListener = (v,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom) -> {
            int nw = right-left, nh = bottom-top;
            if (nw <= 0 || nh <= 0) return;
            boolean landscape = nw > nh;
            if (!orientationKnown) {
                orientationKnown = true;
                lastLandscape = landscape;
                return;
            }
            if (landscape == lastLandscape) return;
            lastLandscape = landscape;
            removeNavigation(a);
            main.postDelayed(() -> ensureNavigation(a),120L);
            if (!recreatingForOrientation) {
                recreatingForOrientation = true;
                main.postDelayed(() -> {
                    try { if (!a.isFinishing() && resumed.get() == a) a.recreate(); }
                    catch (Throwable ignored) { recreatingForOrientation = false; }
                },180L);
            }
        };
        host.addOnLayoutChangeListener(layoutListener);
    }

    private void removeOrientationRecovery(Activity a) {
        FrameLayout host = host(a);
        if (host != null && layoutListener != null) {
            try { host.removeOnLayoutChangeListener(layoutListener); } catch (Throwable ignored) {}
        }
        layoutListener = null;
    }

    private void removeNavigation(Activity a) {
        FrameLayout host = host(a);
        if (host == null) return;
        View v = host.findViewWithTag(NAV_TAG);
        if (v != null) host.removeView(v);
    }

    private void hideUnavailable(Activity a) {
        FrameLayout host=host(a); if(host==null)return;
        View v=host.findViewWithTag(TAG); if(v!=null)host.removeView(v);
    }

    private void hideEstimate(Activity a) {
        FrameLayout host=host(a); if(host==null)return;
        View v=host.findViewWithTag(ESTIMATE_TAG); if(v!=null)host.removeView(v);
    }

    private void hideAll(Activity a) { hideUnavailable(a); hideEstimate(a); }

    private static FrameLayout host(Activity a) {
        try {
            View v=a.findViewById(android.R.id.content);
            return v instanceof FrameLayout ? (FrameLayout)v : null;
        } catch(Throwable ignored) { return null; }
    }

    private static TextView text(Context c,String s,float size,int color,boolean bold) {
        TextView t=new TextView(c); t.setText(s); t.setTextSize(size); t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD); return t;
    }

    private static GradientDrawable panel(Context c,int color,int radius,int stroke) {
        GradientDrawable g=new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(c,radius));
        if (stroke != 0) g.setStroke(dp(c,1),stroke); return g;
    }

    private static int dp(Context c,float v) {
        return Math.round(v*c.getResources().getDisplayMetrics().density);
    }

    @Override public void onActivityResumed(Activity activity) {
        if (!(activity instanceof RoadMapActivity)) return;
        resumed = new WeakReference<>(activity);
        lastStateAt = System.currentTimeMillis();
        main.removeCallbacks(staleCheck);
        recoverProtection();
        installOrientationRecovery(activity);
        main.post(() -> ensureNavigation(activity));
        main.postDelayed(staleCheck,5_000L);
    }

    @Override public void onActivityPaused(Activity activity) {
        if (resumed.get()==activity) {
            hideAll(activity);
            removeNavigation(activity);
            removeOrientationRecovery(activity);
            resumed=new WeakReference<>(null);
            main.removeCallbacks(staleCheck);
        }
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) {}
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
    @Override public void onActivityDestroyed(Activity activity) {}
}

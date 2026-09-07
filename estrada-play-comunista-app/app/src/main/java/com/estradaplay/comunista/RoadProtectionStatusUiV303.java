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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/** Makes loss of the protection/GPS stream explicit on the Estrada screen. */
final class RoadProtectionStatusUiV303 implements Application.ActivityLifecycleCallbacks {
    private static final String TAG = "epc-protection-unavailable-v303";
    private static final String ESTIMATE_TAG = "epc-gps-estimated-v330";
    private static final long UI_STALE_MS = 22_000L;

    private final Application app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WeakReference<Activity> resumed = new WeakReference<>(null);
    private long lastStateAt;

    static void install(Application app) {
        if (app == null) return;
        RoadProtectionStatusUiV303 bridge = new RoadProtectionStatusUiV303(app);
        app.registerActivityLifecycleCallbacks(bridge);
        IntentFilter f = new IntentFilter(RoadSafetyService.ACTION_STATE);
        try {
            if (Build.VERSION.SDK_INT >= 33) InternalBroadcasts.register(app, bridge.receiver, f);
            else InternalBroadcasts.register(app, bridge.receiver, f);
        } catch (Throwable ignored) {}
    }

    private RoadProtectionStatusUiV303(Application app) { this.app = app; }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            lastStateAt = System.currentTimeMillis();
            Activity a = resumed.get();
            if (!(a instanceof RoadMapActivity) || a.isFinishing()) return;
            if (intent.getBooleanExtra("estimated_position", false)) {
                hideUnavailable(a);
                showEstimate(a);
                return;
            }
            hideEstimate(a);
            boolean available = intent.getBooleanExtra("protection_available", true);
            if (available) hideUnavailable(a); else showUnavailable(a, "GPS sem sinal confiável · tentando recuperar");
        }
    };

    private final Runnable staleCheck = new Runnable() {
        @Override public void run() {
            Activity a = resumed.get();
            if (a instanceof RoadMapActivity && !a.isFinishing()) {
                long age = System.currentTimeMillis() - lastStateAt;
                if (lastStateAt > 0L && age >= UI_STALE_MS) { hideEstimate(a); showUnavailable(a, "Sem atualização da proteção · tentando recuperar"); }
                main.postDelayed(this, 5_000L);
            }
        }
    };

    private void showUnavailable(Activity a, String detail) {
        FrameLayout host = host(a);
        if (host == null || host.findViewWithTag(TAG) != null) return;
        LinearLayout card = new LinearLayout(a);
        card.setTag(TAG);card.setOrientation(LinearLayout.VERTICAL);card.setGravity(Gravity.CENTER);
        card.setPadding(dp(a,16),dp(a,11),dp(a,16),dp(a,11));
        card.setBackground(panel(a, Color.argb(250,38,7,11), 17, Color.rgb(239,41,58)));
        TextView title = text(a,"PROTEÇÃO TEMPORARIAMENTE INDISPONÍVEL",11,Color.rgb(255,84,94),true);title.setGravity(Gravity.CENTER);card.addView(title);
        TextView body = text(a,detail,10,Color.WHITE,false);body.setGravity(Gravity.CENTER);card.addView(body);
        int sw=a.getResources().getDisplayMetrics().widthPixels;
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(Math.min(dp(a,440),Math.max(dp(a,280),sw-dp(a,36))),-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);lp.topMargin=dp(a,76);host.addView(card,lp);
        if(Build.VERSION.SDK_INT>=21)card.setElevation(dp(a,110));
    }

    private void showEstimate(Activity a){
        FrameLayout host=host(a);if(host==null||host.findViewWithTag(ESTIMATE_TAG)!=null)return;
        TextView chip=text(a,"GPS EM ESTIMATIVA · aguardando sinal",9,Color.rgb(255,226,154),true);chip.setTag(ESTIMATE_TAG);chip.setGravity(Gravity.CENTER);chip.setPadding(dp(a,14),dp(a,8),dp(a,14),dp(a,8));chip.setBackground(panel(a,Color.argb(242,48,34,9),100,Color.rgb(226,185,76)));
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);lp.topMargin=dp(a,76);host.addView(chip,lp);if(Build.VERSION.SDK_INT>=21)chip.setElevation(dp(a,108));
    }

    private void hideUnavailable(Activity a) {FrameLayout host=host(a);if(host==null)return;View v=host.findViewWithTag(TAG);if(v!=null)host.removeView(v);}
    private void hideEstimate(Activity a){FrameLayout host=host(a);if(host==null)return;View v=host.findViewWithTag(ESTIMATE_TAG);if(v!=null)host.removeView(v);}
    private void hideAll(Activity a){hideUnavailable(a);hideEstimate(a);}

    private static FrameLayout host(Activity a){try{View v=a.findViewById(android.R.id.content);return v instanceof FrameLayout?(FrameLayout)v:null;}catch(Throwable ignored){return null;}}
    private static TextView text(Context c,String s,float size,int color,boolean bold){TextView t=new TextView(c);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private static GradientDrawable panel(Context c,int color,int radius,int stroke){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(c,radius));g.setStroke(dp(c,1),stroke);return g;}
    private static int dp(Context c,float v){return Math.round(v*c.getResources().getDisplayMetrics().density);}

    @Override public void onActivityResumed(Activity activity) {if (!(activity instanceof RoadMapActivity)) return;resumed = new WeakReference<>(activity);lastStateAt = System.currentTimeMillis();main.removeCallbacks(staleCheck);main.postDelayed(staleCheck, 5_000L);}
    @Override public void onActivityPaused(Activity activity) {if(resumed.get()==activity){hideAll(activity);resumed=new WeakReference<>(null);main.removeCallbacks(staleCheck);}}
    @Override public void onActivityCreated(Activity activity, Bundle state) {}
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
    @Override public void onActivityDestroyed(Activity activity) {}
}

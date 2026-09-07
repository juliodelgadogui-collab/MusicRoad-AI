package com.estradaplay.comunista;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/** Small in-app overlay shown above whichever Estrada Play Activity is currently visible. */
final class CopilotOverlayController implements Application.ActivityLifecycleCallbacks {
    private static final String TAG = "epc-copilot-overlay-v1";
    private final Application app;
    private WeakReference<Activity> resumed = new WeakReference<>(null);
    private String lastState = CopilotService.STATE_OFF;
    private String lastText = "";

    static CopilotOverlayController install(Application app) {
        CopilotOverlayController controller = new CopilotOverlayController(app);
        app.registerActivityLifecycleCallbacks(controller);
        IntentFilter filter = new IntentFilter();
        filter.addAction(CopilotService.ACTION_STATE);
        filter.addAction(CopilotService.ACTION_OPEN_SCREEN);
        try {
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(controller.receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else app.registerReceiver(controller.receiver, filter);
        } catch (Throwable ignored) {}
        return controller;
    }

    private CopilotOverlayController(Application app) {
        this.app = app;
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (CopilotService.ACTION_OPEN_SCREEN.equals(action)) {
                openRequestedScreen(intent.getStringExtra("screen"));
                return;
            }
            if (!CopilotService.ACTION_STATE.equals(action)) return;
            lastState = intent.getStringExtra("state");
            if (lastState == null) lastState = CopilotService.STATE_OFF;
            lastText = intent.getStringExtra("text");
            if (lastText == null) lastText = "";
            Activity activity = resumed.get();
            if (activity != null && !activity.isFinishing()) render(activity);
        }
    };

    private void openRequestedScreen(String screen) {
        Activity activity = resumed.get();
        if (activity == null || activity.isFinishing()) return;
        Class<?> target = screenClass(screen);
        if (target == null || target == activity.getClass()) return;
        try { activity.startActivity(new Intent(activity, target)); } catch (Throwable ignored) {}
    }

    private static Class<?> screenClass(String key) {
        if (key == null) return null;
        switch (key) {
            case "map": return RoadMapActivity.class;
            case "central": return DriveToolsActivity.class;
            case "planner": return TripPlannerActivity.class;
            case "favorites": return RoadFavoritesActivity.class;
            case "convoy": return ConvoyActivity.class;
            case "estrada_viva": return EstradaVivaActivity.class;
            case "maintenance": return MaintenanceActivity.class;
            case "fuel": return FuelCommunityActivity.class;
            case "obd": return Obd2Activity.class;
            case "sos": return EmergencyActivity.class;
            case "radio": return RoadRadioActivity.class;
            case "camera": return CameraActivity.class;
            case "services": return NearbyServicesActivity.class;
            case "history": return TripHistoryActivity.class;
            case "report": return RoadReportActivity.class;
            case "destination": return DestinationActivity.class;
            case "music": return MusicPlayerActivity.class;
            case "offline": return OfflineCenterActivity.class;
            case "weather": return WeatherActivity.class;
            default: return null;
        }
    }

    private void render(Activity activity) {
        FrameLayout host = contentHost(activity);
        if (host == null) return;
        View existing = host.findViewWithTag(TAG);
        if (!visibleState(lastState)) {
            if (existing != null) {
                existing.animate().alpha(0f).translationY(-dp(activity, 8)).setDuration(120L)
                        .withEndAction(() -> { try { if (existing.getParent() == host) host.removeView(existing); } catch (Throwable ignored) {} })
                        .start();
            }
            return;
        }

        TextView label;
        if (existing instanceof LinearLayout && ((LinearLayout) existing).getChildCount() > 0
                && ((LinearLayout) existing).getChildAt(0) instanceof TextView) {
            label = (TextView) ((LinearLayout) existing).getChildAt(0);
        } else {
            LinearLayout card = new LinearLayout(activity);
            card.setTag(TAG);
            card.setGravity(Gravity.CENTER);
            card.setPadding(dp(activity, 16), dp(activity, 9), dp(activity, 16), dp(activity, 9));
            card.setBackground(panel(activity));
            if (Build.VERSION.SDK_INT >= 21) card.setElevation(dp(activity, 96));
            label = new TextView(activity);
            label.setTextColor(Color.WHITE);
            label.setTextSize(12f);
            label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(2);
            card.addView(label, new LinearLayout.LayoutParams(-2, -2));
            int max = Math.max(dp(activity, 230), activity.getResources().getDisplayMetrics().widthPixels - dp(activity, 34));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.min(dp(activity, 430), max), -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            lp.topMargin = dp(activity, 16);
            host.addView(card, lp);
            card.setAlpha(0f);
            card.setTranslationY(-dp(activity, 8));
            card.animate().alpha(1f).translationY(0f).setDuration(150L).start();
        }
        label.setText(labelFor(lastState, lastText));
    }

    private static boolean visibleState(String state) {
        return CopilotService.STATE_LISTENING.equals(state)
                || CopilotService.STATE_PROCESSING.equals(state)
                || CopilotService.STATE_SPEAKING.equals(state);
    }

    private static String labelFor(String state, String text) {
        if (CopilotService.STATE_LISTENING.equals(state)) return "🎙  COPILOTO OUVINDO…";
        if (CopilotService.STATE_PROCESSING.equals(state)) return "COPILOTO · PROCESSANDO…";
        if (CopilotService.STATE_SPEAKING.equals(state)) return text == null || text.trim().isEmpty() ? "🔊  COPILOTO" : "🔊  COPILOTO\n" + text.trim();
        return "";
    }

    private static GradientDrawable panel(Context context) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.argb(246, 35, 7, 12));
        g.setCornerRadius(dp(context, 18));
        g.setStroke(dp(context, 1), Color.rgb(184, 20, 38));
        return g;
    }

    private static FrameLayout contentHost(Activity activity) {
        try {
            View content = activity.findViewById(android.R.id.content);
            return content instanceof FrameLayout ? (FrameLayout) content : null;
        } catch (Throwable ignored) { return null; }
    }

    private static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    @Override public void onActivityResumed(Activity activity) {
        resumed = new WeakReference<>(activity);
        render(activity);
        if (CopilotSettings.enabled(app)
                && activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            CopilotService.requestStart(activity);
        }
    }

    @Override public void onActivityPaused(Activity activity) {
        Activity current = resumed.get();
        if (current == activity) resumed = new WeakReference<>(null);
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) {}
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
    @Override public void onActivityDestroyed(Activity activity) {}
}

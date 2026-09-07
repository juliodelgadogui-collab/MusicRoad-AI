package com.estradaplay.comunista;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.List;

/** Process-level bridges for Server 7.0 context and live convoy presence. */
public final class EstradaPlayApplication extends Application {
    private static final String UI_PREFS = "estradaplay_ui_v1";
    private static final String KEY_ACCOUNT = "account";

    private RouteContextV7BackgroundReceiver contextReceiver;
    private ConvoyLiveBridge convoyReceiver;
    private MobilityModeState.Receiver mobilityReceiver;
    private RouteNavigationAssist routeNavigationAssist;
    private DriveRuntimeEnhancer driveRuntimeEnhancer;
    private CopilotOverlayController copilotOverlayController;

    // FOREGROUND_ONLY_ROAD_ALERTS_V411: road protection, wake-word microphone and PTT are
    // user-visible-session features. They must not keep the app alive after every Activity leaves
    // the screen. A short delay avoids false stops while rotating or moving between Activities.
    private final Handler foregroundHandler = new Handler(Looper.getMainLooper());
    private int visibleActivities;
    private Activity lastVisibleActivity;
    private final Runnable stopForegroundOnlyServices = () -> {
        if (visibleActivities != 0) return;
        try { stopService(new Intent(this, RoadSafetyService.class)); } catch (Throwable ignored) {}
        try { stopService(new Intent(this, CopilotService.class)); } catch (Throwable ignored) {}
        try { stopService(new Intent(this, RoadRadioService.class)); } catch (Throwable ignored) {}
    };

    @Override public void onCreate() {
        super.onCreate();

        // PTT_STABILITY_V234: RoadRadioService owns an isolated :radio process. Do not duplicate
        // Contexto Vivo, Comboio or the main crash-loop guard there; native audio failure must stay local.
        if (isRadioProcess()) return;

        installForegroundOnlyRoadSession();

        UiVersionLabelFix.register(this);
        try { ProductionTelemetryV400.install(this); } catch (Throwable ignored) {}
        try { MapStyleConfigV400.refreshAsync(this); } catch (Throwable ignored) {}

        // COPILOT_BACKGROUND_V1 evolved in V411: the service may listen while the app is visible,
        // but is explicitly stopped as soon as the whole app goes to background.
        try { copilotOverlayController = CopilotOverlayController.install(this); } catch (Throwable ignored) {}

        // ANDROID15_SAFE_INSETS_V251: Android 15+ edge-to-edge requires safe system-bar/cutout insets.
        SystemBarsCompatV251.register(this);

        // ROAD_SCREEN_AWAKE_V303: only the Estrada/navigation screen keeps the display awake.
        RoadScreenAwakeV303.install(this);
        RoadProtectionStatusUiV303.install(this);
        RoadRouteWeatherBridgeV303.install(this);

        // DRIVE_QUALITY_V330: one receiver feeds stable ETA and short tunnel continuity from the
        // already existing road-state stream. It does not create another GPS listener.
        try { driveRuntimeEnhancer = DriveRuntimeEnhancer.install(this); } catch (Throwable ignored) {}

        // SESSION_VALIDITY_GUARD_V300: saved accounts still open instantly/offline, but an explicit
        // server-side 401/403 revocation is applied in background when validated internet exists.
        SessionValidityGuardV300.register(this);

        // ROUTE_DIRECTION_V320: route cancellation and sustained reverse-direction detection are core.
        try { routeNavigationAssist = RouteNavigationAssist.install(this); } catch (Throwable ignored) {}

        try { MusicLibraryIntegrityV247.repair(this); } catch (Throwable ignored) {}

        boolean deferOptionalBridges = ProcessCrashGuard.install(this);
        if (deferOptionalBridges) return;

        new Thread(() -> {
            try { SharedMusicPublisher.publishMissingFromApp(EstradaPlayApplication.this); }
            catch (Throwable ignored) {}
        }, "epc-shared-music").start();

        try {
            PhoneMp3Store.installObserver(this);
            if (PhoneMp3Store.hasPermission(this)) {
                PhoneMp3Index index = PhoneMp3Index.get(this);
                long age = System.currentTimeMillis() - index.updatedAt();
                if (!index.isReady() || age > 6L * 60L * 60L * 1000L) index.markDirty();
            }
        } catch (Throwable ignored) {}

        ConvoyIntegrationV237.install(this);

        IntentFilter roadState = new IntentFilter(RoadSafetyService.ACTION_STATE);

        try {
            MobilityModeState.restore(this);
            mobilityReceiver = new MobilityModeState.Receiver();
            InternalBroadcasts.register(this, mobilityReceiver, roadState);
        } catch (Throwable ignored) {
            try { if (mobilityReceiver != null) unregisterReceiver(mobilityReceiver); } catch (Throwable ignored2) {}
            mobilityReceiver = null;
        }

        try {
            contextReceiver = new RouteContextV7BackgroundReceiver();
            InternalBroadcasts.register(this, contextReceiver, roadState);
        } catch (Throwable ignored) {
            try { if (contextReceiver != null) unregisterReceiver(contextReceiver); } catch (Throwable ignored2) {}
            contextReceiver = null;
        }

        try {
            convoyReceiver = new ConvoyLiveBridge();
            InternalBroadcasts.register(this, convoyReceiver, roadState);
        } catch (Throwable ignored) {
            try { if (convoyReceiver != null) unregisterReceiver(convoyReceiver); } catch (Throwable ignored2) {}
            convoyReceiver = null;
        }
    }

    private void installForegroundOnlyRoadSession() {
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) {}

            @Override public void onActivityStarted(Activity activity) {
                boolean returningToForeground = visibleActivities == 0;
                visibleActivities++;
                lastVisibleActivity = activity;
                foregroundHandler.removeCallbacks(stopForegroundOnlyServices);
                if (returningToForeground) resumeForegroundServices(activity);
            }

            @Override public void onActivityResumed(Activity activity) {
                lastVisibleActivity = activity;
            }

            @Override public void onActivityPaused(Activity activity) {}

            @Override public void onActivityStopped(Activity activity) {
                visibleActivities = Math.max(0, visibleActivities - 1);
                if (visibleActivities == 0) {
                    // Activity transitions and rotation can briefly report zero. Wait before stopping.
                    foregroundHandler.removeCallbacks(stopForegroundOnlyServices);
                    foregroundHandler.postDelayed(stopForegroundOnlyServices, 900L);
                }
            }

            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

            @Override public void onActivityDestroyed(Activity activity) {
                if (lastVisibleActivity == activity) lastVisibleActivity = null;
            }
        });
    }

    private void resumeForegroundServices(Activity activity) {
        if (activity == null || !roadProtectionEligible()) return;
        try {
            Intent safety = new Intent(this, RoadSafetyService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(safety);
            else startService(safety);
        } catch (Throwable ignored) {}

        // Wake word follows the same visible-app rule. It is only re-armed when the user had enabled it.
        try {
            if (CopilotSettings.enabled(this)
                    && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                CopilotService.requestStart(activity);
            }
        } catch (Throwable ignored) {}
    }

    private boolean roadProtectionEligible() {
        try {
            boolean location = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            if (!location) return false;
            SharedPreferences p = getSharedPreferences(UI_PREFS, MODE_PRIVATE);
            JSONObject account = new JSONObject(p.getString(KEY_ACCOUNT, "{}"));
            return account.optBoolean("authenticated", false) || account.optJSONObject("user") != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isRadioProcess() {
        try {
            String name;
            if (Build.VERSION.SDK_INT >= 28) {
                name = Application.getProcessName();
            } else {
                name = null;
                int pid = android.os.Process.myPid();
                ActivityManager am = (ActivityManager)getSystemService(Context.ACTIVITY_SERVICE);
                List<ActivityManager.RunningAppProcessInfo> processes = am == null ? null : am.getRunningAppProcesses();
                if (processes != null) {
                    for (ActivityManager.RunningAppProcessInfo p : processes) {
                        if (p != null && p.pid == pid) { name = p.processName; break; }
                    }
                }
            }
            return name != null && name.endsWith(":radio");
        } catch (Throwable ignored) {
            return false;
        }
    }
}

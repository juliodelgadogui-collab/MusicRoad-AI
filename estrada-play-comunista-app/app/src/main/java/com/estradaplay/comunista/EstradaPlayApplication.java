package com.estradaplay.comunista;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Build;

import java.util.List;

/** Process-level bridges for Server 7.0 context and live convoy presence. */
public final class EstradaPlayApplication extends Application {
    private RouteContextV7BackgroundReceiver contextReceiver;
    private ConvoyLiveBridge convoyReceiver;
    private MobilityModeState.Receiver mobilityReceiver;
    private RouteNavigationAssist routeNavigationAssist;
    private DriveRuntimeEnhancer driveRuntimeEnhancer;
    private CopilotOverlayController copilotOverlayController;

    @Override public void onCreate() {
        super.onCreate();

        // PTT_STABILITY_V234: RoadRadioService owns an isolated :radio process. Do not duplicate
        // Contexto Vivo, Comboio or the main crash-loop guard there; native audio failure must stay local.
        if (isRadioProcess()) return;

        UiVersionLabelFix.register(this);

        // COPILOT_BACKGROUND_V1: the visible Activity only hosts a tiny overlay. The microphone and
        // command engine live in CopilotService and are re-armed when an enabled user returns to the app.
        try { copilotOverlayController = CopilotOverlayController.install(this); } catch (Throwable ignored) {}

        // ANDROID15_SAFE_INSETS_V251: Android 15+ edge-to-edge requires safe system-bar/cutout insets.
        SystemBarsCompatV251.register(this);

        // ROAD_SCREEN_AWAKE_V303: only the Estrada/navigation screen keeps the display awake.
        RoadScreenAwakeV303.install(this);
        RoadProtectionStatusUiV303.install(this);
        RoadRouteWeatherBridgeV303.install(this);

        // ROAD_REFERENCE_UI_V360: validated against real-device screenshots. It never hides mapPane,
        // removes legacy duplicates and sizes the cockpit from actual viewport proportions.
        RoadMapReferenceUiV360.install(this);

        // PRODUCTION_ROAD_GUARD_V400: keeps a rebuilt MapLibre view in the resumed lifecycle, prevents
        // accidental map hiding, restores one centered hazard surface and verifies regional coverage.
        try { RoadProductionGuardV400.install(this); } catch (Throwable ignored) {}

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
            if (Build.VERSION.SDK_INT >= 33) {
                InternalBroadcasts.register(this, mobilityReceiver, roadState);
            } else {
                InternalBroadcasts.register(this, mobilityReceiver, roadState);
            }
        } catch (Throwable ignored) {
            try { if (mobilityReceiver != null) unregisterReceiver(mobilityReceiver); } catch (Throwable ignored2) {}
            mobilityReceiver = null;
        }

        try {
            contextReceiver = new RouteContextV7BackgroundReceiver();
            if (Build.VERSION.SDK_INT >= 33) {
                InternalBroadcasts.register(this, contextReceiver, roadState);
            } else {
                InternalBroadcasts.register(this, contextReceiver, roadState);
            }
        } catch (Throwable ignored) {
            try { if (contextReceiver != null) unregisterReceiver(contextReceiver); } catch (Throwable ignored2) {}
            contextReceiver = null;
        }

        try {
            convoyReceiver = new ConvoyLiveBridge();
            if (Build.VERSION.SDK_INT >= 33) {
                InternalBroadcasts.register(this, convoyReceiver, roadState);
            } else {
                InternalBroadcasts.register(this, convoyReceiver, roadState);
            }
        } catch (Throwable ignored) {
            try { if (convoyReceiver != null) unregisterReceiver(convoyReceiver); } catch (Throwable ignored2) {}
            convoyReceiver = null;
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

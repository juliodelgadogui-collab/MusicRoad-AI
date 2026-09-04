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

    @Override public void onCreate() {
        super.onCreate();

        // PTT_STABILITY_V234: RoadRadioService owns an isolated :radio process. Do not duplicate
        // Contexto Vivo, Comboio or the main crash-loop guard there; native audio failure must stay local.
        if (isRadioProcess()) return;

        // PATRIOTA_EDITION_V1: presentation-only layer. It does not modify radio/PTT services.
        PatriotaBranding.install(this);

        UiVersionLabelFix.register(this);
        boolean deferOptionalBridges = ProcessCrashGuard.install(this);
        if (deferOptionalBridges) return;

        // COMBOIO_LINK_MAP_V237: deep links + members projected onto the principal RoadMapActivity.
        ConvoyIntegrationV237.install(this);

        IntentFilter roadState = new IntentFilter(RoadSafetyService.ACTION_STATE);

        try {
            contextReceiver = new RouteContextV7BackgroundReceiver();
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(contextReceiver, roadState, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(contextReceiver, roadState);
            }
        } catch (Throwable ignored) {
            try { if (contextReceiver != null) unregisterReceiver(contextReceiver); } catch (Throwable ignored2) {}
            contextReceiver = null;
        }

        try {
            convoyReceiver = new ConvoyLiveBridge();
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(convoyReceiver, roadState, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(convoyReceiver, roadState);
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

package com.estradaplay.comunista;

import android.app.Application;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Build;

/** Process-level bridges for Server 7.0 context and live convoy presence. */
public final class EstradaPlayApplication extends Application {
    private RouteContextV7BackgroundReceiver contextReceiver;
    private ConvoyLiveBridge convoyReceiver;

    @Override public void onCreate() {
        super.onCreate();

        UiVersionLabelFix.register(this);
        boolean deferOptionalBridges = ProcessCrashGuard.install(this);
        if (deferOptionalBridges) return;

        IntentFilter roadState = new IntentFilter(RoadSafetyService.ACTION_STATE);

        // STABILITY_V232: register optional bridges independently. One bridge can fail without
        // taking the other one, the Application, or the local road-safety core down with it.
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
}

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
        IntentFilter roadState = new IntentFilter(RoadSafetyService.ACTION_STATE);
        contextReceiver = new RouteContextV7BackgroundReceiver();
        convoyReceiver = new ConvoyLiveBridge();
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(contextReceiver, roadState, Context.RECEIVER_NOT_EXPORTED);
                registerReceiver(convoyReceiver, roadState, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(contextReceiver, roadState);
                registerReceiver(convoyReceiver, roadState);
            }
        } catch (Throwable ignored) {
            try { unregisterReceiver(contextReceiver); } catch (Throwable ignored2) {}
            try { unregisterReceiver(convoyReceiver); } catch (Throwable ignored2) {}
            contextReceiver = null;
            convoyReceiver = null;
        }
    }
}

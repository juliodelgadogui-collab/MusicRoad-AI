package com.estradaplay.comunista;

import android.app.Application;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Build;

/** CONTEXTO_VIVO_V220: process-level bridge registration while road safety is running. */
public final class EstradaPlayApplication extends Application {
    private RouteContextV7BackgroundReceiver contextReceiver;

    @Override public void onCreate() {
        super.onCreate();
        contextReceiver = new RouteContextV7BackgroundReceiver();
        IntentFilter filter = new IntentFilter(RoadSafetyService.ACTION_STATE);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(contextReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(contextReceiver, filter);
            }
        } catch (Throwable ignored) {
            contextReceiver = null;
        }
    }
}

package com.estradaplay.comunista;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.WindowManager;

/** Keeps only the main Estrada/navigation screen awake while it is actually visible. */
final class RoadScreenAwakeV303 implements Application.ActivityLifecycleCallbacks {
    private RoadScreenAwakeV303() {}

    static void install(Application app) {
        if (app == null) return;
        try { app.registerActivityLifecycleCallbacks(new RoadScreenAwakeV303()); }
        catch (Throwable ignored) {}
    }

    private static boolean isRoad(Activity a) { return a instanceof RoadMapActivity; }

    @Override public void onActivityResumed(Activity activity) {
        if (!isRoad(activity)) return;
        try { activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); }
        catch (Throwable ignored) {}
    }

    @Override public void onActivityPaused(Activity activity) {
        if (!isRoad(activity)) return;
        try { activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); }
        catch (Throwable ignored) {}
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) {}
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
    @Override public void onActivityDestroyed(Activity activity) {}
}
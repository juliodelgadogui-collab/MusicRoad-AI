package com.estradaplay.comunista;

import android.app.Activity;
import android.app.Application;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;

/**
 * ANDROID15_SAFE_INSETS_V251
 *
 * Android 15 enforces edge-to-edge for apps targeting API 35. Estrada Play uses
 * custom Views instead of Material containers, so the activity content area must
 * explicitly stay clear of status/navigation/caption bars and display cutouts.
 *
 * This is deliberately API-35-only: Android 14 and older already place this app's
 * content inside the system bars, so adding the same padding there would double it.
 */
final class SystemBarsCompatV251 {
    private SystemBarsCompatV251() {}

    static void register(Application app) {
        if (app == null || Build.VERSION.SDK_INT < 35) return;
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) {
                if (activity == null) return;
                View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
                if (decor == null) return;
                decor.post(() -> apply(activity));
            }

            @Override public void onActivityStarted(Activity activity) {}
            @Override public void onActivityResumed(Activity activity) {}
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });
    }

    private static void apply(Activity activity) {
        if (Build.VERSION.SDK_INT < 35 || activity == null || activity.isFinishing()) return;
        View content = activity.findViewById(android.R.id.content);
        if (content == null) return;

        final int baseLeft = content.getPaddingLeft();
        final int baseTop = content.getPaddingTop();
        final int baseRight = content.getPaddingRight();
        final int baseBottom = content.getPaddingBottom();

        content.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            if (windowInsets == null) return null;
            Insets safe = windowInsets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(
                    baseLeft + safe.left,
                    baseTop + safe.top,
                    baseRight + safe.right,
                    baseBottom + safe.bottom);
            return windowInsets;
        });
        content.requestApplyInsets();
    }
}

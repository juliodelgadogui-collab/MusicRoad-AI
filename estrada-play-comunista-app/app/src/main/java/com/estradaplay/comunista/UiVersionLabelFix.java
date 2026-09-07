package com.estradaplay.comunista;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** Keeps legacy visual labels synchronized with the actual APK version without retaining Activities. */
final class UiVersionLabelFix {
    private static final long[] RETRIES_MS = new long[]{0L, 120L, 350L, 900L, 1800L};

    private UiVersionLabelFix() {}

    static void register(Application app) {
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity activity) {
                try {
                    View root = activity.getWindow().getDecorView();
                    if (root == null) return;
                    // A bounded retry set is enough for views built asynchronously and, unlike the old
                    // global-layout listener, does not accumulate callbacks every time a screen resumes.
                    for (long delay : RETRIES_MS) root.postDelayed(() -> {
                        if (root.isAttachedToWindow()) apply(root);
                    }, delay);
                } catch (Throwable ignored) {}
            }
            @Override public void onActivityCreated(Activity activity, Bundle state) {}
            @Override public void onActivityStarted(Activity activity) {}
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });
    }

    private static void apply(View view) {
        if (view == null) return;
        if (view instanceof TextView) {
            TextView text = (TextView)view;
            CharSequence raw = text.getText();
            if (raw != null) {
                String value = raw.toString().trim();
                if (value.startsWith("EPC ") && value.contains("CENTRAL AUTOMOTIVA")
                        && !value.contains(BuildConfig.VERSION_NAME)) {
                    text.setText("EPC " + BuildConfig.VERSION_NAME + "  ·  CENTRAL AUTOMOTIVA");
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) apply(group.getChildAt(i));
        }
    }
}

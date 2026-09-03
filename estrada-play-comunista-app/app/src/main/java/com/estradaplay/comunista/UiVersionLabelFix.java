package com.estradaplay.comunista;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;

/** Keeps legacy visual labels synchronized with the actual APK version. */
final class UiVersionLabelFix {
    private UiVersionLabelFix() {}

    static void register(Application app) {
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity activity) {
                try {
                    View root = activity.getWindow().getDecorView();
                    if (root == null) return;
                    root.post(() -> apply(root));
                    // MainActivity can rebuild its entire native hierarchy after an asynchronous login.
                    // Watching layout keeps the visible version correct without coupling auth code to UI code.
                    ViewTreeObserver observer = root.getViewTreeObserver();
                    if (observer.isAlive()) observer.addOnGlobalLayoutListener(() -> apply(root));
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
                String value = raw.toString();
                if ("EPC 2.0  ·  CENTRAL AUTOMOTIVA".equals(value)) {
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

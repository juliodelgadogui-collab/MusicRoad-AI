package com.estradaplay.comunista;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * Keeps business-critical legacy screens usable while presenting the new Estrada Play brand.
 * This changes display text only; package names, API contracts and stored data remain untouched.
 */
final class PremiumBrandTextFix {
    private PremiumBrandTextFix() {}

    static void register(Application app) {
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity activity) {
                try {
                    View root = activity.getWindow().getDecorView();
                    if (root == null) return;
                    root.post(() -> apply(root));
                    root.postDelayed(() -> apply(root), 180L);
                    root.postDelayed(() -> apply(root), 700L);
                } catch (Throwable ignored) {}
            }
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }

    private static void apply(View view) {
        if (view == null) return;
        if (view instanceof TextView) {
            TextView text = (TextView)view;
            CharSequence raw = text.getText();
            if (raw != null) {
                String original = raw.toString();
                String value = original
                        .replace("ESTRADA PLAY COMUNISTA", "ESTRADA PLAY")
                        .replace("Estrada Play Comunista", "Estrada Play")
                        .replace("EstradaPlay Comunista", "Estrada Play")
                        .replace("EstradaPlay Comunista Universal", "Estrada Play");
                if (!value.equals(original)) text.setText(value);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i=0;i<group.getChildCount();i++) apply(group.getChildAt(i));
        }
    }
}

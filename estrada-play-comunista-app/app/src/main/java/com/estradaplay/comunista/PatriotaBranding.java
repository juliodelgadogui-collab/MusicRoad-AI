package com.estradaplay.comunista;

import android.app.Activity;
import android.app.Application;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.WeakHashMap;

/**
 * Branch-local presentation layer for Estrada Play Patriota.
 *
 * The functional source remains shared with Estrada Play Comunista. This layer only replaces
 * legacy edition labels and known brand colors at view level. Navigation, server, Comboio and
 * PTT/audio classes are deliberately untouched.
 */
final class PatriotaBranding {
    private static final int BRAND_GREEN = Color.rgb(0, 156, 59);
    private static final int BRAND_GREEN_DARK = Color.rgb(4, 70, 35);
    private static final int WINDOW_DARK = Color.rgb(6, 20, 13);
    private static final WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener> LISTENERS = new WeakHashMap<>();

    private PatriotaBranding() {}

    static void install(Application app) {
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) { attach(activity); }
            @Override public void onActivityStarted(Activity activity) { apply(activity); }
            @Override public void onActivityResumed(Activity activity) { apply(activity); }
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
            @Override public void onActivityDestroyed(Activity activity) { detach(activity); }
        });
    }

    private static void attach(Activity activity) {
        if (activity == null || LISTENERS.containsKey(activity)) return;
        View root = activity.getWindow().getDecorView();
        ViewTreeObserver.OnGlobalLayoutListener listener = () -> apply(activity);
        LISTENERS.put(activity, listener);
        root.getViewTreeObserver().addOnGlobalLayoutListener(listener);
        root.post(() -> apply(activity));
    }

    private static void detach(Activity activity) {
        if (activity == null) return;
        ViewTreeObserver.OnGlobalLayoutListener listener = LISTENERS.remove(activity);
        View root = activity.getWindow().getDecorView();
        if (listener != null && root.getViewTreeObserver().isAlive()) {
            root.getViewTreeObserver().removeOnGlobalLayoutListener(listener);
        }
    }

    private static void apply(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        activity.setTitle("Estrada Play Patriota");
        activity.getWindow().setStatusBarColor(WINDOW_DARK);
        activity.getWindow().setNavigationBarColor(WINDOW_DARK);
        View root = activity.getWindow().getDecorView();
        if (root != null) applyView(root);
    }

    private static void applyView(View view) {
        if (view == null) return;

        if (view instanceof TextView) {
            TextView textView = (TextView)view;
            CharSequence before = textView.getText();
            String after = rebrand(before == null ? "" : before.toString());
            if (before != null && !after.contentEquals(before)) textView.setText(after);

            int current = textView.getCurrentTextColor();
            int mapped = mapColor(current);
            if (mapped != current) textView.setTextColor(mapped);
        }

        CharSequence description = view.getContentDescription();
        if (description != null) {
            String after = rebrand(description.toString());
            if (!after.contentEquals(description)) view.setContentDescription(after);
        }

        recolorBackground(view.getBackground());

        if (view instanceof ProgressBar) {
            ProgressBar progress = (ProgressBar)view;
            ColorStateList tint = ColorStateList.valueOf(BRAND_GREEN);
            progress.setIndeterminateTintList(tint);
            progress.setProgressTintList(tint);
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) applyView(group.getChildAt(i));
        }
    }

    private static void recolorBackground(Drawable drawable) {
        if (drawable instanceof ColorDrawable) {
            ColorDrawable color = (ColorDrawable)drawable;
            int old = color.getColor();
            int mapped = mapColor(old);
            if (mapped != old) color.setColor(mapped);
            return;
        }
        if (drawable instanceof GradientDrawable) {
            GradientDrawable gradient = (GradientDrawable)drawable;
            ColorStateList colors = gradient.getColor();
            if (colors != null) {
                int old = colors.getDefaultColor();
                int mapped = mapColor(old);
                if (mapped != old) gradient.setColor(mapped);
            }
        }
    }

    private static String rebrand(String value) {
        if (value == null || value.isEmpty()) return value == null ? "" : value;
        String out = value
                .replace("Estrada Play Comunista", "Estrada Play Patriota")
                .replace("ESTRADA PLAY COMUNISTA", "ESTRADA PLAY PATRIOTA")
                .replace("Comunista", "Patriota")
                .replace("COMUNISTA", "PATRIOTA");
        if ("EPC".equals(out)) return "EPP";
        return out;
    }

    private static int mapColor(int color) {
        if (color == Color.rgb(190, 18, 38)) return BRAND_GREEN;
        if (color == Color.rgb(79, 10, 23)) return BRAND_GREEN_DARK;
        if (color == Color.rgb(9, 5, 7)) return WINDOW_DARK;
        if (color == Color.rgb(20, 10, 13)) return Color.rgb(9, 31, 21);
        if (color == Color.rgb(30, 15, 19)) return Color.rgb(13, 43, 30);
        if (color == Color.rgb(42, 20, 25)) return Color.rgb(18, 56, 38);
        if (color == Color.rgb(76, 38, 43)) return Color.rgb(45, 91, 68);
        if (color == Color.rgb(121, 91, 91)) return Color.rgb(111, 142, 122);
        return color;
    }
}

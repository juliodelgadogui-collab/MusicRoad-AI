package com.estradaplay.comunista;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

/**
 * Visible-brand compatibility layer for legacy native screens.
 *
 * It intentionally changes UI text only. Package names, preferences, server contracts,
 * database fields and deep links keep their historic identifiers for compatibility.
 */
final class PremiumBrandTextFix {
    private static final Pattern FULL_BRAND = Pattern.compile(
            "(?i)\\bestrada\\s*play\\s*(?:[-·|:]\\s*)?comunista(?:\\s*universal)?\\b");
    private static final Pattern COMPACT_BRAND = Pattern.compile(
            "(?i)\\bestradaplay\\s*(?:[-·|:]\\s*)?comunista(?:\\s*universal)?\\b");
    private static final Pattern ESTRADA_PLAY_COMPACT = Pattern.compile("(?i)\\bestradaplay\\b");
    private static final Pattern LEGACY_WORD = Pattern.compile("(?i)\\bcomunista\\b");
    private static final Pattern LEGACY_ACRONYM = Pattern.compile("(?i)(?<![\\p{L}\\p{N}])EPC(?![\\p{L}\\p{N}])");

    private static final Map<Activity, Watch> WATCHES = new WeakHashMap<>();

    private PremiumBrandTextFix() {}

    static void register(Application app) {
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) { attach(activity); }
            @Override public void onActivityStarted(Activity activity) { attach(activity); }
            @Override public void onActivityResumed(Activity activity) { attach(activity); }
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
            @Override public void onActivityDestroyed(Activity activity) { detach(activity); }
        });
    }

    private static void attach(Activity activity) {
        if (activity == null || activity.getWindow() == null) return;
        try {
            View root = activity.getWindow().getDecorView();
            if (root == null) return;

            Watch current;
            synchronized (WATCHES) { current = WATCHES.get(activity); }
            if (current != null && current.root == root) {
                schedule(activity, current);
                return;
            }
            if (current != null) detach(activity);

            final Watch watch = new Watch(root);
            watch.listener = () -> schedule(activity, watch);
            ViewTreeObserver observer = root.getViewTreeObserver();
            if (observer.isAlive()) observer.addOnGlobalLayoutListener(watch.listener);
            synchronized (WATCHES) { WATCHES.put(activity, watch); }
            schedule(activity, watch);
        } catch (Throwable ignored) {}
    }

    private static void schedule(Activity activity, Watch watch) {
        if (watch == null || watch.root == null || watch.pending) return;
        watch.pending = true;
        watch.root.post(() -> {
            watch.pending = false;
            try {
                apply(watch.root);
                CharSequence title = activity == null ? null : activity.getTitle();
                String cleanTitle = sanitize(title);
                if (activity != null && title != null && !cleanTitle.contentEquals(title)) {
                    activity.setTitle(cleanTitle);
                }
            } catch (Throwable ignored) {}
        });
    }

    private static void detach(Activity activity) {
        Watch watch;
        synchronized (WATCHES) { watch = WATCHES.remove(activity); }
        if (watch == null || watch.root == null || watch.listener == null) return;
        try {
            ViewTreeObserver observer = watch.root.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnGlobalLayoutListener(watch.listener);
        } catch (Throwable ignored) {}
    }

    private static void apply(View view) {
        if (view == null) return;

        CharSequence description = view.getContentDescription();
        if (description != null) {
            String cleaned = sanitize(description);
            if (!cleaned.contentEquals(description)) view.setContentDescription(cleaned);
        }

        if (view instanceof TextView) {
            TextView text = (TextView)view;
            CharSequence raw = text.getText();
            if (raw != null) {
                String cleaned = sanitize(raw);
                if (!cleaned.contentEquals(raw)) text.setText(cleaned);
            }
            CharSequence hint = text.getHint();
            if (hint != null) {
                String cleanedHint = sanitize(hint);
                if (!cleanedHint.contentEquals(hint)) text.setHint(cleanedHint);
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) apply(group.getChildAt(i));
        }
    }

    /** Package-private for regression tests. */
    static String sanitize(CharSequence raw) {
        if (raw == null) return "";
        String value = raw.toString();
        value = FULL_BRAND.matcher(value).replaceAll("Estrada Play");
        value = COMPACT_BRAND.matcher(value).replaceAll("Estrada Play");
        value = ESTRADA_PLAY_COMPACT.matcher(value).replaceAll("Estrada Play");
        // The 5.x product no longer exposes the old ideological qualifier anywhere in user-facing UI.
        value = LEGACY_WORD.matcher(value).replaceAll("");
        value = LEGACY_ACRONYM.matcher(value).replaceAll("EP");
        value = value.replaceAll("[ \\t]{2,}", " ")
                .replaceAll("[ \\t]+([,;:·])", "$1")
                .replaceAll("([·|:-])[ \\t]*$", "")
                .trim();
        return value;
    }

    private static final class Watch {
        final View root;
        ViewTreeObserver.OnGlobalLayoutListener listener;
        boolean pending;
        Watch(View root) { this.root = root; }
    }
}

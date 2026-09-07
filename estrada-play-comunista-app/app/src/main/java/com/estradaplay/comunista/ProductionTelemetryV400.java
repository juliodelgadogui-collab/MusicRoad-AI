package com.estradaplay.comunista;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Privacy-minimal crash/ANR telemetry. It never sends GPS coordinates, audio or user content.
 * Events are persisted locally first and uploaded only to the configured Estrada Play HTTPS server.
 */
final class ProductionTelemetryV400 implements Application.ActivityLifecycleCallbacks {
    private static final String PREFS = "epc_telemetry_v400";
    private static final String KEY_QUEUE = "queue";
    private static final int MAX_EVENTS = 12;
    private static final int MAX_STACK = 12000;
    private static final long ANR_LIMIT_MS = 8500L;

    private final Application app;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicLong heartbeat = new AtomicLong(System.currentTimeMillis());
    private volatile String activity = "";
    private volatile boolean foreground;
    private volatile boolean stopped;
    private Thread.UncaughtExceptionHandler previous;

    static ProductionTelemetryV400 install(Application app) {
        ProductionTelemetryV400 t = new ProductionTelemetryV400(app);
        app.registerActivityLifecycleCallbacks(t);
        t.installCrashHandler();
        t.startAnrWatchdog();
        t.flushAsync();
        return t;
    }

    private ProductionTelemetryV400(Application app) { this.app = app; }

    private void installCrashHandler() {
        previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try { enqueue("crash", error, thread == null ? "" : thread.getName()); }
            catch (Throwable ignored) {}
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    private void startAnrWatchdog() {
        Thread watchdog = new Thread(() -> {
            long reportedFor = 0L;
            while (!stopped) {
                try { Thread.sleep(2500L); } catch (InterruptedException ignored) { return; }
                if (!foreground) continue;
                long sent = System.currentTimeMillis();
                main.post(() -> heartbeat.set(System.currentTimeMillis()));
                try { Thread.sleep(ANR_LIMIT_MS); } catch (InterruptedException ignored) { return; }
                long beat = heartbeat.get();
                if (beat < sent && sent != reportedFor) {
                    reportedFor = sent;
                    enqueue("anr", null, "main-thread-stall");
                }
            }
        }, "epc-anr-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private synchronized void enqueue(String kind, Throwable error, String thread) {
        try {
            JSONArray queue = queue();
            JSONObject event = new JSONObject();
            event.put("kind", kind);
            event.put("at", System.currentTimeMillis());
            event.put("activity", activity);
            event.put("thread", thread == null ? "" : thread);
            event.put("app_version", BuildConfig.VERSION_NAME);
            event.put("sdk", Build.VERSION.SDK_INT);
            event.put("manufacturer", safe(Build.MANUFACTURER, 80));
            event.put("model", safe(Build.MODEL, 100));
            if (error != null) {
                event.put("exception", safe(error.getClass().getName(), 200));
                event.put("message", safe(error.getMessage(), 600));
                StringWriter sw = new StringWriter();
                error.printStackTrace(new PrintWriter(sw));
                event.put("stack", safe(sw.toString(), MAX_STACK));
            }
            while (queue.length() >= MAX_EVENTS) {
                JSONArray trimmed = new JSONArray();
                for (int i = 1; i < queue.length(); i++) trimmed.put(queue.opt(i));
                queue = trimmed;
            }
            queue.put(event);
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_QUEUE, queue.toString()).commit();
        } catch (Throwable ignored) {}
    }

    private JSONArray queue() {
        try { return new JSONArray(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_QUEUE, "[]")); }
        catch (Throwable ignored) { return new JSONArray(); }
    }

    private void flushAsync() {
        io.execute(() -> {
            JSONArray batch;
            synchronized (this) { batch = queue(); }
            if (batch.length() == 0) return;
            try {
                JSONObject payload = new JSONObject();
                payload.put("events", batch);
                ApiClient.Response r = new ApiClient(app).post("api/client_telemetry.php", payload);
                if (r.ok() && r.json().optBoolean("ok", false)) {
                    app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_QUEUE).commit();
                }
            } catch (Throwable ignored) {}
        });
    }

    private static String safe(String value, int max) {
        String v = value == null ? "" : value;
        return v.length() <= max ? v : v.substring(0, max);
    }

    @Override public void onActivityResumed(Activity a) {
        activity = a == null ? "" : a.getClass().getSimpleName();
        foreground = true;
        heartbeat.set(System.currentTimeMillis());
        flushAsync();
    }
    @Override public void onActivityPaused(Activity a) { foreground = false; }
    @Override public void onActivityDestroyed(Activity a) {}
    @Override public void onActivityCreated(Activity a, Bundle b) {}
    @Override public void onActivityStarted(Activity a) {}
    @Override public void onActivityStopped(Activity a) {}
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
}

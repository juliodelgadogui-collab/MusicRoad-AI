package com.estradaplay.comunista;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SESSION_VALIDITY_GUARD_V300
 * Keeps the offline-first startup, but verifies an already-saved account in the background.
 * Network failures never log the driver out; only an explicit 401/403 revocation does.
 */
final class SessionValidityGuardV300 implements Application.ActivityLifecycleCallbacks {
    private static final String UI_PREFS = "estradaplay_ui_v1";
    private static final String KEY_ACCOUNT = "account";
    private static final long MIN_CHECK_INTERVAL_MS = 5L * 60L * 1000L;

    private final Application app;
    private final SharedPreferences uiPrefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean checking = new AtomicBoolean(false);
    private volatile long lastCheckAt;
    private volatile WeakReference<Activity> resumed = new WeakReference<>(null);

    private SessionValidityGuardV300(Application app) {
        this.app = app;
        this.uiPrefs = app.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE);
    }

    static void register(Application app) {
        if (app == null) return;
        app.registerActivityLifecycleCallbacks(new SessionValidityGuardV300(app));
    }

    @Override public void onActivityResumed(Activity activity) {
        resumed = new WeakReference<>(activity);
        scheduleValidation();
    }

    private void scheduleValidation() {
        if (!hasLocalAccount() || !online()) return;
        long now = System.currentTimeMillis();
        if (now - lastCheckAt < MIN_CHECK_INTERVAL_MS || !checking.compareAndSet(false, true)) return;
        lastCheckAt = now;
        io.execute(() -> {
            try {
                // SESSION_ACCOUNT_SWITCH_GUARD_V300: capture the exact local account and auth
                // generation immediately before the request. A late response from account A
                // cannot overwrite/revoke account B or a newer login of the same account.
                String accountAtStart = localAccountSnapshot();
                if (accountAtStart.isEmpty()) return;
                String sessionAtStart = SessionValidationClientV300.sessionFingerprint(app);
                if (sessionAtStart.isEmpty()) return;

                JSONObject payload = new JSONObject();
                payload.put("device_token", DeviceIdentity.token(app));
                payload.put("device_label", DeviceIdentity.label());
                payload.put("app_version", BuildConfig.VERSION_NAME);

                // Read-only transport deliberately does not capture Set-Cookie/auth or refresh.
                ApiClient.Response response = SessionValidationClientV300.validate(app, payload);
                if (!validationStillCurrent(accountAtStart, sessionAtStart)) return;

                JSONObject body = response.json();
                JSONObject freshAccount = body.optJSONObject("account");
                if (response.ok() && body.optBoolean("ok", false) && freshAccount != null) {
                    uiPrefs.edit().putString(KEY_ACCOUNT, freshAccount.toString()).apply();
                } else if (SessionValidityPolicyV300.isExplicitRevocation(response.code)) {
                    invalidateLocalSession();
                }
            } catch (Exception ignored) {
                // Offline-first invariant: timeout, DNS, TLS or any transient network failure keeps local access.
            } finally {
                checking.set(false);
            }
        });
    }

    private boolean validationStillCurrent(String accountAtStart, String sessionAtStart) {
        return SessionValidityPolicyV300.sameValidationSubject(
                accountAtStart,
                localAccountSnapshot(),
                sessionAtStart,
                SessionValidationClientV300.sessionFingerprint(app));
    }

    private void invalidateLocalSession() {
        try { new ApiClient(app).clearSession(); } catch (Throwable ignored) {}
        uiPrefs.edit().remove(KEY_ACCOUNT).commit();
        main.post(() -> {
            Activity current = resumed.get();
            if (current == null || current.isFinishing() || current.isDestroyed()) return;
            Intent restart = new Intent(app, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            app.startActivity(restart);
        });
    }

    private boolean hasLocalAccount() {
        return !localAccountSnapshot().isEmpty();
    }

    private String localAccountSnapshot() {
        String raw = uiPrefs.getString(KEY_ACCOUNT, "{}");
        if (raw == null || raw.trim().isEmpty()) return "";
        try { return new JSONObject(raw).length() > 0 ? raw.trim() : ""; }
        catch (Exception ignored) { return ""; }
    }

    private boolean online() {
        try {
            ConnectivityManager cm = (ConnectivityManager)app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null || cm.getActiveNetwork() == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return caps != null
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override public void onActivityPaused(Activity activity) {}
    @Override public void onActivityCreated(Activity activity, Bundle state) {}
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
    @Override public void onActivityDestroyed(Activity activity) {}
}

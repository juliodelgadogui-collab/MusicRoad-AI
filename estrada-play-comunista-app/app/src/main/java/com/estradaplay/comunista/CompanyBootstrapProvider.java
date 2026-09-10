package com.estradaplay.comunista;

import android.app.Activity;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/** Process bootstrap that makes the existing login flow company-aware without a second login screen. */
public final class CompanyBootstrapProvider extends ContentProvider {
    private static volatile boolean routedThisProcess;
    private static volatile boolean routing;

    static void markRouted() {
        routedThisProcess = true;
        routing = false;
    }

    private static void resetForAccountFlow() {
        routedThisProcess = false;
        routing = false;
    }

    @Override public boolean onCreate() {
        if (getContext() == null) return true;
        android.content.Context appContext = getContext().getApplicationContext();
        if (!(appContext instanceof Application)) return true;
        Application app = (Application) appContext;
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) {}
            @Override public void onActivityStarted(Activity activity) {}
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
            @Override public void onActivityDestroyed(Activity activity) {}

            @Override public void onActivityResumed(Activity activity) {
                // Logout/account switch can happen without killing the Android process. Re-arm the router
                // whenever the authentication flow becomes visible so the next successful login is classified again.
                if (activity instanceof GateActivity || activity instanceof PremiumAccountActivity) {
                    resetForAccountFlow();
                    return;
                }
                if (!(activity instanceof PremiumHomeActivity) || routedThisProcess || routing || activity.isFinishing()) return;
                routing = true;
                try {
                    Intent i = new Intent(activity, AccountCentralRouterActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    activity.startActivity(i);
                    activity.finish();
                } catch (Throwable ignored) {
                    routing = false;
                    routedThisProcess = true;
                }
            }
        });
        return true;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}

package com.estradaplay.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;

import androidx.activity.ComponentActivity;

import org.json.JSONObject;

/**
 * Crash-safe launcher gate for EstradaPlay 1.6.1.
 *
 * The proven RoadMapActivity is used as the automotive shell while the larger
 * cockpit remains available for later hardening. Runtime permissions continue
 * to be requested only from inside the app.
 */
public final class GateActivity extends ComponentActivity {
    private static final String UI_PREFS = "estradaplay_ui_v1";
    private static final String KEY_ACCOUNT = "account";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(5, 7, 10));
        getWindow().setNavigationBarColor(Color.rgb(5, 7, 10));

        LibraryStore library = new LibraryStore(this);
        Intent next;
        if (hasAccount() && library.hasSetupDone()) {
            // RoadMapActivity is the stable 1.5 map shell and is now used as
            // the 1.6.1 automotive entrypoint to eliminate the startup crash.
            next = new Intent(this, RoadMapActivity.class);
        } else {
            next = new Intent(this, MainActivity.class);
        }
        startActivity(next);
        finish();
    }

    private boolean hasAccount() {
        try {
            SharedPreferences p = getSharedPreferences(UI_PREFS, MODE_PRIVATE);
            JSONObject a = new JSONObject(p.getString(KEY_ACCOUNT, "{}"));
            return a.optBoolean("authenticated", false) || a.optJSONObject("user") != null;
        } catch (Throwable e) {
            return false;
        }
    }
}

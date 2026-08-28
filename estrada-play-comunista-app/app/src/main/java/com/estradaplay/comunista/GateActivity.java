package com.estradaplay.comunista;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;

import androidx.activity.ComponentActivity;

import org.json.JSONObject;

/** Crash-safe launcher gate for EstradaPlay 1.6.1. */
public final class GateActivity extends ComponentActivity {
    private static final String UI_PREFS = "estradaplay_ui_v1";
    private static final String KEY_ACCOUNT = "account";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(8, 5, 7));
        getWindow().setNavigationBarColor(Color.rgb(8, 5, 7));

        LibraryStore library = new LibraryStore(this);
        Intent next;
        // COMUNISTA_HOME_V100: destination is optional, so returning users land on
        // the choice screen instead of being forced into a route. Passive protection
        // starts as soon as the driver chooses "Dirigir sem destino".
        next = new Intent(this, MainActivity.class);
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

    private boolean hasLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
}

package com.estradaplay.comunista;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.activity.ComponentActivity;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Compatibility router only.
 *
 * Estrada Play 5.x no longer renders the legacy EPC/Comunista dashboard here. Any old code that
 * still targets MainActivity is redirected to the corresponding Premium screen.
 */
public final class MainActivity extends ComponentActivity {
    private static final String PREFS = "estradaplay_ui_v1";
    private static final String KEY_ACCOUNT = "account";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        route(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        route(intent);
    }

    private void route(Intent source) {
        String target = source == null ? "" : source.getStringExtra("open");
        target = target == null ? "" : target.trim().toLowerCase(Locale.ROOT);

        Class<?> destination;
        if ("library".equals(target) || "downloads".equals(target)) {
            destination = PremiumDownloadsActivity.class;
        } else if ("music".equals(target)) {
            destination = PremiumMusicActivity.class;
        } else if ("account".equals(target) || "login".equals(target)) {
            destination = PremiumAccountActivity.class;
        } else {
            destination = signedIn() ? PremiumHomeActivity.class : PremiumAccountActivity.class;
        }

        Intent next = new Intent(this, destination);
        next.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(next);
        finish();
    }

    private boolean signedIn() {
        try {
            SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
            JSONObject account = new JSONObject(p.getString(KEY_ACCOUNT, "{}"));
            return account.optBoolean("authenticated", false) || account.optJSONObject("user") != null;
        } catch (Throwable ignored) {
            return false;
        }
    }
}

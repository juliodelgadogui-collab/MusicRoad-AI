package com.estradaplay.patriota;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class GateActivity extends ComponentActivity {
    private static final String UI_PREFS = "estradaplay_ui_v1";
    private static final String KEY_ACCOUNT = "account";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private boolean launched;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ConvoyIntegrationV237.captureInvite(this, getIntent());
        getWindow().setStatusBarColor(Color.rgb(8, 5, 7));
        getWindow().setNavigationBarColor(Color.rgb(8, 5, 7));
        showBrandIntro();
        ui.postDelayed(this::openApp, 700L);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        ConvoyIntegrationV237.captureInvite(this, intent);
        launched = false;
        ui.removeCallbacksAndMessages(null);
        ui.postDelayed(this::openApp, 120L);
    }

    private void showBrandIntro() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(8, 5, 7));
        setContentView(root);

        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);
        center.setPadding(dp(30), dp(28), dp(30), dp(28));
        root.addView(center, new FrameLayout.LayoutParams(-1, -1));

        BrandMarkView mark = new BrandMarkView(this);
        center.addView(mark, new LinearLayout.LayoutParams(dp(104), dp(104)));

        TextView kicker = label("ESTRADA PLAY", 12, Color.rgb(224, 30, 47), true);
        kicker.setLetterSpacing(0.18f);
        center.addView(kicker, wrap());
        margins(kicker, 0, 24, 0, 3);

        TextView title = label("PATRIOTA", 31, Color.rgb(247, 245, 246), true);
        title.setLetterSpacing(0.05f);
        center.addView(title, wrap());

        TextView subtitle = label("NAVEGAÇÃO  ·  MÚSICA  ·  PROTEÇÃO", 11, Color.rgb(148, 139, 144), true);
        subtitle.setLetterSpacing(0.08f);
        center.addView(subtitle, wrap());
        margins(subtitle, 0, 11, 0, 0);

        TextView version = label("v" + BuildConfig.VERSION_NAME, 10, Color.rgb(118, 84, 77), true);
        version.setLetterSpacing(0.10f);
        center.addView(version, wrap());
        margins(version, 0, 12, 0, 0);

        View line = new View(this);
        line.setBackgroundColor(Color.rgb(224, 30, 47));
        center.addView(line, new LinearLayout.LayoutParams(dp(54), dp(3)));
        margins(line, 0, 20, 0, 0);
    }

    private void openApp() {
        if (launched || isFinishing()) return;

        // SESSION_PERSIST_V232: never translate "Keystore token could not be reopened" directly
        // into a password prompt. If a remembered account exists, first try device_login using the
        // stable random installation secret. A successful response silently mints fresh tokens.
        boolean remembered = hasRememberedAccount();
        if (!remembered || !online()) {
            launchMain();
            return;
        }

        ApiClient api = new ApiClient(this);
        if (api.hasSecureSession()) {
            launchMain();
            return;
        }

        io.execute(() -> {
            boolean authenticated = false;
            boolean definitelyRejected = false;
            try {
                JSONObject data = new JSONObject();
                data.put("device_token", DeviceIdentity.token(GateActivity.this));
                data.put("device_label", DeviceIdentity.label());
                data.put("app_version", BuildConfig.VERSION_NAME);
                ApiClient.Response response = api.post("api/native_app.php?action=device_login", data);
                JSONObject body = response.json();
                authenticated = response.ok() && body.optBoolean("ok", false)
                        && body.optJSONObject("account") != null;
                definitelyRejected = response.code == 401 || response.code == 403;
            } catch (Throwable ignored) {
                // Network failure is not an authentication failure. Keep remembered offline state.
            }

            final boolean ok = authenticated;
            final boolean rejected = definitelyRejected;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (!ok && rejected) {
                    api.clearSession();
                    getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit().remove(KEY_ACCOUNT).apply();
                }
                launchMain();
            });
        });
    }

    private void launchMain() {
        if (launched || isFinishing()) return;
        launched = true;
        Intent next = new Intent(this, MainActivity.class);
        startActivity(next);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    private boolean hasRememberedAccount() {
        SharedPreferences p = getSharedPreferences(UI_PREFS, MODE_PRIVATE);
        String raw = p.getString(KEY_ACCOUNT, "{}");
        if (raw == null || raw.trim().isEmpty() || "{}".equals(raw.trim())) return false;
        try {
            JSONObject account = new JSONObject(raw);
            return account.optBoolean("authenticated", false) || account.optJSONObject("user") != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean online() {
        try {
            ConnectivityManager cm = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            if (Build.VERSION.SDK_INT >= 23) {
                android.net.Network n = cm.getActiveNetwork();
                if (n == null) return false;
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                return c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            }
            android.net.NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        io.shutdownNow();
        super.onDestroy();
    }

    private TextView label(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(-2, -2);
    }

    private void margins(View v, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) v.getLayoutParams();
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        v.setLayoutParams(p);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

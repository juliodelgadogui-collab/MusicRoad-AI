package com.estradaplay.comunista;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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

/** Local startup gate. Configured drivers enter the new Premium Central; onboarding stays isolated. */
public final class GateActivity extends ComponentActivity {
    private static final String UI_PREFS = "estradaplay_ui_v1";
    private static final String KEY_ACCOUNT = "account";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean launched;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ConvoyIntegrationV237.captureInvite(this, getIntent());
        EstradaTheme theme = EstradaTheme.get(this);
        getWindow().setStatusBarColor(theme.background);
        getWindow().setNavigationBarColor(theme.background);
        showBrandIntro(theme);
        if (configured()) ui.postDelayed(this::launchPremium, 180L);
        else ui.postDelayed(this::launchOnboarding, 520L);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        ConvoyIntegrationV237.captureInvite(this, intent);
        launched = false;
        ui.removeCallbacksAndMessages(null);
        if (configured()) ui.postDelayed(this::launchPremium, 80L);
        else ui.postDelayed(this::launchOnboarding, 100L);
    }

    private boolean configured() {
        try {
            SharedPreferences p = getSharedPreferences(UI_PREFS, MODE_PRIVATE);
            JSONObject account = new JSONObject(p.getString(KEY_ACCOUNT, "{}"));
            boolean signed = account.optBoolean("authenticated", false) || account.optJSONObject("user") != null;
            boolean location = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            boolean setup = new LibraryStore(this).hasSetupDone();
            return signed && location && setup;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void showBrandIntro(EstradaTheme theme) {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(PremiumUi.gradient(this, theme.background,
                PremiumUi.withAlpha(theme.surfaceAlt, 255), 0));
        setContentView(root);

        LinearLayout center = PremiumUi.col(this);
        center.setGravity(Gravity.CENTER);
        center.setPadding(dp(30), dp(28), dp(30), dp(28));
        root.addView(center, new FrameLayout.LayoutParams(-1, -1));

        BrandMarkView mark = new BrandMarkView(this);
        center.addView(mark, new LinearLayout.LayoutParams(dp(98), dp(98)));

        TextView brand = PremiumUi.text(this, "ESTRADA PLAY", 29, theme.text, true);
        brand.setGravity(Gravity.CENTER);
        brand.setLetterSpacing(.07f);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2); bp.setMargins(0, dp(22),0,0); center.addView(brand,bp);

        TextView subtitle = PremiumUi.overline(this, "NAVEGAÇÃO  ·  MÚSICA  ·  PROTEÇÃO", theme.secondary);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2,-2); sp.setMargins(0,dp(8),0,0); center.addView(subtitle,sp);

        TextView version = PremiumUi.text(this, "v" + BuildConfig.VERSION_NAME, 9, theme.muted, true);
        version.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-2,-2); vp.setMargins(0,dp(12),0,0); center.addView(version,vp);

        View glow = new View(this);
        glow.setBackgroundColor(theme.primary);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(58), dp(3)); gp.setMargins(0,dp(18),0,0); center.addView(glow,gp);
    }

    private void launchPremium() {
        if (launched || isFinishing()) return;
        launched = true;
        startActivity(new Intent(this, PremiumHomeActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    private void launchOnboarding() {
        if (launched || isFinishing()) return;
        launched = true;
        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private int dp(float v) { return PremiumUi.dp(this, v); }
}

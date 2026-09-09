package com.estradaplay.comunista;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

import org.json.JSONObject;

/**
 * Single premium entry point for the road cockpit.
 *
 * The 5.x install id is new, so Android permissions from the historical package do not migrate.
 * This activity owns the permission gate and only opens road features after location is granted.
 */
public final class RoadEntryActivity extends ComponentActivity {
    public static final String EXTRA_DESTINATION = "open_destination";
    private static final int REQ_LOCATION = 5601;
    private static final String PREFS = "estradaplay_ui_v1";
    private static final String KEY_ACCOUNT = "account";

    private EstradaTheme theme;
    private boolean deniedOnce;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!hasAccount()) {
            startActivity(new Intent(this, PremiumAccountActivity.class));
            finish();
            return;
        }
        if (hasLocation()) {
            openTarget();
            return;
        }
        renderGate(false);
    }

    @Override protected void onResume() {
        super.onResume();
        if (hasLocation() && !isFinishing()) openTarget();
    }

    private void renderGate(boolean denied) {
        deniedOnce = denied;
        theme = EstradaTheme.get(this);
        getWindow().setStatusBarColor(theme.background);
        getWindow().setNavigationBarColor(theme.background);

        LinearLayout page = PremiumUi.col(this);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(dp(24), dp(28), dp(24), dp(30));
        page.setBackground(PremiumUi.gradient(this, theme.background,
                blend(theme.background, theme.primary, .08f), 0));
        setContentView(page);

        BrandMarkView mark = new BrandMarkView(this);
        page.addView(mark, lp(76, 76));

        TextView over = PremiumUi.overline(this, "ESTRADA", theme.secondary);
        over.setGravity(Gravity.CENTER);
        page.addView(over);
        margins(over, 0, 18, 0, 6);

        TextView title = PremiumUi.text(this,
                denied ? "Localização necessária" : "Ativar a estrada",
                28, theme.text, true);
        title.setGravity(Gravity.CENTER);
        page.addView(title);

        TextView body = PremiumUi.text(this,
                denied
                        ? "A página Estrada precisa da localização para mostrar sua posição, velocidade, rota e proteção rodoviária."
                        : "Autorize a localização para abrir o mapa, calcular sua posição e ativar a proteção enquanto o Estrada Play estiver aberto.",
                13, theme.muted, false);
        body.setGravity(Gravity.CENTER);
        page.addView(body);
        margins(body, 0, 10, 0, 22);

        LinearLayout card = PremiumUi.col(this);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(PremiumUi.panel(this, theme.glass, theme.border, theme.radiusDp + 2));
        card.addView(PremiumUi.text(this, "GPS somente durante o uso", 14, theme.text, true));
        TextView detail = PremiumUi.text(this,
                "O Estrada Play não inicia a proteção depois que o aplicativo é fechado.",
                11, theme.muted, false);
        card.addView(detail);
        margins(detail, 0, 6, 0, 0);
        page.addView(card, lp(-1, -2));

        Button allow = PremiumUi.button(this,
                denied ? "ABRIR PERMISSÃO DO APP" : "ATIVAR LOCALIZAÇÃO", true);
        page.addView(allow, lp(-1, 56));
        margins(allow, 0, 16, 0, 0);
        allow.setOnClickListener(v -> {
            if (deniedOnce && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
                    && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                Intent settings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(settings);
            } else {
                requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                }, REQ_LOCATION);
            }
        });

        Button central = PremiumUi.button(this, "VOLTAR À CENTRAL", false);
        page.addView(central, lp(-1, 50));
        margins(central, 0, 9, 0, 0);
        central.setOnClickListener(v -> {
            Intent i = new Intent(this, PremiumHomeActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
            finish();
        });
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_LOCATION) return;
        if (hasLocation()) openTarget();
        else renderGate(true);
    }

    private void openTarget() {
        boolean destination = getIntent() != null && getIntent().getBooleanExtra(EXTRA_DESTINATION, false);
        Intent i = new Intent(this, destination ? DestinationActivity.class : RoadMapActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
    }

    private boolean hasLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasAccount() {
        try {
            SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
            JSONObject a = new JSONObject(p.getString(KEY_ACCOUNT, "{}"));
            return a.optBoolean("authenticated", false) || a.optJSONObject("user") != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private int dp(float v) { return PremiumUi.dp(this, v); }
    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h));
    }
    private void margins(View v, int l, int t, int r, int b) {
        if (!(v.getLayoutParams() instanceof LinearLayout.LayoutParams)) return;
        LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) v.getLayoutParams();
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        v.setLayoutParams(p);
    }
    private static int blend(int a, int b, float amount) {
        float x = Math.max(0f, Math.min(1f, amount));
        return Color.rgb(
                Math.round(Color.red(a) * (1f - x) + Color.red(b) * x),
                Math.round(Color.green(a) * (1f - x) + Color.green(b) * x),
                Math.round(Color.blue(a) * (1f - x) + Color.blue(b) * x));
    }
}

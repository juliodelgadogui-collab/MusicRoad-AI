package com.estradaplay.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

public final class GateActivity extends ComponentActivity {
    private static final int REQ_LOCATION = 901;
    private final int BG = Color.rgb(4, 9, 15);
    private final int TEXT = Color.rgb(247, 249, 252);
    private final int MUTED = Color.rgb(143, 160, 178);
    private final int ACCENT = Color.rgb(255, 116, 24);

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (hasLocation()) {
            startSafety();
            openApp();
        } else showPermissionIntro();
    }

    private void showPermissionIntro() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER);
        page.setPadding(dp(28), dp(36), dp(28), dp(36));
        page.setBackgroundColor(BG);
        setContentView(page);

        TextView brand = text("EstradaPlay", 38, TEXT, true); brand.setGravity(Gravity.CENTER); page.addView(brand);
        TextView tag = text("PLAY NA ESTRADA", 12, ACCENT, true); tag.setGravity(Gravity.CENTER); page.addView(tag);
        TextView icon = text("◎", 72, ACCENT, true); icon.setGravity(Gravity.CENTER); page.addView(icon); margins(icon, 0, 28, 0, 8);
        TextView title = text("Proteção automática na estrada", 25, TEXT, true); title.setGravity(Gravity.CENTER); page.addView(title);
        TextView body = text("Sem escolher destino. O GPS identifica sua posição e o sentido do carro. O EstradaPlay baixa os alertas da região no aparelho e reconhece radares, semáforos, quebra-molas, pedágios e passagens de nível mesmo sem uma rota aberta.", 14, MUTED, false);
        body.setGravity(Gravity.CENTER); page.addView(body); margins(body, 0, 12, 0, 24);

        Button allow = button("ATIVAR PROTEÇÃO", true); page.addView(allow, new LinearLayout.LayoutParams(-1, dp(58)));
        Button skip = button("AGORA NÃO", false); page.addView(skip, new LinearLayout.LayoutParams(-1, dp(50))); margins(skip, 0, 10, 0, 0);
        allow.setOnClickListener(v -> requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION));
        skip.setOnClickListener(v -> openApp());
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION) {
            if (hasLocation()) startSafety();
            openApp();
        }
    }

    private boolean hasLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startSafety() {
        Intent i = new Intent(this, RoadSafetyService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } catch (Throwable ignored) {}
    }

    private void openApp() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setLineSpacing(0, 1.08f);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private Button button(String label, boolean primary) {
        Button b = new Button(this); b.setText(label); b.setTextSize(13); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); b.setAllCaps(false);
        b.setTextColor(primary ? Color.WHITE : TEXT);
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(dp(15)); g.setColor(primary ? ACCENT : Color.rgb(15,27,40));
        if (!primary) g.setStroke(dp(1), Color.rgb(38,55,72)); b.setBackground(g);
        return b;
    }

    private void margins(android.view.View v, int l, int t, int r, int b) {
        if (!(v.getLayoutParams() instanceof LinearLayout.LayoutParams)) return;
        LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)v.getLayoutParams(); p.setMargins(dp(l),dp(t),dp(r),dp(b)); v.setLayoutParams(p);
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}

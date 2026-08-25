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
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

public final class GateActivity extends ComponentActivity {
    private static final int REQ_LOCATION = 901;

    private final int BG = Color.rgb(6, 8, 12);
    private final int SURFACE = Color.rgb(13, 17, 23);
    private final int SURFACE_2 = Color.rgb(20, 26, 34);
    private final int BORDER = Color.rgb(36, 46, 57);
    private final int TEXT = Color.rgb(244, 247, 251);
    private final int MUTED = Color.rgb(143, 154, 167);
    private final int ACCENT = Color.rgb(255, 107, 44);
    private final int ACCENT_SOFT = Color.rgb(67, 31, 20);
    private final int GREEN = Color.rgb(69, 212, 131);
    private final int GREEN_SOFT = Color.rgb(20, 57, 42);
    private final int BLUE = Color.rgb(93, 169, 255);
    private final int BLUE_SOFT = Color.rgb(20, 43, 67);

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
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(dp(24), dp(30), dp(24), dp(30));
        page.setBackgroundColor(BG);
        setContentView(page);

        LinearLayout brand = new LinearLayout(this); brand.setOrientation(LinearLayout.HORIZONTAL); brand.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = badge("EP", ACCENT, ACCENT_SOFT); brand.addView(mark, new LinearLayout.LayoutParams(dp(50), dp(50)));
        LinearLayout names = new LinearLayout(this); names.setOrientation(LinearLayout.VERTICAL);
        names.addView(text("EstradaPlay", 27, TEXT, true)); names.addView(overline("PLAY NA ESTRADA", ACCENT));
        brand.addView(names, new LinearLayout.LayoutParams(0, -2, 1)); margins(names, 12, 0, 0, 0);
        page.addView(brand, new LinearLayout.LayoutParams(-1, -2));

        TextView step = overline("CONFIGURAÇÃO RÁPIDA · 1 DE 1", MUTED); page.addView(step); margins(step, 0, 48, 0, 4);
        TextView title = text("Proteção sem precisar abrir rota", 30, TEXT, true); page.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView body = text("O EstradaPlay usa GPS para entender sua posição e o sentido do carro. Assim ele pode avisar o que está à frente mesmo sem você escolher um destino.", 14, MUTED, false);
        page.addView(body, new LinearLayout.LayoutParams(-1, -2)); margins(body, 0, 8, 0, 20);

        LinearLayout info = card(); page.addView(info, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout row1 = feature("◎", "GPS passivo", "Acompanha direção e velocidade", BLUE, BLUE_SOFT); info.addView(row1);
        View div1 = divider(); info.addView(div1); margins(div1, 0, 12, 0, 12);
        LinearLayout row2 = feature("↓", "Proteção offline", "Base estadual + reserva de 250 km", GREEN, GREEN_SOFT); info.addView(row2);
        View div2 = divider(); info.addView(div2); margins(div2, 0, 12, 0, 12);
        LinearLayout row3 = feature("!", "Alertas à frente", "Radar, semáforo, lombada e mais", ACCENT, ACCENT_SOFT); info.addView(row3);

        TextView privacy = text("A localização é usada para a proteção da estrada. O app não precisa de um destino para funcionar.", 11, MUTED, false);
        privacy.setGravity(Gravity.CENTER); page.addView(privacy, new LinearLayout.LayoutParams(-1, -2)); margins(privacy, 8, 18, 8, 18);

        LinearLayout spacer = new LinearLayout(this); page.addView(spacer, new LinearLayout.LayoutParams(-1, 0, 1));

        Button allow = button("ATIVAR PROTEÇÃO", true); page.addView(allow, new LinearLayout.LayoutParams(-1, dp(58)));
        Button skip = button("CONTINUAR SEM GPS", false); page.addView(skip, new LinearLayout.LayoutParams(-1, dp(50))); margins(skip, 0, 8, 0, 0);
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

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(dp(17), dp(16), dp(17), dp(16)); l.setBackground(bg(SURFACE, 20, BORDER)); l.setElevation(dp(1)); return l;
    }

    private LinearLayout feature(String mark, String title, String subtitle, int accent, int fill) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = badge(mark, accent, fill); row.addView(badge, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout meta = new LinearLayout(this); meta.setOrientation(LinearLayout.VERTICAL); meta.addView(text(title, 14, TEXT, true)); meta.addView(text(subtitle, 11, MUTED, false));
        row.addView(meta, new LinearLayout.LayoutParams(0, -2, 1)); margins(meta, 12, 0, 0, 0); return row;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setLineSpacing(0, 1.08f); if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t;
    }

    private TextView overline(String value, int color) {
        TextView t = text(value, 9.5f, color, true); t.setLetterSpacing(0.13f); return t;
    }

    private TextView badge(String value, int color, int fill) {
        TextView t = text(value, 15, color, true); t.setGravity(Gravity.CENTER); t.setBackground(bg(fill, 100, color)); return t;
    }

    private Button button(String label, boolean primary) {
        Button b = new Button(this); b.setText(label); b.setTextSize(11); b.setLetterSpacing(0.08f); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); b.setAllCaps(false); b.setStateListAnimator(null);
        b.setTextColor(primary ? Color.WHITE : MUTED); b.setBackground(bg(primary ? ACCENT : SURFACE_2, 15, primary ? 0 : BORDER)); return b;
    }

    private View divider() { View v = new View(this); v.setBackgroundColor(BORDER); v.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1))); return v; }

    private GradientDrawable bg(int color, int radius, int stroke) {
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(dp(radius)); g.setColor(color); if (stroke != 0) g.setStroke(dp(1), stroke); return g;
    }

    private void margins(View v, int l, int t, int r, int b) {
        if (!(v.getLayoutParams() instanceof LinearLayout.LayoutParams)) return;
        LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)v.getLayoutParams(); p.setMargins(dp(l),dp(t),dp(r),dp(b)); v.setLayoutParams(p);
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}

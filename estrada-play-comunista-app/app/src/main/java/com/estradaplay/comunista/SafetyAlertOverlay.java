package com.estradaplay.comunista;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** Central driving warning shown over whichever main cockpit is visible. */
final class SafetyAlertOverlay {
    private static final String TAG = "epc-central-safety-alert";
    private static String lastId = "";
    private static long lastAt;

    private SafetyAlertOverlay() {}

    static void show(Context context, FrameLayout host, Intent intent) {
        if (context == null || host == null || intent == null) return;
        String type = normalize(intent.getStringExtra("hazard_type"));
        if (!("RADAR".equals(type) || "QUEBRA_MOLAS".equals(type) || "CAMERA_MONITORAMENTO".equals(type))) return;

        String id = safe(intent.getStringExtra("hazard_id"));
        if (id.isEmpty()) id = type + ":" + Math.round(intent.getDoubleExtra("distance_m", 0));
        long now = System.currentTimeMillis();
        if (id.equals(lastId) && now - lastAt < 12000L) return;
        lastId = id;
        lastAt = now;

        View old = host.findViewWithTag(TAG);
        if (old != null) host.removeView(old);

        int accent = "QUEBRA_MOLAS".equals(type) ? Color.rgb(234, 145, 34)
                : ("CAMERA_MONITORAMENTO".equals(type) ? Color.rgb(217, 190, 93) : Color.rgb(208, 24, 45));
        int ink = Color.rgb(249, 241, 226);
        int muted = Color.rgb(191, 171, 164);
        int panel = Color.rgb(24, 8, 12);

        FrameLayout overlay = new FrameLayout(context);
        overlay.setTag(TAG);
        overlay.setBackgroundColor(Color.argb(132, 0, 0, 0));
        overlay.setClickable(true);
        overlay.setFocusable(false);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(context, 24), dp(context, 20), dp(context, 24), dp(context, 18));
        card.setBackground(round(panel, 24, accent, 2));
        if (android.os.Build.VERSION.SDK_INT >= 21) card.setElevation(dp(context, 36));

        TextView overline = text(context,
                "RADAR".equals(type) ? "ALERTA DE FISCALIZAÇÃO"
                        : ("QUEBRA_MOLAS".equals(type) ? "ATENÇÃO NA VIA" : "MONITORAMENTO DE TRÁFEGO"),
                10, accent, true);
        overline.setLetterSpacing(0.13f);
        overline.setGravity(Gravity.CENTER);
        card.addView(overline);

        String titleValue = "RADAR".equals(type) ? "RADAR À FRENTE"
                : ("QUEBRA_MOLAS".equals(type) ? "QUEBRA-MOLAS À FRENTE" : "CÂMERA À FRENTE");
        TextView title = text(context, titleValue, 23, ink, true);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.setMargins(0, dp(context, 8), 0, dp(context, 12));
        card.addView(title, tp);

        int limit = intent.getIntExtra("radar_limit_kmh", intent.getIntExtra("limit_kmh", 0));
        if ("RADAR".equals(type)) {
            TextView sign = text(context, limit > 0 ? String.valueOf(limit) : "?", limit > 0 ? 46 : 42, Color.rgb(18, 18, 18), true);
            sign.setGravity(Gravity.CENTER);
            sign.setBackground(round(Color.WHITE, 100, accent, 7));
            card.addView(sign, new LinearLayout.LayoutParams(dp(context, 104), dp(context, 104)));
            TextView limitText = text(context, limit > 0 ? "KM/H  ·  LIMITE DO RADAR" : "LIMITE DO RADAR NÃO INFORMADO", 11, ink, true);
            limitText.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(context, 9), 0, 0);
            card.addView(limitText, lp);
        } else {
            TextView action = text(context, "QUEBRA_MOLAS".equals(type) ? "REDUZA" : "ATENÇÃO", 30, accent, true);
            action.setLetterSpacing(0.08f);
            action.setGravity(Gravity.CENTER);
            card.addView(action);
        }

        double distance = intent.getDoubleExtra("distance_m", 0);
        String road = safe(intent.getStringExtra("road"));
        StringBuilder detail = new StringBuilder();
        if (distance > 0) detail.append(distance >= 1000
                ? String.format(Locale.getDefault(), "%.1f km", distance / 1000.0)
                : Math.max(10, Math.round(distance / 10.0) * 10) + " m");
        if (!road.isEmpty()) {
            if (detail.length() > 0) detail.append("  ·  ");
            detail.append(road);
        }
        if (detail.length() == 0) detail.append("Ponto detectado à frente");
        TextView info = text(context, detail.toString(), 13, muted, false);
        info.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, -2);
        ip.setMargins(0, dp(context, 12), 0, 0);
        card.addView(info, ip);

        TextView close = text(context, "TOQUE PARA FECHAR", 9, muted, true);
        close.setLetterSpacing(0.12f);
        close.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.setMargins(0, dp(context, 13), 0, 0);
        card.addView(close, cp);

        int screenW = context.getResources().getDisplayMetrics().widthPixels;
        int max = dp(context, "RADAR".equals(type) ? 390 : 420);
        int width = Math.min(max, Math.max(dp(context, 280), screenW - dp(context, 34)));
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(width, -2, Gravity.CENTER);
        overlay.addView(card, cardParams);
        host.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(140L).start();
        overlay.setOnClickListener(v -> dismiss(host, overlay));
        long visibleMs = "QUEBRA_MOLAS".equals(type) ? 5600L : 5000L;
        overlay.postDelayed(() -> dismiss(host, overlay), visibleMs);
    }

    private static void dismiss(FrameLayout host, View overlay) {
        if (host == null || overlay == null || overlay.getParent() == null) return;
        overlay.animate().alpha(0f).setDuration(160L).withEndAction(() -> {
            try { if (overlay.getParent() == host) host.removeView(overlay); } catch (Throwable ignored) {}
        }).start();
    }

    private static String normalize(String raw) {
        String t = safe(raw).toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (t.contains("QUEBRA") || t.contains("LOMBADA") || t.contains("BUMP") || t.contains("HUMP")) return "QUEBRA_MOLAS";
        if (t.contains("CAMERA") || t.contains("CÂMERA") || t.contains("MONITOR") || t.contains("CCTV") || t.contains("SURVEILLANCE")) return "CAMERA_MONITORAMENTO";
        if (t.contains("RADAR") || t.contains("SPEED_CAMERA") || t.contains("ENFORCEMENT")) return "RADAR";
        return t;
    }

    private static TextView text(Context c, String value, float size, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setLineSpacing(0, 1.04f);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private static GradientDrawable round(int color, int radius, int stroke, int strokeWidth) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dpRaw(radius));
        if (stroke != 0 && strokeWidth > 0) d.setStroke(dpRaw(strokeWidth), stroke);
        return d;
    }

    private static float density = 1f;
    private static int dpRaw(float value) { return Math.round(value * density); }
    private static int dp(Context c, float value) {
        density = c.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
    private static String safe(String s) { return s == null ? "" : s.trim(); }
}

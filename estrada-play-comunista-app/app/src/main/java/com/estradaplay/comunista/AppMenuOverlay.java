package com.estradaplay.comunista;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

final class AppMenuOverlay {
    interface Listener { void onSelect(int index); }

    private static final int PANEL = Color.rgb(10, 14, 19);
    private static final int SURFACE = Color.rgb(18, 24, 31);
    private static final int BORDER = Color.rgb(42, 52, 64);
    private static final int TEXT = Color.rgb(244, 247, 251);
    private static final int MUTED = Color.rgb(139, 151, 164);
    private static final int ACCENT = Color.rgb(255, 107, 44);
    private static final int ACCENT_SOFT = Color.rgb(62, 31, 21);
    private static final int GREEN = Color.rgb(69, 212, 131);

    private AppMenuOverlay() {}

    static void show(Context context, FrameLayout host, String currentScreen, Listener listener) {
        if (context == null || host == null) return;
        View old = host.findViewWithTag("estradaplay-menu-overlay");
        if (old != null) host.removeView(old);

        FrameLayout overlay = new FrameLayout(context);
        overlay.setTag("estradaplay-menu-overlay");
        overlay.setClickable(true);
        overlay.setFocusable(true);

        View scrim = new View(context);
        scrim.setBackgroundColor(Color.BLACK);
        scrim.setAlpha(0f);
        overlay.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        int screen = context.getResources().getDisplayMetrics().widthPixels;
        int width = Math.min(dp(context, 352), Math.max(dp(context, 286), (int)(screen * 0.84f)));

        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(context, 20), dp(context, 22), dp(context, 18), dp(context, 18));
        panel.setBackground(round(PANEL, 28, BORDER));
        panel.setElevation(dp(context, 18));
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(width, -1, Gravity.END);
        pp.setMargins(0, dp(context, 8), dp(context, 8), dp(context, 8));
        overlay.addView(panel, pp);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView mark = text(context, "EP", 14, ACCENT, true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(round(ACCENT_SOFT, 16, ACCENT));
        header.addView(mark, new LinearLayout.LayoutParams(dp(context, 46), dp(context, 46)));

        LinearLayout brand = new LinearLayout(context);
        brand.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(context, "EstradaPlay", 20, TEXT, true);
        TextView sub = text(context, "PLAY NA ESTRADA", 9, ACCENT, true);
        sub.setLetterSpacing(0.12f);
        brand.addView(title);
        brand.addView(sub);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, -2, 1);
        bp.setMargins(dp(context, 12), 0, 0, 0);
        header.addView(brand, bp);

        TextView close = text(context, "×", 27, MUTED, false);
        close.setGravity(Gravity.CENTER);
        close.setBackground(round(SURFACE, 15, BORDER));
        header.addView(close, new LinearLayout.LayoutParams(dp(context, 44), dp(context, 44)));
        panel.addView(header);

        TextView section = text(context, "NAVEGAÇÃO", 9, MUTED, true);
        section.setLetterSpacing(0.13f);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.setMargins(0, dp(context, 28), 0, dp(context, 8));
        panel.addView(section, sp);

        String[][] items = new String[][]{
                {"⌂", "Início", "Resumo do aparelho"},
                {"♪", "Música offline", "Player e biblioteca"},
                {"↓", "Gerenciar músicas", "Baixar ou remover pastas"},
                {"◎", "Mapa e proteção", "GPS, radares e estrada"},
                {"•", "Conta", "Perfil e informações"}
        };

        String current = currentScreen == null ? "" : currentScreen.toLowerCase();
        for (int i = 0; i < items.length; i++) {
            boolean selected = selected(i, current);
            LinearLayout item = menuItem(context, items[i][0], items[i][1], items[i][2], selected);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, dp(context, 68));
            ip.setMargins(0, i == 0 ? 0 : dp(context, 5), 0, 0);
            panel.addView(item, ip);
            final int index = i;
            item.setOnClickListener(v -> close(context, host, overlay, panel, scrim, () -> {
                if (listener != null) listener.onSelect(index);
            }));
        }

        View spacer = new View(context);
        panel.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));

        LinearLayout footer = new LinearLayout(context);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12));
        footer.setBackground(round(SURFACE, 16, BORDER));
        TextView offline = text(context, "●  OFFLINE-FIRST", 9, GREEN, true);
        offline.setLetterSpacing(0.08f);
        footer.addView(offline);
        TextView version = text(context, "EstradaPlay " + BuildConfig.VERSION_NAME + " · mapa livre", 10, MUTED, false);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-1, -2);
        vp.setMargins(0, dp(context, 5), 0, 0);
        footer.addView(version, vp);
        panel.addView(footer);

        host.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        panel.setTranslationX(width + dp(context, 24));
        panel.setAlpha(0.96f);
        panel.post(() -> panel.animate().translationX(0f).alpha(1f).setDuration(220L).start());
        scrim.animate().alpha(0.68f).setDuration(180L).start();

        scrim.setOnClickListener(v -> close(context, host, overlay, panel, scrim, null));
        close.setOnClickListener(v -> close(context, host, overlay, panel, scrim, null));
    }

    private static LinearLayout menuItem(Context c, String icon, String title, String subtitle, boolean selected) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(c, 11), dp(c, 8), dp(c, 10), dp(c, 8));
        row.setBackground(round(selected ? ACCENT_SOFT : Color.TRANSPARENT, 17, selected ? ACCENT : Color.TRANSPARENT));

        TextView glyph = text(c, icon, 19, selected ? ACCENT : MUTED, true);
        glyph.setGravity(Gravity.CENTER);
        glyph.setBackground(round(selected ? Color.rgb(77, 36, 22) : SURFACE, 14, selected ? ACCENT : BORDER));
        row.addView(glyph, new LinearLayout.LayoutParams(dp(c, 44), dp(c, 44)));

        LinearLayout meta = new LinearLayout(c);
        meta.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(c, title, 14, selected ? TEXT : Color.rgb(224, 230, 237), true);
        TextView s = text(c, subtitle, 10, selected ? Color.rgb(210, 175, 160) : MUTED, false);
        meta.addView(t);
        meta.addView(s);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1);
        mp.setMargins(dp(c, 12), 0, 0, 0);
        row.addView(meta, mp);

        if (selected) {
            TextView dot = text(c, "•", 20, ACCENT, true);
            dot.setGravity(Gravity.CENTER);
            row.addView(dot, new LinearLayout.LayoutParams(dp(c, 24), dp(c, 40)));
        }
        return row;
    }

    private static boolean selected(int index, String current) {
        if (index == 0) return current.contains("início") || current.contains("inicio");
        if (index == 1) return current.contains("música") || current.contains("musica");
        if (index == 2) return current.contains("biblioteca") || current.contains("download") || current.contains("gerenciar");
        if (index == 3) return current.contains("estrada") || current.contains("mapa") || current.contains("proteção") || current.contains("protecao");
        return current.contains("conta");
    }

    private static void close(Context c, FrameLayout host, FrameLayout overlay, View panel, View scrim, Runnable after) {
        if (overlay.getParent() == null) return;
        panel.animate().translationX(panel.getWidth() + dp(c, 28)).alpha(0.96f).setDuration(180L).start();
        scrim.animate().alpha(0f).setDuration(160L).withEndAction(() -> {
            try { host.removeView(overlay); } catch (Throwable ignored) {}
            if (after != null) after.run();
        }).start();
    }

    private static TextView text(Context c, String value, float size, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setLineSpacing(0, 1.05f);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private static GradientDrawable round(int color, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        if (stroke != Color.TRANSPARENT && stroke != 0) d.setStroke(1, stroke);
        return d;
    }

    private static int dp(Context c, float value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }
}

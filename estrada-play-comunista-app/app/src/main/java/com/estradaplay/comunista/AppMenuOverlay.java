package com.estradaplay.comunista;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

final class AppMenuOverlay {
    interface Listener { void onSelect(int index); }

    private static final int BG = Color.rgb(8, 5, 7);
    private static final int PANEL = Color.rgb(18, 9, 12);
    private static final int SURFACE = Color.rgb(28, 14, 18);
    private static final int BORDER = Color.rgb(82, 39, 45);
    private static final int TEXT = Color.rgb(246, 238, 224);
    private static final int MUTED = Color.rgb(174, 151, 146);
    private static final int RED = Color.rgb(190, 18, 38);
    private static final int GOLD = Color.rgb(226, 185, 76);
    private static final int GREEN = Color.rgb(72, 212, 134);

    private AppMenuOverlay() {}

    static void show(Context context, FrameLayout host, String currentScreen, Listener listener) {
        if (context == null || host == null) return;
        View old = host.findViewWithTag("epc-central-overlay");
        if (old != null) host.removeView(old);

        FrameLayout overlay = new FrameLayout(context);
        overlay.setTag("epc-central-overlay");
        overlay.setBackgroundColor(Color.argb(238, 3, 2, 3));
        overlay.setClickable(true);
        overlay.setFocusable(true);

        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 14));
        panel.setBackground(box(PANEL, 8, BORDER));
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(-1, -1);
        pp.setMargins(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12));
        overlay.addView(panel, pp);

        LinearLayout banner = new LinearLayout(context);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(dp(context, 14), dp(context, 12), dp(context, 12), dp(context, 12));
        banner.setBackground(box(RED, 3, 0));
        BrandMarkView mark = new BrandMarkView(context);
        banner.addView(mark, new LinearLayout.LayoutParams(dp(context, 50), dp(context, 50)));
        LinearLayout words = new LinearLayout(context);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(text(context, "PAINEL CENTRAL", 19, Color.WHITE, true));
        TextView code = text(context, "EPC  /  COMANDO DE BORDO", 9, Color.rgb(255, 222, 180), true);
        code.setLetterSpacing(0.10f); words.addView(code);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -2, 1); wp.setMargins(dp(context, 12), 0, 0, 0); banner.addView(words, wp);
        TextView close = text(context, "FECHAR", 9, Color.WHITE, true);
        close.setGravity(Gravity.CENTER); close.setBackground(box(Color.rgb(106, 12, 25), 2, Color.rgb(255, 126, 136)));
        banner.addView(close, new LinearLayout.LayoutParams(dp(context, 70), dp(context, 42)));
        panel.addView(banner);

        TextView prompt = text(context, "ESCOLHA UM SETOR", 10, MUTED, true);
        prompt.setLetterSpacing(0.14f);
        LinearLayout.LayoutParams prp = new LinearLayout.LayoutParams(-1, -2); prp.setMargins(dp(context, 2), dp(context, 18), 0, dp(context, 8)); panel.addView(prompt, prp);

        String[][] items = {
                {"01", "CENTRAL", "Resumo e partida"},
                {"02", "SOM", "Música offline"},
                {"03", "ARQUIVO", "Gerenciar downloads"},
                {"04", "ESTRADA", "Mapa e proteção"},
                {"05", "IDENTIDADE", "Conta e aparelho"}
        };
        String current = currentScreen == null ? "" : currentScreen.toLowerCase();

        LinearLayout row1 = row(context);
        LinearLayout a = module(context, items[0], selected(0, current));
        LinearLayout b = module(context, items[1], selected(1, current));
        row1.addView(a, new LinearLayout.LayoutParams(0, dp(context, 112), 1));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(context, 112), 1); bp.setMargins(dp(context, 8), 0, 0, 0); row1.addView(b, bp);
        panel.addView(row1);

        LinearLayout row2 = row(context);
        LinearLayout c = module(context, items[2], selected(2, current));
        LinearLayout d = module(context, items[3], selected(3, current));
        row2.addView(c, new LinearLayout.LayoutParams(0, dp(context, 112), 1));
        LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(0, dp(context, 112), 1); dp2.setMargins(dp(context, 8), 0, 0, 0); row2.addView(d, dp2);
        LinearLayout.LayoutParams r2p = new LinearLayout.LayoutParams(-1, -2); r2p.setMargins(0, dp(context, 8), 0, 0); panel.addView(row2, r2p);

        LinearLayout e = module(context, items[4], selected(4, current));
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, dp(context, 94)); ep.setMargins(0, dp(context, 8), 0, 0); panel.addView(e, ep);

        View spacer = new View(context); panel.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));
        LinearLayout footer = row(context); footer.setGravity(Gravity.CENTER_VERTICAL); footer.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10)); footer.setBackground(box(BG, 2, BORDER));
        TextView offline = text(context, "●  OFFLINE-FIRST", 9, GREEN, true); offline.setLetterSpacing(0.08f); footer.addView(offline, new LinearLayout.LayoutParams(0, -2, 1));
        TextView ver = text(context, "v" + BuildConfig.VERSION_NAME, 10, GOLD, true); footer.addView(ver); panel.addView(footer);

        View[] modules = {a,b,c,d,e};
        for (int i = 0; i < modules.length; i++) {
            final int index = i;
            modules[i].setOnClickListener(v -> close(host, overlay, panel, () -> { if (listener != null) listener.onSelect(index); }));
        }
        close.setOnClickListener(v -> close(host, overlay, panel, null));

        host.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        panel.setAlpha(0f); panel.setScaleX(0.96f); panel.setScaleY(0.96f);
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(170L).start();
    }

    private static LinearLayout module(Context c, String[] data, boolean active) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(c, 13), dp(c, 11), dp(c, 13), dp(c, 11));
        box.setBackground(box(active ? Color.rgb(91, 12, 25) : SURFACE, 3, active ? RED : BORDER));
        TextView code = text(c, data[0], 10, active ? GOLD : RED, true); code.setLetterSpacing(0.12f); box.addView(code);
        TextView title = text(c, data[1], 18, TEXT, true); box.addView(title);
        TextView sub = text(c, data[2], 10, active ? Color.rgb(231, 198, 190) : MUTED, false); box.addView(sub);
        View spacer = new View(c); box.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));
        TextView go = text(c, active ? "SETOR ATUAL  ■" : "ABRIR  →", 9, active ? GOLD : TEXT, true); go.setGravity(Gravity.RIGHT); box.addView(go);
        box.setClickable(true); box.setFocusable(true);
        return box;
    }

    private static boolean selected(int index, String current) {
        if (index == 0) return current.contains("central") || current.contains("início") || current.contains("inicio");
        if (index == 1) return current.contains("música") || current.contains("musica");
        if (index == 2) return current.contains("biblioteca") || current.contains("download") || current.contains("arquivo");
        if (index == 3) return current.contains("estrada") || current.contains("mapa") || current.contains("proteção") || current.contains("protecao");
        return current.contains("conta") || current.contains("identidade");
    }

    private static void close(FrameLayout host, FrameLayout overlay, View panel, Runnable after) {
        if (overlay.getParent() == null) return;
        panel.animate().alpha(0f).scaleX(0.97f).scaleY(0.97f).setDuration(120L).withEndAction(() -> {
            try { host.removeView(overlay); } catch (Throwable ignored) {}
            if (after != null) after.run();
        }).start();
    }

    private static LinearLayout row(Context c) { LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private static TextView text(Context c, String value, float size, int color, boolean bold) {
        TextView t = new TextView(c); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setGravity(Gravity.CENTER_VERTICAL); t.setLineSpacing(0, 1.04f); if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t;
    }
    private static GradientDrawable box(int color, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(radius); if (stroke != 0) d.setStroke(1, stroke); return d;
    }
    private static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }
}

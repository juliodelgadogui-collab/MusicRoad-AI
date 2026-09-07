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

/** Lightweight side drawer shared by the native Estrada Play screens. */
final class AppMenuOverlay {
    interface Listener { void onSelect(int index); }

    private static final int BG = Color.rgb(7, 5, 7);
    private static final int PANEL = Color.rgb(15, 9, 11);
    private static final int SURFACE = Color.rgb(24, 15, 18);
    private static final int BORDER = Color.rgb(75, 42, 47);
    private static final int TEXT = Color.rgb(248, 241, 230);
    private static final int MUTED = Color.rgb(172, 157, 157);
    private static final int RED = Color.rgb(218, 15, 40);
    private static final int GOLD = Color.rgb(232, 190, 70);
    private static final int GREEN = Color.rgb(37, 218, 121);

    private AppMenuOverlay() {}

    static void show(Context context, FrameLayout host, String currentScreen, Listener listener) {
        if (context == null || host == null) return;
        View old = host.findViewWithTag("epc-central-overlay");
        if (old != null) host.removeView(old);

        FrameLayout overlay = new FrameLayout(context);
        overlay.setTag("epc-central-overlay");
        overlay.setBackgroundColor(Color.argb(168, 0, 0, 0));
        overlay.setClickable(true);
        overlay.setFocusable(true);

        int screenW = context.getResources().getDisplayMetrics().widthPixels;
        int screenH = context.getResources().getDisplayMetrics().heightPixels;
        boolean landscape = screenW > screenH;
        int drawerW = landscape
                ? Math.min(dp(context, 370), Math.round(screenW * .38f))
                : Math.min(dp(context, 350), Math.round(screenW * .88f));

        LinearLayout drawer = new LinearLayout(context);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 14));
        drawer.setBackground(box(PANEL, 0, BORDER, 1));
        FrameLayout.LayoutParams drawerLp = new FrameLayout.LayoutParams(drawerW, -1, Gravity.LEFT);
        overlay.addView(drawer, drawerLp);

        LinearLayout head = row(context);
        head.setGravity(Gravity.CENTER_VERTICAL);
        BrandMarkView mark = new BrandMarkView(context);
        head.addView(mark, new LinearLayout.LayoutParams(dp(context, 44), dp(context, 44)));
        LinearLayout words = col(context);
        words.setGravity(Gravity.CENTER_VERTICAL);
        words.addView(text(context, "ESTRADA PLAY", 18, TEXT, true));
        TextView sub = text(context, "CENTRAL DE BORDO", 9, RED, true);
        sub.setLetterSpacing(.11f);
        words.addView(sub);
        LinearLayout.LayoutParams wordsLp = new LinearLayout.LayoutParams(0, dp(context, 52), 1f);
        wordsLp.setMargins(dp(context, 11), 0, 0, 0);
        head.addView(words, wordsLp);
        TextView close = text(context, "×", 30, TEXT, false);
        close.setGravity(Gravity.CENTER);
        close.setBackground(box(Color.rgb(32, 22, 25), 100, BORDER, 1));
        head.addView(close, new LinearLayout.LayoutParams(dp(context, 44), dp(context, 44)));
        drawer.addView(head, new LinearLayout.LayoutParams(-1, dp(context, 58)));

        TextView online = text(context, "●  PROTEÇÃO COLETIVA ATIVA", 9, GREEN, true);
        online.setLetterSpacing(.05f);
        LinearLayout.LayoutParams onlineLp = new LinearLayout.LayoutParams(-1, dp(context, 34));
        onlineLp.setMargins(dp(context, 3), dp(context, 6), 0, dp(context, 8));
        drawer.addView(online, onlineLp);

        View divider = new View(context);
        divider.setBackgroundColor(BORDER);
        drawer.addView(divider, new LinearLayout.LayoutParams(-1, 1));

        String[][] items = {
                {"01", "Central", "Resumo, conta e partida"},
                {"02", "Música", "Biblioteca e reprodução offline"},
                {"03", "Arquivos", "Downloads e armazenamento"},
                {"04", "Estrada", "Mapa, navegação e proteção"},
                {"05", "Identidade", "Conta, aparelho e sessão"}
        };
        String current = currentScreen == null ? "" : currentScreen.toLowerCase();
        View[] rows = new View[items.length];
        for (int i = 0; i < items.length; i++) {
            rows[i] = item(context, items[i], selected(i, current));
            LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(-1, dp(context, 68));
            itemLp.setMargins(0, i == 0 ? dp(context, 10) : dp(context, 5), 0, 0);
            drawer.addView(rows[i], itemLp);
        }

        View spacer = new View(context);
        drawer.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        LinearLayout footer = row(context);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(dp(context, 10), 0, dp(context, 10), 0);
        footer.setBackground(box(BG, 14, BORDER, 1));
        TextView mode = text(context, "UNIVERSAL", 9, GOLD, true);
        mode.setLetterSpacing(.08f);
        footer.addView(mode, new LinearLayout.LayoutParams(0, -1, 1f));
        TextView version = text(context, "v" + BuildConfig.VERSION_NAME, 9, MUTED, true);
        footer.addView(version);
        drawer.addView(footer, new LinearLayout.LayoutParams(-1, dp(context, 42)));

        for (int i = 0; i < rows.length; i++) {
            final int index = i;
            rows[i].setOnClickListener(v -> close(host, overlay, drawer, drawerW,
                    () -> { if (listener != null) listener.onSelect(index); }));
        }
        close.setOnClickListener(v -> close(host, overlay, drawer, drawerW, null));
        overlay.setOnClickListener(v -> {
            if (v == overlay) close(host, overlay, drawer, drawerW, null);
        });
        drawer.setOnClickListener(v -> {});

        host.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        drawer.setTranslationX(-drawerW);
        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(130L).start();
        drawer.animate().translationX(0f).setDuration(190L).start();
    }

    private static View item(Context c, String[] data, boolean active) {
        LinearLayout row = row(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(c, 11), dp(c, 6), dp(c, 9), dp(c, 6));
        row.setBackground(box(active ? Color.rgb(56, 12, 22) : SURFACE, 15, active ? RED : BORDER, 1));

        TextView code = text(c, data[0], 9, active ? GOLD : RED, true);
        code.setGravity(Gravity.CENTER);
        row.addView(code, new LinearLayout.LayoutParams(dp(c, 36), -1));

        View rail = new View(c);
        rail.setBackgroundColor(active ? RED : Color.rgb(53, 38, 42));
        LinearLayout.LayoutParams railLp = new LinearLayout.LayoutParams(dp(c, 2), dp(c, 35));
        railLp.setMargins(dp(c, 3), 0, dp(c, 11), 0);
        row.addView(rail, railLp);

        LinearLayout words = col(c);
        words.setGravity(Gravity.CENTER_VERTICAL);
        words.addView(text(c, data[1], 14, TEXT, true));
        words.addView(text(c, data[2], 9, active ? Color.rgb(222, 194, 190) : MUTED, false));
        row.addView(words, new LinearLayout.LayoutParams(0, -1, 1f));

        TextView arrow = text(c, active ? "●" : "›", active ? 10 : 24, active ? GREEN : TEXT, true);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(c, 30), -1));
        row.setClickable(true);
        row.setFocusable(true);
        return row;
    }

    private static boolean selected(int index, String current) {
        if (index == 0) return current.contains("central") || current.contains("início") || current.contains("inicio");
        if (index == 1) return current.contains("música") || current.contains("musica");
        if (index == 2) return current.contains("biblioteca") || current.contains("download") || current.contains("arquivo");
        if (index == 3) return current.contains("estrada") || current.contains("mapa") || current.contains("proteção") || current.contains("protecao");
        return current.contains("conta") || current.contains("identidade");
    }

    private static void close(FrameLayout host, FrameLayout overlay, View drawer, int drawerW, Runnable after) {
        if (overlay.getParent() == null) return;
        overlay.animate().alpha(0f).setDuration(120L).start();
        drawer.animate().translationX(-drawerW).setDuration(150L).withEndAction(() -> {
            try { host.removeView(overlay); } catch (Throwable ignored) {}
            if (after != null) after.run();
        }).start();
    }

    private static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private static TextView text(Context c, String value, float size, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setLineSpacing(0, 1.03f);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private static GradientDrawable box(int color, int radiusDp, int stroke, int strokeWidthDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusDp <= 0 ? 0 : radiusDp);
        if (stroke != 0 && strokeWidthDp > 0) d.setStroke(strokeWidthDp, stroke);
        return d;
    }

    private static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}

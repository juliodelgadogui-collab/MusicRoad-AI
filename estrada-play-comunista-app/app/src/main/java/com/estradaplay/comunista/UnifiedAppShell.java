package com.estradaplay.comunista;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

/**
 * Estrada Play Premium shared shell with no fixed bottom navigation.
 *
 * The shell also migrates the known 1.x/2.x hard-coded palette into the active imported theme.
 * This changes the real native views; it does not place a second page over legacy Activities.
 */
final class UnifiedAppShell {
    private UnifiedAppShell() {}

    static View wrap(Activity a, String section, View content) {
        if (a == null || content == null) return content;
        EstradaTheme theme = EstradaTheme.get(a);
        a.getWindow().setStatusBarColor(theme.background);
        a.getWindow().setNavigationBarColor(theme.background);
        polish(a, content, theme);

        String active = section == null ? "central" : section.toLowerCase(java.util.Locale.ROOT);
        FrameLayout host = new FrameLayout(a);
        host.setBackgroundColor(theme.background);
        LinearLayout page = PremiumUi.col(a);
        host.addView(page, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout bar = PremiumUi.row(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(a, 10), dp(a, 7), dp(a, 12), dp(a, 7));
        bar.setBackground(PremiumUi.panel(a, theme.glass, theme.border, 0));

        Button menu = PremiumUi.button(a, "☰", false);
        menu.setTextSize(20);
        menu.setContentDescription("Abrir menu");
        bar.addView(menu, new LinearLayout.LayoutParams(dp(a, 48), dp(a, 48)));

        BrandMarkView mark = new BrandMarkView(a);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(a, 42), dp(a, 42));
        mp.setMargins(dp(a, 8), 0, 0, 0);
        bar.addView(mark, mp);

        LinearLayout words = PremiumUi.col(a);
        words.setGravity(Gravity.CENTER_VERTICAL);
        words.addView(PremiumUi.overline(a, "ESTRADA PLAY", theme.secondary));
        words.addView(PremiumUi.text(a, title(active), 16, theme.text, true));
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -1, 1f);
        wp.setMargins(dp(a, 10), 0, 0, 0);
        bar.addView(words, wp);

        TextView state = PremiumUi.overline(a, "● ATIVO", theme.success);
        state.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        bar.addView(state, new LinearLayout.LayoutParams(-2, -1));
        page.addView(bar, new LinearLayout.LayoutParams(-1, dp(a, 62)));

        FrameLayout body = new FrameLayout(a);
        body.setBackgroundColor(theme.background);
        body.addView(content, new FrameLayout.LayoutParams(-1, -1));
        page.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));

        menu.setOnClickListener(v -> AppMenuOverlay.show(a, host, active, index -> navigate(a, index)));
        return host;
    }

    private static void navigate(Activity a, int index) {
        Class<?> cls;
        if (index == 0) cls = PremiumHomeActivity.class;
        else if (index == 1) cls = PremiumMusicActivity.class;
        else if (index == 2) cls = PremiumDownloadsActivity.class;
        else if (index == 3) cls = RoadMapActivity.class;
        else cls = ThemeSettingsActivity.class;
        Intent i = new Intent(a, cls);
        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        a.startActivity(i);
    }

    private static String title(String s) {
        if (s.contains("road") || s.contains("map")) return "Estrada";
        if (s.contains("music")) return "Música";
        if (s.contains("radio")) return "Rádio";
        if (s.contains("trip") || s.contains("viagem")) return "Viagem";
        if (s.contains("storage") || s.contains("arquivo") || s.contains("download")) return "Biblioteca";
        if (s.contains("theme") || s.contains("tema") || s.contains("appearance")) return "Aparência";
        return "Central";
    }

    private static void polish(Activity a, View v, EstradaTheme theme) {
        retintBackground(a, v, theme);

        if (v instanceof Button) {
            Button b = (Button) v;
            b.setAllCaps(false);
            b.setStateListAnimator(null);
            b.setTextColor(theme.text);
            b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            String raw = String.valueOf(b.getText()).toUpperCase(java.util.Locale.ROOT);
            boolean primary = raw.contains("SALVAR") || raw.contains("REGISTRAR")
                    || raw.contains("ATIVAR") || raw.contains("INICIAR")
                    || raw.contains("CONECTAR") || raw.contains("FALAR")
                    || raw.contains("CONFIRMAR") || raw.contains("CRIAR")
                    || raw.contains("ENTRAR") || raw.contains("BAIXAR");
            b.setBackground(PremiumUi.panel(a,
                    primary ? theme.primary : theme.surfaceAlt,
                    primary ? theme.primary : theme.border,
                    theme.radiusDp));
        } else if (v instanceof EditText) {
            EditText e = (EditText) v;
            e.setTextColor(theme.text);
            e.setHintTextColor(theme.muted);
            e.setPadding(dp(a, 14), 0, dp(a, 14), 0);
            e.setBackground(PremiumUi.panel(a, theme.surfaceAlt, theme.border, theme.radiusDp));
        } else if (v instanceof Spinner) {
            Spinner s = (Spinner) v;
            s.setPadding(dp(a, 10), 0, dp(a, 10), 0);
            s.setBackground(PremiumUi.panel(a, theme.surfaceAlt, theme.border, theme.radiusDp));
        } else if (v instanceof TextView) {
            TextView text = (TextView) v;
            int original = text.getCurrentTextColor();
            int mapped = mapLegacyColor(original, theme);
            if (mapped != original) text.setTextColor(mapped);
        }

        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) polish(a, g.getChildAt(i), theme);
        }
    }

    private static void retintBackground(Activity a, View v, EstradaTheme theme) {
        Drawable bg = v.getBackground();
        if (bg instanceof ColorDrawable) {
            int original = ((ColorDrawable) bg).getColor();
            int mapped = mapLegacyColor(original, theme);
            if (mapped != original) v.setBackgroundColor(mapped);
            return;
        }
        if (bg instanceof GradientDrawable && !(v instanceof Button) && !(v instanceof EditText)) {
            GradientDrawable gradient = (GradientDrawable) bg;
            ColorStateList fill = gradient.getColor();
            if (fill == null) return;
            int original = fill.getDefaultColor();
            int mapped = mapLegacyColor(original, theme);
            if (mapped != original) {
                v.setBackground(PremiumUi.panel(a, mapped, theme.border, theme.radiusDp));
            }
        }
    }

    private static int mapLegacyColor(int color, EstradaTheme t) {
        int alpha = Color.alpha(color);
        int rgb = Color.rgb(Color.red(color), Color.green(color), Color.blue(color));
        int mapped = rgb;

        if (near(rgb, 8, 5, 7, 14) || near(rgb, 9, 5, 7, 14) || near(rgb, 5, 7, 10, 14)) mapped = t.background;
        else if (near(rgb, 18, 9, 12, 18) || near(rgb, 20, 10, 13, 18) || near(rgb, 13, 18, 24, 18)) mapped = t.surface;
        else if (near(rgb, 29, 14, 18, 22) || near(rgb, 30, 15, 19, 22) || near(rgb, 18, 25, 33, 22)) mapped = t.surfaceAlt;
        else if (near(rgb, 42, 20, 25, 22) || near(rgb, 24, 33, 43, 22)) mapped = t.surfaceAlt;
        else if (near(rgb, 76, 38, 44, 24) || near(rgb, 38, 49, 61, 22)) mapped = t.border;
        else if (near(rgb, 246, 238, 224, 22) || near(rgb, 246, 248, 251, 16) || near(rgb, 250, 241, 224, 22)) mapped = t.text;
        else if (near(rgb, 174, 151, 146, 26) || near(rgb, 145, 155, 166, 24) || near(rgb, 121, 91, 91, 24)) mapped = t.muted;
        else if (near(rgb, 190, 18, 38, 26) || near(rgb, 255, 82, 103, 28) || near(rgb, 255, 100, 107, 28)) mapped = t.primary;
        else if (near(rgb, 226, 185, 76, 28)) mapped = t.secondary;
        else if (near(rgb, 72, 212, 134, 28) || near(rgb, 69, 212, 131, 28) || near(rgb, 55, 211, 139, 28)) mapped = t.success;
        else if (near(rgb, 79, 165, 255, 28) || near(rgb, 93, 169, 255, 28) || near(rgb, 92, 169, 255, 28)) mapped = t.secondary;
        else if (near(rgb, 245, 145, 55, 28) || near(rgb, 255, 137, 61, 28)) mapped = t.warning;

        return Color.argb(alpha, Color.red(mapped), Color.green(mapped), Color.blue(mapped));
    }

    private static boolean near(int color, int r, int g, int b, int tolerance) {
        return Math.abs(Color.red(color) - r) <= tolerance
                && Math.abs(Color.green(color) - g) <= tolerance
                && Math.abs(Color.blue(color) - b) <= tolerance;
    }

    private static int dp(android.content.Context c, float v) {
        return PremiumUi.dp(c, v);
    }
}

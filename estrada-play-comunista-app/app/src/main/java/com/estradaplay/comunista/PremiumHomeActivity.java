package com.estradaplay.comunista;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

/** Estrada Play 5.0 premium central. Existing account/download logic remains in MainActivity. */
public final class PremiumHomeActivity extends ComponentActivity {
    private EstradaTheme theme;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        build();
    }

    @Override protected void onResume() {
        super.onResume();
        EstradaTheme current = EstradaTheme.get(this);
        if (theme != null && !current.name.equals(theme.name)) build();
    }

    private void build() {
        theme = EstradaTheme.get(this);
        getWindow().setStatusBarColor(theme.background);
        getWindow().setNavigationBarColor(theme.background);

        FrameLayout root = new FrameLayout(this);
        root.setBackground(PremiumUi.gradient(this, theme.background, blend(theme.background, theme.primary, .09f), 0));
        setContentView(root);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout page = PremiumUi.col(this);
        int side = isLandscape() ? dp(30) : dp(18);
        page.setPadding(side, dp(16), side, dp(28));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        page.addView(header());

        LinearLayout hero = PremiumUi.col(this);
        hero.setPadding(dp(20), dp(18), dp(20), dp(18));
        hero.setBackground(PremiumUi.gradient(this,
                PremiumUi.withAlpha(theme.surfaceAlt, 245),
                PremiumUi.withAlpha(blend(theme.surfaceAlt, theme.primary, .20f), 245),
                theme.radiusDp + 4));
        hero.addView(PremiumUi.overline(this, "PRONTO PARA RODAR", theme.success));
        TextView title = PremiumUi.text(this, "Sua estrada. Sua música.\nUm só lugar.", isLandscape()?30:31, theme.text, true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2); tlp.setMargins(0, dp(6), 0, dp(8)); hero.addView(title, tlp);
        hero.addView(PremiumUi.text(this,
                "Navegação, proteção, música local e ferramentas do veículo com uma interface pensada para dirigir.",
                13, theme.muted, false));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2); hp.setMargins(0, dp(18), 0, dp(14)); page.addView(hero, hp);

        if (isLandscape()) {
            LinearLayout primary = PremiumUi.row(this);
            primary.addView(feature("ESTRADA", "Mapa, rota e proteção", "↗", theme.primary,
                    v -> startActivity(new Intent(this, RoadMapActivity.class))), new LinearLayout.LayoutParams(0, dp(176), 1f));
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, dp(176), 1f); mp.setMargins(dp(10),0,0,0);
            primary.addView(feature("MÚSICA", "Biblioteca local e offline", "♪", theme.secondary,
                    v -> startActivity(new Intent(this, MusicPlayerActivity.class))), mp);
            page.addView(primary);
        } else {
            page.addView(feature("ESTRADA", "Mapa, rota e proteção", "↗", theme.primary,
                    v -> startActivity(new Intent(this, RoadMapActivity.class))), new LinearLayout.LayoutParams(-1, dp(156)));
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, dp(138)); mp.setMargins(0,dp(10),0,0);
            page.addView(feature("MÚSICA", "Biblioteca local e offline", "♪", theme.secondary,
                    v -> startActivity(new Intent(this, MusicPlayerActivity.class))), mp);
        }

        TextView tools = PremiumUi.overline(this, "FERRAMENTAS", theme.muted);
        LinearLayout.LayoutParams toolTitle = new LinearLayout.LayoutParams(-1, -2); toolTitle.setMargins(dp(4), dp(22), 0, dp(8)); page.addView(tools, toolTitle);

        LinearLayout grid = PremiumUi.col(this);
        LinearLayout r1 = PremiumUi.row(this);
        r1.addView(tile("ROTAS", "Definir destino", "◎", v -> startActivity(new Intent(this, DestinationActivity.class))), new LinearLayout.LayoutParams(0, dp(116), 1f));
        LinearLayout.LayoutParams t2 = new LinearLayout.LayoutParams(0, dp(116), 1f); t2.setMargins(dp(8),0,0,0);
        r1.addView(tile("DASHCAM", "Gravar viagem", "●", v -> startActivity(new Intent(this, CameraActivity.class))), t2);
        grid.addView(r1);

        LinearLayout r2 = PremiumUi.row(this);
        LinearLayout.LayoutParams r2p = new LinearLayout.LayoutParams(-1, -2); r2p.setMargins(0,dp(8),0,0);
        grid.addView(r2, r2p);
        r2.addView(tile("COPILOTO", "Comandos por voz", "◌", v -> startActivity(new Intent(this, VoiceCommandActivity.class))), new LinearLayout.LayoutParams(0, dp(116), 1f));
        LinearLayout.LayoutParams ptt = new LinearLayout.LayoutParams(0, dp(116), 1f); ptt.setMargins(dp(8),0,0,0);
        r2.addView(tile("RÁDIO", "PTT de estrada", "◉", v -> startActivity(new Intent(this, RoadRadioActivity.class))), ptt);

        LinearLayout r3 = PremiumUi.row(this);
        LinearLayout.LayoutParams r3p = new LinearLayout.LayoutParams(-1, -2); r3p.setMargins(0,dp(8),0,0);
        grid.addView(r3, r3p);
        r3.addView(tile("APARÊNCIA", theme.name, "◐", v -> startActivity(new Intent(this, ThemeSettingsActivity.class))), new LinearLayout.LayoutParams(0, dp(116), 1f));
        LinearLayout.LayoutParams more = new LinearLayout.LayoutParams(0, dp(116), 1f); more.setMargins(dp(8),0,0,0);
        r3.addView(tile("MAIS", "Ferramentas e conta", "•••", v -> startActivity(new Intent(this, DriveToolsActivity.class))), more);
        page.addView(grid);

        LinearLayout protection = PremiumUi.row(this);
        protection.setGravity(Gravity.CENTER_VERTICAL);
        protection.setPadding(dp(15), dp(12), dp(15), dp(12));
        protection.setBackground(PremiumUi.panel(this, PremiumUi.withAlpha(theme.surface, 236), theme.border, theme.radiusDp));
        TextView dot = PremiumUi.text(this, "●", 18, theme.success, true); dot.setGravity(Gravity.CENTER); protection.addView(dot, new LinearLayout.LayoutParams(dp(36), dp(42)));
        LinearLayout pwords = PremiumUi.col(this);
        pwords.addView(PremiumUi.text(this, "Proteção rodoviária", 14, theme.text, true));
        pwords.addView(PremiumUi.text(this, "Ativa somente enquanto o Estrada Play estiver aberto", 10, theme.muted, false));
        protection.addView(pwords, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, -2); pp.setMargins(0,dp(14),0,0); page.addView(protection, pp);
    }

    private View header() {
        LinearLayout head = PremiumUi.row(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        BrandMarkView mark = new BrandMarkView(this);
        head.addView(mark, new LinearLayout.LayoutParams(dp(50), dp(50)));

        LinearLayout words = PremiumUi.col(this);
        words.addView(PremiumUi.text(this, "ESTRADA PLAY", 19, theme.text, true));
        words.addView(PremiumUi.overline(this, "PREMIUM DRIVE", theme.secondary));
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, dp(54), 1f); wp.setMargins(dp(12),0,0,0); head.addView(words, wp);

        Button appearance = PremiumUi.button(this, "◐", false);
        appearance.setTextSize(20);
        appearance.setContentDescription("Aparência");
        appearance.setOnClickListener(v -> startActivity(new Intent(this, ThemeSettingsActivity.class)));
        head.addView(appearance, new LinearLayout.LayoutParams(dp(50), dp(50)));
        return head;
    }

    private View feature(String title, String subtitle, String glyph, int accent, View.OnClickListener click) {
        LinearLayout card = PremiumUi.row(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackground(PremiumUi.gradient(this, PremiumUi.withAlpha(theme.surfaceAlt, 250), PremiumUi.withAlpha(blend(theme.surfaceAlt, accent, .18f), 250), theme.radiusDp+3));

        TextView icon = PremiumUi.text(this, glyph, 30, accent, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(PremiumUi.panel(this, PremiumUi.withAlpha(accent, 28), PremiumUi.withAlpha(accent, 105), 18));
        card.addView(icon, new LinearLayout.LayoutParams(dp(64), dp(64)));

        LinearLayout words = PremiumUi.col(this);
        words.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = PremiumUi.text(this, title, 20, theme.text, true);
        words.addView(t);
        words.addView(PremiumUi.text(this, subtitle, 11, theme.muted, false));
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -1, 1f); wp.setMargins(dp(16),0,0,0); card.addView(words, wp);
        TextView arrow = PremiumUi.text(this, "›", 30, accent, false); arrow.setGravity(Gravity.CENTER); card.addView(arrow, new LinearLayout.LayoutParams(dp(36), -1));
        card.setClickable(true); card.setFocusable(true); card.setOnClickListener(click);
        return card;
    }

    private View tile(String title, String subtitle, String glyph, View.OnClickListener click) {
        LinearLayout card = PremiumUi.col(this);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(PremiumUi.panel(this, PremiumUi.withAlpha(theme.surface, 240), theme.border, theme.radiusDp));
        TextView icon = PremiumUi.text(this, glyph, 21, theme.secondary, true); card.addView(icon);
        TextView t = PremiumUi.text(this, title, 13, theme.text, true); LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1,-2); tp.setMargins(0,dp(5),0,0); card.addView(t,tp);
        TextView s = PremiumUi.text(this, subtitle, 10, theme.muted, false); s.setMaxLines(1); card.addView(s);
        card.setClickable(true); card.setFocusable(true); card.setOnClickListener(click);
        return card;
    }

    private boolean isLandscape() { return getResources().getDisplayMetrics().widthPixels > getResources().getDisplayMetrics().heightPixels; }
    private int dp(float v) { return PremiumUi.dp(this, v); }

    private static int blend(int a, int b, float amount) {
        float x = Math.max(0f, Math.min(1f, amount));
        return Color.rgb(
                Math.round(Color.red(a)*(1f-x)+Color.red(b)*x),
                Math.round(Color.green(a)*(1f-x)+Color.green(b)*x),
                Math.round(Color.blue(a)*(1f-x)+Color.blue(b)*x));
    }
}

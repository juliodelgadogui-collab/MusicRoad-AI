package com.estradaplay.comunista;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Company/fleet central for owner, admin and manager accounts. */
public final class CompanyHomeActivity extends ComponentActivity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private EstradaTheme theme;
    private TextView vehiclesValue, driversValue, activeValue, kmValue, status;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!CompanyAccount.shouldUseCompanyCentral(this)) {
            CompanyBootstrapProvider.markRouted();
            startActivity(new Intent(this, PremiumHomeActivity.class));
            finish();
            return;
        }
        build();
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        if (theme != null) refresh();
    }

    private void build() {
        theme = EstradaTheme.get(this);
        getWindow().setStatusBarColor(theme.background);
        getWindow().setNavigationBarColor(theme.background);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(theme.background);
        LinearLayout page = PremiumUi.col(this);
        int side = dp(isLandscape() ? 28 : 18);
        page.setPadding(side, dp(18), side, dp(30));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);

        LinearLayout header = PremiumUi.row(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        BrandMarkView mark = new BrandMarkView(this);
        header.addView(mark, new LinearLayout.LayoutParams(dp(50), dp(50)));
        LinearLayout words = PremiumUi.col(this);
        words.addView(PremiumUi.text(this, "ESTRADA PLAY FROTAS", 18, theme.text, true));
        words.addView(PremiumUi.overline(this, CompanyAccount.companyName(this).toUpperCase(Locale.ROOT), theme.secondary));
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -2, 1f);
        wp.setMargins(dp(12), 0, 0, 0);
        header.addView(words, wp);
        Button account = PremiumUi.button(this, "CONTA", false);
        account.setOnClickListener(v -> startActivity(new Intent(this, PremiumAccountActivity.class)));
        header.addView(account, new LinearLayout.LayoutParams(dp(82), dp(48)));
        page.addView(header);

        LinearLayout hero = PremiumUi.col(this);
        hero.setPadding(dp(18), dp(17), dp(18), dp(17));
        hero.setBackground(PremiumUi.gradient(this, PremiumUi.withAlpha(theme.surfaceAlt, 250),
                PremiumUi.withAlpha(blend(theme.surfaceAlt, theme.primary, .18f), 250), theme.radiusDp + 3));
        hero.addView(PremiumUi.overline(this, "OPERAÇÃO DE HOJE", theme.success));
        TextView title = PremiumUi.text(this, "Sua frota em um só lugar.", 27, theme.text, true);
        hero.addView(title);
        margins(title, 0, 6, 0, 7);
        status = PremiumUi.text(this, "Atualizando veículos, motoristas e quilometragem…", 12, theme.muted, false);
        hero.addView(status);
        page.addView(hero, lp(-1, -2, 0, 18, 0, 14));

        LinearLayout stats = PremiumUi.row(this);
        vehiclesValue = addStat(stats, "VEÍCULOS", "—");
        driversValue = addStat(stats, "MOTORISTAS", "—");
        activeValue = addStat(stats, "RODANDO", "—");
        kmValue = addStat(stats, "KM HOJE", "—");
        page.addView(stats, lp(-1, -2, 0, 0, 0, 18));

        page.addView(PremiumUi.overline(this, "GESTÃO", theme.muted));
        LinearLayout row1 = PremiumUi.row(this);
        row1.addView(tile("FROTA", "Cadastrar e acompanhar veículos", v -> startActivity(new Intent(this, CompanyFleetActivity.class))), new LinearLayout.LayoutParams(0, dp(118), 1f));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0, dp(118), 1f); dlp.setMargins(dp(8),0,0,0);
        row1.addView(tile("MOTORISTAS", "Equipe e veículo responsável", v -> startActivity(new Intent(this, CompanyDriversActivity.class))), dlp);
        page.addView(row1, lp(-1, -2, 0, 8, 0, 0));

        LinearLayout row2 = PremiumUi.row(this);
        row2.addView(tile("COMBOIO", "Operação da frota em grupo", v -> startActivity(new Intent(this, CompanyConvoyActivity.class))), new LinearLayout.LayoutParams(0, dp(118), 1f));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, dp(118), 1f); tlp.setMargins(dp(8),0,0,0);
        row2.addView(tile("VIAGENS", "Histórico e km percorrido", v -> startActivity(new Intent(this, CompanyTripsActivity.class))), tlp);
        page.addView(row2, lp(-1, -2, 0, 8, 0, 0));

        LinearLayout row3 = PremiumUi.row(this);
        row3.addView(tile("MANUTENÇÃO", "Serviços por veículo", v -> startActivity(new Intent(this, MaintenanceActivity.class))), new LinearLayout.LayoutParams(0, dp(118), 1f));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, dp(118), 1f); clp.setMargins(dp(8),0,0,0);
        row3.addView(tile("CUSTOS", "Combustível e custo por km", v -> startActivity(new Intent(this, VehicleCostActivity.class))), clp);
        page.addView(row3, lp(-1, -2, 0, 8, 0, 0));

        Button road = PremiumUi.button(this, "ABRIR MINHA ESTRADA", true);
        road.setOnClickListener(v -> startActivity(new Intent(this, RoadEntryActivity.class)));
        page.addView(road, lp(-1, 54, 0, 16, 0, 0));

        Button personal = PremiumUi.button(this, "CENTRAL DO MOTORISTA", false);
        personal.setOnClickListener(v -> {
            CompanyBootstrapProvider.markRouted();
            startActivity(new Intent(this, PremiumHomeActivity.class));
        });
        page.addView(personal, lp(-1, 50, 0, 8, 0, 0));
    }

    private TextView addStat(LinearLayout parent, String label, String initial) {
        LinearLayout card = PremiumUi.col(this);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(8), dp(12), dp(8), dp(12));
        card.setBackground(PremiumUi.panel(this, PremiumUi.withAlpha(theme.surface, 240), theme.border, theme.radiusDp));
        TextView value = PremiumUi.text(this, initial, 20, theme.text, true);
        value.setGravity(Gravity.CENTER);
        card.addView(value);
        TextView small = PremiumUi.overline(this, label, theme.muted);
        small.setGravity(Gravity.CENTER);
        card.addView(small);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(86), 1f);
        if (parent.getChildCount() > 0) p.setMargins(dp(6), 0, 0, 0);
        parent.addView(card, p);
        return value;
    }

    private View tile(String title, String subtitle, View.OnClickListener click) {
        LinearLayout card = PremiumUi.col(this);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackground(PremiumUi.panel(this, PremiumUi.withAlpha(theme.surface, 240), theme.border, theme.radiusDp));
        card.addView(PremiumUi.text(this, title, 14, theme.text, true));
        TextView sub = PremiumUi.text(this, subtitle, 10, theme.muted, false);
        card.addView(sub);
        margins(sub, 0, 6, 0, 0);
        card.setClickable(true); card.setFocusable(true); card.setOnClickListener(click);
        return card;
    }

    private void refresh() {
        io.execute(() -> {
            try {
                JSONObject j = new CompanyApi(this).dashboard();
                JSONObject s = j.optJSONObject("summary");
                if (s == null) s = new JSONObject();
                JSONObject finalS = s;
                runOnUiThread(() -> {
                    vehiclesValue.setText(String.valueOf(finalS.optInt("vehicles", 0)));
                    driversValue.setText(String.valueOf(finalS.optInt("drivers", 0)));
                    activeValue.setText(String.valueOf(finalS.optInt("active_now", 0)));
                    kmValue.setText(String.format(Locale.getDefault(), "%.0f", finalS.optDouble("km_today", 0.0)));
                    status.setText(finalS.optInt("active_now", 0) > 0
                            ? finalS.optInt("active_now", 0) + " veículo(s) em jornada agora"
                            : "Nenhum veículo em jornada agora");
                });
            } catch (Throwable e) {
                runOnUiThread(() -> status.setText("Sem conexão com o módulo da empresa. Os dados locais do motorista continuam funcionando."));
            }
        });
    }

    private boolean isLandscape() { return getResources().getDisplayMetrics().widthPixels > getResources().getDisplayMetrics().heightPixels; }
    private int dp(float v) { return PremiumUi.dp(this, v); }
    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h));
        p.setMargins(dp(l), dp(t), dp(r), dp(b)); return p;
    }
    private void margins(View v, int l, int t, int r, int b) {
        if (!(v.getLayoutParams() instanceof LinearLayout.LayoutParams)) return;
        LinearLayout.LayoutParams p = (LinearLayout.LayoutParams)v.getLayoutParams(); p.setMargins(dp(l),dp(t),dp(r),dp(b)); v.setLayoutParams(p);
    }
    private static int blend(int a, int b, float x) {
        x = Math.max(0f, Math.min(1f, x));
        return Color.rgb(Math.round(Color.red(a)*(1f-x)+Color.red(b)*x), Math.round(Color.green(a)*(1f-x)+Color.green(b)*x), Math.round(Color.blue(a)*(1f-x)+Color.blue(b)*x));
    }

    @Override protected void onDestroy() { io.shutdownNow(); super.onDestroy(); }
}

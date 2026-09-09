package com.estradaplay.comunista;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Premium collective-road review and privacy controls. */
public final class CollectiveRoadActivity extends ComponentActivity {
    private LinearLayout page;
    private CollectiveRoadStore store;
    private EstradaTheme theme;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new CollectiveRoadStore(this);
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

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        page = PremiumUi.col(this);
        page.setPadding(dp(18), dp(18), dp(18), dp(30));
        page.setBackgroundColor(theme.background);
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        setContentView(UnifiedAppShell.wrap(this, "central", scroll));

        page.addView(PremiumUi.overline(this, "ESTRADA VIVA", theme.secondary));
        TextView title = PremiumUi.text(this, "Inteligência coletiva", 27, theme.text, true);
        page.addView(title);
        margin(title, 0, 5, 0, 4);
        TextView intro = PremiumUi.text(this,
                "O aparelho pode detectar irregularidades pelos sensores. Somente pequenos eventos e coordenadas podem ser sincronizados; vídeos nunca são enviados.",
                12, theme.muted, false);
        page.addView(intro);
        margin(intro, 0, 0, 0, 16);

        LinearLayout privacy = card();
        privacy.addView(PremiumUi.overline(this, "PRIVACIDADE E CONTRIBUIÇÃO", theme.secondary));

        CheckBox on = check("PARTICIPAR DA BASE COLETIVA",
                "Compartilha somente eventos necessários para melhorar os alertas da estrada.");
        on.setChecked(DriveSettings.collectiveEnabled(this));
        on.setOnCheckedChangeListener((button, value) -> DriveSettings.toggle(this, "collective_enabled", value));
        privacy.addView(on);

        CheckBox protect = check("PROTEGER VÍDEO DA DASHCAM EM IMPACTO",
                "Funciona apenas enquanto a dashcam estiver aberta e gravando.");
        protect.setChecked(DriveSettings.protectImpactVideo(this));
        protect.setOnCheckedChangeListener((button, value) -> DriveSettings.toggle(this, "protect_impact_video", value));
        privacy.addView(protect);

        TextView stats = PremiumUi.text(this,
                "Impactos detectados: " + store.impactCount() + "  ·  fila para sincronizar: " + store.queuedCount(),
                11, theme.success, true);
        privacy.addView(stats);
        margin(stats, 0, 10, 0, 0);
        page.addView(privacy, new LinearLayout.LayoutParams(-1, -2));

        TextView pendingTitle = PremiumUi.overline(this, "RADARES PARA CONFIRMAR DEPOIS", theme.warning);
        page.addView(pendingTitle);
        margin(pendingTitle, 0, 20, 0, 8);

        JSONArray pending = store.pending();
        if (pending.length() == 0) {
            LinearLayout empty = card();
            empty.addView(PremiumUi.text(this, "Nenhuma confirmação pendente", 15, theme.text, true));
            TextView detail = PremiumUi.text(this,
                    "Quando um radar for alertado, ele pode aparecer aqui para você confirmar quando estiver seguro.",
                    11, theme.muted, false);
            empty.addView(detail);
            margin(detail, 0, 5, 0, 0);
            page.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            return;
        }

        for (int i = 0; i < pending.length(); i++) {
            JSONObject item = pending.optJSONObject(i);
            if (item == null) continue;
            renderRadar(item);
        }
    }

    private void renderRadar(JSONObject item) {
        String id = item.optString("radar_id");
        String road = item.optString("road", "Radar");
        int limit = item.optInt("limit_kmh", 0);

        LinearLayout card = card();
        card.addView(PremiumUi.text(this,
                road + (limit > 0 ? "  ·  " + limit + " km/h" : ""),
                15, theme.text, true));

        long at = item.optLong("seen_at");
        if (at > 0) {
            String meta = new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(new Date(at));
            String source = item.optString("source", "").trim();
            if (!source.isEmpty()) meta += "  ·  " + source;
            TextView detail = PremiumUi.text(this, meta, 10, theme.muted, false);
            card.addView(detail);
            margin(detail, 0, 4, 0, 10);
        }

        LinearLayout actions = PremiumUi.row(this);
        Button yes = PremiumUi.button(this, "AINDA EXISTE", true);
        Button no = PremiumUi.button(this, "NÃO ESTÁ MAIS", false);
        no.setTextColor(theme.danger);
        actions.addView(yes, new LinearLayout.LayoutParams(0, dp(48), 1f));
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, dp(48), 1f);
        np.setMargins(dp(7), 0, 0, 0);
        actions.addView(no, np);
        card.addView(actions);

        yes.setOnClickListener(v -> {
            store.resolveRadar(id, "exists");
            build();
        });
        no.setOnClickListener(v -> {
            store.resolveRadar(id, "removed");
            build();
        });

        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.setMargins(0, 0, 0, dp(8));
        page.addView(card, cp);
    }

    private CheckBox check(String title, String subtitle) {
        CheckBox box = new CheckBox(this);
        box.setText(title + "\n" + subtitle);
        box.setTextColor(theme.text);
        box.setTextSize(12);
        box.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        box.setPadding(0, dp(8), 0, dp(8));
        return box;
    }

    private LinearLayout card() {
        LinearLayout card = PremiumUi.col(this);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackground(PremiumUi.panel(this, theme.glass, theme.border, theme.radiusDp));
        return card;
    }

    private void margin(View view, int l, int t, int r, int b) {
        if (!(view.getLayoutParams() instanceof LinearLayout.LayoutParams)) return;
        LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) view.getLayoutParams();
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        view.setLayoutParams(p);
    }

    private int dp(float value) { return PremiumUi.dp(this, value); }
}

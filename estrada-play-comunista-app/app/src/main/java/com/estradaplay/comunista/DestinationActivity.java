package com.estradaplay.comunista;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Premium destination/search flow. */
public final class DestinationActivity extends ComponentActivity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private EstradaTheme theme;
    private EditText query;
    private Button search;
    private ProgressBar progress;
    private TextView status;

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

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(theme.background);
        LinearLayout page = PremiumUi.col(this);
        page.setPadding(dp(20), dp(22), dp(20), dp(28));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        setContentView(UnifiedAppShell.wrap(this, "trip", scroll));

        TextView over = PremiumUi.overline(this, "ROTA", theme.secondary);
        page.addView(over);
        TextView title = PremiumUi.text(this, "Para onde vamos?", 30, theme.text, true);
        page.addView(title);
        margins(title, 0, 6, 0, 4);
        TextView body = PremiumUi.text(this,
                "O destino é opcional. Sem endereço, alertas, limites e proteção continuam funcionando normalmente.",
                13, theme.muted, false);
        page.addView(body);
        margins(body, 0, 0, 0, 18);

        LinearLayout card = PremiumUi.col(this);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(PremiumUi.panel(this, theme.glass, theme.border, theme.radiusDp + 2));
        card.addView(PremiumUi.overline(this, "BUSCAR DESTINO", theme.muted));

        query = new EditText(this);
        query.setHint("Rua, número, cidade ou lugar");
        query.setHintTextColor(theme.muted);
        query.setTextColor(theme.text);
        query.setTextSize(15);
        query.setSingleLine(true);
        query.setPadding(dp(14), 0, dp(14), 0);
        query.setBackground(PremiumUi.panel(this, theme.surfaceAlt, theme.border, theme.radiusDp));
        card.addView(query, new LinearLayout.LayoutParams(-1, dp(56)));
        margins(query, 0, 10, 0, 0);

        search = PremiumUi.button(this, "BUSCAR DESTINO", true);
        card.addView(search, new LinearLayout.LayoutParams(-1, dp(54)));
        margins(search, 0, 10, 0, 0);
        search.setOnClickListener(v -> search());

        progress = new ProgressBar(this);
        progress.setIndeterminateTintList(ColorStateList.valueOf(theme.primary));
        progress.setVisibility(View.GONE);
        card.addView(progress, new LinearLayout.LayoutParams(-1, dp(34)));
        margins(progress, 0, 8, 0, 0);

        status = PremiumUi.text(this,
                "Busca online quando necessária; favoritos e recentes ficam no aparelho.",
                11, theme.muted, false);
        status.setGravity(Gravity.CENTER);
        card.addView(status);
        margins(status, 0, 8, 0, 0);
        page.addView(card, new LinearLayout.LayoutParams(-1, -2));

        addQuickDestinations(page);

        Button passive = PremiumUi.button(this, "DIRIGIR SEM DESTINO", false);
        page.addView(passive, new LinearLayout.LayoutParams(-1, dp(54)));
        margins(passive, 0, 14, 0, 0);
        passive.setOnClickListener(v -> {
            DestinationStore.clear(this);
            openRoad();
        });

        DestinationStore.Destination current = DestinationStore.read(this);
        if (current != null) {
            TextView currentLabel = PremiumUi.overline(this, "DESTINO ATUAL", theme.secondary);
            page.addView(currentLabel);
            margins(currentLabel, 0, 20, 0, 6);

            LinearLayout currentCard = PremiumUi.col(this);
            currentCard.setPadding(dp(14), dp(14), dp(14), dp(14));
            currentCard.setBackground(PremiumUi.panel(this, theme.surfaceAlt, theme.border, theme.radiusDp));
            currentCard.addView(PremiumUi.text(this, current.label, 14, theme.text, true));
            if (RouteOfflineCache.hasPreparedFor(this, current.lat, current.lon)) {
                TextView offline = PremiumUi.text(this,
                        "✓ Rota preparada para recuperação offline", 10, theme.success, true);
                currentCard.addView(offline);
                margins(offline, 0, 7, 0, 0);
            }
            page.addView(currentCard, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private void addQuickDestinations(LinearLayout page) {
        ArrayList<FavoriteRoadStore.Item> favorites = FavoriteRoadStore.list(this);
        ArrayList<DestinationStore.Destination> recent = RecentDestinationStore.list(this);
        if (favorites.isEmpty() && recent.isEmpty()) return;

        TextView quick = PremiumUi.overline(this, "ATALHOS", theme.secondary);
        page.addView(quick);
        margins(quick, 0, 20, 0, 7);

        int shown = 0;
        for (FavoriteRoadStore.Item x : favorites) {
            if (shown++ >= 4) break;
            String type = x.type == null || x.type.trim().isEmpty()
                    ? "FAVORITO" : x.type.trim().toUpperCase(Locale.ROOT);
            Button b = quickButton(type + "  ·  " + x.name, true);
            page.addView(b, new LinearLayout.LayoutParams(-1, dp(50)));
            margins(b, 0, 0, 0, 7);
            b.setOnClickListener(v -> use(new DestinationStore.Destination(x.name, x.lat, x.lon)));
        }

        int recentShown = 0;
        for (DestinationStore.Destination d : recent) {
            if (recentShown >= 4) break;
            boolean duplicate = false;
            for (FavoriteRoadStore.Item x : favorites) {
                if (RouteEngine.distanceM(x.lat, x.lon, d.lat, d.lon) < 80) {
                    duplicate = true;
                    break;
                }
            }
            if (duplicate) continue;
            recentShown++;
            Button b = quickButton("RECENTE  ·  " + d.label, false);
            page.addView(b, new LinearLayout.LayoutParams(-1, dp(48)));
            margins(b, 0, 0, 0, 6);
            b.setOnClickListener(v -> use(d));
        }
    }

    private Button quickButton(String value, boolean favorite) {
        Button b = PremiumUi.button(this, value, false);
        b.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        b.setPadding(dp(14), 0, dp(12), 0);
        b.setTextColor(favorite ? theme.text : theme.muted);
        b.setBackground(PremiumUi.panel(this,
                favorite ? theme.surfaceAlt : theme.surface,
                favorite ? theme.secondary : theme.border,
                theme.radiusDp));
        return b;
    }

    private void use(DestinationStore.Destination d) {
        DestinationStore.save(this, d);
        if (status != null) status.setText("Destino definido. Abrindo a estrada…");
        openRoad();
    }

    private void search() {
        String q = query.getText().toString().trim();
        if (q.length() < 3) {
            status.setText("Digite pelo menos 3 caracteres.");
            return;
        }
        search.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        status.setText("Procurando no mapa…");
        io.execute(() -> {
            try {
                List<DestinationStore.Destination> results = DestinationResolver.search(q);
                ui.post(() -> showResults(results));
            } catch (Exception e) {
                ui.post(() -> {
                    search.setEnabled(true);
                    progress.setVisibility(View.GONE);
                    status.setText("Não consegui buscar agora. Você ainda pode usar favoritos e destinos recentes.");
                });
            }
        });
    }

    private void showResults(List<DestinationStore.Destination> results) {
        search.setEnabled(true);
        progress.setVisibility(View.GONE);
        if (results == null || results.isEmpty()) {
            status.setText("Nenhum endereço encontrado.");
            return;
        }
        String[] labels = new String[results.size()];
        for (int i = 0; i < results.size(); i++) labels[i] = results.get(i).label;
        new AlertDialog.Builder(this)
                .setTitle("Escolha o destino")
                .setItems(labels, (dialog, which) -> use(results.get(which)))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void openRoad() {
        Intent i = new Intent(this, RoadEntryActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
    }

    private void margins(View view, int l, int t, int r, int b) {
        if (!(view.getLayoutParams() instanceof LinearLayout.LayoutParams)) return;
        LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) view.getLayoutParams();
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        view.setLayoutParams(p);
    }

    private int dp(float value) { return PremiumUi.dp(this, value); }

    @Override protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}

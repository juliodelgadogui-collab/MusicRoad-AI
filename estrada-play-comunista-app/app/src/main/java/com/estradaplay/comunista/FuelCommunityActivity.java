package com.estradaplay.comunista;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class FuelCommunityActivity extends ComponentActivity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LinearLayout page;
    private FuelCommunityStore store;
    private EstradaTheme theme;
    private double lat = Double.NaN;
    private double lon = Double.NaN;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new FuelCommunityStore(this);
        seed();
        build();
    }

    @Override protected void onResume() {
        super.onResume();
        EstradaTheme current = EstradaTheme.get(this);
        if (theme != null && !current.name.equals(theme.name)) build();
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void seed() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        try {
            LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE);
            Location best = null;
            for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                Location item = manager.getLastKnownLocation(provider);
                if (item != null && (best == null || item.getTime() > best.getTime())) best = item;
            }
            if (best != null) {
                lat = best.getLatitude();
                lon = best.getLongitude();
            }
        } catch (Throwable ignored) {}
    }

    private void build() {
        theme = EstradaTheme.get(this);
        getWindow().setStatusBarColor(theme.background);
        getWindow().setNavigationBarColor(theme.background);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        page = PremiumUi.col(this);
        page.setPadding(dp(18), dp(18), dp(18), dp(28));
        page.setBackgroundColor(theme.background);
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        setContentView(UnifiedAppShell.wrap(this, "central", scroll));

        page.addView(PremiumUi.overline(this, "COMBUSTÍVEL COMUNITÁRIO", theme.secondary));
        TextView title = PremiumUi.text(this, "Preço na estrada", 27, theme.text, true);
        page.addView(title);
        margin(title, 0, 5, 0, 3);
        TextView subtitle = PremiumUi.text(this,
                "Relatos recentes de motoristas. Confira o valor no posto antes de abastecer.",
                11, theme.muted, false);
        page.addView(subtitle);

        LinearLayout report = card();
        report.addView(PremiumUi.overline(this, "INFORMAR PREÇO", theme.warning));
        EditText station = field("Nome do posto ou referência", false);
        Spinner fuel = new Spinner(this);
        String[] fuels = {"Gasolina comum", "Gasolina aditivada", "Etanol", "Diesel S10", "Diesel S500", "GNV"};
        fuel.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, fuels));
        fuel.setBackground(PremiumUi.panel(this, theme.surfaceAlt, theme.border, theme.radiusDp));
        report.addView(fuel, new LinearLayout.LayoutParams(-1, dp(50)));
        margin(fuel, 0, 8, 0, 0);
        EditText price = field("Preço por litro/m³", true);
        report.addView(station);
        report.addView(price);

        Button send = PremiumUi.button(this, "PUBLICAR PREÇO", true);
        add(report, send, 0, 10, 0, 0, -1, dp(54));
        send.setOnClickListener(v -> {
            if (!Double.isFinite(lat) || !Double.isFinite(lon)) {
                toast("Ainda não tenho uma posição GPS válida.");
                return;
            }
            String name = station.getText().toString().trim();
            double value;
            try {
                value = Double.parseDouble(price.getText().toString().trim().replace(',', '.'));
            } catch (Throwable e) {
                toast("Informe um preço válido.");
                return;
            }
            if (name.length() < 2 || value < 1 || value > 20) {
                toast("Confira o nome do posto e o preço.");
                return;
            }
            store.report(lat, lon, name, String.valueOf(fuel.getSelectedItem()), value);
            toast("Preço salvo. Sincronizando…");
            refresh(true);
        });
        add(page, report, 0, 14, 0, 14, -1, -2);

        LinearLayout queue = card();
        queue.addView(PremiumUi.text(this,
                store.queued() > 0 ? store.queued() + " relato(s) aguardando internet" : "Fila de envio vazia",
                11, store.queued() > 0 ? theme.warning : theme.success, true));
        Button refresh = PremiumUi.button(this, "ATUALIZAR PREÇOS PRÓXIMOS", false);
        add(queue, refresh, 0, 8, 0, 0, -1, dp(50));
        refresh.setOnClickListener(v -> refresh(false));
        add(page, queue, 0, 0, 0, 14, -1, -2);

        page.addView(PremiumUi.overline(this, "MENORES PREÇOS PRÓXIMOS", theme.muted));
        if (!Double.isFinite(lat) || !Double.isFinite(lon)) {
            TextView noGps = PremiumUi.text(this, "GPS ainda não disponível.", 12, theme.muted, false);
            page.addView(noGps);
            margin(noGps, 0, 7, 0, 0);
            return;
        }

        ArrayList<FuelCommunityStore.Price> rows = store.cached(lat, lon);
        if (rows.isEmpty()) {
            TextView empty = PremiumUi.text(this,
                    "Nenhum preço comunitário em cache. Toque em atualizar.",
                    12, theme.muted, false);
            page.addView(empty);
            margin(empty, 0, 7, 0, 0);
        }

        for (int i = 0; i < Math.min(24, rows.size()); i++) {
            FuelCommunityStore.Price item = rows.get(i);
            LinearLayout card = card();
            LinearLayout line = PremiumUi.row(this);
            line.addView(PremiumUi.text(this, item.station, 16, theme.text, true),
                    new LinearLayout.LayoutParams(0, -2, 1f));
            line.addView(PremiumUi.text(this,
                    String.format(Locale.getDefault(), "R$ %.2f", item.price),
                    18, theme.success, true));
            card.addView(line);

            String detail = item.fuel;
            if (Double.isFinite(item.distanceM)) {
                detail += " · " + (item.distanceM >= 1000
                        ? String.format(Locale.getDefault(), "%.1f km", item.distanceM / 1000.0)
                        : Math.round(item.distanceM) + " m");
            }
            if (item.at > 0) {
                detail += " · " + new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
                        .format(new Date(item.at));
            }
            TextView meta = PremiumUi.text(this, detail, 11, theme.muted, false);
            card.addView(meta);
            add(page, card, 0, 7, 0, 0, -1, -2);
        }
    }

    private void refresh(boolean afterReport) {
        if (!Double.isFinite(lat) || !Double.isFinite(lon)) {
            toast("Sem GPS válido.");
            return;
        }
        io.execute(() -> {
            boolean ok = store.sync(new ApiClient(getApplicationContext()), lat, lon);
            runOnUiThread(() -> {
                if (!afterReport) toast(ok ? "Preços atualizados." : "Não consegui atualizar agora; mantendo o cache.");
                build();
            });
        });
    }

    private EditText field(String hint, boolean number) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextColor(theme.text);
        e.setHintTextColor(theme.muted);
        e.setPadding(dp(12), 0, dp(12), 0);
        e.setBackground(PremiumUi.panel(this, theme.surfaceAlt, theme.border, theme.radiusDp));
        if (number) e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(52));
        p.setMargins(0, dp(8), 0, 0);
        e.setLayoutParams(p);
        return e;
    }

    private LinearLayout card() {
        LinearLayout c = PremiumUi.col(this);
        c.setPadding(dp(15), dp(14), dp(15), dp(14));
        c.setBackground(PremiumUi.panel(this, theme.glass, theme.border, theme.radiusDp));
        return c;
    }

    private void add(LinearLayout parent, View view, int l, int t, int r, int b, int w, int h) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        parent.addView(view, p);
    }

    private void margin(View view, int l, int t, int r, int b) {
        if (!(view.getLayoutParams() instanceof LinearLayout.LayoutParams)) return;
        LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) view.getLayoutParams();
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        view.setLayoutParams(p);
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_LONG).show();
    }

    private int dp(float value) { return PremiumUi.dp(this, value); }
}

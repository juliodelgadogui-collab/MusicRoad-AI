package com.estradaplay.comunista;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;

import java.util.Locale;

/** Roadside SOS with local coordinates, road context and shareable rescue message. */
public final class EmergencyActivity extends ComponentActivity {
    private double lat = Double.NaN;
    private double lon = Double.NaN;
    private double roadKm = Double.NaN;
    private String road = "";
    private TextView roadText;
    private TextView coordText;
    private boolean receiverRegistered;
    private EstradaTheme theme;

    private final BroadcastReceiver roadReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            double a = intent.getDoubleExtra("lat", Double.NaN);
            double b = intent.getDoubleExtra("lon", Double.NaN);
            if (Double.isFinite(a) && Double.isFinite(b)) {
                lat = a;
                lon = b;
            }
            String r = intent.getStringExtra("road");
            if (r != null && !r.trim().isEmpty()) road = r.trim();
            double km = intent.getDoubleExtra("road_km", Double.NaN);
            if (Double.isFinite(km) && km >= 0) roadKm = km;
            refreshContext();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        seed();
        build();
    }

    @Override protected void onStart() {
        super.onStart();
        registerRoad();
    }

    @Override protected void onResume() {
        super.onResume();
        EstradaTheme current = EstradaTheme.get(this);
        if (theme != null && !current.name.equals(theme.name)) build();
    }

    @Override protected void onStop() {
        unregisterRoad();
        super.onStop();
    }

    private void seed() {
        road = KnownRoadCatalog.selected(this);
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
        LinearLayout page = PremiumUi.col(this);
        page.setPadding(dp(18), dp(18), dp(18), dp(28));
        page.setBackgroundColor(theme.background);
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        setContentView(UnifiedAppShell.wrap(this, "central", scroll));

        page.addView(PremiumUi.overline(this, "SOS RODOVIÁRIO", theme.danger));
        TextView title = PremiumUi.text(this, "Ajuda na estrada", 28, theme.text, true);
        page.addView(title);
        margin(title, 0, 5, 0, 4);
        TextView subtitle = PremiumUi.text(this,
                "Use somente quando estiver parado em local seguro sempre que possível.",
                11, theme.muted, false);
        page.addView(subtitle);
        margin(subtitle, 0, 0, 0, 16);

        LinearLayout location = card();
        location.addView(PremiumUi.overline(this,
                Double.isFinite(lat) ? "LOCALIZAÇÃO PRONTA" : "AGUARDANDO GPS",
                Double.isFinite(lat) ? theme.success : theme.warning));
        roadText = PremiumUi.text(this, "", 18, theme.text, true);
        coordText = PremiumUi.text(this, "", 11, theme.muted, false);
        location.addView(roadText);
        location.addView(coordText);
        margin(coordText, 0, 4, 0, 0);

        Button share = PremiumUi.button(this, "SOS · COMPARTILHAR LOCALIZAÇÃO", true);
        share.setTextColor(theme.text);
        location.addView(share, new LinearLayout.LayoutParams(-1, dp(56)));
        margin(share, 0, 12, 0, 0);
        share.setOnClickListener(v -> share());

        Button copy = PremiumUi.button(this, "COPIAR COORDENADAS", false);
        location.addView(copy, new LinearLayout.LayoutParams(-1, dp(48)));
        margin(copy, 0, 8, 0, 0);
        copy.setOnClickListener(v -> copy());
        page.addView(location, new LinearLayout.LayoutParams(-1, -2));
        refreshContext();

        TextView phoneTitle = PremiumUi.overline(this, "TELEFONES DE EMERGÊNCIA", theme.muted);
        page.addView(phoneTitle);
        margin(phoneTitle, 0, 18, 0, 7);
        LinearLayout calls = card();
        addCall(calls, "PRF · 191", "191", true);
        addCall(calls, "SAMU · 192", "192", false);
        addCall(calls, "BOMBEIROS · 193", "193", false);
        addCall(calls, "POLÍCIA · 190", "190", false);
        page.addView(calls, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout services = card();
        TextView serviceTitle = PremiumUi.text(this,
                "Precisa de guincho, oficina, borracharia, posto ou hospital?",
                14, theme.text, true);
        services.addView(serviceTitle);
        Button near = PremiumUi.button(this, "VER SERVIÇOS PRÓXIMOS", false);
        services.addView(near, new LinearLayout.LayoutParams(-1, dp(50)));
        margin(near, 0, 10, 0, 0);
        near.setOnClickListener(v -> startActivity(new Intent(this, NearbyServicesActivity.class)));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.setMargins(0, dp(12), 0, 0);
        page.addView(services, sp);
    }

    private void addCall(LinearLayout parent, String label, String number, boolean first) {
        Button button = PremiumUi.button(this, label, false);
        parent.addView(button, new LinearLayout.LayoutParams(-1, dp(50)));
        if (!first) margin(button, 0, 7, 0, 0);
        button.setOnClickListener(v -> dial(number));
    }

    private void refreshContext() {
        if (roadText == null) return;
        String value = road == null || road.isEmpty() ? "Rodovia não identificada" : road;
        roadText.setText(value + (Double.isFinite(roadKm)
                ? " · km " + String.format(Locale.getDefault(), "%.1f", roadKm) : ""));
        coordText.setText(Double.isFinite(lat)
                ? String.format(Locale.US, "%.6f, %.6f", lat, lon)
                : "Abra a Estrada por alguns segundos para obter uma posição.");
    }

    private void share() {
        if (!Double.isFinite(lat) || !Double.isFinite(lon)) {
            toast("Ainda não tenho uma posição GPS válida.");
            return;
        }
        StringBuilder msg = new StringBuilder("Preciso de ajuda na estrada.");
        if (road != null && !road.isEmpty()) msg.append(" Rodovia: ").append(road).append('.');
        if (Double.isFinite(roadKm)) {
            msg.append(" Km aproximado: ")
                    .append(String.format(Locale.getDefault(), "%.1f", roadKm)).append('.');
        }
        msg.append(" Localização: ")
                .append(String.format(Locale.US, "%.6f, %.6f", lat, lon))
                .append(" https://maps.google.com/?q=")
                .append(String.format(Locale.US, "%.6f,%.6f", lat, lon));
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, msg.toString());
        startActivity(Intent.createChooser(intent, "Compartilhar SOS"));
    }

    private void copy() {
        if (!Double.isFinite(lat) || !Double.isFinite(lon)) {
            toast("Sem GPS válido.");
            return;
        }
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Localização Estrada Play",
                    String.format(Locale.US, "%.6f, %.6f", lat, lon)));
        }
        toast("Coordenadas copiadas.");
    }

    private void registerRoad() {
        if (receiverRegistered) return;
        try {
            IntentFilter filter = new IntentFilter(RoadSafetyService.ACTION_STATE);
            InternalBroadcasts.register(this, roadReceiver, filter);
            receiverRegistered = true;
        } catch (Throwable ignored) {}
    }

    private void unregisterRoad() {
        if (!receiverRegistered) return;
        try { unregisterReceiver(roadReceiver); } catch (Throwable ignored) {}
        receiverRegistered = false;
    }

    private void dial(String number) {
        try {
            startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number)));
        } catch (Throwable e) {
            toast("Não consegui abrir o discador.");
        }
    }

    private LinearLayout card() {
        LinearLayout card = PremiumUi.col(this);
        card.setPadding(dp(15), dp(14), dp(15), dp(14));
        card.setBackground(PremiumUi.panel(this, theme.glass, theme.border, theme.radiusDp));
        return card;
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

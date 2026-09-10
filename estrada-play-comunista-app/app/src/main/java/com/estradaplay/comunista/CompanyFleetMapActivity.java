package com.estradaplay.comunista;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Live company fleet map. It reuses the RoadMapView/MapLibre stack and company journey presence. */
public final class CompanyFleetMapActivity extends ComponentActivity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean loading = new AtomicBoolean(false);
    private EstradaTheme theme;
    private RoadMapView map;
    private LinearLayout list;
    private TextView status;
    private boolean resumed;

    private final Runnable refreshTick = new Runnable() {
        @Override public void run() {
            refresh();
            if (resumed) ui.postDelayed(this, 15_000L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!CompanyAccount.shouldUseCompanyCentral(this)) { finish(); return; }
        build();
    }

    private void build() {
        theme = EstradaTheme.get(this);
        getWindow().setStatusBarColor(theme.background);
        getWindow().setNavigationBarColor(theme.background);

        boolean landscape = getResources().getDisplayMetrics().widthPixels > getResources().getDisplayMetrics().heightPixels;
        LinearLayout root = PremiumUi.col(this);
        root.setBackgroundColor(theme.background);
        if (landscape) root.setOrientation(LinearLayout.HORIZONTAL);

        map = new RoadMapView(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout panel = PremiumUi.col(this);
        panel.setPadding(dp(16), dp(15), dp(16), dp(28));
        panel.setBackgroundColor(theme.background);
        scroll.addView(panel, new ScrollView.LayoutParams(-1, -2));

        if (landscape) {
            root.addView(map, new LinearLayout.LayoutParams(0, -1, 1.55f));
            root.addView(scroll, new LinearLayout.LayoutParams(0, -1, 1f));
        } else {
            int h = getResources().getDisplayMetrics().heightPixels;
            root.addView(map, new LinearLayout.LayoutParams(-1, Math.max(dp(280), (int)(h * .43f))));
            root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        }
        setContentView(root);

        Button back = PremiumUi.button(this, "‹ EMPRESA", false);
        back.setOnClickListener(v -> finish());
        panel.addView(back, new LinearLayout.LayoutParams(dp(110), dp(46)));

        TextView over = PremiumUi.overline(this, "MAPA DA FROTA", theme.secondary);
        panel.addView(over); margins(over,0,20,0,4);
        panel.addView(PremiumUi.text(this, CompanyAccount.companyName(this), 25, theme.text, true));
        TextView sub = PremiumUi.text(this,
                "Mostra somente posições recentes enviadas enquanto cada motorista está usando a Estrada.",
                10, theme.muted, false);
        panel.addView(sub); margins(sub,0,6,0,12);

        status = PremiumUi.text(this, "Carregando veículos em jornada…", 11, theme.muted, true);
        panel.addView(status);
        list = PremiumUi.col(this);
        panel.addView(list, new LinearLayout.LayoutParams(-1,-2));
        margins(list,0,10,0,0);
    }

    private void refresh() {
        if (!loading.compareAndSet(false, true)) return;
        io.execute(() -> {
            try {
                JSONObject response = new CompanyApi(this).fleetMap();
                JSONArray vehicles = response.optJSONArray("vehicles");
                if (vehicles == null) vehicles = new JSONArray();
                int live = response.optInt("live_count", 0);
                JSONArray finalVehicles = vehicles;
                runOnUiThread(() -> render(finalVehicles, live));
            } catch (Throwable e) {
                runOnUiThread(() -> status.setText("Sem conexão com o mapa da frota agora."));
            } finally {
                loading.set(false);
            }
        });
    }

    private void render(JSONArray vehicles, int liveCount) {
        list.removeAllViews();
        ArrayList<ConvoyStore.Member> members = new ArrayList<>();
        JSONObject focus = null;
        for (int i=0;i<vehicles.length();i++) {
            JSONObject v = vehicles.optJSONObject(i);
            if (v == null) continue;
            double lat = v.optDouble("lat", Double.NaN);
            double lon = v.optDouble("lon", Double.NaN);
            if (!Double.isFinite(lat) || !Double.isFinite(lon)) continue;
            boolean online = v.optBoolean("online", false);
            if (focus == null || (online && !focus.optBoolean("online", false))) focus = v;
        }

        int focusUserId = focus == null ? -1 : focus.optInt("user_id", -1);
        for (int i=0;i<vehicles.length();i++) {
            JSONObject v = vehicles.optJSONObject(i);
            if (v == null) continue;
            double lat = v.optDouble("lat", Double.NaN), lon = v.optDouble("lon", Double.NaN);
            if (!Double.isFinite(lat) || !Double.isFinite(lon)) continue;
            boolean online = v.optBoolean("online", false);
            long age = Math.max(0L, v.optLong("age_s", 0L));
            float heading = (float)v.optDouble("heading", Double.NaN);
            String vehicle = vehicleLabel(v);
            String driver = v.optString("driver_name", "Motorista").trim();
            boolean self = v.optInt("user_id", -2) == focusUserId;
            members.add(new ConvoyStore.Member(
                    "fleet-" + v.optInt("vehicle_id", i),
                    vehicle,
                    lat, lon,
                    v.optDouble("speed_kmh", 0.0),
                    heading,
                    System.currentTimeMillis() - age * 1000L,
                    age,
                    self, false,
                    online ? "ONLINE" : "ATRASADO",
                    Double.NaN, "", Double.NaN, false, Double.NaN, 0L));
            addVehicleRow(v, vehicle, driver, online);
        }

        map.setConvoyMembers(members);
        if (focus != null) {
            map.setUserLocation(focus.optDouble("lat"), focus.optDouble("lon"), focus.optDouble("heading", 0.0));
            status.setText(liveCount + " veículo(s) em jornada agora · " + vehicles.length() + " posição(ões) recentes");
        } else {
            status.setText("Nenhum veículo enviou uma posição recente.");
        }
    }

    private void addVehicleRow(JSONObject v, String vehicle, String driver, boolean online) {
        LinearLayout row = PremiumUi.col(this);
        row.setPadding(dp(13),dp(11),dp(13),dp(11));
        row.setBackground(PremiumUi.panel(this, theme.surfaceAlt, theme.border, theme.radiusDp));
        LinearLayout top = PremiumUi.row(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(PremiumUi.text(this, vehicle, 13, theme.text, true), new LinearLayout.LayoutParams(0,-2,1f));
        TextView speed = PremiumUi.text(this, Math.round(v.optDouble("speed_kmh",0.0)) + " km/h", 10,
                online ? theme.success : theme.muted, true);
        speed.setGravity(Gravity.RIGHT);
        top.addView(speed, new LinearLayout.LayoutParams(dp(82),-2));
        row.addView(top);
        row.addView(PremiumUi.text(this, driver, 10, theme.muted, false));
        TextView km = PremiumUi.text(this, String.format(Locale.getDefault(), "Hoje · %.1f km", v.optDouble("km_today",0.0)), 10, theme.secondary, true);
        row.addView(km); margins(km,0,4,0,0);
        TextView presence = PremiumUi.text(this, online ? "NA ESTRADA" : "ÚLTIMA POSIÇÃO RECENTE", 9,
                online ? theme.success : theme.muted, true);
        row.addView(presence); margins(presence,0,4,0,0);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,0,0,dp(7)); list.addView(row,p);
    }

    private String vehicleLabel(JSONObject v) {
        String nick = v.optString("nickname", "").trim();
        String plate = v.optString("plate", "").trim();
        String model = v.optString("model", "").trim();
        if (!nick.isEmpty()) return nick + (plate.isEmpty() ? "" : " · " + plate);
        if (!plate.isEmpty()) return plate;
        return model.isEmpty() ? "Veículo" : model;
    }

    @Override protected void onStart() { super.onStart(); if (map != null) map.onStartMap(); }
    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (map != null) map.onResumeMap();
        ui.removeCallbacks(refreshTick);
        ui.post(refreshTick);
    }
    @Override protected void onPause() {
        resumed = false;
        ui.removeCallbacks(refreshTick);
        if (map != null) map.onPauseMap();
        super.onPause();
    }
    @Override protected void onStop() { if (map != null) map.onStopMap(); super.onStop(); }
    @Override public void onLowMemory() { super.onLowMemory(); if (map != null) map.onLowMemoryMap(); }
    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (map != null) map.onDestroyMap();
        io.shutdownNow();
        super.onDestroy();
    }
    @Override protected void onSaveInstanceState(Bundle out) { if (map != null) map.onSaveMap(out); super.onSaveInstanceState(out); }

    private int dp(float v){return PremiumUi.dp(this,v);}
    private void margins(View v,int l,int t,int r,int b){if(!(v.getLayoutParams() instanceof LinearLayout.LayoutParams))return;LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)v.getLayoutParams();p.setMargins(dp(l),dp(t),dp(r),dp(b));v.setLayoutParams(p);}
}

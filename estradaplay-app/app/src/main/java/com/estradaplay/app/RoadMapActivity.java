package com.estradaplay.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RoadMapActivity extends ComponentActivity {
    private static final String UI_PREFS = "estradaplay_ui_v1";
    private static final String KEY_ACCOUNT = "account";

    private final int BG = Color.rgb(6, 8, 12);
    private final int PANEL = Color.rgb(12, 17, 23);
    private final int BORDER = Color.rgb(37, 49, 61);
    private final int TEXT = Color.rgb(245, 248, 252);
    private final int MUTED = Color.rgb(145, 157, 170);
    private final int ACCENT = Color.rgb(255, 107, 44);
    private final int GREEN = Color.rgb(69, 212, 131);
    private final int BLUE = Color.rgb(93, 169, 255);

    private FrameLayout root;
    private RoadMapView roadMap;
    private TextView speedText;
    private TextView protectionText;
    private TextView detailText;
    private TextView hazardText;
    private TextView mapStateText;
    private RoadPackStore mapStore;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile long lastHazardRefreshAt;
    private volatile int loadedHazardCount = -1;
    private double lastLat = Double.NaN;
    private double lastLon = Double.NaN;
    private double lastHeading = 0.0;

    private final BroadcastReceiver roadReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            double lat = intent.getDoubleExtra("lat", Double.NaN);
            double lon = intent.getDoubleExtra("lon", Double.NaN);
            double heading = intent.getFloatExtra("heading", -1f);
            double speed = intent.getDoubleExtra("speed_kmh", 0);
            int count = intent.getIntExtra("hazard_count", 0);
            String status = intent.getStringExtra("status");
            String hazard = intent.getStringExtra("hazard_label");
            String road = intent.getStringExtra("road");
            double distance = intent.getDoubleExtra("distance_m", 0);
            int limit = intent.getIntExtra("limit_kmh", 0);

            if (Double.isFinite(lat) && Double.isFinite(lon)) {
                lastLat = lat; lastLon = lon;
                if (heading >= 0) lastHeading = heading;
                if (roadMap != null) roadMap.setUserLocation(lat, lon, lastHeading);
                refreshHazards(lat, lon, count);
            }

            if (speedText != null) speedText.setText(String.valueOf(Math.max(0, Math.round(speed))));
            if (protectionText != null) protectionText.setText(hazard == null || hazard.trim().isEmpty() ? "Proteção ativa" : hazard + " à frente");
            if (detailText != null) {
                String value = status == null ? "GPS monitorando sua direção" : status.trim();
                if (road != null && !road.trim().isEmpty()) value = road.trim() + " · " + value;
                detailText.setText(value);
            }
            if (hazardText != null) {
                if (hazard == null || hazard.trim().isEmpty()) {
                    hazardText.setVisibility(View.GONE);
                } else {
                    StringBuilder h = new StringBuilder(hazard);
                    if (distance > 0) h.append(" · ").append(distance >= 1000 ? String.format(Locale.getDefault(), "%.1f km", distance / 1000.0) : Math.round(distance) + " m");
                    if (limit > 0) h.append(" · ").append(limit).append(" km/h");
                    hazardText.setText(h.toString());
                    hazardText.setVisibility(View.VISIBLE);
                }
            }
            if (mapStateText != null && roadMap != null) mapStateText.setText(roadMap.status());
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(BG);

        if (!hasAccount()) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }
        if (!hasLocation()) {
            startActivity(new Intent(this, GateActivity.class));
            finish();
            return;
        }

        startSafety();
        buildUi();
        registerRoadReceiver();
        seedLocation();
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        setContentView(root);

        roadMap = new RoadMapView(this);
        root.addView(roadMap, new FrameLayout.LayoutParams(-1, -1));

        int safeTop = statusBarHeight() + dp(8);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(10), dp(12), dp(10));
        header.setBackground(panel(18, Color.argb(220, 10, 14, 19), BORDER));
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-1, dp(72));
        hp.setMargins(dp(12), safeTop, dp(12), 0);
        root.addView(header, hp);

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        TextView logo = label("EstradaPlay", 20, TEXT, true);
        TextView sub = label("MAPA · PROTEÇÃO AUTOMÁTICA", 9, GREEN, true);
        sub.setLetterSpacing(0.12f);
        brand.addView(logo);
        brand.addView(sub);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));

        TextView gps = label("● GPS", 10, GREEN, true);
        gps.setGravity(Gravity.CENTER);
        gps.setPadding(dp(11), 0, dp(11), 0);
        gps.setBackground(panel(100, Color.rgb(18, 54, 40), 0));
        header.addView(gps, new LinearLayout.LayoutParams(-2, dp(34)));

        LinearLayout speed = new LinearLayout(this);
        speed.setOrientation(LinearLayout.VERTICAL);
        speed.setGravity(Gravity.CENTER);
        speed.setBackground(panel(22, Color.argb(232, 10, 14, 19), BORDER));
        speedText = label("0", 34, TEXT, true); speedText.setGravity(Gravity.CENTER);
        TextView kmh = label("KM/H", 9, MUTED, true); kmh.setLetterSpacing(0.12f); kmh.setGravity(Gravity.CENTER);
        speed.addView(speedText);
        speed.addView(kmh);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(dp(92), dp(92), Gravity.TOP | Gravity.LEFT);
        sp.setMargins(dp(14), safeTop + dp(84), 0, 0);
        root.addView(speed, sp);

        Button recenter = compact("◎");
        recenter.setTextSize(20);
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(dp(54), dp(54), Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        rp.setMargins(0, 0, dp(14), dp(80));
        root.addView(recenter, rp);
        recenter.setOnClickListener(v -> { if (roadMap != null) roadMap.recenter(); });

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(16), dp(14), dp(16), dp(14));
        bottom.setBackground(panel(22, Color.argb(238, 10, 14, 19), BORDER));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        bp.setMargins(dp(12), 0, dp(12), navigationBarHeight() + dp(12));
        root.addView(bottom, bp);

        LinearLayout stateRow = new LinearLayout(this);
        stateRow.setOrientation(LinearLayout.HORIZONTAL);
        stateRow.setGravity(Gravity.CENTER_VERTICAL);
        protectionText = label("Proteção ativa", 19, TEXT, true);
        stateRow.addView(protectionText, new LinearLayout.LayoutParams(0, -2, 1));
        TextView offline = label("OFFLINE", 9, GREEN, true);
        offline.setGravity(Gravity.CENTER); offline.setPadding(dp(10), 0, dp(10), 0); offline.setBackground(panel(100, Color.rgb(18, 54, 40), 0));
        stateRow.addView(offline, new LinearLayout.LayoutParams(-2, dp(28)));
        bottom.addView(stateRow);

        detailText = label("GPS monitorando sua direção · sem destino", 11, MUTED, false);
        bottom.addView(detailText); setMargins(detailText, 0, 4, 0, 0);

        hazardText = label("", 13, ACCENT, true);
        hazardText.setVisibility(View.GONE);
        hazardText.setPadding(dp(12), dp(9), dp(12), dp(9));
        hazardText.setBackground(panel(13, Color.rgb(62, 31, 22), Color.rgb(120, 54, 31)));
        bottom.addView(hazardText); setMargins(hazardText, 0, 10, 0, 0);

        mapStateText = label("Carregando mapa…", 9, MUTED, false);
        bottom.addView(mapStateText); setMargins(mapStateText, 0, 8, 0, 0);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button music = action("♪  MÚSICA", true);
        Button app = action("INÍCIO", false);
        actions.addView(music, new LinearLayout.LayoutParams(0, dp(50), 1));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(0, dp(50), 1); ap.setMargins(dp(8), 0, 0, 0); actions.addView(app, ap);
        bottom.addView(actions); setMargins(actions, 0, 12, 0, 0);
        music.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));
        app.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));
    }

    private void refreshHazards(double lat, double lon, int reportedCount) {
        long now = System.currentTimeMillis();
        if (now - lastHazardRefreshAt < 2500L) return;
        lastHazardRefreshAt = now;
        io.execute(() -> {
            try {
                if (mapStore == null || loadedHazardCount != reportedCount) {
                    mapStore = new RoadPackStore(this);
                    loadedHazardCount = mapStore.hazardCount();
                }
                List<RoadHazard> nearby = mapStore.nearby(lat, lon, 5200);
                ui.post(() -> { if (roadMap != null) roadMap.setHazards(nearby); });
            } catch (Throwable ignored) {}
        });
    }

    private void seedLocation() {
        try {
            LocationManager lm = (LocationManager)getSystemService(LOCATION_SERVICE);
            if (lm == null || !hasLocation()) return;
            Location best = null;
            for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                try {
                    Location l = lm.getLastKnownLocation(provider);
                    if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                } catch (Throwable ignored) {}
            }
            if (best != null) {
                lastLat = best.getLatitude(); lastLon = best.getLongitude();
                if (best.hasBearing()) lastHeading = best.getBearing();
                roadMap.setUserLocation(lastLat, lastLon, lastHeading);
                refreshHazards(lastLat, lastLon, 0);
            }
        } catch (Throwable ignored) {}
    }

    private void registerRoadReceiver() {
        IntentFilter f = new IntentFilter(RoadSafetyService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(roadReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(roadReceiver, f);
    }

    private void startSafety() {
        try {
            Intent i = new Intent(this, RoadSafetyService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } catch (Throwable ignored) {}
    }

    private boolean hasAccount() {
        try {
            SharedPreferences p = getSharedPreferences(UI_PREFS, MODE_PRIVATE);
            JSONObject a = new JSONObject(p.getString(KEY_ACCOUNT, "{}"));
            return a.optBoolean("authenticated", false) || a.optJSONObject("user") != null;
        } catch (Throwable e) { return false; }
    }

    private boolean hasLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private Button action(String value, boolean primary) {
        Button b = new Button(this);
        b.setText(value); b.setAllCaps(false); b.setTextSize(10); b.setLetterSpacing(0.08f); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(Color.WHITE); b.setStateListAnimator(null);
        b.setBackground(panel(15, primary ? ACCENT : Color.rgb(24, 31, 40), primary ? 0 : BORDER));
        return b;
    }

    private Button compact(String value) {
        Button b = action(value, false); b.setTextColor(TEXT); return b;
    }

    private TextView label(String value, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setGravity(Gravity.CENTER_VERTICAL); t.setLineSpacing(0, 1.06f);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private GradientDrawable panel(int radius, int color, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(radius));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private void setMargins(View v, int l, int t, int r, int b) {
        ViewGroup.LayoutParams raw = v.getLayoutParams();
        if (!(raw instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams p = (ViewGroup.MarginLayoutParams)raw;
        p.setMargins(dp(l), dp(t), dp(r), dp(b)); v.setLayoutParams(p);
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(28);
    }

    private int navigationBarHeight() {
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(18);
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        try { unregisterReceiver(roadReceiver); } catch (Throwable ignored) {}
        io.shutdownNow();
        super.onDestroy();
    }
}

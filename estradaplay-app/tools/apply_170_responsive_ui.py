from pathlib import Path

root = Path(__file__).resolve().parents[1]

java = r'''package com.estradaplay.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ADAPTIVE_COCKPIT_V170
 *
 * EstradaPlay 1.7 cockpit built from scratch for landscape automotive displays.
 * The old 1.5/1.6 fixed-position composition is intentionally not reused.
 */
public final class RoadMapActivity extends ComponentActivity {
    private static final String UI_PREFS = "estradaplay_ui_v1";
    private static final String KEY_ACCOUNT = "account";

    private final int BG = Color.rgb(5, 8, 12);
    private final int SURFACE = Color.rgb(12, 17, 23);
    private final int SURFACE_2 = Color.rgb(18, 24, 32);
    private final int BORDER = Color.rgb(40, 51, 64);
    private final int TEXT = Color.rgb(244, 247, 250);
    private final int MUTED = Color.rgb(145, 157, 170);
    private final int ACCENT = Color.rgb(255, 111, 48);
    private final int ACCENT_SOFT = Color.rgb(66, 34, 23);
    private final int GREEN = Color.rgb(72, 212, 134);

    private FrameLayout root;
    private RoadMapView roadMap;
    private TextView speedText;
    private TextView hazardTitle;
    private TextView hazardDetail;
    private TextView protectionText;
    private TextView mapStateText;
    private TextView clockText;
    private TextView gpsText;

    private RoadPackStore mapStore;
    private OfflineRoadStore offlineRoadStore;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private volatile long lastHazardRefreshAt;
    private volatile int loadedHazardCount = -1;
    private volatile long loadedRoadRevision = Long.MIN_VALUE;
    private double lastLat = Double.NaN;
    private double lastLon = Double.NaN;
    private double lastHeading;
    private int lastWidth;
    private int lastHeight;
    private boolean receiverRegistered;

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            if (clockText != null) {
                clockText.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
            }
            ui.postDelayed(this, 30000L);
        }
    };

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
                lastLat = lat;
                lastLon = lon;
                if (heading >= 0) lastHeading = heading;
                if (roadMap != null) roadMap.setUserLocation(lat, lon, lastHeading);
                refreshMapData(lat, lon, count);
            }

            if (speedText != null) speedText.setText(String.valueOf(Math.max(0, Math.round(speed))));
            if (gpsText != null) gpsText.setText(Double.isFinite(lat) ? "GPS ATIVO" : "GPS BUSCANDO");

            boolean hasHazard = hazard != null && !hazard.trim().isEmpty();
            if (protectionText != null) protectionText.setText(hasHazard ? "ATENÇÃO À FRENTE" : "PROTEÇÃO ATIVA");
            if (hazardTitle != null) hazardTitle.setText(hasHazard ? hazard.trim() : "Estrada livre à frente");
            if (hazardDetail != null) {
                String value;
                if (hasHazard) {
                    StringBuilder d = new StringBuilder();
                    if (distance > 0) d.append(distance >= 1000 ? String.format(Locale.getDefault(), "%.1f km", distance / 1000.0) : Math.round(distance) + " m");
                    if (limit > 0) {
                        if (d.length() > 0) d.append("  ·  ");
                        d.append(limit).append(" km/h");
                    }
                    if (road != null && !road.trim().isEmpty()) {
                        if (d.length() > 0) d.append("  ·  ");
                        d.append(road.trim());
                    }
                    value = d.length() == 0 ? "Alerta rodoviário detectado" : d.toString();
                } else {
                    value = status == null || status.trim().isEmpty() ? "Monitorando sua direção e a estrada" : status.trim();
                }
                hazardDetail.setText(value);
            }
            updateMapStatus();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        if (!hasAccount()) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }
        if (!hasLocation()) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }

        offlineRoadStore = new OfflineRoadStore(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        setContentView(root);

        buildResponsiveUi();
        startSafety();
        registerRoadReceiver();
        seedLocation();
        ui.removeCallbacks(clockTick);
        ui.post(clockTick);

        root.post(() -> {
            int w = root.getWidth();
            int h = root.getHeight();
            if (w > 0 && h > 0 && (Math.abs(w - lastWidth) > dp(40) || Math.abs(h - lastHeight) > dp(40))) {
                buildResponsiveUi();
                if (Double.isFinite(lastLat) && Double.isFinite(lastLon) && roadMap != null) {
                    roadMap.setUserLocation(lastLat, lastLon, lastHeading);
                    refreshMapData(lastLat, lastLon, 0);
                }
            }
        });
    }

    private void buildResponsiveUi() {
        int[] size = screenSize();
        int width = size[0];
        int height = size[1];
        lastWidth = width;
        lastHeight = height;

        if (roadMap != null) {
            try { roadMap.onPauseMap(); roadMap.onStopMap(); roadMap.onDestroyMap(); } catch (Throwable ignored) {}
        }
        root.removeAllViews();

        float ratio = height <= 0 ? 1.8f : (float)width / (float)height;
        boolean compact = ratio < 1.64f || height < dp(420);
        boolean ultrawide = ratio >= 2.12f;

        int outer = clamp(Math.round(height * 0.022f), dp(8), dp(18));
        int gap = clamp(Math.round(height * 0.016f), dp(7), dp(14));
        int railWidth = clamp(Math.round(width * (compact ? 0.092f : 0.078f)), dp(70), dp(112));
        int rightWidth = compact
                ? clamp(Math.round(width * 0.285f), dp(210), dp(330))
                : ultrawide
                ? clamp(Math.round(width * 0.275f), dp(280), dp(430))
                : clamp(Math.round(width * 0.255f), dp(240), dp(370));

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.HORIZONTAL);
        shell.setGravity(Gravity.CENTER_VERTICAL);
        shell.setPadding(outer, outer, outer, outer);
        root.addView(shell, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout rail = buildRail(height, compact);
        shell.addView(rail, new LinearLayout.LayoutParams(railWidth, -1));

        FrameLayout mapPane = buildMapPane(height, compact);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -1, 1f);
        mp.setMargins(gap, 0, gap, 0);
        shell.addView(mapPane, mp);

        LinearLayout right = buildRightPanel(height, compact, ultrawide);
        shell.addView(right, new LinearLayout.LayoutParams(rightWidth, -1));
    }

    private LinearLayout buildRail(int height, boolean compact) {
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = clamp(Math.round(height * 0.018f), dp(7), dp(14));
        rail.setPadding(pad, pad, pad, pad);
        rail.setBackground(panel(24, SURFACE, BORDER));

        TextView logo = label("EP", compact ? 18 : 21, TEXT, true);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(panel(18, ACCENT, 0));
        int logoSize = clamp(Math.round(height * 0.105f), dp(48), dp(68));
        rail.addView(logo, new LinearLayout.LayoutParams(-1, logoSize));

        TextView mode = label("DRIVE", 8, GREEN, true);
        mode.setGravity(Gravity.CENTER);
        mode.setLetterSpacing(0.15f);
        LinearLayout.LayoutParams modeP = new LinearLayout.LayoutParams(-1, -2);
        modeP.setMargins(0, dp(8), 0, dp(10));
        rail.addView(mode, modeP);

        int buttonH = clamp(Math.round(height * (compact ? 0.135f : 0.128f)), dp(50), dp(76));
        rail.addView(nav("MAPA", true, buttonH));
        LinearLayout.LayoutParams musicP = new LinearLayout.LayoutParams(-1, buttonH);
        musicP.setMargins(0, dp(8), 0, 0);
        Button music = nav("MÚSICA", false, buttonH);
        rail.addView(music, musicP);
        music.setOnClickListener(v -> openMain());

        LinearLayout.LayoutParams homeP = new LinearLayout.LayoutParams(-1, buttonH);
        homeP.setMargins(0, dp(8), 0, 0);
        Button home = nav("INÍCIO", false, buttonH);
        rail.addView(home, homeP);
        home.setOnClickListener(v -> openMain());

        View spacer = new View(this);
        rail.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        TextView online = label("●  ONLINE", 8, GREEN, true);
        online.setGravity(Gravity.CENTER);
        online.setPadding(dp(4), dp(8), dp(4), dp(8));
        rail.addView(online, new LinearLayout.LayoutParams(-1, -2));
        return rail;
    }

    private FrameLayout buildMapPane(int height, boolean compact) {
        FrameLayout pane = new FrameLayout(this);
        pane.setBackground(panel(26, SURFACE, BORDER));
        pane.setClipToPadding(true);

        roadMap = new RoadMapView(this);
        pane.addView(roadMap, new FrameLayout.LayoutParams(-1, -1));

        int inset = clamp(Math.round(height * 0.025f), dp(10), dp(18));
        int chipH = clamp(Math.round(height * 0.087f), dp(44), dp(62));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-1, chipH, Gravity.TOP);
        tp.setMargins(inset, inset, inset, 0);
        pane.addView(top, tp);

        LinearLayout speed = new LinearLayout(this);
        speed.setOrientation(LinearLayout.HORIZONTAL);
        speed.setGravity(Gravity.CENTER);
        speed.setPadding(dp(12), 0, dp(12), 0);
        speed.setBackground(panel(18, Color.argb(238, 9, 13, 18), BORDER));
        speedText = label("0", compact ? 24 : 30, TEXT, true);
        TextView kmh = label("  KM/H", 9, MUTED, true);
        speed.addView(speedText);
        speed.addView(kmh);
        top.addView(speed, new LinearLayout.LayoutParams(-2, -1));

        TextView drive = label("  ESTRADAPLAY  ·  CONDUÇÃO", compact ? 9 : 10, TEXT, true);
        drive.setLetterSpacing(0.08f);
        top.addView(drive, new LinearLayout.LayoutParams(0, -1, 1f));

        gpsText = label("GPS ATIVO", 9, GREEN, true);
        gpsText.setGravity(Gravity.CENTER);
        gpsText.setPadding(dp(11), 0, dp(11), 0);
        gpsText.setBackground(panel(100, Color.rgb(17, 52, 38), 0));
        top.addView(gpsText, new LinearLayout.LayoutParams(-2, Math.max(dp(32), chipH - dp(12))));

        Button recenter = action("CENTRALIZAR", false);
        int recW = compact ? dp(96) : dp(116);
        int recH = clamp(Math.round(height * 0.075f), dp(40), dp(54));
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(recW, recH, Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        rp.setMargins(0, 0, inset, 0);
        pane.addView(recenter, rp);
        recenter.setOnClickListener(v -> { if (roadMap != null) roadMap.recenter(); });

        LinearLayout alert = new LinearLayout(this);
        alert.setOrientation(LinearLayout.VERTICAL);
        alert.setGravity(Gravity.CENTER_VERTICAL);
        int apad = clamp(Math.round(height * 0.021f), dp(9), dp(15));
        alert.setPadding(dp(16), apad, dp(16), apad);
        alert.setBackground(panel(20, Color.argb(242, 10, 15, 20), BORDER));
        FrameLayout.LayoutParams ap = new FrameLayout.LayoutParams(compact ? -1 : Math.min(dp(480), Math.round(lastWidth * 0.42f)), -2, Gravity.LEFT | Gravity.BOTTOM);
        ap.setMargins(inset, 0, inset, inset);
        pane.addView(alert, ap);

        protectionText = label("PROTEÇÃO ATIVA", 8, GREEN, true);
        protectionText.setLetterSpacing(0.14f);
        alert.addView(protectionText);
        hazardTitle = label("Estrada livre à frente", compact ? 16 : 19, TEXT, true);
        LinearLayout.LayoutParams htp = new LinearLayout.LayoutParams(-1, -2);
        htp.setMargins(0, dp(3), 0, 0);
        alert.addView(hazardTitle, htp);
        hazardDetail = label("Monitorando sua direção e a estrada", compact ? 9 : 10, MUTED, false);
        LinearLayout.LayoutParams hdp = new LinearLayout.LayoutParams(-1, -2);
        hdp.setMargins(0, dp(3), 0, 0);
        alert.addView(hazardDetail, hdp);
        return pane;
    }

    private LinearLayout buildRightPanel(int height, boolean compact, boolean ultrawide) {
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        int pad = clamp(Math.round(height * 0.024f), dp(10), dp(18));
        right.setPadding(pad, pad, pad, pad);
        right.setBackground(panel(26, SURFACE, BORDER));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = label("EstradaPlay", compact ? 16 : 20, TEXT, true);
        TextView sub = label("SISTEMA AUTOMOTIVO", 8, MUTED, true);
        sub.setLetterSpacing(0.12f);
        titles.addView(title);
        titles.addView(sub);
        head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        clockText = label("--:--", compact ? 18 : 22, TEXT, true);
        clockText.setGravity(Gravity.CENTER);
        head.addView(clockText);
        right.addView(head);

        int cardGap = clamp(Math.round(height * 0.018f), dp(7), dp(12));
        LinearLayout statusCard = card();
        LinearLayout.LayoutParams scp = new LinearLayout.LayoutParams(-1, 0, compact ? 0.30f : 0.29f);
        scp.setMargins(0, cardGap, 0, 0);
        right.addView(statusCard, scp);
        TextView statusOver = label("SEGURANÇA", 8, GREEN, true);
        statusOver.setLetterSpacing(0.13f);
        statusCard.addView(statusOver);
        TextView statusTitle = label("Proteção rodoviária", compact ? 15 : 18, TEXT, true);
        statusCard.addView(statusTitle);
        TextView statusBody = label("Radar · semáforo · quebra-molas · pedágio · passagem de nível", compact ? 9 : 10, MUTED, false);
        statusCard.addView(statusBody, new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView active = label("●  ATIVA EM SEGUNDO PLANO", 8, GREEN, true);
        statusCard.addView(active);

        LinearLayout mapCard = card();
        LinearLayout.LayoutParams mcp = new LinearLayout.LayoutParams(-1, 0, compact ? 0.28f : 0.27f);
        mcp.setMargins(0, cardGap, 0, 0);
        right.addView(mapCard, mcp);
        TextView mapOver = label("MAPA E OFFLINE", 8, ACCENT, true);
        mapOver.setLetterSpacing(0.12f);
        mapCard.addView(mapOver);
        mapStateText = label("Preparando cobertura desta região…", compact ? 10 : 11, TEXT, true);
        mapCard.addView(mapStateText, new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView reserve = label("Reserva automática de até 250 km à frente", 8, MUTED, false);
        mapCard.addView(reserve);

        LinearLayout mediaCard = card();
        LinearLayout.LayoutParams mdp = new LinearLayout.LayoutParams(-1, 0, 0.43f);
        mdp.setMargins(0, cardGap, 0, 0);
        right.addView(mediaCard, mdp);
        TextView mediaOver = label("ÁUDIO", 8, MUTED, true);
        mediaOver.setLetterSpacing(0.12f);
        mediaCard.addView(mediaOver);
        TextView mediaTitle = label("Sua música continua com você", compact ? 14 : 17, TEXT, true);
        mediaCard.addView(mediaTitle);
        TextView mediaBody = label("Os alertas reduzem o áudio e falam por cima sem encerrar a reprodução.", compact ? 9 : 10, MUTED, false);
        mediaCard.addView(mediaBody, new LinearLayout.LayoutParams(-1, 0, 1f));
        Button music = action(ultrawide ? "ABRIR BIBLIOTECA DE MÚSICA" : "ABRIR MÚSICA", true);
        int actionH = clamp(Math.round(height * 0.078f), dp(42), dp(56));
        mediaCard.addView(music, new LinearLayout.LayoutParams(-1, actionH));
        music.setOnClickListener(v -> openMain());
        return right;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(panel(18, SURFACE_2, BORDER));
        return card;
    }

    private Button nav(String text, boolean active, int height) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(8);
        b.setLetterSpacing(0.08f);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(active ? Color.WHITE : MUTED);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(3), 0, dp(3), 0);
        b.setStateListAnimator(null);
        b.setBackground(panel(16, active ? ACCENT_SOFT : Color.TRANSPARENT, active ? ACCENT : BORDER));
        return b;
    }

    private Button action(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(9);
        b.setLetterSpacing(0.06f);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(Color.WHITE);
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setStateListAnimator(null);
        b.setBackground(panel(15, primary ? ACCENT : Color.argb(235, 18, 25, 33), primary ? 0 : BORDER));
        return b;
    }

    private void refreshMapData(double lat, double lon, int reportedCount) {
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
                if (offlineRoadStore == null) offlineRoadStore = new OfflineRoadStore(this);
                long revision = offlineRoadStore.revision();
                String roads = null;
                if (revision != loadedRoadRevision) {
                    roads = offlineRoadStore.combinedGeoJson(lat, lon);
                    loadedRoadRevision = revision;
                }
                final String finalRoads = roads;
                ui.post(() -> {
                    if (roadMap != null) {
                        roadMap.setHazards(nearby);
                        if (finalRoads != null) roadMap.setOfflineRoadGeoJson(finalRoads);
                    }
                    updateMapStatus();
                });
            } catch (Throwable ignored) {}
        });
    }

    private void updateMapStatus() {
        if (mapStateText == null) return;
        String value = roadMap == null ? "Mapa preparando…" : roadMap.status();
        if (offlineRoadStore != null && Double.isFinite(lastLat) && Double.isFinite(lastLon)) {
            value += "\n" + offlineRoadStore.status(lastLat, lastLon);
        }
        mapStateText.setText(value);
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
                lastLat = best.getLatitude();
                lastLon = best.getLongitude();
                if (best.hasBearing()) lastHeading = best.getBearing();
                if (roadMap != null) roadMap.setUserLocation(lastLat, lastLon, lastHeading);
                refreshMapData(lastLat, lastLon, 0);
            }
        } catch (Throwable ignored) {}
    }

    private void registerRoadReceiver() {
        if (receiverRegistered) return;
        try {
            IntentFilter f = new IntentFilter(RoadSafetyService.ACTION_STATE);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(roadReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(roadReceiver, f);
            receiverRegistered = true;
        } catch (Throwable ignored) {}
    }

    private void startSafety() {
        try {
            Intent i = new Intent(this, RoadSafetyService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } catch (Throwable ignored) {}
    }

    private void openMain() {
        startActivity(new Intent(this, MainActivity.class));
    }

    private boolean hasAccount() {
        try {
            SharedPreferences p = getSharedPreferences(UI_PREFS, MODE_PRIVATE);
            JSONObject a = new JSONObject(p.getString(KEY_ACCOUNT, "{}"));
            return a.optBoolean("authenticated", false) || a.optJSONObject("user") != null;
        } catch (Throwable e) {
            return false;
        }
    }

    private boolean hasLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private TextView label(String value, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setLineSpacing(0, 1.05f);
        t.setMaxLines(3);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private GradientDrawable panel(int radiusDp, int color, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) d.setStroke(dp(1), strokeColor);
        return d;
    }

    private int[] screenSize() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Rect b = getWindowManager().getCurrentWindowMetrics().getBounds();
                return new int[]{b.width(), b.height()};
            }
        } catch (Throwable ignored) {}
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return new int[]{dm.widthPixels, dm.heightPixels};
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (root != null) root.post(this::buildResponsiveUi);
    }

    @Override protected void onStart() {
        super.onStart();
        if (roadMap != null) roadMap.onStartMap();
    }

    @Override protected void onResume() {
        super.onResume();
        if (roadMap != null) roadMap.onResumeMap();
    }

    @Override protected void onPause() {
        if (roadMap != null) roadMap.onPauseMap();
        super.onPause();
    }

    @Override protected void onStop() {
        if (roadMap != null) roadMap.onStopMap();
        super.onStop();
    }

    @Override public void onLowMemory() {
        super.onLowMemory();
        if (roadMap != null) roadMap.onLowMemoryMap();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        if (roadMap != null) roadMap.onSaveMap(outState);
        super.onSaveInstanceState(outState);
    }

    @Override protected void onDestroy() {
        ui.removeCallbacks(clockTick);
        if (receiverRegistered) {
            try { unregisterReceiver(roadReceiver); } catch (Throwable ignored) {}
            receiverRegistered = false;
        }
        try { io.shutdownNow(); } catch (Throwable ignored) {}
        if (roadMap != null) roadMap.onDestroyMap();
        super.onDestroy();
    }
}
'''

p = root / 'app/src/main/java/com/estradaplay/app/RoadMapActivity.java'
p.write_text(java, encoding='utf-8')

# Version bump for the true responsive redesign.
gradle = root / 'app/build.gradle'
s = gradle.read_text(encoding='utf-8')
s = s.replace("versionCode 11", "versionCode 12")
s = s.replace("versionName '1.6.1'", "versionName '1.7.0'")
gradle.write_text(s, encoding='utf-8')

print('EstradaPlay 1.7 responsive automotive cockpit created from scratch')

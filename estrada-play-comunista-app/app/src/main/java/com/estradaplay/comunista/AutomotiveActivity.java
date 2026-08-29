package com.estradaplay.comunista;

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
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
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
 * EstradaPlay cockpit.
 *
 * Landscape-first shell designed to behave like an automotive multimedia unit:
 * map always visible, persistent road protection, persistent local player and
 * large touch targets. No WebView and no destination is required.
 */
public final class AutomotiveActivity extends ComponentActivity {
    private static final String UI_PREFS = "estradaplay_ui_v1";
    private static final String KEY_ACCOUNT = "account";
    private static final int REQ_LOCATION = 1601;
    private static final int REQ_NOTIFICATIONS = 1602;

    private final int BG = Color.rgb(5, 7, 10);
    private final int RAIL = Color.rgb(8, 11, 15);
    private final int SURFACE = Color.rgb(13, 18, 24);
    private final int SURFACE_2 = Color.rgb(18, 25, 33);
    private final int SURFACE_3 = Color.rgb(24, 33, 43);
    private final int BORDER = Color.rgb(38, 49, 61);
    private final int TEXT = Color.rgb(246, 248, 251);
    private final int MUTED = Color.rgb(145, 155, 166);
    private final int SUBTLE = Color.rgb(91, 103, 116);
    private final int AMBER = Color.rgb(255, 137, 61);
    private final int AMBER_SOFT = Color.rgb(66, 36, 22);
    private final int GREEN = Color.rgb(55, 211, 139);
    private final int GREEN_SOFT = Color.rgb(17, 54, 40);
    private final int BLUE = Color.rgb(92, 169, 255);
    private final int BLUE_SOFT = Color.rgb(18, 43, 69);
    private final int RED = Color.rgb(255, 82, 103);
    private final int RED_SOFT = Color.rgb(66, 25, 32);

    private FrameLayout root;
    private RoadMapView roadMap;
    private LibraryStore library;
    private RoadPackStore roadStore;
    private OfflineRoadStore offlineRoadStore;
    private LinearLayout permissionHost;

    private TextView clockText;
    private TextView speedText;
    private TextView roadStateText;
    private TextView roadDetailText;
    private TextView coverageText;
    private TextView hazardTitle;
    private TextView hazardDetail;
    private LinearLayout hazardBanner;
    private TextView playerTitle;
    private TextView playerArtist;
    private TextView playerBadge;
    private Button playPause;

    private boolean playing;
    private String currentTrack = "";
    private double lastLat = Double.NaN;
    private double lastLon = Double.NaN;
    private double lastHeading;
    private volatile int loadedHazardCount = -1;
    private volatile long loadedRoadRevision = Long.MIN_VALUE;
    private volatile long lastMapRefreshAt;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clockFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());

    private final Runnable clockTicker = new Runnable() {
        @Override public void run() {
            if (clockText != null) clockText.setText(clockFormat.format(new Date()));
            ui.postDelayed(this, 15000L);
        }
    };

    private final BroadcastReceiver roadReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            double lat = intent.getDoubleExtra("lat", Double.NaN);
            double lon = intent.getDoubleExtra("lon", Double.NaN);
            double heading = intent.getFloatExtra("heading", -1f);
            double speed = intent.getDoubleExtra("speed_kmh", 0);
            int count = intent.getIntExtra("hazard_count", 0);
            int packCount = intent.getIntExtra("pack_count", 0);
            int stateCount = intent.getIntExtra("state_pack_count", 0);
            int reserveKm = intent.getIntExtra("reserve_km", 250);
            String status = safe(intent.getStringExtra("status"));
            String hazard = safe(intent.getStringExtra("hazard_label"));
            String type = safe(intent.getStringExtra("hazard_type"));
            String road = safe(intent.getStringExtra("road"));
            double distance = intent.getDoubleExtra("distance_m", 0);
            int limit = intent.getIntExtra("limit_kmh", 0);
            SafetyAlertOverlay.show(AutomotiveActivity.this, root, intent);
            RoadThoughtOverlay.show(AutomotiveActivity.this, root, intent);

            if (Double.isFinite(lat) && Double.isFinite(lon)) {
                lastLat = lat;
                lastLon = lon;
                if (heading >= 0) lastHeading = heading;
                if (roadMap != null) roadMap.setUserLocation(lat, lon, lastHeading);
                refreshMapData(lat, lon, count);
            }

            if (speedText != null) speedText.setText(String.valueOf(Math.max(0, Math.round(speed))));
            if (roadStateText != null) roadStateText.setText(hazard.isEmpty() ? "Proteção ativa" : hazard + " à frente");
            if (roadDetailText != null) {
                String value = road.isEmpty() ? status : road + (status.isEmpty() ? "" : "  ·  " + status);
                roadDetailText.setText(value.isEmpty() ? "GPS monitorando seu sentido" : value);
            }
            if (coverageText != null) {
                String state = stateCount > 0 ? stateCount + " base(s) estadual(is)" : "base estadual preparando";
                coverageText.setText(count + " alertas  ·  " + state + "  ·  " + reserveKm + " km à frente");
            }
            updateHazardBanner(hazard, type, road, distance, limit);
        }
    };

    private final BroadcastReceiver playerReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            currentTrack = safe(intent.getStringExtra("title"));
            String artist = safe(intent.getStringExtra("artist"));
            String state = safe(intent.getStringExtra("state"));
            playing = intent.getBooleanExtra("playing", false);
            if (playerTitle != null) playerTitle.setText(currentTrack.isEmpty() ? "Escolha uma música" : currentTrack);
            if (playerArtist != null) playerArtist.setText(artist.isEmpty() ? "Biblioteca offline" : artist);
            if (playerBadge != null) playerBadge.setText(state.isEmpty() ? "OFFLINE" : state.toUpperCase(Locale.ROOT));
            if (playPause != null) playPause.setText(playing ? "Ⅱ" : "▶");
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        enterImmersive();
        library = new LibraryStore(this);
        roadStore = new RoadPackStore(this);
        offlineRoadStore = new OfflineRoadStore(this);

        if (!hasAccount() || !library.hasSetupDone()) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }

        buildCockpit();
        registerReceivers();
        refreshPermissionPanel();
        if (hasLocation()) {
            startSafety();
            seedLocation();
        }
        ui.post(clockTicker);
    }

    private void buildCockpit() {
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        setContentView(root);

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.HORIZONTAL);
        shell.setBackgroundColor(BG);
        root.addView(shell, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout rail = buildRail();
        shell.addView(rail, new LinearLayout.LayoutParams(dp(86), -1));

        FrameLayout mapPane = new FrameLayout(this);
        mapPane.setBackgroundColor(BG);
        shell.addView(mapPane, new LinearLayout.LayoutParams(0, -1, 1));

        int widthDp = Math.round(getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density);
        int panelDp = Math.max(300, Math.min(380, Math.round(widthDp * 0.285f)));
        LinearLayout side = buildSidePanel();
        shell.addView(side, new LinearLayout.LayoutParams(dp(panelDp), -1));

        roadMap = new RoadMapView(this);
        mapPane.addView(roadMap, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout mapTop = row();
        mapTop.setGravity(Gravity.CENTER_VERTICAL);
        mapTop.setPadding(dp(14), dp(9), dp(14), dp(9));
        mapTop.setBackground(panel(18, Color.argb(230, 9, 13, 18), BORDER));
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(-1, dp(58), Gravity.TOP);
        topLp.setMargins(dp(14), dp(14), dp(14), 0);
        mapPane.addView(mapTop, topLp);

        LinearLayout roadMeta = column();
        roadStateText = label("Proteção ativa", 15, TEXT, true);
        roadDetailText = label("GPS monitorando seu sentido", 10, MUTED, false);
        roadMeta.addView(roadStateText);
        roadMeta.addView(roadDetailText);
        mapTop.addView(roadMeta, new LinearLayout.LayoutParams(0, -2, 1));

        TextView gpsChip = chip("GPS PASSIVO", GREEN, GREEN_SOFT);
        mapTop.addView(gpsChip, new LinearLayout.LayoutParams(-2, dp(30)));
        TextView offlineChip = chip("OFFLINE", BLUE, BLUE_SOFT);
        LinearLayout.LayoutParams offlineLp = new LinearLayout.LayoutParams(-2, dp(30));
        offlineLp.setMargins(dp(7), 0, 0, 0);
        mapTop.addView(offlineChip, offlineLp);

        LinearLayout speedHud = column();
        speedHud.setGravity(Gravity.CENTER);
        speedHud.setBackground(panel(22, Color.argb(236, 9, 13, 18), BORDER));
        speedText = label("0", 34, TEXT, true);
        speedText.setGravity(Gravity.CENTER);
        TextView kmh = overline("KM/H", MUTED);
        kmh.setGravity(Gravity.CENTER);
        speedHud.addView(speedText);
        speedHud.addView(kmh);
        FrameLayout.LayoutParams speedLp = new FrameLayout.LayoutParams(dp(96), dp(88), Gravity.TOP | Gravity.LEFT);
        speedLp.setMargins(dp(16), dp(84), 0, 0);
        mapPane.addView(speedHud, speedLp);

        Button recenter = squareButton("◎", false);
        FrameLayout.LayoutParams recenterLp = new FrameLayout.LayoutParams(dp(54), dp(54), Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        recenterLp.setMargins(0, 0, dp(16), 0);
        mapPane.addView(recenter, recenterLp);
        recenter.setOnClickListener(v -> { if (roadMap != null) roadMap.recenter(); });

        hazardBanner = column();
        hazardBanner.setPadding(dp(16), dp(12), dp(16), dp(12));
        hazardBanner.setBackground(panel(18, Color.argb(244, 38, 20, 18), Color.rgb(116, 57, 37)));
        hazardTitle = label("", 17, TEXT, true);
        hazardDetail = label("", 11, Color.rgb(235, 181, 145), false);
        hazardBanner.addView(hazardTitle);
        hazardBanner.addView(hazardDetail);
        hazardBanner.setVisibility(View.GONE);
        FrameLayout.LayoutParams hazardLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        hazardLp.setMargins(dp(14), 0, dp(14), dp(16));
        mapPane.addView(hazardBanner, hazardLp);
    }

    private LinearLayout buildRail() {
        LinearLayout rail = column();
        rail.setGravity(Gravity.CENTER_HORIZONTAL);
        rail.setPadding(dp(8), dp(12), dp(8), dp(12));
        rail.setBackgroundColor(RAIL);

        TextView mark = label("EP", 15, AMBER, true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(panel(18, AMBER_SOFT, Color.rgb(113, 60, 35)));
        rail.addView(mark, new LinearLayout.LayoutParams(dp(52), dp(52)));

        clockText = label(clockFormat.format(new Date()), 16, TEXT, true);
        clockText.setGravity(Gravity.CENTER);
        rail.addView(clockText, new LinearLayout.LayoutParams(-1, dp(42)));

        View spacerTop = new View(this);
        rail.addView(spacerTop, new LinearLayout.LayoutParams(1, 0, 0.35f));

        Button map = railButton("◎", "MAPA", true);
        Button music = railButton("♪", "MÚSICA", false);
        Button libraryButton = railButton("↓", "BAIXAR", false);
        Button account = railButton("•", "CONTA", false);
        rail.addView(map, new LinearLayout.LayoutParams(-1, dp(68)));
        rail.addView(music, new LinearLayout.LayoutParams(-1, dp(68)));
        rail.addView(libraryButton, new LinearLayout.LayoutParams(-1, dp(68)));
        rail.addView(account, new LinearLayout.LayoutParams(-1, dp(68)));

        View spacerBottom = new View(this);
        rail.addView(spacerBottom, new LinearLayout.LayoutParams(1, 0, 1f));

        TextView version = overline("v" + BuildConfig.VERSION_NAME, SUBTLE);
        version.setGravity(Gravity.CENTER);
        rail.addView(version, new LinearLayout.LayoutParams(-1, dp(28)));

        map.setOnClickListener(v -> { if (roadMap != null) roadMap.recenter(); });
        music.setOnClickListener(v -> openManager("music"));
        libraryButton.setOnClickListener(v -> openManager("library"));
        account.setOnClickListener(v -> openManager("account"));
        return rail;
    }

    private LinearLayout buildSidePanel() {
        LinearLayout side = column();
        side.setPadding(dp(14), dp(14), dp(14), dp(14));
        side.setBackgroundColor(RAIL);

        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleBox = column();
        titleBox.addView(overline("ESTRADAPLAY", AMBER));
        titleBox.addView(label("Cockpit", 21, TEXT, true));
        header.addView(titleBox, new LinearLayout.LayoutParams(0, -2, 1));
        TextView live = chip("AO VIVO", GREEN, GREEN_SOFT);
        header.addView(live, new LinearLayout.LayoutParams(-2, dp(30)));
        side.addView(header);

        LinearLayout player = card(SURFACE, BORDER);
        side.addView(player, new LinearLayout.LayoutParams(-1, -2));
        setMargins(player, 0, 14, 0, 0);
        player.addView(overline("AGORA TOCANDO", MUTED));
        playerTitle = label("Escolha uma música", 18, TEXT, true);
        playerTitle.setMaxLines(2);
        player.addView(playerTitle);
        setMargins(playerTitle, 0, 7, 0, 0);
        playerArtist = label("Biblioteca offline", 11, MUTED, false);
        playerArtist.setMaxLines(1);
        player.addView(playerArtist);
        playerBadge = chip("OFFLINE", GREEN, GREEN_SOFT);
        player.addView(playerBadge, new LinearLayout.LayoutParams(-2, dp(28)));
        setMargins(playerBadge, 0, 10, 0, 0);

        LinearLayout controls = row();
        controls.setGravity(Gravity.CENTER);
        Button previous = squareButton("◀", false);
        playPause = squareButton("▶", true);
        Button next = squareButton("▶", false);
        controls.addView(previous, new LinearLayout.LayoutParams(0, dp(54), 1));
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(0, dp(58), 1.25f);
        playLp.setMargins(dp(8), 0, dp(8), 0);
        controls.addView(playPause, playLp);
        controls.addView(next, new LinearLayout.LayoutParams(0, dp(54), 1));
        player.addView(controls);
        setMargins(controls, 0, 13, 0, 0);

        previous.setOnClickListener(v -> sendPlayer(PlayerService.ACTION_PREVIOUS));
        next.setOnClickListener(v -> sendPlayer(PlayerService.ACTION_NEXT));
        playPause.setOnClickListener(v -> togglePlayer());

        LinearLayout protection = card(SURFACE, BORDER);
        side.addView(protection, new LinearLayout.LayoutParams(-1, -2));
        setMargins(protection, 0, 12, 0, 0);
        LinearLayout protectionHead = row();
        protectionHead.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout protectionMeta = column();
        protectionMeta.addView(overline("PROTEÇÃO DA ESTRADA", GREEN));
        protectionMeta.addView(label("Sempre à frente", 17, TEXT, true));
        protectionHead.addView(protectionMeta, new LinearLayout.LayoutParams(0, -2, 1));
        TextView shield = chip("ATIVA", GREEN, GREEN_SOFT);
        protectionHead.addView(shield, new LinearLayout.LayoutParams(-2, dp(28)));
        protection.addView(protectionHead);
        coverageText = label("Preparando base estadual + reserva de 250 km", 10.5f, MUTED, false);
        protection.addView(coverageText);
        setMargins(coverageText, 0, 8, 0, 0);

        permissionHost = column();
        side.addView(permissionHost, new LinearLayout.LayoutParams(-1, -2));
        setMargins(permissionHost, 0, 12, 0, 0);

        View spacer = new View(this);
        side.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));

        LinearLayout footer = row();
        footer.setGravity(Gravity.CENTER_VERTICAL);
        TextView storage = label(storageSummary(), 9.5f, SUBTLE, false);
        footer.addView(storage, new LinearLayout.LayoutParams(0, -2, 1));
        Button manage = compactButton("GERENCIAR");
        footer.addView(manage, new LinearLayout.LayoutParams(dp(104), dp(42)));
        side.addView(footer);
        manage.setOnClickListener(v -> openManager("library"));
        return side;
    }

    private void refreshPermissionPanel() {
        if (permissionHost == null) return;
        permissionHost.removeAllViews();
        boolean location = hasLocation();
        boolean notifications = hasNotifications();
        if (location && notifications) {
            LinearLayout ready = card(SURFACE_2, Color.rgb(33, 70, 56));
            LinearLayout row = row(); row.setGravity(Gravity.CENTER_VERTICAL);
            TextView icon = chip("✓", GREEN, GREEN_SOFT); row.addView(icon, new LinearLayout.LayoutParams(dp(34), dp(34)));
            LinearLayout meta = column();
            meta.addView(label("Permissões prontas", 13, TEXT, true));
            meta.addView(label("GPS e avisos autorizados dentro do app", 9.5f, MUTED, false));
            row.addView(meta, new LinearLayout.LayoutParams(0, -2, 1)); setMargins(meta, 9, 0, 0, 0);
            ready.addView(row); permissionHost.addView(ready);
            return;
        }

        LinearLayout permissions = card(SURFACE_2, AMBER_SOFT);
        permissions.addView(overline("CONFIGURAÇÃO DO VEÍCULO", AMBER));
        permissions.addView(label("Autorizações", 16, TEXT, true));
        TextView explainer = label("Você libera cada recurso por aqui. O EstradaPlay só chama a autorização do Android depois do seu toque.", 10, MUTED, false);
        permissions.addView(explainer); setMargins(explainer, 0, 6, 0, 10);

        if (!location) {
            Button gps = permissionButton("ATIVAR LOCALIZAÇÃO", "Necessária para detectar estrada, sentido e alertas");
            permissions.addView(gps, new LinearLayout.LayoutParams(-1, dp(52)));
            gps.setOnClickListener(v -> requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION));
        }
        if (!notifications && Build.VERSION.SDK_INT >= 33) {
            Button notify = permissionButton("ATIVAR NOTIFICAÇÕES", "Mantém a proteção visível enquanto dirige");
            permissions.addView(notify, new LinearLayout.LayoutParams(-1, dp(52)));
            setMargins(notify, 0, 8, 0, 0);
            notify.setOnClickListener(v -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS));
        }
        permissionHost.addView(permissions);
    }

    private void updateHazardBanner(String hazard, String type, String road, double distance, int limit) {
        if (hazardBanner == null) return;
        if (hazard.isEmpty()) {
            hazardBanner.setVisibility(View.GONE);
            return;
        }
        hazardBanner.setVisibility(View.VISIBLE);
        String prefix;
        if ("PASSAGEM_NIVEL".equals(type)) prefix = "ATENÇÃO MÁXIMA";
        else if ("QUEBRA_MOLAS".equals(type)) prefix = "REDUZA";
        else if ("PEDAGIO".equals(type)) prefix = "À FRENTE";
        else prefix = "ALERTA";
        hazardTitle.setText(prefix + "  ·  " + hazard.toUpperCase(Locale.ROOT));
        StringBuilder detail = new StringBuilder();
        if (distance > 0) detail.append(distance >= 1000 ? String.format(Locale.getDefault(), "%.1f km", distance / 1000.0) : Math.round(distance) + " m");
        if (limit > 0) detail.append(detail.length() == 0 ? "" : "  ·  ").append("limite ").append(limit).append(" km/h");
        if (!road.isEmpty()) detail.append(detail.length() == 0 ? "" : "  ·  ").append(road);
        hazardDetail.setText(detail.toString());
    }

    private void togglePlayer() {
        if (currentTrack.isEmpty()) {
            List<Track> tracks = library.downloadedTracks();
            if (tracks.isEmpty()) {
                openManager("library");
                return;
            }
            Track first = tracks.get(0);
            Intent i = new Intent(this, PlayerService.class).setAction(PlayerService.ACTION_PLAY_TRACK);
            i.putExtra(PlayerService.EXTRA_KEY, first.key());
            i.putExtra(PlayerService.EXTRA_FOLDER, "__ALL__");
            startService(i);
            return;
        }
        sendPlayer(PlayerService.ACTION_TOGGLE);
    }

    private void sendPlayer(String action) {
        try { startService(new Intent(this, PlayerService.class).setAction(action)); } catch (Throwable ignored) {}
    }

    private void openManager(String target) {
        Intent i = new Intent(this, MainActivity.class);
        i.putExtra("open", target);
        startActivity(i);
    }

    private void startSafety() {
        if (!hasLocation()) return;
        try {
            Intent i = new Intent(this, RoadSafetyService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } catch (Throwable ignored) {}
    }

    private void refreshMapData(double lat, double lon, int reportedCount) {
        long now = System.currentTimeMillis();
        if (now - lastMapRefreshAt < 2200L) return;
        lastMapRefreshAt = now;
        io.execute(() -> {
            try {
                if (loadedHazardCount != reportedCount) {
                    roadStore = new RoadPackStore(this);
                    loadedHazardCount = roadStore.hazardCount();
                }
                List<RoadHazard> nearby = roadStore.nearby(lat, lon, 6000);
                long revision = offlineRoadStore.revision();
                String roads = null;
                if (revision != loadedRoadRevision) {
                    roads = offlineRoadStore.combinedGeoJson(lat, lon);
                    loadedRoadRevision = revision;
                }
                final String finalRoads = roads;
                ui.post(() -> {
                    if (roadMap == null) return;
                    roadMap.setHazards(nearby);
                    if (finalRoads != null) roadMap.setOfflineRoadGeoJson(finalRoads);
                });
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
                refreshMapData(lastLat, lastLon, 0);
            }
        } catch (Throwable ignored) {}
    }

    private void registerReceivers() {
        IntentFilter road = new IntentFilter(RoadSafetyService.ACTION_STATE);
        IntentFilter player = new IntentFilter(PlayerService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(roadReceiver, road, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(playerReceiver, player, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(roadReceiver, road);
            registerReceiver(playerReceiver, player);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION && hasLocation()) {
            startSafety();
            seedLocation();
        }
        refreshPermissionPanel();
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

    private boolean hasNotifications() {
        return Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private String storageSummary() {
        int songs = library.downloadedTracks().size();
        return songs + " música(s) offline";
    }

    private void enterImmersive() {
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    private Button railButton(String symbol, String caption, boolean active) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(symbol + "\n" + caption);
        b.setTextSize(9.5f);
        b.setTextColor(active ? AMBER : MUTED);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, dp(4), 0, 0);
        b.setStateListAnimator(null);
        b.setBackground(panel(16, active ? AMBER_SOFT : Color.TRANSPARENT, active ? Color.rgb(110, 58, 35) : 0));
        return b;
    }

    private Button squareButton(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(primary ? 20 : 17);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, 0, 0, 0);
        b.setStateListAnimator(null);
        b.setBackground(panel(17, primary ? AMBER : SURFACE_3, primary ? 0 : BORDER));
        return b;
    }

    private Button compactButton(String text) {
        Button b = new Button(this);
        b.setText(text); b.setAllCaps(false); b.setTextSize(9.5f); b.setLetterSpacing(0.08f);
        b.setTextColor(TEXT); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); b.setStateListAnimator(null);
        b.setBackground(panel(14, SURFACE_3, BORDER));
        return b;
    }

    private Button permissionButton(String title, String subtitle) {
        Button b = new Button(this);
        b.setText(title + "\n" + subtitle);
        b.setAllCaps(false); b.setTextSize(10); b.setTextColor(TEXT); b.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        b.setPadding(dp(13), 0, dp(13), 0); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); b.setStateListAnimator(null);
        b.setBackground(panel(14, SURFACE_3, Color.rgb(91, 57, 40)));
        return b;
    }

    private LinearLayout card(int color, int border) {
        LinearLayout l = column();
        l.setPadding(dp(14), dp(13), dp(14), dp(13));
        l.setBackground(panel(18, color, border));
        l.setElevation(dp(1));
        return l;
    }

    private TextView chip(String value, int color, int fill) {
        TextView t = label(value, 8.5f, color, true);
        t.setGravity(Gravity.CENTER);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(10), 0, dp(10), 0);
        t.setBackground(panel(100, fill, 0));
        return t;
    }

    private TextView overline(String value, int color) {
        TextView t = label(value, 8.5f, color, true);
        t.setLetterSpacing(0.14f);
        return t;
    }

    private TextView label(String value, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setGravity(Gravity.CENTER_VERTICAL);
        t.setLineSpacing(0, 1.04f); if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }

    private GradientDrawable panel(int radius, int color, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(radius));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private void setMargins(View v, int l, int t, int r, int b) {
        ViewGroup.LayoutParams raw = v.getLayoutParams();
        if (!(raw instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams p = (ViewGroup.MarginLayoutParams)raw;
        p.setMargins(dp(l), dp(t), dp(r), dp(b)); v.setLayoutParams(p);
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static String safe(String s) { return s == null ? "" : s.trim(); }

    @Override protected void onStart() { super.onStart(); enterImmersive(); if (roadMap != null) roadMap.onStartMap(); }
    @Override protected void onResume() { super.onResume(); enterImmersive(); if (roadMap != null) roadMap.onResumeMap(); refreshPermissionPanel(); }
    @Override protected void onPause() { if (roadMap != null) roadMap.onPauseMap(); super.onPause(); }
    @Override protected void onStop() { if (roadMap != null) roadMap.onStopMap(); super.onStop(); }
    @Override public void onLowMemory() { super.onLowMemory(); if (roadMap != null) roadMap.onLowMemoryMap(); }
    @Override protected void onSaveInstanceState(Bundle outState) { if (roadMap != null) roadMap.onSaveMap(outState); super.onSaveInstanceState(outState); }

    @Override protected void onDestroy() {
        ui.removeCallbacks(clockTicker);
        try { unregisterReceiver(roadReceiver); } catch (Throwable ignored) {}
        try { unregisterReceiver(playerReceiver); } catch (Throwable ignored) {}
        if (roadMap != null) roadMap.onDestroyMap();
        io.shutdownNow();
        super.onDestroy();
    }
}

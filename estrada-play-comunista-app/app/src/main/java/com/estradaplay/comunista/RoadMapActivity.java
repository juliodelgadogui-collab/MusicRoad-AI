package com.estradaplay.comunista;

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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ADAPTIVE_COCKPIT_V170
 *
 * EstradaPlay 1.7 cockpit built from scratch for landscape automotive displays.
 * The old 1.5/1.6 fixed-position composition is intentionally not reused.
 */
public final class RoadMapActivity extends ComponentActivity {
    private static final String UI_PREFS = "estradaplay_ui_v1";
    private static final String KEY_ACCOUNT = "account";

    private final int BG = Color.rgb(8, 5, 7);
    private final int SURFACE = Color.rgb(18, 9, 12);
    private final int SURFACE_2 = Color.rgb(28, 14, 18);
    private final int BORDER = Color.rgb(79, 39, 45);
    private final int TEXT = Color.rgb(246, 238, 224);
    private final int MUTED = Color.rgb(174, 151, 146);
    private final int ACCENT = Color.rgb(190, 18, 38);
    private final int ACCENT_SOFT = Color.rgb(79, 10, 23);
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
    private TextView destinationText;

    private RoadPackStore mapStore;
    private OfflineRoadStore offlineRoadStore;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService routeIo = Executors.newSingleThreadExecutor();
    private final AtomicBoolean routeLoading = new AtomicBoolean(false);
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
    private DestinationStore.Destination destination;
    private RouteEngine.Route activeRoute;
    private long lastRouteAt;
    private double lastRouteLat = Double.NaN;
    private double lastRouteLon = Double.NaN;

    // TOUCH_INPUT_V171: explicit cockpit hit targets. Automotive Android builds sometimes
    // dispatch touch to a texture/map layer before sibling controls. The Activity sees the
    // event first and routes only real button regions; map gestures remain untouched elsewhere.
    private final java.util.ArrayList<View> touchTargets = new java.util.ArrayList<>();
    private View routedTouchTarget;

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
            SafetyAlertOverlay.show(RoadMapActivity.this, root, intent);

            if (Double.isFinite(lat) && Double.isFinite(lon)) {
                lastLat = lat;
                lastLon = lon;
                if (heading >= 0) lastHeading = heading;
                if (roadMap != null) roadMap.setUserLocation(lat, lon, lastHeading);
                refreshMapData(lat, lon, count);
                refreshDestinationRoute(lat, lon);
            }

            if (speedText != null) speedText.setText(String.valueOf(Math.max(0, Math.round(speed))));
            if (gpsText != null) gpsText.setText(Double.isFinite(lat) ? "GPS ATIVO" : "GPS BUSCANDO");

            boolean hasHazard = hazard != null && !hazard.trim().isEmpty();
            boolean hasAlertBase = count > 0;
            if (protectionText != null) protectionText.setText(hasHazard ? "ATENÇÃO À FRENTE" : (hasAlertBase ? "PROTEÇÃO ATIVA" : "BASE DE ALERTAS VAZIA"));
            if (hazardTitle != null) hazardTitle.setText(hasHazard ? hazard.trim() : (hasAlertBase ? "Estrada livre à frente" : "Sem radares carregados"));
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
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);

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
        destination = DestinationStore.read(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        setContentView(root);

        buildResponsiveUi();
        forceTouchableWindow();
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
                refreshDestinationRoute(lastLat, lastLon);
                }
            }
        });
    }

    // DUAL_ORIENTATION_TOUCH_V176: native portrait/landscape + top-level controls.
    private void buildResponsiveUi() {
        int[] size = screenSize();
        int width = root != null && root.getWidth() > 0 ? root.getWidth() : size[0];
        int height = root != null && root.getHeight() > 0 ? root.getHeight() : size[1];
        lastWidth = width;
        lastHeight = height;

        if (roadMap != null) {
            try { roadMap.onPauseMap(); roadMap.onStopMap(); roadMap.onDestroyMap(); } catch (Throwable ignored) {}
        }
        root.removeAllViews();
        touchTargets.clear();
        routedTouchTarget = null;

        if ("vertical".equals(BuildConfig.FIXED_LAYOUT)) {
            buildPortraitUi(width, height);
        } else {
            buildLandscapeUi(width, height);
        }
    }

    private void buildLandscapeUi(int width, int height) {
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

        int mapLeft = outer + railWidth + gap;
        int mapRight = width - outer - rightWidth - gap;

        // Map first. It can never cover controls added afterwards.
        FrameLayout mapPane = buildMapPane(height, compact);
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(-1, -1);
        mp.setMargins(mapLeft, outer, width - mapRight, outer);
        root.addView(mapPane, mp);

        LinearLayout rail = buildRail(height, compact);
        rail.setClickable(true);
        rail.setFocusable(false);
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(railWidth, -1, Gravity.LEFT);
        rp.setMargins(outer, outer, 0, outer);
        root.addView(rail, rp);

        LinearLayout right = buildRightPanel(height, compact, ultrawide);
        right.setClickable(true);
        right.setFocusable(false);
        FrameLayout.LayoutParams qp = new FrameLayout.LayoutParams(rightWidth, -1, Gravity.RIGHT);
        qp.setMargins(0, outer, outer, outer);
        root.addView(right, qp);

        if (Build.VERSION.SDK_INT >= 21) {
            rail.setElevation(dp(48));
            right.setElevation(dp(48));
        }
        rail.bringToFront();
        right.bringToFront();
        addTopLevelRecenter(mapLeft, outer, mapRight, height - outer, false);
    }

    private void buildPortraitUi(int width, int height) {
        int outer = clamp(Math.round(width * 0.020f), dp(8), dp(13));

        // V1.2: map is the cockpit. No separate EstradaPlay-style bottom card.
        FrameLayout mapPane = buildMapPane(height - outer * 2, true);
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(-1, -1);
        mp.setMargins(outer, outer, outer, outer);
        root.addView(mapPane, mp);

        LinearLayout brandPlate = new LinearLayout(this);
        brandPlate.setOrientation(LinearLayout.VERTICAL);
        brandPlate.setPadding(dp(10), dp(8), dp(10), dp(8));
        brandPlate.setBackground(panel(3, Color.argb(236, 42, 7, 14), Color.rgb(151, 28, 45)));
        TextView central = label("CENTRAL 04", 8, Color.rgb(226, 185, 76), true); central.setLetterSpacing(0.12f); brandPlate.addView(central);
        brandPlate.addView(label(destination == null ? "RODAGEM LIVRE" : shortDestination(destination.label), 13, TEXT, true));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(dp(150), -2, Gravity.LEFT | Gravity.TOP);
        bp.setMargins(outer + dp(10), outer + dp(92), 0, 0); root.addView(brandPlate, bp);
        if (Build.VERSION.SDK_INT >= 21) brandPlate.setElevation(dp(55));

        if (destination != null) {
            LinearLayout route = new LinearLayout(this);
            route.setOrientation(LinearLayout.VERTICAL);
            route.setPadding(dp(10), dp(8), dp(10), dp(8));
            route.setBackground(panel(3, Color.argb(238, 15, 8, 10), Color.rgb(214, 186, 143)));
            TextView over = label("ROTA ATIVA", 7, Color.rgb(226, 185, 76), true); over.setLetterSpacing(0.12f); route.addView(over);
            destinationText = label("Calculando percurso…", 9, TEXT, true); route.addView(destinationText);
            FrameLayout.LayoutParams rtp = new FrameLayout.LayoutParams(dp(210), -2, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
            rtp.setMargins(0, outer + dp(96), 0, 0); root.addView(route, rtp);
            if (Build.VERSION.SDK_INT >= 21) route.setElevation(dp(55));
        }

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(7), dp(7), dp(7), dp(7));
        controls.setBackground(panel(4, Color.argb(238, 12, 7, 9), BORDER));
        Button recenter = action("CENTRO", false);
        Button destinationButton = action(destination == null ? "ROTA" : "MUDAR", false);
        Button music = action("SOM", true);
        Button centralButton = action("CENTRAL", false);
        controls.addView(recenter, new LinearLayout.LayoutParams(-1, dp(48)));
        LinearLayout.LayoutParams dpp = new LinearLayout.LayoutParams(-1, dp(48)); dpp.setMargins(0, dp(6), 0, 0); controls.addView(destinationButton, dpp);
        LinearLayout.LayoutParams mpp = new LinearLayout.LayoutParams(-1, dp(48)); mpp.setMargins(0, dp(6), 0, 0); controls.addView(music, mpp);
        LinearLayout.LayoutParams cpp = new LinearLayout.LayoutParams(-1, dp(48)); cpp.setMargins(0, dp(6), 0, 0); controls.addView(centralButton, cpp);
        recenter.setOnClickListener(v -> { if (roadMap != null) roadMap.recenter(); });
        destinationButton.setOnClickListener(v -> startActivity(new Intent(this, DestinationActivity.class)));
        music.setOnClickListener(v -> openMain("music"));
        centralButton.setOnClickListener(v -> openMain("home"));
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(dp(94), -2, Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        cp.setMargins(0, 0, outer + dp(9), 0); root.addView(controls, cp);
        if (Build.VERSION.SDK_INT >= 21) controls.setElevation(dp(70));
        controls.bringToFront();

        TextView signature = label("EPC / MAPA LIVRE / PROTEÇÃO ATIVA", 7, Color.rgb(226, 185, 76), true);
        signature.setLetterSpacing(0.08f); signature.setGravity(Gravity.CENTER); signature.setBackground(panel(2, Color.argb(220, 12, 7, 9), BORDER));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(dp(218), dp(30), Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        sp.setMargins(0, 0, 0, outer + dp(8)); root.addView(signature, sp);
        if (Build.VERSION.SDK_INT >= 21) signature.setElevation(dp(60));
    }

    private void addTopLevelRecenter(int mapLeft, int mapTop, int mapRight, int mapBottom, boolean portrait) {
        Button recenter = action("CENTRALIZAR", false);
        recenter.setOnClickListener(v -> { if (roadMap != null) roadMap.recenter(); });
        int w = portrait ? dp(112) : dp(116);
        int h = portrait ? dp(48) : dp(52);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h);
        int safeLeft = mapLeft + dp(12);
        int safeRight = Math.max(safeLeft + w, mapRight - dp(22));
        p.leftMargin = Math.max(safeLeft, safeRight - w);
        p.topMargin = Math.max(mapTop + dp(76), mapTop + ((mapBottom - mapTop - h) / 2));
        root.addView(recenter, p);
        if (Build.VERSION.SDK_INT >= 21) recenter.setElevation(dp(64));
        recenter.bringToFront();
    }

    private LinearLayout buildRail(int height, boolean compact) {
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = clamp(Math.round(height * 0.018f), dp(7), dp(14));
        rail.setPadding(pad, pad, pad, pad);
        rail.setBackground(panel(24, SURFACE, BORDER));

        TextView logo = label("EC", compact ? 18 : 21, TEXT, true);
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
        music.setOnClickListener(v -> openMain("music"));

        LinearLayout.LayoutParams homeP = new LinearLayout.LayoutParams(-1, buttonH);
        homeP.setMargins(0, dp(8), 0, 0);
        Button home = nav("INÍCIO", false, buttonH);
        rail.addView(home, homeP);
        home.setOnClickListener(v -> openMain("home"));

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

        TextView drive = label("  CENTRAL DA ESTRADA  ·  CONDUÇÃO", compact ? 9 : 10, TEXT, true);
        drive.setLetterSpacing(0.08f);
        top.addView(drive, new LinearLayout.LayoutParams(0, -1, 1f));

        gpsText = label("GPS ATIVO", 9, GREEN, true);
        gpsText.setGravity(Gravity.CENTER);
        gpsText.setPadding(dp(11), 0, dp(11), 0);
        gpsText.setBackground(panel(100, Color.rgb(17, 52, 38), 0));
        top.addView(gpsText, new LinearLayout.LayoutParams(-2, Math.max(dp(32), chipH - dp(12))));

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
        TextView title = label("Estrada Play Comunista", compact ? 16 : 20, TEXT, true);
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
        TextView statusOver = label(destination == null ? "PROTEÇÃO" : "ROTA ATIVA", 8, destination == null ? GREEN : ACCENT, true);
        statusOver.setLetterSpacing(0.13f);
        statusCard.addView(statusOver);
        TextView statusTitle = label(destination == null ? "Proteção rodoviária" : shortDestination(destination.label), compact ? 15 : 18, TEXT, true);
        statusCard.addView(statusTitle);
        destinationText = label(destination == null
                ? "Sem destino · radares, limites e alertas continuam ativos"
                : "Calculando distância e tempo até o destino…", compact ? 9 : 10, MUTED, false);
        statusCard.addView(destinationText, new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView active = label(destination == null ? "●  PROTEÇÃO PASSIVA ATIVA" : "●  NAVEGAÇÃO + PROTEÇÃO", 8, GREEN, true);
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
        Button destinationButton = action(destination == null ? "DEFINIR DESTINO" : "ALTERAR DESTINO", false);
        mapCard.addView(destinationButton, new LinearLayout.LayoutParams(-1, clamp(Math.round(height * 0.062f), dp(38), dp(48))));
        destinationButton.setOnClickListener(v -> startActivity(new Intent(this, DestinationActivity.class)));

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
        music.setOnClickListener(v -> openMain("music"));
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
        registerTouchTarget(b);
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
        registerTouchTarget(b);
        return b;
    }

    private void registerTouchTarget(View view) {
        if (view == null) return;
        view.setEnabled(true);
        view.setClickable(true);
        view.setLongClickable(false);
        view.setFocusable(true);
        view.setFocusableInTouchMode(false);
        if (Build.VERSION.SDK_INT >= 21) view.setElevation(dp(12));
        touchTargets.add(view);

        // AUTOMOTIVE_INPUT_V177: the map already proves the panel is receiving
        // pointer input. Some head units expose that same panel as mouse/stylus
        // or lose ACTION_UP. Execute controls on DOWN without global coordinates.
        view.setOnTouchListener((v, event) -> {
            if (event == null) return false;
            int action = event.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                v.setPressed(true);
                v.performClick();
                return true;
            }
            if (action == android.view.MotionEvent.ACTION_MOVE) return true;
            if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
                v.setPressed(false);
                return true;
            }
            return false;
        });
        view.setOnGenericMotionListener((v, event) -> {
            if (event == null) return false;
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_BUTTON_PRESS) {
                v.performClick();
                return true;
            }
            return false;
        });
        view.setOnKeyListener((v, keyCode, event) -> {
            if (event == null || event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                    keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER) {
                v.performClick();
                return true;
            }
            return false;
        });
    }

    private boolean touchInside(View view, float rootX, float rootY) {
        if (view == null || root == null || !view.isShown() || !view.isEnabled()) return false;
        android.graphics.Rect rect = new android.graphics.Rect(0, 0, view.getWidth(), view.getHeight());
        try {
            root.offsetDescendantRectToMyCoords(view, rect);
        } catch (Throwable ignored) {
            return false;
        }
        // A slightly larger hit box is deliberate on in-dash resistive panels.
        int slop = dp(7);
        rect.inset(-slop, -slop);
        return rect.contains(Math.round(rootX), Math.round(rootY));
    }

    private View findTouchTarget(float rootX, float rootY) {
        for (int i = touchTargets.size() - 1; i >= 0; i--) {
            View view = touchTargets.get(i);
            if (view != null && view.hasOnClickListeners() && touchInside(view, rootX, rootY)) return view;
        }
        return null;
    }

    // TOUCH_DIRECT_V174: preserve Android's native View dispatch.

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {

        return super.dispatchTouchEvent(event);

    }


    @Override public boolean dispatchGenericMotionEvent(android.view.MotionEvent event) {

        return super.dispatchGenericMotionEvent(event);

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

    private void refreshDestinationRoute(double lat, double lon) {
        DestinationStore.Destination d = destination;
        if (d == null || !Double.isFinite(lat) || !Double.isFinite(lon)) {
            if (roadMap != null) roadMap.setRouteGeoJson(null);
            return;
        }
        long now = System.currentTimeMillis();
        if (routeLoading.get()) return;
        if (activeRoute != null && Double.isFinite(lastRouteLat) && Double.isFinite(lastRouteLon)) {
            double moved = RoadPackStore.distanceM(lat, lon, lastRouteLat, lastRouteLon);
            if (moved < 700 && now - lastRouteAt < 120_000L) return;
        }
        if (!routeLoading.compareAndSet(false, true)) return;
        if (destinationText != null) destinationText.setText("Calculando rota…");
        routeIo.execute(() -> {
            try {
                RouteEngine.Route route = RouteEngine.fetch(lat, lon, d.lat, d.lon);
                activeRoute = route;
                lastRouteAt = System.currentTimeMillis();
                lastRouteLat = lat;
                lastRouteLon = lon;
                ui.post(() -> {
                    if (roadMap != null) roadMap.setRouteGeoJson(route.geoJson);
                    if (destinationText != null) {
                        String detail = route.summary();
                        if (!route.nextInstruction.isEmpty()) detail += "\n" + route.nextInstruction;
                        destinationText.setText(detail);
                    }
                });
            } catch (Throwable e) {
                ui.post(() -> {
                    if (destinationText != null) destinationText.setText("Rota online indisponível. A proteção da estrada continua ativa.");
                });
            } finally {
                routeLoading.set(false);
            }
        });
    }

    private String shortDestination(String value) {
        String v = value == null ? "Destino" : value.trim();
        int comma = v.indexOf(',');
        if (comma > 0) v = v.substring(0, comma).trim();
        return v.length() > 30 ? v.substring(0, 30) + "…" : v;
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        DestinationStore.Destination fresh = DestinationStore.read(this);
        if (!DestinationStore.same(destination, fresh)) {
            destination = fresh;
            activeRoute = null;
            lastRouteAt = 0L;
            if (roadMap != null) roadMap.setRouteGeoJson(null);
            if (root != null) buildResponsiveUi();
            if (Double.isFinite(lastLat) && Double.isFinite(lastLon)) refreshDestinationRoute(lastLat, lastLon);
        }
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

    private void openMain(String target) {
        Intent i = new Intent(this, MainActivity.class);
        i.putExtra("open", target == null ? "home" : target);
        startActivity(i);
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
        forceTouchableWindow();
        if (roadMap != null) roadMap.onResumeMap();
    }

    // TOUCH_WINDOW_V175: re-assert a normal interactive application window.
    private void forceTouchableWindow() {
        try {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                    android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            View decor = getWindow().getDecorView();
            if (decor != null) {
                decor.setEnabled(true);
                decor.setFocusable(true);
                decor.setFocusableInTouchMode(true);
            }
            if (root != null) {
                root.setEnabled(true);
                root.setClickable(false);
                root.setFocusable(false);
            }
        } catch (Throwable ignored) {}
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) forceTouchableWindow();
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
        try { routeIo.shutdownNow(); } catch (Throwable ignored) {}
        if (roadMap != null) roadMap.onDestroyMap();
        super.onDestroy();
    }
}

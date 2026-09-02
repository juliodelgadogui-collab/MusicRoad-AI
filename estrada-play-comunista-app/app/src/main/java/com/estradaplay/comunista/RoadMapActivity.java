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
import android.widget.Toast;

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

    private final int BG = Color.rgb(6, 4, 5);
    private final int SURFACE = Color.rgb(14, 8, 10);
    private final int SURFACE_2 = Color.rgb(25, 13, 16);
    private final int BORDER = Color.rgb(73, 35, 40);
    private final int TEXT = Color.rgb(246, 238, 224);
    private final int MUTED = Color.rgb(174, 151, 146);
    private final int ACCENT = Color.rgb(184, 20, 38);
    private final int ACCENT_SOFT = Color.rgb(78, 10, 23);
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
    private TextView navInstructionText, navLimitText, navWeatherText, navDistanceText, navRoadText, navEtaText, navRemainingText, navDurationText, navTurnText;
    private TextView mapPlayerTitle, mapPlayerArtist; private Button mapPlayerToggle; private boolean mapPlayerPlaying; private boolean playerReceiverRegistered; private LinearLayout hazardCard;
    // UNIVERSAL_DASH_V160
    private TextView universalNowText, universalAheadText, universalMetaText, universalRouteText;
    private double universalSpeed, currentLatForSave=Double.NaN, currentLonForSave=Double.NaN;
    private int universalLimit; private String universalHazard="", universalUpcoming="", universalSource="", universalConfidence="";
    private RoadQualityStore roadQualityStore;
    // ESTRADA_VIVA_MAP_V180
    private EstradaVivaStore estradaVivaStore;

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

    private final BroadcastReceiver playerReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String title=intent.getStringExtra("title"),artist=intent.getStringExtra("artist");mapPlayerPlaying=intent.getBooleanExtra("playing",false);
            if(mapPlayerTitle!=null)mapPlayerTitle.setText(title==null||title.trim().isEmpty()?"Biblioteca offline":title.trim());
            if(mapPlayerArtist!=null)mapPlayerArtist.setText(artist==null||artist.trim().isEmpty()?"Música local":artist.trim());
            if(mapPlayerToggle!=null)mapPlayerToggle.setText(mapPlayerPlaying?"Ⅱ":"▶");
        }
    };

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
            String type = intent.getStringExtra("hazard_type");
            String road = intent.getStringExtra("road");
            double distance = intent.getDoubleExtra("distance_m", 0);
            int limit = intent.getIntExtra("limit_kmh", 0);
            universalSpeed=speed; universalLimit=intent.getIntExtra("road_limit_kmh",0); currentLatForSave=lat; currentLonForSave=lon;
            String uu=intent.getStringExtra("upcoming_text"); if(uu!=null)universalUpcoming=uu;
            String us=intent.getStringExtra("source"); if(us!=null)universalSource=us;
            String uc=intent.getStringExtra("hazard_confidence"); if(uc!=null)universalConfidence=uc;
            if(hazard!=null&&!hazard.trim().isEmpty()) universalHazard=hazard.trim();
            updateUniversalDriveWidgets(intent);
            SafetyAlertOverlay.show(RoadMapActivity.this, root, intent);
            RoadThoughtOverlay.show(RoadMapActivity.this, root, intent);

            if (Double.isFinite(lat) && Double.isFinite(lon)) {
                lastLat = lat;
                lastLon = lon;
                if (heading >= 0) lastHeading = heading;
                if (roadMap != null) roadMap.setUserLocation(lat, lon, lastHeading);
                refreshMapData(lat, lon, count);
                refreshDestinationRoute(lat, lon);
                updateRouteProgress(lat, lon);
            }

            if (speedText != null) { speedText.setText(String.valueOf(Math.max(0, Math.round(speed)))); speedText.setTextColor(limit>0 && speed>limit+2 ? Color.rgb(255,67,67) : TEXT); }
            if (navLimitText != null) navLimitText.setText(limit > 0 ? String.valueOf(limit) : "—");
            if (hazardCard != null) hazardCard.setBackground(panel(17, Color.argb(240,17,9,11), hasHazardColor(type)));
            if (navWeatherText != null) navWeatherText.setText(RoadWeatherMonitor.compactStatus(RoadMapActivity.this));
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

        destination = DestinationStore.read(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        setContentView(root);

        buildResponsiveUi();
        forceTouchableWindow();
        startSafety();
        registerRoadReceiver();
        registerPlayerReceiver();
        queryPlayerState();
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

        if (usePortraitLayout()) { buildPortraitUi(width, height); } else { buildLandscapeUi(width, height); }
        root.postDelayed(() -> EpcMotion.fadeIn(root), 35L);
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
        int outer = clamp(Math.round(width * 0.018f), dp(7), dp(12));
        FrameLayout mapPane = buildMapPane(height - outer * 2, true);
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(-1, -1);
        mp.setMargins(outer, outer, outer, outer);
        root.addView(mapPane, mp);

        // AUTOMOTIVE_RED_GOLD_V200_NAV: compact vertical rail, inspired by a real head unit.
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setGravity(Gravity.CENTER_HORIZONTAL);
        rail.setPadding(dp(6), dp(7), dp(6), dp(7));
        rail.setBackground(panel(18, Color.argb(238, 10, 7, 8), Color.rgb(83, 45, 43)));
        TextView radioMark=label(")))",13,ACCENT,true);radioMark.setGravity(Gravity.CENTER);rail.addView(radioMark,new LinearLayout.LayoutParams(-1,dp(32)));
        Button ptt = action("PTT", false);
        Button hud = action("HUD", false);
        Button dash = action("DASH", false);
        Button central = action("CENTRAL", false);
        for(Button b:new Button[]{ptt,hud,dash,central}){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(76),dp(48));p.setMargins(0,dp(5),0,0);rail.addView(b,p);}
        ptt.setOnClickListener(v -> startActivity(new Intent(this, RoadRadioActivity.class)));
        hud.setOnClickListener(v -> startActivity(new Intent(this, HudActivity.class)));
        dash.setOnClickListener(v -> startActivity(new Intent(this, CameraActivity.class)));
        central.setOnClickListener(v -> startActivity(new Intent(this, DriveToolsActivity.class)));
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(dp(88), -2, Gravity.LEFT | Gravity.CENTER_VERTICAL);
        rp.setMargins(outer + dp(9), dp(76), 0, 0); root.addView(rail, rp);
        if(Build.VERSION.SDK_INT>=21)rail.setElevation(dp(72));

        Button recenter=action("◎",false);recenter.setTextSize(18);recenter.setOnClickListener(v->{if(roadMap!=null)roadMap.recenter();});
        FrameLayout.LayoutParams rc=new FrameLayout.LayoutParams(dp(54),dp(54),Gravity.RIGHT|Gravity.CENTER_VERTICAL);rc.setMargins(0,dp(112),outer+dp(12),0);root.addView(recenter,rc);if(Build.VERSION.SDK_INT>=21)recenter.setElevation(dp(74));
        addPortraitPlayer(width, outer);
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
        int pad = clamp(Math.round(height * 0.018f), dp(7), dp(13));
        rail.setPadding(pad, pad, pad, pad);
        rail.setBackground(panel(22, Color.rgb(12,7,9), Color.rgb(75,35,40)));

        LinearLayout emblem=new LinearLayout(this);emblem.setOrientation(LinearLayout.VERTICAL);emblem.setGravity(Gravity.CENTER);emblem.setBackground(panel(16,Color.rgb(101,10,24),Color.rgb(163,28,43)));
        TextView star=label("★",compact?18:22,Color.rgb(226,185,76),true);star.setGravity(Gravity.CENTER);emblem.addView(star);
        TextView brand=label("EPC",compact?13:16,TEXT,true);brand.setGravity(Gravity.CENTER);emblem.addView(brand);
        rail.addView(emblem,new LinearLayout.LayoutParams(-1,clamp(Math.round(height*.125f),dp(60),dp(78))));
        TextView drive=label("SISTEMA ATIVO",7,GREEN,true);drive.setGravity(Gravity.CENTER);drive.setLetterSpacing(.08f);rail.addView(drive,new LinearLayout.LayoutParams(-1,dp(28)));

        Button estrada = nav("ESTRADA", true, height);
        Button music = nav("MÚSICA", false, height);
        Button radio = nav("RÁDIO", false, height);
        Button trip = nav("VIAGEM", false, height);
        Button central = nav("CENTRAL", false, height);
        for (Button b : new Button[]{estrada,music,radio,trip,central}) {LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, 0, 1f); p.setMargins(0, dp(3), 0, dp(3)); rail.addView(b, p);}
        estrada.setOnClickListener(v -> { if (roadMap != null) roadMap.recenter(); });
        music.setOnClickListener(v -> startActivity(new Intent(this, MusicPlayerActivity.class)));
        radio.setOnClickListener(v -> startActivity(new Intent(this, RoadRadioActivity.class)));
        trip.setOnClickListener(v -> startActivity(new Intent(this, TripPlannerActivity.class)));
        central.setOnClickListener(v -> startActivity(new Intent(this, DriveToolsActivity.class)));
        TextView version = label("v" + BuildConfig.VERSION_NAME, 8, Color.rgb(126,91,87), true); version.setGravity(Gravity.CENTER); rail.addView(version, new LinearLayout.LayoutParams(-1, dp(28)));
        return rail;
    }

    private FrameLayout buildMapPane(int height, boolean compact) {
        FrameLayout pane=new FrameLayout(this);pane.setBackground(panel(26,Color.rgb(8,5,7),Color.rgb(73,35,40)));pane.setClipToPadding(true);roadMap=new RoadMapView(this);pane.addView(roadMap,new FrameLayout.LayoutParams(-1,-1));
        int inset=clamp(Math.round(height*.021f),dp(8),dp(15));int guideH=clamp(Math.round(height*.145f),dp(88),dp(112));
        LinearLayout guidance=new LinearLayout(this);guidance.setOrientation(LinearLayout.HORIZONTAL);guidance.setGravity(Gravity.CENTER_VERTICAL);guidance.setPadding(dp(12),dp(8),dp(12),dp(8));guidance.setBackground(panel(18,Color.argb(245,11,7,9),Color.rgb(94,51,46)));
        navTurnText=label(destination==null?"★":"↑",compact?30:40,destination==null?Color.rgb(226,185,76):Color.rgb(255,78,85),true);navTurnText.setGravity(Gravity.CENTER);guidance.addView(navTurnText,new LinearLayout.LayoutParams(dp(62),-1));
        LinearLayout distanceBox=new LinearLayout(this);distanceBox.setOrientation(LinearLayout.VERTICAL);distanceBox.setGravity(Gravity.CENTER_VERTICAL);navDistanceText=label(destination==null?"LIVRE":"—",compact?20:27,TEXT,true);distanceBox.addView(navDistanceText);TextView until=label(destination==null?"PROTEÇÃO":"ATÉ A MANOBRA",7,MUTED,true);until.setLetterSpacing(.08f);distanceBox.addView(until);guidance.addView(distanceBox,new LinearLayout.LayoutParams(compact?dp(82):dp(108),-1));
        LinearLayout gText=new LinearLayout(this);gText.setOrientation(LinearLayout.VERTICAL);gText.setGravity(Gravity.CENTER_VERTICAL);navInstructionText=label(destination==null?"Siga a estrada":"Calculando rota…",compact?14:18,TEXT,true);navInstructionText.setMaxLines(1);gText.addView(navInstructionText);navRoadText=label(destination==null?"Alertas e clima ativos":"",compact?9:11,ACCENT,true);navRoadText.setMaxLines(1);gText.addView(navRoadText);navWeatherText=label(RoadWeatherMonitor.compactStatus(this),8,Color.rgb(226,185,76),true);navWeatherText.setMaxLines(1);gText.addView(navWeatherText);guidance.addView(gText,new LinearLayout.LayoutParams(0,-1,1));gpsText=label("GPS",8,GREEN,true);gpsText.setGravity(Gravity.CENTER);gpsText.setBackground(panel(100,Color.argb(190,18,54,39),0));guidance.addView(gpsText,new LinearLayout.LayoutParams(dp(52),dp(32)));FrameLayout.LayoutParams gp=new FrameLayout.LayoutParams(-1,guideH,Gravity.TOP);gp.setMargins(inset,inset,inset,0);pane.addView(guidance,gp);
        LinearLayout speed=new LinearLayout(this);speed.setOrientation(LinearLayout.VERTICAL);speed.setGravity(Gravity.CENTER);speed.setBackground(panel(100,Color.argb(246,12,8,9),Color.rgb(126,59,46)));speedText=label("0",compact?28:36,TEXT,true);speedText.setGravity(Gravity.CENTER);speed.addView(speedText);TextView kmh=label("km/h",8,MUTED,true);kmh.setGravity(Gravity.CENTER);speed.addView(kmh);navLimitText=label("—",compact?14:17,Color.rgb(111,13,25),true);navLimitText.setGravity(Gravity.CENTER);navLimitText.setBackground(panel(100,Color.rgb(248,238,220),Color.rgb(184,20,38)));speed.addView(navLimitText,new LinearLayout.LayoutParams(dp(42),dp(30)));FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(dp(94),dp(120),Gravity.RIGHT|Gravity.TOP);sp.setMargins(0,inset+guideH+dp(10),inset,0);pane.addView(speed,sp);
        hazardCard=new LinearLayout(this);hazardCard.setOrientation(LinearLayout.VERTICAL);hazardCard.setGravity(Gravity.CENTER_VERTICAL);hazardCard.setPadding(dp(12),dp(9),dp(12),dp(9));hazardCard.setBackground(panel(17,Color.argb(240,17,9,11),Color.rgb(92,40,43)));protectionText=label("PROTEÇÃO ATIVA",7,GREEN,true);protectionText.setLetterSpacing(.11f);hazardCard.addView(protectionText);hazardTitle=label("Estrada livre à frente",compact?12:15,TEXT,true);hazardTitle.setMaxLines(1);hazardCard.addView(hazardTitle);hazardDetail=label("Monitorando seu sentido",8,MUTED,false);hazardDetail.setMaxLines(1);hazardCard.addView(hazardDetail);FrameLayout.LayoutParams ap=new FrameLayout.LayoutParams(compact?dp(170):dp(230),-2,Gravity.RIGHT|Gravity.CENTER_VERTICAL);ap.setMargins(0,dp(34),inset,0);pane.addView(hazardCard,ap);
        LinearLayout metrics=new LinearLayout(this);metrics.setOrientation(LinearLayout.HORIZONTAL);metrics.setGravity(Gravity.CENTER);metrics.setPadding(dp(8),dp(7),dp(8),dp(7));metrics.setBackground(panel(16,Color.argb(240,11,8,9),Color.rgb(72,38,38)));navEtaText=metric("--:--","CHEGADA");navRemainingText=metric("—","RESTANTE");navDurationText=metric("—","DURAÇÃO");metrics.addView(navEtaText,new LinearLayout.LayoutParams(0,dp(46),1));metrics.addView(navRemainingText,new LinearLayout.LayoutParams(0,dp(46),1));metrics.addView(navDurationText,new LinearLayout.LayoutParams(0,dp(46),1));FrameLayout.LayoutParams mp=new FrameLayout.LayoutParams(compact?-1:dp(390),dp(62),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);mp.setMargins(inset,0,inset,compact?dp(88):inset);pane.addView(metrics,mp);
        mapStateText=label("Preparando mapa e proteção…",7,Color.rgb(193,169,159),true);mapStateText.setGravity(Gravity.CENTER);mapStateText.setMaxLines(1);FrameLayout.LayoutParams stateLp=new FrameLayout.LayoutParams(-1,dp(26),Gravity.BOTTOM);stateLp.setMargins(inset,0,inset,compact?dp(62):dp(68));pane.addView(mapStateText,stateLp);
        return pane;
    }

    private LinearLayout buildRightPanel(int height, boolean compact, boolean ultrawide) {
        LinearLayout right=new LinearLayout(this);right.setOrientation(LinearLayout.VERTICAL);int pad=clamp(Math.round(height*.022f),dp(10),dp(17));right.setPadding(pad,pad,pad,pad);right.setBackground(panel(25,Color.rgb(12,7,9),Color.rgb(72,35,40)));
        LinearLayout head=new LinearLayout(this);head.setOrientation(LinearLayout.HORIZONTAL);head.setGravity(Gravity.CENTER_VERTICAL);TextView star=label("★",20,Color.rgb(226,185,76),true);head.addView(star,new LinearLayout.LayoutParams(dp(34),dp(38)));LinearLayout titles=new LinearLayout(this);titles.setOrientation(LinearLayout.VERTICAL);titles.addView(label("EPC",compact?18:22,TEXT,true));TextView ss=label("CENTRAL AUTOMOTIVA",7,ACCENT,true);ss.setLetterSpacing(.12f);titles.addView(ss);head.addView(titles,new LinearLayout.LayoutParams(0,-2,1));clockText=label("--:--",compact?17:20,TEXT,true);head.addView(clockText);right.addView(head);int gap=clamp(Math.round(height*.016f),dp(7),dp(11));
        LinearLayout route=card();LinearLayout.LayoutParams rlp=new LinearLayout.LayoutParams(-1,0,.30f);rlp.setMargins(0,gap,0,0);right.addView(route,rlp);TextView ro=label(destination==null?"RODAGEM LIVRE":"ROTA ATIVA",8,destination==null?GREEN:ACCENT,true);ro.setLetterSpacing(.11f);route.addView(ro);route.addView(label(destination==null?"Estrada sob proteção":shortDestination(destination.label),compact?14:17,TEXT,true));destinationText=label(destination==null?"Radares, clima e alertas ativos.":"Calculando percurso…",compact?9:10,MUTED,false);route.addView(destinationText,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout quick=card();LinearLayout.LayoutParams qlp=new LinearLayout.LayoutParams(-1,0,.25f);qlp.setMargins(0,gap,0,0);right.addView(quick,qlp);TextView qo=label("ACESSO RÁPIDO",8,ACCENT,true);qo.setLetterSpacing(.11f);quick.addView(qo);LinearLayout q1=new LinearLayout(this);q1.setOrientation(LinearLayout.HORIZONTAL);Button radio=action("PTT",false),hud=action("HUD",false),dash=action("DASH",false),central=action("CENTRAL",false);for(Button b:new Button[]{radio,hud,dash,central})q1.addView(b,new LinearLayout.LayoutParams(0,dp(44),1));quick.addView(q1);radio.setOnClickListener(v->startActivity(new Intent(this,RoadRadioActivity.class)));hud.setOnClickListener(v->startActivity(new Intent(this,HudActivity.class)));dash.setOnClickListener(v->startActivity(new Intent(this,CameraActivity.class)));central.setOnClickListener(v->startActivity(new Intent(this,DriveToolsActivity.class)));
        LinearLayout media=card();LinearLayout.LayoutParams mlp=new LinearLayout.LayoutParams(-1,0,.45f);mlp.setMargins(0,gap,0,0);right.addView(media,mlp);TextView mo=label("MÚSICA OFFLINE",8,ACCENT,true);mo.setLetterSpacing(.11f);media.addView(mo);mapPlayerTitle=label("Biblioteca offline",compact?14:17,TEXT,true);mapPlayerTitle.setMaxLines(1);media.addView(mapPlayerTitle);mapPlayerArtist=label("Música local",compact?9:10,MUTED,false);mapPlayerArtist.setMaxLines(1);media.addView(mapPlayerArtist);View spacer=new View(this);media.addView(spacer,new LinearLayout.LayoutParams(1,0,1));LinearLayout controls=new LinearLayout(this);controls.setOrientation(LinearLayout.HORIZONTAL);Button prev=action("◀",false);mapPlayerToggle=action(mapPlayerPlaying?"Ⅱ":"▶",true);Button next=action("▶|",false);controls.addView(prev,new LinearLayout.LayoutParams(0,dp(46),1));controls.addView(mapPlayerToggle,new LinearLayout.LayoutParams(0,dp(46),1));controls.addView(next,new LinearLayout.LayoutParams(0,dp(46),1));media.addView(controls);Button open=action("ABRIR PLAYER",false);media.addView(open,new LinearLayout.LayoutParams(-1,dp(40)));prev.setOnClickListener(v->playerCommand(PlayerService.ACTION_PREVIOUS));mapPlayerToggle.setOnClickListener(v->playerCommand(PlayerService.ACTION_TOGGLE));next.setOnClickListener(v->playerCommand(PlayerService.ACTION_NEXT));open.setOnClickListener(v->startActivity(new Intent(this,MusicPlayerActivity.class)));queryPlayerState();return right;
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
                    mapStore = new RoadPackStore(getApplicationContext());
                    loadedHazardCount = mapStore.hazardCount();
                }
                List<RoadHazard> nearby = mapStore.nearby(lat, lon, 5200);
                if(roadQualityStore==null) roadQualityStore=new RoadQualityStore(getApplicationContext());
                final java.util.ArrayList<RoadQualityStore.Point> quality=roadQualityStore.around(lat,lon,6500);
                if(estradaVivaStore==null) estradaVivaStore=new EstradaVivaStore(getApplicationContext());
                estradaVivaStore.kickRefresh(lat,lon);
                final java.util.ArrayList<EstradaVivaStore.Event> liveEvents=estradaVivaStore.cachedNearby(lat,lon,12000);
                if (offlineRoadStore == null) offlineRoadStore = new OfflineRoadStore(getApplicationContext());
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
                        roadMap.setRoadQualityPoints(quality);
                        roadMap.setLiveEvents(liveEvents);
                        if (finalRoads != null) roadMap.setOfflineRoadGeoJson(finalRoads);
                    }
                    updateMapStatus();
                });
            } catch (Throwable ignored) {}
        });
    }

    private void refreshDestinationRoute(double lat, double lon) {
        DestinationStore.Destination d=destination;if(d==null||!Double.isFinite(lat)||!Double.isFinite(lon)){if(roadMap!=null)roadMap.setRouteGeoJson(null);return;}long now=System.currentTimeMillis();if(routeLoading.get())return;if(activeRoute!=null&&Double.isFinite(lastRouteLat)&&Double.isFinite(lastRouteLon)){double moved=RoadPackStore.distanceM(lat,lon,lastRouteLat,lastRouteLon);if(moved<700&&now-lastRouteAt<120_000L)return;}if(!routeLoading.compareAndSet(false,true))return;if(destinationText!=null)destinationText.setText("Calculando rota…");routeIo.execute(()->{try{RouteEngine.Route route=RouteEngine.fetch(lat,lon,d.lat,d.lon);activeRoute=route;lastRouteAt=System.currentTimeMillis();lastRouteLat=lat;lastRouteLon=lon;ui.post(()->{if(roadMap!=null)roadMap.setRouteGeoJson(route.geoJson);if(universalRouteText!=null)universalRouteText.setText("ROTA · "+route.summary());renderRouteUi(route,0);});}catch(Throwable e){ui.post(()->{if(navInstructionText!=null)navInstructionText.setText("Rota online indisponível");if(navRoadText!=null)navRoadText.setText("A proteção da estrada continua ativa");if(destinationText!=null)destinationText.setText("Rota online indisponível. A proteção continua ativa.");});}finally{routeLoading.set(false);}});
    }

    // NAV_POLISH_V201 helpers
    private TextView metric(String value,String label){TextView t=this.label(value+"\n"+label,11,TEXT,true);t.setGravity(Gravity.CENTER);t.setLineSpacing(0,.95f);return t;}
    private int hasHazardColor(String type){String t=type==null?"":type.toUpperCase(Locale.ROOT);if(t.contains("RADAR"))return Color.rgb(239,41,58);if(t.contains("QUEBRA")||t.contains("LOMBADA"))return Color.rgb(230,155,48);if(t.contains("CAMERA")||t.contains("CÂMERA"))return Color.rgb(226,185,76);return Color.rgb(92,40,43);}
    private String maneuverGlyph(RouteEngine.Route r){if(r==null)return"↑";String m=r.maneuverModifier==null?"":r.maneuverModifier;if(r.maneuverType.contains("roundabout")||r.maneuverType.contains("rotary"))return"↻";if(r.maneuverType.contains("arrive"))return"★";if(m.contains("right"))return"↱";if(m.contains("left"))return"↰";return"↑";}
    private String navDistance(double m){if(m<=0)return"AGORA";if(m>=1000)return String.format(Locale.getDefault(),"%.1f km",m/1000.0);if(m<120)return Math.max(10,Math.round(m/10.0)*10)+" m";return Math.max(50,Math.round(m/50.0)*50)+" m";}
    private String remainDistance(double m){return m>=1000?String.format(Locale.getDefault(),"%.0f km",m/1000.0):Math.max(0,Math.round(m))+" m";}
    private String durationText(double seconds){long min=Math.max(0,Math.round(seconds/60.0)),h=min/60,m=min%60;return h>0?h+"h "+m+"m":m+" min";}
    private void renderRouteUi(RouteEngine.Route route,double moved){if(route==null)return;double rem=Math.max(0,route.distanceM-moved),ratio=route.distanceM<=0?0:Math.min(1,rem/route.distanceM),dur=Math.max(0,route.durationS*ratio),next=Math.max(0,route.nextDistanceM-moved);if(navTurnText!=null)navTurnText.setText(maneuverGlyph(route));if(navDistanceText!=null)navDistanceText.setText(navDistance(next));if(navInstructionText!=null)navInstructionText.setText(route.nextInstruction.isEmpty()?"Siga na rota":route.nextInstruction);if(navRoadText!=null)navRoadText.setText(route.nextRoad.isEmpty()?shortDestination(destination==null?"Destino":destination.label):route.nextRoad);if(navEtaText!=null){String eta=new SimpleDateFormat("HH:mm",Locale.getDefault()).format(new Date(System.currentTimeMillis()+(long)(dur*1000)));navEtaText.setText(eta+"\nCHEGADA");}if(navRemainingText!=null)navRemainingText.setText(remainDistance(rem)+"\nRESTANTE");if(navDurationText!=null)navDurationText.setText(durationText(dur)+"\nDURAÇÃO");if(destinationText!=null)destinationText.setText(remainDistance(rem)+" · "+durationText(dur)+"\n"+(route.nextInstruction.isEmpty()?"Siga na rota":route.nextInstruction));}
    private void updateRouteProgress(double lat,double lon){RouteEngine.Route r=activeRoute;if(r==null||!Double.isFinite(lastRouteLat)||!Double.isFinite(lastRouteLon))return;double moved=RoadPackStore.distanceM(lat,lon,lastRouteLat,lastRouteLon);renderRouteUi(r,Math.min(r.distanceM,moved));}
    private void playerCommand(String action){try{startService(new Intent(this,PlayerService.class).setAction(action));}catch(Throwable ignored){}}
    private void queryPlayerState(){try{startService(new Intent(this,PlayerService.class).setAction(PlayerService.ACTION_QUERY_STATE));}catch(Throwable ignored){}}
    private void registerPlayerReceiver(){if(playerReceiverRegistered)return;try{IntentFilter f=new IntentFilter(PlayerService.ACTION_STATE);if(Build.VERSION.SDK_INT>=33)registerReceiver(playerReceiver,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(playerReceiver,f);playerReceiverRegistered=true;}catch(Throwable ignored){}}
    private void addPortraitPlayer(int width,int outer){LinearLayout strip=new LinearLayout(this);strip.setOrientation(LinearLayout.HORIZONTAL);strip.setGravity(Gravity.CENTER_VERTICAL);strip.setPadding(dp(11),dp(8),dp(9),dp(8));strip.setBackground(panel(17,Color.argb(246,14,8,10),Color.rgb(80,39,40)));TextView mark=label("★",20,Color.rgb(226,185,76),true);mark.setGravity(Gravity.CENTER);strip.addView(mark,new LinearLayout.LayoutParams(dp(42),dp(52)));LinearLayout meta=new LinearLayout(this);meta.setOrientation(LinearLayout.VERTICAL);mapPlayerTitle=label("Biblioteca offline",13,TEXT,true);mapPlayerTitle.setMaxLines(1);mapPlayerArtist=label("Música local",8,MUTED,false);mapPlayerArtist.setMaxLines(1);meta.addView(mapPlayerTitle);meta.addView(mapPlayerArtist);strip.addView(meta,new LinearLayout.LayoutParams(0,-2,1));Button prev=action("◀",false);mapPlayerToggle=action(mapPlayerPlaying?"Ⅱ":"▶",true);Button next=action("▶|",false);strip.addView(prev,new LinearLayout.LayoutParams(dp(48),dp(48)));strip.addView(mapPlayerToggle,new LinearLayout.LayoutParams(dp(54),dp(52)));strip.addView(next,new LinearLayout.LayoutParams(dp(48),dp(48)));prev.setOnClickListener(v->playerCommand(PlayerService.ACTION_PREVIOUS));mapPlayerToggle.setOnClickListener(v->playerCommand(PlayerService.ACTION_TOGGLE));next.setOnClickListener(v->playerCommand(PlayerService.ACTION_NEXT));FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(Math.max(dp(270),width-outer*2-dp(16)),dp(70),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);lp.setMargins(0,0,0,outer+dp(7));root.addView(strip,lp);if(Build.VERSION.SDK_INT>=21)strip.setElevation(dp(76));strip.bringToFront();queryPlayerState();}

    private void installUniversalDriveWidgets(int width,int height){
        if(!"universal".equals(BuildConfig.FIXED_LAYOUT)||root==null)return;
        boolean portrait=height>=width; LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(10),dp(8),dp(10),dp(8));box.setBackground(panel(5,Color.argb(238,18,8,11),Color.rgb(118,38,48)));if(Build.VERSION.SDK_INT>=21)box.setElevation(dp(88));
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);universalNowText=label("VIAGEM AGORA · aguardando GPS",12,TEXT,true);row.addView(universalNowText,new LinearLayout.LayoutParams(0,dp(40),1));Button save=action("SALVAR",true);Button plan=action("PLANO",false);row.addView(save,new LinearLayout.LayoutParams(dp(76),dp(40)));LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(dp(76),dp(40));pp.setMargins(dp(6),0,0,0);row.addView(plan,pp);box.addView(row);universalAheadText=label(universalUpcoming.isEmpty()?"À FRENTE · lendo base local":"À FRENTE · "+universalUpcoming,10,Color.rgb(226,185,76),true);universalAheadText.setMaxLines(1);box.addView(universalAheadText);universalMetaText=label("BASE LOCAL · qualidade da via preparando",9,MUTED,false);box.addView(universalMetaText);universalRouteText=label(activeRoute==null?(DriveSettings.onlyRoadMode(this)?"MODO SÓ ESTRADA":"SEM ROTA"):"ROTA · "+activeRoute.summary(),9,MUTED,true);box.addView(universalRouteText);
        save.setOnClickListener(v->saveCurrentEvent());plan.setOnClickListener(v->startActivity(new Intent(this,TripPlannerActivity.class)));
        int bw=portrait?Math.max(dp(250),width-dp(34)):Math.min(dp(620),Math.max(dp(390),(int)(width*.56f)));FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(bw,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);lp.setMargins(0,0,0,portrait?dp(48):dp(12));root.addView(box,lp);box.bringToFront();updateUniversalDriveWidgets(null);
    }

    private void updateUniversalDriveWidgets(Intent state){
        if(universalNowText==null)return;int delta=universalLimit>0?(int)Math.round(universalSpeed-universalLimit):0;String now="VIAGEM AGORA · "+Math.round(Math.max(0,universalSpeed))+" km/h"+(universalLimit>0?" · LIM "+universalLimit:"");if(delta>=2)now+=" · REDUZA "+delta;universalNowText.setText(now);if(universalAheadText!=null)universalAheadText.setText(universalUpcoming.isEmpty()?"À FRENTE · nenhum perigo próximo":"À FRENTE · "+universalUpcoming);VehicleProfileStore.Profile v=VehicleProfileStore.active(this);int q=roadQualityStore==null||!Double.isFinite(currentLatForSave)?100:roadQualityStore.scoreNear(currentLatForSave,currentLonForSave);String meta=(universalConfidence.isEmpty()?"BASE LOCAL":universalConfidence)+(universalSource.isEmpty()?"":" · "+universalSource)+" · VIA "+q+"/100 · AUTONOMIA ~"+Math.round(v.autonomyKm())+" km";if(state!=null&&state.getBooleanExtra("rain_mode",false))meta+=" · CHUVA";if(state!=null&&state.getBooleanExtra("offline_test_mode",false))meta+=" · TESTE OFFLINE";if(universalMetaText!=null)universalMetaText.setText(meta);
    }

    private void saveCurrentEvent(){IncidentStore.save(this,currentLatForSave,currentLonForSave,universalSpeed,universalHazard,universalUpcoming,"Salvo pelo painel");try{JSONObject a=TripRecorder.activeSnapshot(this);if(a.length()>0){/* metadata is already preserved in IncidentStore */}}catch(Throwable ignored){}Toast.makeText(this,"Acontecimento salvo · GPS + velocidade + contexto",Toast.LENGTH_SHORT).show();}

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
        value += "\n" + RoadWeatherMonitor.compactStatus(this);
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

    // UNIVERSAL_ORIENTATION_V153: fixed flavors retain their old behavior,
    // while the Universal flavor follows the real screen shape on every rotation.
    private boolean usePortraitLayout() {
        String mode = BuildConfig.FIXED_LAYOUT == null ? "" : BuildConfig.FIXED_LAYOUT;
        if ("vertical".equals(mode)) return true;
        if ("horizontal".equals(mode)) return false;
        int[] size = screenSize();
        return size[1] >= size[0];
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
        if (playerReceiverRegistered) { try { unregisterReceiver(playerReceiver); } catch (Throwable ignored) {} playerReceiverRegistered=false; }
        try { io.shutdownNow(); } catch (Throwable ignored) {}
        try { routeIo.shutdownNow(); } catch (Throwable ignored) {}
        if (roadMap != null) roadMap.onDestroyMap();
        super.onDestroy();
    }
}

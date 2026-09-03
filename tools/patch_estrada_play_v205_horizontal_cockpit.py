#!/usr/bin/env python3
from pathlib import Path

APP = Path('estrada-play-comunista-app')
BUILD = APP / 'app/build.gradle'
ROAD = APP / 'app/src/main/java/com/estradaplay/comunista/RoadMapActivity.java'


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f'missing marker: {label}')
    return text.replace(old, new, 1)


def replace_method(text: str, signature: str, replacement: str) -> str:
    start = text.find(signature)
    if start < 0:
        raise SystemExit(f'missing method: {signature}')
    brace = text.find('{', start)
    if brace < 0:
        raise SystemExit(f'missing opening brace: {signature}')
    depth = 0
    end = -1
    for i in range(brace, len(text)):
        c = text[i]
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                end = i + 1
                break
    if end < 0:
        raise SystemExit(f'unbalanced method: {signature}')
    return text[:start] + replacement.rstrip() + text[end:]

build = BUILD.read_text(encoding='utf-8')
if 'versionCode 205' not in build:
    build = replace_once(build, 'versionCode 204', 'versionCode 205', 'versionCode 204')
    build = replace_once(build, "versionName '2.0.4'", "versionName '2.0.5'", 'versionName 2.0.4')
BUILD.write_text(build, encoding='utf-8')

r = ROAD.read_text(encoding='utf-8')

old_switch = 'if (usePortraitLayout()) { buildPortraitUi(width, height); } else { buildLandscapeUi(width, height); }'
new_switch = '''boolean portraitLayout = usePortraitLayout();
        applyAutomotiveSystemUiV205(!portraitLayout);
        if (portraitLayout) { buildPortraitUi(width, height); } else { buildLandscapeUi(width, height); }'''
if 'applyAutomotiveSystemUiV205(!portraitLayout)' not in r:
    r = replace_once(r, old_switch, new_switch, 'responsive orientation switch')

landscape = r'''    // HORIZONTAL_COCKPIT_V205: true automotive layout, not a stretched portrait screen.
    private void buildLandscapeUi(int width, int height) {
        float ratio = height <= 0 ? 1.8f : (float) width / (float) height;
        boolean compact = height < dp(430) || ratio < 1.58f;
        boolean ultrawide = ratio >= 2.10f;

        int outer = clamp(Math.round(height * 0.015f), dp(6), dp(12));
        int gap = clamp(Math.round(height * 0.012f), dp(6), dp(10));
        int railWidth = clamp(Math.round(width * 0.064f), dp(88), dp(106));
        int dockWidth = ultrawide
                ? clamp(Math.round(width * 0.175f), dp(235), dp(310))
                : clamp(Math.round(width * 0.185f), dp(215), dp(285));

        int mapLeft = outer + railWidth + gap;
        int mapRight = width - outer - dockWidth - gap;
        int mapWidth = Math.max(dp(420), mapRight - mapLeft);
        int mapHeight = Math.max(dp(300), height - outer * 2);

        FrameLayout mapPane = buildLandscapeMapPaneV205(mapWidth, mapHeight, compact);
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(-1, -1);
        mp.setMargins(mapLeft, outer, width - mapRight, outer);
        root.addView(mapPane, mp);

        LinearLayout rail = buildLandscapeRailV205(height, compact);
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(railWidth, -1, Gravity.LEFT);
        rp.setMargins(outer, outer, 0, outer);
        root.addView(rail, rp);

        LinearLayout dock = buildLandscapeDockV205(height, compact);
        FrameLayout.LayoutParams dpane = new FrameLayout.LayoutParams(dockWidth, -1, Gravity.RIGHT);
        dpane.setMargins(0, outer, outer, outer);
        root.addView(dock, dpane);

        Button recenter = action("◎", false);
        recenter.setTextSize(20);
        recenter.setContentDescription("Centralizar mapa");
        recenter.setOnClickListener(v -> { if (roadMap != null) roadMap.recenter(); });
        FrameLayout.LayoutParams rc = new FrameLayout.LayoutParams(dp(52), dp(52));
        rc.leftMargin = Math.max(mapLeft + dp(10), mapRight - dp(64));
        rc.topMargin = Math.max(outer + dp(120), height - outer - dp(66));
        root.addView(recenter, rc);

        if (Build.VERSION.SDK_INT >= 21) {
            rail.setElevation(dp(54));
            dock.setElevation(dp(54));
            recenter.setElevation(dp(66));
        }
        rail.bringToFront();
        dock.bringToFront();
        recenter.bringToFront();
    }'''
r = replace_method(r, '    private void buildLandscapeUi(int width, int height)', landscape)

helpers = r'''

    private void applyAutomotiveSystemUiV205(boolean landscape) {
        try {
            View decor = getWindow().getDecorView();
            if (landscape) {
                decor.setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                        View.SYSTEM_UI_FLAG_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            } else {
                decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            }
        } catch (Throwable ignored) {}
    }

    private LinearLayout buildLandscapeRailV205(int height, boolean compact) {
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setGravity(Gravity.CENTER_HORIZONTAL);
        rail.setPadding(dp(7), dp(8), dp(7), dp(8));
        rail.setBackground(panel(20, Color.rgb(11, 6, 8), Color.rgb(80, 37, 43)));

        LinearLayout emblem = new LinearLayout(this);
        emblem.setOrientation(LinearLayout.VERTICAL);
        emblem.setGravity(Gravity.CENTER);
        emblem.setBackground(panel(17, Color.rgb(105, 9, 25), Color.rgb(174, 29, 46)));
        TextView star = label("★", compact ? 18 : 21, Color.rgb(226,185,76), true);
        star.setGravity(Gravity.CENTER);
        TextView brand = label("EPC", compact ? 13 : 15, TEXT, true);
        brand.setGravity(Gravity.CENTER);
        emblem.addView(star);
        emblem.addView(brand);
        rail.addView(emblem, new LinearLayout.LayoutParams(-1, clamp(Math.round(height * .125f), dp(62), dp(82))));

        TextView active = label("● ATIVO", 7, GREEN, true);
        active.setGravity(Gravity.CENTER);
        active.setSingleLine(true);
        rail.addView(active, new LinearLayout.LayoutParams(-1, dp(26)));

        Button estrada = landscapeNavV205("ESTRADA", true);
        Button music = landscapeNavV205("MÚSICA", false);
        Button radio = landscapeNavV205("RÁDIO", false);
        Button trip = landscapeNavV205("VIAGEM", false);
        Button central = landscapeNavV205("CENTRAL", false);
        for (Button b : new Button[]{estrada, music, radio, trip, central}) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, 0, 1f);
            p.setMargins(0, dp(3), 0, dp(3));
            rail.addView(b, p);
        }
        estrada.setOnClickListener(v -> { if (roadMap != null) roadMap.recenter(); });
        music.setOnClickListener(v -> startActivity(new Intent(this, MusicPlayerActivity.class)));
        radio.setOnClickListener(v -> startActivity(new Intent(this, RoadRadioActivity.class)));
        trip.setOnClickListener(v -> startActivity(new Intent(this, TripPlannerActivity.class)));
        central.setOnClickListener(v -> startActivity(new Intent(this, DriveToolsActivity.class)));

        TextView version = label("v" + BuildConfig.VERSION_NAME, 8, Color.rgb(132,94,90), true);
        version.setGravity(Gravity.CENTER);
        version.setSingleLine(true);
        rail.addView(version, new LinearLayout.LayoutParams(-1, dp(25)));
        return rail;
    }

    private Button landscapeNavV205(String text, boolean active) {
        Button b = nav(text, active, 0);
        b.setSingleLine(true);
        b.setHorizontallyScrolling(true);
        b.setTextSize(7.6f);
        b.setLetterSpacing(0.035f);
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    private FrameLayout buildLandscapeMapPaneV205(int width, int height, boolean compact) {
        FrameLayout pane = new FrameLayout(this);
        pane.setBackground(panel(22, Color.rgb(7,4,6), Color.rgb(70,32,38)));
        pane.setClipToPadding(true);
        roadMap = new RoadMapView(this);
        pane.addView(roadMap, new FrameLayout.LayoutParams(-1, -1));

        int inset = clamp(Math.round(height * .018f), dp(8), dp(13));
        int guideH = clamp(Math.round(height * .125f), dp(78), dp(96));
        int guideW = Math.min(Math.max(dp(300), width - dp(126)), Math.max(dp(360), Math.round(width * .62f)));

        LinearLayout guidance = new LinearLayout(this);
        guidance.setOrientation(LinearLayout.HORIZONTAL);
        guidance.setGravity(Gravity.CENTER_VERTICAL);
        guidance.setPadding(dp(10), dp(7), dp(10), dp(7));
        guidance.setBackground(panel(17, Color.argb(244, 10, 6, 8), Color.rgb(92,47,44)));

        navTurnText = label(destination == null ? "★" : "↑", compact ? 27 : 34,
                destination == null ? Color.rgb(226,185,76) : Color.rgb(255,75,83), true);
        navTurnText.setGravity(Gravity.CENTER);
        guidance.addView(navTurnText, new LinearLayout.LayoutParams(dp(56), -1));

        LinearLayout distanceBox = new LinearLayout(this);
        distanceBox.setOrientation(LinearLayout.VERTICAL);
        distanceBox.setGravity(Gravity.CENTER_VERTICAL);
        navDistanceText = label(destination == null ? "LIVRE" : "—", compact ? 18 : 23, TEXT, true);
        navDistanceText.setSingleLine(true);
        distanceBox.addView(navDistanceText);
        TextView until = label(destination == null ? "PROTEÇÃO" : "PRÓXIMA", 7, MUTED, true);
        until.setSingleLine(true);
        distanceBox.addView(until);
        guidance.addView(distanceBox, new LinearLayout.LayoutParams(compact ? dp(76) : dp(94), -1));

        LinearLayout gText = new LinearLayout(this);
        gText.setOrientation(LinearLayout.VERTICAL);
        gText.setGravity(Gravity.CENTER_VERTICAL);
        navInstructionText = label(destination == null ? "Siga a estrada" : "Calculando rota…", compact ? 13 : 16, TEXT, true);
        navInstructionText.setSingleLine(true);
        navInstructionText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        gText.addView(navInstructionText);
        navRoadText = label(destination == null ? "Alertas e clima ativos" : "", compact ? 8 : 10, ACCENT, true);
        navRoadText.setSingleLine(true);
        navRoadText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        gText.addView(navRoadText);
        navWeatherText = label(RoadWeatherMonitor.compactStatus(this), 8, Color.rgb(226,185,76), true);
        navWeatherText.setSingleLine(true);
        navWeatherText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        gText.addView(navWeatherText);
        guidance.addView(gText, new LinearLayout.LayoutParams(0, -1, 1));

        gpsText = label("GPS", 8, GREEN, true);
        gpsText.setGravity(Gravity.CENTER);
        gpsText.setBackground(panel(100, Color.argb(190,18,54,39), 0));
        guidance.addView(gpsText, new LinearLayout.LayoutParams(dp(48), dp(30)));
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(guideW, guideH, Gravity.TOP | Gravity.LEFT);
        gp.setMargins(inset, inset, 0, 0);
        pane.addView(guidance, gp);

        LinearLayout speed = new LinearLayout(this);
        speed.setOrientation(LinearLayout.VERTICAL);
        speed.setGravity(Gravity.CENTER);
        speed.setBackground(panel(100, Color.argb(244, 11,7,8), Color.rgb(128,58,46)));
        speedText = label("0", compact ? 27 : 33, TEXT, true);
        speedText.setGravity(Gravity.CENTER);
        speed.addView(speedText);
        TextView kmh = label("km/h", 7, MUTED, true);
        kmh.setGravity(Gravity.CENTER);
        speed.addView(kmh);
        navLimitText = label("—", compact ? 13 : 16, Color.rgb(104,12,23), true);
        navLimitText.setGravity(Gravity.CENTER);
        navLimitText.setBackground(panel(100, Color.rgb(248,238,220), ACCENT));
        speed.addView(navLimitText, new LinearLayout.LayoutParams(dp(39), dp(28)));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(dp(88), dp(108), Gravity.TOP | Gravity.RIGHT);
        sp.setMargins(0, inset, inset, 0);
        pane.addView(speed, sp);

        hazardCard = new LinearLayout(this);
        hazardCard.setOrientation(LinearLayout.VERTICAL);
        hazardCard.setGravity(Gravity.CENTER_VERTICAL);
        hazardCard.setPadding(dp(11), dp(8), dp(11), dp(8));
        hazardCard.setBackground(panel(15, Color.argb(235,16,8,10), Color.rgb(91,39,43)));
        protectionText = label("PROTEÇÃO ATIVA", 7, GREEN, true);
        protectionText.setSingleLine(true);
        hazardTitle = label("Estrada livre à frente", compact ? 11 : 13, TEXT, true);
        hazardTitle.setSingleLine(true);
        hazardTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        hazardDetail = label("Monitorando seu sentido", 8, MUTED, false);
        hazardDetail.setSingleLine(true);
        hazardDetail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        hazardCard.addView(protectionText);
        hazardCard.addView(hazardTitle);
        hazardCard.addView(hazardDetail);
        int alertW = Math.min(dp(310), Math.max(dp(210), Math.round(width * .34f)));
        FrameLayout.LayoutParams ap = new FrameLayout.LayoutParams(alertW, -2, Gravity.LEFT | Gravity.BOTTOM);
        ap.setMargins(inset, 0, 0, dp(78));
        pane.addView(hazardCard, ap);

        LinearLayout metrics = new LinearLayout(this);
        metrics.setOrientation(LinearLayout.HORIZONTAL);
        metrics.setGravity(Gravity.CENTER);
        metrics.setPadding(dp(7), dp(5), dp(7), dp(5));
        metrics.setBackground(panel(15, Color.argb(236,10,7,8), Color.rgb(70,36,37)));
        navEtaText = metric("--:--", "CHEGADA");
        navRemainingText = metric("—", "RESTANTE");
        navDurationText = metric("—", "DURAÇÃO");
        metrics.addView(navEtaText, new LinearLayout.LayoutParams(0, dp(43), 1));
        metrics.addView(navRemainingText, new LinearLayout.LayoutParams(0, dp(43), 1));
        metrics.addView(navDurationText, new LinearLayout.LayoutParams(0, dp(43), 1));
        int metricsW = Math.min(dp(390), Math.max(dp(300), Math.round(width * .43f)));
        FrameLayout.LayoutParams mt = new FrameLayout.LayoutParams(metricsW, dp(56), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        mt.setMargins(0, 0, 0, inset);
        pane.addView(metrics, mt);

        mapStateText = label("Mapa + proteção", 7, Color.rgb(190,166,158), true);
        mapStateText.setSingleLine(true);
        mapStateText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mapStateText.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams st = new FrameLayout.LayoutParams(Math.min(dp(260), Math.round(width * .30f)), dp(24), Gravity.RIGHT | Gravity.BOTTOM);
        st.setMargins(0, 0, inset + dp(56), inset + dp(8));
        pane.addView(mapStateText, st);
        return pane;
    }

    private LinearLayout buildLandscapeDockV205(int height, boolean compact) {
        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.VERTICAL);
        dock.setPadding(dp(10), dp(10), dp(10), dp(10));
        dock.setBackground(panel(21, Color.rgb(11,6,8), Color.rgb(74,34,40)));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView star = label("★", 18, Color.rgb(226,185,76), true);
        head.addView(star, new LinearLayout.LayoutParams(dp(30), dp(36)));
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.addView(label("EPC", compact ? 16 : 19, TEXT, true));
        TextView sub = label("CENTRAL AUTOMOTIVA", 6.5f, ACCENT, true);
        sub.setSingleLine(true);
        brand.addView(sub);
        head.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        clockText = label("--:--", compact ? 14 : 17, TEXT, true);
        clockText.setSingleLine(true);
        head.addView(clockText);
        dock.addView(head, new LinearLayout.LayoutParams(-1, dp(48)));

        int gap = dp(7);
        LinearLayout route = card();
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, 0, .31f);
        rlp.setMargins(0, gap, 0, 0);
        dock.addView(route, rlp);
        TextView ro = label(destination == null ? "RODAGEM LIVRE" : "ROTA ATIVA", 7.5f, destination == null ? GREEN : ACCENT, true);
        ro.setSingleLine(true);
        route.addView(ro);
        TextView routeTitle = label(destination == null ? "Estrada protegida" : shortDestination(destination.label), compact ? 13 : 15, TEXT, true);
        routeTitle.setMaxLines(2);
        route.addView(routeTitle);
        destinationText = label(destination == null ? "Radares, clima e alertas ativos." : "Calculando percurso…", compact ? 8 : 9, MUTED, false);
        destinationText.setMaxLines(3);
        route.addView(destinationText, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout quick = card();
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(-1, 0, .20f);
        qlp.setMargins(0, gap, 0, 0);
        dock.addView(quick, qlp);
        TextView qo = label("ATALHOS", 7.5f, ACCENT, true);
        qo.setSingleLine(true);
        quick.addView(qo);
        LinearLayout q = new LinearLayout(this);
        q.setOrientation(LinearLayout.HORIZONTAL);
        Button radio = action("PTT", false), hud = action("HUD", false), dash = action("CAM", false), central = action("MENU", false);
        for (Button b : new Button[]{radio, hud, dash, central}) {
            b.setPadding(0,0,0,0);
            b.setTextSize(7.5f);
            q.addView(b, new LinearLayout.LayoutParams(0, dp(40), 1));
        }
        quick.addView(q);
        radio.setOnClickListener(v -> startActivity(new Intent(this, RoadRadioActivity.class)));
        hud.setOnClickListener(v -> startActivity(new Intent(this, HudActivity.class)));
        dash.setOnClickListener(v -> startActivity(new Intent(this, CameraActivity.class)));
        central.setOnClickListener(v -> startActivity(new Intent(this, DriveToolsActivity.class)));

        LinearLayout media = card();
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, 0, .49f);
        mlp.setMargins(0, gap, 0, 0);
        dock.addView(media, mlp);
        TextView mo = label("MÚSICA OFFLINE", 7.5f, ACCENT, true);
        mo.setSingleLine(true);
        media.addView(mo);
        mapPlayerTitle = label("Biblioteca offline", compact ? 13 : 15, TEXT, true);
        mapPlayerTitle.setSingleLine(true);
        mapPlayerTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        media.addView(mapPlayerTitle);
        mapPlayerArtist = label("Música local", compact ? 8 : 9, MUTED, false);
        mapPlayerArtist.setSingleLine(true);
        mapPlayerArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        media.addView(mapPlayerArtist);
        media.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        Button prev = action("◀", false);
        mapPlayerToggle = action(mapPlayerPlaying ? "Ⅱ" : "▶", true);
        Button next = action("▶|", false);
        controls.addView(prev, new LinearLayout.LayoutParams(0, dp(43), 1));
        controls.addView(mapPlayerToggle, new LinearLayout.LayoutParams(0, dp(43), 1));
        controls.addView(next, new LinearLayout.LayoutParams(0, dp(43), 1));
        media.addView(controls);
        Button open = action("ABRIR MÚSICA", false);
        open.setTextSize(8);
        media.addView(open, new LinearLayout.LayoutParams(-1, dp(36)));
        prev.setOnClickListener(v -> playerCommand(PlayerService.ACTION_PREVIOUS));
        mapPlayerToggle.setOnClickListener(v -> playerCommand(PlayerService.ACTION_TOGGLE));
        next.setOnClickListener(v -> playerCommand(PlayerService.ACTION_NEXT));
        open.setOnClickListener(v -> startActivity(new Intent(this, MusicPlayerActivity.class)));
        queryPlayerState();
        return dock;
    }
'''

if 'private void applyAutomotiveSystemUiV205' not in r:
    marker = '    private void buildPortraitUi(int width, int height) {'
    if marker not in r:
        raise SystemExit('missing portrait marker')
    r = r.replace(marker, helpers + '\n' + marker, 1)

ROAD.write_text(r, encoding='utf-8')
print('EPC 2.0.5 horizontal automotive cockpit patch applied')

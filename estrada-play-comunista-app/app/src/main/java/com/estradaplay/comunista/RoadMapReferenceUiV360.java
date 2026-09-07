package com.estradaplay.comunista;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Locale;

/**
 * ROAD_REFERENCE_UI_V360
 * Reference cockpit rebuilt after real-device validation. The map pane is never hidden; legacy
 * controls are removed selectively and all geometry is based on the actual viewport proportions.
 */
final class RoadMapReferenceUiV360 implements Application.ActivityLifecycleCallbacks {
    private static final String TAG = "epc-road-reference-v360";
    private final Application app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WeakReference<RoadMapActivity> resumed = new WeakReference<>(null);

    private FrameLayout host;
    private boolean landscapeBuilt;
    private ReferenceSpeedometerView speedometer;
    private TextView gps, limit, guideTitle, guideSub, playerTitle, playerArtist;
    private TextView eta, remainingTime, remainingDistance, average;
    private Button play;
    private double lastSpeed;
    private int lastLimit;
    private boolean playing;
    private boolean gpsOk;
    private String musicTitle = "Biblioteca offline";
    private String musicArtist = "Música local";

    static void install(Application app) {
        if (app == null) return;
        RoadMapReferenceUiV360 ui = new RoadMapReferenceUiV360(app);
        app.registerActivityLifecycleCallbacks(ui);
        ui.register();
    }

    private RoadMapReferenceUiV360(Application app) { this.app = app; }

    private void register() {
        IntentFilter f = new IntentFilter();
        f.addAction(RoadSafetyService.ACTION_STATE);
        f.addAction(PlayerService.ACTION_STATE);
        try {
            if (Build.VERSION.SDK_INT >= 33) InternalBroadcasts.register(app, receiver, f);
            else InternalBroadcasts.register(app, receiver, f);
        } catch (Throwable ignored) {}
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (i == null) return;
            if (RoadSafetyService.ACTION_STATE.equals(i.getAction())) {
                lastSpeed = i.getDoubleExtra("speed_kmh", lastSpeed);
                lastLimit = i.getIntExtra("limit_kmh", i.getIntExtra("road_limit_kmh", lastLimit));
                double lat = i.getDoubleExtra("lat", Double.NaN);
                double lon = i.getDoubleExtra("lon", Double.NaN);
                gpsOk = Double.isFinite(lat) && Double.isFinite(lon)
                        && i.getBooleanExtra("protection_available", true);
            } else if (PlayerService.ACTION_STATE.equals(i.getAction())) {
                String t = i.getStringExtra("title"), a = i.getStringExtra("artist");
                if (t != null && !t.trim().isEmpty()) musicTitle = t.trim();
                if (a != null && !a.trim().isEmpty()) musicArtist = a.trim();
                playing = i.getBooleanExtra("playing", false);
            }
            RoadMapActivity activity = resumed.get();
            if (activity != null && !activity.isFinishing()) main.postDelayed(() -> apply(activity), 35L);
        }
    };

    private void apply(RoadMapActivity a) {
        FrameLayout root = field(a, "root", FrameLayout.class);
        RoadMapView map = field(a, "roadMap", RoadMapView.class);
        if (root == null || map == null || !(map.getParent() instanceof FrameLayout)) return;
        FrameLayout mapPane = (FrameLayout) map.getParent();
        int w = root.getWidth(), h = root.getHeight();
        if (w <= 0 || h <= 0) return;
        boolean landscape = w > h || "horizontal".equals(BuildConfig.FIXED_LAYOUT);

        FrameLayout.LayoutParams mp = mapPane.getLayoutParams() instanceof FrameLayout.LayoutParams
                ? (FrameLayout.LayoutParams) mapPane.getLayoutParams()
                : new FrameLayout.LayoutParams(-1, -1);
        mp.width = -1;
        mp.height = -1;
        mp.gravity = Gravity.FILL;
        int edge = Math.max(2, Math.round(Math.min(w, h) * .006f));
        mp.setMargins(edge, edge, edge, edge);
        mapPane.setLayoutParams(mp);
        mapPane.setVisibility(View.VISIBLE);

        hideLegacy(a, root, mapPane);
        softenMapTint(map);

        View existing = root.findViewWithTag(TAG);
        if (!(existing instanceof FrameLayout) || host != existing || landscapeBuilt != landscape) {
            if (existing != null) root.removeView(existing);
            host = new FrameLayout(a);
            host.setTag(TAG);
            host.setClickable(false);
            host.setFocusable(false);
            root.addView(host, new FrameLayout.LayoutParams(-1, -1));
            landscapeBuilt = landscape;
            if (landscape) buildLandscape(a, w, h); else buildPortrait(a, w, h);
        }
        sync(a);
        styleCancelRoute(a, root, landscape, w, h);
    }

    private void hideLegacy(RoadMapActivity a, FrameLayout root, FrameLayout mapPane) {
        // The old V350 used two levels for navEtaText and accidentally hid mapPane itself.
        hideParent(field(a, "navInstructionText", TextView.class), 2);
        hideParent(field(a, "speedText", TextView.class), 1);
        hideParent(field(a, "navEtaText", TextView.class), 1);
        hideParent(field(a, "mapPlayerTitle", TextView.class), 2);
        LinearLayout hazard = field(a, "hazardCard", LinearLayout.class);
        if (hazard != null) hazard.setVisibility(View.GONE);

        for (int i = 0; i < root.getChildCount(); i++) {
            View v = root.getChildAt(i);
            if (v == mapPane || v == host || TAG.equals(v.getTag())) continue;
            String s = textOf(v).toUpperCase(Locale.ROOT);
            boolean oldLandscapeRail = s.contains("ESTRADA") && s.contains("RÁDIO") && s.contains("VIAGEM") && s.contains("CENTRAL");
            boolean oldLandscapeDock = s.contains("CENTRAL AUTOMOTIVA");
            boolean oldPortraitRail = s.contains("PTT") && s.contains("DASH") && s.contains("CENTRAL");
            boolean oldPlayer = s.contains("BIBLIOTECA OFFLINE") && !s.contains("PROTEÇÃO TEMPORARIAMENTE");
            boolean oldRecenter = v instanceof Button && "◎".equals(String.valueOf(((Button) v).getText()).trim());
            if (oldLandscapeRail || oldLandscapeDock || oldPortraitRail || oldPlayer || oldRecenter) {
                v.setVisibility(View.GONE);
            }
        }
    }

    private void softenMapTint(RoadMapView map) {
        try {
            View tint = field(map, "nightTint", View.class);
            if (tint != null) {
                tint.setVisibility(View.VISIBLE);
                tint.setBackgroundColor(Color.argb(72, 4, 0, 8));
            }
        } catch (Throwable ignored) {}
    }

    private void buildLandscape(RoadMapActivity a, int w, int h) {
        int m = Math.max(dp(6), Math.round(Math.min(w, h) * .018f));
        int gap = Math.max(dp(5), Math.round(h * .015f));
        int sideW = Math.round(w * .275f);
        int bottomH = Math.round(h * .135f);
        int topH = Math.round(h * .145f);
        int usableSide = h - bottomH - m * 3 - gap;
        int speedH = Math.round(usableSide * .53f);
        int playerH = Math.max(Math.round(h * .27f), usableSide - speedH);
        if (speedH + playerH > usableSide) playerH = Math.max(dp(120), usableSide - speedH);

        LinearLayout top = row(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Math.round(w * .014f), 0, Math.round(w * .014f), 0);
        top.setBackground(round(Color.argb(242, 3, 5, 8), 20, Color.rgb(177, 16, 38), 1));
        LinearLayout brand = col(a);
        brand.addView(txt(a, "★  LIVRE", 24, Color.WHITE, false));
        brand.addView(txt(a, "PROTEÇÃO", 9, Color.rgb(186, 176, 178), false));
        top.addView(brand, new LinearLayout.LayoutParams(Math.round(w * .17f), -1));
        View divider = new View(a);
        divider.setBackgroundColor(Color.rgb(185, 18, 40));
        LinearLayout.LayoutParams dv = new LinearLayout.LayoutParams(Math.max(2, Math.round(w * .0015f)), Math.round(topH * .64f));
        dv.setMargins(Math.round(w * .008f), 0, Math.round(w * .018f), 0);
        top.addView(divider, dv);
        LinearLayout guide = col(a);
        guide.setGravity(Gravity.CENTER_VERTICAL);
        guideTitle = txt(a, "Siga a estrada", 20, Color.WHITE, false);
        guideSub = txt(a, "Navegação ativa", 10, Color.rgb(174, 160, 162), false);
        guide.addView(guideTitle);
        guide.addView(guideSub);
        top.addView(guide, new LinearLayout.LayoutParams(0, -1, 1f));
        gps = pill(a, "●  GPS ATIVO", Color.rgb(17, 220, 112));
        top.addView(gps, new LinearLayout.LayoutParams(Math.round(w * .115f), Math.round(topH * .55f)));
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(w - sideW - m * 3, topH, Gravity.TOP | Gravity.LEFT);
        tp.setMargins(m, m, 0, 0);
        host.addView(top, tp);

        LinearLayout speedCard = col(a);
        speedCard.setGravity(Gravity.CENTER);
        speedCard.setBackground(round(Color.argb(245, 3, 4, 7), 22, Color.rgb(170, 16, 37), 1));
        speedometer = new ReferenceSpeedometerView(a);
        speedCard.addView(speedometer, new LinearLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(sideW, speedH, Gravity.TOP | Gravity.RIGHT);
        sp.setMargins(0, m, m, 0);
        host.addView(speedCard, sp);

        LinearLayout player = col(a);
        player.setPadding(Math.round(sideW * .06f), Math.round(playerH * .06f), Math.round(sideW * .06f), Math.round(playerH * .05f));
        player.setBackground(round(Color.argb(245, 4, 5, 8), 22, Color.rgb(170, 16, 37), 1));
        player.addView(txt(a, "MÚSICA OFFLINE", 9, Color.rgb(225, 36, 58), true));
        playerTitle = txt(a, musicTitle, 17, Color.WHITE, false);
        playerArtist = txt(a, musicArtist, 10, Color.rgb(174, 160, 162), false);
        player.addView(playerTitle);
        player.addView(playerArtist);
        View wave = new View(a);
        wave.setBackgroundColor(Color.argb(150, 176, 15, 37));
        LinearLayout.LayoutParams waveLp = new LinearLayout.LayoutParams(-1, Math.max(2, Math.round(playerH * .012f)));
        waveLp.setMargins(0, Math.round(playerH * .10f), 0, Math.round(playerH * .08f));
        player.addView(wave, waveLp);
        LinearLayout controls = row(a);
        controls.setGravity(Gravity.CENTER);
        Button prev = mediaButton(a, "|◀"), next = mediaButton(a, "▶|");
        play = mediaButton(a, playing ? "Ⅱ" : "▶");
        play.setTextSize(24);
        play.setBackground(round(Color.rgb(220, 13, 39), 100, 0, 0));
        int controlH = Math.max(dp(52), Math.round(playerH * .36f));
        controls.addView(prev, new LinearLayout.LayoutParams(0, controlH, 1f));
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(0, controlH, 1.12f);
        playLp.setMargins(Math.round(sideW * .04f), 0, Math.round(sideW * .04f), 0);
        controls.addView(play, playLp);
        controls.addView(next, new LinearLayout.LayoutParams(0, controlH, 1f));
        player.addView(controls, new LinearLayout.LayoutParams(-1, 0, 1f));
        prev.setOnClickListener(v -> playerAction(a, PlayerService.ACTION_PREVIOUS));
        play.setOnClickListener(v -> playerAction(a, PlayerService.ACTION_TOGGLE));
        next.setOnClickListener(v -> playerAction(a, PlayerService.ACTION_NEXT));
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(sideW, playerH, Gravity.TOP | Gravity.RIGHT);
        pp.setMargins(0, m + speedH + gap, m, 0);
        host.addView(player, pp);

        int centralSize = Math.round(Math.min(h * .18f, w * .075f));
        Button central = roundButton(a, "◎\nCENTRAL");
        central.setOnClickListener(v -> a.startActivity(new Intent(a, DriveToolsActivity.class)));
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(centralSize, centralSize, Gravity.BOTTOM | Gravity.LEFT);
        cp.setMargins(m + Math.round(w * .012f), 0, 0, bottomH + m + gap);
        host.addView(central, cp);

        LinearLayout metrics = row(a);
        metrics.setGravity(Gravity.CENTER_VERTICAL);
        metrics.setPadding(Math.round(w * .012f), 0, Math.round(w * .012f), 0);
        metrics.setBackground(round(Color.argb(245, 3, 4, 7), 18, Color.rgb(170, 16, 37), 1));
        eta = metric(a, "—:—", "CHEGADA");
        remainingTime = metric(a, "—", "RESTANTE");
        remainingDistance = metric(a, "—", "DISTÂNCIA");
        average = metric(a, "0 km/h", "MÉDIA");
        metrics.addView(metricWrap(a, "⚑", eta), new LinearLayout.LayoutParams(0, -1, 1f));
        metrics.addView(metricWrap(a, "◷", remainingTime), new LinearLayout.LayoutParams(0, -1, 1f));
        metrics.addView(metricWrap(a, "╱╲", remainingDistance), new LinearLayout.LayoutParams(0, -1, 1f));
        metrics.addView(metricWrap(a, "◴", average), new LinearLayout.LayoutParams(0, -1, 1f));
        FrameLayout.LayoutParams bm = new FrameLayout.LayoutParams(-1, bottomH, Gravity.BOTTOM);
        bm.setMargins(m, 0, m, m);
        host.addView(metrics, bm);
    }

    private void buildPortrait(RoadMapActivity a, int w, int h) {
        int m = Math.max(dp(7), Math.round(w * .025f));
        int topH = Math.round(h * .092f);

        LinearLayout top = row(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Math.round(w * .018f), 0, Math.round(w * .02f), 0);
        top.setBackground(round(Color.argb(243, 3, 5, 8), 20, Color.rgb(67, 42, 47), 1));
        Button menu = plainButton(a, "☰");
        menu.setTextSize(25);
        menu.setOnClickListener(v -> a.startActivity(new Intent(a, DriveToolsActivity.class)));
        top.addView(menu, new LinearLayout.LayoutParams(Math.round(w * .12f), -1));
        TextView star = txt(a, "★", 28, Color.rgb(236, 191, 65), true);
        star.setGravity(Gravity.CENTER);
        top.addView(star, new LinearLayout.LayoutParams(Math.round(w * .09f), -1));
        LinearLayout brand = col(a);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.addView(txt(a, "LIVRE", 23, Color.WHITE, false));
        brand.addView(txt(a, "PROTEÇÃO", 9, Color.rgb(174, 160, 162), false));
        top.addView(brand, new LinearLayout.LayoutParams(0, -1, 1f));
        gps = pill(a, "●  GPS ATIVO", Color.rgb(17, 221, 113));
        top.addView(gps, new LinearLayout.LayoutParams(Math.round(w * .30f), Math.round(topH * .55f)));
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-1, topH, Gravity.TOP);
        tp.setMargins(m, m, m, 0);
        host.addView(top, tp);

        int limitSize = Math.round(w * .14f);
        limit = txt(a, "—", 24, Color.rgb(18, 18, 18), true);
        limit.setGravity(Gravity.CENTER);
        GradientDrawable sign = new GradientDrawable();
        sign.setShape(GradientDrawable.OVAL);
        sign.setColor(Color.WHITE);
        sign.setStroke(Math.max(dp(5), Math.round(limitSize * .09f)), Color.rgb(232, 27, 40));
        limit.setBackground(sign);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(limitSize, limitSize, Gravity.TOP | Gravity.LEFT);
        lp.setMargins(m + Math.round(w * .02f), Math.round(h * .205f), 0, 0);
        host.addView(limit, lp);

        int speedSize = Math.round(Math.min(w * .60f, h * .28f));
        speedometer = new ReferenceSpeedometerView(a);
        FrameLayout.LayoutParams sv = new FrameLayout.LayoutParams(speedSize, speedSize, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        sv.topMargin = Math.round(h * .40f);
        host.addView(speedometer, sv);

        int navH = Math.round(h * .065f);
        int playerH = Math.round(h * .078f);
        int driveH = Math.round(h * .095f);
        int bottomGap = m;

        LinearLayout drive = row(a);
        drive.setGravity(Gravity.CENTER);
        Button ptt = actionButton(a, "((•))\nPTT"), dash = actionButton(a, "▰\nDASH");
        Button mic = actionButton(a, "🎙");
        mic.setTextSize(28);
        mic.setBackground(round(Color.rgb(222, 13, 39), 22, 0, 0));
        ptt.setOnClickListener(v -> a.startActivity(new Intent(a, RoadRadioActivity.class)));
        mic.setOnClickListener(v -> a.requestCopilotNow());
        dash.setOnClickListener(v -> a.startActivity(new Intent(a, CameraActivity.class)));
        drive.addView(ptt, new LinearLayout.LayoutParams(0, -1, 1f));
        LinearLayout.LayoutParams micLp = new LinearLayout.LayoutParams(0, -1, 1.12f);
        micLp.setMargins(Math.round(w * .02f), 0, Math.round(w * .02f), 0);
        drive.addView(mic, micLp);
        drive.addView(dash, new LinearLayout.LayoutParams(0, -1, 1f));
        FrameLayout.LayoutParams dr = new FrameLayout.LayoutParams(-1, driveH, Gravity.BOTTOM);
        dr.setMargins(Math.round(w * .055f), 0, Math.round(w * .055f), navH + playerH + bottomGap * 3);
        host.addView(drive, dr);

        LinearLayout player = row(a);
        player.setGravity(Gravity.CENTER_VERTICAL);
        player.setPadding(Math.round(w * .025f), 0, Math.round(w * .018f), 0);
        player.setBackground(round(Color.argb(243, 4, 5, 8), 18, Color.rgb(167, 16, 36), 1));
        LinearLayout meta = col(a);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        playerTitle = txt(a, musicTitle, 15, Color.WHITE, false);
        playerArtist = txt(a, musicArtist, 10, Color.rgb(174, 160, 162), false);
        meta.addView(playerTitle);
        meta.addView(playerArtist);
        player.addView(meta, new LinearLayout.LayoutParams(0, -1, 1f));
        Button prev = mediaButton(a, "◀"), next = mediaButton(a, "▶|");
        play = mediaButton(a, playing ? "Ⅱ" : "▶");
        play.setBackground(round(Color.rgb(220, 13, 39), 18, 0, 0));
        int mediaW = Math.round(w * .13f);
        player.addView(prev, new LinearLayout.LayoutParams(mediaW, Math.round(playerH * .80f)));
        player.addView(play, new LinearLayout.LayoutParams(mediaW, Math.round(playerH * .90f)));
        player.addView(next, new LinearLayout.LayoutParams(mediaW, Math.round(playerH * .80f)));
        prev.setOnClickListener(v -> playerAction(a, PlayerService.ACTION_PREVIOUS));
        play.setOnClickListener(v -> playerAction(a, PlayerService.ACTION_TOGGLE));
        next.setOnClickListener(v -> playerAction(a, PlayerService.ACTION_NEXT));
        FrameLayout.LayoutParams pr = new FrameLayout.LayoutParams(-1, playerH, Gravity.BOTTOM);
        pr.setMargins(m, 0, m, navH + bottomGap * 2);
        host.addView(player, pr);

        LinearLayout nav = row(a);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(3), dp(3), dp(3), dp(3));
        nav.setBackground(round(Color.argb(247, 3, 5, 8), 18, Color.rgb(62, 43, 47), 1));
        Button mapB = navButton(a, "▣\nMapa", true), routes = navButton(a, "⌘\nRotas", false);
        Button music = navButton(a, "♪\nMúsica", false), more = navButton(a, "⠿\nMais", false);
        nav.addView(mapB, new LinearLayout.LayoutParams(0, -1, 1f));
        nav.addView(routes, new LinearLayout.LayoutParams(0, -1, 1f));
        nav.addView(music, new LinearLayout.LayoutParams(0, -1, 1f));
        nav.addView(more, new LinearLayout.LayoutParams(0, -1, 1f));
        mapB.setOnClickListener(v -> { RoadMapView rm = field(a, "roadMap", RoadMapView.class); if (rm != null) rm.recenter(); });
        routes.setOnClickListener(v -> a.startActivity(new Intent(a, DestinationActivity.class)));
        music.setOnClickListener(v -> a.startActivity(new Intent(a, MusicPlayerActivity.class)));
        more.setOnClickListener(v -> a.startActivity(new Intent(a, DriveToolsActivity.class)));
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-1, navH, Gravity.BOTTOM);
        np.setMargins(m, 0, m, bottomGap);
        host.addView(nav, np);

        int recSize = Math.round(w * .11f);
        Button recenter = roundButton(a, "◎");
        recenter.setTextSize(20);
        recenter.setOnClickListener(v -> { RoadMapView rm = field(a, "roadMap", RoadMapView.class); if (rm != null) rm.recenter(); });
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(recSize, recSize, Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        rp.setMargins(0, 0, m + Math.round(w * .01f), Math.round(h * .05f));
        host.addView(recenter, rp);
    }

    private void sync(RoadMapActivity a) {
        if (speedometer != null) speedometer.setSpeed(lastSpeed);
        if (limit != null) limit.setText(lastLimit > 0 ? String.valueOf(lastLimit) : "—");
        if (gps != null) {
            gps.setText(gpsOk ? "●  GPS ATIVO" : "●  GPS BUSCANDO");
            gps.setTextColor(gpsOk ? Color.rgb(17, 221, 113) : Color.rgb(226, 185, 76));
        }
        if (playerTitle != null) playerTitle.setText(musicTitle);
        if (playerArtist != null) playerArtist.setText(musicArtist);
        if (play != null) play.setText(playing ? "Ⅱ" : "▶");

        TextView instruction = field(a, "navInstructionText", TextView.class);
        TextView road = field(a, "navRoadText", TextView.class);
        TextView oldEta = field(a, "navEtaText", TextView.class);
        TextView oldRemaining = field(a, "navRemainingText", TextView.class);
        TextView oldDuration = field(a, "navDurationText", TextView.class);
        DestinationStore.Destination destination = DestinationStore.read(a);
        if (guideTitle != null) guideTitle.setText(destination == null ? "Siga a estrada" : firstLine(instruction, "Siga a rota"));
        if (guideSub != null) guideSub.setText(destination == null ? "Navegação ativa" : firstLine(road, destination.label));
        if (eta != null) eta.setText(firstLine(oldEta, "—:—"));
        if (remainingTime != null) remainingTime.setText(firstLine(oldDuration, "—"));
        if (remainingDistance != null) remainingDistance.setText(firstLine(oldRemaining, "—"));
        if (average != null) {
            try { average.setText(new DriveSessionStore(a).snapshot().averageLabel()); }
            catch (Throwable ignored) { average.setText("0 km/h"); }
        }
    }

    private void styleCancelRoute(RoadMapActivity a, FrameLayout root, boolean landscape, int w, int h) {
        Button cancel = findButton(root, "CANCELAR ROTA");
        boolean active = DestinationStore.read(a) != null;
        if (cancel == null) return;
        cancel.setVisibility(active ? View.VISIBLE : View.GONE);
        if (!active) return;
        cancel.setText("×  ROTA");
        cancel.setTextSize(9f);
        cancel.setTextColor(Color.WHITE);
        cancel.setBackground(round(Color.argb(235, 28, 6, 10), 100, Color.rgb(214, 26, 47), 1));
        FrameLayout.LayoutParams p = cancel.getLayoutParams() instanceof FrameLayout.LayoutParams
                ? (FrameLayout.LayoutParams) cancel.getLayoutParams()
                : new FrameLayout.LayoutParams(dp(88), dp(36));
        p.width = landscape ? Math.round(w * .07f) : Math.round(w * .18f);
        p.height = landscape ? Math.round(h * .065f) : Math.round(h * .038f);
        p.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        p.setMargins(0, landscape ? Math.round(h * .17f) : Math.round(h * .12f), 0, 0);
        cancel.setLayoutParams(p);
        if (Build.VERSION.SDK_INT >= 21) cancel.setElevation(dp(120));
    }

    private String firstLine(TextView t, String fallback) {
        if (t == null) return fallback;
        String s = String.valueOf(t.getText()).trim();
        if (s.isEmpty()) return fallback;
        int n = s.indexOf('\n');
        if (n >= 0) s = s.substring(0, n).trim();
        if (s.isEmpty()) return fallback;
        String upper = s.toUpperCase(Locale.ROOT);
        if (upper.equals("CHEGADA") || upper.equals("RESTANTE") || upper.equals("DURAÇÃO") || upper.equals("DISTÂNCIA")) return fallback;
        return s;
    }

    private void playerAction(Context c, String action) {
        try { c.startService(new Intent(c, PlayerService.class).setAction(action)); } catch (Throwable ignored) {}
    }

    private LinearLayout metricWrap(Context c, String icon, TextView value) {
        LinearLayout r = row(c);
        r.setGravity(Gravity.CENTER);
        TextView i = txt(c, icon, 20, Color.WHITE, false);
        i.setGravity(Gravity.CENTER);
        r.addView(i, new LinearLayout.LayoutParams(dp(38), -1));
        LinearLayout box = col(c);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(txt(c, String.valueOf(value.getTag()), 9, Color.rgb(174, 160, 162), false));
        box.addView(value);
        r.addView(box, new LinearLayout.LayoutParams(0, -1, 1f));
        return r;
    }

    private TextView metric(Context c, String value, String label) {
        TextView v = txt(c, value, 17, Color.WHITE, false);
        v.setTag(label);
        v.setSingleLine(true);
        return v;
    }

    private Button mediaButton(Context c, String s) {
        Button b = plainButton(c, s);
        b.setTextSize(18);
        b.setBackground(round(Color.argb(220, 7, 8, 11), 100, Color.rgb(112, 27, 39), 1));
        return b;
    }

    private Button actionButton(Context c, String s) {
        Button b = plainButton(c, s);
        b.setTextSize(14);
        b.setBackground(round(Color.argb(239, 7, 9, 13), 20, Color.rgb(89, 40, 51), 1));
        return b;
    }

    private Button navButton(Context c, String s, boolean active) {
        Button b = plainButton(c, s);
        b.setTextSize(10.5f);
        b.setTextColor(active ? Color.rgb(238, 31, 54) : Color.rgb(220, 216, 216));
        if (active) b.setBackground(round(Color.argb(80, 160, 13, 31), 14, 0, 0));
        return b;
    }

    private Button roundButton(Context c, String s) {
        Button b = plainButton(c, s);
        b.setText(s);
        b.setTextSize(10.5f);
        b.setBackground(round(Color.argb(239, 4, 6, 9), 100, Color.rgb(188, 18, 40), 2));
        return b;
    }

    private Button plainButton(Context c, String s) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setPadding(dp(3), 0, dp(3), 0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setBackgroundColor(Color.TRANSPARENT);
        return b;
    }

    private TextView pill(Context c, String s, int color) {
        TextView t = txt(c, s, 10, color, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(Color.argb(116, 0, 76, 47), 100, Color.argb(145, 20, 190, 105), 1));
        return t;
    }

    private LinearLayout row(Context c) { LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private LinearLayout col(Context c) { LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL); return l; }

    private TextView txt(Context c, String s, float size, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private GradientDrawable round(int color, int radius, int stroke, int strokeW) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        if (stroke != 0 && strokeW > 0) g.setStroke(dp(strokeW), stroke);
        return g;
    }

    private void hideParent(View v, int levels) {
        View x = v;
        for (int i = 0; i < levels && x != null; i++) x = x.getParent() instanceof View ? (View) x.getParent() : null;
        if (x != null) x.setVisibility(View.GONE);
    }

    private Button findButton(View v, String text) {
        if (v instanceof Button && String.valueOf(((Button) v).getText()).toUpperCase(Locale.ROOT).contains(text)) return (Button) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                Button b = findButton(g.getChildAt(i), text);
                if (b != null) return b;
            }
        }
        return null;
    }

    private String textOf(View v) {
        StringBuilder b = new StringBuilder();
        collect(v, b);
        return b.toString();
    }

    private void collect(View v, StringBuilder b) {
        if (v instanceof TextView) b.append(' ').append(((TextView) v).getText());
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), b);
        }
    }

    private int dp(float v) { return Math.round(v * app.getResources().getDisplayMetrics().density); }

    @SuppressWarnings("unchecked")
    private <T> T field(Object o, String name, Class<T> type) {
        try {
            Field f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            Object v = f.get(o);
            return type.isInstance(v) ? (T) v : null;
        } catch (Throwable ignored) { return null; }
    }

    @Override public void onActivityResumed(Activity activity) {
        if (activity instanceof RoadMapActivity) {
            RoadMapActivity a = (RoadMapActivity) activity;
            resumed = new WeakReference<>(a);
            main.postDelayed(() -> apply(a), 120L);
        }
    }
    @Override public void onActivityPaused(Activity activity) { if (resumed.get() == activity) resumed = new WeakReference<>(null); }
    @Override public void onActivityCreated(Activity a, Bundle b) {}
    @Override public void onActivityStarted(Activity a) {}
    @Override public void onActivityStopped(Activity a) {}
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
    @Override public void onActivityDestroyed(Activity a) {}
}

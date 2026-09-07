package com.estradaplay.comunista;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Production cockpit owned directly by RoadMapActivity.
 * No reflection, no second UI tree and no legacy controls underneath it.
 */
final class RoadCockpitUiV400 {
    private static final int RED = Color.rgb(226, 12, 39);
    private static final int RED_DARK = Color.rgb(93, 7, 22);
    private static final int WHITE = Color.rgb(248, 246, 245);
    private static final int MUTED = Color.rgb(176, 166, 168);
    private static final int GREEN = Color.rgb(22, 221, 112);

    static final class Bindings {
        final RoadMapView map;
        final TextView speedText, hazardTitle, hazardDetail, protectionText, clockText, gpsText,
                destinationText, navInstructionText, navLimitText, navWeatherText, navDistanceText,
                navRoadText, navEtaText, navRemainingText, navDurationText, navTurnText,
                mapPlayerTitle, mapPlayerArtist;
        final Button mapPlayerToggle;
        final LinearLayout hazardCard;

        Bindings(RoadMapView map, TextView speedText, TextView hazardTitle, TextView hazardDetail,
                 TextView protectionText, TextView clockText, TextView gpsText, TextView destinationText,
                 TextView navInstructionText, TextView navLimitText, TextView navWeatherText,
                 TextView navDistanceText, TextView navRoadText, TextView navEtaText,
                 TextView navRemainingText, TextView navDurationText, TextView navTurnText,
                 TextView mapPlayerTitle, TextView mapPlayerArtist, Button mapPlayerToggle,
                 LinearLayout hazardCard) {
            this.map = map;
            this.speedText = speedText;
            this.hazardTitle = hazardTitle;
            this.hazardDetail = hazardDetail;
            this.protectionText = protectionText;
            this.clockText = clockText;
            this.gpsText = gpsText;
            this.destinationText = destinationText;
            this.navInstructionText = navInstructionText;
            this.navLimitText = navLimitText;
            this.navWeatherText = navWeatherText;
            this.navDistanceText = navDistanceText;
            this.navRoadText = navRoadText;
            this.navEtaText = navEtaText;
            this.navRemainingText = navRemainingText;
            this.navDurationText = navDurationText;
            this.navTurnText = navTurnText;
            this.mapPlayerTitle = mapPlayerTitle;
            this.mapPlayerArtist = mapPlayerArtist;
            this.mapPlayerToggle = mapPlayerToggle;
            this.hazardCard = hazardCard;
        }
    }

    private RoadCockpitUiV400() {}

    static Bindings build(RoadMapActivity a, FrameLayout root, int width, int height, boolean portrait) {
        RoadMapView map = new RoadMapView(a);
        FrameLayout.LayoutParams mapLp = new FrameLayout.LayoutParams(-1, -1);
        int edge = dp(a, 2);
        mapLp.setMargins(edge, edge, edge, edge);
        root.addView(map, mapLp);

        ReferenceSpeedometerView speedometer = new ReferenceSpeedometerView(a);
        SpeedBridgeTextView speedBridge = new SpeedBridgeTextView(a, speedometer);
        GpsBridgeTextView gpsBridge = new GpsBridgeTextView(a, speedometer);

        TextView protection = text(a, "PROTEÇÃO ATIVA", 9, MUTED, false);
        TextView instruction = text(a, "Siga a estrada", portrait ? 15 : 20, WHITE, false);
        TextView weather = text(a, "CLIMA · atualizando…", portrait ? 9 : 10, MUTED, false);
        TextView limit = limit(a, speedometer);
        TextView routeDistance = text(a, "", 10, MUTED, false);
        TextView routeRoad = text(a, "Navegação ativa", 10, MUTED, false);
        TextView eta = metricValue(a, "—:—");
        TextView remaining = metricValue(a, "—");
        TextView duration = metricValue(a, "—");
        TextView turn = ghost(a);
        TextView destination = ghost(a);
        TextView clock = ghost(a);
        TextView hazardTitle = ghost(a);
        TextView hazardDetail = ghost(a);
        LinearLayout hazardCard = new LinearLayout(a);
        hazardCard.setVisibility(View.GONE);
        root.addView(hazardCard, new FrameLayout.LayoutParams(1, 1));

        TextView playerTitle = text(a, "Biblioteca offline", portrait ? 14 : 17, WHITE, false);
        TextView playerArtist = text(a, "Música local", portrait ? 10 : 10, MUTED, false);
        Button playerToggle = mediaButton(a, "▶");

        if (portrait) {
            buildPortrait(a, root, width, height, map, speedometer, gpsBridge, protection, instruction, weather,
                    limit, eta, remaining, duration, playerTitle, playerArtist, playerToggle);
        } else {
            buildLandscape(a, root, width, height, speedometer, gpsBridge, protection, instruction, weather,
                    limit, eta, remaining, duration, playerTitle, playerArtist, playerToggle);
        }

        speedBridge.setVisibility(View.GONE);
        root.addView(speedBridge, new FrameLayout.LayoutParams(1, 1));
        root.addView(routeDistance, new FrameLayout.LayoutParams(1, 1)); routeDistance.setVisibility(View.GONE);
        root.addView(routeRoad, new FrameLayout.LayoutParams(1, 1)); routeRoad.setVisibility(View.GONE);
        root.addView(turn, new FrameLayout.LayoutParams(1, 1));
        root.addView(destination, new FrameLayout.LayoutParams(1, 1));
        root.addView(clock, new FrameLayout.LayoutParams(1, 1));
        root.addView(hazardTitle, new FrameLayout.LayoutParams(1, 1));
        root.addView(hazardDetail, new FrameLayout.LayoutParams(1, 1));

        return new Bindings(map, speedBridge, hazardTitle, hazardDetail, protection, clock, gpsBridge,
                destination, instruction, limit, weather, routeDistance, routeRoad, eta, remaining,
                duration, turn, playerTitle, playerArtist, playerToggle, hazardCard);
    }

    private static void buildLandscape(RoadMapActivity a, FrameLayout root, int w, int h,
                                       ReferenceSpeedometerView speedometer, TextView gps, TextView protection,
                                       TextView instruction, TextView weather, TextView limit, TextView eta,
                                       TextView remaining, TextView duration, TextView playerTitle,
                                       TextView playerArtist, Button playerToggle) {
        int margin = Math.max(dp(a, 7), Math.round(h * .018f));
        int gap = Math.max(dp(a, 6), Math.round(h * .014f));
        int sideW = Math.round(w * .275f);
        int bottomH = Math.max(dp(a, 72), Math.round(h * .135f));
        int topH = Math.max(dp(a, 78), Math.round(h * .145f));
        int availableRight = h - bottomH - margin * 3 - gap;
        int speedH = Math.round(availableRight * .54f);
        int playerH = Math.max(dp(a, 138), availableRight - speedH);

        LinearLayout top = row(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Math.round(w * .015f), 0, Math.round(w * .014f), 0);
        top.setBackground(panel(a, Color.argb(241, 4, 5, 8), 20, RED_DARK, 1));
        TextView star = text(a, "★", 29, Color.rgb(238, 194, 67), true);
        star.setGravity(Gravity.CENTER);
        top.addView(star, new LinearLayout.LayoutParams(Math.round(w * .045f), -1));
        LinearLayout brand = col(a);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.addView(text(a, "LIVRE", 23, WHITE, false));
        brand.addView(protection);
        top.addView(brand, new LinearLayout.LayoutParams(Math.round(w * .16f), -1));
        View divider = new View(a);
        divider.setBackgroundColor(RED);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(dp(a, 2), Math.round(topH * .62f));
        divLp.setMargins(dp(a, 8), 0, dp(a, 18), 0);
        top.addView(divider, divLp);
        LinearLayout guide = col(a);
        guide.setGravity(Gravity.CENTER_VERTICAL);
        guide.addView(instruction);
        guide.addView(weather);
        top.addView(guide, new LinearLayout.LayoutParams(0, -1, 1f));
        styleGps(gps, a);
        top.addView(gps, new LinearLayout.LayoutParams(Math.round(w * .14f), Math.round(topH * .54f)));
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(w - sideW - margin * 3, topH, Gravity.TOP | Gravity.LEFT);
        topLp.setMargins(margin, margin, 0, 0);
        root.addView(top, topLp);

        LinearLayout speedCard = col(a);
        speedCard.setGravity(Gravity.CENTER);
        speedCard.setBackground(panel(a, Color.argb(244, 3, 4, 7), 22, RED_DARK, 1));
        speedCard.addView(speedometer, new LinearLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(sideW, speedH, Gravity.TOP | Gravity.RIGHT);
        sp.setMargins(0, margin, margin, 0);
        root.addView(speedCard, sp);

        LinearLayout player = col(a);
        player.setPadding(Math.round(sideW * .065f), Math.round(playerH * .065f), Math.round(sideW * .065f), Math.round(playerH * .055f));
        player.setBackground(panel(a, Color.argb(244, 3, 4, 7), 22, RED_DARK, 1));
        player.addView(text(a, "MÚSICA OFFLINE", 9, Color.rgb(232, 39, 61), true));
        player.addView(playerTitle);
        player.addView(playerArtist);
        View wave = new View(a);
        wave.setBackgroundColor(Color.argb(170, 190, 12, 38));
        LinearLayout.LayoutParams waveLp = new LinearLayout.LayoutParams(-1, dp(a, 2));
        waveLp.setMargins(0, Math.round(playerH * .08f), 0, Math.round(playerH * .07f));
        player.addView(wave, waveLp);
        LinearLayout controls = row(a);
        controls.setGravity(Gravity.CENTER);
        Button prev = mediaButton(a, "|◀"), next = mediaButton(a, "▶|");
        playerToggle.setTextSize(24);
        playerToggle.setBackground(panel(a, RED, 100, 0, 0));
        int controlH = Math.max(dp(a, 50), Math.round(playerH * .34f));
        controls.addView(prev, new LinearLayout.LayoutParams(0, controlH, 1f));
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(0, controlH, 1.14f);
        playLp.setMargins(dp(a, 9), 0, dp(a, 9), 0);
        controls.addView(playerToggle, playLp);
        controls.addView(next, new LinearLayout.LayoutParams(0, controlH, 1f));
        player.addView(controls, new LinearLayout.LayoutParams(-1, 0, 1f));
        bindPlayer(a, prev, playerToggle, next);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(sideW, playerH, Gravity.TOP | Gravity.RIGHT);
        pp.setMargins(0, margin + speedH + gap, margin, 0);
        root.addView(player, pp);

        limit.setVisibility(View.VISIBLE);
        FrameLayout.LayoutParams lpLimit = new FrameLayout.LayoutParams(dp(a, 70), dp(a, 70), Gravity.TOP | Gravity.LEFT);
        lpLimit.setMargins(margin + dp(a, 22), topH + margin + dp(a, 24), 0, 0);
        root.addView(limit, lpLimit);

        Button central = roundButton(a, "◎\nCENTRAL");
        central.setOnClickListener(v -> a.startActivity(new Intent(a, DriveToolsActivity.class)));
        int cs = Math.max(dp(a, 86), Math.round(Math.min(h * .18f, w * .075f)));
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(cs, cs, Gravity.BOTTOM | Gravity.LEFT);
        cp.setMargins(margin + dp(a, 18), 0, 0, bottomH + margin + gap);
        root.addView(central, cp);

        LinearLayout metrics = row(a);
        metrics.setGravity(Gravity.CENTER_VERTICAL);
        metrics.setPadding(Math.round(w * .012f), 0, Math.round(w * .012f), 0);
        metrics.setBackground(panel(a, Color.argb(244, 3, 4, 7), 18, RED_DARK, 1));
        metrics.addView(metricWrap(a, "⚑", "CHEGADA", eta), new LinearLayout.LayoutParams(0, -1, 1f));
        metrics.addView(metricWrap(a, "◷", "RESTANTE", duration), new LinearLayout.LayoutParams(0, -1, 1f));
        metrics.addView(metricWrap(a, "╱╲", "DISTÂNCIA", remaining), new LinearLayout.LayoutParams(0, -1, 1f));
        TextView avg = new TripAverageTextView(a); avg.setTextSize(15); avg.setTextColor(WHITE); avg.setGravity(Gravity.CENTER_VERTICAL);
        metrics.addView(metricWrap(a, "◴", "MÉDIA", avg), new LinearLayout.LayoutParams(0, -1, 1f));
        FrameLayout.LayoutParams bm = new FrameLayout.LayoutParams(-1, bottomH, Gravity.BOTTOM);
        bm.setMargins(margin, 0, margin, margin);
        root.addView(metrics, bm);
    }

    private static void buildPortrait(RoadMapActivity a, FrameLayout root, int w, int h, RoadMapView map,
                                      ReferenceSpeedometerView speedometer, TextView gps, TextView protection,
                                      TextView instruction, TextView weather, TextView limit, TextView eta,
                                      TextView remaining, TextView duration, TextView playerTitle,
                                      TextView playerArtist, Button playerToggle) {
        int margin = Math.max(dp(a, 8), Math.round(w * .025f));
        int topH = Math.max(dp(a, 78), Math.round(h * .092f));
        int navH = Math.max(dp(a, 72), Math.round(h * .09f));
        int playerH = Math.max(dp(a, 102), Math.round(h * .105f));
        int actionsH = Math.max(dp(a, 96), Math.round(h * .12f));
        int speedSize = Math.min(Math.round(w * .72f), Math.round(h * .34f));

        LinearLayout top = row(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(a, 10), 0, dp(a, 12), 0);
        top.setBackground(panel(a, Color.argb(242, 3, 5, 8), 20, Color.rgb(62, 40, 45), 1));
        Button menu = plainButton(a, "☰");
        menu.setTextSize(25);
        menu.setOnClickListener(v -> a.startActivity(new Intent(a, DriveToolsActivity.class)));
        top.addView(menu, new LinearLayout.LayoutParams(Math.round(w * .13f), -1));
        TextView star = text(a, "★", 28, Color.rgb(237, 190, 57), true);
        star.setGravity(Gravity.CENTER);
        top.addView(star, new LinearLayout.LayoutParams(Math.round(w * .09f), -1));
        LinearLayout brand = col(a);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.addView(text(a, "LIVRE", 23, WHITE, false));
        brand.addView(protection);
        top.addView(brand, new LinearLayout.LayoutParams(0, -1, 1f));
        styleGps(gps, a);
        top.addView(gps, new LinearLayout.LayoutParams(Math.round(w * .34f), Math.round(topH * .56f)));
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(-1, topH, Gravity.TOP);
        topLp.setMargins(margin, margin, margin, 0);
        root.addView(top, topLp);

        LinearLayout guide = col(a);
        guide.setPadding(dp(a, 13), dp(a, 7), dp(a, 13), dp(a, 7));
        guide.setBackground(panel(a, Color.argb(216, 4, 5, 8), 14, Color.rgb(78, 44, 49), 1));
        guide.addView(instruction);
        guide.addView(weather);
        FrameLayout.LayoutParams guideLp = new FrameLayout.LayoutParams(Math.round(w * .62f), -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        guideLp.topMargin = margin + topH + dp(a, 8);
        root.addView(guide, guideLp);

        FrameLayout.LayoutParams limitLp = new FrameLayout.LayoutParams(dp(a, 72), dp(a, 72), Gravity.TOP | Gravity.LEFT);
        limitLp.setMargins(margin + dp(a, 8), margin + topH + dp(a, 82), 0, 0);
        root.addView(limit, limitLp);

        FrameLayout speedCard = new FrameLayout(a);
        speedCard.setBackground(panel(a, Color.argb(222, 2, 3, 6), 200, Color.argb(130, 150, 20, 36), 1));
        speedCard.addView(speedometer, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(speedSize, speedSize, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        sp.bottomMargin = navH + playerH + actionsH + margin + dp(a, 18);
        root.addView(speedCard, sp);

        LinearLayout actions = row(a);
        actions.setGravity(Gravity.CENTER);
        Button ptt = bigAction(a, "◉\nPTT", false);
        Button copilot = bigAction(a, "🎙", true);
        Button dash = bigAction(a, "▰\nDASH", false);
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(0, -1, 1f);
        actions.addView(ptt, actionLp);
        LinearLayout.LayoutParams midLp = new LinearLayout.LayoutParams(0, -1, 1.08f);
        midLp.setMargins(dp(a, 10), 0, dp(a, 10), 0);
        actions.addView(copilot, midLp);
        actions.addView(dash, actionLp);
        ptt.setOnClickListener(v -> a.startActivity(new Intent(a, RoadRadioActivity.class)));
        dash.setOnClickListener(v -> a.startActivity(new Intent(a, CameraActivity.class)));
        copilot.setOnClickListener(v -> {
            if (a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                CopilotService.requestListenNow(a);
            } else if (Build.VERSION.SDK_INT >= 23) {
                a.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 2202);
            }
        });
        FrameLayout.LayoutParams actionsLp = new FrameLayout.LayoutParams(-1, actionsH, Gravity.BOTTOM);
        actionsLp.setMargins(margin, 0, margin, navH + playerH + margin + dp(a, 8));
        root.addView(actions, actionsLp);

        LinearLayout player = row(a);
        player.setGravity(Gravity.CENTER_VERTICAL);
        player.setPadding(dp(a, 14), dp(a, 8), dp(a, 10), dp(a, 8));
        player.setBackground(panel(a, Color.argb(244, 3, 4, 7), 18, RED_DARK, 1));
        LinearLayout info = col(a);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.addView(playerTitle);
        info.addView(playerArtist);
        player.addView(info, new LinearLayout.LayoutParams(0, -1, 1f));
        Button prev = mediaButton(a, "◀"), next = mediaButton(a, "▶|");
        playerToggle.setTextSize(23);
        playerToggle.setBackground(panel(a, RED, 15, 0, 0));
        int buttonW = Math.round(w * .14f);
        player.addView(prev, new LinearLayout.LayoutParams(buttonW, -1));
        player.addView(playerToggle, new LinearLayout.LayoutParams(buttonW, -1));
        player.addView(next, new LinearLayout.LayoutParams(buttonW, -1));
        bindPlayer(a, prev, playerToggle, next);
        FrameLayout.LayoutParams playerLp = new FrameLayout.LayoutParams(-1, playerH, Gravity.BOTTOM);
        playerLp.setMargins(margin, 0, margin, navH + margin);
        root.addView(player, playerLp);

        LinearLayout nav = row(a);
        nav.setGravity(Gravity.CENTER);
        nav.setBackground(panel(a, Color.argb(246, 3, 4, 7), 18, Color.rgb(53, 42, 46), 1));
        Button mapB = navButton(a, "▣\nMapa", true), routeB = navButton(a, "⌘\nRotas", false),
                musicB = navButton(a, "♪\nMúsica", false), moreB = navButton(a, "⠿\nMais", false);
        nav.addView(mapB, new LinearLayout.LayoutParams(0, -1, 1f));
        nav.addView(routeB, new LinearLayout.LayoutParams(0, -1, 1f));
        nav.addView(musicB, new LinearLayout.LayoutParams(0, -1, 1f));
        nav.addView(moreB, new LinearLayout.LayoutParams(0, -1, 1f));
        mapB.setOnClickListener(v -> { if (map != null) map.recenter(); });
        routeB.setOnClickListener(v -> a.startActivity(new Intent(a, DestinationActivity.class)));
        musicB.setOnClickListener(v -> a.startActivity(new Intent(a, MusicPlayerActivity.class)));
        moreB.setOnClickListener(v -> a.startActivity(new Intent(a, DriveToolsActivity.class)));
        FrameLayout.LayoutParams navLp = new FrameLayout.LayoutParams(-1, navH, Gravity.BOTTOM);
        navLp.setMargins(margin, 0, margin, margin);
        root.addView(nav, navLp);

        // Route metrics stay bound but unobtrusive on portrait; they appear as a small chip when populated.
        LinearLayout trip = row(a);
        trip.setGravity(Gravity.CENTER);
        trip.setPadding(dp(a, 10), dp(a, 4), dp(a, 10), dp(a, 4));
        trip.setBackground(panel(a, Color.argb(205, 3, 4, 7), 14, Color.argb(100, 160, 20, 36), 1));
        trip.addView(metricWrap(a, "", "CHEGADA", eta), new LinearLayout.LayoutParams(0, -1, 1f));
        trip.addView(metricWrap(a, "", "RESTANTE", duration), new LinearLayout.LayoutParams(0, -1, 1f));
        trip.addView(metricWrap(a, "", "DISTÂNCIA", remaining), new LinearLayout.LayoutParams(0, -1, 1f));
        FrameLayout.LayoutParams tripLp = new FrameLayout.LayoutParams(Math.round(w * .74f), dp(a, 54), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tripLp.topMargin = margin + topH + dp(a, 65);
        root.addView(trip, tripLp);
    }

    private static void bindPlayer(Activity a, Button prev, Button toggle, Button next) {
        prev.setOnClickListener(v -> player(a, PlayerService.ACTION_PREVIOUS));
        toggle.setOnClickListener(v -> player(a, PlayerService.ACTION_TOGGLE));
        next.setOnClickListener(v -> player(a, PlayerService.ACTION_NEXT));
    }

    private static void player(Activity a, String action) {
        try {
            Intent i = new Intent(a, PlayerService.class).setAction(action);
            if (Build.VERSION.SDK_INT >= 26) a.startForegroundService(i); else a.startService(i);
        } catch (Throwable ignored) {}
    }

    private static TextView metricValue(Activity a, String value) {
        TextView t = new MetricBridgeTextView(a);
        t.setTextSize(15); t.setTextColor(WHITE); t.setGravity(Gravity.CENTER_VERTICAL); t.setSingleLine(true);
        t.setText(value);
        return t;
    }

    private static LinearLayout metricWrap(Activity a, String icon, String label, TextView value) {
        LinearLayout box = row(a);
        box.setGravity(Gravity.CENTER);
        if (!icon.isEmpty()) {
            TextView i = text(a, icon, 20, WHITE, false);
            i.setGravity(Gravity.CENTER);
            box.addView(i, new LinearLayout.LayoutParams(dp(a, 42), -1));
        }
        LinearLayout c = col(a);
        c.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = text(a, label, 9, MUTED, false);
        l.setSingleLine(true);
        c.addView(l);
        c.addView(value);
        box.addView(c, new LinearLayout.LayoutParams(0, -1, 1f));
        return box;
    }

    private static TextView limit(Activity a, ReferenceSpeedometerView gauge) {
        LimitBridgeTextView t = new LimitBridgeTextView(a, gauge);
        t.setText("—"); t.setTextSize(23); t.setTextColor(Color.rgb(34,34,34));
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); t.setGravity(Gravity.CENTER);
        t.setBackground(panel(a, Color.WHITE, 100, RED, 5));
        return t;
    }

    private static void styleGps(TextView t, Activity a) {
        t.setText("●  GPS ATIVO");
        t.setTextColor(GREEN);
        t.setTextSize(10);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setBackground(panel(a, Color.argb(218, 3, 38, 28), 100, Color.rgb(4, 105, 66), 1));
    }

    private static Button bigAction(Activity a, String label, boolean primary) {
        Button b = plainButton(a, label);
        b.setTextSize(primary ? 24 : 14);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackground(panel(a, primary ? RED : Color.argb(238, 8, 10, 14), 20,
                primary ? RED : Color.rgb(87, 48, 55), 1));
        return b;
    }

    private static Button navButton(Activity a, String label, boolean active) {
        Button b = plainButton(a, label);
        b.setTextSize(11);
        b.setTextColor(active ? Color.rgb(238, 37, 63) : Color.rgb(214, 207, 209));
        b.setBackground(active ? panel(a, Color.rgb(62, 10, 20), 16, 0, 0) : null);
        return b;
    }

    private static Button roundButton(Activity a, String label) {
        Button b = plainButton(a, label);
        b.setTextSize(11);
        b.setBackground(panel(a, Color.argb(232, 2, 4, 7), 100, RED, 2));
        return b;
    }

    private static Button mediaButton(Activity a, String label) {
        Button b = plainButton(a, label);
        b.setTextSize(18);
        b.setBackground(panel(a, Color.argb(232, 5, 6, 9), 100, Color.rgb(118, 22, 39), 1));
        return b;
    }

    private static Button plainButton(Activity a, String label) {
        Button b = new Button(a);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(WHITE);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, 0, 0, 0);
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setStateListAnimator(null);
        b.setBackgroundColor(Color.TRANSPARENT);
        return b;
    }

    private static TextView text(Activity a, String value, float size, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setMaxLines(2);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private static TextView ghost(Activity a) {
        TextView t = new TextView(a);
        t.setVisibility(View.GONE);
        return t;
    }

    private static LinearLayout row(Activity a) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private static LinearLayout col(Activity a) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private static GradientDrawable panel(Activity a, int color, int radiusDp, int stroke, int strokeWidthDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(a, radiusDp));
        if (stroke != 0 && strokeWidthDp > 0) g.setStroke(dp(a, strokeWidthDp), stroke);
        return g;
    }

    private static int dp(Activity a, float v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    private static final class TripAverageTextView extends TextView {
        private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        private final Runnable tick = new Runnable(){@Override public void run(){
            try{setText(new DriveSessionStore(getContext()).snapshot().averageLabel());}catch(Throwable ignored){setText("0 km/h");}
            handler.postDelayed(this,5000L);
        }};
        TripAverageTextView(Activity a){super(a);}
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();handler.removeCallbacks(tick);handler.post(tick);}
        @Override protected void onDetachedFromWindow(){handler.removeCallbacks(tick);super.onDetachedFromWindow();}
    }

    private static final class SpeedBridgeTextView extends TextView {
        private final ReferenceSpeedometerView gauge;
        SpeedBridgeTextView(Activity a, ReferenceSpeedometerView gauge) { super(a); this.gauge = gauge; }
        @Override public void setText(CharSequence text, BufferType type) {
            super.setText(text, type);
            if (gauge == null || text == null) return;
            try { gauge.setSpeed(Double.parseDouble(text.toString().trim().replace(',', '.'))); }
            catch (Throwable ignored) {}
        }
    }

    private static final class LimitBridgeTextView extends TextView {
        private final ReferenceSpeedometerView gauge;
        LimitBridgeTextView(Activity a, ReferenceSpeedometerView gauge){super(a);this.gauge=gauge;}
        @Override public void setText(CharSequence value, BufferType type){
            int limit=0;try{limit=Integer.parseInt(value==null?"":value.toString().trim());}catch(Throwable ignored){}
            if(gauge!=null)gauge.setLimit(limit);
            super.setText(limit>0?String.valueOf(limit):"—",type);
        }
    }

    private static final class MetricBridgeTextView extends TextView {
        MetricBridgeTextView(Activity a){super(a);}
        @Override public void setText(CharSequence value, BufferType type){
            String s=value==null?"":value.toString();
            int cut=s.indexOf('\n'); if(cut>=0)s=s.substring(0,cut).trim();
            super.setText(s,type);
        }
    }

    private static final class GpsBridgeTextView extends TextView {
        private final ReferenceSpeedometerView gauge;
        GpsBridgeTextView(Activity a, ReferenceSpeedometerView gauge) { super(a); this.gauge = gauge; }
        @Override public void setText(CharSequence text, BufferType type) {
            boolean ok = text != null && text.toString().toUpperCase().contains("ATIVO");
            if (gauge != null) gauge.setGpsAvailable(ok);
            super.setText(ok ? "●  GPS ATIVO" : "●  GPS BUSCANDO", type);
            setTextColor(ok ? GREEN : Color.rgb(242, 181, 65));
        }
    }
}

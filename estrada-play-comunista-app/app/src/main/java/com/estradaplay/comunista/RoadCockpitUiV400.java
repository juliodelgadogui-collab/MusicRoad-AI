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
 * Map-first automotive cockpit.
 *
 * UI_RESET_V410:
 * - one responsive Universal layout instead of separate visual editions;
 * - map remains the dominant surface in portrait and landscape;
 * - no fixed bottom navigation bar;
 * - no large landscape side dock;
 * - compact speed, route, alerts, shortcuts and media are floating layers;
 * - all existing RoadMapActivity bindings remain native and functional.
 */
final class RoadCockpitUiV400 {
    private static final int RED = Color.rgb(226, 12, 39);
    private static final int RED_DARK = Color.rgb(94, 9, 25);
    private static final int RED_SOFT = Color.rgb(54, 9, 18);
    private static final int WHITE = Color.rgb(248, 246, 245);
    private static final int MUTED = Color.rgb(181, 171, 173);
    private static final int GREEN = Color.rgb(26, 222, 116);
    private static final int GOLD = Color.rgb(232, 191, 71);
    private static final int GLASS = Color.argb(228, 7, 7, 10);
    private static final int GLASS_STRONG = Color.argb(242, 6, 6, 9);
    private static final int BORDER = Color.rgb(82, 42, 49);

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
        root.addView(map, new FrameLayout.LayoutParams(-1, -1));

        ReferenceSpeedometerView gauge = new ReferenceSpeedometerView(a);
        SpeedBridgeTextView speedBridge = new SpeedBridgeTextView(a, gauge);
        GpsBridgeTextView gps = new GpsBridgeTextView(a, gauge);
        styleGps(gps, a);

        TextView protection = text(a, "PROTEÇÃO ATIVA", 9, GREEN, true);
        TextView clock = text(a, "--:--", 12, WHITE, true);
        TextView instruction = text(a, "Siga a estrada", portrait ? 17 : 18, WHITE, true);
        TextView weather = text(a, "CLIMA · atualizando…", 9, MUTED, false);
        TextView routeRoad = text(a, "Navegação pronta", 9, MUTED, false);
        TextView routeDistance = text(a, "", 10, WHITE, true);
        TextView turn = text(a, "↑", portrait ? 20 : 22, WHITE, true);
        turn.setGravity(Gravity.CENTER);
        TextView limit = limit(a, gauge);

        TextView eta = metricValue(a, "—:—");
        TextView remaining = metricValue(a, "—");
        TextView duration = metricValue(a, "—");

        TextView hazardTitle = text(a, "Estrada livre à frente", 12, WHITE, true);
        TextView hazardDetail = text(a, "Monitorando seu sentido e os alertas", 9, MUTED, false);
        LinearLayout hazardCard = col(a);
        hazardCard.setGravity(Gravity.CENTER_VERTICAL);
        hazardCard.setPadding(dp(a, 13), dp(a, 8), dp(a, 13), dp(a, 8));
        hazardCard.setBackground(panel(a, GLASS, 16, BORDER, 1));
        hazardCard.addView(hazardTitle);
        hazardCard.addView(hazardDetail);

        TextView destination = ghost(a);
        TextView playerTitle = text(a, "Biblioteca offline", portrait ? 13 : 14, WHITE, true);
        TextView playerArtist = text(a, "Música local", 9, MUTED, false);
        Button playerToggle = mediaButton(a, "▶", true);

        if (portrait) {
            buildPortrait(a, root, map, width, height, gauge, gps, protection, clock, instruction,
                    weather, routeRoad, routeDistance, turn, limit, eta, remaining, duration,
                    hazardCard, playerTitle, playerArtist, playerToggle);
        } else {
            buildLandscape(a, root, map, width, height, gauge, gps, protection, clock, instruction,
                    weather, routeRoad, routeDistance, turn, limit, eta, remaining, duration,
                    hazardCard, playerTitle, playerArtist, playerToggle);
        }

        speedBridge.setVisibility(View.GONE);
        destination.setVisibility(View.GONE);
        root.addView(speedBridge, new FrameLayout.LayoutParams(1, 1));
        root.addView(destination, new FrameLayout.LayoutParams(1, 1));

        return new Bindings(map, speedBridge, hazardTitle, hazardDetail, protection, clock, gps,
                destination, instruction, limit, weather, routeDistance, routeRoad, eta, remaining,
                duration, turn, playerTitle, playerArtist, playerToggle, hazardCard);
    }

    private static void buildPortrait(RoadMapActivity a, FrameLayout root, RoadMapView map, int w, int h,
                                      ReferenceSpeedometerView gauge, TextView gps, TextView protection,
                                      TextView clock, TextView instruction, TextView weather, TextView routeRoad,
                                      TextView routeDistance, TextView turn, TextView limit, TextView eta,
                                      TextView remaining, TextView duration, LinearLayout hazardCard,
                                      TextView playerTitle, TextView playerArtist, Button playerToggle) {
        int margin = Math.max(dp(a, 8), Math.round(w * .022f));
        int gap = dp(a, 8);
        int headerH = dp(a, 62);
        int routeH = dp(a, 76);
        int hazardH = dp(a, 58);
        int playerH = dp(a, 76);
        int metricsH = dp(a, 50);
        int railW = clamp(Math.round(w * .22f), dp(a, 78), dp(a, 96));
        int speedSize = clamp(Math.min(Math.round(w * .49f), Math.round(h * .22f)), dp(a, 154), dp(a, 202));

        LinearLayout header = row(a);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(a, 6), 0, dp(a, 10), 0);
        header.setBackground(panel(a, GLASS_STRONG, 18, BORDER, 1));
        Button menu = plainButton(a, "☰");
        menu.setTextSize(24);
        menu.setContentDescription("Abrir painel");
        menu.setOnClickListener(v -> openCentralMenu(a, root, map));
        header.addView(menu, new LinearLayout.LayoutParams(dp(a, 52), -1));

        TextView star = text(a, "★", 23, GOLD, true);
        star.setGravity(Gravity.CENTER);
        header.addView(star, new LinearLayout.LayoutParams(dp(a, 34), -1));

        LinearLayout brand = col(a);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.addView(text(a, "ESTRADA PLAY", 15, WHITE, true));
        brand.addView(protection);
        header.addView(brand, new LinearLayout.LayoutParams(0, -1, 1f));

        LinearLayout status = col(a);
        status.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        clock.setGravity(Gravity.RIGHT);
        gps.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        status.addView(clock);
        status.addView(gps);
        header.addView(status, new LinearLayout.LayoutParams(clamp(Math.round(w * .28f), dp(a, 94), dp(a, 124)), -1));

        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-1, headerH, Gravity.TOP);
        hp.setMargins(margin, margin, margin, 0);
        root.addView(header, hp);

        LinearLayout route = row(a);
        route.setGravity(Gravity.CENTER_VERTICAL);
        route.setPadding(dp(a, 10), dp(a, 7), dp(a, 12), dp(a, 7));
        route.setBackground(panel(a, GLASS, 18, RED_DARK, 1));
        turn.setTextSize(22);
        route.addView(turn, new LinearLayout.LayoutParams(dp(a, 45), -1));
        LinearLayout routeWords = col(a);
        routeWords.setGravity(Gravity.CENTER_VERTICAL);
        routeWords.addView(instruction);
        LinearLayout routeMeta = row(a);
        routeMeta.setGravity(Gravity.CENTER_VERTICAL);
        routeMeta.addView(routeRoad, new LinearLayout.LayoutParams(0, -2, 1f));
        routeMeta.addView(weather);
        routeWords.addView(routeMeta);
        route.addView(routeWords, new LinearLayout.LayoutParams(0, -1, 1f));
        routeDistance.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        route.addView(routeDistance, new LinearLayout.LayoutParams(dp(a, 70), -1));
        route.setOnClickListener(v -> a.startActivity(new Intent(a, DestinationActivity.class)));
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(-1, routeH, Gravity.TOP);
        rp.setMargins(margin, margin + headerH + gap, margin, 0);
        root.addView(route, rp);

        FrameLayout.LayoutParams hz = new FrameLayout.LayoutParams(-1, hazardH, Gravity.TOP);
        hz.setMargins(margin, margin + headerH + gap + routeH + gap, margin, 0);
        root.addView(hazardCard, hz);

        LinearLayout speedCard = col(a);
        speedCard.setGravity(Gravity.CENTER);
        speedCard.setBackground(panel(a, Color.argb(218, 4, 4, 7), 100, Color.argb(150, 130, 23, 42), 1));
        speedCard.addView(gauge, new LinearLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(speedSize, speedSize, Gravity.BOTTOM | Gravity.LEFT);
        sp.setMargins(margin, 0, 0, margin + playerH + gap + metricsH + gap);
        root.addView(speedCard, sp);

        int limitSize = dp(a, 58);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(limitSize, limitSize, Gravity.BOTTOM | Gravity.LEFT);
        lp.setMargins(margin + Math.max(0, speedSize - limitSize / 2), 0, 0,
                margin + playerH + gap + metricsH + gap + Math.max(0, speedSize - limitSize - dp(a, 4)));
        root.addView(limit, lp);

        LinearLayout rail = col(a);
        rail.setGravity(Gravity.CENTER);
        Button copilot = actionChip(a, "COPILOTO", true);
        Button routeB = actionChip(a, "ROTA", false);
        Button dash = actionChip(a, "DASH", false);
        Button ptt = actionChip(a, "PTT", false);
        bindActions(a, copilot, routeB, dash, ptt);
        rail.addView(copilot, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout.LayoutParams r1 = new LinearLayout.LayoutParams(-1, 0, 1f); r1.setMargins(0, gap, 0, 0); rail.addView(routeB, r1);
        LinearLayout.LayoutParams r2 = new LinearLayout.LayoutParams(-1, 0, 1f); r2.setMargins(0, gap, 0, 0); rail.addView(dash, r2);
        LinearLayout.LayoutParams r3 = new LinearLayout.LayoutParams(-1, 0, 1f); r3.setMargins(0, gap, 0, 0); rail.addView(ptt, r3);
        int railH = dp(a, 238);
        FrameLayout.LayoutParams railLp = new FrameLayout.LayoutParams(railW, railH, Gravity.BOTTOM | Gravity.RIGHT);
        railLp.setMargins(0, 0, margin, margin + playerH + gap + metricsH + gap);
        root.addView(rail, railLp);

        Button recenter = smallRoundButton(a, "◎");
        recenter.setContentDescription("Centralizar mapa");
        recenter.setOnClickListener(v -> map.recenter());
        FrameLayout.LayoutParams rec = new FrameLayout.LayoutParams(dp(a, 48), dp(a, 48), Gravity.BOTTOM | Gravity.RIGHT);
        rec.setMargins(0, 0, margin + railW + gap, margin + playerH + gap + metricsH + gap);
        root.addView(recenter, rec);

        LinearLayout metrics = buildMetrics(a, eta, remaining, duration);
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(-1, metricsH, Gravity.BOTTOM);
        mp.setMargins(margin, 0, margin, margin + playerH + gap);
        root.addView(metrics, mp);

        LinearLayout player = buildPlayer(a, playerTitle, playerArtist, playerToggle, true);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(-1, playerH, Gravity.BOTTOM);
        pp.setMargins(margin, 0, margin, margin);
        root.addView(player, pp);
    }

    private static void buildLandscape(RoadMapActivity a, FrameLayout root, RoadMapView map, int w, int h,
                                       ReferenceSpeedometerView gauge, TextView gps, TextView protection,
                                       TextView clock, TextView instruction, TextView weather, TextView routeRoad,
                                       TextView routeDistance, TextView turn, TextView limit, TextView eta,
                                       TextView remaining, TextView duration, LinearLayout hazardCard,
                                       TextView playerTitle, TextView playerArtist, Button playerToggle) {
        int margin = Math.max(dp(a, 7), Math.round(h * .018f));
        int gap = dp(a, 8);
        int headerH = clamp(Math.round(h * .13f), dp(a, 54), dp(a, 66));
        int headerW = clamp(Math.round(w * .245f), dp(a, 270), dp(a, 360));
        int speedSize = clamp(Math.min(Math.round(h * .38f), Math.round(w * .17f)), dp(a, 142), dp(a, 205));
        int routeH = clamp(Math.round(h * .16f), dp(a, 64), dp(a, 80));
        int playerW = clamp(Math.round(w * .31f), dp(a, 300), dp(a, 430));
        int playerH = clamp(Math.round(h * .17f), dp(a, 72), dp(a, 92));
        int actionH = clamp(Math.round(h * .14f), dp(a, 58), dp(a, 76));
        int routeLeft = margin + headerW + gap;
        int routeRightReserve = speedSize + margin + gap;
        int routeW = Math.max(dp(a, 280), w - routeLeft - routeRightReserve - margin);

        LinearLayout header = row(a);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(a, 5), 0, dp(a, 10), 0);
        header.setBackground(panel(a, GLASS_STRONG, 18, BORDER, 1));
        Button menu = plainButton(a, "☰");
        menu.setTextSize(22);
        menu.setOnClickListener(v -> openCentralMenu(a, root, map));
        header.addView(menu, new LinearLayout.LayoutParams(dp(a, 48), -1));
        TextView star = text(a, "★", 22, GOLD, true); star.setGravity(Gravity.CENTER);
        header.addView(star, new LinearLayout.LayoutParams(dp(a, 32), -1));
        LinearLayout brand = col(a);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.addView(text(a, "ESTRADA PLAY", 15, WHITE, true));
        brand.addView(protection);
        header.addView(brand, new LinearLayout.LayoutParams(0, -1, 1f));
        LinearLayout time = col(a); time.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        clock.setGravity(Gravity.RIGHT); gps.setGravity(Gravity.RIGHT);
        time.addView(clock); time.addView(gps);
        header.addView(time, new LinearLayout.LayoutParams(dp(a, 98), -1));
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(headerW, headerH, Gravity.TOP | Gravity.LEFT);
        hp.setMargins(margin, margin, 0, 0);
        root.addView(header, hp);

        LinearLayout route = row(a);
        route.setGravity(Gravity.CENTER_VERTICAL);
        route.setPadding(dp(a, 9), dp(a, 6), dp(a, 12), dp(a, 6));
        route.setBackground(panel(a, GLASS, 18, RED_DARK, 1));
        turn.setGravity(Gravity.CENTER);
        route.addView(turn, new LinearLayout.LayoutParams(dp(a, 48), -1));
        LinearLayout words = col(a); words.setGravity(Gravity.CENTER_VERTICAL);
        words.addView(instruction);
        LinearLayout meta = row(a); meta.setGravity(Gravity.CENTER_VERTICAL);
        meta.addView(routeRoad, new LinearLayout.LayoutParams(0, -2, 1f));
        meta.addView(weather);
        words.addView(meta);
        route.addView(words, new LinearLayout.LayoutParams(0, -1, 1f));
        routeDistance.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        route.addView(routeDistance, new LinearLayout.LayoutParams(dp(a, 80), -1));
        route.setOnClickListener(v -> a.startActivity(new Intent(a, DestinationActivity.class)));
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(routeW, routeH, Gravity.TOP | Gravity.LEFT);
        rp.setMargins(routeLeft, margin, 0, 0);
        root.addView(route, rp);

        LinearLayout speedCard = col(a);
        speedCard.setGravity(Gravity.CENTER);
        speedCard.setBackground(panel(a, Color.argb(222, 4, 4, 7), 100, Color.argb(150, 130, 23, 42), 1));
        speedCard.addView(gauge, new LinearLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(speedSize, speedSize, Gravity.TOP | Gravity.RIGHT);
        sp.setMargins(0, margin, margin, 0);
        root.addView(speedCard, sp);

        int limitSize = dp(a, 56);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(limitSize, limitSize, Gravity.TOP | Gravity.RIGHT);
        lp.setMargins(0, margin + Math.max(0, speedSize - limitSize / 2), margin + Math.max(0, speedSize - limitSize / 2), 0);
        root.addView(limit, lp);

        FrameLayout.LayoutParams hz = new FrameLayout.LayoutParams(headerW, dp(a, 62), Gravity.TOP | Gravity.LEFT);
        hz.setMargins(margin, margin + headerH + gap, 0, 0);
        root.addView(hazardCard, hz);

        LinearLayout metrics = buildMetrics(a, eta, remaining, duration);
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(routeW, dp(a, 48), Gravity.TOP | Gravity.LEFT);
        mp.setMargins(routeLeft, margin + routeH + gap, 0, 0);
        root.addView(metrics, mp);

        LinearLayout actions = row(a);
        actions.setGravity(Gravity.CENTER);
        Button copilot = actionChip(a, "COPILOTO", true);
        Button routeB = actionChip(a, "ROTA", false);
        Button dash = actionChip(a, "DASH", false);
        Button ptt = actionChip(a, "PTT", false);
        bindActions(a, copilot, routeB, dash, ptt);
        int actionW = clamp(Math.round(w * .086f), dp(a, 90), dp(a, 124));
        actions.addView(copilot, new LinearLayout.LayoutParams(actionW, -1));
        LinearLayout.LayoutParams a1 = new LinearLayout.LayoutParams(actionW, -1); a1.setMargins(gap, 0, 0, 0); actions.addView(routeB, a1);
        LinearLayout.LayoutParams a2 = new LinearLayout.LayoutParams(actionW, -1); a2.setMargins(gap, 0, 0, 0); actions.addView(dash, a2);
        LinearLayout.LayoutParams a3 = new LinearLayout.LayoutParams(actionW, -1); a3.setMargins(gap, 0, 0, 0); actions.addView(ptt, a3);
        FrameLayout.LayoutParams ap = new FrameLayout.LayoutParams(actionW * 4 + gap * 3, actionH, Gravity.BOTTOM | Gravity.LEFT);
        ap.setMargins(margin, 0, 0, margin);
        root.addView(actions, ap);

        Button recenter = smallRoundButton(a, "◎");
        recenter.setContentDescription("Centralizar mapa");
        recenter.setOnClickListener(v -> map.recenter());
        FrameLayout.LayoutParams rec = new FrameLayout.LayoutParams(dp(a, 50), dp(a, 50), Gravity.BOTTOM | Gravity.LEFT);
        rec.setMargins(margin + actionW * 4 + gap * 4, 0, 0, margin + Math.max(0, (actionH - dp(a, 50)) / 2));
        root.addView(recenter, rec);

        LinearLayout player = buildPlayer(a, playerTitle, playerArtist, playerToggle, false);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(playerW, playerH, Gravity.BOTTOM | Gravity.RIGHT);
        pp.setMargins(0, 0, margin, margin);
        root.addView(player, pp);
    }

    private static LinearLayout buildMetrics(Activity a, TextView eta, TextView remaining, TextView duration) {
        LinearLayout metrics = row(a);
        metrics.setGravity(Gravity.CENTER_VERTICAL);
        metrics.setPadding(dp(a, 8), 0, dp(a, 8), 0);
        metrics.setBackground(panel(a, Color.argb(216, 6, 6, 9), 15, BORDER, 1));
        metrics.addView(metric(a, "CHEGADA", eta), new LinearLayout.LayoutParams(0, -1, 1f));
        metrics.addView(metric(a, "DISTÂNCIA", remaining), new LinearLayout.LayoutParams(0, -1, 1f));
        metrics.addView(metric(a, "TEMPO", duration), new LinearLayout.LayoutParams(0, -1, 1f));
        return metrics;
    }

    private static LinearLayout buildPlayer(Activity a, TextView title, TextView artist, Button toggle, boolean portrait) {
        LinearLayout player = row(a);
        player.setGravity(Gravity.CENTER_VERTICAL);
        player.setPadding(dp(a, 12), dp(a, 7), dp(a, 8), dp(a, 7));
        player.setBackground(panel(a, GLASS_STRONG, 18, RED_DARK, 1));
        LinearLayout words = col(a);
        words.setGravity(Gravity.CENTER_VERTICAL);
        words.addView(title);
        words.addView(artist);
        words.setOnClickListener(v -> a.startActivity(new Intent(a, MusicPlayerActivity.class)));
        player.addView(words, new LinearLayout.LayoutParams(0, -1, 1f));

        Button prev = mediaButton(a, "‹", false);
        Button next = mediaButton(a, "›", false);
        int button = portrait ? dp(a, 50) : dp(a, 54);
        player.addView(prev, new LinearLayout.LayoutParams(button, -1));
        LinearLayout.LayoutParams play = new LinearLayout.LayoutParams(button, -1);
        play.setMargins(dp(a, 5), 0, dp(a, 5), 0);
        player.addView(toggle, play);
        player.addView(next, new LinearLayout.LayoutParams(button, -1));
        bindPlayer(a, prev, toggle, next);
        return player;
    }

    private static LinearLayout metric(Activity a, String label, TextView value) {
        LinearLayout box = col(a);
        box.setGravity(Gravity.CENTER);
        TextView l = text(a, label, 8, MUTED, true);
        l.setGravity(Gravity.CENTER);
        value.setGravity(Gravity.CENTER);
        box.addView(l);
        box.addView(value);
        return box;
    }

    private static void bindActions(Activity a, Button copilot, Button route, Button dash, Button ptt) {
        copilot.setOnClickListener(v -> {
            if (a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                CopilotService.requestListenNow(a);
            } else if (Build.VERSION.SDK_INT >= 23) {
                a.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 2202);
            }
        });
        route.setOnClickListener(v -> a.startActivity(new Intent(a, DestinationActivity.class)));
        dash.setOnClickListener(v -> a.startActivity(new Intent(a, CameraActivity.class)));
        ptt.setOnClickListener(v -> a.startActivity(new Intent(a, RoadRadioActivity.class)));
    }

    private static void openCentralMenu(RoadMapActivity a, FrameLayout root, RoadMapView map) {
        AppMenuOverlay.show(a, root, "mapa", index -> {
            if (index == 0) {
                a.startActivity(new Intent(a, MainActivity.class));
            } else if (index == 1) {
                a.startActivity(new Intent(a, MusicPlayerActivity.class));
            } else if (index == 2) {
                a.startActivity(new Intent(a, MusicStorageActivity.class));
            } else if (index == 3) {
                if (map != null) map.recenter();
            } else if (index == 4) {
                a.startActivity(new Intent(a, MainActivity.class).putExtra("open", "account"));
            }
        });
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
        t.setText(value);
        t.setTextSize(13);
        t.setTextColor(WHITE);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setSingleLine(true);
        return t;
    }

    private static TextView limit(Activity a, ReferenceSpeedometerView gauge) {
        LimitBridgeTextView t = new LimitBridgeTextView(a, gauge);
        t.setText("—");
        t.setTextSize(20);
        t.setTextColor(Color.rgb(27, 27, 30));
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setBackground(panel(a, Color.WHITE, 100, RED, 5));
        return t;
    }

    private static void styleGps(TextView t, Activity a) {
        t.setText("● GPS ATIVO");
        t.setTextColor(GREEN);
        t.setTextSize(8.5f);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setSingleLine(true);
    }

    private static Button actionChip(Activity a, String label, boolean primary) {
        Button b = plainButton(a, label);
        b.setTextSize(primary ? 10.5f : 10f);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackground(panel(a, primary ? RED : Color.argb(232, 6, 7, 10), 16,
                primary ? RED : BORDER, 1));
        return b;
    }

    private static Button smallRoundButton(Activity a, String label) {
        Button b = plainButton(a, label);
        b.setTextSize(22);
        b.setBackground(panel(a, Color.argb(232, 5, 6, 9), 100, RED, 1));
        return b;
    }

    private static Button mediaButton(Activity a, String label, boolean primary) {
        Button b = plainButton(a, label);
        b.setTextSize(primary ? 20 : 26);
        b.setBackground(panel(a, primary ? RED : Color.argb(226, 6, 7, 10), 14,
                primary ? RED : Color.rgb(105, 32, 45), 1));
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
        t.setLineSpacing(0, 1.03f);
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

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int dp(Activity a, float v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    private static final class SpeedBridgeTextView extends TextView {
        private final ReferenceSpeedometerView gauge;
        SpeedBridgeTextView(Activity a, ReferenceSpeedometerView gauge) { super(a); this.gauge = gauge; }
        @Override public void setText(CharSequence value, BufferType type) {
            super.setText(value, type);
            if (gauge == null || value == null) return;
            try { gauge.setSpeed(Double.parseDouble(value.toString().trim().replace(',', '.'))); }
            catch (Throwable ignored) {}
        }
    }

    private static final class LimitBridgeTextView extends TextView {
        private final ReferenceSpeedometerView gauge;
        LimitBridgeTextView(Activity a, ReferenceSpeedometerView gauge) { super(a); this.gauge = gauge; }
        @Override public void setText(CharSequence value, BufferType type) {
            int limitValue = 0;
            try { limitValue = Integer.parseInt(value == null ? "" : value.toString().trim()); }
            catch (Throwable ignored) {}
            if (gauge != null) gauge.setLimit(limitValue);
            super.setText(limitValue > 0 ? String.valueOf(limitValue) : "—", type);
        }
    }

    private static final class MetricBridgeTextView extends TextView {
        MetricBridgeTextView(Activity a) { super(a); }
        @Override public void setText(CharSequence value, BufferType type) {
            String s = value == null ? "" : value.toString();
            int cut = s.indexOf('\n');
            if (cut >= 0) s = s.substring(0, cut).trim();
            super.setText(s, type);
        }
    }

    private static final class GpsBridgeTextView extends TextView {
        private final ReferenceSpeedometerView gauge;
        GpsBridgeTextView(Activity a, ReferenceSpeedometerView gauge) { super(a); this.gauge = gauge; }
        @Override public void setText(CharSequence value, BufferType type) {
            boolean ok = value != null && value.toString().toUpperCase().contains("ATIVO");
            if (gauge != null) gauge.setGpsAvailable(ok);
            super.setText(ok ? "● GPS ATIVO" : "● GPS BUSCANDO", type);
            setTextColor(ok ? GREEN : Color.rgb(242, 181, 65));
        }
    }
}

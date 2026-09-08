package com.estradaplay.comunista;

import android.app.Activity;
import android.content.Intent;
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
 * Estrada cockpit v4.1.3.
 *
 * The fixed driving UI has only three permanent elements:
 * 1) full-screen map;
 * 2) speedometer floating on the right;
 * 3) compact music bar at the bottom.
 *
 * Route, hazard and service state bindings are kept hidden so RoadMapActivity can continue
 * updating navigation/safety state and temporary overlays can appear only when actually needed.
 */
final class RoadCockpitUiV400 {
    private static final int RED = Color.rgb(226, 12, 39);
    private static final int WHITE = Color.rgb(248, 246, 245);
    private static final int MUTED = Color.rgb(181, 171, 173);
    private static final int PANEL = Color.argb(235, 5, 6, 9);
    private static final int BORDER = Color.rgb(92, 31, 44);

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
        GpsBridgeTextView gpsBridge = new GpsBridgeTextView(a, gauge);
        LimitBridgeTextView limitBridge = new LimitBridgeTextView(a, gauge);

        TextView hazardTitle = ghost(a);
        TextView hazardDetail = ghost(a);
        TextView protection = ghost(a);
        TextView clock = ghost(a);
        TextView destination = ghost(a);
        TextView instruction = ghost(a);
        TextView weather = ghost(a);
        TextView routeDistance = ghost(a);
        TextView routeRoad = ghost(a);
        TextView eta = ghost(a);
        TextView remaining = ghost(a);
        TextView duration = ghost(a);
        TextView turn = ghost(a);

        LinearLayout hazardCard = new LinearLayout(a);
        hazardCard.setVisibility(View.GONE);

        TextView playerTitle = text(a, "Biblioteca offline", portrait ? 14 : 15, WHITE, true);
        playerTitle.setSingleLine(true);
        TextView playerArtist = text(a, "Música local", 10, MUTED, false);
        playerArtist.setSingleLine(true);
        Button playerToggle = mediaButton(a, "▶", true);

        addHidden(root, speedBridge);
        addHidden(root, gpsBridge);
        addHidden(root, limitBridge);
        addHidden(root, hazardTitle);
        addHidden(root, hazardDetail);
        addHidden(root, protection);
        addHidden(root, clock);
        addHidden(root, destination);
        addHidden(root, instruction);
        addHidden(root, weather);
        addHidden(root, routeDistance);
        addHidden(root, routeRoad);
        addHidden(root, eta);
        addHidden(root, remaining);
        addHidden(root, duration);
        addHidden(root, turn);
        root.addView(hazardCard, new FrameLayout.LayoutParams(1, 1));

        buildSpeedometer(a, root, width, height, portrait, gauge);
        buildPlayer(a, root, width, portrait, playerTitle, playerArtist, playerToggle);

        return new Bindings(map, speedBridge, hazardTitle, hazardDetail, protection, clock, gpsBridge,
                destination, instruction, limitBridge, weather, routeDistance, routeRoad, eta,
                remaining, duration, turn, playerTitle, playerArtist, playerToggle, hazardCard);
    }

    private static void buildSpeedometer(RoadMapActivity a, FrameLayout root, int w, int h,
                                         boolean portrait, ReferenceSpeedometerView gauge) {
        int margin = dp(a, portrait ? 10 : 8);
        int playerHeight = dp(a, portrait ? 76 : 68);
        int maxByWidth = portrait ? Math.round(w * .43f) : Math.round(w * .18f);
        int maxByHeight = portrait ? Math.round(h * .22f) : Math.round(h * .40f);
        int size = clamp(Math.min(maxByWidth, maxByHeight), dp(a, 142), dp(a, portrait ? 190 : 198));

        FrameLayout card = new FrameLayout(a);
        card.setBackground(panel(a, Color.argb(218, 4, 5, 8), 100, Color.argb(165, 118, 24, 41), 1));
        card.addView(gauge, new FrameLayout.LayoutParams(-1, -1));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size,
                Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        lp.setMargins(0, 0, margin, playerHeight / 2);
        root.addView(card, lp);
        if (Build.VERSION.SDK_INT >= 21) card.setElevation(dp(a, 18));
    }

    private static void buildPlayer(Activity a, FrameLayout root, int width, boolean portrait,
                                    TextView title, TextView artist, Button toggle) {
        int margin = dp(a, portrait ? 9 : 8);
        int height = dp(a, portrait ? 76 : 68);
        int button = dp(a, portrait ? 50 : 48);

        LinearLayout player = new LinearLayout(a);
        player.setOrientation(LinearLayout.HORIZONTAL);
        player.setGravity(Gravity.CENTER_VERTICAL);
        player.setPadding(dp(a, 13), dp(a, 6), dp(a, 8), dp(a, 6));
        player.setBackground(panel(a, PANEL, 18, BORDER, 1));

        LinearLayout info = new LinearLayout(a);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.addView(title);
        info.addView(artist);
        info.setOnClickListener(v -> a.startActivity(new Intent(a, MusicPlayerActivity.class)));
        player.addView(info, new LinearLayout.LayoutParams(0, -1, 1f));

        Button prev = mediaButton(a, "‹", false);
        Button next = mediaButton(a, "›", false);
        player.addView(prev, new LinearLayout.LayoutParams(button, -1));
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(button, -1);
        playLp.setMargins(dp(a, 5), 0, dp(a, 5), 0);
        player.addView(toggle, playLp);
        player.addView(next, new LinearLayout.LayoutParams(button, -1));
        bindPlayer(a, prev, toggle, next);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, height, Gravity.BOTTOM);
        lp.setMargins(margin, 0, margin, margin);
        root.addView(player, lp);
        if (Build.VERSION.SDK_INT >= 21) player.setElevation(dp(a, 20));
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

    private static void addHidden(FrameLayout root, View view) {
        view.setVisibility(View.GONE);
        root.addView(view, new FrameLayout.LayoutParams(1, 1));
    }

    private static Button mediaButton(Activity a, String value, boolean primary) {
        Button b = new Button(a);
        b.setText(value);
        b.setAllCaps(false);
        b.setTextColor(WHITE);
        b.setTextSize(primary ? 20 : 27);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, 0, 0, 0);
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setStateListAnimator(null);
        b.setBackground(panel(a, primary ? RED : Color.argb(224, 7, 8, 11), 15,
                primary ? RED : Color.rgb(105, 35, 48), 1));
        return b;
    }

    private static TextView text(Activity a, String value, float size, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private static TextView ghost(Activity a) {
        TextView t = new TextView(a);
        t.setVisibility(View.GONE);
        return t;
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
            int limit = 0;
            try { limit = Integer.parseInt(value == null ? "" : value.toString().trim()); }
            catch (Throwable ignored) {}
            if (gauge != null) gauge.setLimit(limit);
            super.setText(limit > 0 ? String.valueOf(limit) : "—", type);
        }
    }

    private static final class GpsBridgeTextView extends TextView {
        private final ReferenceSpeedometerView gauge;
        GpsBridgeTextView(Activity a, ReferenceSpeedometerView gauge) { super(a); this.gauge = gauge; }
        @Override public void setText(CharSequence value, BufferType type) {
            boolean ok = value != null && value.toString().toUpperCase().contains("ATIVO");
            if (gauge != null) gauge.setGpsAvailable(ok);
            super.setText(value, type);
        }
    }
}

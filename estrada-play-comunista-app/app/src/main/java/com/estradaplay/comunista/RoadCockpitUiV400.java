package com.estradaplay.comunista;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Estrada Play Premium road cockpit.
 *
 * Permanent UI: full-screen map + speedometer on the right + music bar at the bottom.
 * Navigation and hazards are contextual overlays and disappear when they are not needed.
 */
final class RoadCockpitUiV400 {
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
        EstradaTheme theme = EstradaTheme.get(a);

        RoadMapView map = new RoadMapView(a);
        root.addView(map, new FrameLayout.LayoutParams(-1, -1));

        ReferenceSpeedometerView gauge = new ReferenceSpeedometerView(a);
        SpeedBridgeTextView speedBridge = new SpeedBridgeTextView(a, gauge);
        GpsBridgeTextView gpsBridge = new GpsBridgeTextView(a, gauge);

        TextView limitBadge = PremiumUi.text(a, "—", portrait ? 18 : 19, theme.text, true);
        limitBadge.setGravity(Gravity.CENTER);
        limitBadge.setBackground(PremiumUi.panel(a, Color.argb(245, 249, 250, 252), theme.danger, 100));
        limitBadge.setTextColor(Color.rgb(22, 27, 34));
        LimitBridgeTextView limitBridge = new LimitBridgeTextView(a, gauge, limitBadge);

        TextView protection = ghost(a);
        TextView clock = ghost(a);
        TextView destination = ghost(a);
        TextView weather = ghost(a);
        TextView eta = ghost(a);
        TextView remaining = ghost(a);
        TextView duration = ghost(a);

        LinearLayout routeCard = PremiumUi.row(a);
        routeCard.setGravity(Gravity.CENTER_VERTICAL);
        routeCard.setPadding(dp(a, 12), dp(a, 8), dp(a, 12), dp(a, 8));
        routeCard.setBackground(PremiumUi.panel(a, theme.glass, PremiumUi.withAlpha(theme.primary, 125), theme.radiusDp));
        routeCard.setVisibility(View.GONE);
        TextView turn = PremiumUi.text(a, "↑", portrait ? 25 : 27, theme.text, true);
        turn.setGravity(Gravity.CENTER);
        routeCard.addView(turn, new LinearLayout.LayoutParams(dp(a, 50), -1));
        LinearLayout routeWords = PremiumUi.col(a);
        routeWords.setGravity(Gravity.CENTER_VERTICAL);
        RouteInstructionBridgeTextView instruction = new RouteInstructionBridgeTextView(a, routeCard, theme);
        routeWords.addView(instruction);
        LinearLayout meta = PremiumUi.row(a);
        TextView routeRoad = PremiumUi.text(a, "", 10, theme.muted, false);
        TextView routeDistance = PremiumUi.text(a, "", 11, theme.secondary, true);
        routeDistance.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        meta.addView(routeRoad, new LinearLayout.LayoutParams(0, -2, 1f));
        meta.addView(routeDistance, new LinearLayout.LayoutParams(dp(a, 82), -2));
        routeWords.addView(meta);
        routeCard.addView(routeWords, new LinearLayout.LayoutParams(0, -1, 1f));
        routeCard.setOnClickListener(v -> a.startActivity(new Intent(a, DestinationActivity.class)));

        FrameLayout.LayoutParams routeLp = new FrameLayout.LayoutParams(
                portrait ? -1 : Math.min(dp(a, 520), Math.round(width * .52f)),
                dp(a, portrait ? 76 : 72), Gravity.TOP | Gravity.LEFT);
        routeLp.setMargins(dp(a, 10), dp(a, 10), portrait ? dp(a, 10) : 0, 0);
        root.addView(routeCard, routeLp);

        LinearLayout hazardCard = PremiumUi.col(a);
        hazardCard.setGravity(Gravity.CENTER_VERTICAL);
        hazardCard.setPadding(dp(a, 14), dp(a, 9), dp(a, 14), dp(a, 9));
        hazardCard.setBackground(PremiumUi.panel(a, PremiumUi.withAlpha(theme.surface, 246), theme.danger, theme.radiusDp));
        hazardCard.setVisibility(View.GONE);
        HazardTitleBridgeTextView hazardTitle = new HazardTitleBridgeTextView(a, hazardCard, theme);
        TextView hazardDetail = PremiumUi.text(a, "", 10, theme.muted, false);
        hazardCard.addView(hazardTitle);
        hazardCard.addView(hazardDetail);
        FrameLayout.LayoutParams hazardLp = new FrameLayout.LayoutParams(
                portrait ? Math.min(dp(a, 330), Math.round(width * .72f)) : Math.min(dp(a, 390), Math.round(width * .35f)),
                -2, Gravity.TOP | Gravity.LEFT);
        hazardLp.setMargins(dp(a, 10), dp(a, portrait ? 96 : 92), 0, 0);
        root.addView(hazardCard, hazardLp);

        TextView playerTitle = PremiumUi.text(a, "Biblioteca offline", portrait ? 14 : 15, theme.text, true);
        playerTitle.setSingleLine(true);
        playerTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView playerArtist = PremiumUi.text(a, "Toque para escolher", 10, theme.muted, false);
        playerArtist.setSingleLine(true);
        playerArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        Button playerToggle = playerButton(a, theme, "▶", true);

        addHidden(root, speedBridge);
        addHidden(root, gpsBridge);
        addHidden(root, limitBridge);
        addHidden(root, protection);
        addHidden(root, clock);
        addHidden(root, destination);
        addHidden(root, weather);
        addHidden(root, eta);
        addHidden(root, remaining);
        addHidden(root, duration);

        buildSpeedometer(a, root, width, height, portrait, gauge, limitBadge, theme);
        buildPlayer(a, root, portrait, playerTitle, playerArtist, playerToggle, theme);

        return new Bindings(map, speedBridge, hazardTitle, hazardDetail, protection, clock, gpsBridge,
                destination, instruction, limitBridge, weather, routeDistance, routeRoad, eta,
                remaining, duration, turn, playerTitle, playerArtist, playerToggle, hazardCard);
    }

    private static void buildSpeedometer(Activity a, FrameLayout root, int width, int height,
                                         boolean portrait, ReferenceSpeedometerView gauge,
                                         TextView limitBadge, EstradaTheme theme) {
        int margin = dp(a, portrait ? 10 : 12);
        int playerHeight = dp(a, portrait ? 90 : 82);
        int maxByWidth = portrait ? Math.round(width * .42f) : Math.round(width * .20f);
        int maxByHeight = portrait ? Math.round(height * .23f) : Math.round(height * .40f);
        int size = clamp(Math.min(maxByWidth, maxByHeight), dp(a, 148), dp(a, portrait ? 196 : 216));

        FrameLayout card = new FrameLayout(a);
        card.setBackground(PremiumUi.panel(a, PremiumUi.withAlpha(theme.background, 220), PremiumUi.withAlpha(theme.border, 205), 100));
        card.addView(gauge, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        lp.setMargins(0, 0, margin, playerHeight / 2);
        root.addView(card, lp);
        if (Build.VERSION.SDK_INT >= 21) card.setElevation(dp(a, 18));

        int limitSize = dp(a, portrait ? 54 : 58);
        FrameLayout.LayoutParams limitLp = new FrameLayout.LayoutParams(limitSize, limitSize, Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        limitLp.setMargins(0, Math.max(0, size - limitSize - dp(a, 4)), margin + Math.max(0, (size - limitSize) / 2), 0);
        root.addView(limitBadge, limitLp);
        if (Build.VERSION.SDK_INT >= 21) limitBadge.setElevation(dp(a, 23));
    }

    private static void buildPlayer(Activity a, FrameLayout root, boolean portrait,
                                    TextView title, TextView artist, Button toggle, EstradaTheme theme) {
        int margin = dp(a, portrait ? 9 : 10);
        int height = dp(a, portrait ? 90 : 82);
        int control = dp(a, portrait ? 48 : 48);

        LinearLayout player = PremiumUi.row(a);
        player.setGravity(Gravity.CENTER_VERTICAL);
        player.setPadding(dp(a, 10), dp(a, 7), dp(a, 8), dp(a, 7));
        player.setBackground(PremiumUi.panel(a, theme.glass, theme.border, theme.radiusDp + 3));

        TextView art = PremiumUi.text(a, "♪", 23, theme.secondary, true);
        art.setGravity(Gravity.CENTER);
        art.setBackground(PremiumUi.gradient(a, PremiumUi.withAlpha(theme.primary, 185), PremiumUi.withAlpha(theme.secondary, 155), 16));
        player.addView(art, new LinearLayout.LayoutParams(dp(a, portrait ? 58 : 56), dp(a, portrait ? 58 : 56)));

        LinearLayout info = PremiumUi.col(a);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.addView(title);
        info.addView(artist);
        info.setClickable(true);
        info.setFocusable(true);
        info.setOnClickListener(v -> RoadMusicChooserOverlay.show(a, root));
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, -1, 1f);
        infoLp.setMargins(dp(a, 11), 0, dp(a, 8), 0);
        player.addView(info, infoLp);

        Button prev = playerButton(a, theme, "‹", false);
        Button next = playerButton(a, theme, "›", false);
        Button library = playerButton(a, theme, "≡", false);
        library.setContentDescription("Escolher música");

        player.addView(prev, new LinearLayout.LayoutParams(control, -1));
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(control, -1);
        playLp.setMargins(dp(a, 5), 0, dp(a, 5), 0);
        player.addView(toggle, playLp);
        player.addView(next, new LinearLayout.LayoutParams(control, -1));
        LinearLayout.LayoutParams libraryLp = new LinearLayout.LayoutParams(control, -1);
        libraryLp.setMargins(dp(a, 6), 0, 0, 0);
        player.addView(library, libraryLp);

        prev.setOnClickListener(v -> player(a, PlayerService.ACTION_PREVIOUS));
        toggle.setOnClickListener(v -> player(a, PlayerService.ACTION_TOGGLE));
        next.setOnClickListener(v -> player(a, PlayerService.ACTION_NEXT));
        library.setOnClickListener(v -> RoadMusicChooserOverlay.show(a, root));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, height, Gravity.BOTTOM);
        lp.setMargins(margin, 0, margin, margin);
        root.addView(player, lp);
        if (Build.VERSION.SDK_INT >= 21) player.setElevation(dp(a, 22));
    }

    private static void player(Activity a, String action) {
        try {
            Intent i = new Intent(a, PlayerService.class).setAction(action);
            if (Build.VERSION.SDK_INT >= 26) a.startForegroundService(i); else a.startService(i);
        } catch (Throwable ignored) {}
    }

    private static Button playerButton(Activity a, EstradaTheme theme, String value, boolean primary) {
        Button b = new Button(a);
        b.setText(value);
        b.setAllCaps(false);
        b.setTextColor(theme.text);
        b.setTextSize(primary ? 18 : 25);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setStateListAnimator(null);
        b.setBackground(PremiumUi.panel(a,
                primary ? theme.primary : PremiumUi.withAlpha(theme.surfaceAlt, 235),
                primary ? theme.primary : theme.border, 15));
        return b;
    }

    private static void addHidden(FrameLayout root, View view) {
        view.setVisibility(View.GONE);
        root.addView(view, new FrameLayout.LayoutParams(1, 1));
    }

    private static TextView ghost(Activity a) {
        TextView t = new TextView(a);
        t.setVisibility(View.GONE);
        return t;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int dp(Activity a, float v) {
        return PremiumUi.dp(a, v);
    }

    private static boolean hasDestination(Activity a) {
        try { return DestinationStore.read(a) != null; }
        catch (Throwable ignored) { return false; }
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
        private final TextView badge;
        LimitBridgeTextView(Activity a, ReferenceSpeedometerView gauge, TextView badge) {
            super(a); this.gauge = gauge; this.badge = badge;
        }
        @Override public void setText(CharSequence value, BufferType type) {
            int limit = 0;
            try { limit = Integer.parseInt(value == null ? "" : value.toString().trim()); }
            catch (Throwable ignored) {}
            if (gauge != null) gauge.setLimit(limit);
            if (badge != null) badge.setText(limit > 0 ? String.valueOf(limit) : "—");
            super.setText(limit > 0 ? String.valueOf(limit) : "—", type);
        }
    }

    private static final class GpsBridgeTextView extends TextView {
        private final ReferenceSpeedometerView gauge;
        GpsBridgeTextView(Activity a, ReferenceSpeedometerView gauge) { super(a); this.gauge = gauge; }
        @Override public void setText(CharSequence value, BufferType type) {
            boolean ok = value != null && value.toString().toUpperCase(java.util.Locale.ROOT).contains("ATIVO");
            if (gauge != null) gauge.setGpsAvailable(ok);
            super.setText(value, type);
        }
    }

    private static final class RouteInstructionBridgeTextView extends TextView {
        private final Activity activity;
        private final View card;
        private final EstradaTheme theme;
        RouteInstructionBridgeTextView(Activity a, View card, EstradaTheme theme) {
            super(a); this.activity = a; this.card = card; this.theme = theme;
            setTextSize(16); setTextColor(theme.text); setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            setSingleLine(true); setEllipsize(android.text.TextUtils.TruncateAt.END);
        }
        @Override public void setText(CharSequence value, BufferType type) {
            String s = value == null ? "" : value.toString().trim();
            super.setText(s, type);
            boolean show = hasDestination(activity) && !s.isEmpty();
            if (card != null) card.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    private static final class HazardTitleBridgeTextView extends TextView {
        private final View card;
        HazardTitleBridgeTextView(Activity a, View card, EstradaTheme theme) {
            super(a); this.card = card;
            setTextSize(13); setTextColor(theme.text); setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            setSingleLine(true); setEllipsize(android.text.TextUtils.TruncateAt.END);
        }
        @Override public void setText(CharSequence value, BufferType type) {
            String s = value == null ? "" : value.toString().trim();
            super.setText(s, type);
            String folded = s.toLowerCase(java.util.Locale.ROOT);
            boolean show = !s.isEmpty() && !folded.contains("estrada livre");
            if (card != null) card.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }
}

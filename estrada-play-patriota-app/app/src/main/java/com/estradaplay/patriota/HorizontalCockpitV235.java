package com.estradaplay.patriota;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * HORIZONTAL_MINIMAL_V235
 *
 * Reorganiza somente o cockpit em paisagem. O motor de mapa, navegação, GPS,
 * proteção e player continua pertencendo ao RoadMapActivity. Os mesmos Views
 * são apenas reposicionados, então os listeners e atualizações existentes são
 * preservados sem duplicar GPS, rota ou serviço de música.
 */
final class HorizontalCockpitV235 {
    private static final String MARKER = "EPP_HORIZONTAL_MINIMAL_V235";
    private static final int BG = Color.rgb(6, 4, 5);
    private static final int PANEL = Color.rgb(12, 8, 10);
    private static final int BORDER = Color.rgb(94, 34, 42);
    private static final int TEXT = Color.rgb(246, 238, 224);
    private static final int MUTED = Color.rgb(166, 148, 145);
    private static final int RED = Color.rgb(196, 17, 38);
    private static final int GREEN = Color.rgb(66, 210, 126);

    private HorizontalCockpitV235() {}

    static void apply(View rootView) {
        if (!(rootView instanceof FrameLayout)) return;
        Context context = rootView.getContext();
        if (!(context instanceof RoadMapActivity)) return;
        if (rootView.getResources().getConfiguration().orientation != Configuration.ORIENTATION_LANDSCAPE) return;
        FrameLayout root = (FrameLayout) rootView;
        if (findByContentDescription(root, MARKER) != null) return;
        root.post(() -> applyNow(root));
    }

    private static void applyNow(FrameLayout root) {
        if (findByContentDescription(root, MARKER) != null) return;
        if (root.getWidth() <= 0 || root.getHeight() <= 0) return;

        FrameLayout mapPane = findMapPane(root);
        LinearLayout dock = findDirectLinearWithText(root, "MÚSICA OFFLINE");
        LinearLayout rail = findDirectLinearWithButton(root, "ESTRADA");
        Button recenter = findDirectButton(root, "◎");
        if (mapPane == null || dock == null) return;

        boolean compact = root.getHeight() < dp(root, 430);
        int outer = dp(root, compact ? 5 : 8);
        int gap = dp(root, 8);
        int dockWidth = clamp(Math.round(root.getWidth() * 0.28f), dp(root, 245), dp(root, 390));

        if (rail != null) root.removeView(rail);

        ViewGroup.LayoutParams rawMapLp = mapPane.getLayoutParams();
        FrameLayout.LayoutParams mapLp = rawMapLp instanceof FrameLayout.LayoutParams
                ? (FrameLayout.LayoutParams) rawMapLp
                : new FrameLayout.LayoutParams(-1, -1);
        mapLp.width = -1;
        mapLp.height = -1;
        mapLp.gravity = Gravity.LEFT | Gravity.TOP;
        mapLp.setMargins(outer, outer, outer + dockWidth + gap, outer);
        mapPane.setLayoutParams(mapLp);

        FrameLayout.LayoutParams dockLp = new FrameLayout.LayoutParams(dockWidth, -1, Gravity.RIGHT);
        dockLp.setMargins(0, outer, outer, outer);
        dock.setLayoutParams(dockLp);
        dock.setPadding(0, 0, 0, 0);
        dock.setBackgroundColor(Color.TRANSPARENT);

        LinearLayout guidance = findDirectLinearWithText(mapPane, "GPS");
        LinearLayout speed = findDirectLinearWithText(mapPane, "km/h");
        LinearLayout metrics = findDirectLinearWithAllText(mapPane, "CHEGADA", "RESTANTE", "DURAÇÃO");
        LinearLayout hazard = findOtherMapCard(mapPane, guidance, speed, metrics);
        TextView gpsSource = guidance == null ? null : findTextContaining(guidance, "GPS");

        if (speed != null && speed.getParent() instanceof ViewGroup) {
            ((ViewGroup) speed.getParent()).removeView(speed);
        }

        LinearLayout media = findDirectLinearWithText(dock, "MÚSICA OFFLINE");
        if (media != null && media.getParent() instanceof ViewGroup) {
            ((ViewGroup) media.getParent()).removeView(media);
        }
        dock.removeAllViews();

        FrameLayout speedCard = new FrameLayout(root.getContext());
        speedCard.setBackground(round(root, PANEL, compact ? 18 : 22, BORDER));
        HorizontalGauge gauge = new HorizontalGauge(root.getContext(), speed == null ? null : firstText(speed));
        FrameLayout.LayoutParams gaugeLp = new FrameLayout.LayoutParams(-1, -1);
        gaugeLp.setMargins(dp(root, 10), dp(root, 8), dp(root, 10), dp(root, 12));
        speedCard.addView(gauge, gaugeLp);

        if (speed != null) {
            speed.setGravity(Gravity.CENTER);
            speed.setPadding(0, 0, 0, 0);
            speed.setBackgroundColor(Color.TRANSPARENT);
            if (speed.getChildCount() > 0 && speed.getChildAt(0) instanceof TextView) {
                TextView value = (TextView) speed.getChildAt(0);
                value.setTextSize(compact ? 42 : 54);
                value.setTextColor(TEXT);
                value.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
            }
            if (speed.getChildCount() > 1 && speed.getChildAt(1) instanceof TextView) {
                TextView unit = (TextView) speed.getChildAt(1);
                unit.setTextSize(compact ? 9 : 11);
                unit.setTextColor(MUTED);
            }
            if (speed.getChildCount() > 2) speed.getChildAt(2).setVisibility(View.GONE);
            FrameLayout.LayoutParams speedLp = new FrameLayout.LayoutParams(dp(root, compact ? 150 : 185), dp(root, compact ? 112 : 145), Gravity.CENTER);
            speedLp.setMargins(0, 0, 0, dp(root, 2));
            speedCard.addView(speed, speedLp);
        }

        TextView gpsMirror = text(root, "GPS ATIVO", compact ? 8.5f : 10f, GREEN, true);
        gpsMirror.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams gpsLp = new FrameLayout.LayoutParams(-1, dp(root, 30), Gravity.BOTTOM);
        gpsLp.setMargins(dp(root, 12), 0, dp(root, 12), dp(root, 10));
        speedCard.addView(gpsMirror, gpsLp);
        mirrorGps(gpsMirror, gpsSource);

        LinearLayout.LayoutParams topLp = new LinearLayout.LayoutParams(-1, 0, 1.04f);
        dock.addView(speedCard, topLp);

        if (media == null) media = fallbackMedia(root);
        styleMedia(root, media, compact);
        LinearLayout.LayoutParams mediaLp = new LinearLayout.LayoutParams(-1, 0, .96f);
        mediaLp.setMargins(0, gap, 0, 0);
        dock.addView(media, mediaLp);

        if (guidance != null && guidance.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) guidance.getLayoutParams();
            lp.width = -1;
            lp.height = clamp(Math.round(root.getHeight() * 0.13f), dp(root, 72), dp(root, 94));
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.setMargins(dp(root, compact ? 70 : 78), dp(root, 12), dp(root, 12), 0);
            guidance.setLayoutParams(lp);
        }

        if (metrics != null) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, dp(root, compact ? 54 : 64), Gravity.BOTTOM);
            lp.setMargins(dp(root, compact ? 18 : 38), 0, dp(root, compact ? 18 : 38), dp(root, 12));
            metrics.setLayoutParams(lp);
        }

        if (hazard != null) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    clamp(Math.round(root.getWidth() * .235f), dp(root, 210), dp(root, 330)), -2,
                    Gravity.LEFT | Gravity.BOTTOM);
            lp.setMargins(dp(root, 16), 0, 0, dp(root, compact ? 78 : 92));
            hazard.setLayoutParams(lp);
        }

        for (int i = 0; i < mapPane.getChildCount(); i++) {
            View child = mapPane.getChildAt(i);
            if (child instanceof TextView) child.setVisibility(View.GONE);
        }

        if (recenter != null) {
            root.removeView(recenter);
            FrameLayout.LayoutParams rc = new FrameLayout.LayoutParams(dp(root, 54), dp(root, 54), Gravity.RIGHT | Gravity.BOTTOM);
            rc.setMargins(0, 0, dp(root, 14), dp(root, compact ? 78 : 92));
            mapPane.addView(recenter, rc);
            if (Build.VERSION.SDK_INT >= 21) recenter.setElevation(dp(root, 30));
        }

        Button menu = new Button(root.getContext());
        menu.setText("☰");
        menu.setTextSize(compact ? 18 : 21);
        menu.setTextColor(TEXT);
        menu.setAllCaps(false);
        menu.setGravity(Gravity.CENTER);
        menu.setPadding(0, 0, 0, 0);
        menu.setMinWidth(0);
        menu.setMinimumWidth(0);
        menu.setMinHeight(0);
        menu.setMinimumHeight(0);
        menu.setStateListAnimator(null);
        menu.setContentDescription(MARKER);
        menu.setBackground(round(root, Color.rgb(16, 11, 13), 16, RED));
        menu.setOnClickListener(v -> root.getContext().startActivity(new Intent(root.getContext(), DriveToolsActivity.class)));
        FrameLayout.LayoutParams menuLp = new FrameLayout.LayoutParams(dp(root, compact ? 52 : 58), dp(root, compact ? 52 : 58), Gravity.TOP | Gravity.LEFT);
        menuLp.setMargins(dp(root, 12), dp(root, 12), 0, 0);
        mapPane.addView(menu, menuLp);
        if (Build.VERSION.SDK_INT >= 21) menu.setElevation(dp(root, 32));

        mapPane.bringToFront();
        dock.bringToFront();
        menu.bringToFront();
        root.setBackgroundColor(BG);
    }

    private static void styleMedia(View root, LinearLayout media, boolean compact) {
        media.setPadding(dp(root, compact ? 14 : 18), dp(root, compact ? 13 : 18), dp(root, compact ? 14 : 18), dp(root, compact ? 13 : 18));
        media.setBackground(round(root, PANEL, compact ? 18 : 22, BORDER));
        TextView title = null;
        TextView artist = null;
        for (int i = 0; i < media.getChildCount(); i++) {
            View child = media.getChildAt(i);
            if (child instanceof TextView) {
                TextView tv = (TextView) child;
                String s = String.valueOf(tv.getText());
                if (s.equalsIgnoreCase("MÚSICA OFFLINE")) {
                    tv.setTextColor(RED);
                    tv.setTextSize(compact ? 7.5f : 8.5f);
                } else if (title == null && !s.trim().isEmpty()) {
                    title = tv;
                } else if (artist == null && !s.trim().isEmpty()) {
                    artist = tv;
                }
            }
            if (child instanceof Button) {
                Button b = (Button) child;
                if (String.valueOf(b.getText()).toUpperCase(Locale.ROOT).contains("ABRIR")) b.setVisibility(View.GONE);
            }
        }
        if (title != null) { title.setTextSize(compact ? 14 : 18); title.setTextColor(TEXT); }
        if (artist != null) { artist.setTextSize(compact ? 9 : 10); artist.setTextColor(MUTED); }

        LinearLayout controls = findButtonRow(media);
        if (controls != null) {
            controls.setGravity(Gravity.CENTER);
            for (int i = 0; i < controls.getChildCount(); i++) {
                View child = controls.getChildAt(i);
                if (!(child instanceof Button)) continue;
                Button b = (Button) child;
                LinearLayout.LayoutParams lp = b.getLayoutParams() instanceof LinearLayout.LayoutParams
                        ? (LinearLayout.LayoutParams) b.getLayoutParams()
                        : new LinearLayout.LayoutParams(0, dp(root, 56), 1);
                lp.height = dp(root, compact ? 50 : 60);
                lp.setMargins(i == 0 ? 0 : dp(root, 5), 0, 0, 0);
                b.setLayoutParams(lp);
                b.setTextSize(compact ? 15 : 18);
                b.setTextColor(TEXT);
                boolean play = String.valueOf(b.getText()).contains("▶") && !String.valueOf(b.getText()).contains("|")
                        || String.valueOf(b.getText()).contains("Ⅱ");
                b.setBackground(round(root, play ? RED : Color.rgb(17, 18, 23), 100, play ? Color.rgb(235, 45, 65) : BORDER));
            }
        }
    }

    private static LinearLayout fallbackMedia(View root) {
        LinearLayout media = new LinearLayout(root.getContext());
        media.setOrientation(LinearLayout.VERTICAL);
        media.setGravity(Gravity.CENTER_VERTICAL);
        media.addView(text(root, "MÚSICA OFFLINE", 8, RED, true));
        media.addView(text(root, "Biblioteca offline", 17, TEXT, true));
        media.addView(text(root, "Música local", 10, MUTED, false));
        return media;
    }

    private static void mirrorGps(TextView mirror, TextView source) {
        mirror.post(new Runnable() {
            @Override public void run() {
                if (!mirror.isAttachedToWindow()) return;
                String value = source == null ? "GPS ATIVO" : String.valueOf(source.getText()).trim();
                if (value.isEmpty() || value.equalsIgnoreCase("GPS")) value = "GPS ATIVO";
                mirror.setText("◉  " + value);
                mirror.postDelayed(this, 800L);
            }
        });
    }

    private static FrameLayout findMapPane(FrameLayout root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child instanceof FrameLayout && containsClassNamed(child, "RoadMapView")) return (FrameLayout) child;
        }
        return null;
    }

    private static LinearLayout findOtherMapCard(FrameLayout pane, View... excluded) {
        outer: for (int i = 0; i < pane.getChildCount(); i++) {
            View child = pane.getChildAt(i);
            if (!(child instanceof LinearLayout)) continue;
            for (View x : excluded) if (child == x) continue outer;
            if (countTextViews(child) >= 3) return (LinearLayout) child;
        }
        return null;
    }

    private static int countTextViews(View v) {
        int count = v instanceof TextView ? 1 : 0;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) count += countTextViews(g.getChildAt(i));
        }
        return count;
    }

    private static LinearLayout findDirectLinearWithText(ViewGroup parent, String text) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof LinearLayout && containsText(child, text)) return (LinearLayout) child;
        }
        return null;
    }

    private static LinearLayout findDirectLinearWithAllText(ViewGroup parent, String... values) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (!(child instanceof LinearLayout)) continue;
            boolean ok = true;
            for (String value : values) if (!containsText(child, value)) { ok = false; break; }
            if (ok) return (LinearLayout) child;
        }
        return null;
    }

    private static LinearLayout findDirectLinearWithButton(ViewGroup parent, String text) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof LinearLayout && containsButton(child, text)) return (LinearLayout) child;
        }
        return null;
    }

    private static LinearLayout findLinearWithText(View root, String text) {
        if (root instanceof LinearLayout && containsText(root, text)) return (LinearLayout) root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                LinearLayout result = findLinearWithText(g.getChildAt(i), text);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static LinearLayout findButtonRow(View root) {
        if (root instanceof LinearLayout) {
            LinearLayout l = (LinearLayout) root;
            int buttons = 0;
            for (int i = 0; i < l.getChildCount(); i++) if (l.getChildAt(i) instanceof Button) buttons++;
            if (buttons >= 3) return l;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                LinearLayout result = findButtonRow(g.getChildAt(i));
                if (result != null) return result;
            }
        }
        return null;
    }

    private static Button findDirectButton(ViewGroup parent, String text) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof Button && String.valueOf(((Button) child).getText()).equals(text)) return (Button) child;
        }
        return null;
    }

    private static TextView firstText(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) if (group.getChildAt(i) instanceof TextView) return (TextView) group.getChildAt(i);
        return null;
    }

    private static TextView findTextContaining(View root, String text) {
        if (root instanceof TextView) {
            String value = String.valueOf(((TextView) root).getText());
            if (value.toUpperCase(Locale.ROOT).contains(text.toUpperCase(Locale.ROOT))) return (TextView) root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView result = findTextContaining(g.getChildAt(i), text);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static boolean containsText(View root, String text) { return findTextContaining(root, text) != null; }

    private static boolean containsButton(View root, String text) {
        if (root instanceof Button && String.valueOf(((Button) root).getText()).equalsIgnoreCase(text)) return true;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) if (containsButton(g.getChildAt(i), text)) return true;
        }
        return false;
    }

    private static boolean containsClassNamed(View root, String simpleName) {
        if (root.getClass().getSimpleName().equals(simpleName)) return true;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) if (containsClassNamed(g.getChildAt(i), simpleName)) return true;
        }
        return false;
    }

    private static View findByContentDescription(View root, String description) {
        CharSequence cd = root.getContentDescription();
        if (cd != null && description.contentEquals(cd)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View result = findByContentDescription(g.getChildAt(i), description);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static TextView text(View root, String value, float size, int color, boolean bold) {
        TextView t = new TextView(root.getContext());
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private static GradientDrawable round(View root, int color, int radiusDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(root, radiusDp));
        if (strokeColor != 0) g.setStroke(Math.max(1, dp(root, 1)), strokeColor);
        return g;
    }

    private static int dp(View root, float value) { return Math.round(value * root.getResources().getDisplayMetrics().density); }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    private static final class HorizontalGauge extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextView speedSource;
        private final RectF arc = new RectF();

        HorizontalGauge(Context context, TextView source) {
            super(context);
            speedSource = source;
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float cx = w * .50f;
            float cy = h * .54f;
            float radius = Math.min(w * .40f, h * .43f);
            arc.set(cx - radius, cy - radius, cx + radius, cy + radius);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(Math.max(3f, radius * .035f));
            paint.setColor(Color.rgb(55, 48, 50));
            canvas.drawArc(arc, 150f, 240f, false, paint);

            float speed = readSpeed();
            float progress = Math.max(0f, Math.min(1f, speed / 200f));
            paint.setColor(RED);
            paint.setShadowLayer(Math.max(2f, radius * .045f), 0, 0, Color.argb(125, 235, 18, 45));
            canvas.drawArc(arc, 150f, 240f * progress, false, paint);
            paint.clearShadowLayer();

            paint.setStrokeWidth(Math.max(1.5f, radius * .012f));
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
            for (int value = 0; value <= 200; value += 20) {
                float p = value / 200f;
                double angle = Math.toRadians(150f + 240f * p);
                float outerR = radius * .98f;
                float innerR = value % 40 == 0 ? radius * .86f : radius * .90f;
                float x1 = cx + (float) Math.cos(angle) * innerR;
                float y1 = cy + (float) Math.sin(angle) * innerR;
                float x2 = cx + (float) Math.cos(angle) * outerR;
                float y2 = cy + (float) Math.sin(angle) * outerR;
                paint.setColor(value <= speed ? Color.rgb(245, 239, 226) : Color.rgb(116, 105, 104));
                canvas.drawLine(x1, y1, x2, y2, paint);
                if (value % 40 == 0) {
                    float labelR = radius * .70f;
                    float lx = cx + (float) Math.cos(angle) * labelR;
                    float ly = cy + (float) Math.sin(angle) * labelR + radius * .035f;
                    paint.setStyle(Paint.Style.FILL);
                    paint.setTextSize(Math.max(9f, radius * .105f));
                    canvas.drawText(String.valueOf(value), lx, ly, paint);
                    paint.setStyle(Paint.Style.STROKE);
                }
            }

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1.5f, radius * .012f));
            paint.setColor(Color.argb(110, 196, 17, 38));
            float lineY = h * .79f;
            canvas.drawLine(w * .38f, lineY, w * .62f, lineY, paint);
            postInvalidateDelayed(300L);
        }

        private float readSpeed() {
            if (speedSource == null) return 0f;
            try { return Float.parseFloat(String.valueOf(speedSource.getText()).replace(',', '.').trim()); }
            catch (Throwable ignored) { return 0f; }
        }
    }
}

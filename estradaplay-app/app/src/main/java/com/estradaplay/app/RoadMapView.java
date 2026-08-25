package com.estradaplay.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.mapbox.common.MapboxOptions;
import com.mapbox.geojson.Point;
import com.mapbox.maps.CameraOptions;
import com.mapbox.maps.MapInitOptions;
import com.mapbox.maps.MapView;
import com.mapbox.maps.MapboxMap;
import com.mapbox.maps.ScreenCoordinate;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class RoadMapView extends FrameLayout {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayList<RoadHazard> hazards = new ArrayList<>();
    private final HazardOverlay overlay;
    private final TextView fallback;
    private MapView mapView;
    private MapboxMap mapboxMap;
    private double userLat = Double.NaN;
    private double userLon = Double.NaN;
    private double bearing = 0.0;
    private boolean mapReady;
    private boolean styleFallbackTried;
    private boolean follow = true;
    private String status = "Carregando mapa…";

    RoadMapView(Context context) {
        super(context);
        setBackgroundColor(Color.rgb(5, 8, 12));
        fallback = new TextView(context);
        fallback.setText("Preparando mapa…");
        fallback.setTextColor(Color.rgb(132, 145, 160));
        fallback.setTextSize(12);
        fallback.setGravity(android.view.Gravity.CENTER);
        fallback.setBackgroundColor(Color.rgb(5, 8, 12));
        addView(fallback, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        overlay = new HazardOverlay(context);
        addView(overlay, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        fetchConfig();
    }

    void setFollow(boolean value) {
        follow = value;
        if (value) recenter();
    }

    void recenter() {
        follow = true;
        if (mapboxMap != null && Double.isFinite(userLat) && Double.isFinite(userLon)) {
            setCamera(userLat, userLon, 16.35, 52.0, bearing);
        }
    }

    void setUserLocation(double lat, double lon, double heading) {
        if (!Double.isFinite(lat) || !Double.isFinite(lon)) return;
        userLat = lat;
        userLon = lon;
        if (Double.isFinite(heading) && heading >= 0) bearing = ((heading % 360.0) + 360.0) % 360.0;
        if (follow && mapboxMap != null) setCamera(lat, lon, 16.35, 52.0, bearing);
        overlay.invalidate();
    }

    void setHazards(List<RoadHazard> value) {
        hazards.clear();
        if (value != null) hazards.addAll(value);
        overlay.invalidate();
    }

    String status() { return status; }

    private void fetchConfig() {
        new Thread(() -> {
            try {
                ApiClient api = new ApiClient(getContext());
                ApiClient.Response response = api.get("api/native_app.php?action=mapbox_config&v=140");
                JSONObject json = response.json();
                if (!response.ok() || !json.optBoolean("ok", false) || !json.optBoolean("enabled", true)) {
                    ui.post(() -> setFallback("Mapa indisponível agora\nAlertas offline continuam ativos"));
                    return;
                }
                String token = json.optString("token", "").trim();
                String style = json.optString("style", "mapbox://styles/mapbox/navigation-night-v1").trim();
                if (!token.startsWith("pk.")) {
                    ui.post(() -> setFallback("Token do mapa não configurado\nAlertas offline continuam ativos"));
                    return;
                }
                if (!style.startsWith("mapbox://styles/")) style = "mapbox://styles/mapbox/navigation-night-v1";
                String finalStyle = style;
                ui.post(() -> attachMapbox(token, finalStyle));
            } catch (Throwable e) {
                ui.post(() -> setFallback("Sem conexão para carregar o mapa\nAlertas offline continuam ativos"));
            }
        }, "EstradaPlay-MapConfig").start();
    }

    private void attachMapbox(String token, String styleUri) {
        if (mapView != null) return;
        try {
            MapboxOptions.INSTANCE.setAccessToken(token);
            MapInitOptions options = new MapInitOptions(getContext());
            options.setTextureView(true);
            MapView mv = new MapView(getContext(), options);
            mv.setAlpha(0f);
            addView(mv, 1, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            mapView = mv;
            mapboxMap = mv.getMapboxMap();
            status = "Carregando mapa…";
            mapboxMap.loadStyle(styleUri, style -> activate());

            ui.postDelayed(() -> {
                if (mapReady || mapboxMap == null || styleFallbackTried) return;
                styleFallbackTried = true;
                try { mapboxMap.loadStyle("mapbox://styles/mapbox/dark-v11", style -> activate()); }
                catch (Throwable ignored) {}
            }, 5500L);

            ui.postDelayed(() -> {
                if (!mapReady) setFallback("Mapa sem conexão\nAlertas offline continuam ativos");
            }, 12000L);
        } catch (Throwable e) {
            setFallback("Não consegui iniciar o mapa\nAlertas offline continuam ativos");
        }
    }

    private void activate() {
        if (mapView == null) return;
        mapReady = true;
        status = "Mapa ativo";
        fallback.setVisibility(View.GONE);
        mapView.animate().alpha(1f).setDuration(220L).start();
        if (Double.isFinite(userLat) && Double.isFinite(userLon)) recenter();
        overlay.invalidate();
    }

    private void setFallback(String message) {
        status = message == null ? "Mapa offline" : message.replace('\n', ' ');
        fallback.setText(message == null ? "Mapa offline" : message);
        fallback.setVisibility(View.VISIBLE);
        overlay.invalidate();
    }

    private void setCamera(double lat, double lon, double zoom, double pitch, double direction) {
        try {
            if (mapboxMap == null) return;
            mapboxMap.setCamera(new CameraOptions.Builder()
                    .center(Point.fromLngLat(lon, lat))
                    .zoom(zoom)
                    .pitch(pitch)
                    .bearing(direction)
                    .build());
        } catch (Throwable ignored) {}
    }

    private ScreenCoordinate screen(double lat, double lon) {
        try {
            if (mapboxMap == null || !mapReady) return null;
            ScreenCoordinate s = mapboxMap.pixelForCoordinate(Point.fromLngLat(lon, lat));
            if (!Double.isFinite(s.getX()) || !Double.isFinite(s.getY())) return null;
            return s;
        } catch (Throwable e) { return null; }
    }

    private final class HazardOverlay extends View {
        private final Paint radar = circlePaint(Color.rgb(255, 92, 72));
        private final Paint signal = circlePaint(Color.rgb(81, 165, 255));
        private final Paint bump = circlePaint(Color.rgb(174, 112, 255));
        private final Paint toll = circlePaint(Color.rgb(71, 214, 134));
        private final Paint user = circlePaint(Color.rgb(63, 164, 255));
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arrow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);

        HazardOverlay(Context c) {
            super(c);
            setWillNotDraw(false);
            setClickable(false);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(2.2f));
            ring.setColor(Color.WHITE);
            arrow.setStyle(Paint.Style.FILL);
            arrow.setColor(Color.WHITE);
            label.setColor(Color.rgb(225, 232, 239));
            label.setTextSize(dp(10));
            label.setFakeBoldText(true);
            label.setShadowLayer(dp(2), 0, dp(1), Color.BLACK);
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            if (mapboxMap != null && mapReady) {
                for (RoadHazard h : hazards) {
                    ScreenCoordinate s = screen(h.lat, h.lon);
                    if (s == null) continue;
                    float x = (float)s.getX(), y = (float)s.getY();
                    if (x < -40 || y < -40 || x > getWidth() + 40 || y > getHeight() + 40) continue;
                    Paint p = hazardPaint(h.type);
                    c.drawCircle(x, y, dp(7.0f), p);
                    c.drawCircle(x, y, dp(9.2f), ring);
                    if (h.speed > 0 && "RADAR".equals(h.type)) {
                        String speed = String.valueOf(h.speed);
                        float w = label.measureText(speed);
                        c.drawText(speed, x - w / 2f, y - dp(13), label);
                    }
                }
                if (Double.isFinite(userLat) && Double.isFinite(userLon)) {
                    ScreenCoordinate s = screen(userLat, userLon);
                    if (s != null) drawUser(c, (float)s.getX(), (float)s.getY());
                }
            } else if (Double.isFinite(userLat) && Double.isFinite(userLon)) {
                float x = getWidth() / 2f, y = getHeight() / 2f;
                drawUser(c, x, y);
                drawFallbackHazards(c, x, y);
            }
            postInvalidateDelayed(180L);
        }

        private void drawUser(Canvas c, float x, float y) {
            c.drawCircle(x, y, dp(9), user);
            c.drawCircle(x, y, dp(12), ring);
            double r = Math.toRadians(bearing);
            float tipX = x + (float)Math.sin(r) * dp(21);
            float tipY = y - (float)Math.cos(r) * dp(21);
            float leftX = x + (float)Math.sin(r - 2.55) * dp(8);
            float leftY = y - (float)Math.cos(r - 2.55) * dp(8);
            float rightX = x + (float)Math.sin(r + 2.55) * dp(8);
            float rightY = y - (float)Math.cos(r + 2.55) * dp(8);
            Path path = new Path();
            path.moveTo(tipX, tipY); path.lineTo(leftX, leftY); path.lineTo(rightX, rightY); path.close();
            c.drawPath(path, arrow);
        }

        private void drawFallbackHazards(Canvas c, float cx, float cy) {
            for (RoadHazard h : hazards) {
                double north = (h.lat - userLat) * 110540.0;
                double east = (h.lon - userLon) * 111320.0 * Math.max(0.25, Math.cos(Math.toRadians(userLat)));
                double scale = dp(1) / 22.0;
                float x = cx + (float)(east * scale);
                float y = cy - (float)(north * scale);
                if (x < 0 || y < 0 || x > getWidth() || y > getHeight()) continue;
                c.drawCircle(x, y, dp(6), hazardPaint(h.type));
            }
        }

        private Paint hazardPaint(String type) {
            if ("SEMAFORO".equals(type)) return signal;
            if ("QUEBRA_MOLAS".equals(type)) return bump;
            if ("PEDAGIO".equals(type) || "PASSAGEM_NIVEL".equals(type)) return toll;
            return radar;
        }

        private Paint circlePaint(int color) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            return p;
        }
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
}

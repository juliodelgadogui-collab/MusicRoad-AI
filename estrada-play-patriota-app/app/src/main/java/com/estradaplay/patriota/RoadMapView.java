package com.estradaplay.patriota;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapLibreMapOptions;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.sources.GeoJsonSource;

import java.util.ArrayList;
import java.util.List;

import static org.maplibre.android.style.layers.PropertyFactory.lineColor;
import static org.maplibre.android.style.layers.PropertyFactory.lineOpacity;
import static org.maplibre.android.style.layers.PropertyFactory.lineWidth;

final class RoadMapView extends FrameLayout {
    private static final String OPEN_STYLE = "https://tiles.openfreemap.org/styles/liberty";
    private static final String EMPTY_GEOJSON = "{\"type\":\"FeatureCollection\",\"features\":[]}";
    private static final String LOCAL_STYLE = "{\"version\":8,\"name\":\"EPP Night\",\"sources\":{},\"layers\":[{\"id\":\"background\",\"type\":\"background\",\"paint\":{\"background-color\":\"#080507\"}}]}";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayList<RoadHazard> hazards = new ArrayList<>();
    // ESTRADA_VIVA_MAP_V180
    private final ArrayList<EstradaVivaStore.Event> liveEvents = new ArrayList<>();
    private final ArrayList<ConvoyStore.Member> convoyMembers = new ArrayList<>();
    private final ArrayList<RoadQualityStore.Point> qualityPoints = new ArrayList<>();
    private final HazardOverlay overlay;
    private final TextView fallback;
    private final View nightTint;
    private MapView mapView;
    private MapLibreMap map;
    private Style currentStyle;
    private GeoJsonSource offlineRoadSource;
    private GeoJsonSource routeSource;
    private double userLat = Double.NaN;
    private double userLon = Double.NaN;
    private double bearing = 0.0;
    private boolean mapReady;
    private boolean follow = true;
    private boolean offlineStyle;
    private String offlineRoadGeoJson = EMPTY_GEOJSON;
    private String routeGeoJson = EMPTY_GEOJSON;
    private String status = "Preparando mapa livre…";

    RoadMapView(Context context) {
        super(context);
        setBackgroundColor(Color.rgb(7, 7, 9));
        fallback = new TextView(context);
        fallback.setText("Preparando mapa livre…");
        fallback.setTextColor(Color.rgb(132, 145, 160));
        fallback.setTextSize(12);
        fallback.setGravity(android.view.Gravity.CENTER);
        fallback.setBackgroundColor(Color.rgb(7, 7, 9));
        addView(fallback, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        initMapLibre();
        nightTint = new View(context);
        nightTint.setBackgroundColor(Color.argb(52, 10, 0, 5));
        nightTint.setClickable(false);
        nightTint.setFocusable(false);
        addView(nightTint, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        overlay = new HazardOverlay(context);
        addView(overlay, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    private void initMapLibre() {
        try {
            MapLibre.getInstance(getContext().getApplicationContext());
            MapLibreMapOptions options = new MapLibreMapOptions().textureMode(true);
            MapView mv = new MapView(getContext(), options);
            mv.onCreate(null);
            mv.setAlpha(0f);
            addView(mv, 1, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            mapView = mv;
            mv.getMapAsync(value -> {
                map = value;
                map.addOnCameraMoveListener(() -> overlay.invalidate());
                if (online()) loadOpenMap(); else loadOfflineMap();
            });
        } catch (Throwable e) {
            setFallback("Mapa offline próprio\nAguardando dados da estrada");
        }
    }

    private void loadOpenMap() {
        if (map == null) return;
        mapReady = false;
        offlineStyle = false;
        status = "Carregando MapLibre + OpenStreetMap…";
        fallback.setText("Carregando mapa livre…");
        fallback.setVisibility(View.VISIBLE);
        try {
            map.setStyle(new Style.Builder().fromUri(OPEN_STYLE), style -> {
                currentStyle = style;
                offlineRoadSource = null;
                routeSource = null;
                offlineStyle = false;
                mapReady = true;
                status = "Mapa livre · OpenStreetMap";
                fallback.setVisibility(View.GONE);
                if (mapView != null) mapView.animate().alpha(1f).setDuration(180L).start();
                if (Double.isFinite(userLat) && Double.isFinite(userLon)) recenter();
                installRoute(style);
                overlay.invalidate();
            });
        } catch (Throwable e) {
            loadOfflineMap();
            return;
        }
        ui.postDelayed(() -> {
            if (!mapReady) loadOfflineMap();
        }, 6500L);
    }

    private void loadOfflineMap() {
        if (map == null) return;
        try {
            map.setStyle(new Style.Builder().fromJson(LOCAL_STYLE), style -> {
                currentStyle = style;
                offlineRoadSource = null;
                routeSource = null;
                offlineStyle = true;
                mapReady = true;
                status = "Mapa offline próprio · EstradaPlay";
                fallback.setVisibility(View.GONE);
                installOfflineRoads(style);
                installRoute(style);
                if (mapView != null) mapView.animate().alpha(1f).setDuration(160L).start();
                if (Double.isFinite(userLat) && Double.isFinite(userLon)) recenter();
                overlay.invalidate();
            });
        } catch (Throwable e) {
            setFallback("Mapa offline próprio\nAlertas continuam ativos");
        }
    }

    void setOfflineRoadGeoJson(String geoJson) {
        offlineRoadGeoJson = geoJson == null || geoJson.trim().isEmpty() ? EMPTY_GEOJSON : geoJson;
        if (offlineStyle && currentStyle != null) installOfflineRoads(currentStyle);
    }

    private void installOfflineRoads(Style style) {
        try {
            GeoJsonSource source = offlineRoadSource;
            if (source == null) {
                source = new GeoJsonSource("estradaplay-offline-roads", offlineRoadGeoJson);
                style.addSource(source);
                offlineRoadSource = source;
                LineLayer casing = new LineLayer("estradaplay-road-casing", "estradaplay-offline-roads")
                        .withProperties(lineColor("#21171a"), lineWidth(5.8f), lineOpacity(0.98f));
                LineLayer roads = new LineLayer("estradaplay-roads", "estradaplay-offline-roads")
                        .withProperties(lineColor("#9d918d"), lineWidth(2.7f), lineOpacity(0.90f));
                style.addLayer(casing);
                style.addLayer(roads);
            } else {
                source.setGeoJson(offlineRoadGeoJson);
            }
        } catch (Throwable ignored) {}
    }

    void setRouteGeoJson(String geoJson) {
        routeGeoJson = geoJson == null || geoJson.trim().isEmpty() ? EMPTY_GEOJSON : geoJson;
        if (currentStyle != null) installRoute(currentStyle);
    }

    private void installRoute(Style style) {
        try {
            GeoJsonSource source = routeSource;
            if (source == null) {
                source = new GeoJsonSource("epp-route", routeGeoJson);
                style.addSource(source);
                routeSource = source;
                LineLayer casing = new LineLayer("epp-route-casing", "epp-route")
                        .withProperties(lineColor("#4d0713"), lineWidth(10.4f), lineOpacity(0.96f));
                LineLayer route = new LineLayer("epp-route-line", "epp-route")
                        .withProperties(lineColor("#ff3048"), lineWidth(6.2f), lineOpacity(1.0f));
                style.addLayer(casing);
                style.addLayer(route);
            } else {
                source.setGeoJson(routeGeoJson);
            }
        } catch (Throwable ignored) {}
    }

    void setFollow(boolean value) {
        follow = value;
        if (value) recenter();
    }

    void recenter() {
        follow = true;
        if (map != null && Double.isFinite(userLat) && Double.isFinite(userLon)) setCamera(userLat, userLon, offlineStyle ? 15.0 : 16.35, offlineStyle ? 42.0 : 56.0, bearing);
    }

    void setUserLocation(double lat, double lon, double heading) {
        if (!Double.isFinite(lat) || !Double.isFinite(lon)) return;
        userLat = lat;
        userLon = lon;
        if (Double.isFinite(heading) && heading >= 0) bearing = ((heading % 360.0) + 360.0) % 360.0;
        if (follow && map != null && mapReady) setCamera(lat, lon, offlineStyle ? 15.0 : 16.35, offlineStyle ? 42.0 : 56.0, bearing);
        overlay.invalidate();
    }

    void setRoadQualityPoints(List<RoadQualityStore.Point> value) { qualityPoints.clear(); if(value!=null)qualityPoints.addAll(value); overlay.invalidate(); }

    void setHazards(List<RoadHazard> value) {
        hazards.clear();
        if (value != null) hazards.addAll(value);
        overlay.invalidate();
    }

    void setLiveEvents(List<EstradaVivaStore.Event> value) { liveEvents.clear(); if(value!=null)liveEvents.addAll(value); overlay.invalidate(); }
    void setConvoyMembers(List<ConvoyStore.Member> value) { convoyMembers.clear(); if(value!=null)convoyMembers.addAll(value); overlay.invalidate(); }

    String status() { return status; }

    boolean isOfflineStyle() { return offlineStyle; }

    void onStartMap() { try { if (mapView != null) mapView.onStart(); } catch (Throwable ignored) {} }
    void onResumeMap() { try { if (mapView != null) mapView.onResume(); } catch (Throwable ignored) {} }
    void onPauseMap() { try { if (mapView != null) mapView.onPause(); } catch (Throwable ignored) {} }
    void onStopMap() { try { if (mapView != null) mapView.onStop(); } catch (Throwable ignored) {} }
    void onLowMemoryMap() { try { if (mapView != null) mapView.onLowMemory(); } catch (Throwable ignored) {} }
    void onDestroyMap() { try { if (mapView != null) mapView.onDestroy(); } catch (Throwable ignored) {} }
    void onSaveMap(Bundle out) { try { if (mapView != null) mapView.onSaveInstanceState(out); } catch (Throwable ignored) {} }

    private void setFallback(String message) {
        mapReady = false;
        status = message == null ? "Mapa offline próprio" : message.replace('\n', ' ');
        fallback.setText(message == null ? "Mapa offline próprio" : message);
        fallback.setVisibility(View.VISIBLE);
        overlay.invalidate();
    }

    private void setCamera(double lat, double lon, double zoom, double tilt, double direction) {
        try {
            if (map == null) return;
            CameraPosition position = new CameraPosition.Builder()
                    .target(new LatLng(lat, lon))
                    .zoom(zoom)
                    .tilt(tilt)
                    .bearing(direction)
                    .build();
            map.animateCamera(CameraUpdateFactory.newCameraPosition(position), 320);
        } catch (Throwable ignored) {}
    }

    private PointF screen(double lat, double lon) {
        try {
            if (map == null || !mapReady) return null;
            return map.getProjection().toScreenLocation(new LatLng(lat, lon));
        } catch (Throwable e) { return null; }
    }

    private boolean online() {
        if (DriveSettings.offlineTestMode(getContext())) return false;
        try {
            ConnectivityManager cm = (ConnectivityManager)getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            if (Build.VERSION.SDK_INT >= 23) {
                android.net.Network n = cm.getActiveNetwork(); if (n == null) return false;
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                return c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            }
            android.net.NetworkInfo info = cm.getActiveNetworkInfo(); return info != null && info.isConnected();
        } catch (Throwable e) { return false; }
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
            setFocusable(false);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
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

        @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
            return false;
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent event) {
            return false;
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            if (map != null && mapReady) {
                for (RoadQualityStore.Point q : qualityPoints) { PointF sp=screen(q.lat,q.lon); if(sp==null)continue; int qc=q.score>=75?Color.rgb(72,212,134):(q.score>=50?Color.rgb(226,185,76):Color.rgb(226,55,55)); Paint qp=circlePaint(qc); c.drawCircle(sp.x,sp.y,dp(4.2f),qp); }
                for (RoadHazard h : hazards) {
                    PointF s = screen(h.lat, h.lon);
                    if (s == null) continue;
                    float x = s.x, y = s.y;
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
                // ESTRADA_VIVA_MAP_V180: community reports stay visually distinct from fixed hazards.
                for (EstradaVivaStore.Event e : liveEvents) {
                    PointF ep = screen(e.lat, e.lon); if (ep == null) continue;
                    if (ep.x < -40 || ep.y < -40 || ep.x > getWidth()+40 || ep.y > getHeight()+40) continue;
                    int color = Color.rgb(226,185,76);
                    if ("accident".equals(e.type)) color=Color.rgb(235,55,65);
                    else if ("flooding".equals(e.type)) color=Color.rgb(75,165,255);
                    else if ("animal".equals(e.type)) color=Color.rgb(236,175,65);
                    else if ("construction".equals(e.type)) color=Color.rgb(255,132,55);
                    else if ("traffic".equals(e.type)) color=Color.rgb(190,100,240);
                    else if ("object".equals(e.type)) color=Color.rgb(225,232,239);
                    Paint lp=circlePaint(color);c.drawCircle(ep.x,ep.y,dp(6.4f),lp);c.drawCircle(ep.x,ep.y,dp(9.0f),ring);
                    String tag=e.shortLabel();float tw=label.measureText(tag);c.drawText(tag,ep.x-tw/2f,ep.y-dp(13),label);
                }
                // CONVOY_MAP_V180: live members are blue, self location keeps the regular arrow.
                for (ConvoyStore.Member m : convoyMembers) {
                    if(m.self||!Double.isFinite(m.lat)||!Double.isFinite(m.lon))continue;PointF cp=screen(m.lat,m.lon);if(cp==null)continue;
                    Paint mp=circlePaint(Color.rgb(55,190,225));c.drawCircle(cp.x,cp.y,dp(7.2f),mp);c.drawCircle(cp.x,cp.y,dp(10.0f),ring);
                    String tag=m.shortName();float tw=label.measureText(tag);c.drawText(tag,cp.x-tw/2f,cp.y-dp(14),label);
                }
                if (Double.isFinite(userLat) && Double.isFinite(userLon)) {
                    PointF s = screen(userLat, userLon);
                    if (s != null) drawUser(c, s.x, s.y);
                }
            } else if (Double.isFinite(userLat) && Double.isFinite(userLon)) {
                float x = getWidth() / 2f, y = getHeight() / 2f;
                drawUser(c, x, y);
                drawFallbackHazards(c, x, y);
            }
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

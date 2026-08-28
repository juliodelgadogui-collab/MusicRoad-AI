from pathlib import Path

ROOT = Path('estrada-play-comunista-app')
APP = ROOT / 'app'
JAVA = APP / 'src/main/java/com/estradaplay/comunista'


def read(path):
    return Path(path).read_text(encoding='utf-8')


def write(path, content):
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content, encoding='utf-8')


def must_replace(text, old, new, label):
    if old not in text:
        raise SystemExit(f'v122: missing target: {label}')
    return text.replace(old, new, 1)

# Version.
gradle_path = APP / 'build.gradle'
gradle = read(gradle_path)
gradle = must_replace(gradle, "versionCode 121\n        versionName '1.2.1'", "versionCode 122\n        versionName '1.2.2'", 'version 1.2.2')
write(gradle_path, gradle)

# Normalize camera / bump aliases from server and public map sources.
hazard_path = JAVA / 'RoadHazard.java'
hazard = read(hazard_path)
hazard = must_replace(
    hazard,
    '        String type = o.optString("type", o.optString("tipo", "RADAR"));',
    '        String type = normalizeType(o.optString("type", o.optString("tipo", "RADAR")));',
    'RoadHazard normalize input'
)
hazard = must_replace(
    hazard,
    '                type == null || type.trim().isEmpty() ? "RADAR" : type.trim().toUpperCase(java.util.Locale.ROOT),',
    '                type,',
    'RoadHazard normalized constructor'
)
hazard = must_replace(
    hazard,
    '    String label() {\n        switch (type) {',
    '''    private static String normalizeType(String raw) {
        String key = raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
        key = key.replace('Á','A').replace('À','A').replace('Ã','A').replace('Â','A')
                .replace('É','E').replace('Ê','E').replace('Í','I').replace('Ó','O')
                .replace('Ô','O').replace('Õ','O').replace('Ú','U').replace('Ç','C')
                .replace('-', '_').replace(' ', '_');
        if (key.isEmpty()) return "RADAR";
        if (key.contains("RADAR") || key.contains("SPEED_CAMERA") || key.contains("MAXSPEED") || key.contains("ENFORCEMENT")) return "RADAR";
        if (key.contains("QUEBRA") || key.contains("LOMBADA") || key.contains("SPEED_BUMP") || key.contains("SPEED_HUMP")) return "QUEBRA_MOLAS";
        if (key.contains("SEMAFOR") || key.contains("TRAFFIC_SIGNAL")) return "SEMAFORO";
        if (key.contains("PEDAG") || key.contains("TOLL")) return "PEDAGIO";
        if (key.contains("PASSAGEM_NIVEL") || key.contains("LEVEL_CROSSING")) return "PASSAGEM_NIVEL";
        if (key.contains("CAMERA") || key.contains("CCTV") || key.contains("SURVEILLANCE") || key.contains("MONITORAMENTO")) return "CAMERA_MONITORAMENTO";
        return key;
    }

    String label() {
        switch (type) {''',
    'RoadHazard normalizer helper'
)
hazard = must_replace(
    hazard,
    '            case "PASSAGEM_NIVEL": return "Passagem de nível";\n            default: return "Radar";',
    '            case "PASSAGEM_NIVEL": return "Passagem de nível";\n            case "CAMERA_MONITORAMENTO": return "Câmera de monitoramento";\n            default: return "Radar";',
    'camera label'
)
write(hazard_path, hazard)

# Contextual copilot voice knows monitoring cameras.
copilot_path = JAVA / 'CommunistCopilot.java'
copilot = read(copilot_path)
copilot = must_replace(
    copilot,
    '            case "PASSAGEM_NIVEL":\n                text = "Atenção. Passagem de nível em " + distance + ". Reduza.";\n                break;\n            default:',
    '''            case "PASSAGEM_NIVEL":
                text = "Atenção. Passagem de nível em " + distance + ". Reduza.";
                break;
            case "CAMERA_MONITORAMENTO":
                text = n % 2 == 0
                        ? "Câmera de monitoramento de tráfego à frente, a " + distance + "."
                        : "Atenção à via. Monitoramento de tráfego em " + distance + ".";
                break;
            default:''',
    'copilot camera voice'
)
write(copilot_path, copilot)

# Embedded offline voice fallback also knows camera, so it never calls one a radar.
offline_voice_path = JAVA / 'EstradaPlayOfflineVoice.java'
offline_voice = read(offline_voice_path)
offline_voice = must_replace(
    offline_voice,
    '            case "PASSAGEM_NIVEL":\n                clips.add("ep_atencao");\n                clips.add("ep_passagem_nivel_frente");\n                clips.add(distance);\n                clips.add("ep_reduza");\n                break;\n            default:',
    '''            case "PASSAGEM_NIVEL":
                clips.add("ep_atencao");
                clips.add("ep_passagem_nivel_frente");
                clips.add(distance);
                clips.add("ep_reduza");
                break;
            case "CAMERA_MONITORAMENTO":
                clips.add("ep_atencao");
                clips.add("ep_camera_monitoramento");
                clips.add(distance);
                break;
            default:''',
    'offline camera voice'
)
write(offline_voice_path, offline_voice)

voice_gen_path = ROOT / 'tools/generate_voice_bank.py'
voice_gen = read(voice_gen_path)
voice_gen = must_replace(
    voice_gen,
    '        "ep_passagem_nivel_frente": "Passagem de nível à frente.",\n        "ep_reduza": "Reduza.",',
    '        "ep_passagem_nivel_frente": "Passagem de nível à frente.",\n        "ep_camera_monitoramento": "Câmera de monitoramento de tráfego à frente.",\n        "ep_reduza": "Reduza.",',
    'voice bank camera phrase'
)
write(voice_gen_path, voice_gen)

# Add selective public traffic monitoring cameras to the offline safety packs.
store_path = JAVA / 'RoadPackStore.java'
store = read(store_path)
store = must_replace(
    store,
    '''        if (isSupportedUf(uf)) {
            // Always try the same state package MusicRoad uses. If unavailable,
            // keep the EstradaPlay state endpoint as a second source.
            boolean stateOk = fetchMusicRoadStateRadars(api, uf);
            if (!stateOk) stateOk = fetchStateCoverage(api, uf);
            ok = stateOk || ok;
        }
        return ok;''',
    '''        if (isSupportedUf(uf)) {
            // Always try the same state package MusicRoad uses. If unavailable,
            // keep the EstradaPlay state endpoint as a second source.
            boolean stateOk = fetchMusicRoadStateRadars(api, uf);
            if (!stateOk) stateOk = fetchStateCoverage(api, uf);
            ok = stateOk || ok;
        }
        // CAMERA_MONITORAMENTO_V122: additive, public map data only. We intentionally
        // select cameras tagged for traffic/road monitoring and ignore generic private CCTV.
        ok = fetchOpenStreetMapTrafficCameras(lat, lon) || ok;
        return ok;''',
    'camera fetch hook'
)
method = r'''
    // CAMERA_MONITORAMENTO_V122: public traffic-monitoring cameras only.
    private boolean fetchOpenStreetMapTrafficCameras(double lat, double lon) {
        double centerLat = Math.round(lat / GRID_DEG) * GRID_DEG;
        double centerLon = Math.round(lon / GRID_DEG) * GRID_DEG;
        String key = "osmcamera_" + packKey(centerLat, centerLon);
        synchronized (this) {
            Pack existing = findByKey(key);
            if (existing != null && System.currentTimeMillis() - existing.fetchedAt <= TILE_FRESH_MS) return true;
        }
        try {
            String query = String.format(Locale.US,
                    "[out:json][timeout:28];(" +
                    "node(around:50000,%.6f,%.6f)[\"man_made\"=\"surveillance\"][\"surveillance\"=\"traffic\"];" +
                    "node(around:50000,%.6f,%.6f)[\"man_made\"=\"surveillance\"][\"surveillance:zone\"=\"traffic\"];" +
                    "node(around:50000,%.6f,%.6f)[\"camera:type\"=\"traffic\"];" +
                    ");out tags;",
                    lat, lon, lat, lon, lat, lon);
            String target = "https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(query, "UTF-8");
            JSONObject root = directHttpJson(target, 8_000_000);
            JSONArray elements = root.optJSONArray("elements");
            if (elements == null) return false;

            JSONArray hazards = new JSONArray();
            for (int i = 0; i < elements.length(); i++) {
                JSONObject e = elements.optJSONObject(i); if (e == null) continue;
                double rlat = e.optDouble("lat", Double.NaN), rlon = e.optDouble("lon", Double.NaN);
                if (!Double.isFinite(rlat) || !Double.isFinite(rlon)) continue;
                JSONObject tags = e.optJSONObject("tags"); if (tags == null) tags = new JSONObject();
                JSONObject h = new JSONObject();
                h.put("id", "osm-traffic-camera-" + e.optLong("id", i));
                h.put("type", "CAMERA_MONITORAMENTO");
                h.put("lat", rlat); h.put("lon", rlon);
                h.put("road", tags.optString("ref", tags.optString("name", "")));
                h.put("speed", 0);
                String direction = tags.optString("direction", tags.optString("camera:direction", ""));
                try { h.put("heading", Double.parseDouble(direction.trim())); } catch (Throwable ignored) {}
                h.put("source", "OpenStreetMap traffic monitoring");
                hazards.put(h);
            }

            JSONObject stored = new JSONObject();
            stored.put("key", key); stored.put("kind", "tile");
            stored.put("center_lat", centerLat); stored.put("center_lon", centerLon);
            stored.put("radius_m", 50000); stored.put("fetched_at", System.currentTimeMillis());
            stored.put("source_ok", true); stored.put("hazards", hazards);
            JSONObject coverage = new JSONObject();
            coverage.put("source", "OpenStreetMap monitoramento de tráfego");
            coverage.put("total", hazards.length());
            stored.put("coverage", coverage);
            return savePack(key, stored);
        } catch (Throwable ignored) { return false; }
    }

'''
store = must_replace(store, '    private static int parseOsmSpeed(String raw) {', method + '    private static int parseOsmSpeed(String raw) {', 'camera fetch method')
write(store_path, store)

# Safety engine: don't let already-alerted points suppress the next one; emergency
# bump matching; camera monitoring range; camera fallback voice.
service_path = JAVA / 'RoadSafetyService.java'
service = read(service_path)
service = must_replace(
    service,
    '''        RoadHazard best = null;
        double bestForward = Double.MAX_VALUE;
        double bestDistance = Double.MAX_VALUE;

        if (Float.isFinite(heading) && speedKmh >= 4.0) {
            for (RoadHazard h : nearby) {
                Match m = match(loc.getLatitude(), loc.getLongitude(), heading, speedKmh, h);
                if (!m.valid) continue;
                if (m.forwardM < bestForward) {
                    best = h;
                    bestForward = m.forwardM;
                    bestDistance = m.distanceM;
                }
            }
        }

        if (best != null && shouldAlert(best)) {''',
    '''        RoadHazard best = null;
        double bestForward = Double.MAX_VALUE;
        double bestDistance = Double.MAX_VALUE;
        double bestScore = Double.MAX_VALUE;

        if (Float.isFinite(heading) && speedKmh >= 3.0) {
            for (RoadHazard h : nearby) {
                if (!shouldAlert(h)) continue;
                Match m = match(loc.getLatitude(), loc.getLongitude(), heading, speedKmh, h);
                if (!m.valid) continue;
                double score = m.forwardM + hazardPriorityBias(h.type);
                if (score < bestScore) {
                    best = h;
                    bestForward = m.forwardM;
                    bestDistance = m.distanceM;
                    bestScore = score;
                }
            }
        }

        if (best != null) {''',
    'next hazard selection'
)
service = must_replace(
    service,
    '        if (forward <= 12.0) return Match.no();',
    '''        // If a bump enters the local pack very late, still warn while it is almost
        // under the car. Other point types retain the normal forward safety gate.
        if ("QUEBRA_MOLAS".equals(h.type)) {
            if (forward < -12.0) return Match.no();
        } else if (forward <= 12.0) return Match.no();''',
    'late bump emergency gate'
)
service = must_replace(
    service,
    '''            case "QUEBRA_MOLAS":
                maxDistance = speedKmh >= 55 ? 360 : 260; maxLateral = 50; minSpeed = 10; break;
            case "PEDAGIO":''',
    '''            case "QUEBRA_MOLAS":
                maxDistance = speedKmh >= 55 ? 430 : 300; maxLateral = 55; minSpeed = 3; break;
            case "CAMERA_MONITORAMENTO":
                maxDistance = speedKmh >= 80 ? 650 : 450; maxLateral = 75; minSpeed = 8; break;
            case "PEDAGIO":''',
    'bump camera ranges'
)
service = must_replace(
    service,
    '    private boolean shouldAlert(RoadHazard h) {',
    '''    private double hazardPriorityBias(String type) {
        if ("QUEBRA_MOLAS".equals(type)) return -220.0;
        if ("RADAR".equals(type)) return -160.0;
        if ("PASSAGEM_NIVEL".equals(type)) return -130.0;
        if ("SEMAFORO".equals(type)) return -90.0;
        if ("CAMERA_MONITORAMENTO".equals(type)) return -35.0;
        return 0.0;
    }

    private boolean shouldAlert(RoadHazard h) {''',
    'hazard priority helper'
)
service = must_replace(
    service,
    '''            case "PASSAGEM_NIVEL":
                return "Atenção. Passagem de nível à frente. " + distance + ". Reduza.";
            default:''',
    '''            case "PASSAGEM_NIVEL":
                return "Atenção. Passagem de nível à frente. " + distance + ". Reduza.";
            case "CAMERA_MONITORAMENTO":
                return "Atenção. Câmera de monitoramento de tráfego à frente. " + distance + ".";
            default:''',
    'camera fallback voice'
)
write(service_path, service)

# Native in-app central overlay. No image assets; all shapes/text are Android views.
overlay = r'''package com.estradaplay.comunista;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** Central driving warning shown over whichever main cockpit is visible. */
final class SafetyAlertOverlay {
    private static final String TAG = "epc-central-safety-alert";
    private static String lastId = "";
    private static long lastAt;

    private SafetyAlertOverlay() {}

    static void show(Context context, FrameLayout host, Intent intent) {
        if (context == null || host == null || intent == null) return;
        String type = normalize(intent.getStringExtra("hazard_type"));
        if (!("RADAR".equals(type) || "QUEBRA_MOLAS".equals(type) || "CAMERA_MONITORAMENTO".equals(type))) return;

        String id = safe(intent.getStringExtra("hazard_id"));
        if (id.isEmpty()) id = type + ":" + Math.round(intent.getDoubleExtra("distance_m", 0));
        long now = System.currentTimeMillis();
        if (id.equals(lastId) && now - lastAt < 12000L) return;
        lastId = id;
        lastAt = now;

        View old = host.findViewWithTag(TAG);
        if (old != null) host.removeView(old);

        int accent = "QUEBRA_MOLAS".equals(type) ? Color.rgb(234, 145, 34)
                : ("CAMERA_MONITORAMENTO".equals(type) ? Color.rgb(217, 190, 93) : Color.rgb(208, 24, 45));
        int ink = Color.rgb(249, 241, 226);
        int muted = Color.rgb(191, 171, 164);
        int panel = Color.rgb(24, 8, 12);

        FrameLayout overlay = new FrameLayout(context);
        overlay.setTag(TAG);
        overlay.setBackgroundColor(Color.argb(132, 0, 0, 0));
        overlay.setClickable(true);
        overlay.setFocusable(false);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(context, 24), dp(context, 20), dp(context, 24), dp(context, 18));
        card.setBackground(round(panel, 24, accent, 2));
        if (android.os.Build.VERSION.SDK_INT >= 21) card.setElevation(dp(context, 36));

        TextView overline = text(context,
                "RADAR".equals(type) ? "ALERTA DE FISCALIZAÇÃO"
                        : ("QUEBRA_MOLAS".equals(type) ? "ATENÇÃO NA VIA" : "MONITORAMENTO DE TRÁFEGO"),
                10, accent, true);
        overline.setLetterSpacing(0.13f);
        overline.setGravity(Gravity.CENTER);
        card.addView(overline);

        String titleValue = "RADAR".equals(type) ? "RADAR À FRENTE"
                : ("QUEBRA_MOLAS".equals(type) ? "QUEBRA-MOLAS À FRENTE" : "CÂMERA À FRENTE");
        TextView title = text(context, titleValue, 23, ink, true);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.setMargins(0, dp(context, 8), 0, dp(context, 12));
        card.addView(title, tp);

        int limit = intent.getIntExtra("radar_limit_kmh", intent.getIntExtra("limit_kmh", 0));
        if ("RADAR".equals(type)) {
            TextView sign = text(context, limit > 0 ? String.valueOf(limit) : "?", limit > 0 ? 46 : 42, Color.rgb(18, 18, 18), true);
            sign.setGravity(Gravity.CENTER);
            sign.setBackground(round(Color.WHITE, 100, accent, 7));
            card.addView(sign, new LinearLayout.LayoutParams(dp(context, 104), dp(context, 104)));
            TextView limitText = text(context, limit > 0 ? "KM/H  ·  LIMITE DO RADAR" : "LIMITE DO RADAR NÃO INFORMADO", 11, ink, true);
            limitText.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(context, 9), 0, 0);
            card.addView(limitText, lp);
        } else {
            TextView action = text(context, "QUEBRA_MOLAS".equals(type) ? "REDUZA" : "ATENÇÃO", 30, accent, true);
            action.setLetterSpacing(0.08f);
            action.setGravity(Gravity.CENTER);
            card.addView(action);
        }

        double distance = intent.getDoubleExtra("distance_m", 0);
        String road = safe(intent.getStringExtra("road"));
        StringBuilder detail = new StringBuilder();
        if (distance > 0) detail.append(distance >= 1000
                ? String.format(Locale.getDefault(), "%.1f km", distance / 1000.0)
                : Math.max(10, Math.round(distance / 10.0) * 10) + " m");
        if (!road.isEmpty()) {
            if (detail.length() > 0) detail.append("  ·  ");
            detail.append(road);
        }
        if (detail.length() == 0) detail.append("Ponto detectado à frente");
        TextView info = text(context, detail.toString(), 13, muted, false);
        info.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, -2);
        ip.setMargins(0, dp(context, 12), 0, 0);
        card.addView(info, ip);

        TextView close = text(context, "TOQUE PARA FECHAR", 9, muted, true);
        close.setLetterSpacing(0.12f);
        close.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.setMargins(0, dp(context, 13), 0, 0);
        card.addView(close, cp);

        int screenW = context.getResources().getDisplayMetrics().widthPixels;
        int max = dp(context, "RADAR".equals(type) ? 390 : 420);
        int width = Math.min(max, Math.max(dp(context, 280), screenW - dp(context, 34)));
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(width, -2, Gravity.CENTER);
        overlay.addView(card, cardParams);
        host.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(140L).start();
        overlay.setOnClickListener(v -> dismiss(host, overlay));
        long visibleMs = "QUEBRA_MOLAS".equals(type) ? 5600L : 5000L;
        overlay.postDelayed(() -> dismiss(host, overlay), visibleMs);
    }

    private static void dismiss(FrameLayout host, View overlay) {
        if (host == null || overlay == null || overlay.getParent() == null) return;
        overlay.animate().alpha(0f).setDuration(160L).withEndAction(() -> {
            try { if (overlay.getParent() == host) host.removeView(overlay); } catch (Throwable ignored) {}
        }).start();
    }

    private static String normalize(String raw) {
        String t = safe(raw).toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (t.contains("QUEBRA") || t.contains("LOMBADA") || t.contains("BUMP") || t.contains("HUMP")) return "QUEBRA_MOLAS";
        if (t.contains("CAMERA") || t.contains("CÂMERA") || t.contains("MONITOR") || t.contains("CCTV") || t.contains("SURVEILLANCE")) return "CAMERA_MONITORAMENTO";
        if (t.contains("RADAR") || t.contains("SPEED_CAMERA") || t.contains("ENFORCEMENT")) return "RADAR";
        return t;
    }

    private static TextView text(Context c, String value, float size, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setLineSpacing(0, 1.04f);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private static GradientDrawable round(int color, int radius, int stroke, int strokeWidth) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dpRaw(radius));
        if (stroke != 0 && strokeWidth > 0) d.setStroke(dpRaw(strokeWidth), stroke);
        return d;
    }

    private static float density = 1f;
    private static int dpRaw(float value) { return Math.round(value * density); }
    private static int dp(Context c, float value) {
        density = c.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
    private static String safe(String s) { return s == null ? "" : s.trim(); }
}
'''
write(JAVA / 'SafetyAlertOverlay.java', overlay)

# Hook the central overlay into every long-lived app cockpit.
main_path = JAVA / 'MainActivity.java'
main = read(main_path)
main = must_replace(
    main,
    '''            int packs = intent.getIntExtra("pack_count", 0);
            int points = intent.getIntExtra("hazard_count", 0);''',
    '''            int packs = intent.getIntExtra("pack_count", 0);
            int points = intent.getIntExtra("hazard_count", 0);
            SafetyAlertOverlay.show(MainActivity.this, root, intent);''',
    'MainActivity safety overlay hook'
)
write(main_path, main)

road_map_path = JAVA / 'RoadMapActivity.java'
road_map = read(road_map_path)
road_map = must_replace(
    road_map,
    '''            int limit = intent.getIntExtra("limit_kmh", 0);

            if (Double.isFinite(lat) && Double.isFinite(lon)) {''',
    '''            int limit = intent.getIntExtra("limit_kmh", 0);
            SafetyAlertOverlay.show(RoadMapActivity.this, root, intent);

            if (Double.isFinite(lat) && Double.isFinite(lon)) {''',
    'RoadMapActivity safety overlay hook'
)
write(road_map_path, road_map)

auto_path = JAVA / 'AutomotiveActivity.java'
auto = read(auto_path)
auto = must_replace(
    auto,
    '''            int limit = intent.getIntExtra("limit_kmh", 0);

            if (Double.isFinite(lat) && Double.isFinite(lon)) {''',
    '''            int limit = intent.getIntExtra("limit_kmh", 0);
            SafetyAlertOverlay.show(AutomotiveActivity.this, root, intent);

            if (Double.isFinite(lat) && Double.isFinite(lon)) {''',
    'AutomotiveActivity safety overlay hook'
)
write(auto_path, auto)

print('Estrada Play Comunista 1.2.2: central radar/bump/camera alerts ready')

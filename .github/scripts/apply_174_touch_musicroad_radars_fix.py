from pathlib import Path
import re

repo = Path(__file__).resolve().parents[2]
app = repo / 'estradaplay-app'

# -----------------------------------------------------------------------------
# 1) HARD TOUCH BRIDGE
# -----------------------------------------------------------------------------
# Real automotive Android panels may expose touch through vendor/compatibility
# layers that make Activity-level hit testing unreliable. 1.7.4 puts a normal
# Android View over the cockpit. It receives the exact same local coordinate
# space as the layout and only consumes events that start over a real control.
# Taps outside controls fall through to MapLibre, preserving map gestures.
p = app / 'app/src/main/java/com/estradaplay/app/RoadMapActivity.java'
s = p.read_text(encoding='utf-8')

if 'TOUCH_BRIDGE_V174' not in s:
    field = '    private View routedTouchTarget;\n'
    if field not in s:
        raise SystemExit('1.7.4 touch field anchor not found')
    s = s.replace(field, field + '    private TouchBridgeView touchBridge;\n', 1)

    # Stop the older Activity interceptors from consuming/losing events. The
    # dedicated overlay below becomes the only compatibility bridge.
    pattern = re.compile(
        r'    @Override public boolean dispatchTouchEvent\(android\.view\.MotionEvent event\) \{.*?\n'
        r'    @Override public boolean dispatchGenericMotionEvent\(android\.view\.MotionEvent event\) \{.*?\n'
        r'    \}\n',
        re.S,
    )
    replacement = '''    // TOUCH_BRIDGE_V174: keep normal Android dispatch intact.\n'
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {\n'
        return super.dispatchTouchEvent(event);\n'
    }\n\n'
    @Override public boolean dispatchGenericMotionEvent(android.view.MotionEvent event) {\n'
        return super.dispatchGenericMotionEvent(event);\n'
    }\n'''.replace("'\n", "\n")
    s, n = pattern.subn(replacement, s, count=1)
    if n != 1:
        raise SystemExit('1.7.4 old dispatch block not found')

    # Add a top-most touch bridge after the actual cockpit layout has been built.
    anchor = '        shell.addView(right, new LinearLayout.LayoutParams(rightWidth, -1));\n    }\n\n    private LinearLayout buildRail'
    if anchor not in s:
        raise SystemExit('1.7.4 cockpit end anchor not found')
    s = s.replace(
        anchor,
        '''        shell.addView(right, new LinearLayout.LayoutParams(rightWidth, -1));\n\n'
        // TOUCH_BRIDGE_V174: a full-window local-coordinate input bridge.\n'
        touchBridge = new TouchBridgeView(this);\n'
        root.addView(touchBridge, new FrameLayout.LayoutParams(-1, -1));\n'
    }\n\n'
    private LinearLayout buildRail'''.replace("'\n", "\n"),
        1,
    )

    # Slightly enlarge target bounds for resistive / low precision panels.
    s = s.replace('        int slop = dp(7);', '        int slop = dp(12);', 1)

    # Insert bridge before map-data refresh method. It clicks on ACTION_DOWN on
    # purpose: some head units never deliver a matching ACTION_UP to the same
    # logical window after a compatibility transform.
    marker = '    private void refreshMapData(double lat, double lon, int reportedCount) {'
    if marker not in s:
        raise SystemExit('1.7.4 refreshMapData marker not found')
    bridge = r'''    private final class TouchBridgeView extends View {
        private View active;

        TouchBridgeView(Context context) {
            super(context);
            setBackgroundColor(Color.TRANSPARENT);
            setClickable(true);
            setFocusable(false);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent event) {
            if (event == null) return false;
            int action = event.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                active = findTouchTarget(event.getX(), event.getY());
                if (active == null) return false;
                active.setPressed(true);
                // Automotive compatibility mode: activate immediately. This avoids
                // vendor ROMs that move ACTION_UP to another virtual coordinate space.
                active.performClick();
                return true;
            }
            if (active != null) {
                if (action == android.view.MotionEvent.ACTION_UP ||
                        action == android.view.MotionEvent.ACTION_CANCEL) {
                    active.setPressed(false);
                    active = null;
                }
                return true;
            }
            return false;
        }

        @Override public boolean onGenericMotionEvent(android.view.MotionEvent event) {
            if (event == null) return false;
            int action = event.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_BUTTON_PRESS ||
                    action == android.view.MotionEvent.ACTION_UP) {
                View target = findTouchTarget(event.getX(), event.getY());
                if (target != null) {
                    target.performClick();
                    return true;
                }
            }
            return false;
        }
    }

'''
    s = s.replace(marker, bridge + marker, 1)

p.write_text(s, encoding='utf-8')

# -----------------------------------------------------------------------------
# 2) ACCEPT MUSICROAD RADAR JSON DIRECTLY
# -----------------------------------------------------------------------------
p = app / 'app/src/main/java/com/estradaplay/app/RoadHazard.java'
s = p.read_text(encoding='utf-8')
old = '''        double lat = o.optDouble("lat", Double.NaN);
        double lon = o.optDouble("lon", Double.NaN);
        if (!Double.isFinite(lat) || !Double.isFinite(lon)) return null;
        return new RoadHazard(
                o.optString("id", ""),
                o.optString("type", "RADAR"),
                lat,
                lon,
                o.optString("road", ""),
                o.optInt("speed", 0),
                o.isNull("heading") ? Double.NaN : o.optDouble("heading", Double.NaN),
                o.optString("source", "")
        );
'''
new = '''        // MUSICROAD_RADARS_V174: accept both EstradaPlay and MusicRoad schemas.
        double lat = o.has("lat") ? o.optDouble("lat", Double.NaN) : o.optDouble("latitude", Double.NaN);
        double lon = o.has("lon") ? o.optDouble("lon", Double.NaN) : o.optDouble("longitude", Double.NaN);
        if (!Double.isFinite(lat) || !Double.isFinite(lon)) return null;
        String id = o.optString("id", o.optString("external_id", ""));
        String type = o.optString("type", o.optString("tipo", "RADAR"));
        String road = o.optString("road", o.optString("rodovia", ""));
        int speed = o.has("speed") ? o.optInt("speed", 0) : o.optInt("velocidade", 0);
        String source = o.optString("source", o.optString("fonte", "MusicRoad"));
        return new RoadHazard(
                id,
                type == null || type.trim().isEmpty() ? "RADAR" : type.trim().toUpperCase(java.util.Locale.ROOT),
                lat,
                lon,
                road,
                speed,
                o.isNull("heading") ? Double.NaN : o.optDouble("heading", Double.NaN),
                source
        );
'''
if old not in s:
    raise SystemExit('1.7.4 RoadHazard parser anchor not found')
s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')

# -----------------------------------------------------------------------------
# 3) USE THE SAME STATE RADAR FEED AS MUSICROAD
# -----------------------------------------------------------------------------
p = app / 'app/src/main/java/com/estradaplay/app/RoadPackStore.java'
s = p.read_text(encoding='utf-8')

if 'import java.net.URLEncoder;' not in s:
    s = s.replace('import java.nio.charset.StandardCharsets;\n', 'import java.nio.charset.StandardCharsets;\nimport java.net.URLEncoder;\n', 1)

# Do not block all preparation just because the in-memory cookie is temporarily
# empty; RoadSafetyService now self-heals the device session and endpoints are
# allowed to return their own auth status.
s = s.replace(
    '        if (api == null || api.cookie() == null || api.cookie().trim().isEmpty()) return false;\n        boolean ok = fetchCoverage(api, lat, lon);',
    '        if (api == null) return false;\n        boolean ok = fetchCoverage(api, lat, lon);',
    1,
)
s = s.replace(
    '        if (api == null || api.cookie() == null || api.cookie().trim().isEmpty()) return false;\n        if (hasFreshCoreCoverage(lat, lon)) return true;',
    '        if (api == null) return false;\n        if (hasFreshCoreCoverage(lat, lon)) return true;',
    1,
)

old = '''        String uf = resolveUf(lat, lon);
        if (isSupportedUf(uf)) ok = fetchStateCoverage(api, uf) || ok;
        return ok;
'''
new = '''        String uf = resolveUf(lat, lon);
        if (isSupportedUf(uf)) {
            // MUSICROAD_RADARS_V174: first try the exact state-radar feed used by
            // MusicRoad. This keeps both apps on the same radar database.
            boolean musicRoad = fetchMusicRoadStateRadars(api, uf);
            if (!musicRoad) musicRoad = fetchStateCoverage(api, uf);
            ok = musicRoad || ok;
        }
        return ok;
'''
if old not in s:
    raise SystemExit('1.7.4 prepareTravelReserve anchor not found')
s = s.replace(old, new, 1)

# When the local road_pack endpoint is unavailable, immediately fall back to the
# MusicRoad state feed instead of leaving the cockpit with zero radar points.
old = '''            JSONArray hazards = json.optJSONArray("hazards");
            if (!response.ok() || !json.optBoolean("ok", false) || hazards == null) return false;
'''
new = '''            JSONArray hazards = json.optJSONArray("hazards");
            if (!response.ok() || !json.optBoolean("ok", false) || hazards == null) {
                String uf = resolveUf(lat, lon);
                return isSupportedUf(uf) && fetchMusicRoadStateRadars(api, uf);
            }
'''
if old not in s:
    raise SystemExit('1.7.4 fetchCoverage response anchor not found')
s = s.replace(old, new, 1)

old = '''        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean fetchCorridor'''
new = '''        } catch (Exception ignored) {
            String uf = resolveUf(lat, lon);
            return isSupportedUf(uf) && fetchMusicRoadStateRadars(api, uf);
        }
    }

    private boolean fetchCorridor'''
if old not in s:
    raise SystemExit('1.7.4 fetchCoverage catch anchor not found')
s = s.replace(old, new, 1)

# Insert the MusicRoad state-feed loader before the existing EstradaPlay state
# endpoint. offline_state.php is the endpoint the MusicRoad 2.x app already uses
# for its "BAIXAR RADARES" state package.
marker = '    private boolean fetchStateCoverage(ApiClient api,String uf){\n'
if marker not in s:
    raise SystemExit('1.7.4 state loader marker not found')
loader = r'''    private boolean fetchMusicRoadStateRadars(ApiClient api, String uf) {
        if (api == null || !isSupportedUf(uf)) return false;
        synchronized (this) {
            if (hasFreshStateWithHazardsLocked(uf)) return true;
        }
        String[] info = musicRoadStateInfo(uf);
        if (info == null) return false;
        try {
            String path = "api/offline_state.php?kind=radars&state_id=" + info[0] +
                    "&uf=" + URLEncoder.encode(uf, "UTF-8") +
                    "&state=" + URLEncoder.encode(info[1], "UTF-8");
            ApiClient.Response response = api.getLong(path);
            JSONObject json = response.json();
            JSONArray radars = json.optJSONArray("radars");
            if (!response.ok() || !json.optBoolean("ok", false) || radars == null || radars.length() == 0) return false;

            // Save raw MusicRoad rows. RoadHazard.fromJson() accepts its native
            // latitude/longitude/tipo/velocidade/fonte schema in 1.7.4.
            double[] g = stateGeometry(uf);
            String key = "state_" + uf.toLowerCase(Locale.ROOT);
            JSONObject stored = new JSONObject();
            stored.put("key", key);
            stored.put("kind", "state");
            stored.put("uf", uf);
            stored.put("center_lat", g[0]);
            stored.put("center_lon", g[1]);
            stored.put("radius_m", (int)g[2]);
            stored.put("fetched_at", System.currentTimeMillis());
            stored.put("source_ok", true);
            stored.put("hazards", radars);
            JSONObject coverage = new JSONObject();
            coverage.put("source", "MusicRoad offline_state");
            coverage.put("total", radars.length());
            coverage.put("musicRoad", true);
            stored.put("coverage", coverage);
            return savePack(key, stored);
        } catch (Exception ignored) {
            return false;
        }
    }

    private synchronized boolean hasFreshStateWithHazardsLocked(String uf) {
        long now = System.currentTimeMillis();
        for (Pack p : packs) {
            if (!"state".equals(p.kind) || !uf.equals(p.uf)) continue;
            if (now - p.fetchedAt > freshMs(p)) continue;
            if (p.hazards != null && !p.hazards.isEmpty()) return true;
        }
        return false;
    }

    private static String[] musicRoadStateInfo(String uf) {
        if ("SP".equals(uf)) return new String[]{"35", "São Paulo"};
        if ("MG".equals(uf)) return new String[]{"31", "Minas Gerais"};
        if ("ES".equals(uf)) return new String[]{"32", "Espírito Santo"};
        if ("RJ".equals(uf)) return new String[]{"33", "Rio de Janeiro"};
        return null;
    }

'''
s = s.replace(marker, loader + marker, 1)
p.write_text(s, encoding='utf-8')

# -----------------------------------------------------------------------------
# 4) SELF-HEAL THE SERVER SESSION BEFORE FETCHING RADARS
# -----------------------------------------------------------------------------
p = app / 'app/src/main/java/com/estradaplay/app/RoadSafetyService.java'
s = p.read_text(encoding='utf-8')
if 'import org.json.JSONObject;' not in s:
    s = s.replace('import android.speech.tts.UtteranceProgressListener;\n', 'import android.speech.tts.UtteranceProgressListener;\n\nimport org.json.JSONObject;\n', 1)

s = s.replace('        if (api.cookie() == null || api.cookie().trim().isEmpty()) return;\n', '', 1)
old = '''            try {
                if (alertNeeds) packs.prepareTravelReserve(api, lat, lon, heading);
                if (mapNeeds) mapRoads.prepare(api, lat, lon, heading);
'''
new = '''            try {
                ensureApiSession();
                if (alertNeeds) packs.prepareTravelReserve(api, lat, lon, heading);
                if (mapNeeds) mapRoads.prepare(api, lat, lon, heading);
'''
if old not in s:
    raise SystemExit('1.7.4 ensureCoverage worker anchor not found')
s = s.replace(old, new, 1)

marker = '    private Match match(double lat, double lon, float heading, double speedKmh, RoadHazard h) {'
if marker not in s:
    raise SystemExit('1.7.4 match marker not found')
method = r'''    private void ensureApiSession() {
        if (api == null) return;
        String cookie = api.cookie();
        if (cookie != null && !cookie.trim().isEmpty()) return;
        try {
            JSONObject d = new JSONObject();
            d.put("device_token", DeviceIdentity.token(this));
            d.put("device_label", DeviceIdentity.label());
            d.put("app_version", BuildConfig.VERSION_NAME);
            api.post("api/native_app.php?action=device_login", d);
        } catch (Throwable ignored) {}
    }

'''
s = s.replace(marker, method + marker, 1)
p.write_text(s, encoding='utf-8')

# -----------------------------------------------------------------------------
# 5) VERSION
# -----------------------------------------------------------------------------
gradle = app / 'app/build.gradle'
s = gradle.read_text(encoding='utf-8')
s = s.replace('versionCode 15', 'versionCode 16')
s = s.replace("versionName '1.7.3'", "versionName '1.7.4'")
if "versionName '1.7.4'" not in s or 'versionCode 16' not in s:
    raise SystemExit('1.7.4 version bump failed')
gradle.write_text(s, encoding='utf-8')

print('EstradaPlay 1.7.4 hard touch bridge + MusicRoad radar source patch applied')

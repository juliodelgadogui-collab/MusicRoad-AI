from pathlib import Path
import re

repo = Path(__file__).resolve().parents[2]
app = repo / 'estradaplay-app'

# -----------------------------------------------------------------------------
# 1) DIRECT NATIVE TOUCH — no full-screen overlay, no coordinate hit testing.
# -----------------------------------------------------------------------------
p = app / 'app/src/main/java/com/estradaplay/app/RoadMapActivity.java'
s = p.read_text(encoding='utf-8')

# Let Android perform normal dispatch. The previous compatibility hit-test can
# consume events on vendor ROMs when their virtual coordinate space is unusual.
pattern = re.compile(
    r'    @Override public boolean dispatchTouchEvent\(android\.view\.MotionEvent event\) \{.*?\n'
    r'    \}\n\n'
    r'    @Override public boolean dispatchGenericMotionEvent\(android\.view\.MotionEvent event\) \{.*?\n'
    r'    \}\n',
    re.S,
)
replacement = '''    // TOUCH_DIRECT_V174: preserve Android's native View dispatch.\n'
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {\n'
        return super.dispatchTouchEvent(event);\n'
    }\n\n'
    @Override public boolean dispatchGenericMotionEvent(android.view.MotionEvent event) {\n'
        return super.dispatchGenericMotionEvent(event);\n'
    }\n'''.replace("'\n", "\n")
s, n = pattern.subn(replacement, s, count=1)
if n != 1:
    raise SystemExit('1.7.4 direct dispatch block not found')

old = '''    private void registerTouchTarget(View view) {
        if (view == null) return;
        view.setClickable(true);
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        if (Build.VERSION.SDK_INT >= 21) view.setElevation(dp(10));
        touchTargets.add(view);
    }
'''
new = '''    private void registerTouchTarget(View view) {
        if (view == null) return;
        view.setClickable(true);
        view.setLongClickable(false);
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        if (Build.VERSION.SDK_INT >= 21) view.setElevation(dp(24));
        touchTargets.add(view);

        // TOUCH_DIRECT_V174: activate on DOWN. Several in-dash Android ROMs send
        // DOWN correctly but lose/move UP after their compatibility transform.
        view.setOnTouchListener((v, event) -> {
            if (event == null) return false;
            int action = event.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                v.setPressed(true);
                v.performClick();
                return true;
            }
            if (action == android.view.MotionEvent.ACTION_MOVE) return true;
            if (action == android.view.MotionEvent.ACTION_UP ||
                    action == android.view.MotionEvent.ACTION_CANCEL) {
                v.setPressed(false);
                return true;
            }
            return false;
        });

        // Some multimedia panels expose the touchscreen as mouse/stylus input.
        view.setOnGenericMotionListener((v, event) -> {
            if (event == null) return false;
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_BUTTON_PRESS) {
                v.performClick();
                return true;
            }
            return false;
        });
    }
'''
if old not in s:
    raise SystemExit('1.7.4 registerTouchTarget anchor not found')
s = s.replace(old, new, 1)

# Keep the window explicitly touchable/focusable.
if 'FLAG_NOT_TOUCHABLE' not in s:
    window_anchor = '        getWindow().setNavigationBarColor(BG);\n'
    if window_anchor not in s:
        raise SystemExit('1.7.4 window anchor not found')
    s = s.replace(window_anchor, window_anchor + '''        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
''', 1)

p.write_text(s, encoding='utf-8')

# -----------------------------------------------------------------------------
# 2) Accept both EstradaPlay and MusicRoad radar JSON schemas.
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
new = '''        // MUSICROAD_RADARS_V174: accept both schemas without conversion server-side.
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
# 3) Use the same state radar feed already used by MusicRoad.
# -----------------------------------------------------------------------------
p = app / 'app/src/main/java/com/estradaplay/app/RoadPackStore.java'
s = p.read_text(encoding='utf-8')
if 'import java.net.URLEncoder;' not in s:
    s = s.replace('import java.nio.charset.StandardCharsets;\n', 'import java.nio.charset.StandardCharsets;\nimport java.net.URLEncoder;\n', 1)

# Remove cookie-only short-circuit. The service repairs the device session first.
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
            // Always try the same state package MusicRoad uses. If unavailable,
            // keep the EstradaPlay state endpoint as a second source.
            boolean stateOk = fetchMusicRoadStateRadars(api, uf);
            if (!stateOk) stateOk = fetchStateCoverage(api, uf);
            ok = stateOk || ok;
        }
        return ok;
'''
if old not in s:
    raise SystemExit('1.7.4 prepareTravelReserve anchor not found')
s = s.replace(old, new, 1)

# This is the post-1.5.2 shape (coverage/sourceOk already introduced there).
old = '''            JSONObject json = response.json();
            JSONArray hazards = json.optJSONArray("hazards");
            JSONObject coverage = json.optJSONObject("coverage");
            boolean sourceOk = coverage == null || coverage.optBoolean("osm_ok", true);
            if (!response.ok() || !json.optBoolean("ok", false) || hazards == null) return false;
            if (hazards.length() == 0 && !sourceOk) return false;
            JSONObject stored = new JSONObject();
'''
new = '''            JSONObject json = response.json();
            JSONArray hazards = json.optJSONArray("hazards");
            JSONObject coverage = json.optJSONObject("coverage");
            boolean sourceOk = coverage == null || coverage.optBoolean("osm_ok", true);
            if (!response.ok() || !json.optBoolean("ok", false) || hazards == null || hazards.length() == 0) {
                String uf = resolveUf(lat, lon);
                return isSupportedUf(uf) && fetchMusicRoadStateRadars(api, uf);
            }
            JSONObject stored = new JSONObject();
'''
if old not in s:
    raise SystemExit('1.7.4 post-1.5.2 fetchCoverage anchor not found')
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
# 4) Repair the native device session before radar downloads.
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
        } catch (Exception ignored) {}
    }

'''
s = s.replace(marker, method + marker, 1)
p.write_text(s, encoding='utf-8')

# Version bump after 1.7.3.
gradle = app / 'app/build.gradle'
s = gradle.read_text(encoding='utf-8')
s = s.replace('versionCode 15', 'versionCode 16')
s = s.replace("versionName '1.7.3'", "versionName '1.7.4'")
if "versionName '1.7.4'" not in s or 'versionCode 16' not in s:
    raise SystemExit('1.7.4 version bump failed')
gradle.write_text(s, encoding='utf-8')

print('EstradaPlay 1.7.4 direct touch + MusicRoad radar bridge applied')

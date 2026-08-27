from pathlib import Path

repo = Path(__file__).resolve().parents[2]
app = repo / 'estradaplay-app'

# EstradaPlay 2.0.0 — final fixes before the source is snapshotted to
# estradaplay-app-v2/.  From 2.0 onward the generated source is the source of
# truth; this script is bootstrap-only.

# -----------------------------------------------------------------------------
# 1) Cockpit sizing: use the actual content root, not full display metrics.
#    Automotive ROMs often report display width including the system nav strip,
#    which pushed CENTRALIZAR into the right panel.
# -----------------------------------------------------------------------------
p = app / 'app/src/main/java/com/estradaplay/app/RoadMapActivity.java'
s = p.read_text(encoding='utf-8')
old = '''        int[] size = screenSize();
        int width = size[0];
        int height = size[1];
        lastWidth = width;
        lastHeight = height;
'''
new = '''        int[] size = screenSize();
        int width = root != null && root.getWidth() > 0 ? root.getWidth() : size[0];
        int height = root != null && root.getHeight() > 0 ? root.getHeight() : size[1];
        lastWidth = width;
        lastHeight = height;
'''
if old not in s:
    raise SystemExit('2.0 cockpit content-size anchor not found')
s = s.replace(old, new, 1)

old = '''        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h);
        p.leftMargin = Math.max(mapLeft + dp(8), mapRight - w - dp(14));
        p.topMargin = Math.max(mapTop + dp(76), mapTop + ((mapBottom - mapTop - h) / 2));
        root.addView(recenter, p);
'''
new = '''        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h);
        int safeLeft = mapLeft + dp(12);
        int safeRight = Math.max(safeLeft + w, mapRight - dp(22));
        p.leftMargin = Math.max(safeLeft, safeRight - w);
        p.topMargin = Math.max(mapTop + dp(76), mapTop + ((mapBottom - mapTop - h) / 2));
        root.addView(recenter, p);
'''
if old not in s:
    raise SystemExit('2.0 recenter placement anchor not found')
s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')

# -----------------------------------------------------------------------------
# 2) Music setup: the Activity must not mark setup complete when every download
#    failed. DownloadService was fixed in 1.7.7, but this receiver still did it.
# -----------------------------------------------------------------------------
p = app / 'app/src/main/java/com/estradaplay/app/MainActivity.java'
s = p.read_text(encoding='utf-8')
old = '''            if (!active) {
                if (downloadInitialFlow) {
                    library.setSetupDone(true);
                    ui.postDelayed(() -> { downloadInitialFlow = false; showHome(); }, 900);
                } else ui.postDelayed(MainActivity.this::showMusic, 650);
            }
'''
new = '''            if (!active) {
                if (downloadInitialFlow) {
                    boolean hasOfflineMusic = !library.downloadedTracks().isEmpty();
                    library.setSetupDone(hasOfflineMusic);
                    if (hasOfflineMusic) {
                        ui.postDelayed(() -> { downloadInitialFlow = false; showHome(); }, 900);
                    } else {
                        downloadInitialFlow = false;
                        ui.postDelayed(() -> {
                            toast("Nenhuma música foi baixada. Escolha novamente quando estiver online.");
                            if (online()) loadCatalogAndOpenChooser(true); else showOfflineSetupBlocked();
                        }, 650);
                    }
                } else ui.postDelayed(MainActivity.this::showMusic, 650);
            }
'''
if old not in s:
    raise SystemExit('2.0 MainActivity download completion anchor not found')
s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')

# -----------------------------------------------------------------------------
# 3) Speed: start in stationary-safe mode. A head unit may boot the Location
#    provider with an old non-zero speed. We require real displacement to leave
#    stationary mode; this makes a parked vehicle display 0 immediately.
# -----------------------------------------------------------------------------
p = app / 'app/src/main/java/com/estradaplay/app/RoadSafetyService.java'
s = p.read_text(encoding='utf-8')
old = '''        if (motionAnchor == null) {
            motionAnchor = new Location(loc);
            motionAnchorWallMs = nowWall;
            stationaryConfirmed = false;
        } else {
'''
new = '''        if (motionAnchor == null) {
            motionAnchor = new Location(loc);
            motionAnchorWallMs = nowWall;
            // Fail safe: an automotive receiver can expose a stale speed on its
            // first fix. Only actual displacement is allowed to leave this state.
            stationaryConfirmed = true;
        } else {
'''
if old not in s:
    raise SystemExit('2.0 stationary-first anchor not found')
s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')

# -----------------------------------------------------------------------------
# 4) Radar fallback: when the MusicRoad API/session is unavailable, read nearby
#    speed cameras directly from OpenStreetMap Overpass and persist them in the
#    same offline RoadPackStore. Server remains the preferred source.
# -----------------------------------------------------------------------------
p = app / 'app/src/main/java/com/estradaplay/app/RoadPackStore.java'
s = p.read_text(encoding='utf-8')
if 'import java.net.HttpURLConnection;' not in s:
    s = s.replace('import java.nio.charset.StandardCharsets;\n',
                  'import java.nio.charset.StandardCharsets;\nimport java.net.HttpURLConnection;\nimport java.net.URL;\nimport java.net.URLEncoder;\nimport java.io.InputStream;\n', 1)

old = '''            JSONArray radars = json.optJSONArray("radars");
            if (!response.ok() || !json.optBoolean("ok", false) || radars == null || radars.length() == 0) return false;

            double centerLat = Math.round(lat / GRID_DEG) * GRID_DEG;
'''
new = '''            JSONArray radars = json.optJSONArray("radars");
            if (!response.ok() || !json.optBoolean("ok", false) || radars == null || radars.length() == 0)
                return fetchOpenStreetMapNearRadars(lat, lon);

            double centerLat = Math.round(lat / GRID_DEG) * GRID_DEG;
'''
if old not in s:
    raise SystemExit('2.0 MusicRoad radar response anchor not found')
s = s.replace(old, new, 1)

old = '''        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean fetchMusicRoadStateRadars'''
new = '''        } catch (Exception ignored) {
            return fetchOpenStreetMapNearRadars(lat, lon);
        }
    }

    // OSM_DIRECT_RADARS_V200 — read-only fallback, no account/session required.
    private boolean fetchOpenStreetMapNearRadars(double lat, double lon) {
        try {
            String query = String.format(Locale.US,
                    "[out:json][timeout:28];(node(around:50000,%.6f,%.6f)[\\\"highway\\\"=\\\"speed_camera\\\"];node(around:50000,%.6f,%.6f)[\\\"enforcement\\\"=\\\"maxspeed\\\"];);out tags;",
                    lat, lon, lat, lon);
            String target = "https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(query, "UTF-8");
            JSONObject root = directHttpJson(target, 12_000_000);
            JSONArray elements = root.optJSONArray("elements");
            if (elements == null || elements.length() == 0) return false;

            JSONArray hazards = new JSONArray();
            for (int i = 0; i < elements.length(); i++) {
                JSONObject e = elements.optJSONObject(i); if (e == null) continue;
                double rlat = e.optDouble("lat", Double.NaN), rlon = e.optDouble("lon", Double.NaN);
                if (!Double.isFinite(rlat) || !Double.isFinite(rlon)) continue;
                JSONObject tags = e.optJSONObject("tags"); if (tags == null) tags = new JSONObject();
                JSONObject h = new JSONObject();
                h.put("id", "osm-node-" + e.optLong("id", i));
                h.put("type", "RADAR");
                h.put("lat", rlat); h.put("lon", rlon);
                h.put("road", tags.optString("ref", tags.optString("name", "")));
                int speed = parseOsmSpeed(tags.optString("maxspeed", ""));
                h.put("speed", speed);
                String direction = tags.optString("direction", tags.optString("camera:direction", ""));
                try { h.put("heading", Double.parseDouble(direction.trim())); } catch (Throwable ignored) {}
                h.put("source", "OpenStreetMap");
                hazards.put(h);
            }
            if (hazards.length() == 0) return false;

            double centerLat = Math.round(lat / GRID_DEG) * GRID_DEG;
            double centerLon = Math.round(lon / GRID_DEG) * GRID_DEG;
            String key = "osmnear_" + packKey(centerLat, centerLon);
            JSONObject stored = new JSONObject();
            stored.put("key", key); stored.put("kind", "tile");
            stored.put("center_lat", centerLat); stored.put("center_lon", centerLon);
            stored.put("radius_m", 50000); stored.put("fetched_at", System.currentTimeMillis());
            stored.put("source_ok", true); stored.put("hazards", hazards);
            JSONObject coverage = new JSONObject();
            coverage.put("source", "OpenStreetMap direto"); coverage.put("total", hazards.length());
            stored.put("coverage", coverage);
            return savePack(key, stored);
        } catch (Throwable ignored) { return false; }
    }

    private static int parseOsmSpeed(String raw) {
        if (raw == null) return 0;
        String t = raw.trim().toLowerCase(Locale.ROOT);
        String digits = t.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return 0;
        try {
            int v = Integer.parseInt(digits);
            if (t.contains("mph")) v = (int)Math.round(v * 1.609344);
            return v >= 10 && v <= 180 ? v : 0;
        } catch (Throwable ignored) { return 0; }
    }

    private static JSONObject directHttpJson(String target, int maxBytes) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(target).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(50000); c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "EstradaPlay/2.0 Android");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) { c.disconnect(); throw new Exception("HTTP " + code); }
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[32768]; int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > maxBytes) throw new Exception("Resposta OSM grande demais");
            }
            c.disconnect();
            return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    private boolean fetchMusicRoadStateRadars'''
if old not in s:
    raise SystemExit('2.0 MusicRoad radar catch anchor not found')
s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')

# -----------------------------------------------------------------------------
# 5) Offline road fallback: server packages can still provide state + 250 km
#    corridor. If unavailable, cache a useful local road network directly from
#    OSM so the app does not remain forever on "aguardando pacote offline".
# -----------------------------------------------------------------------------
p = app / 'app/src/main/java/com/estradaplay/app/OfflineRoadStore.java'
s = p.read_text(encoding='utf-8')
if 'import java.net.HttpURLConnection;' not in s:
    s = s.replace('import java.nio.charset.StandardCharsets;\n',
                  'import java.nio.charset.StandardCharsets;\nimport java.net.HttpURLConnection;\nimport java.net.URL;\nimport java.net.URLEncoder;\nimport java.io.InputStream;\n', 1)

old = '''    boolean needsPreparation(double lat, double lon, float heading) {
        String uf = guessUfFast(lat, lon);
        if (supported(uf) && !fresh(stateFile(uf), STATE_FRESH_MS)) return true;
        return Float.isFinite(heading) && !hasFreshCorridor(lat, lon, heading);
    }

    boolean prepare(ApiClient api, double lat, double lon, float heading) {
        if (api == null || api.cookie() == null || api.cookie().trim().isEmpty()) return false;
        boolean ok = false;
        String uf = guessUfFast(lat, lon);
        if (supported(uf) && !fresh(stateFile(uf), STATE_FRESH_MS)) ok = fetchState(api, uf) || ok;
        if (Float.isFinite(heading) && !hasFreshCorridor(lat, lon, heading)) ok = fetchCorridor(api, lat, lon, heading) || ok;
        cleanup();
        return ok;
    }
'''
new = '''    boolean needsPreparation(double lat, double lon, float heading) {
        String uf = guessUfFast(lat, lon);
        if (supported(uf) && !fresh(stateFile(uf), STATE_FRESH_MS) && !hasRecentCorridorNear(lat, lon)) return true;
        return Float.isFinite(heading) && !hasFreshCorridor(lat, lon, heading);
    }

    boolean prepare(ApiClient api, double lat, double lon, float heading) {
        boolean ok = false;
        String uf = guessUfFast(lat, lon);
        if (api != null && supported(uf) && !fresh(stateFile(uf), STATE_FRESH_MS)) ok = fetchState(api, uf) || ok;
        if (api != null && Float.isFinite(heading) && !hasFreshCorridor(lat, lon, heading)) ok = fetchCorridor(api, lat, lon, heading) || ok;

        boolean needsLocal = !hasRecentCorridorNear(lat, lon) ||
                (Float.isFinite(heading) && !hasFreshCorridor(lat, lon, heading));
        if (needsLocal) ok = fetchDirectLocalMap(lat, lon, heading) || ok;
        cleanup();
        return ok;
    }
'''
if old not in s:
    raise SystemExit('2.0 OfflineRoadStore preparation anchor not found')
s = s.replace(old, new, 1)

marker = '    private boolean hasFreshCorridor(double lat, double lon, float heading) {\n'
if marker not in s:
    raise SystemExit('2.0 offline map insertion marker not found')
method = r'''    // OSM_DIRECT_MAP_V200: local fallback (~30 km) when server offline packs
    // are unavailable. It is intentionally smaller than the server 250 km pack.
    private boolean fetchDirectLocalMap(double lat, double lon, float heading) {
        try {
            String query = String.format(Locale.US,
                    "[out:json][timeout:35];way(around:30000,%.6f,%.6f)[\"highway\"~\"motorway|trunk|primary|secondary|tertiary\"];out geom tags;",
                    lat, lon);
            String target = "https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(query, "UTF-8");
            HttpURLConnection c = (HttpURLConnection)new URL(target).openConnection();
            c.setConnectTimeout(15000); c.setReadTimeout(60000); c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "EstradaPlay/2.0 Android");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) { c.disconnect(); return false; }
            JSONObject osm;
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[32768]; int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > 22_000_000) throw new Exception("Mapa OSM grande demais");
                }
                osm = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            } finally { c.disconnect(); }

            JSONArray elements = osm.optJSONArray("elements");
            if (elements == null || elements.length() == 0) return false;
            JSONArray features = new JSONArray();
            for (int i = 0; i < elements.length() && features.length() < 7000; i++) {
                JSONObject e = elements.optJSONObject(i); if (e == null) continue;
                JSONArray geom = e.optJSONArray("geometry"); if (geom == null || geom.length() < 2) continue;
                JSONArray coords = new JSONArray();
                for (int j = 0; j < geom.length(); j++) {
                    JSONObject g = geom.optJSONObject(j); if (g == null) continue;
                    double glat = g.optDouble("lat", Double.NaN), glon = g.optDouble("lon", Double.NaN);
                    if (!Double.isFinite(glat) || !Double.isFinite(glon)) continue;
                    JSONArray xy = new JSONArray(); xy.put(glon); xy.put(glat); coords.put(xy);
                }
                if (coords.length() < 2) continue;
                JSONObject tags = e.optJSONObject("tags"); if (tags == null) tags = new JSONObject();
                JSONObject feature = new JSONObject();
                feature.put("type", "Feature"); feature.put("id", "osm-way-" + e.optLong("id", i));
                JSONObject geometry = new JSONObject(); geometry.put("type", "LineString"); geometry.put("coordinates", coords);
                feature.put("geometry", geometry);
                JSONObject props = new JSONObject();
                props.put("highway", tags.optString("highway", "road"));
                props.put("name", tags.optString("name", "")); props.put("ref", tags.optString("ref", ""));
                feature.put("properties", props); features.put(feature);
            }
            if (features.length() == 0) return false;

            JSONObject root = new JSONObject();
            root.put("ok", true); root.put("kind", "local_map"); root.put("fetched_at", System.currentTimeMillis());
            JSONObject start = new JSONObject(); start.put("lat", lat); start.put("lon", lon);
            start.put("heading", Float.isFinite(heading) ? heading : 0.0); root.put("start", start);
            JSONObject roads = new JSONObject(); roads.put("type", "FeatureCollection"); roads.put("features", features); root.put("roads", roads);
            JSONObject coverage = new JSONObject(); coverage.put("source", "OpenStreetMap direto"); coverage.put("features", features.length());
            root.put("coverage", coverage);
            String key = corridorKey(lat, lon, Float.isFinite(heading) ? heading : 0f);
            return writeJson(new File(dir, key + ".json"), root);
        } catch (Throwable ignored) { return false; }
    }

'''
s = s.replace(marker, method + marker, 1)
p.write_text(s, encoding='utf-8')

# -----------------------------------------------------------------------------
# 6) Version 2.0.0. Use a clear code jump so Android always treats this as newer
#    than the 1.7.x test builds.
# -----------------------------------------------------------------------------
gradle = app / 'app/build.gradle'
s = gradle.read_text(encoding='utf-8')
s = s.replace('versionCode 20', 'versionCode 200')
s = s.replace("versionName '1.7.8'", "versionName '2.0.0'")
if "versionName '2.0.0'" not in s or 'versionCode 200' not in s:
    raise SystemExit('2.0 version bump failed')
gradle.write_text(s, encoding='utf-8')

print('EstradaPlay 2.0.0 final reliability fixes applied')

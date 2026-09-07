from pathlib import Path
import re

ROOT = Path('.')
APP = ROOT / 'estrada-play-comunista-app/app/src/main/java/com/estradaplay/comunista'


def read(path):
    return path.read_text(encoding='utf-8')


def write(path, text):
    path.write_text(text, encoding='utf-8')


def must_replace(path, old, new, label):
    text = read(path)
    if old not in text:
        raise SystemExit(f'missing {label}: {path}')
    write(path, text.replace(old, new, 1))


def must_regex(path, pattern, replacement, label, flags=re.S):
    text = read(path)
    new, n = re.subn(pattern, replacement, text, count=1, flags=flags)
    if n != 1:
        raise SystemExit(f'missing/ambiguous {label}: {path} ({n})')
    write(path, new)

# 1) Native cockpit: RoadMapActivity owns the only visible road UI.
p = APP / 'RoadMapActivity.java'
new_builder = r'''    private void buildResponsiveUi() {
        int[] size = screenSize();
        int width = root != null && root.getWidth() > 0 ? root.getWidth() : size[0];
        int height = root != null && root.getHeight() > 0 ? root.getHeight() : size[1];
        lastWidth = width; lastHeight = height;
        if (roadMap != null) try { roadMap.onPauseMap(); roadMap.onStopMap(); roadMap.onDestroyMap(); } catch (Throwable ignored) {}
        root.removeAllViews();
        touchTargets.clear();
        boolean portraitLayout = usePortraitLayout();
        applyAutomotiveSystemUiV205(!portraitLayout);

        RoadCockpitUiV400.Bindings b = RoadCockpitUiV400.build(this, root, width, height, portraitLayout);
        roadMap = b.map;
        speedText = b.speedText;
        hazardTitle = b.hazardTitle;
        hazardDetail = b.hazardDetail;
        protectionText = b.protectionText;
        clockText = b.clockText;
        gpsText = b.gpsText;
        destinationText = b.destinationText;
        navInstructionText = b.navInstructionText;
        navLimitText = b.navLimitText;
        navWeatherText = b.navWeatherText;
        navDistanceText = b.navDistanceText;
        navRoadText = b.navRoadText;
        navEtaText = b.navEtaText;
        navRemainingText = b.navRemainingText;
        navDurationText = b.navDurationText;
        navTurnText = b.navTurnText;
        mapPlayerTitle = b.mapPlayerTitle;
        mapPlayerArtist = b.mapPlayerArtist;
        mapPlayerToggle = b.mapPlayerToggle;
        hazardCard = b.hazardCard;

        root.postDelayed(() -> EpcMotion.fadeIn(root), 35L);
    }

'''
must_regex(p, r'    private void buildResponsiveUi\(\) \{.*?\n    private void buildLandscapeUi', new_builder + '    private void buildLandscapeUi', 'RoadMapActivity buildResponsiveUi')

must_replace(
    p,
    '@Override public void onConfigurationChanged(Configuration newConfig){super.onConfigurationChanged(newConfig);if(root!=null)root.post(this::buildResponsiveUi);}',
    '@Override public void onConfigurationChanged(Configuration newConfig){super.onConfigurationChanged(newConfig);if(root!=null)root.post(()->{buildResponsiveUi();if(roadMap!=null){try{roadMap.onStartMap();}catch(Throwable ignored){}try{roadMap.onResumeMap();}catch(Throwable ignored){}}if(Double.isFinite(lastLat)&&Double.isFinite(lastLon)&&roadMap!=null){roadMap.setUserLocation(lastLat,lastLon,lastHeading);refreshMapData(lastLat,lastLon,0);refreshDestinationRoute(lastLat,lastLon);}});}',
    'RoadMapActivity configuration lifecycle')

# 2) Remove runtime UI patch layers; keep typed native cockpit + telemetry.
p = APP / 'EstradaPlayApplication.java'
text = read(p)
text = re.sub(r'\n\s*// ROAD_REFERENCE_UI_V360:.*?RoadMapReferenceUiV360\.install\(this\);\n', '\n', text, flags=re.S)
text = re.sub(r'\n\s*// PRODUCTION_ROAD_GUARD_V400:.*?RoadProductionGuardV400\.install\(this\); \} catch \(Throwable ignored\) \{\}\n', '\n', text, flags=re.S)
needle = '        UiVersionLabelFix.register(this);\n'
if needle not in text:
    raise SystemExit('Application anchor missing')
text = text.replace(needle, needle + '        try { ProductionTelemetryV400.install(this); } catch (Throwable ignored) {}\n', 1)
write(p, text)

# 3) Real dark map style, not Liberty + heavy tint.
p = APP / 'RoadMapView.java'
must_replace(p, 'private static final String OPEN_STYLE = "https://tiles.openfreemap.org/styles/liberty";', 'private static final String OPEN_STYLE = "https://tiles.openfreemap.org/styles/dark";', 'dark map style')
must_replace(p, 'nightTint.setBackgroundColor(Color.argb(52, 10, 0, 5));', 'nightTint.setBackgroundColor(Color.argb(18, 8, 0, 5));', 'dark map tint')

# 4) Production routing: public OSRM is debug-only; production uses server or cached prepared route.
p = APP / 'RouteEngine.java'
old = '''        try {
            Route fallbackRoute = fetchOsrmFallback(fromLat, fromLon, toLat, toLon);
            cacheResolved(context, fromLat, fromLon, toLat, toLon, fallbackRoute);
            return fallbackRoute;
        } catch (Exception fallback) {
            if (cached != null) return cached;
            if (serverError != null) fallback.addSuppressed(serverError);
            throw fallback;
        }
'''
new = '''        if (cached != null) return cached;
        if (BuildConfig.DEBUG) {
            try {
                Route fallbackRoute = fetchOsrmFallback(fromLat, fromLon, toLat, toLon);
                cacheResolved(context, fromLat, fromLon, toLat, toLon, fallbackRoute);
                return fallbackRoute;
            } catch (Exception fallback) {
                if (serverError != null) fallback.addSuppressed(serverError);
                throw fallback;
            }
        }
        throw serverError != null ? serverError : new Exception("Servidor de rota indisponível e nenhuma rota offline preparada");
'''
must_replace(p, old, new, 'RouteEngine production fallback')

# 5) Overpass public calls leave the normal driving path. Server packs/corridors remain authoritative.
p = APP / 'RoadPackStore.java'
old = '''        if(isSupportedUf(uf)){
            if(fetchMusicRoadStateRadars(api,uf))return true;
            if(fetchStateCoverage(api,uf))return true;
        }
        return fetchOpenStreetMapSafetyNear(lat,lon);
'''
new = '''        if(isSupportedUf(uf)){
            if(fetchMusicRoadStateRadars(api,uf))return true;
            if(fetchStateCoverage(api,uf))return true;
        }
        // Production: public Overpass is an ingestion source for the server, not a dependency of a trip.
        return false;
'''
must_replace(p, old, new, 'RoadPackStore public fallback removal')

p = APP / 'OfflineRoadStore.java'
old = '''        boolean needsLocal = !hasRecentCorridorNear(lat, lon) ||
                (Float.isFinite(heading) && !hasFreshCorridor(lat, lon, heading));
        if (needsLocal) ok = fetchDirectLocalMap(lat, lon, heading) || ok;
        cleanup();
        return ok;
'''
new = '''        // Production downloads road geometry from Estrada Play. Public Overpass remains only as
        // legacy code and is not called automatically while the driver is travelling.
        cleanup();
        return ok;
'''
must_replace(p, old, new, 'OfflineRoadStore public fallback removal')

# 6) Credential hardening: access token has no plaintext/base64 recovery mirror; refresh can recover session.
p = APP / 'SecureDeviceCredential.java'
text = read(p)
text = text.replace('''        String value = load(KEY_ACCESS);
        if (!validToken(value)) {
            value = loadRecoveryToken(KEY_ACCESS_RECOVERY);
            if (validToken(value)) save(KEY_ACCESS, value);
        }
        return validToken(value) ? value : "";
''', '''        String value = load(KEY_ACCESS);
        return validToken(value) ? value : "";
''')
text = text.replace('''                .putString(KEY_ACCESS_RECOVERY, encodeRecoveryToken(a))
                .putString(KEY_REFRESH_RECOVERY, encodeRecoveryToken(r))
''', '''                .remove(KEY_ACCESS_RECOVERY)
                .putString(KEY_REFRESH_RECOVERY, encodeRecoveryToken(r))
''')
write(p, text)

# 7) Weather server-first. Direct Open-Meteo remains fallback only.
p = APP / 'RoadWeatherMonitor.java'
text = read(p)
text = text.replace('Forecast f = fetchSingle(lat, lon, LOCAL_HOURS);', 'Forecast f = fetchSingle(app, lat, lon, LOCAL_HOURS);')
text = text.replace('ArrayList<Forecast> forecasts = fetchMany(samples, ROUTE_HOURS);', 'ArrayList<Forecast> forecasts = fetchMany(c.getApplicationContext(), samples, ROUTE_HOURS);')
text = text.replace('private static Forecast fetchSingle(double lat, double lon, int hours) throws Exception {', 'private static Forecast fetchSingle(Context c, double lat, double lon, int hours) throws Exception {')
text = text.replace('ArrayList<Forecast> result = fetchMany(one, hours);', 'ArrayList<Forecast> result = fetchMany(c, one, hours);')
text = text.replace('private static ArrayList<Forecast> fetchMany(ArrayList<Sample> samples, int hours) throws Exception {', 'private static ArrayList<Forecast> fetchMany(Context c, ArrayList<Sample> samples, int hours) throws Exception {')
anchor = '''        StringBuilder lats = new StringBuilder(), lons = new StringBuilder();
        for (Sample s : samples) {
'''
insert = '''        // Server is the primary weather/cache layer so every device uses the same forecast snapshot.
        try {
            JSONObject payload = new JSONObject();
            JSONArray points = new JSONArray();
            for (Sample s : samples) {
                JSONObject p = new JSONObject();
                p.put("lat", s.lat); p.put("lon", s.lon); p.put("minutes", s.minutes);
                points.put(p);
            }
            payload.put("points", points); payload.put("hours", Math.max(6, hours));
            ApiClient.Response response = new ApiClient(c).post("api/weather_batch.php", payload);
            JSONObject root = response.json();
            JSONArray forecasts = root.optJSONArray("forecasts");
            if (response.ok() && root.optBoolean("ok", false) && forecasts != null && forecasts.length() > 0) {
                ArrayList<Forecast> server = new ArrayList<>();
                for (int i = 0; i < forecasts.length(); i++) {
                    JSONObject o = forecasts.optJSONObject(i);
                    if (o != null) server.add(Forecast.parse(o));
                }
                if (!server.isEmpty()) return server;
            }
        } catch (Throwable ignored) {}

        StringBuilder lats = new StringBuilder(), lons = new StringBuilder();
        for (Sample s : samples) {
'''
if anchor not in text:
    raise SystemExit('weather fetch anchor missing')
text = text.replace(anchor, insert, 1)
text = text.replace('EstradaPlayComunista/3.0 Android', 'EstradaPlayComunista/4.0 Android')
write(p, text)

# 8) PTT server accepts every Brazilian state road prefix and exposes TURN readiness.
p = ROOT / 'api/radio.php'
text = read(p)
text = text.replace("/^(BR|RJ|MG|ES|SP)-[0-9]{1,4}$/", "/^(BR|AC|AL|AP|AM|BA|CE|DF|ES|GO|MA|MT|MS|MG|PA|PB|PR|PE|PI|RJ|RN|RS|RO|RR|SC|SP|SE|TO)-[0-9]{1,4}$/")
text = text.replace("function radio_ice():array{$out=[['url'=>'stun:stun.l.google.com:19302']];$url=trim((string)app_setting('radio_turn_url',''));if($url!=='')$out[]=['url'=>$url,'username'=>(string)app_setting('radio_turn_username',''),'password'=>(string)app_setting('radio_turn_password','')];return$out;}", "function radio_ice():array{$out=[];$url=trim((string)app_setting('radio_turn_url',''));if($url!=='')$out[]=['url'=>$url,'username'=>(string)app_setting('radio_turn_username',''),'password'=>(string)app_setting('radio_turn_password','')];$out[]=['url'=>'stun:stun.l.google.com:19302'];return$out;}function radio_turn_ready():bool{return trim((string)app_setting('radio_turn_url',''))!=='';}")
text = text.replace("'audio_stored'=>false]);", "'audio_stored'=>false,'turn_ready'=>radio_turn_ready()]);", 1)
write(p, text)

print('production v4 consolidation patches applied')

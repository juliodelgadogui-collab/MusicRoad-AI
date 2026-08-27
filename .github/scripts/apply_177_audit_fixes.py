from pathlib import Path
import re

repo = Path(__file__).resolve().parents[2]
app = repo / 'estradaplay-app'

# -----------------------------------------------------------------------------
# EstradaPlay 1.7.7 — fixes found by end-to-end audit.
# 1) Cockpit buttons really navigate to different destinations.
# 2) Native controls support touchscreen + mouse/stylus/rotary style input.
# 3) API redirects do not hide expired sessions behind a 200 HTML login page.
# 4) Radar loading retries authentication and falls back to MusicRoad radars.php.
# 5) Empty state packs cannot suppress radar refresh for 30 days.
# 6) Failed music downloads do not mark an empty library as configured.
# -----------------------------------------------------------------------------

# --- ApiClient: behave like the working MusicRoad NativeApiClient. ---
p = app / 'app/src/main/java/com/estradaplay/app/ApiClient.java'
s = p.read_text(encoding='utf-8')
if 'c.setInstanceFollowRedirects(true);' not in s:
    raise SystemExit('1.7.7 ApiClient redirect anchor not found')
s = s.replace('c.setInstanceFollowRedirects(true);', 'c.setInstanceFollowRedirects(false);', 1)
p.write_text(s, encoding='utf-8')

# --- RoadMapActivity: visible navigation + direct per-control compatibility. ---
p = app / 'app/src/main/java/com/estradaplay/app/RoadMapActivity.java'
s = p.read_text(encoding='utf-8')

music_count = s.count('music.setOnClickListener(v -> openMain());')
if music_count < 2:
    raise SystemExit(f'1.7.7 expected >=2 cockpit music listeners, found {music_count}')
s = s.replace('music.setOnClickListener(v -> openMain());', 'music.setOnClickListener(v -> openMain("music"));')

home_count = s.count('home.setOnClickListener(v -> openMain());')
if home_count < 1:
    raise SystemExit(f'1.7.7 expected cockpit home listener, found {home_count}')
s = s.replace('home.setOnClickListener(v -> openMain());', 'home.setOnClickListener(v -> openMain("home"));')

old = '''    private void openMain() {
        startActivity(new Intent(this, MainActivity.class));
    }
'''
new = '''    private void openMain(String target) {
        Intent i = new Intent(this, MainActivity.class);
        i.putExtra("open", target == null ? "home" : target);
        startActivity(i);
    }
'''
if old not in s:
    raise SystemExit('1.7.7 openMain anchor not found')
s = s.replace(old, new, 1)

old = '''    private void registerTouchTarget(View view) {
        if (view == null) return;
        view.setEnabled(true);
        view.setClickable(true);
        view.setLongClickable(false);
        view.setFocusable(true);
        view.setFocusableInTouchMode(false);
        view.setOnTouchListener(null);
        view.setOnGenericMotionListener(null);
        if (Build.VERSION.SDK_INT >= 21) view.setElevation(dp(8));
        touchTargets.add(view);
    }
'''
new = '''    private void registerTouchTarget(View view) {
        if (view == null) return;
        view.setEnabled(true);
        view.setClickable(true);
        view.setLongClickable(false);
        view.setFocusable(true);
        view.setFocusableInTouchMode(false);
        if (Build.VERSION.SDK_INT >= 21) view.setElevation(dp(12));
        touchTargets.add(view);

        // AUTOMOTIVE_INPUT_V177: the map already proves the panel is receiving
        // pointer input. Some head units expose that same panel as mouse/stylus
        // or lose ACTION_UP. Execute controls on DOWN without global coordinates.
        view.setOnTouchListener((v, event) -> {
            if (event == null) return false;
            int action = event.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                v.setPressed(true);
                v.performClick();
                return true;
            }
            if (action == android.view.MotionEvent.ACTION_MOVE) return true;
            if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
                v.setPressed(false);
                return true;
            }
            return false;
        });
        view.setOnGenericMotionListener((v, event) -> {
            if (event == null) return false;
            int action = event.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_BUTTON_PRESS) {
                v.performClick();
                return true;
            }
            return false;
        });
        view.setOnKeyListener((v, keyCode, event) -> {
            if (event == null || event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                    keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER) {
                v.performClick();
                return true;
            }
            return false;
        });
    }
'''
if old not in s:
    raise SystemExit('1.7.7 registerTouchTarget final 1.7.6 anchor not found')
s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')

# --- MainActivity: real Home screen + compatibility input + singleTask intents. ---
p = app / 'app/src/main/java/com/estradaplay/app/MainActivity.java'
s = p.read_text(encoding='utf-8')

old = '''    private void showHome() {
        clearDownloadViews();
        roadLiveState = null;
        roadLiveDetail = null;
        openCockpit();
    }
'''
new = '''    private void showHome() {
        clearDownloadViews();
        roadLiveState = null;
        roadLiveDetail = null;
        root.removeAllViews();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = column();
        page.setPadding(dp(18), dp(14), dp(18), dp(26));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        page.addView(topBar("Início"));

        TextView over = overline("ESTRADAPLAY", ACCENT);
        page.addView(over); margins(over, 0, 18, 0, 3);
        TextView title = text("Pronto para dirigir", 29, TEXT, true);
        page.addView(title);
        TextView body = text("Escolha o mapa, a música offline ou a biblioteca. A proteção da estrada continua em segundo plano.", 13, MUTED, false);
        page.addView(body); margins(body, 0, 5, 0, 18);

        Button map = button("ABRIR MAPA E PROTEÇÃO", true);
        page.addView(map, lp(-1, 60));
        map.setOnClickListener(v -> openCockpit());

        List<Track> downloaded = library.downloadedTracks();
        Button music = button(downloaded.isEmpty() ? "ESCOLHER MÚSICAS" : "ABRIR MÚSICA OFFLINE", false);
        page.addView(music, lp(-1, 56)); margins(music, 0, 10, 0, 0);
        music.setOnClickListener(v -> {
            if (downloaded.isEmpty()) {
                if (online()) loadCatalogAndOpenChooser(false); else toast("Conecte-se para escolher músicas.");
            } else showMusic();
        });

        Button libraryButton = compactButton("GERENCIAR BIBLIOTECA");
        page.addView(libraryButton, lp(-1, 52)); margins(libraryButton, 0, 9, 0, 0);
        libraryButton.setOnClickListener(v -> {
            if (online()) loadCatalogAndOpenChooser(false); else toast("Conecte-se para sincronizar pastas.");
        });

        Button accountButton = compactButton("CONTA");
        page.addView(accountButton, lp(-1, 52)); margins(accountButton, 0, 9, 0, 0);
        accountButton.setOnClickListener(v -> showAccount());
    }
'''
if old not in s:
    raise SystemExit('1.7.7 final showHome anchor not found')
s = s.replace(old, new, 1)

# Add a reusable input compatibility helper before UI primitives.
marker = '    // ---- UI primitives ----\n'
helper = '''    // AUTOMOTIVE_INPUT_ALL_SCREENS_V177
    private void automotiveInput(View view) {
        if (view == null) return;
        view.setEnabled(true);
        view.setClickable(true);
        view.setOnTouchListener((v, event) -> {
            if (event == null) return false;
            int action = event.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                v.setPressed(true);
                v.performClick();
                return true;
            }
            if (action == android.view.MotionEvent.ACTION_MOVE) return true;
            if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
                v.setPressed(false);
                return true;
            }
            return false;
        });
        view.setOnGenericMotionListener((v, event) -> {
            if (event != null && event.getActionMasked() == android.view.MotionEvent.ACTION_BUTTON_PRESS) {
                v.performClick();
                return true;
            }
            return false;
        });
        view.setOnKeyListener((v, keyCode, event) -> {
            if (event == null || event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                    keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER) {
                v.performClick();
                return true;
            }
            return false;
        });
    }

'''
if marker not in s:
    raise SystemExit('1.7.7 MainActivity UI primitives marker not found')
s = s.replace(marker, helper + marker, 1)

old = '''    private Button button(String value, boolean primary) {
        Button b = new Button(this); b.setText(value); b.setAllCaps(false); b.setTextSize(11); b.setLetterSpacing(0.08f); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); b.setTextColor(primary ? Color.WHITE : TEXT); b.setBackground(bg(primary ? ACCENT : SURFACE_2, 15, primary ? 0 : BORDER)); b.setStateListAnimator(null); return b;
    }
'''
new = '''    private Button button(String value, boolean primary) {
        Button b = new Button(this); b.setText(value); b.setAllCaps(false); b.setTextSize(11); b.setLetterSpacing(0.08f); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); b.setTextColor(primary ? Color.WHITE : TEXT); b.setBackground(bg(primary ? ACCENT : SURFACE_2, 15, primary ? 0 : BORDER)); b.setStateListAnimator(null); automotiveInput(b); return b;
    }
'''
if old not in s:
    raise SystemExit('1.7.7 MainActivity button factory anchor not found')
s = s.replace(old, new, 1)

old = '''    private Button textButton(String value) {
        Button b = new Button(this); b.setText(value); b.setAllCaps(false); b.setTextSize(11); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); b.setTextColor(MUTED); b.setBackgroundColor(Color.TRANSPARENT); b.setStateListAnimator(null); return b;
    }
'''
new = '''    private Button textButton(String value) {
        Button b = new Button(this); b.setText(value); b.setAllCaps(false); b.setTextSize(11); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); b.setTextColor(MUTED); b.setBackgroundColor(Color.TRANSPARENT); b.setStateListAnimator(null); automotiveInput(b); return b;
    }
'''
if old not in s:
    raise SystemExit('1.7.7 textButton anchor not found')
s = s.replace(old, new, 1)

old = '''    private Button chipButton(String value, boolean selected) {
        Button b = new Button(this); b.setAllCaps(false); b.setText(value); b.setTextSize(10); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); b.setStateListAnimator(null); styleChipButton(b, selected); return b;
    }
'''
new = '''    private Button chipButton(String value, boolean selected) {
        Button b = new Button(this); b.setAllCaps(false); b.setText(value); b.setTextSize(10); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); b.setStateListAnimator(null); styleChipButton(b, selected); automotiveInput(b); return b;
    }
'''
if old not in s:
    raise SystemExit('1.7.7 chipButton anchor not found')
s = s.replace(old, new, 1)

# miniCard is used as a clickable shortcut for Library/Account.
old = '''        l.addView(text(subtitle, 10, MUTED, false));
        return l;
    }

    private LinearLayout metric'''
new = '''        l.addView(text(subtitle, 10, MUTED, false));
        automotiveInput(l);
        return l;
    }

    private LinearLayout metric'''
if old in s:
    s = s.replace(old, new, 1)

# launchMode=singleTask can deliver a new target without recreating MainActivity.
marker = '    @Override protected void onDestroy() {\n'
onnew = '''    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (hasAccount() && library != null && library.hasSetupDone()) openConfiguredTarget();
    }

'''
if marker not in s:
    raise SystemExit('1.7.7 onDestroy marker not found')
s = s.replace(marker, onnew + marker, 1)
p.write_text(s, encoding='utf-8')

# --- RoadSafetyService: stale-cookie retry instead of only checking empty cookie. ---
p = app / 'app/src/main/java/com/estradaplay/app/RoadSafetyService.java'
s = p.read_text(encoding='utf-8')
old = '''            try {
                ensureApiSession();
                if (alertNeeds) packs.prepareTravelReserve(api, lat, lon, heading);
                if (mapNeeds) mapRoads.prepare(api, lat, lon, heading);
            } finally {
'''
new = '''            try {
                ensureApiSession(false);
                boolean alertOk = !alertNeeds || packs.prepareTravelReserve(api, lat, lon, heading);
                boolean mapOk = !mapNeeds || mapRoads.prepare(api, lat, lon, heading);
                if (!alertOk || !mapOk) {
                    // Cookie can exist locally while PHP session already expired.
                    // Refresh device session once and retry only failed downloads.
                    ensureApiSession(true);
                    if (alertNeeds && !alertOk) packs.prepareTravelReserve(api, lat, lon, heading);
                    if (mapNeeds && !mapOk) mapRoads.prepare(api, lat, lon, heading);
                }
            } finally {
'''
if old not in s:
    raise SystemExit('1.7.7 RoadSafetyService coverage worker anchor not found')
s = s.replace(old, new, 1)

old = '''    private void ensureApiSession() {
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
new = '''    private void ensureApiSession(boolean force) {
        if (api == null) return;
        String cookie = api.cookie();
        if (!force && cookie != null && !cookie.trim().isEmpty()) return;
        try {
            if (force) api.clearSession();
            JSONObject d = new JSONObject();
            d.put("device_token", DeviceIdentity.token(this));
            d.put("device_label", DeviceIdentity.label());
            d.put("app_version", BuildConfig.VERSION_NAME);
            ApiClient.Response response = api.post("api/native_app.php?action=device_login", d);
            if (!response.ok() || !response.json().optBoolean("ok", false)) {
                if (force) api.clearSession();
            }
        } catch (Exception ignored) {
            if (force) api.clearSession();
        }
    }
'''
if old not in s:
    raise SystemExit('1.7.7 ensureApiSession anchor not found')
s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')

# --- RoadPackStore: fallback to the existing MusicRoad radar endpoint. ---
p = app / 'app/src/main/java/com/estradaplay/app/RoadPackStore.java'
s = p.read_text(encoding='utf-8')

# Empty state packs must not suppress refresh.
old = '        for(Pack p:packs)if("state".equals(p.kind)&&uf.equals(p.uf)&&now-p.fetchedAt<=freshMs(p))return true;\n'
new = '        for(Pack p:packs)if("state".equals(p.kind)&&uf.equals(p.uf)&&now-p.fetchedAt<=freshMs(p)&&p.hazards!=null&&!p.hazards.isEmpty())return true;\n'
if old not in s:
    raise SystemExit('1.7.7 hasFreshStateLocked anchor not found')
s = s.replace(old, new, 1)

old = '            if(!response.ok()||!json.optBoolean("ok",false)||hazards==null)return false;\n'
# Only replace the state coverage occurrence (the first remaining exact compact occurrence after corridor).
idx = s.find('    private boolean fetchStateCoverage')
if idx < 0:
    raise SystemExit('1.7.7 fetchStateCoverage marker missing')
sub = s[idx:]
if old not in sub:
    raise SystemExit('1.7.7 fetchStateCoverage condition anchor not found')
sub = sub.replace(old, '            if(!response.ok()||!json.optBoolean("ok",false)||hazards==null||hazards.length()==0)return false;\n', 1)
s = s[:idx] + sub

# Insert direct MusicRoad near-radar loader before the state loader added in 1.7.4.
marker = '    private boolean fetchMusicRoadStateRadars(ApiClient api, String uf) {\n'
if marker not in s:
    raise SystemExit('1.7.7 MusicRoad state loader marker missing')
loader = r'''    // MUSICROAD_NEAR_RADARS_V177: use the endpoint already used by the
    // existing MusicRoad backend. This makes passive radar loading work even if
    // the newer EstradaPlay road_pack/offline_state files were not deployed yet.
    private boolean fetchMusicRoadNearRadars(ApiClient api, double lat, double lon) {
        if (api == null) return false;
        try {
            String path = String.format(Locale.US,
                    "api/radars.php?action=near&lat=%.6f&lon=%.6f&radius=50000", lat, lon);
            ApiClient.Response response = api.getLong(path);
            JSONObject json = response.json();
            JSONArray radars = json.optJSONArray("radars");
            if (!response.ok() || !json.optBoolean("ok", false) || radars == null || radars.length() == 0) return false;

            double centerLat = Math.round(lat / GRID_DEG) * GRID_DEG;
            double centerLon = Math.round(lon / GRID_DEG) * GRID_DEG;
            String key = "mrnear_" + packKey(centerLat, centerLon);
            JSONObject stored = new JSONObject();
            stored.put("key", key);
            stored.put("kind", "tile");
            stored.put("center_lat", centerLat);
            stored.put("center_lon", centerLon);
            stored.put("radius_m", 50000);
            stored.put("fetched_at", System.currentTimeMillis());
            stored.put("source_ok", true);
            stored.put("hazards", radars);
            JSONObject coverage = json.optJSONObject("coverage");
            if (coverage == null) coverage = new JSONObject();
            coverage.put("source", "MusicRoad radars.php");
            coverage.put("total", radars.length());
            stored.put("coverage", coverage);
            return savePack(key, stored);
        } catch (Exception ignored) {
            return false;
        }
    }

'''
s = s.replace(marker, loader + marker, 1)

# When road_pack fails/missing/empty, prefer the already-deployed MusicRoad API.
old = '''                String uf = resolveUf(lat, lon);
                return isSupportedUf(uf) && fetchMusicRoadStateRadars(api, uf);
'''
new = '''                if (fetchMusicRoadNearRadars(api, lat, lon)) return true;
                String uf = resolveUf(lat, lon);
                return isSupportedUf(uf) && fetchMusicRoadStateRadars(api, uf);
'''
count = s.count(old)
if count < 1:
    raise SystemExit('1.7.7 fetchCoverage fallback anchor not found')
s = s.replace(old, new)

p.write_text(s, encoding='utf-8')

# --- DownloadService: do not claim setup succeeded when every file failed. ---
p = app / 'app/src/main/java/com/estradaplay/app/DownloadService.java'
s = p.read_text(encoding='utf-8')
old = '''        running = false;
        store.setSetupDone(true);
        publish(false, total, done, failed, failed == 0 ? "Biblioteca offline pronta" : "Concluído com " + failed + " falha(s)");
'''
new = '''        running = false;
        if (done > 0 || !store.downloadedTracks().isEmpty()) store.setSetupDone(true);
        publish(false, total, done, failed, failed == 0 ? "Biblioteca offline pronta" : "Concluído com " + failed + " falha(s)");
'''
if old not in s:
    raise SystemExit('1.7.7 DownloadService setup anchor not found')
s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')

# --- Server endpoint: native clients receive JSON 401 instead of HTML redirect. ---
p = repo / 'api/offline_state.php'
s = p.read_text(encoding='utf-8')
if 'require_login();' in s:
    s = s.replace('require_login();', "$nativeUser=current_user();\nif(!$nativeUser)json_response(['ok'=>false,'error'=>'Sessão expirada.'],401);", 1)
p.write_text(s, encoding='utf-8')

# Version bump after 1.7.6.
gradle = app / 'app/build.gradle'
s = gradle.read_text(encoding='utf-8')
s = s.replace('versionCode 18', 'versionCode 19')
s = s.replace("versionName '1.7.6'", "versionName '1.7.7'")
if "versionName '1.7.7'" not in s or 'versionCode 19' not in s:
    raise SystemExit('1.7.7 version bump failed')
gradle.write_text(s, encoding='utf-8')

print('EstradaPlay 1.7.7 audit fixes applied')

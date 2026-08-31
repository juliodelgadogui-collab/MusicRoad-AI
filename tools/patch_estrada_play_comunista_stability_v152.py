#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "estrada-play-comunista-app"
JAVA = APP / "app/src/main/java/com/estradaplay/comunista"


def read(p):
    return p.read_text(encoding="utf-8")


def write(p, s):
    p.write_text(s, encoding="utf-8")


def replace_once(s, old, new, label):
    if new in s:
        return s
    if old not in s:
        raise SystemExit(f"Trecho não encontrado: {label}")
    return s.replace(old, new, 1)

# Version ---------------------------------------------------------------------
gradle = APP / "app/build.gradle"
s = read(gradle)
s = re.sub(r"versionCode\s+151\b", "versionCode 152", s, count=1)
s = re.sub(r"versionName\s+'1\.5\.1'", "versionName '1.5.2'", s, count=1)
write(gradle, s)

# Services must die with the app task -----------------------------------------
manifest = APP / "app/src/main/AndroidManifest.xml"
s = read(manifest)
for service in ("DownloadService", "PlayerService", "RoadSafetyService"):
    pattern = rf'(android:name="\.{service}"\s*\n\s*android:exported="false")'
    if f'android:name=".{service}"' not in s:
        raise SystemExit(f"Serviço ausente: {service}")
    block_start = s.index(f'android:name=".{service}"')
    block_end = s.index('/>', block_start)
    block = s[block_start:block_end]
    if 'android:stopWithTask="true"' not in block:
        s = re.sub(pattern, rf'\1\n            android:stopWithTask="true"', s, count=1)
write(manifest, s)

# One voice engine at a time. Embedded voice is the safe default --------------
voice_settings = JAVA / "VoiceSettings.java"
write(voice_settings, '''package com.estradaplay.comunista;

import android.content.Context;
import android.content.SharedPreferences;

// STABILITY_V152: exactly one runtime voice engine is selected at a time.
final class VoiceSettings {
    static final int MODE_AUTO = 0; // legacy value; migrated to embedded.
    static final int MODE_EMBEDDED = 1;
    static final int MODE_ANDROID = 2;
    private static final String PREFS = "epc_voice_settings_v151";
    private static final String KEY_MODE = "mode";
    private static final String KEY_MIGRATED_152 = "stability_152_single_voice";
    private VoiceSettings() {}

    static int mode(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int stored = p.getInt(KEY_MODE, MODE_EMBEDDED);
        if (!p.getBoolean(KEY_MIGRATED_152, false)) {
            // Previous AUTO could alternate between embedded and Android TTS.
            // Existing explicit Android selection is preserved; everything else
            // moves to the deterministic embedded bank.
            stored = stored == MODE_ANDROID ? MODE_ANDROID : MODE_EMBEDDED;
            p.edit().putInt(KEY_MODE, stored).putBoolean(KEY_MIGRATED_152, true).apply();
        }
        return stored == MODE_ANDROID ? MODE_ANDROID : MODE_EMBEDDED;
    }

    static void setMode(Context c, int m) {
        int safe = m == MODE_ANDROID ? MODE_ANDROID : MODE_EMBEDDED;
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_MODE, safe).putBoolean(KEY_MIGRATED_152, true).apply();
    }

    static String label(Context c) {
        return mode(c) == MODE_ANDROID ? "TTS DO ANDROID" : "VOZ EMBARCADA";
    }
}
''')

# Thoughts become screen-only by default/migration; voice follows selected engine.
thoughts = JAVA / "RoadThoughts.java"
s = read(thoughts)
s = replace_once(s,
    '    private static final String KEY_INDEX = "next_index";\n',
    '    private static final String KEY_INDEX = "next_index";\n    private static final String KEY_MIGRATED_152 = "stability_152_screen_default";\n',
    'RoadThoughts migration key')
old_mode = '''    static int mode(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MODE, MODE_SCREEN_VOICE);
    }
'''
new_mode = '''    static int mode(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int value = p.getInt(KEY_MODE, MODE_SCREEN);
        if (!p.getBoolean(KEY_MIGRATED_152, false)) {
            // Stability migration: an old screen+voice default must not introduce
            // a second Android TTS voice next to the embedded safety voice.
            if (value == MODE_SCREEN_VOICE) value = MODE_SCREEN;
            p.edit().putInt(KEY_MODE, value).putBoolean(KEY_MIGRATED_152, true).apply();
        }
        return Math.max(MODE_OFF, Math.min(MODE_SCREEN_VOICE, value));
    }
'''
s = replace_once(s, old_mode, new_mode, 'RoadThoughts default mode')
write(thoughts, s)

# Road pack hot-path count cache ----------------------------------------------
pack = JAVA / "RoadPackStore.java"
s = read(pack)
s = replace_once(s,
    '    private final ArrayList<Pack> packs = new ArrayList<>();\n',
    '    private final ArrayList<Pack> packs = new ArrayList<>();\n    // STABILITY_V152: baseBroadcast runs frequently; never recount every hazard per GPS fix.\n    private int cachedHazardCount = -1;\n',
    'RoadPackStore count field')
old_count = '''    synchronized int hazardCount() {
        LinkedHashMap<String, RoadHazard> unique = new LinkedHashMap<>();
        for (Pack p : packs) for (RoadHazard h : p.hazards) unique.put(h.id, h);
        return unique.size();
    }
'''
new_count = '''    synchronized int hazardCount() {
        if (cachedHazardCount >= 0) return cachedHazardCount;
        LinkedHashMap<String, RoadHazard> unique = new LinkedHashMap<>();
        for (Pack p : packs) for (RoadHazard h : p.hazards) unique.put(h.id, h);
        cachedHazardCount = unique.size();
        return cachedHazardCount;
    }
'''
s = replace_once(s, old_count, new_count, 'RoadPackStore hazardCount cache')
s = replace_once(s,
    '    private void cleanupLocked() {\n        long now = System.currentTimeMillis();\n',
    '    private void cleanupLocked() {\n        cachedHazardCount = -1;\n        long now = System.currentTimeMillis();\n',
    'RoadPackStore cache invalidation')
write(pack, s)

# Road safety: non-sticky, task removal cleanup, one thought voice policy ------
road = JAVA / "RoadSafetyService.java"
s = read(road)
s = s.replace('        return START_STICKY;\n', '        return START_NOT_STICKY;\n', 1)
s = s.replace(
    '        if (mode == RoadThoughts.MODE_SCREEN_VOICE && ttsReady) speakThought(RoadThoughts.spoken(e));',
    '        if (mode == RoadThoughts.MODE_SCREEN_VOICE && VoiceSettings.mode(this) == VoiceSettings.MODE_ANDROID && ttsReady) speakThought(RoadThoughts.spoken(e));')
marker = '    @Override public void onDestroy() {\n'
if 'STABILITY_V152_TASK_REMOVED' not in s:
    task_removed = '''    // STABILITY_V152_TASK_REMOVED: closing the app task means closing protection.
    @Override public void onTaskRemoved(Intent rootIntent) {
        try { stopService(new Intent(this, PlayerService.class)); } catch (Throwable ignored) {}
        try { stopService(new Intent(this, DownloadService.class)); } catch (Throwable ignored) {}
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

'''
    if marker not in s:
        raise SystemExit('RoadSafetyService onDestroy marker ausente')
    s = s.replace(marker, task_removed + marker, 1)
write(road, s)

# Player stops completely when the app task is removed ------------------------
player = JAVA / "PlayerService.java"
s = read(player)
if 'STABILITY_V152_TASK_REMOVED' not in s:
    old = '    @Override public void onDestroy() { io.shutdownNow(); releasePlayer(); stopForeground(true); super.onDestroy(); }\n'
    new = '''    // STABILITY_V152_TASK_REMOVED: no music service survives a closed app task.
    @Override public void onTaskRemoved(Intent rootIntent) {
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() { io.shutdownNow(); releasePlayer(); stopForeground(true); super.onDestroy(); }
'''
    s = replace_once(s, old, new, 'PlayerService task removal')
write(player, s)

# Downloads also respect a fully closed app task ------------------------------
download = JAVA / "DownloadService.java"
s = read(download)
if 'STABILITY_V152_TASK_REMOVED' not in s:
    old = '    @Override public void onDestroy() { running = false; io.shutdownNow(); super.onDestroy(); }\n'
    new = '''    // STABILITY_V152_TASK_REMOVED: user closed the app; cancel background downloads.
    @Override public void onTaskRemoved(Intent rootIntent) {
        running = false;
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() { running = false; io.shutdownNow(); stopForeground(true); super.onDestroy(); }
'''
    s = replace_once(s, old, new, 'DownloadService task removal')
write(download, s)

# MainActivity: never parse state packs on the UI thread -----------------------
main = JAVA / "MainActivity.java"
s = read(main)
if 'STABILITY_V152_ASYNC_ROAD_SCREEN' not in s:
    pattern = re.compile(r'    private void showRoad\(\) \{.*?\n    private void showAccount\(\) \{', re.S)
    m = pattern.search(s)
    if not m:
        raise SystemExit('MainActivity showRoad não encontrado')
    new_show_road = '''    // STABILITY_V152_ASYNC_ROAD_SCREEN: state-pack JSON never opens on the UI thread.
    private void showRoad() {
        root.removeAllViews();
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout page = column(); page.setPadding(dp(18), dp(10), dp(18), dp(26));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        page.addView(topBar("Estrada"));

        LinearLayout live = featureCard(GREEN); page.addView(live); margins(live, 0, 8, 0, 0);
        LinearLayout liveTop = row(); liveTop.setGravity(Gravity.CENTER_VERTICAL);
        TextView liveBadge = chip(hasLocationPermission() ? "PROTEÇÃO ATIVA" : "GPS DESATIVADO",
                hasLocationPermission() ? GREEN : RED,
                hasLocationPermission() ? GREEN_SOFT : Color.rgb(63, 27, 31));
        liveTop.addView(liveBadge);
        TextView passive = overline("SEM ROTA", MUTED); liveTop.addView(passive, new LinearLayout.LayoutParams(0, -2, 1));
        passive.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        live.addView(liveTop);
        roadLiveState = text(hasLocationPermission() ? "Monitorando sua direção" : "Ative a localização", 27, TEXT, true);
        live.addView(roadLiveState); margins(roadLiveState, 0, 16, 0, 4);
        roadLiveDetail = text("Carregando resumo offline sem bloquear a tela…", 13, MUTED, false);
        live.addView(roadLiveDetail);

        TextView coverageLabel = overline("COBERTURA OFFLINE", MUTED); page.addView(coverageLabel); margins(coverageLabel, 0, 20, 0, 8);
        LinearLayout coverage = card(); page.addView(coverage);
        TextView coverageInfo = text("RJ · MG · ES · lendo base local…", 13, TEXT, true);
        coverage.addView(coverageInfo);
        TextView coverageDetail = text("A proteção usa os arquivos já salvos no aparelho. A leitura detalhada acontece fora da interface.", 11, MUTED, false);
        coverage.addView(coverageDetail); margins(coverageDetail, 0, 8, 0, 0);

        TextView alertsLabel = overline("O QUE O APP OBSERVA", MUTED); page.addView(alertsLabel); margins(alertsLabel, 0, 20, 0, 8);
        LinearLayout types = card(); page.addView(types);
        LinearLayout r1 = row();
        r1.addView(alertType("RADAR", "velocidade", ACCENT), new LinearLayout.LayoutParams(0, dp(72), 1));
        r1.addView(alertType("SEMÁFORO", "sinalização", BLUE), new LinearLayout.LayoutParams(0, dp(72), 1));
        margins(r1.getChildAt(1), 8, 0, 0, 0); types.addView(r1);
        LinearLayout r2 = row();
        r2.addView(alertType("LOMBADA", "quebra-molas", PURPLE), new LinearLayout.LayoutParams(0, dp(72), 1));
        r2.addView(alertType("PEDÁGIO", "e ferrovia", GREEN), new LinearLayout.LayoutParams(0, dp(72), 1));
        margins(r2.getChildAt(1), 8, 0, 0, 0); types.addView(r2); margins(r2, 0, 8, 0, 0);

        Button action = button(hasLocationPermission() ? "GARANTIR PROTEÇÃO ATIVA" : "ATIVAR LOCALIZAÇÃO", true);
        page.addView(action, lp(-1, 58)); margins(action, 0, 14, 0, 0);
        action.setOnClickListener(v -> {
            if (!hasLocationPermission()) startActivity(new Intent(this, GateActivity.class));
            else { startRoadSafetyIfAllowed(); toast("Proteção da estrada ativa."); }
        });

        io.execute(() -> {
            try {
                RoadPackStore store = new RoadPackStore(getApplicationContext());
                final int points = store.hazardCount();
                final int states = store.coreStatePackCount();
                final String core = store.coreStatesStatus();
                ui.post(() -> {
                    if (isFinishing() || (Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
                    coverageInfo.setText(core + " · " + points + " pontos");
                    coverageDetail.setText(states == 3
                            ? "Núcleo offline completo. Radares e perigos permanecem disponíveis sem internet."
                            : "Núcleo offline " + states + "/3. Conecte-se para completar os estados restantes.");
                    if (roadLiveDetail != null) roadLiveDetail.setText(points + " pontos de segurança disponíveis no aparelho");
                });
            } catch (Throwable ignored) {
                ui.post(() -> coverageInfo.setText("Base offline temporariamente indisponível"));
            }
        });
    }

    private void showAccount() {'''
    s = s[:m.start()] + new_show_road + s[m.end():]
write(main, s)

# AutomotiveActivity: remove all large disk parsing from UI -------------------
auto = JAVA / "AutomotiveActivity.java"
s = read(auto)
s = s.replace('        roadStore = new RoadPackStore(this);\n        offlineRoadStore = new OfflineRoadStore(this);\n', '')
s = s.replace(
'''    private void togglePlayer() {
        if (currentTrack.isEmpty()) {
            List<Track> tracks = library.downloadedTracks();
            if (tracks.isEmpty()) {
                openManager("library");
                return;
            }
            Track first = tracks.get(0);
            Intent i = new Intent(this, PlayerService.class).setAction(PlayerService.ACTION_PLAY_TRACK);
            i.putExtra(PlayerService.EXTRA_KEY, first.key());
            i.putExtra(PlayerService.EXTRA_FOLDER, "__ALL__");
            startService(i);
            return;
        }
        sendPlayer(PlayerService.ACTION_TOGGLE);
    }
''',
'''    private void togglePlayer() {
        if (currentTrack.isEmpty()) {
            // STABILITY_V152: parsing the downloaded library never happens on UI.
            io.execute(() -> {
                List<Track> tracks = library.downloadedTracks();
                ui.post(() -> {
                    if (tracks.isEmpty()) { openManager("library"); return; }
                    Track first = tracks.get(0);
                    Intent i = new Intent(this, PlayerService.class).setAction(PlayerService.ACTION_PLAY_TRACK);
                    i.putExtra(PlayerService.EXTRA_KEY, first.key());
                    i.putExtra(PlayerService.EXTRA_FOLDER, "__ALL__");
                    try { startService(i); } catch (Throwable ignored) {}
                });
            });
            return;
        }
        sendPlayer(PlayerService.ACTION_TOGGLE);
    }
''')
s = s.replace(
'''    private String storageSummary() {
        int songs = library.downloadedTracks().size();
        return songs + " música(s) offline";
    }
''',
'''    private String storageSummary() {
        // STABILITY_V152: use the lightweight boot hint instead of parsing the full catalog.
        return library.hasDownloadedHint() ? "Biblioteca offline pronta" : "Nenhuma música offline";
    }
''')
old_refresh = '''    private void refreshMapData(double lat, double lon, int reportedCount) {
        long now = System.currentTimeMillis();
        if (now - lastMapRefreshAt < 2200L) return;
        lastMapRefreshAt = now;
        io.execute(() -> {
            try {
                if (loadedHazardCount != reportedCount) {
                    roadStore = new RoadPackStore(this);
                    loadedHazardCount = roadStore.hazardCount();
                }
                List<RoadHazard> nearby = roadStore.nearby(lat, lon, 6000);
                long revision = offlineRoadStore.revision();
                String roads = null;
                if (revision != loadedRoadRevision) {
                    roads = offlineRoadStore.combinedGeoJson(lat, lon);
                    loadedRoadRevision = revision;
                }
                final String finalRoads = roads;
                ui.post(() -> {
                    if (roadMap == null) return;
                    roadMap.setHazards(nearby);
                    if (finalRoads != null) roadMap.setOfflineRoadGeoJson(finalRoads);
                });
            } catch (Throwable ignored) {}
        });
    }
'''
new_refresh = '''    private void refreshMapData(double lat, double lon, int reportedCount) {
        long now = System.currentTimeMillis();
        if (now - lastMapRefreshAt < 3000L) return;
        lastMapRefreshAt = now;
        io.execute(() -> {
            try {
                RoadPackStore localRoad = roadStore;
                if (localRoad == null || loadedHazardCount != reportedCount) {
                    localRoad = new RoadPackStore(getApplicationContext());
                    roadStore = localRoad;
                    loadedHazardCount = localRoad.hazardCount();
                }
                List<RoadHazard> nearby = localRoad.nearby(lat, lon, 6000);
                OfflineRoadStore localOffline = offlineRoadStore;
                if (localOffline == null) {
                    localOffline = new OfflineRoadStore(getApplicationContext());
                    offlineRoadStore = localOffline;
                }
                long revision = localOffline.revision();
                String roads = null;
                if (revision != loadedRoadRevision) {
                    roads = localOffline.combinedGeoJson(lat, lon);
                    loadedRoadRevision = revision;
                }
                final String finalRoads = roads;
                ui.post(() -> {
                    if (roadMap == null || isFinishing()) return;
                    roadMap.setHazards(nearby);
                    if (finalRoads != null) roadMap.setOfflineRoadGeoJson(finalRoads);
                });
            } catch (Throwable ignored) {}
        });
    }
'''
s = replace_once(s, old_refresh, new_refresh, 'Automotive refreshMapData')
write(auto, s)

# RoadMapActivity already loads mapStore on IO; remove the remaining UI disk parse.
roadmap = JAVA / "RoadMapActivity.java"
s = read(roadmap)
s = s.replace('        offlineRoadStore = new OfflineRoadStore(this);\n', '')
s = s.replace('                    mapStore = new RoadPackStore(this);', '                    mapStore = new RoadPackStore(getApplicationContext());')
s = s.replace('                if (offlineRoadStore == null) offlineRoadStore = new OfflineRoadStore(this);',
              '                if (offlineRoadStore == null) offlineRoadStore = new OfflineRoadStore(getApplicationContext());')
write(roadmap, s)

print("STABILITY_V152 applied")

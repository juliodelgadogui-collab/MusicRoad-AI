#!/usr/bin/env python3
from pathlib import Path

APP = Path('estrada-play-comunista-app')
JAVA = APP / 'app/src/main/java/com/estradaplay/comunista'
ROAD = JAVA / 'RoadMapActivity.java'
SETTINGS = JAVA / 'DriveSettings.java'
TOOLS = JAVA / 'DriveToolsActivity.java'
GRADLE = APP / 'app/build.gradle'


def must_replace(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f'2.0.9 patch marker missing: {label}')
    return text.replace(old, new, 1)

# Version.
g = GRADLE.read_text(encoding='utf-8')
if 'versionCode 209' not in g:
    g = must_replace(g, 'versionCode 208', 'versionCode 209', 'versionCode')
if "versionName '2.0.9'" not in g:
    g = must_replace(g, "versionName '2.0.8'", "versionName '2.0.9'", 'versionName')
GRADLE.write_text(g, encoding='utf-8')

# Explicit privacy opt-in. Default is intentionally false.
s = SETTINGS.read_text(encoding='utf-8')
if 'contextTrafficOptIn' not in s:
    marker = '    static int impactSensitivity(Context c){return Math.max(0,Math.min(2,p(c).getInt("impact_sensitivity",1)));}\n'
    insert = ('    // CONTEXTO_INTELIGENTE_V209: collaborative traffic is explicit opt-in and defaults OFF.\n'
              '    static boolean contextTrafficOptIn(Context c){return p(c).getBoolean("context_traffic_opt_in",false);}\n'
              + marker)
    s = must_replace(s, marker, insert, 'DriveSettings context opt-in')
SETTINGS.write_text(s, encoding='utf-8')

# User-facing switch with the privacy/retention contract visible.
t = TOOLS.read_text(encoding='utf-8')
if 'TRÂNSITO COLABORATIVO' not in t:
    marker = '        toggle("BASE COLETIVA","Compartilha eventos leves da estrada","collective_enabled",DriveSettings.collectiveEnabled(this));\n'
    insert = marker + ('        toggle("TRÂNSITO COLABORATIVO","Opt-in: envia velocidade/posição com ID em hash e retenção de 2h",'
                       '"context_traffic_opt_in",DriveSettings.contextTrafficOptIn(this));\n')
    t = must_replace(t, marker, insert, 'DriveTools traffic toggle')
TOOLS.write_text(t, encoding='utf-8')

r = ROAD.read_text(encoding='utf-8')

# Context worker/state.
r = must_replace(
    r,
    '    private final ExecutorService routeIo = Executors.newSingleThreadExecutor();\n    private final AtomicBoolean routeLoading = new AtomicBoolean(false);\n',
    '    private final ExecutorService routeIo = Executors.newSingleThreadExecutor();\n'
    '    private final ExecutorService contextIo = Executors.newSingleThreadExecutor();\n'
    '    private final AtomicBoolean routeLoading = new AtomicBoolean(false);\n'
    '    private final AtomicBoolean contextLoading = new AtomicBoolean(false);\n',
    'context executors')

r = must_replace(
    r,
    '    private boolean routeArrived;\n\n    // TOUCH_INPUT_V171',
    '    private boolean routeArrived;\n\n'
    '    // CONTEXTO_INTELIGENTE_V209: Server 7.0 is the unified online context source.\n'
    '    private volatile RouteContextV7Client.Snapshot intelligentContext;\n'
    '    private volatile long lastContextAttemptAt;\n'
    '    private String currentRoadForContext = "";\n'
    '    private boolean localHazardPresent;\n'
    '    private double localHazardDistanceM = Double.POSITIVE_INFINITY;\n\n'
    '    // TOUCH_INPUT_V171',
    'context state')

# Keep latest road name for traffic cell/context correlation.
r = must_replace(
    r,
    '            String road = intent.getStringExtra("road");\n            double distance = intent.getDoubleExtra("distance_m", 0);\n',
    '            String road = intent.getStringExtra("road");\n'
    '            currentRoadForContext = road == null ? "" : road.trim();\n'
    '            double distance = intent.getDoubleExtra("distance_m", 0);\n',
    'road context field')

# Preserve local hazard precedence; Server 7 fills the card only when the local safety engine has no closer alert.
r = must_replace(
    r,
    '            boolean hasHazard = hazard != null && !hazard.trim().isEmpty();\n            boolean hasAlertBase = count > 0;\n',
    '            boolean hasHazard = hazard != null && !hazard.trim().isEmpty();\n'
    '            localHazardPresent = hasHazard;\n'
    '            localHazardDistanceM = hasHazard && distance > 0 ? distance : Double.POSITIVE_INFINITY;\n'
    '            boolean hasAlertBase = count > 0;\n',
    'local hazard precedence')

# Context is applied after local status rendering on every GPS/safety tick, using cache between network refreshes.
r = must_replace(
    r,
    '            updateMapStatus();\n        }\n    };\n',
    '            updateMapStatus();\n'
    '            refreshIntelligentContext(lat, lon);\n'
    '            applyIntelligentContextToUi();\n'
    '        }\n    };\n',
    'road receiver context refresh')

# Force a route-aware Server 7 refresh after a new/recalculated route lands.
r = must_replace(
    r,
    '                routeArrived = false;\n                ui.post(() -> {\n',
    '                routeArrived = false;\n'
    '                lastContextAttemptAt = 0L;\n'
    '                ui.post(() -> {\n',
    'route context reset')

# Reset old route context when destination changes.
r = must_replace(
    r,
    '            routeArrived = false;\n            lastRouteAt = 0L;\n',
    '            routeArrived = false;\n'
    '            intelligentContext = null;\n'
    '            lastContextAttemptAt = 0L;\n'
    '            lastRouteAt = 0L;\n',
    'new destination context reset')

# Seed route/context immediately from last known GPS instead of waiting for the first safety broadcast.
r = must_replace(
    r,
    '                refreshMapData(lastLat, lastLon, 0);\n            }\n        } catch (Throwable ignored) {}\n    }\n\n    private void registerRoadReceiver()',
    '                refreshMapData(lastLat, lastLon, 0);\n'
    '                refreshDestinationRoute(lastLat, lastLon);\n'
    '                refreshIntelligentContext(lastLat, lastLon);\n'
    '            }\n        } catch (Throwable ignored) {}\n    }\n\n    private void registerRoadReceiver()',
    'seed context')

if 'private void refreshIntelligentContext(double lat, double lon)' not in r:
    marker = '    private void playerCommand(String action)'
    methods = r'''    private void refreshIntelligentContext(double lat, double lon) {
        if (!Double.isFinite(lat) || !Double.isFinite(lon)) return;
        long now = System.currentTimeMillis();
        long cadence = activeRoute == null ? 45_000L : 30_000L;
        if (lastContextAttemptAt > 0 && now - lastContextAttemptAt < cadence) return;
        if (!contextLoading.compareAndSet(false, true)) return;
        lastContextAttemptAt = now;

        final RouteEngine.Route route = activeRoute;
        final double heading = lastHeading;
        final double speed = universalSpeed;
        final int limit = universalLimit;
        final String road = currentRoadForContext;
        contextIo.execute(() -> {
            try {
                RouteContextV7Client.Snapshot snapshot = RouteContextV7Client.fetch(
                        getApplicationContext(), lat, lon, heading, speed, limit, road, route);
                intelligentContext = snapshot;
                // Server weather now drives the existing automatic rain safety mode too.
                DriveSettings.setRainAutoDetected(getApplicationContext(), snapshot.rainSoon);
                ui.post(() -> {
                    applyIntelligentContextToUi();
                    updateMapStatus();
                    applyIntelligentContextToUi();
                });
            } catch (Throwable ignored) {
                // Offline/local protection remains authoritative when Server 7.0 is unavailable.
            } finally {
                contextLoading.set(false);
            }
        });
    }

    private void applyIntelligentContextToUi() {
        RouteContextV7Client.Snapshot c = intelligentContext;
        if (c == null || !c.fresh()) return;

        if (navWeatherText != null && !c.weatherText.isEmpty()) navWeatherText.setText(c.weatherText);

        // Radar/physical local safety alerts remain first priority. Unified online context fills
        // the same automotive alert area when there is no closer local hazard.
        boolean serverCanOwnAlert = !localHazardPresent
                || (c.attentionAheadM > 0 && c.attentionAheadM + 120 < localHazardDistanceM);
        if (serverCanOwnAlert && !c.attentionTitle.isEmpty() && c.attentionAheadM <= 60_000) {
            if (protectionText != null) protectionText.setText("SERVER 7.0 · " +
                    (c.attentionKind.isEmpty() ? "CONTEXTO" : c.attentionKind));
            if (hazardTitle != null) hazardTitle.setText(c.attentionTitle);
            if (hazardDetail != null) hazardDetail.setText(c.attentionDetail);
            if (hazardCard != null) hazardCard.setBackground(panel(17, Color.argb(240,17,9,11), Color.rgb(184,20,38)));
        }

        if (universalAheadText != null) {
            if (!c.attentionTitle.isEmpty()) universalAheadText.setText("À FRENTE · " + c.aheadLabel());
            else universalAheadText.setText("À FRENTE · contexto Server 7.0 atualizado");
        }
        if (universalMetaText != null) {
            VehicleProfileStore.Profile v = VehicleProfileStore.active(this);
            universalMetaText.setText(c.metaLine() + " · AUTONOMIA ~" + Math.round(v.autonomyKm()) + " km");
        }
        if (mapStateText != null) mapStateText.setText(c.metaLine());
    }

'''
    r = must_replace(r, marker, methods + marker, 'context methods')

# Shut down the dedicated network worker with the Activity.
r = must_replace(
    r,
    '        try { routeIo.shutdownNow(); } catch (Throwable ignored) {}\n        if (roadMap != null) roadMap.onDestroyMap();\n',
    '        try { routeIo.shutdownNow(); } catch (Throwable ignored) {}\n'
    '        try { contextIo.shutdownNow(); } catch (Throwable ignored) {}\n'
    '        if (roadMap != null) roadMap.onDestroyMap();\n',
    'context shutdown')

ROAD.write_text(r, encoding='utf-8')

# Final source assertions: this script is both generator and migration guard.
checks = {
    GRADLE: ['versionCode 209', "versionName '2.0.9'"],
    SETTINGS: ['contextTrafficOptIn', 'getBoolean("context_traffic_opt_in",false)'],
    TOOLS: ['TRÂNSITO COLABORATIVO', 'context_traffic_opt_in'],
    ROAD: ['CONTEXTO_INTELIGENTE_V209', 'RouteContextV7Client.fetch(', 'refreshIntelligentContext(lat, lon)',
           'DriveSettings.setRainAutoDetected', 'c.metaLine()', 'contextIo.shutdownNow()'],
}
for path, needles in checks.items():
    text = path.read_text(encoding='utf-8')
    for needle in needles:
        if needle not in text:
            raise SystemExit(f'2.0.9 verification failed in {path}: {needle}')

print('Estrada Play Universal 2.0.9 Contexto Inteligente source generated')

#!/usr/bin/env python3
from pathlib import Path
import re

APP = Path('estrada-play-comunista-app')
GRADLE = APP / 'app/build.gradle'
SERVICE = APP / 'app/src/main/java/com/estradaplay/comunista/RoadSafetyService.java'


def must_replace(text, old, new, label):
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f'2.1.0 migration marker missing: {label}')
    return text.replace(old, new, 1)


def must_sub(text, pattern, repl, label, flags=0):
    new, count = re.subn(pattern, repl, text, count=1, flags=flags)
    if count != 1:
        raise SystemExit(f'2.1.0 migration regex failed ({count}): {label}')
    return new

# Version and direct unit-test dependency.
g = GRADLE.read_text(encoding='utf-8')
g = must_replace(g, 'versionCode 209', 'versionCode 210', 'versionCode')
g = must_replace(g, "versionName '2.0.9'", "versionName '2.1.0'", 'versionName')
if "testImplementation 'junit:junit:4.13.2'" not in g:
    g = must_replace(g, "    implementation 'io.github.webrtc-sdk:android:144.7559.09'\n",
                     "    implementation 'io.github.webrtc-sdk:android:144.7559.09'\n    testImplementation 'junit:junit:4.13.2'\n",
                     'JUnit dependency')
GRADLE.write_text(g, encoding='utf-8')

s = SERVICE.read_text(encoding='utf-8')
if 'BASE_CONSOLIDADA_V210' not in s:
    s = must_replace(s, 'public final class RoadSafetyService extends Service {\n',
                     'public final class RoadSafetyService extends Service {\n    // BASE_CONSOLIDADA_V210: Android Service coordinates extracted/tested policies.\n',
                     'service marker')

# The service no longer owns the mutable cooldown map.
s = must_replace(s,
    '    private final Map<String, Long> alertedAt = new HashMap<>();\n',
    '    private final RoadAlertCooldown alertCooldown = new RoadAlertCooldown(ALERT_COOLDOWN_MS, 300);\n',
    'alert cooldown field')
s = s.replace('import java.util.HashMap;\n', '').replace('import java.util.Map;\n', '')

# Road-limit state becomes a pure state machine.
s = must_replace(s,
    '    private final AtomicBoolean roadLimitResolving = new AtomicBoolean(false);\n'
    '    private volatile int currentRoadLimitKmh;\n'
    '    private int announcedRoadLimitKmh;\n'
    '    private boolean roadOverspeedWarned;\n'
    '    private long lastRoadLimitCheckAt;\n',
    '    private final AtomicBoolean roadLimitResolving = new AtomicBoolean(false);\n'
    '    private final RoadLimitPolicy roadLimitPolicy = new RoadLimitPolicy();\n'
    '    private long lastRoadLimitCheckAt;\n',
    'road limit policy field')

# The hot GPS path now delegates candidate geometry + best/next ordering to pure Java.
selection_pattern = r'''        RoadHazard best = null;\n        RoadHazard next = null;\n        double bestForward = Double\.MAX_VALUE;\n        double bestDistance = Double\.MAX_VALUE;\n        double bestScore = Double\.MAX_VALUE;\n        double nextForward = Double\.MAX_VALUE;\n        double nextScore = Double\.MAX_VALUE;\n\n        if \(Float\.isFinite\(heading\) && speedKmh >= 3\.0\) \{.*?\n        \}\n\n        if \(best != null\) \{'''
selection_repl = '''        RoadHazardSelector.Selection selection = RoadHazardSelector.select(
                nearby, loc.getLatitude(), loc.getLongitude(),
                Float.isFinite(heading) ? heading : Double.NaN, speedKmh,
                DriveSettings.rainNow(this), alertCooldown, nowWall);
        RoadHazard best = selection.best;
        RoadHazard next = selection.next;
        double bestForward = selection.bestMatch.forwardM;
        double bestDistance = selection.bestMatch.distanceM;
        double nextForward = selection.nextMatch.forwardM;

        if (best != null) {'''
s = must_sub(s, selection_pattern, selection_repl, 'hazard selector extraction', re.S)

# Road-limit methods retain voice side effects in the Service, state/decisions outside it.
road_limit_pattern = r'''    private void applyRoadLimit\(int limitKmh\) \{.*?\n    \}\n\n    private void evaluateRoadLimit\(double speedKmh\) \{.*?\n    \}\n'''
road_limit_repl = '''    private void applyRoadLimit(int limitKmh) {
        if (!roadLimitPolicy.applyLimit(limitKmh)) return;
        if (roadLimitPolicy.shouldAnnounceLimit() && speakRoadLimitVoice(roadLimitPolicy.currentLimit())) {
            roadLimitPolicy.markLimitAnnounced();
        }
    }

    private void evaluateRoadLimit(double speedKmh) {
        int limit = roadLimitPolicy.currentLimit();
        if (roadLimitPolicy.observeSpeedAndShouldWarn(speedKmh) && speakOverspeedVoice(limit)) {
            roadLimitPolicy.markOverspeedWarned();
        }
    }
'''
s = must_sub(s, road_limit_pattern, road_limit_repl, 'road limit extraction', re.S)

# Remove the in-Service geometry implementation; keep only a tiny cooldown adapter for call-site clarity.
geometry_pattern = r'''    private Match match\(double lat, double lon, float heading, double speedKmh, RoadHazard h\) \{.*?\n    private boolean shouldAlert\(RoadHazard h\) \{.*?\n    \}\n\n    private void rememberAlert\(RoadHazard h\) \{.*?\n    \}\n'''
geometry_repl = '''    private boolean shouldAlert(RoadHazard h) {
        return h != null && alertCooldown.shouldAlert(h.id, System.currentTimeMillis());
    }

    private void rememberAlert(RoadHazard h) {
        if (h != null) alertCooldown.remember(h.id, System.currentTimeMillis());
    }
'''
s = must_sub(s, geometry_pattern, geometry_repl, 'matching/cooldown extraction', re.S)

# Shared distance rendering is now unit tested.
distance_pattern = r'''    private String distanceSpeech\(double m\) \{.*?\n    \}\n\n    private String distanceText\(double m\) \{.*?\n    \}\n'''
distance_repl = '''    private String distanceSpeech(double m) { return RoadSafetyFormat.distanceSpeech(m); }

    private String distanceText(double m) { return RoadSafetyFormat.distanceText(m); }
'''
s = must_sub(s, distance_pattern, distance_repl, 'distance format extraction', re.S)

# Broadcast reads the consolidated road-limit state.
s = s.replace('i.putExtra("road_limit_kmh", currentRoadLimitKmh);',
              'i.putExtra("road_limit_kmh", roadLimitPolicy.currentLimit());')

# Remove the now-obsolete inner Match type from the Service.
s = must_sub(s,
    r'''\n    private static final class Match \{\n        final boolean valid;\n        final double forwardM, lateralM, distanceM;\n        Match\(boolean valid, double forwardM, double lateralM, double distanceM\) \{\n            this\.valid=valid; this\.forwardM=forwardM; this\.lateralM=lateralM; this\.distanceM=distanceM;\n        \}\n        static Match no\(\) \{ return new Match\(false, 0, 0, 0\); \}\n    \}\n\}''',
    '\n}', 'inner Match removal')

# Guard against stale references that would quietly re-grow the monolith.
for forbidden in ['new HashMap<>', 'private Match match(', 'hazardPriorityBias(',
                  'currentRoadLimitKmh', 'announcedRoadLimitKmh', 'roadOverspeedWarned']:
    if forbidden in s:
        raise SystemExit('2.1.0 stale monolith reference: ' + forbidden)

for required in ['BASE_CONSOLIDADA_V210', 'RoadHazardSelector.select(', 'RoadAlertCooldown',
                 'RoadLimitPolicy', 'RoadSafetyFormat.distanceText', 'RoadSafetyFormat.distanceSpeech']:
    if required not in s:
        raise SystemExit('2.1.0 required delegation missing: ' + required)

SERVICE.write_text(s, encoding='utf-8')
print('Estrada Play Universal 2.1.0 direct source consolidated')

#!/usr/bin/env python3
from pathlib import Path

ROAD = Path('estrada-play-comunista-app/app/src/main/java/com/estradaplay/comunista/RoadMapActivity.java')
ENGINE = Path('estrada-play-comunista-app/app/src/main/java/com/estradaplay/comunista/RouteEngine.java')
s = ROAD.read_text(encoding='utf-8')

# re.sub interprets backslash escapes in replacement strings. Repair the source
# persisted by the first 2.0.8 run so Java contains escaped \n rather than a
# physical newline inside a quoted string.
repairs = {
    'navEtaText.setText(eta + "\nCHEGADA");': 'navEtaText.setText(eta + "\\nCHEGADA");',
    'navRemainingText.setText(remainDistance(rem) + "\nRESTANTE");': 'navRemainingText.setText(remainDistance(rem) + "\\nRESTANTE");',
    'navDurationText.setText(durationText(dur) + "\nDURAÇÃO");': 'navDurationText.setText(durationText(dur) + "\\nDURAÇÃO");',
    '+ durationText(dur) + "\n" + instruction);': '+ durationText(dur) + "\\n" + instruction);',
}

changed = False
for broken, fixed in repairs.items():
    if broken in s:
        s = s.replace(broken, fixed)
        changed = True

required = [
    'navEtaText.setText(eta + "\\nCHEGADA");',
    'navRemainingText.setText(remainDistance(rem) + "\\nRESTANTE");',
    'navDurationText.setText(durationText(dur) + "\\nDURAÇÃO");',
    '+ durationText(dur) + "\\n" + instruction);',
]
for item in required:
    if item not in s:
        raise SystemExit('2.0.8 navigation string repair failed: ' + item)
ROAD.write_text(s, encoding='utf-8')

# TripPlannerActivity still uses the pre-2.0.8 four-coordinate signature.
# Keep this overload during the transition; cockpit navigation uses the new
# Context-aware authenticated server route method.
e = ENGINE.read_text(encoding='utf-8')
overload = '''
    // NAVIGATION_REAL_COMPAT_V208: legacy planner overload during consolidation.
    static Route fetch(double fromLat, double fromLon, double toLat, double toLon) throws Exception {
        return fetchOsrmFallback(fromLat, fromLon, toLat, toLon);
    }
'''
if 'NAVIGATION_REAL_COMPAT_V208' not in e:
    anchor = '    private RouteEngine() {}\n'
    if anchor not in e:
        raise SystemExit('RouteEngine constructor anchor not found')
    e = e.replace(anchor, anchor + overload, 1)
    changed = True
if 'static Route fetch(double fromLat, double fromLon, double toLat, double toLon)' not in e:
    raise SystemExit('Trip planner compatibility overload missing')
ENGINE.write_text(e, encoding='utf-8')

print('2.0.8 navigation compatibility repaired' + (' (changed)' if changed else ' (already clean)'))

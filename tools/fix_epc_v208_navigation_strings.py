#!/usr/bin/env python3
from pathlib import Path

ROAD = Path('estrada-play-comunista-app/app/src/main/java/com/estradaplay/comunista/RoadMapActivity.java')
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

# Validate the exact Java forms required by the cockpit.
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
print('2.0.8 Java navigation strings repaired' + (' (changed)' if changed else ' (already clean)'))

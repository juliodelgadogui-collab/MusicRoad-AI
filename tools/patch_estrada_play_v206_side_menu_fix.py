#!/usr/bin/env python3
from pathlib import Path

APP = Path('estrada-play-comunista-app')
BUILD = APP / 'app/build.gradle'
ROAD = APP / 'app/src/main/java/com/estradaplay/comunista/RoadMapActivity.java'


def once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f'missing marker: {label}')
    return text.replace(old, new, 1)

build = BUILD.read_text(encoding='utf-8')
build = once(build, 'versionCode 205', 'versionCode 206', 'versionCode')
build = once(build, "versionName '2.0.5'", "versionName '2.0.6'", 'versionName')
BUILD.write_text(build, encoding='utf-8')

r = ROAD.read_text(encoding='utf-8')
r = once(r,
    'int railWidth = clamp(Math.round(width * 0.064f), dp(88), dp(106));',
    '// SIDE_MENU_FIX_V206: compact rail only; map/dock/navigation remain unchanged.\n        int railWidth = clamp(Math.round(width * 0.052f), dp(76), dp(90));',
    'rail width')
r = once(r,
    'rail.setPadding(dp(7), dp(8), dp(7), dp(8));',
    'rail.setPadding(dp(5), dp(7), dp(5), dp(7));',
    'rail padding')
r = once(r,
    'rail.addView(emblem, new LinearLayout.LayoutParams(-1, clamp(Math.round(height * .125f), dp(62), dp(82))));',
    'rail.addView(emblem, new LinearLayout.LayoutParams(-1, clamp(Math.round(height * .105f), dp(54), dp(68))));',
    'emblem height')
r = once(r,
    'rail.addView(active, new LinearLayout.LayoutParams(-1, dp(26)));',
    'rail.addView(active, new LinearLayout.LayoutParams(-1, dp(22)));',
    'active height')
r = once(r,
    '''        for (Button b : new Button[]{estrada, music, radio, trip, central}) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, 0, 1f);
            p.setMargins(0, dp(3), 0, dp(3));
            rail.addView(b, p);
        }''',
    '''        int navH = clamp(Math.round(height * .092f), dp(48), dp(58));
        for (Button b : new Button[]{estrada, music, radio, trip, central}) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, navH);
            p.setMargins(0, dp(3), 0, dp(3));
            rail.addView(b, p);
        }
        View railFill = new View(this);
        rail.addView(railFill, new LinearLayout.LayoutParams(1, 0, 1f));''',
    'fixed nav buttons')
r = once(r,
    'rail.addView(version, new LinearLayout.LayoutParams(-1, dp(25)));',
    'rail.addView(version, new LinearLayout.LayoutParams(-1, dp(22)));',
    'version height')
r = once(r,
    '''        b.setTextSize(7.6f);
        b.setLetterSpacing(0.035f);
        b.setPadding(0, 0, 0, 0);''',
    '''        b.setTextSize(8.0f);
        b.setLetterSpacing(0.018f);
        b.setPadding(dp(1), 0, dp(1), 0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);''',
    'nav typography')
ROAD.write_text(r, encoding='utf-8')
print('EPC 2.0.6 side menu fix applied')

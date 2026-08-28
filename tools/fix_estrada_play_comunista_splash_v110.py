#!/usr/bin/env python3
from pathlib import Path

path = Path('estrada-play-comunista-app/app/src/main/res/values-v31/styles.xml')
if not path.exists():
    raise SystemExit('design v1.1.0 must run before splash fix')
text = path.read_text(encoding='utf-8')
invalid = '        <item name="android:postSplashScreenTheme">@style/Theme.EstradaPlay</item>\n'
if invalid not in text:
    raise SystemExit('expected Android splash compatibility anchor not found')
path.write_text(text.replace(invalid, '', 1), encoding='utf-8')
print('Android 12 native splash compatibility fixed')

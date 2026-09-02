#!/usr/bin/env python3
from pathlib import Path
p=Path(__file__).resolve().parents[1]/'estrada-play-comunista-app/app/src/main/java/com/estradaplay/comunista/RoadMapActivity.java'
s=p.read_text(encoding='utf-8')
s=s.replace('if (navWeatherText != null) navWeatherText.setText(RoadWeatherMonitor.compactStatus(this));','if (navWeatherText != null) navWeatherText.setText(RoadWeatherMonitor.compactStatus(RoadMapActivity.this));')
p.write_text(s,encoding='utf-8')
print('V200 compile context fix applied')

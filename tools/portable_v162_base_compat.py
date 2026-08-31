#!/usr/bin/env python3
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
p=ROOT/'estrada-play-comunista-app/app/src/main/java/com/estradaplay/comunista/DriveToolsActivity.java'
s=p.read_text(encoding='utf-8')
if 'RÁDIO DA RODOVIA · PTT' not in s:
    marker='button("CENTRAL OFFLINE 2.0",OfflineCenterActivity.class);'
    if marker not in s:
        raise SystemExit('Central Offline marker missing')
    s=s.replace(marker,'button("RÁDIO DA RODOVIA · PTT",RoadRadioActivity.class);'+marker,1)
p.write_text(s,encoding='utf-8')
print('Current Central Universal base prepared for portable patch')

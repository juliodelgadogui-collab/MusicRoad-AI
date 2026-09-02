#!/usr/bin/env python3
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
p=ROOT/'estrada-play-comunista-app/app/src/main/java/com/estradaplay/comunista/RouteEngine.java'
s=p.read_text()
bad='return new Maneuver(action,road,type,modifier,before,type.equals("arrive")?Math.max(before,20):Math.max(before,15));'
good='return new Maneuver(action,road,type,modifier,type.equals("arrive")?Math.max(before,20):Math.max(before,15));'
if bad in s:
    s=s.replace(bad,good)
elif good not in s:
    raise SystemExit('RouteEngine maneuver constructor pattern not found')
p.write_text(s)
print('EPC 2.0.1 RouteEngine maneuver constructor fixed')

#!/usr/bin/env python3
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
p=ROOT/'estrada-play-comunista-app/app/src/main/java/com/estradaplay/comunista/RoadMapActivity.java'
s=p.read_text()
needle='            String hazard = intent.getStringExtra("hazard_label");\n            String road = intent.getStringExtra("road");'
if needle in s:
    s=s.replace(needle,'            String hazard = intent.getStringExtra("hazard_label");\n            String type = intent.getStringExtra("hazard_type");\n            String road = intent.getStringExtra("road");',1)
s=s.replace('private void playerCommand(String action){try{Intent i=new Intent(this,PlayerService.class).setAction(action);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}catch(Throwable ignored){}}','private void playerCommand(String action){try{startService(new Intent(this,PlayerService.class).setAction(action));}catch(Throwable ignored){}}')
p.write_text(s)
print('EPC 2.0.1 integration fix applied')

#!/usr/bin/env python3
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/'estrada-play-comunista-app/app/src/main/java/com/estradaplay/comunista'

def rw(name,fn):
 p=JAVA/name;s=p.read_text(encoding='utf-8');n=fn(s)
 if n==s: print(name+': sem alteração')
 p.write_text(n,encoding='utf-8')

# Keep the same settings file used since 1.4, so 1.5.3 Universal preferences survive.
rw('DriveSettings.java',lambda s:s.replace('epc_drive_settings_v160','epc_drive_settings_v140'))

# First vehicle profile inherits the legacy consumption/fuel price when available.
def vehicle(s):
 old='if(out.isEmpty()){Profile d=new Profile("default","Meu veículo","Carro",10,0,50,50);out.add(d);saveAll(c,out);p(c).edit().putString(A,d.id).apply();}'
 new='if(out.isEmpty()){android.content.SharedPreferences legacy=c.getSharedPreferences("epc_drive_settings_v140",Context.MODE_PRIVATE);float lk=Math.max(1f,legacy.getFloat("consumption_kml",10f));float lp=Math.max(0f,legacy.getFloat("fuel_price",0f));Profile d=new Profile("default","Meu veículo","Carro",lk,lp,50,50);out.add(d);saveAll(c,out);p(c).edit().putString(A,d.id).apply();}'
 if old not in s: raise SystemExit('VehicleProfileStore default marker ausente')
 return s.replace(old,new,1)
rw('VehicleProfileStore.java',vehicle)
print('Migração 1.6.0 aplicada.')

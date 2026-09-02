#!/usr/bin/env python3
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
APP=ROOT/'estrada-play-comunista-app'
JAVA=APP/'app/src/main/java/com/estradaplay/comunista'

def read(p): return p.read_text(encoding='utf-8')
def write(p,s): p.write_text(s,encoding='utf-8')
def once(s,old,new,label):
    if new in s: return s
    if old not in s: raise SystemExit(f'v190 anchor missing: {label}')
    return s.replace(old,new,1)

# Version ---------------------------------------------------------------------
p=APP/'app/build.gradle';s=read(p)
s=s.replace('versionCode 180','versionCode 190').replace("versionName '1.8.0'","versionName '1.9.0'")
if 'versionCode 190' not in s or "versionName '1.9.0'" not in s: raise SystemExit('v190 version failed')
write(p,s)

# Manifest: Bluetooth Classic read-only OBD + new native screens --------------
p=APP/'app/src/main/AndroidManifest.xml';s=read(p)
perm='    <uses-permission android:name="android.permission.RECORD_AUDIO" />\n'
perm_new=perm+'    <uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />\n    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />\n    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />\n'
s=once(s,perm,perm_new,'bluetooth permissions')
act='        <activity android:name=".VehicleCostActivity" android:exported="false" android:screenOrientation="${appOrientation}" />\n'
act_new=act+'        <activity android:name=".MaintenanceActivity" android:exported="false" android:screenOrientation="${appOrientation}" />\n        <activity android:name=".FuelCommunityActivity" android:exported="false" android:screenOrientation="${appOrientation}" />\n        <activity android:name=".EmergencyActivity" android:exported="false" android:screenOrientation="${appOrientation}" />\n        <activity android:name=".Obd2Activity" android:exported="false" android:screenOrientation="${appOrientation}" />\n'
s=once(s,act,act_new,'v190 activities')
write(p,s)

# Central de Bordo ------------------------------------------------------------
p=JAVA/'DriveToolsActivity.java';s=read(p)
old='        addStatusStrip();section("VIAGEM");grid(new Tool[]{new Tool("PLANEJAR VIAGEM","Destino, distância e custo",TripPlannerActivity.class,GOLD),new Tool("HISTÓRICO","Viagens e replay",TripHistoryActivity.class,GREEN),new Tool("VEÍCULO","Consumo e abastecimento",VehicleCostActivity.class,RED),new Tool("FAVORITOS","Postos e paradas salvas",RoadFavoritesActivity.class,GOLD)});'
new='        addStatusStrip();section("VIAGEM");grid(new Tool[]{new Tool("PLANEJAR VIAGEM","Destino, distância e custo",TripPlannerActivity.class,GOLD),new Tool("HISTÓRICO","Viagens e replay",TripHistoryActivity.class,GREEN),new Tool("FAVORITOS","Postos e paradas salvas",RoadFavoritesActivity.class,GOLD)});section("VEÍCULO");grid(new Tool[]{new Tool("VEÍCULO","Consumo e abastecimento",VehicleCostActivity.class,RED),new Tool("MANUTENÇÃO","Diário e próximos serviços",MaintenanceActivity.class,GOLD),new Tool("COMBUSTÍVEL","Preços informados na estrada",FuelCommunityActivity.class,GREEN),new Tool("OBD2","ELM327 Bluetooth · somente leitura",Obd2Activity.class,GREEN)});'
s=once(s,old,new,'central vehicle section')
old='        section("ESTRADA");grid(new Tool[]{new Tool("OFFLINE","RJ · MG · ES e teste local",OfflineCenterActivity.class,GREEN),new Tool("ESTRADA VIVA","Alertas comunitários no mapa",EstradaVivaActivity.class,RED),new Tool("RADARES","Confirmações da base coletiva",CollectiveRoadActivity.class,GOLD),new Tool("SERVIÇOS","Postos, oficinas e hospitais",NearbyServicesActivity.class,GREEN),new Tool("REPORTAR","Registrar ocorrência da via",RoadReportActivity.class,RED)});'
new='        section("ESTRADA");grid(new Tool[]{new Tool("SOS","Localização e emergência",EmergencyActivity.class,RED),new Tool("OFFLINE","RJ · MG · ES e teste local",OfflineCenterActivity.class,GREEN),new Tool("ESTRADA VIVA","Alertas comunitários no mapa",EstradaVivaActivity.class,RED),new Tool("RADARES","Confirmações da base coletiva",CollectiveRoadActivity.class,GOLD),new Tool("SERVIÇOS","Postos, oficinas e hospitais",NearbyServicesActivity.class,GREEN),new Tool("REPORTAR","Registrar ocorrência da via",RoadReportActivity.class,RED)});'
s=once(s,old,new,'central SOS')
write(p,s)

# Copilot voice: expose the new modules without adding another assistant --------
p=JAVA/'VoiceCommandActivity.java';s=read(p)
old='TextView hint=t("‘qual o limite’, ‘o que vem’, ‘tem radar’, ‘autonomia’, ‘offline’, ‘planejar’, ‘favoritos’, ‘salvar isso’, ‘câmera’, ‘posto’, ‘histórico’, ‘HUD’.",13,Color.rgb(174,151,146),false);'
new='TextView hint=t("‘qual o limite’, ‘o que vem’, ‘tem radar’, ‘autonomia’, ‘offline’, ‘planejar’, ‘comboio’, ‘estrada viva’, ‘manutenção’, ‘combustível’, ‘OBD’, ‘SOS’, ‘câmera’, ‘posto’, ‘histórico’, ‘HUD’.",13,Color.rgb(174,151,146),false);'
s=once(s,old,new,'voice hint')
anchor='if(q.contains("rádio")||q.contains("radio")){open(RoadRadioActivity.class);return;}'
insert='if(q.contains("comboio")){open(ConvoyActivity.class);return;}if(q.contains("estrada viva")||q.contains("alerta comunit")){open(EstradaVivaActivity.class);return;}if(q.contains("manutenção")||q.contains("manutencao")||q.contains("revisão")||q.contains("revisao")){open(MaintenanceActivity.class);return;}if(q.contains("preço do combustível")||q.contains("preco do combustivel")||q.contains("combustível barato")||q.contains("combustivel barato")){open(FuelCommunityActivity.class);return;}if(q.contains("obd")||q.contains("motor")&&q.contains("temperatura")){open(Obd2Activity.class);return;}if(q.contains("sos")||q.contains("socorro")||q.contains("emergência")||q.contains("emergencia")){open(EmergencyActivity.class);return;}'+anchor
s=once(s,anchor,insert,'voice new commands')
write(p,s)

# HUD: automatic night brightness + current road ------------------------------
p=JAVA/'HudActivity.java';s=read(p)
old='    private TextView speed,limit,hazard,distance,mirrorState;'
new='    private TextView speed,limit,hazard,distance,mirrorState,roadName;'
s=once(s,old,new,'hud road field')
old='        String h=i.getStringExtra("hazard_label");double d=i.getDoubleExtra("distance_m",0);'
new='        String h=i.getStringExtra("hazard_label");double d=i.getDoubleExtra("distance_m",0);String road=i.getStringExtra("road");if(roadName!=null)roadName.setText(road==null||road.trim().isEmpty()?"RODOVIA --":road.trim().toUpperCase());'
s=once(s,old,new,'hud road rx')
old='    @Override protected void onCreate(Bundle b){super.onCreate(b);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);build();}'
new='    @Override protected void onCreate(Bundle b){super.onCreate(b);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);WindowManager.LayoutParams lp=getWindow().getAttributes();lp.screenBrightness=DriveSettings.nightNow(this)?.22f:.68f;getWindow().setAttributes(lp);build();}'
s=once(s,old,new,'hud night brightness')
old='TextView over=text("HUD DE PARA-BRISA",9,RED,true);'
new='TextView over=text(DriveSettings.nightNow(this)?"HUD NOTURNO · PARA-BRISA":"HUD DE PARA-BRISA",9,RED,true);'
s=once(s,old,new,'hud night title')
old='        TextView kmh=text("KM/H",13,MUTED,true);kmh.setGravity(Gravity.CENTER);kmh.setLetterSpacing(.16f);projection.addView(kmh);'
new='        TextView kmh=text("KM/H",13,MUTED,true);kmh.setGravity(Gravity.CENTER);kmh.setLetterSpacing(.16f);projection.addView(kmh);roadName=text("RODOVIA --",12,GOLD,true);roadName.setGravity(Gravity.CENTER);roadName.setLetterSpacing(.10f);LinearLayout.LayoutParams rnp=new LinearLayout.LayoutParams(-1,-2);rnp.setMargins(0,dp(7),0,0);projection.addView(roadName,rnp);'
s=once(s,old,new,'hud road view')
write(p,s)

print('Estrada Play Comunista Universal 1.9.0 Central Inteligente source generated.')

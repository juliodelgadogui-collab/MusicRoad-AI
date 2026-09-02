#!/usr/bin/env python3
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
APP=ROOT/'estrada-play-comunista-app'
JAVA=APP/'app/src/main/java/com/estradaplay/comunista'

def read(p): return p.read_text(encoding='utf-8')
def write(p,s): p.write_text(s,encoding='utf-8')
def once(s,old,new,label):
    if new in s: return s
    if old not in s: raise SystemExit(f'v180 anchor missing: {label}')
    return s.replace(old,new,1)

# Version ---------------------------------------------------------------------
p=APP/'app/build.gradle';s=read(p)
s=s.replace('versionCode 177','versionCode 180').replace("versionName '1.7.7'","versionName '1.8.0'")
if 'versionCode 180' not in s or "versionName '1.8.0'" not in s: raise SystemExit('v180 version failed')
write(p,s)

# Manifest --------------------------------------------------------------------
p=APP/'app/src/main/AndroidManifest.xml';s=read(p)
anchor='        <activity android:name=".CollectiveRoadActivity" android:exported="false" android:screenOrientation="${appOrientation}" />\n'
insert=anchor+'        <activity android:name=".EstradaVivaActivity" android:exported="false" android:screenOrientation="${appOrientation}" />\n        <activity android:name=".ConvoyActivity" android:exported="false" android:configChanges="orientation|screenSize|keyboardHidden|uiMode" android:screenOrientation="${appOrientation}" />\n'
s=once(s,anchor,insert,'manifest activities')
write(p,s)

# Central de Bordo ------------------------------------------------------------
p=JAVA/'DriveToolsActivity.java';s=read(p)
old='section("ESTRADA");grid(new Tool[]{new Tool("OFFLINE","RJ · MG · ES e teste local",OfflineCenterActivity.class,GREEN),new Tool("RADARES","Confirmações e base coletiva",CollectiveRoadActivity.class,RED),new Tool("SERVIÇOS","Postos, oficinas e hospitais",NearbyServicesActivity.class,GOLD),new Tool("REPORTAR","Registrar ocorrência da via",RoadReportActivity.class,RED)});'
new='section("ESTRADA");grid(new Tool[]{new Tool("OFFLINE","RJ · MG · ES e teste local",OfflineCenterActivity.class,GREEN),new Tool("ESTRADA VIVA","Alertas comunitários no mapa",EstradaVivaActivity.class,RED),new Tool("RADARES","Confirmações da base coletiva",CollectiveRoadActivity.class,GOLD),new Tool("SERVIÇOS","Postos, oficinas e hospitais",NearbyServicesActivity.class,GREEN),new Tool("REPORTAR","Registrar ocorrência da via",RoadReportActivity.class,RED)});'
s=once(s,old,new,'drive tools estrada viva')
old='section("BORDO");grid(new Tool[]{new Tool("RÁDIO PTT","Canal da rodovia",RoadRadioActivity.class,GREEN),new Tool("HUD","Projeção no para-brisa",HudActivity.class,GOLD),new Tool("DASHCAM","Câmera e salvar momento",CameraActivity.class,RED),new Tool("COPILOTO","Comandos de voz",VoiceCommandActivity.class,GREEN)});'
new='section("BORDO");grid(new Tool[]{new Tool("RÁDIO PTT","Canal da rodovia",RoadRadioActivity.class,GREEN),new Tool("COMBOIO","Mapa ao vivo entre os carros",ConvoyActivity.class,BLUE),new Tool("HUD","Projeção no para-brisa",HudActivity.class,GOLD),new Tool("DASHCAM","Câmera e salvar momento",CameraActivity.class,RED),new Tool("COPILOTO","Comandos de voz",VoiceCommandActivity.class,GREEN)});'
# BLUE is not currently declared. Use GREEN to avoid changing palette field.
new=new.replace('ConvoyActivity.class,BLUE','ConvoyActivity.class,GREEN')
s=once(s,old,new,'drive tools convoy')
write(p,s)

# Trip planner: prepare whole calculated route for offline use -----------------
p=JAVA/'TripPlannerActivity.java';s=read(p)
old='Button go=btn("INICIAR VIAGEM",true);LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(-1,dp(58));gp.setMargins(0,dp(16),0,0);ready.addView(go,gp);go.setOnClickListener(x->{DriveSettings.toggle(this,"only_road_mode",false);startActivity(new Intent(this,RoadMapActivity.class));finish();});result.addView(ready);}'
new='Button offline=btn("PREPARAR OFFLINE",false);LinearLayout.LayoutParams op=new LinearLayout.LayoutParams(-1,dp(54));op.setMargins(0,dp(14),0,0);ready.addView(offline,op);offline.setOnClickListener(x->prepareOffline(r,offline));Button go=btn("INICIAR VIAGEM",true);LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(-1,dp(58));gp.setMargins(0,dp(8),0,0);ready.addView(go,gp);go.setOnClickListener(x->{DriveSettings.toggle(this,"only_road_mode",false);startActivity(new Intent(this,RoadMapActivity.class));finish();});result.addView(ready);}'
s=once(s,old,new,'trip offline button')
anchor='    private View metric(String k,String v,int accent)'
method='''    // ROUTE_OFFLINE_PREP_V180: sample the calculated route and prepare map + safety reserves.\n    private void prepareOffline(RouteEngine.Route r,Button button){if(r==null)return;if(DriveSettings.offlineTestMode(this)){toast("Encerre o teste offline para baixar a rota.");return;}button.setEnabled(false);button.setText("PREPARANDO ROTA OFFLINE…");io.execute(()->{TripOfflinePreparer.Result prep=TripOfflinePreparer.prepare(getApplicationContext(),r);runOnUiThread(()->{button.setEnabled(true);button.setText(prep.useful()?"OFFLINE PRONTO · "+Math.max(prep.mapOk,prep.safetyOk)+"/"+prep.points:"TENTAR OFFLINE NOVAMENTE");toast(prep.summary());});});}\n'''
if method not in s:
    if anchor not in s: raise SystemExit('v180 anchor missing: trip method')
    s=s.replace(anchor,method+anchor,1)
write(p,s)

# Road map view: draw community alerts and convoy members ---------------------
p=JAVA/'RoadMapView.java';s=read(p)
old='    private final ArrayList<RoadHazard> hazards = new ArrayList<>();\n    private final ArrayList<RoadQualityStore.Point> qualityPoints = new ArrayList<>();'
new='    private final ArrayList<RoadHazard> hazards = new ArrayList<>();\n    // ESTRADA_VIVA_MAP_V180\n    private final ArrayList<EstradaVivaStore.Event> liveEvents = new ArrayList<>();\n    private final ArrayList<ConvoyStore.Member> convoyMembers = new ArrayList<>();\n    private final ArrayList<RoadQualityStore.Point> qualityPoints = new ArrayList<>();'
s=once(s,old,new,'road map lists')
old='''    void setHazards(List<RoadHazard> value) {\n        hazards.clear();\n        if (value != null) hazards.addAll(value);\n        overlay.invalidate();\n    }\n\n    String status() { return status; }'''
new='''    void setHazards(List<RoadHazard> value) {\n        hazards.clear();\n        if (value != null) hazards.addAll(value);\n        overlay.invalidate();\n    }\n\n    void setLiveEvents(List<EstradaVivaStore.Event> value) { liveEvents.clear(); if(value!=null)liveEvents.addAll(value); overlay.invalidate(); }\n    void setConvoyMembers(List<ConvoyStore.Member> value) { convoyMembers.clear(); if(value!=null)convoyMembers.addAll(value); overlay.invalidate(); }\n\n    String status() { return status; }'''
s=once(s,old,new,'road map setters')
needle='''                if (Double.isFinite(userLat) && Double.isFinite(userLon)) {\n                    PointF s = screen(userLat, userLon);\n                    if (s != null) drawUser(c, s.x, s.y);\n                }'''
draw='''                // ESTRADA_VIVA_MAP_V180: community reports stay visually distinct from fixed hazards.\n                for (EstradaVivaStore.Event e : liveEvents) {\n                    PointF ep = screen(e.lat, e.lon); if (ep == null) continue;\n                    if (ep.x < -40 || ep.y < -40 || ep.x > getWidth()+40 || ep.y > getHeight()+40) continue;\n                    int color = Color.rgb(226,185,76);\n                    if ("accident".equals(e.type)) color=Color.rgb(235,55,65);\n                    else if ("flooding".equals(e.type)) color=Color.rgb(75,165,255);\n                    else if ("animal".equals(e.type)) color=Color.rgb(236,175,65);\n                    else if ("construction".equals(e.type)) color=Color.rgb(255,132,55);\n                    else if ("traffic".equals(e.type)) color=Color.rgb(190,100,240);\n                    else if ("object".equals(e.type)) color=Color.rgb(225,232,239);\n                    Paint lp=circlePaint(color);c.drawCircle(ep.x,ep.y,dp(6.4f),lp);c.drawCircle(ep.x,ep.y,dp(9.0f),ring);\n                    String tag=e.shortLabel();float tw=label.measureText(tag);c.drawText(tag,ep.x-tw/2f,ep.y-dp(13),label);\n                }\n                // CONVOY_MAP_V180: live members are blue, self location keeps the regular arrow.\n                for (ConvoyStore.Member m : convoyMembers) {\n                    if(m.self||!Double.isFinite(m.lat)||!Double.isFinite(m.lon))continue;PointF cp=screen(m.lat,m.lon);if(cp==null)continue;\n                    Paint mp=circlePaint(Color.rgb(55,190,225));c.drawCircle(cp.x,cp.y,dp(7.2f),mp);c.drawCircle(cp.x,cp.y,dp(10.0f),ring);\n                    String tag=m.shortName();float tw=label.measureText(tag);c.drawText(tag,cp.x-tw/2f,cp.y-dp(14),label);\n                }\n                if (Double.isFinite(userLat) && Double.isFinite(userLon)) {\n                    PointF s = screen(userLat, userLon);\n                    if (s != null) drawUser(c, s.x, s.y);\n                }'''
s=once(s,needle,draw,'road map draw live')
write(p,s)

# RoadMapActivity periodically refreshes local-first community feed ------------
p=JAVA/'RoadMapActivity.java';s=read(p)
old='    private RoadQualityStore roadQualityStore;'
new='    private RoadQualityStore roadQualityStore;\n    // ESTRADA_VIVA_MAP_V180\n    private EstradaVivaStore estradaVivaStore;'
s=once(s,old,new,'road activity store')
old='''                final java.util.ArrayList<RoadQualityStore.Point> quality=roadQualityStore.around(lat,lon,6500);\n                if (offlineRoadStore == null) offlineRoadStore = new OfflineRoadStore(getApplicationContext());'''
new='''                final java.util.ArrayList<RoadQualityStore.Point> quality=roadQualityStore.around(lat,lon,6500);\n                if(estradaVivaStore==null) estradaVivaStore=new EstradaVivaStore(getApplicationContext());\n                estradaVivaStore.kickRefresh(lat,lon);\n                final java.util.ArrayList<EstradaVivaStore.Event> liveEvents=estradaVivaStore.cachedNearby(lat,lon,12000);\n                if (offlineRoadStore == null) offlineRoadStore = new OfflineRoadStore(getApplicationContext());'''
s=once(s,old,new,'road activity live load')
old='''                        roadMap.setHazards(nearby);\n                        roadMap.setRoadQualityPoints(quality);'''
new='''                        roadMap.setHazards(nearby);\n                        roadMap.setRoadQualityPoints(quality);\n                        roadMap.setLiveEvents(liveEvents);'''
s=once(s,old,new,'road activity map live')
write(p,s)

print('Estrada Play Comunista Universal 1.8.0 Estrada Viva source generated.')

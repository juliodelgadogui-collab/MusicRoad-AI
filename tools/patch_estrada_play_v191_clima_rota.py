#!/usr/bin/env python3
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
APP=ROOT/'estrada-play-comunista-app'
JAVA=APP/'app/src/main/java/com/estradaplay/comunista'

def read(p): return p.read_text(encoding='utf-8')
def write(p,s): p.write_text(s,encoding='utf-8')
def once(s,old,new,label):
    if new in s: return s
    if old not in s: raise SystemExit(f'v191 anchor missing: {label}')
    return s.replace(old,new,1)

# Version ---------------------------------------------------------------------
p=APP/'app/build.gradle';s=read(p)
s=s.replace('versionCode 190','versionCode 191').replace("versionName '1.9.0'","versionName '1.9.1'")
if 'versionCode 191' not in s or "versionName '1.9.1'" not in s: raise SystemExit('v191 version failed')
write(p,s)

# Manifest --------------------------------------------------------------------
p=APP/'app/src/main/AndroidManifest.xml';s=read(p)
anchor='        <activity android:name=".Obd2Activity" android:exported="false" android:screenOrientation="${appOrientation}" />\n'
insert=anchor+'        <activity android:name=".WeatherActivity" android:exported="false" android:screenOrientation="${appOrientation}" />\n'
s=once(s,anchor,insert,'weather activity')
write(p,s)

# Central: weather card + visible status --------------------------------------
p=JAVA/'DriveToolsActivity.java';s=read(p)
old='section("ESTRADA");grid(new Tool[]{new Tool("SOS","Localização e emergência",EmergencyActivity.class,RED),new Tool("OFFLINE","RJ · MG · ES e teste local",OfflineCenterActivity.class,GREEN),new Tool("ESTRADA VIVA","Alertas comunitários no mapa",EstradaVivaActivity.class,RED),new Tool("RADARES","Confirmações da base coletiva",CollectiveRoadActivity.class,GOLD),new Tool("SERVIÇOS","Postos, oficinas e hospitais",NearbyServicesActivity.class,GREEN),new Tool("REPORTAR","Registrar ocorrência da via",RoadReportActivity.class,RED)});'
new='section("ESTRADA");grid(new Tool[]{new Tool("SOS","Localização e emergência",EmergencyActivity.class,RED),new Tool("CLIMA","Previsão local e ao longo da rota",WeatherActivity.class,GOLD),new Tool("OFFLINE","RJ · MG · ES e teste local",OfflineCenterActivity.class,GREEN),new Tool("ESTRADA VIVA","Alertas comunitários no mapa",EstradaVivaActivity.class,RED),new Tool("RADARES","Confirmações da base coletiva",CollectiveRoadActivity.class,GOLD),new Tool("SERVIÇOS","Postos, oficinas e hospitais",NearbyServicesActivity.class,GREEN),new Tool("REPORTAR","Registrar ocorrência da via",RoadReportActivity.class,RED)});'
s=once(s,old,new,'central weather card')
old='private void addStatusStrip(){LinearLayout strip=row();strip.setGravity(Gravity.CENTER_VERTICAL);strip.setPadding(dp(12),dp(10),dp(12),dp(10));strip.setBackground(panel(SURFACE2,14,BORDER));TextView a=over("● PROTEÇÃO",GREEN);strip.addView(a,new LinearLayout.LayoutParams(0,-2,1));TextView b=over(DriveSettings.offlineTestMode(this)?"TESTE OFFLINE":"BASE LOCAL",DriveSettings.offlineTestMode(this)?GOLD:MUTED);b.setGravity(Gravity.RIGHT);strip.addView(b);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(14),0,0);page.addView(strip,p);}'
new='private void addStatusStrip(){LinearLayout box=col();box.setPadding(dp(12),dp(10),dp(12),dp(10));box.setBackground(panel(SURFACE2,14,BORDER));LinearLayout strip=row();strip.setGravity(Gravity.CENTER_VERTICAL);TextView a=over("● PROTEÇÃO",GREEN);strip.addView(a,new LinearLayout.LayoutParams(0,-2,1));TextView b=over(DriveSettings.offlineTestMode(this)?"TESTE OFFLINE":"BASE LOCAL",DriveSettings.offlineTestMode(this)?GOLD:MUTED);b.setGravity(Gravity.RIGHT);strip.addView(b);box.addView(strip);RoadWeatherMonitor.Snapshot wx=RoadWeatherMonitor.snapshot(this);TextView weather=text(RoadWeatherMonitor.compactStatus(this),10,wx.currentWet?RED:(wx.routeRisk()||wx.nextRainMinutes>=0?GOLD:GREEN),true);LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(-1,-2);wp.setMargins(0,dp(5),0,0);box.addView(weather,wp);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(14),0,0);page.addView(box,p);}'
s=once(s,old,new,'central weather status')
write(p,s)

# Trip planner: save route + analyze forecast before showing plan -------------
p=JAVA/'TripPlannerActivity.java';s=read(p)
old='RouteEngine.Route r=RouteEngine.fetch(l.getLatitude(),l.getLongitude(),d.lat,d.lon);RoadPackStore store=new RoadPackStore(getApplicationContext());'
new='RouteEngine.Route r=RouteEngine.fetch(l.getLatitude(),l.getLongitude(),d.lat,d.lon);RoadWeatherMonitor.saveActiveRoute(getApplicationContext(),r,d.label);RoadWeatherMonitor.refresh(getApplicationContext(),l.getLatitude(),l.getLongitude());RoadPackStore store=new RoadPackStore(getApplicationContext());'
s=once(s,old,new,'trip route weather refresh')
old='ready.addView(text(r.summary(),12,MUTED,false));LinearLayout metrics=row();'
new='ready.addView(text(r.summary(),12,MUTED,false));RoadWeatherMonitor.Snapshot wx=RoadWeatherMonitor.snapshot(this);TextView weather=text(RoadWeatherMonitor.compactStatus(this),11,wx.currentWet?RED:(wx.routeRisk()||wx.nextRainMinutes>=0?GOLD:GREEN),true);LinearLayout.LayoutParams wxp=new LinearLayout.LayoutParams(-1,-2);wxp.setMargins(0,dp(8),0,0);ready.addView(weather,wxp);LinearLayout metrics=row();'
s=once(s,old,new,'trip weather result')
write(p,s)

# Map: weather is visible in the existing map/offline status card -------------
p=JAVA/'RoadMapActivity.java';s=read(p)
old='''        if (offlineRoadStore != null && Double.isFinite(lastLat) && Double.isFinite(lastLon)) {
            value += "\\n" + offlineRoadStore.status(lastLat, lastLon);
        }
        mapStateText.setText(value);'''
new='''        if (offlineRoadStore != null && Double.isFinite(lastLat) && Double.isFinite(lastLon)) {
            value += "\\n" + offlineRoadStore.status(lastLat, lastLon);
        }
        value += "\\n" + RoadWeatherMonitor.compactStatus(this);
        mapStateText.setText(value);'''
s=once(s,old,new,'map weather status')
write(p,s)

# Copilot command: ask directly about weather ---------------------------------
p=JAVA/'VoiceCommandActivity.java';s=read(p)
old='‘comboio’, ‘estrada viva’, ‘manutenção’, ‘combustível’, ‘OBD’, ‘SOS’, ‘câmera’, ‘posto’, ‘histórico’, ‘HUD’.'
new='‘comboio’, ‘estrada viva’, ‘vai chover’, ‘clima’, ‘manutenção’, ‘combustível’, ‘OBD’, ‘SOS’, ‘câmera’, ‘posto’, ‘histórico’, ‘HUD’.'
s=once(s,old,new,'voice hint weather')
old='if(q.contains("offline")){say(core.isEmpty()?"Ainda estou lendo a base offline.":"Base offline: "+core.replace("✓","pronto"));return;}if(q.contains("planej"))'
new='if(q.contains("offline")){say(core.isEmpty()?"Ainda estou lendo a base offline.":"Base offline: "+core.replace("✓","pronto"));return;}if(q.contains("vai chover")||q.contains("chuva")||q.contains("clima")||q.contains("previsão do tempo")||q.contains("previsao do tempo")){say(RoadWeatherMonitor.spokenStatus(this));return;}if(q.contains("planej"))'
s=once(s,old,new,'voice weather command')
write(p,s)

# Safety service: refresh every 15 min, announce useful rain-ahead changes -----
p=JAVA/'RoadSafetyService.java';s=read(p)
old='    private long lastWeatherCheckAt;\n    private boolean ttsReady;'
new='    private long lastWeatherCheckAt;\n    // CLIMA_ROTA_V191: announce only meaningful changes, never every refresh.\n    private long lastWeatherVoiceAt;\n    private String lastWeatherVoiceKey="";\n    private boolean ttsReady;'
s=once(s,old,new,'service weather fields')
old='''        if (DriveSettings.autoRain(this) && !DriveSettings.offlineTestMode(this) && nowWall-lastWeatherCheckAt>=30L*60L*1000L) {
            lastWeatherCheckAt=nowWall; final double wa=loc.getLatitude(), wo=loc.getLongitude(); io.execute(() -> RoadWeatherMonitor.refresh(this,wa,wo));
        }'''
new='''        if (DriveSettings.autoRain(this) && !DriveSettings.offlineTestMode(this) && nowWall-lastWeatherCheckAt>=15L*60L*1000L) {
            lastWeatherCheckAt=nowWall; final double wa=loc.getLatitude(), wo=loc.getLongitude(); io.execute(() -> {RoadWeatherMonitor.refresh(this,wa,wo);main.post(this::maybeAnnounceWeatherForecast);});
        }'''
s=once(s,old,new,'service weather refresh')
anchor='    private void maybeResolveRoadLimit(double lat, double lon, float heading) {'
method='''    // CLIMA_ROTA_V191: voice warning for current/near-future/route rain. Safety alerts keep priority.\n    private void maybeAnnounceWeatherForecast() {\n        RoadWeatherMonitor.Snapshot wx=RoadWeatherMonitor.snapshot(this);\n        if(!wx.shouldAnnounce()||voiceBusy())return;\n        long now=System.currentTimeMillis();String key=wx.voiceKey();\n        if(key.isEmpty())return;\n        if(key.equals(lastWeatherVoiceKey)&&now-lastWeatherVoiceAt<90L*60L*1000L)return;\n        if(speakWeatherVoice(wx.spoken())){lastWeatherVoiceKey=key;lastWeatherVoiceAt=now;}\n    }\n\n    private boolean speakWeatherVoice(String text) {\n        if(!ttsReady||tts==null||voiceBusy()||text==null||text.trim().isEmpty())return false;\n        if(VoiceSettings.mode(this)==VoiceSettings.MODE_EMBEDDED)return false;\n        int token=openVoiceSession("weather",false);if(token<=0)return false;\n        if(speakWithToken(text,token))return true;\n        restoreAudioAfterVoice(token);return false;\n    }\n\n'''
if method not in s:
    if anchor not in s: raise SystemExit('v191 anchor missing: service weather voice method')
    s=s.replace(anchor,method+anchor,1)
old='        i.putExtra("rain_mode", DriveSettings.rainNow(this));\n        i.putExtra("night_mode", DriveSettings.nightNow(this));'
new='        i.putExtra("rain_mode", DriveSettings.rainNow(this));\n        i.putExtra("weather_status", RoadWeatherMonitor.compactStatus(this));\n        i.putExtra("weather_rain_ahead", RoadWeatherMonitor.snapshot(this).shouldAnnounce());\n        i.putExtra("night_mode", DriveSettings.nightNow(this));'
s=once(s,old,new,'service broadcast weather')
write(p,s)

print('Estrada Play Comunista Universal 1.9.1 Clima da Rota source generated.')

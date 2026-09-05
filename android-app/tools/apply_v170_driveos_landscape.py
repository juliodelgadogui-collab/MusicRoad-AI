from pathlib import Path
import re

ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/'app/src/main/java/com/musicroad/ai'
MAIN=JAVA/'MainActivity.java'
MAP=JAVA/'NativeMapView.java'
BUILD=ROOT/'app/build.gradle'

s=MAIN.read_text()

# DriveOS state used only by the landscape presentation.
old='private TextView playerNow,hudSpeed,hudLimit,hudRadar,turnInstruction;'
new='private TextView playerNow,hudSpeed,hudLimit,hudRadar,turnInstruction,landscapeTripInfo;\n    private boolean landscapePaneRender=false;\n    private double currentRouteDistanceMeters=0d,currentRouteDurationSeconds=0d;'
if old not in s: raise SystemExit('v1.7 DriveOS: UI fields anchor missing')
s=s.replace(old,new,1)

# Landscape is a separate automotive shell. Portrait keeps the existing application hierarchy.
pattern=r'    private void showShell\(\)\{.*?\n    \}\n\n    private SpannableString logoSpan\(\)'
replacement=r'''    private void showShell(){
        if(landscape()){showLandscapeShell();return;}
        root.removeAllViews();
        LinearLayout shell=new LinearLayout(this);shell.setOrientation(LinearLayout.VERTICAL);root.addView(shell,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout main=new LinearLayout(this);main.setOrientation(LinearLayout.VERTICAL);shell.addView(main,new LinearLayout.LayoutParams(-1,0,1));
        if(screen!=MAP)main.addView(top(),new LinearLayout.LayoutParams(-1,dp(80)));
        content=new FrameLayout(this);main.addView(content,new LinearLayout.LayoutParams(-1,0,1));render();
    }

    private void showLandscapeShell(){
        root.removeAllViews();root.setBackgroundColor(Color.rgb(3,8,14));
        LinearLayout shell=new LinearLayout(this);shell.setOrientation(LinearLayout.HORIZONTAL);root.addView(shell,new FrameLayout.LayoutParams(-1,-1));
        shell.addView(driveRail(),new LinearLayout.LayoutParams(dp(86),-1));
        content=new FrameLayout(this);content.setBackgroundColor(Color.rgb(5,11,18));shell.addView(content,new LinearLayout.LayoutParams(0,-1,1));
        renderLandscape();
    }

    private View driveRail(){
        LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.VERTICAL);r.setGravity(Gravity.CENTER_HORIZONTAL);r.setPadding(dp(7),dp(8),dp(7),dp(8));r.setBackground(grad(new int[]{Color.rgb(4,9,16),Color.rgb(8,12,25)},0,Color.rgb(28,35,55)));
        TextView mark=t("MR",15,Color.WHITE,true);mark.setGravity(Gravity.CENTER);mark.setBackground(grad(new int[]{PURPLE,Color.rgb(70,35,156)},15,PURPLE));r.addView(mark,new LinearLayout.LayoutParams(-1,dp(48)));
        driveRailButton(r,"⌂","INÍCIO",HOME);driveRailButton(r,"➤","MAPA",MAP);driveRailButton(r,"♫","MÍDIA",MUSIC);driveRailButton(r,"FM","RÁDIO",RADIO);driveRailButton(r,"⇩","OFF",OFFLINE);driveRailButton(r,"⚙","AJUSTES",SETTINGS);
        Space sp=new Space(this);r.addView(sp,new LinearLayout.LayoutParams(1,0,1));TextView ver=t("v"+BuildConfig.VERSION_NAME,9,MUTED,false);ver.setGravity(Gravity.CENTER);r.addView(ver,new LinearLayout.LayoutParams(-1,dp(22)));return r;
    }

    private void driveRailButton(LinearLayout r,String icon,String label,int target){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setGravity(Gravity.CENTER);box.setPadding(dp(3),dp(4),dp(3),dp(4));
        if(screen==target)box.setBackground(grad(new int[]{Color.rgb(81,37,166),Color.rgb(35,24,91)},15,PURPLE));else box.setBackground(bg(Color.TRANSPARENT,15,0));
        TextView i=t(icon,target==RADIO?13:20,screen==target?Color.WHITE:MUTED,true);i.setGravity(Gravity.CENTER);box.addView(i,new LinearLayout.LayoutParams(-1,dp(29)));TextView l=t(label,8,screen==target?Color.WHITE:MUTED,true);l.setGravity(Gravity.CENTER);box.addView(l,new LinearLayout.LayoutParams(-1,dp(17)));
        r.addView(box,new LinearLayout.LayoutParams(-1,dp(55)));margins(box,0,5,0,0);box.setOnClickListener(v->go(target));
    }

    private void renderLandscape(){
        content.removeAllViews();
        if(screen==MAP){landscapeMap();return;}
        if(screen==HOME){landscapeHome();return;}
        if(screen==MUSIC){landscapeMusic();return;}
        landscapeUtility(screen);
    }

    private void landscapeHome(){
        locate();LinearLayout board=new LinearLayout(this);board.setOrientation(LinearLayout.HORIZONTAL);board.setPadding(dp(22),dp(18),dp(22),dp(18));content.addView(board,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout left=new LinearLayout(this);left.setOrientation(LinearLayout.VERTICAL);left.setPadding(dp(22),dp(18),dp(22),dp(18));left.setBackground(grad(new int[]{Color.rgb(16,25,48),Color.rgb(20,17,48),Color.rgb(8,16,28)},24,Color.rgb(55,48,105)));board.addView(left,new LinearLayout.LayoutParams(0,-1,1.18f));
        left.addView(t("DRIVE OS",10,ACCENT,true));JSONObject u=account.optJSONObject("user");String n=u==null?"Motorista":u.optString("name","Motorista");left.addView(t("Olá, "+first(n),31,TEXT,true));TextView sub=t("Navegação, mídia e alertas preparados para a central do carro.",13,MUTED,false);left.addView(sub);margins(sub,0,4,0,14);
        TextView gps=pill(lastLocation==null?"GPS procurando":"GPS ativo",lastLocation==null?MUTED:GREEN);left.addView(gps,new LinearLayout.LayoutParams(-2,dp(34)));margins(gps,0,0,0,14);
        TextView lbl=t("PARA ONDE VAMOS?",9,MUTED,true);left.addView(lbl);EditText dest=edit("Cidade, rua ou endereço");dest.setText(pendingDestination);left.addView(dest,new LinearLayout.LayoutParams(-1,dp(54)));margins(dest,0,6,0,9);Button start=btn("INICIAR NAVEGAÇÃO",true);left.addView(start,new LinearLayout.LayoutParams(-1,dp(56)));start.setOnClickListener(v->{String d=dest.getText().toString().trim();if(d.length()<2){toast("Digite um destino.");return;}pendingDestination=d;go(MAP);});
        LinearLayout quickRow=new LinearLayout(this);left.addView(quickRow,new LinearLayout.LayoutParams(-1,dp(56)));margins(quickRow,0,10,0,0);Button music=btn("♫ MÍDIA",false),radio=btn("FM RÁDIO",false),off=btn("⇩ OFFLINE",false);quickRow.addView(music,new LinearLayout.LayoutParams(0,-1,1));quickRow.addView(radio,new LinearLayout.LayoutParams(0,-1,1));quickRow.addView(off,new LinearLayout.LayoutParams(0,-1,1));margins(radio,7,0,0,0);margins(off,7,0,0,0);music.setOnClickListener(v->go(MUSIC));radio.setOnClickListener(v->go(RADIO));off.setOnClickListener(v->go(OFFLINE));
        LinearLayout right=new LinearLayout(this);right.setOrientation(LinearLayout.VERTICAL);right.setPadding(dp(18),0,0,0);board.addView(right,new LinearLayout.LayoutParams(0,-1,.82f));
        LinearLayout drive=card();drive.addView(t("PAINEL DE CONDUÇÃO",10,ACCENT,true));LinearLayout metrics=new LinearLayout(this);drive.addView(metrics,new LinearLayout.LayoutParams(-1,dp(92)));TextView sp=driveMetric(metrics,lastLocation==null?"0":String.valueOf(Math.max(0,Math.round(lastLocation.getSpeed()*3.6f))),"km/h");driveMetric(metrics,"--","limite");driveMetric(metrics,nearestHazardText(),"alerta");right.addView(drive,new LinearLayout.LayoutParams(-1,0,1));
        if(currentRouteCoords.length()>1){LinearLayout trip=card();trip.addView(t("VIAGEM ATIVA",10,GREEN,true));trip.addView(t(currentDestination.isEmpty()?"Destino ativo":currentDestination,18,TEXT,true));trip.addView(t(currentRouteDistanceMeters>0?km(currentRouteDistanceMeters)+" · "+duration(currentRouteDurationSeconds):"Rota salva",13,MUTED,false));right.addView(trip,new LinearLayout.LayoutParams(-1,-2));margins(trip,0,10,0,0);trip.setOnClickListener(v->go(MAP));}
        View now=nowCard();right.addView(now,new LinearLayout.LayoutParams(-1,dp(86)));margins(now,0,10,0,0);
    }

    private TextView driveMetric(LinearLayout row,String value,String unit){
        LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setGravity(Gravity.CENTER);TextView v=t(value,22,TEXT,true);v.setGravity(Gravity.CENTER);TextView u=t(unit,9,MUTED,true);u.setGravity(Gravity.CENTER);c.addView(v);c.addView(u);row.addView(c,new LinearLayout.LayoutParams(0,-1,1));return v;
    }

    private void landscapeMap(){
        locate();LinearLayout drive=new LinearLayout(this);drive.setOrientation(LinearLayout.HORIZONTAL);content.addView(drive,new FrameLayout.LayoutParams(-1,-1));
        int screenPx=getResources().getDisplayMetrics().widthPixels;int cockpitPx=Math.max(dp(300),Math.min(dp(405),(int)(screenPx*.34f)));
        LinearLayout cockpit=new LinearLayout(this);cockpit.setOrientation(LinearLayout.VERTICAL);cockpit.setPadding(dp(18),dp(15),dp(18),dp(14));cockpit.setBackground(grad(new int[]{Color.rgb(8,17,29),Color.rgb(12,15,34)},0,Color.rgb(37,46,68)));drive.addView(cockpit,new LinearLayout.LayoutParams(cockpitPx,-1));
        LinearLayout title=new LinearLayout(this);title.setGravity(Gravity.CENTER_VERTICAL);TextView nav=t("NAVEGAÇÃO",10,ACCENT,true);title.addView(nav,new LinearLayout.LayoutParams(0,-1,1));TextView onlineChip=pill(online()?"ONLINE":"OFFLINE",online()?GREEN:ACCENT);title.addView(onlineChip,new LinearLayout.LayoutParams(-2,dp(31)));cockpit.addView(title,new LinearLayout.LayoutParams(-1,dp(32)));
        turnInstruction=t(currentRouteCoords.length()>1?"Siga a rota destacada":"Escolha um destino",22,TEXT,true);turnInstruction.setMaxLines(2);cockpit.addView(turnInstruction,new LinearLayout.LayoutParams(-1,-2));TextView destinationText=t(currentDestination.isEmpty()?"MusicRoad Navigation":currentDestination,11,MUTED,false);destinationText.setMaxLines(2);cockpit.addView(destinationText,new LinearLayout.LayoutParams(-1,-2));margins(destinationText,0,3,0,9);
        LinearLayout speedRow=new LinearLayout(this);cockpit.addView(speedRow,new LinearLayout.LayoutParams(-1,dp(82)));hudSpeed=driveMetric(speedRow,lastLocation==null?"0":String.valueOf(Math.max(0,Math.round(lastLocation.getSpeed()*3.6f))),"km/h");hudLimit=driveMetric(speedRow,"--","limite");landscapeTripInfo=driveMetric(speedRow,currentRouteDistanceMeters>0?km(currentRouteDistanceMeters):"--",currentRouteDurationSeconds>0?duration(currentRouteDurationSeconds):"viagem");
        LinearLayout hazard=card();hazard.setGravity(Gravity.CENTER_VERTICAL);TextView hi=t("⚠",24,ACCENT,true);hi.setGravity(Gravity.CENTER);hazard.addView(hi,new LinearLayout.LayoutParams(dp(44),dp(44)));LinearLayout hzText=new LinearLayout(this);hzText.setOrientation(LinearLayout.VERTICAL);hzText.addView(t("PRÓXIMO ALERTA",9,MUTED,true));hudRadar=t(nearestHazardText(),20,TEXT,true);hzText.addView(hudRadar);hazard.addView(hzText,new LinearLayout.LayoutParams(0,-1,1));cockpit.addView(hazard,new LinearLayout.LayoutParams(-1,dp(76)));margins(hazard,0,8,0,8);
        EditText dest=edit("Cidade, rua ou endereço");dest.setText(pendingDestination);cockpit.addView(dest,new LinearLayout.LayoutParams(-1,dp(50)));LinearLayout actions=new LinearLayout(this);Button route=btn(currentRouteCoords.length()>1?"RECALCULAR":"IR",true);Button stop=btn("ENCERRAR",false);actions.addView(route,new LinearLayout.LayoutParams(0,dp(49),1));actions.addView(stop,new LinearLayout.LayoutParams(0,dp(49),1));margins(stop,8,0,0,0);cockpit.addView(actions,new LinearLayout.LayoutParams(-1,dp(49)));margins(actions,0,7,0,0);activeRouteButton=route;TextView summary=small(currentRouteCoords.length()>1?"Viagem ativa":"GPS pronto");summary.setVisibility(View.GONE);cockpit.addView(summary);
        Space spacer=new Space(this);cockpit.addView(spacer,new LinearLayout.LayoutParams(1,0,1));View now=nowCard();cockpit.addView(now,new LinearLayout.LayoutParams(-1,dp(76)));
        FrameLayout mapStage=new FrameLayout(this);drive.addView(mapStage,new LinearLayout.LayoutParams(0,-1,1));mapView=new NativeMapView(this);mapStage.addView(mapView,new FrameLayout.LayoutParams(-1,-1));loadMap();loadRoute();if(lastLocation!=null){mapView.setUserBearing(lastLocation.hasBearing()?lastLocation.getBearing():0f);mapView.setUserLocation(lastLocation.getLatitude(),lastLocation.getLongitude());}if(currentRouteCoords.length()>1)mapView.setDrivingMode(true);
        LinearLayout tools=new LinearLayout(this);tools.setOrientation(LinearLayout.VERTICAL);Button follow=btn("➤",false),report=btn("⚠",false);follow.setTextSize(22);report.setTextSize(22);tools.addView(follow,new LinearLayout.LayoutParams(dp(54),dp(54)));tools.addView(report,new LinearLayout.LayoutParams(dp(54),dp(54)));margins(report,0,8,0,0);FrameLayout.LayoutParams toolp=new FrameLayout.LayoutParams(dp(56),-2);toolp.gravity=Gravity.END|Gravity.CENTER_VERTICAL;toolp.setMargins(0,0,dp(14),0);mapStage.addView(tools,toolp);follow.setOnClickListener(v->{if(lastLocation!=null){mapView.setDrivingMode(true);mapView.recenter(lastLocation.getLatitude(),lastLocation.getLongitude());}});report.setOnClickListener(v->reportPointDialog());
        TextView mapStatus=pill(currentRouteCoords.length()>1?"ROTA ATIVA":"MAPBOX",currentRouteCoords.length()>1?GREEN:PURPLE);FrameLayout.LayoutParams msp=new FrameLayout.LayoutParams(-2,dp(34));msp.gravity=Gravity.TOP|Gravity.END;msp.setMargins(0,dp(14),dp(14),0);mapStage.addView(mapStatus,msp);
        route.setOnClickListener(v->{String d=dest.getText().toString().trim();if(d.length()<2){toast("Informe o destino.");return;}if(lastLocation==null){toast("Aguardando GPS.");locate();return;}pendingDestination=d;mapView.setDrivingMode(false);calc(d,route,summary,cockpit);});
        stop.setOnClickListener(v->{try{startService(NavigationService.stopIntent(this));}catch(Exception ignored){}currentRouteCoords=new JSONArray();currentHazards=new JSONArray();currentDestination="";currentRouteDistanceMeters=0;currentRouteDurationSeconds=0;if(mapView!=null){mapView.setDrivingMode(false);mapView.clearRoute();mapView.setRadars(currentHazards);}turnInstruction.setText("Escolha um destino");if(landscapeTripInfo!=null)landscapeTripInfo.setText("--");toast("Navegação encerrada.");});
        if(!pendingDestination.isEmpty()&&currentRouteCoords.length()<2&&lastLocation!=null&&online())ui.postDelayed(()->calc(pendingDestination,route,summary,cockpit),400);
    }

    private void landscapeMusic(){
        content.removeAllViews();LinearLayout shell=new LinearLayout(this);shell.setOrientation(LinearLayout.HORIZONTAL);shell.setPadding(dp(16),dp(14),dp(16),dp(14));content.addView(shell,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout player=new LinearLayout(this);player.setOrientation(LinearLayout.VERTICAL);player.setPadding(dp(18),dp(18),dp(18),dp(18));player.setBackground(grad(new int[]{Color.rgb(14,22,45),Color.rgb(24,14,50)},22,Color.rgb(55,47,105)));shell.addView(player,new LinearLayout.LayoutParams(dp(300),-1));player.addView(t("MÍDIA",10,ACCENT,true));player.addView(t("Tocando agora",27,TEXT,true));TextView art=t("♫",62,Color.rgb(203,132,255),true);art.setGravity(Gravity.CENTER);art.setBackground(grad(new int[]{Color.rgb(28,48,77),Color.rgb(54,25,105)},24,PURPLE));player.addView(art,new LinearLayout.LayoutParams(-1,0,1));margins(art,0,13,0,13);View now=nowCard();player.addView(now,new LinearLayout.LayoutParams(-1,dp(84)));LinearLayout nav=new LinearLayout(this);Button radio=btn("FM RÁDIO",false),off=btn("OFFLINE",false);nav.addView(radio,new LinearLayout.LayoutParams(0,dp(48),1));nav.addView(off,new LinearLayout.LayoutParams(0,dp(48),1));margins(off,8,0,0,0);player.addView(nav);margins(nav,0,10,0,0);radio.setOnClickListener(v->go(RADIO));off.setOnClickListener(v->go(OFFLINE));
        FrameLayout library=new FrameLayout(this);shell.addView(library,new LinearLayout.LayoutParams(0,-1,1));margins(library,14,0,0,0);FrameLayout previous=content;content=library;landscapePaneRender=true;music();landscapePaneRender=false;content=previous;
    }

    private void landscapeUtility(int target){
        content.removeAllViews();LinearLayout shell=new LinearLayout(this);shell.setOrientation(LinearLayout.HORIZONTAL);shell.setPadding(dp(16),dp(14),dp(16),dp(14));content.addView(shell,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout side=new LinearLayout(this);side.setOrientation(LinearLayout.VERTICAL);side.setPadding(dp(20),dp(20),dp(20),dp(20));side.setBackground(grad(new int[]{Color.rgb(13,22,39),Color.rgb(16,14,35)},22,Color.rgb(45,51,76)));shell.addView(side,new LinearLayout.LayoutParams(dp(255),-1));side.addView(t("DRIVE OS",10,ACCENT,true));side.addView(t(name(target),28,TEXT,true));side.addView(t(target==OFFLINE?"Prepare mapa, radares e dados para viagens sem internet.":target==RADIO?"Rádio e áudio para a estrada.":"Ajuste voz, alertas, conta e comportamento do MusicRoad.",12,MUTED,false));Space sp=new Space(this);side.addView(sp,new LinearLayout.LayoutParams(1,0,1));View now=nowCard();side.addView(now,new LinearLayout.LayoutParams(-1,dp(80)));
        FrameLayout pane=new FrameLayout(this);shell.addView(pane,new LinearLayout.LayoutParams(0,-1,1));margins(pane,14,0,0,0);FrameLayout previous=content;content=pane;landscapePaneRender=true;if(target==OFFLINE)offline();else if(target==RADIO)radio();else settings();landscapePaneRender=false;content=previous;
    }

    private SpannableString logoSpan()'''
ns,n=re.subn(pattern,replacement,s,count=1,flags=re.S)
if n!=1: raise SystemExit(f'v1.7 DriveOS: showShell replacement failed ({n})')
s=ns

# Make direct refreshes of the music screen return through the landscape composer.
s=s.replace('    private void music(){\n', '    private void music(){\n        if(landscape()&&!landscapePaneRender){landscapeMusic();return;}\n',1)
s=s.replace('    private void offline(){', '    private void offline(){if(landscape()&&!landscapePaneRender){landscapeUtility(OFFLINE);return;}',1)
s=s.replace('    private void radio(){', '    private void radio(){if(landscape()&&!landscapePaneRender){landscapeUtility(RADIO);return;}',1)
s=s.replace('    private void settings(){', '    private void settings(){if(landscape()&&!landscapePaneRender){landscapeUtility(SETTINGS);return;}',1)

# Keep trip information for the DriveOS cockpit and enable follow camera after Mapbox route callbacks.
anchor='currentRouteCoords=coords;currentDestination=destination;currentDestinationLat=destLat;currentDestinationLon=destLon;pendingDestination="";currentHazards=new JSONArray();mapboxOffRouteSamples=0;'
if anchor not in s: raise SystemExit('v1.7 DriveOS: route callback anchor missing')
s=s.replace(anchor,anchor+'currentRouteDistanceMeters=distance;currentRouteDurationSeconds=durationSeconds;',1)
anchor2='if(mapView!=null){mapView.setRoute(coords);mapView.setRadars(currentHazards);mapView.fitRoute();mapView.setMessage("Mapbox · rota pronta");}'
if anchor2 not in s: raise SystemExit('v1.7 DriveOS: map route callback anchor missing')
s=s.replace(anchor2,'if(mapView!=null){mapView.setRoute(coords);mapView.setRadars(currentHazards);mapView.fitRoute();mapView.setMessage("Mapbox · rota pronta");if(landscape()){mapView.setDrivingMode(true);if(lastLocation!=null)ui.postDelayed(()->{if(mapView!=null&&lastLocation!=null)mapView.recenter(lastLocation.getLatitude(),lastLocation.getLongitude());},900);}}if(landscapeTripInfo!=null)landscapeTripInfo.setText(km(distance));',1)

# Restore saved distance/duration when opening a route already cached on the device.
old_load='currentRouteCoords=c;currentDestination=j.optString("destination","");mapView.setRoute(c);'
if old_load in s:
    s=s.replace(old_load,'currentRouteCoords=c;currentDestination=j.optString("destination","");currentRouteDistanceMeters=r==null?0:r.optDouble("distance",0);currentRouteDurationSeconds=r==null?0:r.optDouble("duration",0);mapView.setRoute(c);',1)

# Feed GPS bearing into the map so landscape follow mode behaves like an automotive navigator.
old_loc='if(mapView!=null)mapView.setUserLocation(l.getLatitude(),l.getLongitude());'
if old_loc not in s: raise SystemExit('v1.7 DriveOS: location listener anchor missing')
s=s.replace(old_loc,'if(mapView!=null){mapView.setUserBearing(l.hasBearing()?l.getBearing():0f);mapView.setUserLocation(l.getLatitude(),l.getLongitude());}',1)
MAIN.write_text(s)

# Improve the native Mapbox camera for DriveOS without changing the route engine.
m=MAP.read_text()
old='private double userLat = Double.NaN, userLon = Double.NaN;'
if old not in m: raise SystemExit('v1.7 DriveOS: NativeMapView location field missing')
m=m.replace(old,'private double userLat = Double.NaN, userLon = Double.NaN, userBearing = 0.0;\n    private boolean drivingMode = false;',1)

loc_pattern=r'    void setUserLocation\(double lat, double lon\) \{.*?\n    \}\n\n    void clearRoute\(\)'
loc_repl=r'''    void setUserLocation(double lat, double lon) {
        userLat = lat; userLon = lon; fallback.recenterIfEmpty(lat, lon);
        if (mapboxMap != null && drivingMode) { cameraInitialized=true; setCamera(lat,lon,16.6,52.0); }
        else if (mapboxMap != null && !cameraInitialized && route.isEmpty()) { cameraInitialized = true; setCamera(lat, lon, 15.8, 25.0); }
        fallback.invalidate(); overlay.invalidate();
    }

    void setUserBearing(double bearing){if(Double.isFinite(bearing)){userBearing=((bearing%360.0)+360.0)%360.0;}}
    void setDrivingMode(boolean enabled){drivingMode=enabled;if(enabled&&Double.isFinite(userLat)&&Double.isFinite(userLon)){cameraInitialized=true;setCamera(userLat,userLon,16.6,52.0);}}

    void clearRoute()'''
m2,n=re.subn(loc_pattern,loc_repl,m,count=1,flags=re.S)
if n!=1: raise SystemExit(f'v1.7 DriveOS: setUserLocation replacement failed ({n})')
m=m2
m=m.replace('    void fitRoute() {','    void fitRoute() {\n        drivingMode=false;',1)
rec_pattern=r'    void recenter\(double lat, double lon\) \{.*?\n    \}'
rec_repl='''    void recenter(double lat, double lon) {
        drivingMode=true;cameraInitialized=true; fallback.centerLat=lat; fallback.centerLon=lon; fallback.zoom=1.8f; fallback.panX=fallback.panY=0;
        setCamera(lat,lon,16.6,52.0); fallback.invalidate(); overlay.invalidate();
    }'''
m2,n=re.subn(rec_pattern,rec_repl,m,count=1,flags=re.S)
if n!=1: raise SystemExit(f'v1.7 DriveOS: recenter replacement failed ({n})')
m=m2
if '.bearing(0.0)' not in m: raise SystemExit('v1.7 DriveOS: camera bearing anchor missing')
m=m.replace('.bearing(0.0)', '.bearing(drivingMode?userBearing:0.0)',1)
MAP.write_text(m)

# Release identity.
b=BUILD.read_text()
b=re.sub(r'versionCode\s+\d+','versionCode 39',b,count=1)
b=re.sub(r"versionName\s+'[^']+'","versionName '1.7.0'",b,count=1)
BUILD.write_text(b)
print('MusicRoad 1.7.0 DriveOS landscape automotive UI applied')

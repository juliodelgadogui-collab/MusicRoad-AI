from pathlib import Path

main_path=Path('android-app-v2/app/src/main/java/com/musicroad/ai/MainActivity.java')
route_path=Path('android-app-v2/app/src/main/java/com/musicroad/ai/FastMapboxRouteEngine.java')
map_path=Path('android-app-v2/app/src/main/java/com/musicroad/ai/NativeMapView.java')
api_path=Path('api/route_fast.php')
gradle_path=Path('android-app-v2/app/build.gradle')
build_path=Path('.github/workflows/build-native-apk.yml')

def require_replace(text,old,new,label):
    if old not in text: raise SystemExit(f'missing replacement: {label}')
    return text.replace(old,new,1)

def replace_between(text,start,end,new,label):
    i=text.find(start)
    if i<0: raise SystemExit(f'missing start: {label}')
    j=text.find(end,i)
    if j<0: raise SystemExit(f'missing end: {label}')
    return text[:i]+new+text[j:]

s=main_path.read_text()
s=require_replace(s,'import android.widget.HorizontalScrollView;\n','import android.widget.HorizontalScrollView;\nimport android.widget.ImageView;\n','ImageView import')
s=require_replace(s,'private TextView playerNow,hudSpeed,hudLimit,hudRadar,turnInstruction,landscapeTripInfo;','private TextView playerNow,playerArtist,hudSpeed,hudLimit,hudRadar,hudHazardTitle,hudHazardMeta,turnInstruction,turnDistance,turnArrow,landscapeTripInfo,landscapeEta;\n    private ImageView playerArt;','DriveOS fields')
s=require_replace(s,'private JSONArray currentHazards=new JSONArray(),currentRouteCoords=new JSONArray();','private JSONArray currentHazards=new JSONArray(),currentRouteCoords=new JSONArray(),currentRouteSteps=new JSONArray(),currentRouteMaxspeeds=new JSONArray();\n    private int currentStepIndex=0;','route guidance state')
s=require_replace(s,'private static final String KEY_RADIO="radio_stations_v1",KEY_FAVORITES="music_favorites_v1";','private static final String KEY_RADIO="radio_stations_v1",KEY_FAVORITES="music_favorites_v1",KEY_RECENT_DESTINATIONS="recent_destinations_v1",KEY_FAVORITE_DESTINATIONS="favorite_destinations_v1";','destination keys')
s=require_replace(s,'@Override public void onRoute(JSONArray coords,double distance,double durationSeconds,String destination,double destLat,double destLon,boolean reroute){','@Override public void onRoute(JSONArray coords,double distance,double durationSeconds,String destination,double destLat,double destLon,boolean reroute,JSONArray steps,JSONArray maxspeeds){','listener signature')
s=require_replace(s,'currentRouteCoords=coords;currentDestination=destination;currentDestinationLat=destLat;currentDestinationLon=destLon;pendingDestination="";currentHazards=new JSONArray();mapboxOffRouteSamples=0;currentRouteDistanceMeters=distance;currentRouteDurationSeconds=durationSeconds;','currentRouteCoords=coords;currentRouteSteps=steps==null?new JSONArray():steps;currentRouteMaxspeeds=maxspeeds==null?new JSONArray():maxspeeds;currentStepIndex=0;currentDestination=destination;currentDestinationLat=destLat;currentDestinationLon=destLon;pendingDestination="";currentHazards=new JSONArray();mapboxOffRouteSamples=0;currentRouteDistanceMeters=distance;currentRouteDurationSeconds=durationSeconds;rememberDestination(destination);','route state assignment')
s=require_replace(s,'saved.put("route",route);saved.put("radars",currentHazards);offline.saveRoute(saved.toString());','saved.put("route",route);saved.put("radars",currentHazards);saved.put("steps",currentRouteSteps);saved.put("maxspeeds",currentRouteMaxspeeds);offline.saveRoute(saved.toString());','persist guidance')
s=s.replace('if(hudRadar!=null)hudRadar.setText(nearestHazardText());','updateDriveGuidance(lastLocation);')

show_landscape='''    private void showLandscapeShell(){
        setAutomotiveImmersive(true);
        root.removeAllViews();root.setBackgroundColor(Color.rgb(2,7,12));
        LinearLayout outer=new LinearLayout(this);outer.setOrientation(LinearLayout.VERTICAL);root.addView(outer,new FrameLayout.LayoutParams(-1,-1));
        outer.addView(driveStatusBar(),new LinearLayout.LayoutParams(-1,dp(38)));
        LinearLayout shell=new LinearLayout(this);shell.setOrientation(LinearLayout.HORIZONTAL);outer.addView(shell,new LinearLayout.LayoutParams(-1,0,1));
        if(screen!=MAP)shell.addView(driveRail(),new LinearLayout.LayoutParams(dp(74),-1));
        content=new FrameLayout(this);content.setBackgroundColor(Color.rgb(4,10,16));shell.addView(content,new LinearLayout.LayoutParams(0,-1,1));
        renderLandscape();
    }

'''
s=replace_between(s,'    private void showLandscapeShell(){','    private View driveRail(){',show_landscape,'landscape shell')

home_old='EditText dest=edit("Cidade, rua ou endereço");dest.setText(pendingDestination);navCard.addView(dest,new LinearLayout.LayoutParams(-1,dp(54)));'
home_new=home_old+'View destinationStrip=driveDestinationStrip(dest);navCard.addView(destinationStrip,new LinearLayout.LayoutParams(-1,dp(48)));margins(destinationStrip,0,8,0,0);'
s=require_replace(s,home_old,home_new,'home destinations')

landscape_map='''    private void landscapeMap(){
        locate();
        LinearLayout drive=new LinearLayout(this);drive.setOrientation(LinearLayout.HORIZONTAL);content.addView(drive,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout cockpit=new LinearLayout(this);cockpit.setOrientation(LinearLayout.VERTICAL);cockpit.setPadding(dp(14),dp(12),dp(14),dp(12));cockpit.setBackgroundColor(Color.rgb(3,9,14));drive.addView(cockpit,new LinearLayout.LayoutParams(dp(318),-1));
        LinearLayout brandRow=new LinearLayout(this);brandRow.setGravity(Gravity.CENTER_VERTICAL);brandRow.addView(t("MUSICROAD DRIVE",10,ACCENT,true),new LinearLayout.LayoutParams(0,dp(28),1));brandRow.addView(pill(currentRouteCoords.length()>1?"EM ROTA":"PRONTO",currentRouteCoords.length()>1?GREEN:MUTED),new LinearLayout.LayoutParams(-2,dp(28)));cockpit.addView(brandRow);
        TextView destLabel=t(currentRouteCoords.length()>1?(currentDestination.isEmpty()?"Destino ativo":currentDestination):"Escolha um destino no mapa",11,MUTED,false);destLabel.setMaxLines(2);cockpit.addView(destLabel);margins(destLabel,0,1,0,10);
        LinearLayout maneuver=autoPanel();maneuver.setPadding(dp(12),dp(10),dp(12),dp(10));cockpit.addView(maneuver,new LinearLayout.LayoutParams(-1,dp(132)));LinearLayout maneuverTop=new LinearLayout(this);maneuverTop.setGravity(Gravity.CENTER_VERTICAL);turnArrow=t("↑",34,Color.WHITE,true);turnArrow.setGravity(Gravity.CENTER);turnArrow.setBackground(bg(Color.rgb(37,28,20),16,ACCENT));maneuverTop.addView(turnArrow,new LinearLayout.LayoutParams(dp(64),dp(64)));LinearLayout maneuverText=new LinearLayout(this);maneuverText.setOrientation(LinearLayout.VERTICAL);maneuverText.setPadding(dp(11),0,0,0);turnDistance=t("--",27,ACCENT,true);maneuverText.addView(turnDistance);maneuverText.addView(t("PRÓXIMA MANOBRA",8,MUTED,true));maneuverTop.addView(maneuverText,new LinearLayout.LayoutParams(0,-1,1));maneuver.addView(maneuverTop,new LinearLayout.LayoutParams(-1,dp(66)));turnInstruction=t(currentRouteCoords.length()>1?"Siga a rota destacada":"Aguardando rota",15,TEXT,true);turnInstruction.setMaxLines(2);maneuver.addView(turnInstruction,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout speedRow=new LinearLayout(this);speedRow.setGravity(Gravity.CENTER_VERTICAL);cockpit.addView(speedRow,new LinearLayout.LayoutParams(-1,dp(116)));margins(speedRow,0,10,0,0);hudSpeed=autoSpeedGauge(speedRow,lastLocation==null?"0":String.valueOf(Math.max(0,Math.round(lastLocation.getSpeed()*3.6f))));hudLimit=autoLimitGauge(speedRow,"--");LinearLayout speedInfo=new LinearLayout(this);speedInfo.setOrientation(LinearLayout.VERTICAL);speedInfo.setGravity(Gravity.CENTER_VERTICAL);speedInfo.setPadding(dp(10),0,0,0);speedInfo.addView(t("VELOCIDADE",9,MUTED,true));speedInfo.addView(t("Limite Mapbox quando disponível",9,Color.rgb(112,132,149),false));speedRow.addView(speedInfo,new LinearLayout.LayoutParams(0,-1,1));
        LinearLayout trip=autoPanel();trip.setOrientation(LinearLayout.HORIZONTAL);trip.setPadding(dp(8),dp(6),dp(8),dp(6));cockpit.addView(trip,new LinearLayout.LayoutParams(-1,dp(72)));margins(trip,0,9,0,0);landscapeEta=driveMetric(trip,"--:--","ETA");landscapeTripInfo=driveMetric(trip,currentRouteDistanceMeters>0?km(currentRouteDistanceMeters):"--","RESTANTE");
        LinearLayout hazard=autoPanel();hazard.setPadding(dp(12),dp(9),dp(12),dp(9));cockpit.addView(hazard,new LinearLayout.LayoutParams(-1,dp(100)));margins(hazard,0,9,0,0);LinearLayout hazardHead=new LinearLayout(this);hazardHead.setGravity(Gravity.CENTER_VERTICAL);hudHazardTitle=t("Nenhum alerta próximo",12,TEXT,true);hazardHead.addView(hudHazardTitle,new LinearLayout.LayoutParams(0,dp(26),1));hazardHead.addView(t("⚠",19,ACCENT,true),new LinearLayout.LayoutParams(dp(28),dp(26)));hazard.addView(hazardHead);LinearLayout hazardData=new LinearLayout(this);hazardData.setGravity(Gravity.CENTER_VERTICAL);hudRadar=t("--",26,ACCENT,true);hazardData.addView(hudRadar,new LinearLayout.LayoutParams(0,dp(38),1));hudHazardMeta=t("Radares e quebra-molas MusicRoad",9,MUTED,false);hudHazardMeta.setGravity(Gravity.CENTER_VERTICAL|Gravity.END);hazardData.addView(hudHazardMeta,new LinearLayout.LayoutParams(-2,dp(38)));hazard.addView(hazardData);
        Space cockpitSpace=new Space(this);cockpit.addView(cockpitSpace,new LinearLayout.LayoutParams(1,0,1));View now=nowCard();cockpit.addView(now,new LinearLayout.LayoutParams(-1,dp(76)));margins(now,0,9,0,0);LinearLayout cockpitActions=new LinearLayout(this);Button home=btn("⌂  INÍCIO",false),stop=btn("■  ENCERRAR",false);cockpitActions.addView(home,new LinearLayout.LayoutParams(0,dp(48),1));cockpitActions.addView(stop,new LinearLayout.LayoutParams(0,dp(48),1));margins(stop,7,0,0,0);cockpit.addView(cockpitActions);margins(cockpitActions,0,8,0,0);home.setOnClickListener(v->go(HOME));stop.setEnabled(currentRouteCoords.length()>1);
        FrameLayout mapStage=new FrameLayout(this);drive.addView(mapStage,new LinearLayout.LayoutParams(0,-1,1));mapView=new NativeMapView(this);mapStage.addView(mapView,new FrameLayout.LayoutParams(-1,-1));loadMap();loadRoute();if(lastLocation!=null){mapView.setUserBearing(lastLocation.hasBearing()?lastLocation.getBearing():0f);mapView.setUserLocation(lastLocation.getLatitude(),lastLocation.getLongitude());}if(currentRouteCoords.length()>1)mapView.setDrivingMode(true);
        LinearLayout search=new LinearLayout(this);search.setGravity(Gravity.CENTER_VERTICAL);search.setPadding(dp(8),dp(7),dp(8),dp(7));search.setBackground(bg(Color.argb(238,5,12,19),18,Color.rgb(48,63,75)));EditText dest=edit("Destino · cidade, rua ou endereço");dest.setText(pendingDestination);search.addView(dest,new LinearLayout.LayoutParams(0,dp(48),1));Button route=btn(currentRouteCoords.length()>1?"↻":"IR",true);route.setTextSize(14);search.addView(route,new LinearLayout.LayoutParams(dp(64),dp(48)));margins(route,7,0,0,0);activeRouteButton=route;FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(Math.min(dp(540),Math.max(dp(390),(int)(getResources().getDisplayMetrics().widthPixels*.42f))),dp(62));sp.gravity=Gravity.TOP|Gravity.START;sp.setMargins(dp(14),dp(12),0,0);mapStage.addView(search,sp);TextView mode=pill(currentRouteCoords.length()>1?"ROTA MAPBOX":"MAPBOX",currentRouteCoords.length()>1?GREEN:PURPLE);FrameLayout.LayoutParams mp=new FrameLayout.LayoutParams(-2,dp(32));mp.gravity=Gravity.TOP|Gravity.END;mp.setMargins(0,dp(14),dp(14),0);mapStage.addView(mode,mp);
        LinearLayout tools=new LinearLayout(this);tools.setOrientation(LinearLayout.VERTICAL);Button follow=autoIconButton("➤"),report=autoIconButton("⚠");tools.addView(follow,new LinearLayout.LayoutParams(dp(58),dp(58)));tools.addView(report,new LinearLayout.LayoutParams(dp(58),dp(58)));margins(report,0,9,0,0);FrameLayout.LayoutParams tp=new FrameLayout.LayoutParams(dp(60),-2);tp.gravity=Gravity.END|Gravity.BOTTOM;tp.setMargins(0,0,dp(14),dp(16));mapStage.addView(tools,tp);follow.setOnClickListener(v->{if(lastLocation!=null){mapView.setDrivingMode(true);mapView.recenter(lastLocation.getLatitude(),lastLocation.getLongitude());}});report.setOnClickListener(v->reportPointDialog());TextView summary=small(currentRouteCoords.length()>1?"Viagem ativa":"GPS pronto");summary.setVisibility(View.GONE);mapStage.addView(summary,new FrameLayout.LayoutParams(1,1));
        route.setOnClickListener(v->{String d=dest.getText().toString().trim();if(d.length()<2){toast("Informe o destino.");return;}if(lastLocation==null){toast("Aguardando GPS.");locate();return;}pendingDestination=d;mapView.setDrivingMode(false);calc(d,route,summary,mapStage);});stop.setOnClickListener(v->{try{startService(NavigationService.stopIntent(this));}catch(Exception ignored){}currentRouteCoords=new JSONArray();currentRouteSteps=new JSONArray();currentRouteMaxspeeds=new JSONArray();currentHazards=new JSONArray();currentStepIndex=0;currentDestination="";currentRouteDistanceMeters=0;currentRouteDurationSeconds=0;if(mapView!=null){mapView.setDrivingMode(false);mapView.clearRoute();mapView.setRadars(currentHazards);}stop.setEnabled(false);route.setText("IR");turnInstruction.setText("Aguardando rota");turnDistance.setText("--");updateDriveGuidance(lastLocation);toast("Navegação encerrada.");});if(!pendingDestination.isEmpty()&&currentRouteCoords.length()<2&&lastLocation!=null&&online())ui.postDelayed(()->calc(pendingDestination,route,summary,mapStage),350);updateDriveGuidance(lastLocation);
    }

'''
s=replace_between(s,'    private void landscapeMap(){','    private void landscapeMusic(){',landscape_map,'landscape map')

landscape_music='''    private void landscapeMusic(){
        content.removeAllViews();LinearLayout shell=new LinearLayout(this);shell.setOrientation(LinearLayout.HORIZONTAL);shell.setPadding(dp(14),dp(12),dp(14),dp(12));content.addView(shell,new FrameLayout.LayoutParams(-1,-1));LinearLayout player=autoPanel();player.setPadding(dp(18),dp(14),dp(18),dp(14));shell.addView(player,new LinearLayout.LayoutParams(dp(360),-1));LinearLayout mh=new LinearLayout(this);mh.setGravity(Gravity.CENTER_VERTICAL);mh.addView(t("MUSICROAD PLAYER",10,Color.rgb(202,139,255),true),new LinearLayout.LayoutParams(0,dp(28),1));mh.addView(pill("DRIVE MEDIA",PURPLE),new LinearLayout.LayoutParams(-2,dp(28)));player.addView(mh);FrameLayout artwork=new FrameLayout(this);artwork.setBackground(bg(Color.rgb(17,22,32),22,Color.rgb(63,51,85)));TextView fallback=t("♫",74,Color.rgb(218,170,255),true);fallback.setGravity(Gravity.CENTER);artwork.addView(fallback,new FrameLayout.LayoutParams(-1,-1));playerArt=new ImageView(this);playerArt.setScaleType(ImageView.ScaleType.CENTER_CROP);artwork.addView(playerArt,new FrameLayout.LayoutParams(-1,-1));player.addView(artwork,new LinearLayout.LayoutParams(-1,0,1));margins(artwork,0,9,0,9);playerNow=t("Nenhuma música",20,TEXT,true);playerNow.setMaxLines(1);player.addView(playerNow);playerArtist=t("MusicRoad",12,MUTED,false);playerArtist.setMaxLines(1);player.addView(playerArtist);margins(playerArtist,0,2,0,9);LinearLayout controls=new LinearLayout(this);controls.setGravity(Gravity.CENTER);Button prev=autoIconButton("⏮"),play=autoIconButton("▶"),next=autoIconButton("⏭");play.setTextSize(27);play.setBackground(bg(Color.rgb(58,30,84),20,PURPLE));controls.addView(prev,new LinearLayout.LayoutParams(0,dp(62),1));controls.addView(play,new LinearLayout.LayoutParams(0,dp(62),1));controls.addView(next,new LinearLayout.LayoutParams(0,dp(62),1));margins(play,8,0,8,0);player.addView(controls,new LinearLayout.LayoutParams(-1,dp(62)));prev.setOnClickListener(v->startService(PlaybackService.intentAction(this,PlaybackService.ACTION_PREVIOUS)));play.setOnClickListener(v->startService(PlaybackService.intentAction(this,PlaybackService.ACTION_TOGGLE)));next.setOnClickListener(v->startService(PlaybackService.intentAction(this,PlaybackService.ACTION_NEXT)));LinearLayout shortcuts=new LinearLayout(this);Button radio=btn("FM / RÁDIO",false),off=btn("OFFLINE",false);shortcuts.addView(radio,new LinearLayout.LayoutParams(0,dp(48),1));shortcuts.addView(off,new LinearLayout.LayoutParams(0,dp(48),1));margins(off,7,0,0,0);player.addView(shortcuts);margins(shortcuts,0,9,0,0);radio.setOnClickListener(v->go(RADIO));off.setOnClickListener(v->go(OFFLINE));FrameLayout library=new FrameLayout(this);shell.addView(library,new LinearLayout.LayoutParams(0,-1,1));margins(library,12,0,0,0);FrameLayout previous=content;content=library;landscapePaneRender=true;music();landscapePaneRender=false;content=previous;try{startService(PlaybackService.intentAction(this,PlaybackService.ACTION_BROADCAST_STATE));}catch(Exception ignored){}
    }

    private View driveDestinationStrip(EditText dest){HorizontalScrollView scroll=new HorizontalScrollView(this);scroll.setHorizontalScrollBarEnabled(false);LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);scroll.addView(row,new HorizontalScrollView.LayoutParams(-2,-1));Button save=btn("☆ SALVAR",false);save.setTextSize(10);row.addView(save,new LinearLayout.LayoutParams(-2,dp(42)));save.setOnClickListener(v->{String value=dest.getText().toString().trim();if(value.length()<2){toast("Digite um destino para favoritar.");return;}addFavoriteDestination(value);toast("Destino favorito salvo.");showShell();});List<String> favorites=favoriteDestinations();for(String value:favorites){Button b=btn("★ "+shortDestination(value),false);b.setTextSize(10);row.addView(b,new LinearLayout.LayoutParams(-2,dp(42)));margins(b,7,0,0,0);b.setOnClickListener(v->dest.setText(value));}for(String value:recentDestinations()){if(favorites.contains(value))continue;Button b=btn("↺ "+shortDestination(value),false);b.setTextSize(10);row.addView(b,new LinearLayout.LayoutParams(-2,dp(42)));margins(b,7,0,0,0);b.setOnClickListener(v->dest.setText(value));}return scroll;}
    private List<String> recentDestinations(){return destinationList(KEY_RECENT_DESTINATIONS);}private List<String> favoriteDestinations(){return destinationList(KEY_FAVORITE_DESTINATIONS);}private List<String> destinationList(String key){ArrayList<String> out=new ArrayList<>();try{JSONArray a=new JSONArray(prefs.getString(key,"[]"));for(int i=0;i<a.length();i++){String x=a.optString(i,"").trim();if(!x.isEmpty()&&!out.contains(x))out.add(x);}}catch(Exception ignored){}return out;}private void rememberDestination(String value){value=value==null?"":value.trim();if(value.isEmpty())return;List<String> old=recentDestinations();old.remove(value);old.add(0,value);JSONArray a=new JSONArray();for(int i=0;i<Math.min(6,old.size());i++)a.put(old.get(i));prefs.edit().putString(KEY_RECENT_DESTINATIONS,a.toString()).apply();}private void addFavoriteDestination(String value){value=value==null?"":value.trim();if(value.isEmpty())return;List<String> old=favoriteDestinations();old.remove(value);old.add(0,value);JSONArray a=new JSONArray();for(int i=0;i<Math.min(6,old.size());i++)a.put(old.get(i));prefs.edit().putString(KEY_FAVORITE_DESTINATIONS,a.toString()).apply();}private String shortDestination(String value){if(value==null)return "Destino";String x=value.trim();return x.length()>24?x.substring(0,23)+"…":x;}
    private void updateDriveGuidance(Location l){if(l==null)return;if(hudSpeed!=null)hudSpeed.setText(String.valueOf(Math.max(0,Math.round(l.getSpeed()*3.6f))));int limit=DriveGuidance.speedLimitKph(currentRouteCoords,currentRouteMaxspeeds,l.getLatitude(),l.getLongitude());if(hudLimit!=null)hudLimit.setText(limit>0?String.valueOf(limit):"--");if(currentRouteCoords!=null&&currentRouteCoords.length()>1){DriveGuidance.Maneuver m=DriveGuidance.maneuver(currentRouteSteps,currentStepIndex,l.getLatitude(),l.getLongitude());currentStepIndex=m.index;if(turnArrow!=null)turnArrow.setText(m.arrow);if(turnInstruction!=null)turnInstruction.setText(m.instruction);if(turnDistance!=null)turnDistance.setText(DriveGuidance.distanceLabel(m.distanceMeters));double remaining=DriveGuidance.remainingMeters(currentRouteCoords,l.getLatitude(),l.getLongitude());if(remaining<=0&&currentRouteDistanceMeters>0)remaining=currentRouteDistanceMeters;if(landscapeTripInfo!=null)landscapeTripInfo.setText(km(remaining));double seconds=currentRouteDurationSeconds;if(currentRouteDistanceMeters>0&&remaining>0)seconds=currentRouteDurationSeconds*Math.min(1d,remaining/currentRouteDistanceMeters);if(landscapeEta!=null)landscapeEta.setText(android.text.format.DateFormat.format("HH:mm",new java.util.Date(System.currentTimeMillis()+Math.max(0L,Math.round(seconds*1000d)))));}else{if(turnArrow!=null)turnArrow.setText("↑");if(turnInstruction!=null)turnInstruction.setText("Aguardando rota");if(turnDistance!=null)turnDistance.setText("--");if(landscapeTripInfo!=null)landscapeTripInfo.setText("--");if(landscapeEta!=null)landscapeEta.setText("--:--");}DriveGuidance.Hazard h=DriveGuidance.nearestHazard(currentHazards,l.getLatitude(),l.getLongitude());if(hudRadar!=null)hudRadar.setText(h==null?"--":DriveGuidance.distanceLabel(h.distanceMeters));if(hudHazardTitle!=null)hudHazardTitle.setText(h==null?"Nenhum alerta próximo":h.label);if(hudHazardMeta!=null)hudHazardMeta.setText(h==null?"Radares e quebra-molas MusicRoad":(h.speedKph>0?h.speedKph+" km/h":"Alerta MusicRoad"));if(hudRadar!=null)hudRadar.setTextColor(h!=null&&h.distanceMeters<220?RED:ACCENT);}
    private void updatePlayerArtwork(JSONObject tr){if(playerArt==null)return;try{playerArt.setImageDrawable(null);long albumId=tr==null?0:tr.optLong("album_id",0);if(albumId>0)playerArt.setImageURI(android.net.Uri.parse("content://media/external/audio/albumart/"+albumId));}catch(Exception ignored){}}

'''
s=replace_between(s,'    private void landscapeMusic(){','    private void landscapeUtility(int target){',landscape_music,'landscape music/helpers')

load_route='''    private void loadRoute(){try{String raw=offline.route();if(raw.isEmpty())return;JSONObject j=new JSONObject(raw),r=j.optJSONObject("route"),g=r==null?null:r.optJSONObject("geometry");JSONArray c=g==null?null:g.optJSONArray("coordinates");if(c!=null){currentRouteCoords=c;currentRouteDistanceMeters=r==null?0:r.optDouble("distance",0);currentRouteDurationSeconds=r==null?0:r.optDouble("duration",0);currentDestination=j.optString("destination","");currentDestinationLat=j.optDouble("destination_lat",Double.NaN);currentDestinationLon=j.optDouble("destination_lon",Double.NaN);currentRouteSteps=j.optJSONArray("steps");if(currentRouteSteps==null)currentRouteSteps=new JSONArray();currentRouteMaxspeeds=j.optJSONArray("maxspeeds");if(currentRouteMaxspeeds==null)currentRouteMaxspeeds=new JSONArray();currentStepIndex=0;mapView.setRoute(c);JSONArray savedHazards=j.optJSONArray("radars");currentHazards=savedHazards==null?hazards():savedHazards;mapView.setRadars(currentHazards);}}catch(Exception ignored){}}
'''
s=replace_between(s,'    private void loadRoute(){','    private JSONArray hazards(){',load_route,'load route')
location_listener='''    private final LocationListener locationListener=new LocationListener(){@Override public void onLocationChanged(Location l){lastLocation=l;if(mapView!=null){mapView.setUserBearing(l.hasBearing()?l.getBearing():0f);mapView.setUserLocation(l.getLatitude(),l.getLongitude());}updateDriveGuidance(l);maybeMapboxReroute(l);}@Override public void onProviderEnabled(String p){}@Override public void onProviderDisabled(String p){}@Override public void onStatusChanged(String p,int st,Bundle e){}};
'''
s=replace_between(s,'    private final LocationListener locationListener=','    private final BroadcastReceiver playerReceiver=',location_listener,'location listener')
player_receiver='''    private final BroadcastReceiver playerReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){if(!PlaybackService.ACTION_STATE.equals(i.getAction()))return;try{JSONObject j=new JSONObject(i.getStringExtra(PlaybackService.EXTRA_STATE_JSON));JSONObject tr=j.optJSONObject("track");if(tr!=null){if(playerNow!=null)playerNow.setText(tr.optString("title","Música"));if(playerArtist!=null)playerArtist.setText(tr.optString("artist","MusicRoad"));updatePlayerArtwork(tr);}}catch(Exception ignored){}}};
'''
s=replace_between(s,'    private final BroadcastReceiver playerReceiver=','    private void registerPlayerReceiver(){',player_receiver,'player receiver')
main_path.write_text(s)

r=route_path.read_text()
r=require_replace(r,'String destinationLabel,double destinationLat,double destinationLon,boolean reroute);','String destinationLabel,double destinationLat,double destinationLon,boolean reroute,JSONArray steps,JSONArray maxspeeds);','route listener interface')
old='double distance=route.optDouble("distance",0d),duration=route.optDouble("duration",0d);\n                JSONArray finalCoords=coords;String finalLabel=label;\n                main.post(()->{inFlight.set(false);if(listener!=null)listener.onRoute(finalCoords,distance,duration,finalLabel,dlat,dlon,reroute);});'
new='double distance=route.optDouble("distance",0d),duration=route.optDouble("duration",0d);\n                JSONArray legs=route.optJSONArray("legs");JSONObject leg=legs==null?null:legs.optJSONObject(0);JSONArray steps=leg==null?null:leg.optJSONArray("steps");JSONObject annotation=leg==null?null:leg.optJSONObject("annotation");JSONArray maxspeeds=annotation==null?null:annotation.optJSONArray("maxspeed");if(steps==null)steps=new JSONArray();if(maxspeeds==null)maxspeeds=new JSONArray();\n                JSONArray finalCoords=coords,finalSteps=steps,finalMaxspeeds=maxspeeds;String finalLabel=label;\n                main.post(()->{inFlight.set(false);if(listener!=null)listener.onRoute(finalCoords,distance,duration,finalLabel,dlat,dlon,reroute,finalSteps,finalMaxspeeds);});'
r=require_replace(r,old,new,'route payload')
r=r.replace('&v=162','&v=220').replace('route_hazards.php?v=162','route_hazards.php?v=220')
route_path.write_text(r)

m=map_path.read_text()
m=require_replace(m,'import android.content.Context;\n','import android.content.Context;\nimport android.content.res.Configuration;\n','configuration import')
m=require_replace(m,'import com.mapbox.maps.CameraOptions;\n','import com.mapbox.maps.CameraOptions;\nimport com.mapbox.maps.EdgeInsets;\n','edge insets import')
old_camera='''    private void setCamera(double lat,double lon,double zoom,double pitch) {
        if (mapboxMap==null) return;
        try { mapboxMap.setCamera(new CameraOptions.Builder().center(Point.fromLngLat(lon,lat)).zoom(zoom).pitch(pitch).bearing(drivingMode?userBearing:0.0).build()); } catch(Throwable ignored){}
    }
'''
new_camera='''    private void setCamera(double lat,double lon,double zoom,double pitch) {
        if (mapboxMap==null) return;
        try {
            CameraOptions.Builder camera=new CameraOptions.Builder().center(Point.fromLngLat(lon,lat)).zoom(zoom).pitch(pitch).bearing(drivingMode?userBearing:0.0);
            if(drivingMode){boolean land=getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;camera.padding(new EdgeInsets(land?dp(168):dp(128),dp(18),land?dp(28):dp(74),dp(18)));}
            mapboxMap.setCamera(camera.build());
        } catch(Throwable ignored){}
    }
'''
m=require_replace(m,old_camera,new_camera,'camera padding')
map_path.write_text(m)

a=api_path.read_text()
a=require_replace(a,"'steps'=>'true','language'=>'pt-BR'","'steps'=>'true','annotations'=>'maxspeed','language'=>'pt-BR'",'maxspeed annotations')
a=a.replace("'version'=>'1.6.2'","'version'=>'2.2.0'")
api_path.write_text(a)
gradle_path.write_text(gradle_path.read_text().replace('versionCode 41','versionCode 42').replace("versionName '2.1.0'","versionName '2.2.0'"))

final_workflow='''# MusicRoad 2.2 DriveOS Cockpit — clean native build
name: Build MusicRoad Native APK

on:
  push:
    branches: [agent/native-apk-build]
    paths:
      - 'android-app-v2/**'
      - 'api/route_fast.php'
      - 'api/route_hazards.php'
      - '.github/workflows/build-native-apk.yml'
  pull_request:
    branches: [main]
    paths:
      - 'android-app-v2/**'
      - 'api/route_fast.php'
      - 'api/route_hazards.php'
      - '.github/workflows/build-native-apk.yml'
  workflow_dispatch:

jobs:
  build-debug-apk:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4
      - name: JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - name: Android SDK
        uses: android-actions/setup-android@v3
      - name: Install Android 35
        run: sdkmanager "platforms;android-35" "build-tools;35.0.0"
      - name: Gradle 8.9
        uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: '8.9'
      - name: Restore stable debug signing key
        uses: actions/cache@v4
        with:
          path: ~/.android/debug.keystore
          key: musicroad-stable-debug-signing-v1
      - name: Ensure stable debug signing key
        shell: bash
        run: |
          mkdir -p "$HOME/.android"
          if [ ! -f "$HOME/.android/debug.keystore" ]; then
            keytool -genkeypair -v -keystore "$HOME/.android/debug.keystore" -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=MusicRoad Stable Debug,O=MusicRoad,C=BR"
          fi
      - name: Verify MusicRoad 2.2 automotive architecture
        shell: bash
        run: |
          set -e
          grep -q "versionName '2.2.0'" android-app-v2/app/build.gradle
          grep -q 'versionCode 42' android-app-v2/app/build.gradle
          grep -q "applicationId 'com.musicroad.ai'" android-app-v2/app/build.gradle
          grep -q 'extends ComponentActivity' android-app-v2/app/src/main/java/com/musicroad/ai/MainActivity.java
          grep -q 'DriveGuidance' android-app-v2/app/src/main/java/com/musicroad/ai/MainActivity.java
          grep -q 'landscapeMap' android-app-v2/app/src/main/java/com/musicroad/ai/MainActivity.java
          grep -q 'landscapeMusic' android-app-v2/app/src/main/java/com/musicroad/ai/MainActivity.java
          grep -q 'FastMapboxRouteEngine' android-app-v2/app/src/main/java/com/musicroad/ai/MainActivity.java
          grep -q 'annotations.*maxspeed' api/route_fast.php
          grep -q 'setDrivingMode' android-app-v2/app/src/main/java/com/musicroad/ai/NativeMapView.java
          grep -q 'MediaStore' android-app-v2/app/src/main/java/com/musicroad/ai/NativeMusicRepository.java
          grep -q 'ACTION_PREVIOUS' android-app-v2/app/src/main/java/com/musicroad/ai/PlaybackService.java
          grep -q 'ACTION_NEXT' android-app-v2/app/src/main/java/com/musicroad/ai/PlaybackService.java
          grep -q 'BUMP_SEQUENCE_MILESTONES' android-app-v2/app/src/main/java/com/musicroad/ai/NavigationService.java
          if grep -R -q 'android.webkit.WebView\\|new WebView' android-app-v2/app/src/main/java; then echo 'WebView found in MusicRoad 2.2 source'; exit 1; fi
      - name: Build MusicRoad 2.2 APK
        working-directory: android-app-v2
        run: gradle --no-daemon clean assembleDebug
      - name: Upload MusicRoad 2.2 APK
        uses: actions/upload-artifact@v4
        with:
          name: MusicRoad-AI-native-2.2.0-DriveOS-Cockpit-apk
          path: android-app-v2/app/build/outputs/apk/debug/app-debug.apk
          if-no-files-found: error
'''
build_path.write_text(final_workflow)
for p in [Path('.driveos-2.2-migrate'),Path('.github/workflows/driveos-2.2-source-migration.yml')]:
    if p.exists(): p.unlink()
Path(__file__).unlink()
print('DriveOS 2.2 source migration complete')

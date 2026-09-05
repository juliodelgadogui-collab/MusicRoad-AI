from pathlib import Path
import re, shutil

ROOT=Path(__file__).resolve().parents[1]
APP=ROOT/'app'
JAVA=APP/'src/main/java/com/musicroad/ai'

# New native engines.
for name in ['MapboxNavigationEngine.java','MapboxOfflineRegionManager.java']:
    src=ROOT/'mapbox-v160'/name
    if not src.exists(): raise SystemExit('v1.6.0 missing '+name)
    shutil.copyfile(src,JAVA/name)

# Authenticated Mapbox Maven repository. Secret is build-time only and never enters the APK.
settings=ROOT/'settings.gradle'
s=settings.read_text()
if 'org.gradle.authentication.http.BasicAuthentication' not in s:
    s='import org.gradle.authentication.http.BasicAuthentication\n'+s
old="maven { url = uri('https://api.mapbox.com/downloads/v2/releases/maven') }"
new="""maven {
            url = uri('https://api.mapbox.com/downloads/v2/releases/maven')
            authentication { basic(BasicAuthentication) }
            credentials {
                username = 'mapbox'
                password = System.getenv('MAPBOX_DOWNLOADS_TOKEN') ?: ''
            }
        }"""
if old in s:s=s.replace(old,new,1)
elif 'MAPBOX_DOWNLOADS_TOKEN' not in s: raise SystemExit('v1.6.0 Mapbox Maven repository not found')
settings.write_text(s)

build=APP/'build.gradle'
b=build.read_text()
b=b.replace("com.mapbox.maps:android:11.28.3","com.mapbox.maps:android:11.27.0")
if "com.mapbox.navigationcore:android:3.27.0" not in b:
    if 'dependencies {' in b:b=b.replace('dependencies {',"dependencies {\n    implementation 'com.mapbox.navigationcore:android:3.27.0'",1)
    else:b += "\n\ndependencies {\n    implementation 'com.mapbox.navigationcore:android:3.27.0'\n}\n"
b=re.sub(r'versionCode\s+\d+','versionCode 36',b,count=1)
b=re.sub(r"versionName\s+'[^']+'","versionName '1.6.0'",b,count=1)
build.write_text(b)

# Make MapView use the exact same TileStore as Navigation SDK.
mapf=JAVA/'NativeMapView.java'
ms=mapf.read_text()
if 'import com.mapbox.maps.MapboxMapsOptions;' not in ms:
    ms=ms.replace('import com.mapbox.maps.MapboxMap;','import com.mapbox.maps.MapboxMap;\nimport com.mapbox.maps.MapboxMapsOptions;')
needle='''            MapboxOptions.INSTANCE.setAccessToken(token);\n            MapInitOptions initOptions=new MapInitOptions(getContext());'''
repl='''            MapboxOptions.INSTANCE.setAccessToken(token);\n            MapboxMapsOptions.setTileStore(MapboxNavigationEngine.sharedTileStore(getContext()));\n            MapInitOptions initOptions=new MapInitOptions(getContext());'''
if needle not in ms: raise SystemExit('v1.6.0 NativeMapView init point missing')
ms=ms.replace(needle,repl,1)
mapf.write_text(ms)

# Upgrade NavigationService into the persistent MusicRoad Hazard Engine for Mapbox reroutes.
navf=JAVA/'NavigationService.java'
ns=navf.read_text()
ns=ns.replace('public static final String ACTION_UPDATE="com.musicroad.ai.NAV_UPDATE";',
              'public static final String ACTION_UPDATE="com.musicroad.ai.NAV_UPDATE";\n    public static final String ACTION_ROUTE_CHANGED="com.musicroad.ai.NAV_ROUTE_CHANGED";',1)
needle='''    public static Intent updateIntent(Context c,String hazardsJson,String limitsJson){return new Intent(c,NavigationService.class).setAction(ACTION_UPDATE).putExtra(EXTRA_HAZARDS,hazardsJson).putExtra(EXTRA_LIMITS,limitsJson);}\n    public static Intent stopIntent'''
repl='''    public static Intent updateIntent(Context c,String hazardsJson,String limitsJson){return new Intent(c,NavigationService.class).setAction(ACTION_UPDATE).putExtra(EXTRA_HAZARDS,hazardsJson).putExtra(EXTRA_LIMITS,limitsJson);}\n    public static Intent routeChangedIntent(Context c,String routeJson,String hazardsJson,String destination){return new Intent(c,NavigationService.class).setAction(ACTION_ROUTE_CHANGED).putExtra(EXTRA_ROUTE,routeJson).putExtra(EXTRA_HAZARDS,hazardsJson).putExtra(EXTRA_DEST,destination);}\n    public static Intent stopIntent'''
if needle not in ns: raise SystemExit('v1.6.0 NavigationService intent point missing')
ns=ns.replace(needle,repl,1)
needle='''        if(ACTION_UPDATE.equals(action)){\n            parseHazards(intent.getStringExtra(EXTRA_HAZARDS));parseLimits(intent.getStringExtra(EXTRA_LIMITS));persistState();updateTripNotification("Fiscalização atualizada · "+hazards.size()+" pontos");return START_STICKY;\n        }'''
repl='''        if(ACTION_ROUTE_CHANGED.equals(action)){\n            destination=safe(intent.getStringExtra(EXTRA_DEST),destination);\n            parseRoute(intent.getStringExtra(EXTRA_ROUTE));parseHazards(intent.getStringExtra(EXTRA_HAZARDS));lastProgressM=0;offRouteSamples=0;suspendedOffRoute=false;persistState();updateTripNotification("Rota Mapbox recalculada · alertas reposicionados");return START_STICKY;\n        }\n        if(ACTION_UPDATE.equals(action)){\n            parseHazards(intent.getStringExtra(EXTRA_HAZARDS));parseLimits(intent.getStringExtra(EXTRA_LIMITS));persistState();updateTripNotification("Fiscalização atualizada · "+hazards.size()+" pontos");return START_STICKY;\n        }'''
if needle not in ns: raise SystemExit('v1.6.0 NavigationService action point missing')
ns=ns.replace(needle,repl,1)
navf.write_text(ns)

# MainActivity: Mapbox owns route/reroute; MusicRoad owns hazards and UI.
main=JAVA/'MainActivity.java'
m=main.read_text()
m=m.replace('private NativeMapView mapView;','private NativeMapView mapView;\n    private MapboxNavigationEngine mapboxNavigation;\n    private MapboxOfflineRegionManager mapboxOffline;',1)

oncreate='''prefs=getSharedPreferences(PREFS,MODE_PRIVATE);api=new NativeApiClient(this);offline=new OfflineStore(this);locationManager=(LocationManager)getSystemService(LOCATION_SERVICE);'''
listener='''prefs=getSharedPreferences(PREFS,MODE_PRIVATE);api=new NativeApiClient(this);offline=new OfflineStore(this);locationManager=(LocationManager)getSystemService(LOCATION_SERVICE);\n        mapboxNavigation=new MapboxNavigationEngine(this,api,server(),new MapboxNavigationEngine.Listener(){\n            @Override public void onEngineReady(){if(mapView!=null)mapView.setMessage("Mapbox Navigation pronto");}\n            @Override public void onRoute(JSONArray coords,double distance,double duration,String destination,boolean reroute){\n                currentRouteCoords=coords;currentDestination=destination;pendingDestination="";currentHazards=hazards();\n                try{JSONObject route=new JSONObject();JSONObject geom=new JSONObject();geom.put("coordinates",coords);route.put("geometry",geom);route.put("distance",distance);route.put("duration",duration);JSONObject saved=new JSONObject();saved.put("destination",destination);saved.put("route",route);offline.saveRoute(saved.toString());}catch(Exception ignored){}\n                if(mapView!=null){mapView.setRoute(coords);mapView.setRadars(currentHazards);mapView.fitRoute();mapView.setMessage("Mapbox Navigation");}\n                if(turnInstruction!=null)turnInstruction.setText(reroute?"Rota recalculada pelo Mapbox":"Rota Mapbox ativa");\n                if(hudRadar!=null)hudRadar.setText(nearestHazardText());\n                try{Intent i=reroute?NavigationService.routeChangedIntent(MainActivity.this,coords.toString(),currentHazards.toString(),destination):NavigationService.startIntent(MainActivity.this,coords.toString(),currentHazards.toString(),"[]",destination);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}catch(Exception ignored){}\n                toast((reroute?"Rota recalculada · ":"")+km(distance)+" · "+duration(duration));\n            }\n            @Override public void onError(String message){toast(message);if(mapView!=null)mapView.setMessage(message);}\n        });\n        mapboxOffline=new MapboxOfflineRegionManager(this,mapboxNavigation);'''
if oncreate not in m: raise SystemExit('v1.6.0 onCreate init point missing')
m=m.replace(oncreate,listener,1)

# Replace calc() from server OSRM with Mapbox Navigation SDK. Server remains only Mapbox geocoder.
pattern=r'''    private void calc\(String destination,Button b,TextView summary,View panel\)\{.*?\n    \}\n    private String routeInstruction'''
replacement=r'''    private void calc(String destination,Button b,TextView summary,View panel){
        if(lastLocation==null){toast("Aguardando localização GPS.");locate();return;}
        if(!online()){toast("Sem internet: o Mapbox tentará os dados offline já baixados.");}
        b.setEnabled(false);b.setText("…");
        final double nearLat=lastLocation.getLatitude(),nearLon=lastLocation.getLongitude();
        io.execute(()->{try{
            String routeDestination=destination,resolvedDestination=destination;double dlat=Double.NaN,dlon=Double.NaN;
            String[] rawParts=destination.split(",");
            if(rawParts.length==2)try{double la=Double.parseDouble(rawParts[0].trim().replace(',','.')),lo=Double.parseDouble(rawParts[1].trim().replace(',','.'));if(la>=-90&&la<=90&&lo>=-180&&lo<=180){dlat=la;dlon=lo;}}catch(Exception ignored){}
            if(!Double.isFinite(dlat)||!Double.isFinite(dlon)){
                if(!online()){ui.post(()->{b.setEnabled(true);b.setText("IR");toast("Para pesquisar um endereço novo é necessária internet. Rotas para coordenadas e áreas já baixadas continuam offline.");});return;}
                String geocodePath="api/native_app.php?action=geocode&q="+enc(destination)+"&lat="+String.format(Locale.US,"%.7f",nearLat)+"&lon="+String.format(Locale.US,"%.7f",nearLon)+"&limit=1";
                NativeApiClient.Response gr=api.get(server(),geocodePath);JSONObject gj=gr.json();JSONArray results=gj.optJSONArray("results");
                if(!gr.ok()||!gj.optBoolean("ok")||results==null||results.length()==0){String ge=gj.optString("error","Endereço não encontrado pelo Mapbox.");ui.post(()->{b.setEnabled(true);b.setText("IR");toast(ge);});return;}
                JSONObject best=results.optJSONObject(0);if(best==null)throw new Exception("Resultado de endereço inválido.");
                dlat=best.optDouble("latitude",Double.NaN);dlon=best.optDouble("longitude",Double.NaN);if(!Double.isFinite(dlat)||!Double.isFinite(dlon))throw new Exception("Coordenadas inválidas.");
                String label=best.optString("label",destination).trim();if(!label.isEmpty())resolvedDestination=label;
            }
            final double flat=dlat,flon=dlon;final String finalDestination=resolvedDestination;
            ui.post(()->{b.setEnabled(true);b.setText("↻");if(mapboxNavigation==null){toast("Mapbox Navigation não inicializado.");return;}mapboxNavigation.startTripSessionIfPermitted();mapboxNavigation.requestRoute(nearLat,nearLon,flat,flon,finalDestination);});
        }catch(Exception e){ui.post(()->{b.setEnabled(true);b.setText("IR");toast(err(e,"Falha ao preparar rota Mapbox."));});}});
    }
    private String routeInstruction'''
m2,n=re.subn(pattern,replacement,m,count=1,flags=re.S)
if n!=1: raise SystemExit(f'v1.6.0 calc replacement failed ({n})')
m=m2

# Stop button must clear Mapbox route too.
m=m.replace('stop.setOnClickListener(v->{currentRouteCoords=new JSONArray();currentHazards=new JSONArray();currentDestination="";offline.clearRoute();mapView.clearRoute();stopService(NavigationService.stopIntent(this));',
            'stop.setOnClickListener(v->{currentRouteCoords=new JSONArray();currentHazards=new JSONArray();currentDestination="";offline.clearRoute();if(mapboxNavigation!=null)mapboxNavigation.clearRoute();mapView.clearRoute();stopService(NavigationService.stopIntent(this));',1)

# Offline UI: Mapbox route/current-area tiles + MusicRoad hazards by state.
m=m.replace('Baixe seu estado para manter mapa, vias e fiscalização disponíveis no aparelho.',
            'Baixe a área da sua rota/posição pelo Mapbox e mantenha radares e quebra-molas do estado no aparelho.')
m=m.replace('Button map=btn("↓ BAIXAR MAPA",true),rad=btn("↓ BAIXAR RADARES",false),del=btn("EXCLUIR DADOS DO ESTADO",false);',
            'Button map=btn("↓ BAIXAR MAPBOX OFFLINE",true),rad=btn("↓ BAIXAR RADARES E QUEBRA-MOLAS",false),del=btn("EXCLUIR DADOS OFFLINE",false);')
m=m.replace('status.setText((offline.has("state/"+st.uf+"/map")?"Mapa ✓":"Mapa —")+" · "+(offline.has("state/"+st.uf+"/radars")?"Radares ✓":"Radares —"));',
            'status.setText((prefs.getBoolean("mapbox_offline_"+st.uf,false)?"Mapbox ✓":"Mapbox —")+" · "+(offline.has("state/"+st.uf+"/radars")?"Alertas ✓":"Alertas —"));')
m=m.replace('map.setOnClickListener(v->download((State)sp.getSelectedItem(),"map",map,status,update));',
            'map.setOnClickListener(v->downloadMapboxOffline((State)sp.getSelectedItem(),map,status,update));')
m=m.replace('del.setOnClickListener(v->{State st=(State)sp.getSelectedItem();offline.removePrefix("state/"+st.uf+"/");update.run();});',
            'del.setOnClickListener(v->{State st=(State)sp.getSelectedItem();offline.removePrefix("state/"+st.uf+"/");if(mapboxOffline!=null)mapboxOffline.remove("musicroad-"+st.uf,"mapbox://styles/mapbox/navigation-night-v1");prefs.edit().remove("mapbox_offline_"+st.uf).apply();update.run();});')
m=m.replace('note.addView(t("Mapa no aparelho",17,TEXT,true));note.addView(small("O servidor transmite os dados durante o download; o conteúdo offline permanece no armazenamento privado do MusicRoad."));',
            'note.addView(t("Mapbox Navigation offline",17,TEXT,true));note.addView(small("O mesmo TileStore guarda mapa e dados de navegação. Com uma rota ativa, baixa um corredor ao redor dela; sem rota, baixa a área próxima ao GPS. Os alertas MusicRoad são baixados separadamente por estado."));')

# Add Mapbox offline download method before old server download helper.
needle='''    private void download(State st,String kind,Button b,TextView status,Runnable done){'''
method='''    private void downloadMapboxOffline(State st,Button b,TextView status,Runnable done){
        if(mapboxOffline==null||mapboxNavigation==null||!mapboxNavigation.isReady()){toast("Mapbox Navigation ainda está inicializando.");return;}
        if(lastLocation==null&&currentRouteCoords.length()<2){toast("Aguarde o GPS ou calcule uma rota antes de baixar a área offline.");locate();return;}
        prefs.edit().putString(KEY_UF,st.uf).apply();b.setEnabled(false);b.setText("PREPARANDO MAPBOX…");
        double lat=lastLocation==null?Double.NaN:lastLocation.getLatitude(),lon=lastLocation==null?Double.NaN:lastLocation.getLongitude();
        mapboxOffline.download("musicroad-"+st.uf,"mapbox://styles/mapbox/navigation-night-v1",currentRouteCoords,lat,lon,new MapboxOfflineRegionManager.Listener(){
            @Override public void onProgress(long c,long r,String phase){ui.post(()->{long pct=r>0?Math.min(100,Math.round(c*100d/r)):0;status.setText("Mapbox "+phase+" · "+pct+"%");});}
            @Override public void onDone(){ui.post(()->{prefs.edit().putBoolean("mapbox_offline_"+st.uf,true).apply();b.setEnabled(true);b.setText("↓ BAIXAR MAPBOX OFFLINE");status.setText("Mapa + navegação offline salvos ✓");done.run();});}
            @Override public void onError(String msg){ui.post(()->{b.setEnabled(true);b.setText("↓ BAIXAR MAPBOX OFFLINE");status.setText(msg);});}
        });
    }
    private void download(State st,String kind,Button b,TextView status,Runnable done){'''
if needle not in m: raise SystemExit('v1.6.0 offline helper insertion point missing')
m=m.replace(needle,method,1)
main.write_text(m)

print('MusicRoad 1.6.0 Mapbox Navigation + offline routing applied')

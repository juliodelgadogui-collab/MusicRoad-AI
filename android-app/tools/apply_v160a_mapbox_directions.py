from pathlib import Path
import re, shutil

ROOT=Path(__file__).resolve().parents[1]
APP=ROOT/'app'
JAVA=APP/'src/main/java/com/musicroad/ai'

src=ROOT/'mapbox-v160a/MapboxDirectionsEngine.java'
if not src.exists(): raise SystemExit('v1.6.0A MapboxDirectionsEngine missing')
shutil.copyfile(src,JAVA/'MapboxDirectionsEngine.java')

build=APP/'build.gradle'
b=build.read_text()
b=re.sub(r'versionCode\s+\d+','versionCode 36',b,count=1)
b=re.sub(r"versionName\s+'[^']+'","versionName '1.6.0A'",b,count=1)
build.write_text(b)

# Keep NavigationService as the MusicRoad Hazard Engine, but allow a Mapbox reroute
# to replace the geometry without restarting all warning preferences/state.
navf=JAVA/'NavigationService.java'
ns=navf.read_text()
if 'ACTION_ROUTE_CHANGED' not in ns:
    ns=ns.replace('public static final String ACTION_UPDATE="com.musicroad.ai.NAV_UPDATE";',
                  'public static final String ACTION_UPDATE="com.musicroad.ai.NAV_UPDATE";\n    public static final String ACTION_ROUTE_CHANGED="com.musicroad.ai.NAV_ROUTE_CHANGED";',1)
    needle='''    public static Intent updateIntent(Context c,String hazardsJson,String limitsJson){return new Intent(c,NavigationService.class).setAction(ACTION_UPDATE).putExtra(EXTRA_HAZARDS,hazardsJson).putExtra(EXTRA_LIMITS,limitsJson);}\n    public static Intent stopIntent'''
    repl='''    public static Intent updateIntent(Context c,String hazardsJson,String limitsJson){return new Intent(c,NavigationService.class).setAction(ACTION_UPDATE).putExtra(EXTRA_HAZARDS,hazardsJson).putExtra(EXTRA_LIMITS,limitsJson);}\n    public static Intent routeChangedIntent(Context c,String routeJson,String hazardsJson,String destination){return new Intent(c,NavigationService.class).setAction(ACTION_ROUTE_CHANGED).putExtra(EXTRA_ROUTE,routeJson).putExtra(EXTRA_HAZARDS,hazardsJson).putExtra(EXTRA_DEST,destination);}\n    public static Intent stopIntent'''
    if needle not in ns: raise SystemExit('v1.6.0A NavigationService intent point missing')
    ns=ns.replace(needle,repl,1)
    needle='''        if(ACTION_UPDATE.equals(action)){\n            parseHazards(intent.getStringExtra(EXTRA_HAZARDS));parseLimits(intent.getStringExtra(EXTRA_LIMITS));persistState();updateTripNotification("Fiscalização atualizada · "+hazards.size()+" pontos");return START_STICKY;\n        }'''
    repl='''        if(ACTION_ROUTE_CHANGED.equals(action)){\n            destination=safe(intent.getStringExtra(EXTRA_DEST),destination);\n            parseRoute(intent.getStringExtra(EXTRA_ROUTE));parseHazards(intent.getStringExtra(EXTRA_HAZARDS));lastProgressM=0;offRouteSamples=0;suspendedOffRoute=false;persistState();updateTripNotification("Rota Mapbox recalculada · alertas reposicionados");return START_STICKY;\n        }\n        if(ACTION_UPDATE.equals(action)){\n            parseHazards(intent.getStringExtra(EXTRA_HAZARDS));parseLimits(intent.getStringExtra(EXTRA_LIMITS));persistState();updateTripNotification("Fiscalização atualizada · "+hazards.size()+" pontos");return START_STICKY;\n        }'''
    if needle not in ns: raise SystemExit('v1.6.0A NavigationService action point missing')
    ns=ns.replace(needle,repl,1)
navf.write_text(ns)

main=JAVA/'MainActivity.java'
m=main.read_text()

m=m.replace('private NativeMapView mapView;',
'''private NativeMapView mapView;\n    private MapboxDirectionsEngine mapboxDirections;\n    private Button activeRouteButton;\n    private double currentDestinationLat=Double.NaN,currentDestinationLon=Double.NaN;\n    private int mapboxOffRouteSamples=0;\n    private long lastMapboxRerouteAt=0;''',1)

oncreate='''prefs=getSharedPreferences(PREFS,MODE_PRIVATE);api=new NativeApiClient(this);offline=new OfflineStore(this);locationManager=(LocationManager)getSystemService(LOCATION_SERVICE);'''
listener='''prefs=getSharedPreferences(PREFS,MODE_PRIVATE);api=new NativeApiClient(this);offline=new OfflineStore(this);locationManager=(LocationManager)getSystemService(LOCATION_SERVICE);\n        mapboxDirections=new MapboxDirectionsEngine(this,api,server(),new MapboxDirectionsEngine.Listener(){\n            @Override public void onRoute(JSONArray coords,double distance,double durationSeconds,String destination,double destLat,double destLon,boolean reroute){\n                currentRouteCoords=coords;currentDestination=destination;currentDestinationLat=destLat;currentDestinationLon=destLon;pendingDestination="";currentHazards=hazards();mapboxOffRouteSamples=0;\n                try{JSONObject route=new JSONObject();JSONObject geom=new JSONObject();geom.put("coordinates",coords);route.put("geometry",geom);route.put("distance",distance);route.put("duration",durationSeconds);JSONObject saved=new JSONObject();saved.put("destination",destination);saved.put("destination_lat",destLat);saved.put("destination_lon",destLon);saved.put("route",route);offline.saveRoute(saved.toString());}catch(Exception ignored){}\n                if(activeRouteButton!=null){activeRouteButton.setEnabled(true);activeRouteButton.setText("↻");}\n                if(mapView!=null){mapView.setRoute(coords);mapView.setRadars(currentHazards);mapView.fitRoute();mapView.setMessage("Mapbox Directions");}\n                if(turnInstruction!=null)turnInstruction.setText(reroute?"Rota recalculada pelo Mapbox":"Rota Mapbox ativa");\n                if(hudRadar!=null)hudRadar.setText(nearestHazardText());\n                try{Intent i=reroute?NavigationService.routeChangedIntent(MainActivity.this,coords.toString(),currentHazards.toString(),destination):NavigationService.startIntent(MainActivity.this,coords.toString(),currentHazards.toString(),"[]",destination);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}catch(Exception ignored){}\n                toast((reroute?"Rota recalculada · ":"")+km(distance)+" · "+duration(durationSeconds));\n            }\n            @Override public void onError(String message){if(activeRouteButton!=null){activeRouteButton.setEnabled(true);activeRouteButton.setText(currentRouteCoords.length()>1?"↻":"IR");}toast(message);if(mapView!=null)mapView.setMessage("Mapbox: "+message);}\n        });'''
if oncreate not in m: raise SystemExit('v1.6.0A onCreate init point missing')
m=m.replace(oncreate,listener,1)

# Replace OSRM/server route calculation. Address lookup remains Mapbox geocoding on
# the MusicRoad server, while the route itself goes directly to Mapbox Directions.
pattern=r'''    private void calc\(String destination,Button b,TextView summary,View panel\)\{.*?\n    \}\n    private String routeInstruction'''
replacement=r'''    private void calc(String destination,Button b,TextView summary,View panel){
        if(lastLocation==null){toast("Aguardando localização GPS.");locate();return;}
        if(!online()){toast("A rota Mapbox precisa de internet nesta versão. Alertas baixados continuam disponíveis offline.");return;}
        activeRouteButton=b;b.setEnabled(false);b.setText("…");
        final double nearLat=lastLocation.getLatitude(),nearLon=lastLocation.getLongitude();
        io.execute(()->{try{
            double dlat=Double.NaN,dlon=Double.NaN;String resolvedDestination=destination;
            String[] rawParts=destination.split(",");
            if(rawParts.length==2)try{double la=Double.parseDouble(rawParts[0].trim().replace(',','.')),lo=Double.parseDouble(rawParts[1].trim().replace(',','.'));if(la>=-90&&la<=90&&lo>=-180&&lo<=180){dlat=la;dlon=lo;}}catch(Exception ignored){}
            if(!Double.isFinite(dlat)||!Double.isFinite(dlon)){
                String geocodePath="api/native_app.php?action=geocode&q="+enc(destination)+"&lat="+String.format(Locale.US,"%.7f",nearLat)+"&lon="+String.format(Locale.US,"%.7f",nearLon)+"&limit=1";
                NativeApiClient.Response gr=api.get(server(),geocodePath);JSONObject gj=gr.json();JSONArray results=gj.optJSONArray("results");
                if(!gr.ok()||!gj.optBoolean("ok")||results==null||results.length()==0){String ge=gj.optString("error","Endereço não encontrado pelo Mapbox.");ui.post(()->{b.setEnabled(true);b.setText("IR");toast(ge);});return;}
                JSONObject best=results.optJSONObject(0);if(best==null)throw new Exception("Resultado de endereço inválido.");
                dlat=best.optDouble("latitude",Double.NaN);dlon=best.optDouble("longitude",Double.NaN);if(!Double.isFinite(dlat)||!Double.isFinite(dlon))throw new Exception("Coordenadas inválidas.");
                String label=best.optString("label",destination).trim();if(!label.isEmpty())resolvedDestination=label;
            }
            final double flat=dlat,flon=dlon;final String finalDestination=resolvedDestination;
            ui.post(()->{if(mapboxDirections==null){b.setEnabled(true);b.setText("IR");toast("Mapbox Directions não inicializado.");return;}mapboxDirections.requestRoute(nearLat,nearLon,flat,flon,finalDestination,false);});
        }catch(Exception e){ui.post(()->{b.setEnabled(true);b.setText("IR");toast(err(e,"Falha ao preparar rota Mapbox."));});}});
    }
    private String routeInstruction'''
m2,n=re.subn(pattern,replacement,m,count=1,flags=re.S)
if n!=1: raise SystemExit(f'v1.6.0A calc replacement failed ({n})')
m=m2

# Restore destination coordinates when a saved Mapbox route is loaded.
old='''JSONObject j=new JSONObject(raw),r=j.optJSONObject("route"),g=r==null?null:r.optJSONObject("geometry");JSONArray c=g==null?null:g.optJSONArray("coordinates");if(c!=null){currentRouteCoords=c;currentDestination=j.optString("destination","");mapView.setRoute(c);currentHazards=hazards();mapView.setRadars(currentHazards);}'''
new='''JSONObject j=new JSONObject(raw),r=j.optJSONObject("route"),g=r==null?null:r.optJSONObject("geometry");JSONArray c=g==null?null:g.optJSONArray("coordinates");if(c!=null){currentRouteCoords=c;currentDestination=j.optString("destination","");currentDestinationLat=j.optDouble("destination_lat",Double.NaN);currentDestinationLon=j.optDouble("destination_lon",Double.NaN);mapView.setRoute(c);currentHazards=hazards();mapView.setRadars(currentHazards);}'''
if old in m:m=m.replace(old,new,1)

# On stop, clear destination coordinates too.
m=m.replace('currentRouteCoords=new JSONArray();currentHazards=new JSONArray();currentDestination="";offline.clearRoute();',
            'currentRouteCoords=new JSONArray();currentHazards=new JSONArray();currentDestination="";currentDestinationLat=Double.NaN;currentDestinationLon=Double.NaN;offline.clearRoute();',1)

# Foreground reroute: 3 consecutive samples outside the route corridor. The new
# route is again calculated by Mapbox and the Hazard Engine receives new geometry.
listener_old='''private final LocationListener locationListener=new LocationListener(){@Override public void onLocationChanged(Location l){lastLocation=l;if(mapView!=null)mapView.setUserLocation(l.getLatitude(),l.getLongitude());if(hudSpeed!=null)hudSpeed.setText(String.valueOf(Math.max(0,Math.round(l.getSpeed()*3.6f))));if(hudRadar!=null)hudRadar.setText(nearestHazardText());}@Override public void onProviderEnabled(String p){}@Override public void onProviderDisabled(String p){}@Override public void onStatusChanged(String p,int st,Bundle e){}};'''
listener_new='''private final LocationListener locationListener=new LocationListener(){@Override public void onLocationChanged(Location l){lastLocation=l;if(mapView!=null)mapView.setUserLocation(l.getLatitude(),l.getLongitude());if(hudSpeed!=null)hudSpeed.setText(String.valueOf(Math.max(0,Math.round(l.getSpeed()*3.6f))));if(hudRadar!=null)hudRadar.setText(nearestHazardText());maybeMapboxReroute(l);}@Override public void onProviderEnabled(String p){}@Override public void onProviderDisabled(String p){}@Override public void onStatusChanged(String p,int st,Bundle e){}};'''
if listener_old not in m: raise SystemExit('v1.6.0A location listener point missing')
m=m.replace(listener_old,listener_new,1)

# Insert route-corridor distance/reroute helpers before the LocationListener.
needle='''    private final LocationListener locationListener='''
helpers='''    private void maybeMapboxReroute(Location l){
        if(l==null||currentRouteCoords==null||currentRouteCoords.length()<2||!Double.isFinite(currentDestinationLat)||!Double.isFinite(currentDestinationLon)||mapboxDirections==null)return;
        double off=distanceToRouteMeters(l.getLatitude(),l.getLongitude(),currentRouteCoords);double threshold=Math.max(85d,l.hasAccuracy()?l.getAccuracy()*2.2d:85d);
        if(off>threshold)mapboxOffRouteSamples++;else mapboxOffRouteSamples=Math.max(0,mapboxOffRouteSamples-1);
        long now=System.currentTimeMillis();
        if(mapboxOffRouteSamples>=3&&online()&&!mapboxDirections.isBusy()&&now-lastMapboxRerouteAt>15000){mapboxOffRouteSamples=0;lastMapboxRerouteAt=now;if(turnInstruction!=null)turnInstruction.setText("Recalculando pelo Mapbox…");mapboxDirections.requestRoute(l.getLatitude(),l.getLongitude(),currentDestinationLat,currentDestinationLon,currentDestination,true);}
    }
    private double distanceToRouteMeters(double lat,double lon,JSONArray coords){
        double best=Double.MAX_VALUE,cos=Math.cos(Math.toRadians(lat));
        for(int i=1;i<coords.length();i++){JSONArray a=coords.optJSONArray(i-1),b=coords.optJSONArray(i);if(a==null||b==null||a.length()<2||b.length()<2)continue;double x1=(a.optDouble(0)-lon)*111320d*cos,y1=(a.optDouble(1)-lat)*110540d,x2=(b.optDouble(0)-lon)*111320d*cos,y2=(b.optDouble(1)-lat)*110540d,dx=x2-x1,dy=y2-y1,den=dx*dx+dy*dy,t=den<=1e-6?0d:-(x1*dx+y1*dy)/den;t=Math.max(0d,Math.min(1d,t));double x=x1+t*dx,y=y1+t*dy;best=Math.min(best,Math.sqrt(x*x+y*y));}
        return best==Double.MAX_VALUE?999999d:best;
    }

    private final LocationListener locationListener='''
if needle not in m: raise SystemExit('v1.6.0A helper insertion point missing')
m=m.replace(needle,helpers,1)

# Version/about copy makes it explicit that routing is Mapbox, while full offline
# routing remains a later SDK upgrade rather than silently falling back to OSRM.
m=m.replace('about.addView(small("Aplicativo Android nativo · sem WebView"));',
            'about.addView(small("Aplicativo Android nativo · rota online 100% Mapbox · sem WebView"));',1)

# Clean engine thread on Activity destruction when the method exists; otherwise add one.
if 'mapboxDirections.shutdown()' not in m:
    marker='''    @Override protected void onDestroy(){'''
    if marker in m:m=m.replace(marker,marker+'if(mapboxDirections!=null)mapboxDirections.shutdown();',1)

main.write_text(m)
print('MusicRoad 1.6.0A Mapbox Directions + Hazard Engine applied')

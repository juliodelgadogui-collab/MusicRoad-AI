from pathlib import Path
import re, shutil

ROOT=Path(__file__).resolve().parents[1]
APP=ROOT/'app'
JAVA=APP/'src/main/java/com/musicroad/ai'

src=ROOT/'mapbox-v161/ServerMapboxRouteEngine.java'
if not src.exists(): raise SystemExit('v1.6.1 ServerMapboxRouteEngine missing')
shutil.copyfile(src,JAVA/'ServerMapboxRouteEngine.java')

build=APP/'build.gradle'
b=build.read_text()
b=re.sub(r'versionCode\s+\d+','versionCode 37',b,count=1)
b=re.sub(r"versionName\s+'[^']+'","versionName '1.6.1'",b,count=1)
build.write_text(b)

main=JAVA/'MainActivity.java'
m=main.read_text()

m=m.replace('private MapboxDirectionsEngine mapboxDirections;', 'private ServerMapboxRouteEngine mapboxRoute;', 1)

old_listener='''prefs=getSharedPreferences(PREFS,MODE_PRIVATE);api=new NativeApiClient(this);offline=new OfflineStore(this);locationManager=(LocationManager)getSystemService(LOCATION_SERVICE);\n        mapboxDirections=new MapboxDirectionsEngine(this,api,server(),new MapboxDirectionsEngine.Listener(){\n            @Override public void onRoute(JSONArray coords,double distance,double durationSeconds,String destination,double destLat,double destLon,boolean reroute){\n                currentRouteCoords=coords;currentDestination=destination;currentDestinationLat=destLat;currentDestinationLon=destLon;pendingDestination="";currentHazards=hazards();mapboxOffRouteSamples=0;\n                try{JSONObject route=new JSONObject();JSONObject geom=new JSONObject();geom.put("coordinates",coords);route.put("geometry",geom);route.put("distance",distance);route.put("duration",durationSeconds);JSONObject saved=new JSONObject();saved.put("destination",destination);saved.put("destination_lat",destLat);saved.put("destination_lon",destLon);saved.put("route",route);offline.saveRoute(saved.toString());}catch(Exception ignored){}\n                if(activeRouteButton!=null){activeRouteButton.setEnabled(true);activeRouteButton.setText("↻");}\n                if(mapView!=null){mapView.setRoute(coords);mapView.setRadars(currentHazards);mapView.fitRoute();mapView.setMessage("Mapbox Directions");}\n                if(turnInstruction!=null)turnInstruction.setText(reroute?"Rota recalculada pelo Mapbox":"Rota Mapbox ativa");\n                if(hudRadar!=null)hudRadar.setText(nearestHazardText());\n                try{Intent i=reroute?NavigationService.routeChangedIntent(MainActivity.this,coords.toString(),currentHazards.toString(),destination):NavigationService.startIntent(MainActivity.this,coords.toString(),currentHazards.toString(),"[]",destination);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}catch(Exception ignored){}\n                toast((reroute?"Rota recalculada · ":"")+km(distance)+" · "+duration(durationSeconds));\n            }\n            @Override public void onError(String message){if(activeRouteButton!=null){activeRouteButton.setEnabled(true);activeRouteButton.setText(currentRouteCoords.length()>1?"↻":"IR");}toast(message);if(mapView!=null)mapView.setMessage("Mapbox: "+message);}\n        });'''
new_listener='''prefs=getSharedPreferences(PREFS,MODE_PRIVATE);api=new NativeApiClient(this);offline=new OfflineStore(this);locationManager=(LocationManager)getSystemService(LOCATION_SERVICE);\n        mapboxRoute=new ServerMapboxRouteEngine(this,api,server(),new ServerMapboxRouteEngine.Listener(){\n            @Override public void onRoute(JSONArray coords,JSONArray routeHazards,double distance,double durationSeconds,String destination,double destLat,double destLon,boolean reroute){\n                currentRouteCoords=coords;currentDestination=destination;currentDestinationLat=destLat;currentDestinationLon=destLon;pendingDestination="";currentHazards=routeHazards==null?new JSONArray():routeHazards;mapboxOffRouteSamples=0;\n                try{JSONObject route=new JSONObject();JSONObject geom=new JSONObject();geom.put("coordinates",coords);route.put("geometry",geom);route.put("distance",distance);route.put("duration",durationSeconds);JSONObject saved=new JSONObject();saved.put("destination",destination);saved.put("destination_lat",destLat);saved.put("destination_lon",destLon);saved.put("route",route);saved.put("radars",currentHazards);offline.saveRoute(saved.toString());}catch(Exception ignored){}\n                if(activeRouteButton!=null){activeRouteButton.setEnabled(true);activeRouteButton.setText("↻");}\n                if(mapView!=null){mapView.setRoute(coords);mapView.setRadars(currentHazards);mapView.fitRoute();mapView.setMessage("Mapbox · rota validada pelo servidor");}\n                if(turnInstruction!=null)turnInstruction.setText(reroute?"Rota recalculada pelo Mapbox":"Rota Mapbox ativa");\n                if(hudRadar!=null)hudRadar.setText(nearestHazardText());\n                try{Intent i=reroute?NavigationService.routeChangedIntent(MainActivity.this,coords.toString(),currentHazards.toString(),destination):NavigationService.startIntent(MainActivity.this,coords.toString(),currentHazards.toString(),"[]",destination);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}catch(Exception ignored){}\n                toast((reroute?"Rota recalculada · ":"")+km(distance)+" · "+duration(durationSeconds)+" · "+currentHazards.length()+" alertas");\n            }\n            @Override public void onError(String message){if(activeRouteButton!=null){activeRouteButton.setEnabled(true);activeRouteButton.setText(currentRouteCoords.length()>1?"↻":"IR");}toast(message);if(mapView!=null)mapView.setMessage("Mapbox: "+message);}\n        });'''
if old_listener not in m: raise SystemExit('v1.6.1 route listener block not found')
m=m.replace(old_listener,new_listener,1)

pattern=r'''    private void calc\(String destination,Button b,TextView summary,View panel\)\{.*?\n    \}\n    private String routeInstruction'''
replacement=r'''    private void calc(String destination,Button b,TextView summary,View panel){
        if(lastLocation==null){toast("Aguardando localização GPS.");locate();return;}
        if(!online()){toast("A rota Mapbox precisa de internet nesta versão. Alertas baixados continuam disponíveis offline.");return;}
        activeRouteButton=b;b.setEnabled(false);b.setText("…");
        if(mapboxRoute==null){b.setEnabled(true);b.setText("IR");toast("Motor de rota Mapbox não inicializado.");return;}
        mapboxRoute.requestRoute(lastLocation.getLatitude(),lastLocation.getLongitude(),destination,destination,false);
    }
    private String routeInstruction'''
m2,n=re.subn(pattern,replacement,m,count=1,flags=re.S)
if n!=1: raise SystemExit(f'v1.6.1 calc replacement failed ({n})')
m=m2

old_reroute='''    private void maybeMapboxReroute(Location l){
        if(l==null||currentRouteCoords==null||currentRouteCoords.length()<2||!Double.isFinite(currentDestinationLat)||!Double.isFinite(currentDestinationLon)||mapboxDirections==null)return;
        double off=distanceToRouteMeters(l.getLatitude(),l.getLongitude(),currentRouteCoords);double threshold=Math.max(85d,l.hasAccuracy()?l.getAccuracy()*2.2d:85d);
        if(off>threshold)mapboxOffRouteSamples++;else mapboxOffRouteSamples=Math.max(0,mapboxOffRouteSamples-1);
        long now=System.currentTimeMillis();
        if(mapboxOffRouteSamples>=3&&online()&&!mapboxDirections.isBusy()&&now-lastMapboxRerouteAt>15000){mapboxOffRouteSamples=0;lastMapboxRerouteAt=now;if(turnInstruction!=null)turnInstruction.setText("Recalculando pelo Mapbox…");mapboxDirections.requestRoute(l.getLatitude(),l.getLongitude(),currentDestinationLat,currentDestinationLon,currentDestination,true);}
    }'''
new_reroute='''    private void maybeMapboxReroute(Location l){
        if(l==null||currentRouteCoords==null||currentRouteCoords.length()<2||!Double.isFinite(currentDestinationLat)||!Double.isFinite(currentDestinationLon)||mapboxRoute==null)return;
        double off=distanceToRouteMeters(l.getLatitude(),l.getLongitude(),currentRouteCoords);double threshold=Math.max(85d,l.hasAccuracy()?l.getAccuracy()*2.2d:85d);
        if(off>threshold)mapboxOffRouteSamples++;else mapboxOffRouteSamples=Math.max(0,mapboxOffRouteSamples-1);
        long now=System.currentTimeMillis();
        if(mapboxOffRouteSamples>=3&&online()&&!mapboxRoute.isBusy()&&now-lastMapboxRerouteAt>15000){
            mapboxOffRouteSamples=0;lastMapboxRerouteAt=now;if(turnInstruction!=null)turnInstruction.setText("Recalculando pelo Mapbox…");
            String target=String.format(Locale.US,"%.7f,%.7f",currentDestinationLat,currentDestinationLon);
            mapboxRoute.requestRoute(l.getLatitude(),l.getLongitude(),target,currentDestination,true);
        }
    }'''
if old_reroute not in m: raise SystemExit('v1.6.1 reroute block not found')
m=m.replace(old_reroute,new_reroute,1)

m=m.replace('if(mapboxDirections!=null)mapboxDirections.shutdown();','if(mapboxRoute!=null)mapboxRoute.shutdown();',1)

# Navigation screen owns its own header. Avoid stacking the global header above it.
old='if(!landscape()||screen!=MAP)main.addView(top(),new LinearLayout.LayoutParams(-1,dp(80)));'
if old not in m: raise SystemExit('v1.6.1 shell header condition not found')
m=m.replace(old,'if(screen!=MAP)main.addView(top(),new LinearLayout.LayoutParams(-1,dp(80)));',1)

# Reserve real map space for Mapbox attribution/logo and bottom controls.
old='locate();FrameLayout stage=new FrameLayout(this);content.addView(stage,new FrameLayout.LayoutParams(-1,-1));mapView=new NativeMapView(this);stage.addView(mapView,new FrameLayout.LayoutParams(-1,-1));loadMap();loadRoute();if(lastLocation!=null)mapView.setUserLocation(lastLocation.getLatitude(),lastLocation.getLongitude());'
new='locate();FrameLayout stage=new FrameLayout(this);content.addView(stage,new FrameLayout.LayoutParams(-1,-1));mapView=new NativeMapView(this);FrameLayout.LayoutParams mapLp=new FrameLayout.LayoutParams(-1,-1);mapLp.topMargin=dp(70);mapLp.bottomMargin=dp(118);stage.addView(mapView,mapLp);loadMap();loadRoute();if(lastLocation!=null)mapView.setUserLocation(lastLocation.getLatitude(),lastLocation.getLongitude());'
if old not in m: raise SystemExit('v1.6.1 map layout point not found')
m=m.replace(old,new,1)

m=m.replace('hp.setMargins(dp(18),0,landscape()?0:dp(18),dp(landscape()?18:176));','hp.setMargins(dp(18),0,landscape()?0:dp(18),dp(landscape()?18:126));',1)
m=m.replace('pp.setMargins(dp(18),0,dp(18),dp(66));','pp.setMargins(dp(18),0,dp(18),dp(18));',1)

# Saved route should restore the exact route-filtered alert set returned by the server.
old='''JSONObject j=new JSONObject(raw),r=j.optJSONObject("route"),g=r==null?null:r.optJSONObject("geometry");JSONArray c=g==null?null:g.optJSONArray("coordinates");if(c!=null){currentRouteCoords=c;currentDestination=j.optString("destination","");currentDestinationLat=j.optDouble("destination_lat",Double.NaN);currentDestinationLon=j.optDouble("destination_lon",Double.NaN);mapView.setRoute(c);currentHazards=hazards();mapView.setRadars(currentHazards);}'''
new='''JSONObject j=new JSONObject(raw),r=j.optJSONObject("route"),g=r==null?null:r.optJSONObject("geometry");JSONArray c=g==null?null:g.optJSONArray("coordinates");if(c!=null){currentRouteCoords=c;currentDestination=j.optString("destination","");currentDestinationLat=j.optDouble("destination_lat",Double.NaN);currentDestinationLon=j.optDouble("destination_lon",Double.NaN);mapView.setRoute(c);JSONArray savedHazards=j.optJSONArray("radars");currentHazards=savedHazards==null?hazards():savedHazards;mapView.setRadars(currentHazards);}'''
if old in m: m=m.replace(old,new,1)

main.write_text(m)

mapf=JAVA/'NativeMapView.java'
ms=mapf.read_text()
ms=ms.replace('double zoom=Math.log(360.0/span)/Math.log(2.0)-1.45;', 'double zoom=Math.log(360.0/span)/Math.log(2.0)-1.95;', 1)
ms=ms.replace('cameraInitialized=true; setCamera(lat,lon,zoom,route.size()>2?34.0:0.0);','cameraInitialized=true; setCamera(lat,lon,zoom,0.0);',1)
mapf.write_text(ms)

print('MusicRoad 1.6.1 unified Mapbox route + navigation layout fix applied')

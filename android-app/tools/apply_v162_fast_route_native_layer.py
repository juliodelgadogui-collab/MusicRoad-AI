from pathlib import Path
import re, shutil

ROOT=Path(__file__).resolve().parents[1]
APP=ROOT/'app'
JAVA=APP/'src/main/java/com/musicroad/ai'

src=ROOT/'mapbox-v162/FastMapboxRouteEngine.java'
if not src.exists(): raise SystemExit('v1.6.2 FastMapboxRouteEngine missing')
shutil.copyfile(src,JAVA/'FastMapboxRouteEngine.java')

build=APP/'build.gradle'
b=build.read_text()
b=re.sub(r'versionCode\s+\d+','versionCode 38',b,count=1)
b=re.sub(r"versionName\s+'[^']+'","versionName '1.6.2'",b,count=1)
build.write_text(b)

main=JAVA/'MainActivity.java'
m=main.read_text()
m=m.replace('private ServerMapboxRouteEngine mapboxRoute;','private FastMapboxRouteEngine mapboxRoute;',1)

pattern=r'''        mapboxRoute=new ServerMapboxRouteEngine\(this,api,server\(\),new ServerMapboxRouteEngine\.Listener\(\)\{.*?\n        \}\);'''
replacement='''        mapboxRoute=new FastMapboxRouteEngine(this,api,server(),new FastMapboxRouteEngine.Listener(){
            @Override public void onRoute(JSONArray coords,double distance,double durationSeconds,String destination,double destLat,double destLon,boolean reroute){
                currentRouteCoords=coords;currentDestination=destination;currentDestinationLat=destLat;currentDestinationLon=destLon;pendingDestination="";currentHazards=new JSONArray();mapboxOffRouteSamples=0;
                try{JSONObject route=new JSONObject();JSONObject geom=new JSONObject();geom.put("coordinates",coords);route.put("geometry",geom);route.put("distance",distance);route.put("duration",durationSeconds);JSONObject saved=new JSONObject();saved.put("destination",destination);saved.put("destination_lat",destLat);saved.put("destination_lon",destLon);saved.put("route",route);saved.put("radars",currentHazards);offline.saveRoute(saved.toString());}catch(Exception ignored){}
                if(activeRouteButton!=null){activeRouteButton.setEnabled(true);activeRouteButton.setText("↻");}
                if(mapView!=null){mapView.setRoute(coords);mapView.setRadars(currentHazards);mapView.fitRoute();mapView.setMessage("Mapbox · rota pronta");}
                if(turnInstruction!=null)turnInstruction.setText(reroute?"Rota recalculada pelo Mapbox":"Rota Mapbox ativa");
                if(hudRadar!=null)hudRadar.setText("--");
                try{Intent i=reroute?NavigationService.routeChangedIntent(MainActivity.this,coords.toString(),currentHazards.toString(),destination):NavigationService.startIntent(MainActivity.this,coords.toString(),currentHazards.toString(),"[]",destination);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}catch(Exception ignored){}
                toast((reroute?"Rota recalculada · ":"")+km(distance)+" · "+duration(durationSeconds));
            }
            @Override public void onHazards(JSONArray hazards){
                currentHazards=hazards==null?new JSONArray():hazards;
                if(mapView!=null)mapView.setRadars(currentHazards);
                if(hudRadar!=null)hudRadar.setText(nearestHazardText());
                try{startService(NavigationService.updateIntent(MainActivity.this,currentHazards.toString(),"[]"));}catch(Exception ignored){}
            }
            @Override public void onError(String message){if(activeRouteButton!=null){activeRouteButton.setEnabled(true);activeRouteButton.setText(currentRouteCoords.length()>1?"↻":"IR");}toast(message);if(mapView!=null)mapView.setMessage("Mapbox: "+message);}
        });'''
m2,n=re.subn(pattern,replacement,m,count=1,flags=re.S)
if n!=1: raise SystemExit(f'v1.6.2 listener replacement failed ({n})')
m=m2
main.write_text(m)

mapf=JAVA/'NativeMapView.java'
s=mapf.read_text()
if 'import com.mapbox.bindgen.Value;' not in s:
    marker='import com.mapbox.geojson.Point;'
    if marker not in s: raise SystemExit('v1.6.2 Mapbox import point missing')
    s=s.replace(marker,'import com.mapbox.bindgen.Value;\n'+marker,1)
if 'import java.util.HashMap;' not in s:
    s=s.replace('import java.util.ArrayList;','import java.util.ArrayList;\nimport java.util.HashMap;',1)

s=s.replace('private boolean mapboxLoaded = false, mapboxFallbackTried = false;',
            'private boolean mapboxLoaded = false, mapboxFallbackTried = false, nativeRouteLayerReady = false;',1)

old='void clearRoute() { route.clear(); fallback.fitData(); overlay.invalidate(); fallback.invalidate(); }'
new='void clearRoute() { route.clear(); renderNativeRoute(); fallback.fitData(); overlay.invalidate(); fallback.invalidate(); }'
if old not in s: raise SystemExit('v1.6.2 clearRoute point missing')
s=s.replace(old,new,1)

old='fallback.fitData(); fallback.invalidate(); overlay.invalidate();\n    }\n\n    void setRadars'
new='renderNativeRoute(); fallback.fitData(); fallback.invalidate(); overlay.invalidate();\n    }\n\n    void setRadars'
if old not in s: raise SystemExit('v1.6.2 setRoute tail missing')
s=s.replace(old,new,1)

old='''        fallback.setVisibility(View.GONE);
        mapView.animate().alpha(1f).setDuration(220).start();
        if(!route.isEmpty())fitRoute();'''
new='''        fallback.setVisibility(View.GONE);
        mapView.animate().alpha(1f).setDuration(220).start();
        renderNativeRoute();
        if(!route.isEmpty())fitRoute();'''
if old not in s: raise SystemExit('v1.6.2 activateMapbox point missing')
s=s.replace(old,new,1)

needle='''    private final class OverlayView extends View {'''
methods=r'''    private String routeGeoJson(){
        try{
            JSONArray cc=new JSONArray();for(double[]p:route){JSONArray q=new JSONArray();q.put(p[1]);q.put(p[0]);cc.put(q);}
            JSONObject fc=new JSONObject();fc.put("type","FeatureCollection");JSONArray ff=new JSONArray();
            if(cc.length()>=2){JSONObject g=new JSONObject();g.put("type","LineString");g.put("coordinates",cc);JSONObject f=new JSONObject();f.put("type","Feature");f.put("properties",new JSONObject());f.put("geometry",g);ff.put(f);}fc.put("features",ff);return fc.toString();
        }catch(Exception e){return "{\"type\":\"FeatureCollection\",\"features\":[]}";}
    }

    private Value routeLayerValue(String id,String color,double width,double opacity){
        HashMap<String,Value> paint=new HashMap<>();paint.put("line-color",new Value(color));paint.put("line-width",new Value(width));paint.put("line-opacity",new Value(opacity));
        HashMap<String,Value> layout=new HashMap<>();layout.put("line-cap",new Value("round"));layout.put("line-join",new Value("round"));
        HashMap<String,Value> layer=new HashMap<>();layer.put("id",new Value(id));layer.put("type",new Value("line"));layer.put("source",new Value("musicroad-route-source"));layer.put("paint",new Value(paint));layer.put("layout",new Value(layout));return new Value(layer);
    }

    private void renderNativeRoute(){
        if(mapboxMap==null||!mapboxLoaded)return;
        try{
            String data=routeGeoJson();
            if(!mapboxMap.styleSourceExists("musicroad-route-source")){
                HashMap<String,Value> src=new HashMap<>();src.put("type",new Value("geojson"));src.put("data",new Value(data));mapboxMap.addStyleSource("musicroad-route-source",new Value(src));
            }else mapboxMap.setStyleSourceProperty("musicroad-route-source","data",new Value(data));
            if(!mapboxMap.styleLayerExists("musicroad-route-shadow"))mapboxMap.addStyleLayer(routeLayerValue("musicroad-route-shadow","#6F2DFF",11.0,0.88),null);
            if(!mapboxMap.styleLayerExists("musicroad-route-line"))mapboxMap.addStyleLayer(routeLayerValue("musicroad-route-line","#FF7A1A",6.0,1.0),null);
            nativeRouteLayerReady=true;
        }catch(Throwable e){nativeRouteLayerReady=false;}
        overlay.invalidate();
    }

    private final class OverlayView extends View {'''
if needle not in s: raise SystemExit('v1.6.2 overlay insertion point missing')
s=s.replace(needle,methods,1)

old='drawRoute(c,routeShadow);drawRoute(c,routePaint);'
if old not in s: raise SystemExit('v1.6.2 overlay route draw point missing')
s=s.replace(old,'if(!nativeRouteLayerReady){drawRoute(c,routeShadow);drawRoute(c,routePaint);}',1)
mapf.write_text(s)
print('MusicRoad 1.6.2 fast Mapbox route + async hazards + native route layer applied')

from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app'
JAVA = APP / 'src/main/java/com/musicroad/ai'

# MusicRoad 1.5.6: use only the already-validated native_app.php endpoint.
map_file = JAVA / 'NativeMapView.java'
map_src = map_file.read_text()
for old in ['base+"mapbox_config.php"', 'base+"api/mapbox_config.php"']:
    map_src = map_src.replace(old, 'base+"api/native_app.php?action=mapbox_config"')
if 'api/native_app.php?action=mapbox_config' not in map_src:
    raise SystemExit('MusicRoad 1.5.6: Mapbox config endpoint not applied')
map_file.write_text(map_src)

main = JAVA / 'MainActivity.java'
m = main.read_text()

pattern = r'''    private void calc\(String destination,Button b,TextView summary,View panel\)\{.*?\n    \}\n    private String routeInstruction'''
replacement = r'''    private void calc(String destination,Button b,TextView summary,View panel){
        if(!online()){toast("Sem internet para calcular uma rota nova. O mapa e a última rota continuam offline.");return;}
        b.setEnabled(false);b.setText("…");
        String origin=String.format(Locale.US,"%.7f,%.7f",lastLocation.getLatitude(),lastLocation.getLongitude());
        final double nearLat=lastLocation.getLatitude(),nearLon=lastLocation.getLongitude();
        io.execute(()->{try{
            String routeDestination=destination;
            String resolvedDestination=destination;
            boolean coordinates=false;
            String[] rawParts=destination.split(",");
            if(rawParts.length==2){
                try{
                    double la=Double.parseDouble(rawParts[0].trim().replace(',','.'));
                    double lo=Double.parseDouble(rawParts[1].trim().replace(',','.'));
                    if(la>=-90&&la<=90&&lo>=-180&&lo<=180){routeDestination=String.format(Locale.US,"%.7f,%.7f",la,lo);coordinates=true;}
                }catch(Exception ignored){}
            }
            if(!coordinates){
                String geocodePath="api/native_app.php?action=geocode&q="+enc(destination)+"&lat="+String.format(Locale.US,"%.7f",nearLat)+"&lon="+String.format(Locale.US,"%.7f",nearLon)+"&limit=1";
                NativeApiClient.Response gr=api.get(server(),geocodePath);
                JSONObject gj=gr.json();
                JSONArray results=gj.optJSONArray("results");
                if(!gr.ok()||!gj.optBoolean("ok")||results==null||results.length()==0){
                    String ge=gj.optString("error","Endereço não encontrado pelo Mapbox.");
                    ui.post(()->{b.setEnabled(true);b.setText("IR");toast(ge);});return;
                }
                JSONObject best=results.optJSONObject(0);
                if(best==null)throw new Exception("Resultado de endereço inválido.");
                double dlat=best.optDouble("latitude",Double.NaN),dlon=best.optDouble("longitude",Double.NaN);
                if(!Double.isFinite(dlat)||!Double.isFinite(dlon))throw new Exception("Coordenadas do endereço inválidas.");
                routeDestination=String.format(Locale.US,"%.7f,%.7f",dlat,dlon);
                String label=best.optString("label",destination).trim();
                if(!label.isEmpty())resolvedDestination=label;
            }
            final String finalDestination=resolvedDestination;
            NativeApiClient.Response r=api.get(server(),"api/route.php?origin="+enc(origin)+"&destination="+enc(routeDestination));
            JSONObject j=r.json();
            if(!r.ok()||!j.optBoolean("ok")){ui.post(()->{b.setEnabled(true);b.setText("IR");toast(j.optString("error","Falha na rota."));});return;}
            JSONObject route=j.optJSONObject("route"),geom=route==null?null:route.optJSONObject("geometry");
            JSONArray coords=geom==null?null:geom.optJSONArray("coordinates");
            if(coords==null||coords.length()<2)throw new Exception("Rota sem geometria.");
            JSONObject saved=new JSONObject();saved.put("destination",finalDestination);saved.put("route",route);offline.saveRoute(saved.toString());
            JSONArray hz=hazards();currentRouteCoords=coords;currentHazards=hz;currentDestination=finalDestination;pendingDestination="";
            ui.post(()->{b.setEnabled(true);b.setText("↻");mapView.setRoute(coords);mapView.setRadars(hz);mapView.fitRoute();if(turnInstruction!=null)turnInstruction.setText(routeInstruction(route));if(hudRadar!=null)hudRadar.setText(nearestHazardText());toast(km(route.optDouble("distance",0))+" · "+duration(route.optDouble("duration",0)));try{Intent i=NavigationService.startIntent(this,coords.toString(),hz.toString(),"[]",finalDestination);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}catch(Exception ignored){}});
        }catch(Exception e){ui.post(()->{b.setEnabled(true);b.setText("IR");toast(err(e,"Falha ao calcular rota."));});}});
    }
    private String routeInstruction'''

m2,n = re.subn(pattern,replacement,m,count=1,flags=re.S)
if n != 1:
    raise SystemExit(f'MusicRoad 1.5.6: calc replacement failed ({n})')
main.write_text(m2)

build = APP / 'build.gradle'
b = build.read_text()
b = re.sub(r'versionCode\s+\d+', 'versionCode 32', b, count=1)
b = re.sub(r"versionName\s+'[^']+'", "versionName '1.5.6'", b, count=1)
build.write_text(b)

print('MusicRoad 1.5.6 Mapbox server geocoding applied')

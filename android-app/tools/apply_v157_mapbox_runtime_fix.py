from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app'
JAVA = APP / 'src/main/java/com/musicroad/ai'
map_file = JAVA / 'NativeMapView.java'
s = map_file.read_text()

# Runtime state so the fallback is only hidden after a Mapbox style really loads.
s = s.replace(
    'private boolean cameraInitialized = false;\n    private String message = "Mapa nativo";',
    'private boolean cameraInitialized = false;\n    private boolean mapboxLoaded = false, mapboxFallbackTried = false;\n    private String message = "Mapa nativo";'
)
if 'mapboxLoaded = false' not in s:
    raise SystemExit('v1.5.7: state fields insertion failed')

pattern = r'''    private void fetchMapboxConfig\(\) \{.*?\n    \}\n\n    private void attachMapbox\(String token,String styleUri\) \{.*?\n    \}\n'''
replacement = r'''    private void fetchMapboxConfig() {
        new Thread(() -> {
            try {
                String base=BuildConfig.MUSICROAD_URL.endsWith("/")?BuildConfig.MUSICROAD_URL:BuildConfig.MUSICROAD_URL+"/";
                // Use the same native API client as login/geocoding. It carries the
                // session cookie and the X-MusicRoad-Native header already accepted
                // by the production LiteSpeed server.
                NativeApiClient client=new NativeApiClient(getContext());
                NativeApiClient.Response response=client.get(base,"api/native_app.php?action=mapbox_config&v=157");
                JSONObject json=response.json();
                if(!response.ok()||!json.optBoolean("ok",false)||!json.optBoolean("enabled",true)){
                    String detail=json.optString("error","").trim();
                    if(detail.isEmpty())detail="HTTP "+response.code;
                    final String msg="Mapbox config: "+detail;
                    ui.post(()->{message=msg;fallback.invalidate();overlay.invalidate();});
                    return;
                }
                String token=json.optString("token","").trim();
                String style=json.optString("style","mapbox://styles/mapbox/navigation-night-v1").trim();
                if(!token.startsWith("pk.")){
                    ui.post(()->{message="Mapbox: token público inválido";fallback.invalidate();overlay.invalidate();});
                    return;
                }
                if(!style.startsWith("mapbox://styles/"))style="mapbox://styles/mapbox/navigation-night-v1";
                final String finalStyle=style;
                ui.post(()->attachMapbox(token,finalStyle));
            } catch(Throwable e){
                ui.post(()->{message="Mapbox: falha ao consultar servidor";fallback.invalidate();overlay.invalidate();});
            }
        },"MusicRoad-MapboxConfig").start();
    }

    private void activateMapbox() {
        if(mapView==null||mapboxMap==null)return;
        mapboxLoaded=true;
        message="Mapbox";
        fallback.setVisibility(View.GONE);
        mapView.animate().alpha(1f).setDuration(220).start();
        if(!route.isEmpty())fitRoute();
        else if(Double.isFinite(userLat)&&Double.isFinite(userLon))setCamera(userLat,userLon,15.8,25.0);
        overlay.invalidate();
    }

    private void attachMapbox(String token,String styleUri) {
        if(mapView!=null)return;
        try {
            MapboxOptions.INSTANCE.setAccessToken(token);
            MapView mv=new MapView(getContext());
            mv.setAlpha(0f);
            addView(mv,1,new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT));
            mapView=mv;
            mapboxMap=mv.getMapboxMap();
            message="Mapbox: carregando";
            fallback.invalidate();
            mapboxMap.loadStyle(styleUri, style->activateMapbox());

            // Some older navigation styles may fail on a newer Maps SDK. If the
            // server style does not become ready, retry with Mapbox dark-v11.
            ui.postDelayed(()->{
                if(mapboxMap==null||mapboxLoaded||mapboxFallbackTried)return;
                mapboxFallbackTried=true;
                message="Mapbox: estilo alternativo";
                fallback.invalidate();
                try{mapboxMap.loadStyle("mapbox://styles/mapbox/dark-v11",style->activateMapbox());}
                catch(Throwable ignored){}
            },6500);

            ui.postDelayed(()->{
                if(mapboxLoaded)return;
                message="Mapbox indisponível · usando mapa offline";
                fallback.setVisibility(View.VISIBLE);
                fallback.invalidate();
                overlay.invalidate();
            },14000);
        } catch(Throwable e) {
            if(mapView!=null){removeView(mapView);mapView=null;mapboxMap=null;}
            message="Mapbox: erro de inicialização";
            fallback.setVisibility(View.VISIBLE);
            fallback.invalidate();
        }
    }
'''

s2,n=re.subn(pattern,replacement,s,count=1,flags=re.S)
if n!=1:
    raise SystemExit(f'v1.5.7: map runtime replacement failed ({n})')
map_file.write_text(s2)

build=APP/'build.gradle'
b=build.read_text()
b=re.sub(r'versionCode\s+\d+','versionCode 33',b,count=1)
b=re.sub(r"versionName\s+'[^']+'","versionName '1.5.7'",b,count=1)
build.write_text(b)

print('MusicRoad 1.5.7 Mapbox runtime fix applied')

package com.musicroad.ai;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Online Mapbox routing engine used while the full Navigation SDK artifact is not
 * available at build time. Route calculation is 100% Mapbox Directions API; no
 * OSRM/fallback router is used. The public Mapbox token is loaded at runtime from
 * the MusicRoad server and is never hard-coded in the APK.
 */
final class MapboxDirectionsEngine {
    interface Listener {
        void onRoute(JSONArray coordinates,double distanceMeters,double durationSeconds,
                     String destination,double destinationLat,double destinationLon,boolean reroute);
        void onError(String message);
    }

    private final NativeApiClient api;
    private final String server;
    private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final AtomicBoolean inFlight=new AtomicBoolean(false);
    private volatile String token="";

    MapboxDirectionsEngine(Context context,NativeApiClient api,String server,Listener listener){
        this.api=api;
        this.server=NativeApiClient.normalizeBase(server);
        this.listener=listener;
    }

    boolean isBusy(){return inFlight.get();}

    void requestRoute(double originLat,double originLon,double destinationLat,double destinationLon,
                      String destination,boolean reroute){
        if(!validCoordinate(originLat,originLon)||!validCoordinate(destinationLat,destinationLon)){
            fail("Coordenadas inválidas para a rota Mapbox.");return;
        }
        if(!inFlight.compareAndSet(false,true))return;
        io.execute(()->{
            try{
                String accessToken=ensureToken();
                JSONObject response=callDirections(accessToken,originLat,originLon,destinationLat,destinationLon,"driving-traffic");
                JSONArray routes=response.optJSONArray("routes");
                if(routes==null||routes.length()==0){
                    // Driving traffic is preferred. If the account/profile is not available,
                    // still remain entirely on Mapbox and retry the normal driving profile.
                    response=callDirections(accessToken,originLat,originLon,destinationLat,destinationLon,"driving");
                    routes=response.optJSONArray("routes");
                }
                if(routes==null||routes.length()==0){
                    String msg=response.optString("message","Mapbox não encontrou uma rota para este destino.");
                    throw new IllegalStateException(msg);
                }
                JSONObject route=routes.optJSONObject(0);
                JSONObject geometry=route==null?null:route.optJSONObject("geometry");
                JSONArray coords=geometry==null?null:geometry.optJSONArray("coordinates");
                if(coords==null||coords.length()<2)throw new IllegalStateException("Mapbox retornou rota sem geometria.");
                double distance=route.optDouble("distance",0d),duration=route.optDouble("duration",0d);
                JSONArray finalCoords=coords;
                main.post(()->{
                    inFlight.set(false);
                    if(listener!=null)listener.onRoute(finalCoords,distance,duration,destination,destinationLat,destinationLon,reroute);
                });
            }catch(Exception e){
                String msg=e.getMessage();if(msg==null||msg.trim().isEmpty())msg="Falha ao calcular rota pelo Mapbox.";
                final String out=msg;
                main.post(()->{inFlight.set(false);if(listener!=null)listener.onError(out);});
            }
        });
    }

    private String ensureToken() throws Exception {
        String cached=token;
        if(validToken(cached))return cached;
        NativeApiClient.Response r=api.get(server,"api/native_app.php?action=mapbox_config");
        JSONObject j=r.json();
        String t=j.optString("token","").trim();
        if(!r.ok()||!j.optBoolean("ok")||!validToken(t)){
            r=api.get(server,"mapbox_config.php");j=r.json();t=j.optString("token","").trim();
        }
        if(!r.ok()||!j.optBoolean("ok")||!validToken(t))throw new IllegalStateException("Token público Mapbox não disponível no servidor.");
        token=t;return t;
    }

    private JSONObject callDirections(String accessToken,double oLat,double oLon,double dLat,double dLon,String profile) throws Exception {
        String coords=String.format(Locale.US,"%.7f,%.7f;%.7f,%.7f",oLon,oLat,dLon,dLat);
        String url="https://api.mapbox.com/directions/v5/mapbox/"+profile+"/"+coords+
                "?alternatives=true&geometries=geojson&overview=full&steps=true&language=pt-BR"+
                "&voice_instructions=true&banner_instructions=true&access_token="+accessToken;
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(12000);c.setReadTimeout(30000);c.setInstanceFollowRedirects(true);
        c.setRequestMethod("GET");c.setRequestProperty("Accept","application/json");
        c.setRequestProperty("User-Agent","MusicRoadAndroid/"+BuildConfig.VERSION_NAME+" MapboxDirections");
        int code=c.getResponseCode();InputStream in=code>=400?c.getErrorStream():c.getInputStream();
        String body=read(in,8*1024*1024);c.disconnect();
        JSONObject j=new JSONObject(body==null||body.isEmpty()?"{}":body);
        if(code<200||code>=300){
            String message=j.optString("message","Mapbox Directions HTTP "+code);
            throw new IllegalStateException(message);
        }
        return j;
    }

    private static String read(InputStream in,int max) throws Exception {
        if(in==null)return "";
        try(BufferedInputStream bin=new BufferedInputStream(in);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[16384];int n,total=0;
            while((n=bin.read(b))!=-1){total+=n;if(total>max)throw new IllegalStateException("Resposta Mapbox muito grande.");out.write(b,0,n);}
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static boolean validToken(String t){return t!=null&&t.startsWith("pk.")&&t.length()>20;}
    private static boolean validCoordinate(double lat,double lon){return Double.isFinite(lat)&&Double.isFinite(lon)&&lat>=-90&&lat<=90&&lon>=-180&&lon<=180;}

    private void fail(String message){main.post(()->{if(listener!=null)listener.onError(message);});}
    void shutdown(){io.shutdownNow();}
}

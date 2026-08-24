package com.musicroad.ai;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 1.6.2: route first, hazards second.
 * The route is calculated by Mapbox immediately through route_fast.php.
 * Radar/speed-bump enrichment runs on a separate worker and never blocks drawing.
 */
final class FastMapboxRouteEngine {
    interface Listener {
        void onRoute(JSONArray coordinates,double distanceMeters,double durationSeconds,
                     String destinationLabel,double destinationLat,double destinationLon,boolean reroute);
        void onHazards(JSONArray hazards);
        void onError(String message);
    }

    private final NativeApiClient api;
    private final String server;
    private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService routeIo=Executors.newSingleThreadExecutor();
    private final ExecutorService hazardIo=Executors.newSingleThreadExecutor();
    private final AtomicBoolean inFlight=new AtomicBoolean(false);

    FastMapboxRouteEngine(Context context,NativeApiClient api,String server,Listener listener){
        this.api=api;this.server=NativeApiClient.normalizeBase(server);this.listener=listener;
    }

    boolean isBusy(){return inFlight.get();}

    void requestRoute(double originLat,double originLon,String destinationQuery,String displayDestination,boolean reroute){
        if(!validCoordinate(originLat,originLon)){fail("GPS inválido para calcular a rota.");return;}
        if(destinationQuery==null||destinationQuery.trim().length()<2){fail("Informe um destino válido.");return;}
        if(!inFlight.compareAndSet(false,true))return;
        routeIo.execute(()->{
            try{
                String origin=String.format(Locale.US,"%.7f,%.7f",originLat,originLon);
                String path="api/route_fast.php?origin="+enc(origin)+"&destination="+enc(destinationQuery.trim())+"&v=162";
                NativeApiClient.Response response=api.getLarge(server,path);
                JSONObject json=response.json();
                if(!response.ok()||!json.optBoolean("ok"))throw new IllegalStateException(json.optString("error","Falha ao calcular rota Mapbox."));
                JSONObject route=json.optJSONObject("route"),geometry=route==null?null:route.optJSONObject("geometry");
                JSONArray coords=geometry==null?null:geometry.optJSONArray("coordinates");
                if(coords==null||coords.length()<2)throw new IllegalStateException("A rota Mapbox retornou sem geometria.");
                JSONObject dest=json.optJSONObject("destination");
                double dlat=dest==null?Double.NaN:dest.optDouble("lat",Double.NaN),dlon=dest==null?Double.NaN:dest.optDouble("lon",Double.NaN);
                String label=dest==null?"":dest.optString("display_name","").trim();if(label.isEmpty())label=displayDestination==null?destinationQuery:displayDestination;
                double distance=route.optDouble("distance",0d),duration=route.optDouble("duration",0d);
                JSONArray finalCoords=coords;String finalLabel=label;
                main.post(()->{inFlight.set(false);if(listener!=null)listener.onRoute(finalCoords,distance,duration,finalLabel,dlat,dlon,reroute);});
                fetchHazardsAsync(finalCoords);
            }catch(Exception e){String msg=e.getMessage();if(msg==null||msg.trim().isEmpty())msg="Falha ao calcular rota Mapbox.";final String out=msg;main.post(()->{inFlight.set(false);if(listener!=null)listener.onError(out);});}
        });
    }

    private void fetchHazardsAsync(JSONArray coords){
        final String geometry=coords.toString();
        hazardIo.execute(()->{
            try{
                JSONObject body=new JSONObject();body.put("coordinates",new JSONArray(geometry));
                NativeApiClient.Response r=api.post(server,"api/route_hazards.php?v=162",body);JSONObject j=r.json();
                if(!r.ok()||!j.optBoolean("ok"))return;JSONArray hazards=j.optJSONArray("radars");if(hazards==null)hazards=new JSONArray();
                JSONArray finalHazards=hazards;main.post(()->{if(listener!=null)listener.onHazards(finalHazards);});
            }catch(Exception ignored){}
        });
    }

    private static String enc(String value){try{return URLEncoder.encode(value,StandardCharsets.UTF_8.name());}catch(Exception e){return value;}}
    private static boolean validCoordinate(double lat,double lon){return Double.isFinite(lat)&&Double.isFinite(lon)&&lat>=-90&&lat<=90&&lon>=-180&&lon<=180;}
    private void fail(String message){main.post(()->{if(listener!=null)listener.onError(message);});}
    void shutdown(){routeIo.shutdownNow();hazardIo.shutdownNow();}
}

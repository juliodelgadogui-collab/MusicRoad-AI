package com.musicroad.ai;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import com.mapbox.api.directions.v5.models.DirectionsRoute;
import com.mapbox.api.directions.v5.models.RouteOptions;
import com.mapbox.common.MapboxOptions;
import com.mapbox.common.TileStore;
import com.mapbox.geojson.Point;
import com.mapbox.geojson.utils.PolylineUtils;
import com.mapbox.maps.MapboxMapsOptions;
import com.mapbox.navigation.base.extensions.RouteOptionsExtensions;
import com.mapbox.navigation.base.options.NavigationOptions;
import com.mapbox.navigation.base.options.RoutingTilesOptions;
import com.mapbox.navigation.base.route.NavigationRoute;
import com.mapbox.navigation.base.route.NavigationRouterCallback;
import com.mapbox.navigation.base.route.RouterFailure;
import com.mapbox.navigation.core.MapboxNavigation;
import com.mapbox.navigation.core.MapboxNavigationProvider;
import com.mapbox.navigation.core.directions.session.RoutesObserver;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * Single routing authority for MusicRoad 1.6.
 *
 * Mapbox Navigation calculates and reroutes. The server is only used to obtain
 * the already validated public Mapbox token and to sync MusicRoad-owned data.
 * The same TileStore is shared with Maps so downloaded routing/map resources
 * can be used offline.
 */
final class MapboxNavigationEngine {
    interface Listener {
        void onEngineReady();
        void onRoute(JSONArray coordinates,double distanceMeters,double durationSeconds,String destination,boolean reroute);
        void onError(String message);
    }

    private static TileStore sharedTileStore;
    static synchronized TileStore sharedTileStore(Context context){
        if(sharedTileStore==null) sharedTileStore=TileStore.create();
        return sharedTileStore;
    }

    private final Context context;
    private final NativeApiClient api;
    private final String base;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private Listener listener;
    private MapboxNavigation navigation;
    private boolean ready=false;
    private Pending pending;
    private String currentDestination="";

    private static final class Pending {
        final double olat,olon,dlat,dlon; final String label;
        Pending(double a,double b,double c,double d,String l){olat=a;olon=b;dlat=c;dlon=d;label=l;}
    }

    MapboxNavigationEngine(Context c,NativeApiClient client,String baseUrl,Listener l){
        context=c.getApplicationContext();api=client;base=NativeApiClient.normalizeBase(baseUrl);listener=l;initialize();
    }

    MapboxNavigation navigation(){return navigation;}
    TileStore tileStore(){return sharedTileStore(context);}
    boolean isReady(){return ready&&navigation!=null;}

    private void initialize(){
        new Thread(()->{
            try{
                NativeApiClient.Response r=api.get(base,"api/native_app.php?action=mapbox_config&v=160");
                JSONObject j=r.json();
                if(!r.ok()||!j.optBoolean("ok",false))throw new IllegalStateException(j.optString("error","Configuração Mapbox indisponível."));
                String token=j.optString("token","").trim();
                if(!token.startsWith("pk."))throw new IllegalStateException("Token público Mapbox inválido.");
                ui.post(()->initializeSdk(token));
            }catch(Throwable e){notifyError("Mapbox Navigation: "+safe(e));}
        },"MusicRoad-MapboxNavInit").start();
    }

    private void initializeSdk(String token){
        try{
            MapboxOptions.setAccessToken(token);
            TileStore store=sharedTileStore(context);
            MapboxMapsOptions.setTileStore(store);
            RoutingTilesOptions routing=new RoutingTilesOptions.Builder().tileStore(store).build();
            NavigationOptions options=new NavigationOptions.Builder(context).routingTilesOptions(routing).build();
            if(MapboxNavigationProvider.isCreated())navigation=MapboxNavigationProvider.retrieve();
            else navigation=MapboxNavigationProvider.create(options);
            navigation.setRerouteEnabled(true);
            navigation.registerRoutesObserver(routesObserver);
            if(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED||context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED){
                try{navigation.startTripSession();}catch(Throwable ignored){}
            }
            ready=true;
            if(listener!=null)listener.onEngineReady();
            Pending p=pending;pending=null;if(p!=null)requestRoute(p.olat,p.olon,p.dlat,p.dlon,p.label);
        }catch(Throwable e){notifyError("Mapbox Navigation init: "+safe(e));}
    }

    void startTripSessionIfPermitted(){
        if(navigation==null)return;
        if(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED&&context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED)return;
        try{navigation.startTripSession();}catch(Throwable ignored){}
    }

    void requestRoute(double originLat,double originLon,double destLat,double destLon,String destination){
        currentDestination=destination==null?"Destino":destination;
        if(!isReady()){pending=new Pending(originLat,originLon,destLat,destLon,currentDestination);return;}
        try{
            RouteOptions.Builder b=RouteOptions.builder();
            RouteOptionsExtensions.applyDefaultNavigationOptions(b);
            RouteOptionsExtensions.applyLanguageAndVoiceUnitOptions(b,context);
            RouteOptionsExtensions.coordinates(b,Point.fromLngLat(originLon,originLat),Point.fromLngLat(destLon,destLat));
            navigation.requestRoutes(b.build(),new NavigationRouterCallback(){
                @Override public void onRoutesReady(List<NavigationRoute> routes,String routerOrigin){
                    if(routes==null||routes.isEmpty()){notifyError("Mapbox não retornou uma rota.");return;}
                    try{navigation.setNavigationRoutes(routes);emit(routes.get(0),false);}catch(Throwable e){notifyError("Falha ao iniciar rota Mapbox: "+safe(e));}
                }
                @Override public void onFailure(List<RouterFailure> reasons,RouteOptions routeOptions){
                    String m="Falha ao calcular rota Mapbox.";
                    if(reasons!=null&&!reasons.isEmpty()&&reasons.get(0)!=null&&reasons.get(0).getMessage()!=null)m=reasons.get(0).getMessage();
                    notifyError(m);
                }
                @Override public void onCanceled(RouteOptions routeOptions,String routerOrigin){notifyError("Cálculo da rota cancelado.");}
            });
        }catch(Throwable e){notifyError("Falha ao solicitar rota Mapbox: "+safe(e));}
    }

    void clearRoute(){
        pending=null;currentDestination="";
        if(navigation!=null)try{navigation.setNavigationRoutes(java.util.Collections.emptyList());}catch(Throwable ignored){}
    }

    private final RoutesObserver routesObserver=result->{
        if(result==null||result.getNavigationRoutes()==null||result.getNavigationRoutes().isEmpty())return;
        String reason=result.getReason();
        boolean reroute=reason!=null&&reason.toLowerCase(java.util.Locale.ROOT).contains("reroute");
        if(reroute)emit(result.getNavigationRoutes().get(0),true);
    };

    private void emit(NavigationRoute nr,boolean reroute){
        try{
            DirectionsRoute dr=nr.getDirectionsRoute();
            String geometry=dr.geometry();
            if(geometry==null||geometry.isEmpty())throw new IllegalStateException("Rota Mapbox sem geometria.");
            List<Point> points=PolylineUtils.decode(geometry,6);
            JSONArray coords=new JSONArray();
            for(Point p:points){JSONArray c=new JSONArray();c.put(p.longitude());c.put(p.latitude());coords.put(c);}
            double distance=dr.distance()==null?0:dr.distance();
            double duration=dr.duration()==null?0:dr.duration();
            if(listener!=null)listener.onRoute(coords,distance,duration,currentDestination,reroute);
        }catch(Throwable e){notifyError("Falha ao ler rota Mapbox: "+safe(e));}
    }

    private void notifyError(String message){ui.post(()->{if(listener!=null)listener.onError(message);});}
    private static String safe(Throwable e){String m=e==null?null:e.getMessage();if(m==null||m.trim().isEmpty())m=e==null?"erro desconhecido":e.getClass().getSimpleName();return m.replace('\n',' ').replace('\r',' ');}
}

package com.musicroad.ai;

import android.content.Context;

import com.mapbox.common.NetworkRestriction;
import com.mapbox.common.TileRegionLoadOptions;
import com.mapbox.common.TileStore;
import com.mapbox.geojson.Point;
import com.mapbox.geojson.Polygon;
import com.mapbox.maps.GlyphsRasterizationMode;
import com.mapbox.maps.OfflineManager;
import com.mapbox.maps.StylePackLoadOptions;
import com.mapbox.maps.TilesetDescriptor;
import com.mapbox.maps.TilesetDescriptorOptions;
import com.mapbox.navigation.base.route.NavigationRoute;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Downloads one practical offline corridor/area using the SAME TileStore for Maps + Navigation. */
final class MapboxOfflineRegionManager {
    interface Listener { void onProgress(long completed,long required,String phase); void onDone(); void onError(String message); }
    private final Context context;
    private final MapboxNavigationEngine engine;
    private final OfflineManager offlineManager=new OfflineManager();

    MapboxOfflineRegionManager(Context c,MapboxNavigationEngine e){context=c.getApplicationContext();engine=e;}

    void download(String regionId,String styleUri,JSONArray route,double lat,double lon,Listener listener){
        if(engine==null||!engine.isReady()||engine.navigation()==null){listener.onError("Mapbox Navigation ainda está inicializando.");return;}
        try{
            Polygon area=area(route,lat,lon);
            TileStore store=engine.tileStore();
            TilesetDescriptor mapDescriptor=offlineManager.createTilesetDescriptor(new TilesetDescriptorOptions.Builder()
                    .styleURI(styleUri).pixelRatio(context.getResources().getDisplayMetrics().density).minZoom((byte)0).maxZoom((byte)14).build());
            TilesetDescriptor navDescriptor=engine.navigation().getTilesetDescriptorFactory().getLatest();
            List<TilesetDescriptor> descriptors=Arrays.asList(mapDescriptor,navDescriptor);
            AtomicBoolean styleDone=new AtomicBoolean(false),tilesDone=new AtomicBoolean(false),failed=new AtomicBoolean(false);
            Runnable finish=()->{if(!failed.get()&&styleDone.get()&&tilesDone.get())listener.onDone();};

            StylePackLoadOptions sp=new StylePackLoadOptions.Builder()
                    .glyphsRasterizationMode(GlyphsRasterizationMode.IDEOGRAPHS_RASTERIZED_LOCALLY).build();
            offlineManager.loadStylePack(styleUri,sp,
                    p->listener.onProgress(p.getCompletedResourceCount(),p.getRequiredResourceCount(),"estilo"),
                    expected->{
                        if(expected.getError()!=null){failed.set(true);listener.onError("Estilo offline: "+expected.getError().getMessage());return;}
                        styleDone.set(true);finish.run();
                    });

            TileRegionLoadOptions opts=new TileRegionLoadOptions.Builder().geometry(area).descriptors(descriptors)
                    .acceptExpired(true).networkRestriction(NetworkRestriction.NONE).build();
            store.loadTileRegion(regionId,opts,
                    p->listener.onProgress(p.getCompletedResourceCount(),p.getRequiredResourceCount(),"mapa + navegação"),
                    expected->{
                        if(expected.getError()!=null){failed.set(true);listener.onError("Região offline: "+expected.getError().getMessage());return;}
                        tilesDone.set(true);finish.run();
                    });
        }catch(Throwable e){listener.onError("Falha ao preparar offline Mapbox: "+safe(e));}
    }

    void remove(String regionId,String styleUri){
        try{engine.tileStore().removeTileRegion(regionId);}catch(Throwable ignored){}
        try{offlineManager.removeStylePack(styleUri);}catch(Throwable ignored){}
    }

    private Polygon area(JSONArray route,double lat,double lon){
        double minLat=lat,maxLat=lat,minLon=lon,maxLon=lon;boolean any=Double.isFinite(lat)&&Double.isFinite(lon);
        if(route!=null)for(int i=0;i<route.length();i++){
            JSONArray c=route.optJSONArray(i);if(c==null||c.length()<2)continue;
            double x=c.optDouble(0,Double.NaN),y=c.optDouble(1,Double.NaN);if(!Double.isFinite(x)||!Double.isFinite(y))continue;
            if(!any){minLat=maxLat=y;minLon=maxLon=x;any=true;}else{minLat=Math.min(minLat,y);maxLat=Math.max(maxLat,y);minLon=Math.min(minLon,x);maxLon=Math.max(maxLon,x);}
        }
        if(!any){minLat=-23.7;maxLat=-22.5;minLon=-44.0;maxLon=-42.5;}
        // ~8 km corridor around a route; ~35 km box when there is no active route.
        double pad=route!=null&&route.length()>1?0.075:0.32;
        minLat-=pad;maxLat+=pad;minLon-=pad;maxLon+=pad;
        ArrayList<Point> ring=new ArrayList<>();
        ring.add(Point.fromLngLat(minLon,minLat));ring.add(Point.fromLngLat(maxLon,minLat));ring.add(Point.fromLngLat(maxLon,maxLat));ring.add(Point.fromLngLat(minLon,maxLat));ring.add(Point.fromLngLat(minLon,minLat));
        List<List<Point>> rings=new ArrayList<>();rings.add(ring);return Polygon.fromLngLats(rings);
    }
    private static String safe(Throwable e){String m=e==null?null:e.getMessage();return m==null||m.trim().isEmpty()?(e==null?"erro desconhecido":e.getClass().getSimpleName()):m;}
}

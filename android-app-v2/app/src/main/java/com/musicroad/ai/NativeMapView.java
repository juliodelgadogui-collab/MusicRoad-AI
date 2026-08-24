package com.musicroad.ai;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;

import com.mapbox.bindgen.Value;
import com.mapbox.geojson.Point;
import com.mapbox.maps.CameraOptions;
import com.mapbox.maps.MapView;
import com.mapbox.maps.MapInitOptions;
import com.mapbox.maps.MapboxMap;
import com.mapbox.common.MapboxOptions;
import com.mapbox.maps.ScreenCoordinate;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

final class NativeMapView extends FrameLayout {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<double[]> roads = new ArrayList<>();
    private final List<double[]> route = new ArrayList<>();
    private final List<double[]> radars = new ArrayList<>();
    private final FallbackView fallback;
    private final OverlayView overlay;
    private MapView mapView;
    private MapboxMap mapboxMap;
    private double userLat = Double.NaN, userLon = Double.NaN, userBearing = 0.0;
    private boolean drivingMode = false;
    private boolean cameraInitialized = false;
    private boolean mapboxLoaded = false, mapboxFallbackTried = false, nativeRouteLayerReady = false;
    private String message = "Mapa nativo";

    NativeMapView(Context context) {
        super(context);
        setBackgroundColor(Color.rgb(5, 11, 18));
        fallback = new FallbackView(context);
        overlay = new OverlayView(context);
        addView(fallback, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(overlay, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        fetchMapboxConfig();
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    void setMessage(String value) { message = value == null ? "" : value; fallback.invalidate(); overlay.invalidate(); }

    void setUserLocation(double lat, double lon) {
        userLat = lat; userLon = lon; fallback.recenterIfEmpty(lat, lon);
        if (mapboxMap != null && drivingMode) { cameraInitialized=true; setCamera(lat,lon,16.6,52.0); }
        else if (mapboxMap != null && !cameraInitialized && route.isEmpty()) { cameraInitialized = true; setCamera(lat, lon, 15.8, 25.0); }
        fallback.invalidate(); overlay.invalidate();
    }

    void setUserBearing(double bearing){if(Double.isFinite(bearing)){userBearing=((bearing%360.0)+360.0)%360.0;}}
    void setDrivingMode(boolean enabled){drivingMode=enabled;if(enabled&&Double.isFinite(userLat)&&Double.isFinite(userLon)){cameraInitialized=true;setCamera(userLat,userLon,16.6,52.0);}}

    void clearRoute() { route.clear(); renderNativeRoute(); fallback.fitData(); overlay.invalidate(); fallback.invalidate(); }

    void setRoute(JSONArray coords) {
        route.clear();
        if (coords != null) {
            int step = Math.max(1, coords.length() / 900);
            for (int i = 0; i < coords.length(); i += step) {
                JSONArray c = coords.optJSONArray(i); if (c == null || c.length() < 2) continue;
                double lon = c.optDouble(0, Double.NaN), lat = c.optDouble(1, Double.NaN);
                if (Double.isFinite(lat) && Double.isFinite(lon)) route.add(new double[]{lat, lon});
            }
            if (coords.length() > 1) {
                JSONArray c = coords.optJSONArray(coords.length() - 1);
                if (c != null && c.length() >= 2) {
                    double lon = c.optDouble(0, Double.NaN), lat = c.optDouble(1, Double.NaN);
                    if (Double.isFinite(lat) && Double.isFinite(lon)) {
                        if (route.isEmpty() || route.get(route.size() - 1)[0] != lat || route.get(route.size() - 1)[1] != lon) route.add(new double[]{lat, lon});
                    }
                }
            }
        }
        renderNativeRoute(); fallback.fitData(); fallback.invalidate(); overlay.invalidate();
    }

    void setRadars(JSONArray rr) {
        radars.clear();
        if (rr != null) for (int i = 0; i < rr.length(); i++) {
            JSONObject r = rr.optJSONObject(i); if (r == null) continue;
            double lat = r.optDouble("latitude", Double.NaN), lon = r.optDouble("longitude", Double.NaN);
            if (Double.isFinite(lat) && Double.isFinite(lon)) radars.add(new double[]{lat, lon});
        }
        fallback.invalidate(); overlay.invalidate();
    }

    void setOfflinePack(JSONObject pack) {
        roads.clear();
        if (pack != null) {
            JSONObject map = pack.optJSONObject("map"), roadsFc = map == null ? null : map.optJSONObject("roads");
            JSONArray features = roadsFc == null ? null : roadsFc.optJSONArray("features");
            if (features != null) {
                int max = Math.min(features.length(), 16000);
                for (int i = 0; i < max; i++) {
                    JSONObject f = features.optJSONObject(i), g = f == null ? null : f.optJSONObject("geometry");
                    JSONArray coords = g == null ? null : g.optJSONArray("coordinates");
                    if (coords == null || coords.length() < 2) continue;
                    int step = Math.max(1, coords.length() / 500); ArrayList<Double> vals = new ArrayList<>();
                    for (int j = 0; j < coords.length(); j += step) {
                        JSONArray c = coords.optJSONArray(j); if (c == null || c.length() < 2) continue;
                        double lon = c.optDouble(0, Double.NaN), lat = c.optDouble(1, Double.NaN);
                        if (Double.isFinite(lat) && Double.isFinite(lon)) { vals.add(lat); vals.add(lon); }
                    }
                    if (vals.size() >= 4) { double[] line = new double[vals.size()]; for (int k = 0; k < line.length; k++) line[k] = vals.get(k); roads.add(line); }
                }
            }
            JSONArray rr = pack.optJSONArray("radars"); if (rr != null) setRadars(rr);
        }
        fallback.fitData(); fallback.invalidate();
    }

    void fitRoute() {
        drivingMode=false;
        fallback.fitData();
        if (mapboxMap == null || route.isEmpty()) { fallback.invalidate(); return; }
        double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
        for (double[] p : route) { minLat = Math.min(minLat,p[0]); maxLat = Math.max(maxLat,p[0]); minLon = Math.min(minLon,p[1]); maxLon = Math.max(maxLon,p[1]); }
        double lat = (minLat+maxLat)/2.0, lon=(minLon+maxLon)/2.0;
        double latSpan=Math.max(0.0005,maxLat-minLat), lonSpan=Math.max(0.0005,(maxLon-minLon)*Math.cos(Math.toRadians(lat))), span=Math.max(latSpan,lonSpan);
        double zoom=Math.log(360.0/span)/Math.log(2.0)-1.95; zoom=Math.max(5.0,Math.min(17.5,zoom));
        cameraInitialized=true; setCamera(lat,lon,zoom,0.0);
    }

    void recenter(double lat, double lon) {
        drivingMode=true;cameraInitialized=true; fallback.centerLat=lat; fallback.centerLon=lon; fallback.zoom=1.8f; fallback.panX=fallback.panY=0;
        setCamera(lat,lon,16.6,52.0); fallback.invalidate(); overlay.invalidate();
    }

    private void setCamera(double lat,double lon,double zoom,double pitch) {
        if (mapboxMap==null) return;
        try { mapboxMap.setCamera(new CameraOptions.Builder().center(Point.fromLngLat(lon,lat)).zoom(zoom).pitch(pitch).bearing(drivingMode?userBearing:0.0).build()); } catch(Throwable ignored){}
    }

    private void fetchMapboxConfig() {
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
        renderNativeRoute();
        if(!route.isEmpty())fitRoute();
        else if(Double.isFinite(userLat)&&Double.isFinite(userLon))setCamera(userLat,userLon,15.8,25.0);
        overlay.invalidate();
    }

    private void attachMapbox(String token,String styleUri) {
        if(mapView!=null)return;
        try {
            MapboxOptions.INSTANCE.setAccessToken(token);
            MapInitOptions initOptions=new MapInitOptions(getContext());
            initOptions.setTextureView(true);
            MapView mv=new MapView(getContext(),initOptions);
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
            String type=e.getClass().getSimpleName();
            String detail=e.getMessage();
            if(detail==null||detail.trim().isEmpty())detail=type;
            detail=detail.replace('\n',' ').replace('\r',' ');
            if(detail.length()>72)detail=detail.substring(0,72);
            message="Mapbox init: "+detail;
            fallback.setVisibility(View.VISIBLE);
            fallback.invalidate();
        }
    }

    private String routeGeoJson(){
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

    private final class OverlayView extends View {
        private final Paint routeShadow=new Paint(Paint.ANTI_ALIAS_FLAG),routePaint=new Paint(Paint.ANTI_ALIAS_FLAG),radarPaint=new Paint(Paint.ANTI_ALIAS_FLAG),userPaint=new Paint(Paint.ANTI_ALIAS_FLAG),userRing=new Paint(Paint.ANTI_ALIAS_FLAG),labelPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
        OverlayView(Context c){super(c);setWillNotDraw(false);setClickable(false);routeShadow.setColor(Color.rgb(105,43,190));routeShadow.setStyle(Paint.Style.STROKE);routeShadow.setStrokeWidth(dp(11));routeShadow.setStrokeCap(Paint.Cap.ROUND);routeShadow.setStrokeJoin(Paint.Join.ROUND);routePaint.setColor(Color.rgb(255,122,26));routePaint.setStyle(Paint.Style.STROKE);routePaint.setStrokeWidth(dp(6));routePaint.setStrokeCap(Paint.Cap.ROUND);routePaint.setStrokeJoin(Paint.Join.ROUND);radarPaint.setColor(Color.rgb(255,73,78));userPaint.setColor(Color.rgb(45,159,255));userRing.setColor(Color.WHITE);userRing.setStyle(Paint.Style.STROKE);userRing.setStrokeWidth(dp(2.5f));labelPaint.setColor(Color.rgb(175,188,202));labelPaint.setTextSize(dp(11));}
        @Override public boolean onTouchEvent(android.view.MotionEvent event){return false;}
        @Override protected void onDraw(Canvas c){super.onDraw(c);if(mapboxMap==null||mapView==null||mapView.getAlpha()<0.9f)return;if(!nativeRouteLayerReady){drawRoute(c,routeShadow);drawRoute(c,routePaint);}for(double[]r:radars){ScreenCoordinate s=screen(r[0],r[1]);if(s==null)continue;float x=(float)s.getX(),y=(float)s.getY();c.drawCircle(x,y,dp(6.2f),radarPaint);Paint ring=new Paint(Paint.ANTI_ALIAS_FLAG);ring.setStyle(Paint.Style.STROKE);ring.setStrokeWidth(dp(2));ring.setColor(Color.WHITE);c.drawCircle(x,y,dp(8.3f),ring);}if(Double.isFinite(userLat)&&Double.isFinite(userLon)){ScreenCoordinate s=screen(userLat,userLon);if(s!=null){float x=(float)s.getX(),y=(float)s.getY();c.drawCircle(x,y,dp(8),userPaint);c.drawCircle(x,y,dp(11),userRing);}}if(!message.isEmpty())c.drawText(message,dp(12),getHeight()-dp(14),labelPaint);postInvalidateDelayed(120);}
        private void drawRoute(Canvas c,Paint paint){if(route.size()<2)return;Path path=new Path();boolean started=false;for(double[]p:route){ScreenCoordinate s=screen(p[0],p[1]);if(s==null){started=false;continue;}float x=(float)s.getX(),y=(float)s.getY();if(!started){path.moveTo(x,y);started=true;}else path.lineTo(x,y);}c.drawPath(path,paint);}
        private ScreenCoordinate screen(double lat,double lon){try{ScreenCoordinate s=mapboxMap.pixelForCoordinate(Point.fromLngLat(lon,lat));if(!Double.isFinite(s.getX())||!Double.isFinite(s.getY())||s.getX()<-1||s.getY()<-1)return null;return s;}catch(Throwable ignored){return null;}}
    }

    private final class FallbackView extends View {
        private final Paint bg=new Paint(),roadPaint=new Paint(Paint.ANTI_ALIAS_FLAG),routeShadow=new Paint(Paint.ANTI_ALIAS_FLAG),routePaint=new Paint(Paint.ANTI_ALIAS_FLAG),radarPaint=new Paint(Paint.ANTI_ALIAS_FLAG),userPaint=new Paint(Paint.ANTI_ALIAS_FLAG),userRing=new Paint(Paint.ANTI_ALIAS_FLAG),text=new Paint(Paint.ANTI_ALIAS_FLAG);
        double centerLat=-19.5,centerLon=-46.6,baseScale=800.0;float zoom=1f,panX=0,panY=0;
        FallbackView(Context c){super(c);bg.setColor(Color.rgb(5,11,18));roadPaint.setColor(Color.rgb(62,79,94));roadPaint.setStyle(Paint.Style.STROKE);roadPaint.setStrokeWidth(dp(1.7f));routeShadow.setColor(Color.rgb(102,40,185));routeShadow.setStyle(Paint.Style.STROKE);routeShadow.setStrokeWidth(dp(9));routePaint.setColor(Color.rgb(255,122,26));routePaint.setStyle(Paint.Style.STROKE);routePaint.setStrokeWidth(dp(5.2f));radarPaint.setColor(Color.rgb(255,73,78));userPaint.setColor(Color.rgb(45,159,255));userRing.setColor(Color.WHITE);userRing.setStyle(Paint.Style.STROKE);userRing.setStrokeWidth(dp(2.4f));text.setColor(Color.rgb(139,158,173));text.setTextSize(dp(12));}
        void recenterIfEmpty(double lat,double lon){if(route.isEmpty()&&roads.isEmpty()){centerLat=lat;centerLon=lon;zoom=1.25f;panX=panY=0;}}
        private double wx(double lon){return lon*Math.cos(Math.toRadians(centerLat));}private float sx(double lon){return(float)(getWidth()/2.0+(wx(lon)-wx(centerLon))*baseScale*zoom+panX);}private float sy(double lat){return(float)(getHeight()/2.0-(lat-centerLat)*baseScale*zoom+panY);}
        void fitData(){double minLat=90,maxLat=-90,minLon=180,maxLon=-180;boolean any=false;for(double[]line:roads)for(int i=0;i+1<line.length;i+=2){double lat=line[i],lon=line[i+1];minLat=Math.min(minLat,lat);maxLat=Math.max(maxLat,lat);minLon=Math.min(minLon,lon);maxLon=Math.max(maxLon,lon);any=true;}for(double[]p:route){minLat=Math.min(minLat,p[0]);maxLat=Math.max(maxLat,p[0]);minLon=Math.min(minLon,p[1]);maxLon=Math.max(maxLon,p[1]);any=true;}if(!any&&Double.isFinite(userLat)){minLat=userLat-.03;maxLat=userLat+.03;minLon=userLon-.03;maxLon=userLon+.03;any=true;}if(!any)return;centerLat=(minLat+maxLat)/2;centerLon=(minLon+maxLon)/2;double dx=Math.max(.002,(maxLon-minLon)*Math.cos(Math.toRadians(centerLat))),dy=Math.max(.002,maxLat-minLat);double w=Math.max(260,getWidth()>0?getWidth()*.82:900),h=Math.max(220,getHeight()>0?getHeight()*.78:500);baseScale=Math.min(w/dx,h/dy);zoom=1;panX=panY=0;}
        @Override protected void onSizeChanged(int w,int h,int ow,int oh){super.onSizeChanged(w,h,ow,oh);if(ow==0||oh==0)fitData();}
        @Override protected void onDraw(Canvas c){super.onDraw(c);c.drawRect(0,0,getWidth(),getHeight(),bg);Paint grid=new Paint();grid.setColor(Color.rgb(12,22,31));int gap=(int)dp(42);for(int x=0;x<getWidth();x+=gap)c.drawLine(x,0,x,getHeight(),grid);for(int y=0;y<getHeight();y+=gap)c.drawLine(0,y,getWidth(),y,grid);for(double[]line:roads)draw(c,line,roadPaint);if(route.size()>1){double[]line=new double[route.size()*2];for(int i=0;i<route.size();i++){line[i*2]=route.get(i)[0];line[i*2+1]=route.get(i)[1];}draw(c,line,routeShadow);draw(c,line,routePaint);}for(double[]p:radars)c.drawCircle(sx(p[1]),sy(p[0]),dp(4.5f),radarPaint);if(Double.isFinite(userLat)){float x=sx(userLon),y=sy(userLat);c.drawCircle(x,y,dp(8),userPaint);c.drawCircle(x,y,dp(11),userRing);}if(!message.isEmpty())c.drawText(message,dp(12),getHeight()-dp(14),text);}
        private void draw(Canvas c,double[]line,Paint p){if(line.length<4)return;Path path=new Path();boolean start=false;for(int i=0;i+1<line.length;i+=2){float x=sx(line[i+1]),y=sy(line[i]);if(!start){path.moveTo(x,y);start=true;}else path.lineTo(x,y);}c.drawPath(path,p);}
    }
}

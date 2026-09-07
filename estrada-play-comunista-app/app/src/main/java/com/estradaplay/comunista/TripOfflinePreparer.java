package com.estradaplay.comunista;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;

/** Samples a calculated route and prepares map + road-safety reserves ahead of the trip. */
final class TripOfflinePreparer {
    static final class Result {
        final int points,mapOk,safetyOk; final double routeKm;
        Result(int points,int mapOk,int safetyOk,double routeKm){this.points=points;this.mapOk=mapOk;this.safetyOk=safetyOk;this.routeKm=routeKm;}
        boolean useful(){return mapOk>0||safetyOk>0;}
        boolean complete(){return points>0&&mapOk>=points&&safetyOk>=points;}
        String summary(){
            if(complete())return "Rota offline pronta · "+points+" trecho(s) protegidos";
            if(useful())return "Offline parcial · mapa "+mapOk+"/"+points+" · proteção "+safetyOk+"/"+points;
            return "Não consegui baixar os pacotes da rota agora.";
        }
    }
    private static final double SAMPLE_M=180000.0;
    private TripOfflinePreparer(){}

    static Result prepare(Context context,RouteEngine.Route route){
        Result result=prepareInternal(context,route,Integer.MAX_VALUE);
        if(result.complete())RouteOfflineCache.markPrepared(context);
        return result;
    }

    static Result prepareAhead(Context context,RouteEngine.Route route,int maxSamples){
        return prepareInternal(context,route,Math.max(1,maxSamples));
    }

    private static Result prepareInternal(Context context,RouteEngine.Route route,int maxSamples){
        if(context==null||route==null||route.geoJson==null)return new Result(0,0,0,0);
        ArrayList<double[]> pts=routePoints(route.geoJson);if(pts.isEmpty())return new Result(0,0,0,route.distanceM/1000.0);
        ArrayList<Integer> samples=new ArrayList<>();samples.add(0);double acc=0;
        for(int i=1;i<pts.size();i++){double[] a=pts.get(i-1),b=pts.get(i);acc+=RoadPackStore.distanceM(a[0],a[1],b[0],b[1]);if(acc>=SAMPLE_M){samples.add(i);acc=0;}}
        if(samples.get(samples.size()-1)!=pts.size()-1)samples.add(pts.size()-1);
        if(samples.size()>maxSamples){ArrayList<Integer> limited=new ArrayList<>();for(int i=0;i<maxSamples;i++)limited.add(samples.get(i));samples=limited;}
        ApiClient api=new ApiClient(context);OfflineRoadStore map=new OfflineRoadStore(context);RoadPackStore safety=new RoadPackStore(context);int mapOk=0,safetyOk=0;
        for(int n=0;n<samples.size();n++){
            int idx=samples.get(n);double[] p=pts.get(idx);float heading;
            if(idx<pts.size()-1){int next=Math.min(pts.size()-1,idx+Math.max(1,Math.min(20,pts.size()-idx-1)));double[] q=pts.get(next);heading=(float)bearing(p[0],p[1],q[0],q[1]);}
            else if(idx>0){double[] q=pts.get(Math.max(0,idx-1));heading=(float)bearing(q[0],q[1],p[0],p[1]);}
            else heading=0f;
            try{if(map.prepare(api,p[0],p[1],heading))mapOk++;}catch(Throwable ignored){}try{if(safety.prepareTravelReserve(api,p[0],p[1],heading))safetyOk++;}catch(Throwable ignored){}
        }
        return new Result(samples.size(),mapOk,safetyOk,route.distanceM/1000.0);
    }

    private static ArrayList<double[]> routePoints(String raw){ArrayList<double[]> out=new ArrayList<>();try{JSONObject root=new JSONObject(raw);JSONArray f=root.optJSONArray("features");if(f==null||f.length()==0)return out;JSONObject g=f.optJSONObject(0);JSONObject geo=g==null?null:g.optJSONObject("geometry");JSONArray c=geo==null?null:geo.optJSONArray("coordinates");if(c==null)return out;for(int i=0;i<c.length();i++){JSONArray p=c.optJSONArray(i);if(p==null||p.length()<2)continue;double lon=p.optDouble(0,Double.NaN),lat=p.optDouble(1,Double.NaN);if(Double.isFinite(lat)&&Double.isFinite(lon))out.add(new double[]{lat,lon});}}catch(Throwable ignored){}return out;}
    private static double bearing(double lat1,double lon1,double lat2,double lon2){double p1=Math.toRadians(lat1),p2=Math.toRadians(lat2),dl=Math.toRadians(lon2-lon1);double y=Math.sin(dl)*Math.cos(p2),x=Math.cos(p1)*Math.sin(p2)-Math.sin(p1)*Math.cos(p2)*Math.cos(dl);double b=Math.toDegrees(Math.atan2(y,x));return(b+360.0)%360.0;}
}

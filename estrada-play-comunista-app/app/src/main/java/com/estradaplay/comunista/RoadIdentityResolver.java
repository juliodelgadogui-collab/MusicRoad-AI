package com.estradaplay.comunista;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves the current named highway using only the locally cached road geometry. */
final class RoadIdentityResolver {
    private static final Pattern ROAD = Pattern.compile("(?i)\\b(BR|RJ|MG|ES|SP)[-\\s]?(\\d{1,4})\\b");

    static final class Identity {
        final String road, uf, direction, segment, roomKey, label;
        Identity(String road, String uf, String direction, String segment) {
            this.road=road; this.uf=uf; this.direction=direction; this.segment=segment;
            String dirKey = direction.length() > 0 ? direction.substring(0,1) : "G";
            roomKey = (road.replace("-","")+"|"+uf+"|"+dirKey+"|"+segment).toUpperCase(Locale.ROOT);
            label = road+" · "+uf+" · "+direction;
        }
    }

    private RoadIdentityResolver() {}

    static Identity resolve(OfflineRoadStore store, double lat, double lon, float heading) {
        if (store == null || !Double.isFinite(lat) || !Double.isFinite(lon)) return null;
        try {
            JSONObject root = new JSONObject(store.combinedGeoJson(lat, lon));
            JSONArray features = root.optJSONArray("features");
            if (features == null) return null;
            double best = Double.MAX_VALUE;
            String bestRoad = "";
            int max = Math.min(features.length(), 12000);
            for (int i=0;i<max;i++) {
                JSONObject f=features.optJSONObject(i); if(f==null)continue;
                JSONObject props=f.optJSONObject("properties"); if(props==null)props=new JSONObject();
                String road=canonical(props.optString("ref",""));
                if(road.isEmpty()) road=canonical(props.optString("name",""));
                if(road.isEmpty()) continue;
                JSONObject g=f.optJSONObject("geometry"); if(g==null||!"LineString".equalsIgnoreCase(g.optString("type","")))continue;
                JSONArray c=g.optJSONArray("coordinates"); if(c==null||c.length()<2)continue;
                for(int j=1;j<c.length();j++){
                    JSONArray a=c.optJSONArray(j-1),b=c.optJSONArray(j); if(a==null||b==null||a.length()<2||b.length()<2)continue;
                    double d=segmentDistance(lat,lon,a.optDouble(1,Double.NaN),a.optDouble(0,Double.NaN),b.optDouble(1,Double.NaN),b.optDouble(0,Double.NaN));
                    if(d<best){best=d;bestRoad=road;}
                }
            }
            if(bestRoad.isEmpty()||best>95.0)return null;
            String uf=uf(lat,lon,bestRoad);
            String direction=direction(heading);
            String segment=segment(lat,lon);
            return new Identity(bestRoad,uf,direction,segment);
        } catch(Throwable ignored){ return null; }
    }

    private static String canonical(String raw){
        if(raw==null)return""; Matcher m=ROAD.matcher(raw.toUpperCase(Locale.ROOT));
        if(!m.find())return""; return m.group(1).toUpperCase(Locale.ROOT)+"-"+m.group(2);
    }
    private static String direction(float h){
        if(!Float.isFinite(h))return"GERAL"; float v=((h%360)+360)%360;
        if(v<45||v>=315)return"NORTE"; if(v<135)return"LESTE"; if(v<225)return"SUL"; return"OESTE";
    }
    private static String uf(double lat,double lon,String road){
        if(lat>=-23.40&&lat<=-20.75&&lon>=-44.95&&lon<=-40.70)return"RJ";
        if(lat>=-22.95&&lat<=-14.00&&lon>=-51.10&&lon<=-39.80)return"MG";
        if(lat>=-21.35&&lat<=-17.85&&lon>=-41.95&&lon<=-39.55)return"ES";
        if(lat>=-25.40&&lat<=-19.70&&lon>=-53.20&&lon<=-44.00)return"SP";
        if(road.startsWith("RJ-"))return"RJ"; if(road.startsWith("MG-"))return"MG"; if(road.startsWith("ES-"))return"ES"; if(road.startsWith("SP-"))return"SP";
        return"BR";
    }
    private static String segment(double lat,double lon){
        int a=(int)Math.floor((lat+35.0)*5.0), b=(int)Math.floor((lon+75.0)*5.0);
        return a+"-"+b;
    }
    private static double segmentDistance(double lat,double lon,double lat1,double lon1,double lat2,double lon2){
        if(!Double.isFinite(lat1)||!Double.isFinite(lon1)||!Double.isFinite(lat2)||!Double.isFinite(lon2))return Double.MAX_VALUE;
        double cos=Math.max(.25,Math.cos(Math.toRadians(lat))); double x1=(lon1-lon)*111320*cos,y1=(lat1-lat)*110540,x2=(lon2-lon)*111320*cos,y2=(lat2-lat)*110540;
        double dx=x2-x1,dy=y2-y1,den=dx*dx+dy*dy,t=den<.001?0:-(x1*dx+y1*dy)/den; t=Math.max(0,Math.min(1,t));
        return Math.hypot(x1+t*dx,y1+t*dy);
    }
}

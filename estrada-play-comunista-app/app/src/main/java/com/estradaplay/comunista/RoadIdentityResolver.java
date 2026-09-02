package com.estradaplay.comunista;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

/** Resolves the current named highway using locally cached road geometry, with a manual known-road fallback. */
final class RoadIdentityResolver {
    static final class Identity {
        final String road, uf, direction, segment, roomKey, label;
        Identity(String road, String uf, String direction, String segment, String segmentLabel) {
            this.road=road; this.uf=uf; this.direction=direction; this.segment=segment;
            // LOCAL_RADIO_V173: direction and state are display metadata, not room scope.
            // The room is the road plus a local geographic segment, so BR-101/Campos and BR-101/Vitória never meet.
            roomKey = (road.replace("-","")+"|"+segment).toUpperCase(Locale.ROOT);
            label = road+" · "+segmentLabel+(uf==null||uf.isEmpty()?"":" · "+uf);
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
                String road=KnownRoadCatalog.canonical(props.optString("ref",""));
                if(road.isEmpty()) road=KnownRoadCatalog.canonical(props.optString("name",""));
                if(road.isEmpty()) continue;
                JSONObject g=f.optJSONObject("geometry"); if(g==null||!"LineString".equalsIgnoreCase(g.optString("type","")))continue;
                JSONArray c=g.optJSONArray("coordinates"); if(c==null||c.length()<2)continue;
                for(int j=1;j<c.length();j++){
                    JSONArray a=c.optJSONArray(j-1),b=c.optJSONArray(j); if(a==null||b==null||a.length()<2||b.length()<2)continue;
                    double d=segmentDistance(lat,lon,a.optDouble(1,Double.NaN),a.optDouble(0,Double.NaN),b.optDouble(1,Double.NaN),b.optDouble(0,Double.NaN));
                    if(d<best){best=d;bestRoad=road;}
                }
            }
            if(bestRoad.isEmpty()||best>110.0)return null;
            return create(bestRoad, lat, lon, heading);
        } catch(Throwable ignored){ return null; }
    }

    static Identity fromKnownRoad(String road, double lat, double lon, float heading) {
        String c = KnownRoadCatalog.canonical(road);
        if (c.isEmpty() || !Double.isFinite(lat) || !Double.isFinite(lon)) return null;
        return create(c, lat, lon, heading);
    }

    private static Identity create(String road, double lat, double lon, float heading) {
        return new Identity(road, KnownRoadCatalog.uf(lat,lon,road), direction(heading),
                KnownRoadCatalog.segmentKey(lat,lon), KnownRoadCatalog.segmentLabel(lat,lon));
    }

    private static String direction(float h){
        if(!Float.isFinite(h))return"GERAL"; float v=((h%360)+360)%360;
        if(v<45||v>=315)return"NORTE"; if(v<135)return"LESTE"; if(v<225)return"SUL"; return"OESTE";
    }
    private static double segmentDistance(double lat,double lon,double lat1,double lon1,double lat2,double lon2){
        if(!Double.isFinite(lat1)||!Double.isFinite(lon1)||!Double.isFinite(lat2)||!Double.isFinite(lon2))return Double.MAX_VALUE;
        double cos=Math.max(.25,Math.cos(Math.toRadians(lat))); double x1=(lon1-lon)*111320*cos,y1=(lat1-lat)*110540,x2=(lon2-lon)*111320*cos,y2=(lat2-lat)*110540;
        double dx=x2-x1,dy=y2-y1,den=dx*dx+dy*dy,t=den<.001?0:-(x1*dx+y1*dy)/den; t=Math.max(0,Math.min(1,t));
        return Math.hypot(x1+t*dx,y1+t*dy);
    }
}

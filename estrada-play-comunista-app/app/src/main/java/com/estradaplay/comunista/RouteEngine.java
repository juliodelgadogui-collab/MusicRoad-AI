package com.estradaplay.comunista;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

// NAV_POLISH_V201: routing separates maneuver distance, action and road for a real automotive HUD.
final class RouteEngine {
    static final class Route {
        final String geoJson;
        final double distanceM, durationS, nextDistanceM;
        final String nextInstruction, nextRoad, maneuverType, maneuverModifier;
        Route(String geoJson,double distanceM,double durationS,String instruction,double nextDistanceM,String road,String type,String modifier){
            this.geoJson=geoJson;this.distanceM=distanceM;this.durationS=durationS;this.nextInstruction=instruction==null?"":instruction;
            this.nextDistanceM=Math.max(0,nextDistanceM);this.nextRoad=road==null?"":road;this.maneuverType=type==null?"":type;this.maneuverModifier=modifier==null?"":modifier;
        }
        String summary(){String d=distanceM>=1000?String.format(Locale.getDefault(),"%.1f km",distanceM/1000.0):Math.max(0,Math.round(distanceM))+" m";long min=Math.max(1,Math.round(durationS/60.0)),h=min/60,m=min%60;return d+" · "+(h>0?h+"h "+m+"min":m+" min");}
    }
    private static final class Maneuver {final String action,road,type,modifier;final double distance;Maneuver(String a,String r,String t,String m,double d){action=a;road=r;type=t;modifier=m;distance=d;}}
    private RouteEngine(){}

    static Route fetch(double fromLat,double fromLon,double toLat,double toLon)throws Exception{
        String url=String.format(Locale.US,"https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson&steps=true&alternatives=true&continue_straight=true&radiuses=500;500",fromLon,fromLat,toLon,toLat);
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(10000);c.setReadTimeout(25000);c.setInstanceFollowRedirects(true);c.setRequestProperty("Accept","application/json");c.setRequestProperty("User-Agent","EstradaPlayComunista/2.0.1 Android");
        int code=c.getResponseCode();if(code<200||code>=300){c.disconnect();throw new Exception("HTTP "+code);}String raw;
        try(InputStream in=c.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){byte[]buf=new byte[32768];int n;while((n=in.read(buf))>0){bytes.write(buf,0,n);if(bytes.size()>8_000_000)throw new Exception("Rota grande demais");}raw=new String(bytes.toByteArray(),StandardCharsets.UTF_8);}finally{c.disconnect();}
        JSONObject root=new JSONObject(raw);JSONArray routes=root.optJSONArray("routes");if(routes==null||routes.length()==0)throw new Exception("Rota não encontrada");JSONObject r=chooseRoute(routes,fromLat,fromLon,toLat,toLon);if(r==null)throw new Exception("Rota incoerente para este destino");JSONObject geometry=r.optJSONObject("geometry");if(geometry==null)throw new Exception("Geometria ausente");
        JSONObject feature=new JSONObject();feature.put("type","Feature");feature.put("properties",new JSONObject());feature.put("geometry",geometry);JSONArray features=new JSONArray();features.put(feature);JSONObject collection=new JSONObject();collection.put("type","FeatureCollection");collection.put("features",features);
        Maneuver m=firstManeuver(r);return new Route(collection.toString(),r.optDouble("distance",0),r.optDouble("duration",0),m.action,m.distance,m.road,m.type,m.modifier);
    }

    private static JSONObject chooseRoute(JSONArray routes,double fromLat,double fromLon,double toLat,double toLon){ArrayList<JSONObject> valid=new ArrayList<>();double fastest=Double.POSITIVE_INFINITY,straight=distanceM(fromLat,fromLon,toLat,toLon);for(int i=0;i<routes.length();i++){JSONObject r=routes.optJSONObject(i);if(r==null)continue;double dist=r.optDouble("distance",0),dur=r.optDouble("duration",0);JSONObject g=r.optJSONObject("geometry");if(dist<=0||dur<=0||g==null)continue;JSONArray coords=g.optJSONArray("coordinates");if(coords==null||coords.length()<2)continue;JSONArray first=coords.optJSONArray(0),last=coords.optJSONArray(coords.length()-1);if(first==null||last==null||first.length()<2||last.length()<2)continue;double startGap=distanceM(fromLat,fromLon,first.optDouble(1),first.optDouble(0)),endGap=distanceM(toLat,toLon,last.optDouble(1),last.optDouble(0));if(startGap>1200||endGap>1200)continue;valid.add(r);fastest=Math.min(fastest,dur);}if(valid.isEmpty())return null;JSONObject best=null;double scoreBest=Double.POSITIVE_INFINITY;for(JSONObject r:valid){double dist=r.optDouble("distance",0),dur=r.optDouble("duration",0),score=dist+Math.max(0,dur-fastest)*8.0;if(score<scoreBest){scoreBest=score;best=r;}}if(best==null)return null;double chosen=best.optDouble("distance",0);if(straight>=1200&&straight<=15000){double max=Math.max(straight*3.2,straight+12000);if(chosen>max)return null;}return best;}
    private static double distanceM(double lat1,double lon1,double lat2,double lon2){if(!Double.isFinite(lat1)||!Double.isFinite(lon1)||!Double.isFinite(lat2)||!Double.isFinite(lon2))return Double.POSITIVE_INFINITY;double p1=Math.toRadians(lat1),p2=Math.toRadians(lat2),dLat=p2-p1,dLon=Math.toRadians(lon2-lon1),a=Math.sin(dLat/2)*Math.sin(dLat/2)+Math.cos(p1)*Math.cos(p2)*Math.sin(dLon/2)*Math.sin(dLon/2);return 6371000.0*2.0*Math.atan2(Math.sqrt(a),Math.sqrt(Math.max(0,1-a)));}
    private static Maneuver firstManeuver(JSONObject route){JSONArray legs=route.optJSONArray("legs");if(legs==null||legs.length()==0)return new Maneuver("Siga na rota","","","",0);JSONObject leg=legs.optJSONObject(0);JSONArray steps=leg==null?null:leg.optJSONArray("steps");if(steps==null)return new Maneuver("Siga na rota","","","",0);double before=0;for(int i=0;i<steps.length();i++){JSONObject step=steps.optJSONObject(i);if(step==null)continue;JSONObject man=step.optJSONObject("maneuver");if(man==null){before+=Math.max(0,step.optDouble("distance",0));continue;}String type=man.optString("type",""),modifier=man.optString("modifier",""),road=step.optString("name","").trim();if("depart".equals(type)){before+=Math.max(0,step.optDouble("distance",0));continue;}String action;if("arrive".equals(type))action="Chegada ao destino";else if("roundabout".equals(type)||"rotary".equals(type))action="Entre na rotatória";else if(modifier.contains("slight right"))action="Mantenha-se levemente à direita";else if(modifier.contains("slight left"))action="Mantenha-se levemente à esquerda";else if(modifier.contains("right"))action="Vire à direita";else if(modifier.contains("left"))action="Vire à esquerda";else if("merge".equals(type))action="Entre na via";else action="Siga em frente";return new Maneuver(action,road,type,modifier,type.equals("arrive")?Math.max(before,20):Math.max(before,15));}return new Maneuver("Siga na rota","","","",0);}
}

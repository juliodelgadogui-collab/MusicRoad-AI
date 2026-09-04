package com.estradaplay.patriota;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

/** COMBOIO_AVANCADO_V230: persistent group state, leader route and privacy-safe member ids. */
final class ConvoyStore {
    private static final String PREF="epp_convoy_v180",KEY_CODE="code",KEY_SNAPSHOT="snapshot_v230";
    private final Context app; private final SharedPreferences prefs; private final ApiClient api;
    ConvoyStore(Context c){app=c.getApplicationContext();prefs=app.getSharedPreferences(PREF,Context.MODE_PRIVATE);api=new ApiClient(app);}

    static final class Member {
        final String memberId,nickname,presence,separationStatus;
        final double lat,lon,speedKmh,distanceToLeaderM,routeDeviationM,distanceToDestinationM;
        final float heading; final long lastSeen,ageS,etaS; final boolean self,leader,offRoute;
        Member(String memberId,String nickname,double lat,double lon,double speedKmh,float heading,long lastSeen,long ageS,
               boolean self,boolean leader,String presence,double distanceToLeaderM,String separationStatus,
               double routeDeviationM,boolean offRoute,double distanceToDestinationM,long etaS){
            this.memberId=clean(memberId);this.nickname=clean(nickname).isEmpty()?"MOTORISTA":clean(nickname);this.lat=lat;this.lon=lon;this.speedKmh=Math.max(0,speedKmh);this.heading=heading;this.lastSeen=lastSeen;this.ageS=Math.max(0,ageS);this.self=self;this.leader=leader;this.presence=clean(presence);this.distanceToLeaderM=distanceToLeaderM;this.separationStatus=clean(separationStatus);this.routeDeviationM=routeDeviationM;this.offRoute=offRoute;this.distanceToDestinationM=distanceToDestinationM;this.etaS=Math.max(0,etaS);
        }
        static Member from(JSONObject o){if(o==null)return null;String id=o.optString("member_id",o.optString("device",""));return new Member(id,o.optString("nickname","MOTORISTA"),nullableDouble(o,"lat"),nullableDouble(o,"lon"),o.optDouble("speed_kmh",0),o.isNull("heading")?Float.NaN:(float)o.optDouble("heading",Double.NaN),o.optLong("last_seen",0),o.optLong("age_s",0),o.optBoolean("self",false),o.optBoolean("leader",false),o.optString("presence","ONLINE"),nullableDouble(o,"distance_to_leader_m"),o.optString("separation_status","OK"),nullableDouble(o,"route_deviation_m"),o.optBoolean("off_route",false),nullableDouble(o,"distance_to_destination_m"),o.optLong("eta_s",0));}
        String shortName(){String n=nickname;if(n.length()>12)n=n.substring(0,12);return leader?"★ "+n:n;}
        boolean online(){return "ONLINE".equalsIgnoreCase(presence);}
    }

    static final class SharedDestination {
        final String label; final double lat,lon; final long updatedAt;
        SharedDestination(String label,double lat,double lon,long updatedAt){this.label=clean(label).isEmpty()?"Destino do comboio":clean(label);this.lat=lat;this.lon=lon;this.updatedAt=updatedAt;}
    }

    String code(){return normalize(prefs.getString(KEY_CODE,""));}
    void clear(){prefs.edit().remove(KEY_CODE).remove(KEY_SNAPSHOT).apply();}
    JSONObject lastSnapshot(){try{return new JSONObject(prefs.getString(KEY_SNAPSHOT,"{}"));}catch(Exception e){return new JSONObject();}}
    void saveSnapshot(JSONObject j){if(j!=null&&j.optBoolean("ok",false))prefs.edit().putString(KEY_SNAPSHOT,j.toString()).apply();}

    JSONObject create(Location l) throws Exception {JSONObject d=base("create",l);d.put("title","Comboio Estrada Play");JSONObject j=post(d);if(j.optBoolean("ok",false)){String c=normalize(j.optString("code",""));if(!c.isEmpty())prefs.edit().putString(KEY_CODE,c).apply();saveSnapshot(j);}return j;}
    JSONObject join(String code,Location l) throws Exception {String c=normalize(code);JSONObject d=base("join",l);d.put("code",c);JSONObject j=post(d);if(j.optBoolean("ok",false)){prefs.edit().putString(KEY_CODE,c).apply();saveSnapshot(j);}return j;}
    JSONObject ping(Location l) throws Exception {if(l==null)return state();return ping(l.getLatitude(),l.getLongitude(),l.hasSpeed()?l.getSpeed()*3.6:0,l.hasBearing()?l.getBearing():Double.NaN);}
    JSONObject ping(double lat,double lon,double speedKmh,double heading) throws Exception {String c=code();if(c.isEmpty())return new JSONObject();JSONObject d=base("ping",null);d.put("code",c);d.put("lat",lat);d.put("lon",lon);d.put("speed_kmh",Math.max(0,speedKmh));if(Double.isFinite(heading))d.put("heading",heading);JSONObject j=post(d);handleMembershipFailure(j);saveSnapshot(j);return j;}
    JSONObject state() throws Exception {String c=code();if(c.isEmpty())return new JSONObject();JSONObject d=base("state",null);d.put("code",c);JSONObject j=post(d);handleMembershipFailure(j);saveSnapshot(j);return j;}
    JSONObject leave() throws Exception {String c=code();JSONObject d=base("leave",null);d.put("code",c);JSONObject j=post(d);if(j.optBoolean("ok",false))clear();return j;}
    JSONObject clearRoute() throws Exception {JSONObject d=base("clear_route",null);d.put("code",code());JSONObject j=post(d);saveSnapshot(j);return j;}
    JSONObject kick(String memberId) throws Exception {JSONObject d=base("kick",null);d.put("code",code());d.put("member_id",clean(memberId));JSONObject j=post(d);saveSnapshot(j);return j;}

    JSONObject setRoute(DestinationStore.Destination destination, RouteEngine.Route route) throws Exception {
        if(destination==null||!Double.isFinite(destination.lat)||!Double.isFinite(destination.lon))return new JSONObject().put("ok",false).put("error","Escolha um destino antes de compartilhar a rota.");
        JSONObject d=base("set_route",null);d.put("code",code());d.put("destination_label",destination.label);d.put("destination_lat",destination.lat);d.put("destination_lon",destination.lon);d.put("route_points",compactRoutePoints(route));JSONObject j=post(d);saveSnapshot(j);return j;
    }

    ArrayList<Member> members(JSONObject j){ArrayList<Member> out=new ArrayList<>();if(j==null)return out;JSONArray a=j.optJSONArray("members");if(a==null)return out;for(int i=0;i<a.length();i++){Member m=Member.from(a.optJSONObject(i));if(m!=null)out.add(m);}return out;}
    boolean selfIsLeader(JSONObject j){return j!=null&&j.optBoolean("self_is_leader",false);}
    SharedDestination destination(JSONObject j){if(j==null)return null;JSONObject d=j.optJSONObject("destination");if(d==null)return null;double lat=d.optDouble("lat",Double.NaN),lon=d.optDouble("lon",Double.NaN);if(!Double.isFinite(lat)||!Double.isFinite(lon))return null;return new SharedDestination(d.optString("label","Destino do comboio"),lat,lon,d.optLong("updated_at",0));}

    private JSONObject post(JSONObject d) throws Exception {ApiClient.Response r=api.post("api/convoy.php",d);JSONObject j=r.json();if(!r.ok()&&!j.has("ok"))j.put("ok",false);return j;}
    private void handleMembershipFailure(JSONObject j){if(j==null||j.optBoolean("ok",false))return;String e=j.optString("error","").toLowerCase(Locale.ROOT);if(e.contains("não faz parte")||e.contains("nao faz parte")||e.contains("removido")||e.contains("expirado")||e.contains("entre novamente"))clear();}
    private JSONObject base(String action,Location l){JSONObject d=new JSONObject();try{d.put("action",action);d.put("nickname",nickname());if(l!=null){d.put("lat",l.getLatitude());d.put("lon",l.getLongitude());d.put("speed_kmh",l.hasSpeed()?Math.max(0,l.getSpeed()*3.6):0);if(l.hasBearing())d.put("heading",l.getBearing());}}catch(Throwable ignored){}return d;}

    // EPP_CONVOY_ACCOUNT_NAME_V239: show the signed-in driver's name on maps; never expose email.
    private String nickname(){
        try{
            String raw=app.getSharedPreferences("estradaplay_ui_v1",Context.MODE_PRIVATE).getString("account","{}");
            JSONObject account=new JSONObject(raw==null?"{}":raw);
            JSONObject user=account.optJSONObject("user");
            if(user!=null){
                String n=clean(user.optString("name",""));
                if(n.isEmpty())n=clean(user.optString("username",""));
                if(!n.isEmpty())return n.length()>36?n.substring(0,36):n;
            }
        }catch(Throwable ignored){}
        String t=DeviceIdentity.token(app);String suffix=t==null||t.length()<4?"0000":t.substring(t.length()-4).toUpperCase(Locale.ROOT);return"MOTORISTA-"+suffix;
    }

    private static JSONArray compactRoutePoints(RouteEngine.Route route){JSONArray out=new JSONArray();if(route==null||route.geoJson==null||route.geoJson.trim().isEmpty())return out;try{JSONObject collection=new JSONObject(route.geoJson);JSONArray features=collection.optJSONArray("features");JSONObject feature=features==null?null:features.optJSONObject(0);JSONObject geometry=feature==null?null:feature.optJSONObject("geometry");JSONArray coords=geometry==null?null:geometry.optJSONArray("coordinates");if(coords==null||coords.length()<2)return out;int wanted=Math.min(48,coords.length());for(int i=0;i<wanted;i++){int index=wanted==1?0:(int)Math.round((i/(double)(wanted-1))*(coords.length()-1));JSONArray p=coords.optJSONArray(Math.min(coords.length()-1,index));if(p==null||p.length()<2)continue;JSONObject q=new JSONObject();q.put("lat",p.optDouble(1));q.put("lon",p.optDouble(0));out.put(q);}}catch(Exception ignored){}return out;}
    private static double nullableDouble(JSONObject o,String key){return o==null||o.isNull(key)?Double.NaN:o.optDouble(key,Double.NaN);}
    private static String clean(String s){return s==null?"":s.trim();}
    static String normalize(String raw){if(raw==null)return"";StringBuilder b=new StringBuilder();String x=raw.toUpperCase(Locale.ROOT);for(int i=0;i<x.length()&&b.length()<8;i++){char c=x.charAt(i);if((c>='A'&&c<='Z')||(c>='0'&&c<='9'))b.append(c);}return b.toString();}
}

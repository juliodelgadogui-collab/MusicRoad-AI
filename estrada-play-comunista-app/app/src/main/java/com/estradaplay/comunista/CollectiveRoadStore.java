package com.estradaplay.comunista;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import org.json.JSONArray;
import org.json.JSONObject;

final class CollectiveRoadStore {
    private static final String P="epc_collective_v150",Q="queue",R="radar_pending",I="impact_count";
    private final Context app; private final SharedPreferences prefs;
    CollectiveRoadStore(Context c){app=c.getApplicationContext();prefs=app.getSharedPreferences(P,Context.MODE_PRIVATE);}
    synchronized void recordImpact(RoadSurfaceMonitor.Impact x){if(x==null)return;try{JSONObject o=new JSONObject();o.put("event_type","road_surface");o.put("lat",x.lat);o.put("lon",x.lon);o.put("speed_kmh",x.speedKmh);o.put("severity",x.force);o.put("occurred_at",x.at);enqueue(o);prefs.edit().putInt(I,prefs.getInt(I,0)+1).apply();}catch(Throwable ignored){}}
    synchronized void addRadarConfirmation(RoadHazard h,Location loc){if(h==null||loc==null||!"RADAR".equals(h.type))return;try{JSONArray a=pending();for(int i=0;i<a.length();i++){JSONObject p=a.optJSONObject(i);if(p!=null&&h.id.equals(p.optString("radar_id")))return;}JSONObject o=new JSONObject();o.put("radar_id",h.id);o.put("road",h.road);o.put("limit_kmh",h.speed);o.put("source",h.source);o.put("lat",h.lat);o.put("lon",h.lon);o.put("seen_at",System.currentTimeMillis());JSONArray out=new JSONArray();out.put(o);for(int i=0;i<a.length()&&out.length()<20;i++)out.put(a.opt(i));prefs.edit().putString(R,out.toString()).apply();}catch(Throwable ignored){}}
    synchronized JSONArray pending(){try{return new JSONArray(prefs.getString(R,"[]"));}catch(Throwable ignored){return new JSONArray();}}
    synchronized boolean resolveRadar(String radarId,String answer){try{JSONArray a=pending(),out=new JSONArray();JSONObject chosen=null;for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;if(chosen==null&&radarId.equals(o.optString("radar_id")))chosen=o;else out.put(o);}if(chosen==null)return false;JSONObject event=new JSONObject(chosen.toString());event.put("event_type","radar_confirmation");event.put("answer",answer==null?"unknown":answer);event.put("confirmed_at",System.currentTimeMillis());enqueue(event);prefs.edit().putString(R,out.toString()).apply();return true;}catch(Throwable ignored){return false;}}
    synchronized int impactCount(){return prefs.getInt(I,0);}
    synchronized int queuedCount(){try{return new JSONArray(prefs.getString(Q,"[]")).length();}catch(Throwable ignored){return 0;}}
    synchronized void flush(ApiClient api){if(api==null||!DriveSettings.collectiveEnabled(app))return;JSONArray a=queue(),keep=new JSONArray();for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;boolean ok=false;try{ApiClient.Response r=api.post("api/road_collective.php",o);ok=r.ok()&&r.json().optBoolean("ok",false);}catch(Throwable ignored){}if(!ok)keep.put(o);}prefs.edit().putString(Q,keep.toString()).apply();}
    private JSONArray queue(){try{return new JSONArray(prefs.getString(Q,"[]"));}catch(Throwable ignored){return new JSONArray();}}
    private void enqueue(JSONObject o){JSONArray old=queue(),out=new JSONArray();out.put(o);for(int i=0;i<old.length()&&out.length()<100;i++)out.put(old.opt(i));prefs.edit().putString(Q,out.toString()).apply();}
}

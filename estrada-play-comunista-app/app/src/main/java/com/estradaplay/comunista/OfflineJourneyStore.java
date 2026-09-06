package com.estradaplay.comunista;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;

/** Lightweight offline index for recent routes, destinations, alerts and downloaded regions. */
final class OfflineJourneyStore {
    private static final String PREF="epc_offline_journey_v301";
    private static final String DEST="destinations",ROUTES="routes",ALERTS="alerts",REGIONS="regions";
    private final SharedPreferences p;
    OfflineJourneyStore(Context c){p=c.getApplicationContext().getSharedPreferences(PREF,Context.MODE_PRIVATE);}

    void rememberDestination(String label,double lat,double lon){if(!Double.isFinite(lat)||!Double.isFinite(lon))return;try{JSONObject o=new JSONObject();o.put("label",label==null?"Destino":label);o.put("lat",lat);o.put("lon",lon);o.put("at",System.currentTimeMillis());prepend(DEST,o,20);}catch(Throwable ignored){}}
    void rememberRoute(String from,String to,double distanceM,long durationS){try{JSONObject o=new JSONObject();o.put("from",from==null?"":from);o.put("to",to==null?"":to);o.put("distance_m",Math.max(0,distanceM));o.put("duration_s",Math.max(0,durationS));o.put("at",System.currentTimeMillis());prepend(ROUTES,o,12);}catch(Throwable ignored){}}
    void rememberAlert(String type,String label,double lat,double lon,long expiresAt){try{JSONObject o=new JSONObject();o.put("type",type==null?"":type);o.put("label",label==null?"":label);o.put("lat",lat);o.put("lon",lon);o.put("expires_at",expiresAt);o.put("at",System.currentTimeMillis());prepend(ALERTS,o,100);}catch(Throwable ignored){}}
    void rememberRegion(String id,String title,long revision,boolean mapReady){if(id==null||id.trim().isEmpty())return;try{JSONObject o=new JSONObject();o.put("id",id.trim());o.put("title",title==null?id:title);o.put("revision",revision);o.put("map_ready",mapReady);o.put("at",System.currentTimeMillis());JSONArray old=array(REGIONS),out=new JSONArray();out.put(o);for(int i=0;i<old.length()&&out.length()<30;i++){JSONObject x=old.optJSONObject(i);if(x!=null&&!id.equals(x.optString("id")))out.put(x);}p.edit().putString(REGIONS,out.toString()).apply();}catch(Throwable ignored){}}
    ArrayList<JSONObject> recentDestinations(){return list(DEST,20,true);}
    ArrayList<JSONObject> recentRoutes(){return list(ROUTES,12,true);}
    ArrayList<JSONObject> activeAlerts(){return list(ALERTS,100,false);}
    ArrayList<JSONObject> regions(){return list(REGIONS,30,true);}

    private synchronized void prepend(String key,JSONObject o,int max){JSONArray old=array(key),out=new JSONArray();out.put(o);for(int i=0;i<old.length()&&out.length()<max;i++)out.put(old.opt(i));p.edit().putString(key,out.toString()).apply();}
    private ArrayList<JSONObject> list(String key,int max,boolean includeExpired){ArrayList<JSONObject> out=new ArrayList<>();JSONArray a=array(key);long now=System.currentTimeMillis();for(int i=0;i<a.length()&&out.size()<max;i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;if(!includeExpired){long ex=o.optLong("expires_at",0);if(ex>0&&ex<now)continue;}out.add(o);}return out;}
    private JSONArray array(String key){try{return new JSONArray(p.getString(key,"[]"));}catch(Throwable e){return new JSONArray();}}
}

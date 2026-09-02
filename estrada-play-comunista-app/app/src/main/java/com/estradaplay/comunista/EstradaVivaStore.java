package com.estradaplay.comunista;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local-first community road alerts. Reports queue offline and synchronize when possible. */
final class EstradaVivaStore {
    private static final String PREF="epc_estrada_viva_v180", KEY_QUEUE="queue", KEY_CACHE="cache", KEY_CACHE_AT="cache_at";
    private static final ExecutorService NET=Executors.newSingleThreadExecutor();
    private static final AtomicBoolean BUSY=new AtomicBoolean(false);
    private static volatile long lastKickAt;
    private final Context app;
    private final SharedPreferences prefs;

    EstradaVivaStore(Context c){app=c.getApplicationContext();prefs=app.getSharedPreferences(PREF,Context.MODE_PRIVATE);}

    static final class Event {
        final long id; final String type,road,note; final double lat,lon; final int confirmations; final long occurredAt,createdAt,expiresAt,distanceM;
        Event(long id,String type,String road,String note,double lat,double lon,int confirmations,long occurredAt,long createdAt,long expiresAt,long distanceM){this.id=id;this.type=type;this.road=road;this.note=note;this.lat=lat;this.lon=lon;this.confirmations=confirmations;this.occurredAt=occurredAt;this.createdAt=createdAt;this.expiresAt=expiresAt;this.distanceM=distanceM;}
        static Event from(JSONObject o,double refLat,double refLon){
            if(o==null)return null;double lat=o.optDouble("lat",Double.NaN),lon=o.optDouble("lon",Double.NaN);if(!Double.isFinite(lat)||!Double.isFinite(lon))return null;
            long id=o.has("id")?o.optLong("id",0):o.optLong("local_id",0);String type=o.optString("type",o.optString("event_type",""));
            long d=Double.isFinite(refLat)&&Double.isFinite(refLon)?Math.round(RoadPackStore.distanceM(refLat,refLon,lat,lon)):o.optLong("distance_m",0);
            return new Event(id,type,o.optString("road",""),o.optString("note",""),lat,lon,o.optInt("confirmations",0),o.optLong("occurred_at",0),o.optLong("created_at",o.optLong("occurred_at",0)),o.optLong("expires_at",0),d);
        }
        String label(){return typeLabel(type);}
        String shortLabel(){String s=label();return s.length()<=5?s:s.substring(0,Math.min(5,s.length()));}
        String distanceLabel(){if(distanceM<1000)return Math.max(0,distanceM)+" m";return String.format(Locale.getDefault(),"%.1f km",distanceM/1000.0);}
    }

    void report(String type,double lat,double lon,String road,String note){
        if(!Double.isFinite(lat)||!Double.isFinite(lon))return;String t=normalizeType(type);if(t.isEmpty())return;
        try{long now=System.currentTimeMillis();JSONObject o=new JSONObject();o.put("local_id",-now);o.put("event_type",t);o.put("type",t);o.put("lat",lat);o.put("lon",lon);o.put("road",road==null?"":road.trim());o.put("note",note==null?"":note.trim());o.put("occurred_at",now);o.put("created_at",now);enqueue(o);prependCache(o);kickRefresh(lat,lon,true);}catch(Throwable ignored){}
    }

    int queuedCount(){try{return new JSONArray(prefs.getString(KEY_QUEUE,"[]")).length();}catch(Throwable e){return 0;}}
    long cacheAgeMs(){long at=prefs.getLong(KEY_CACHE_AT,0);return at<=0?Long.MAX_VALUE:Math.max(0,System.currentTimeMillis()-at);}

    ArrayList<Event> cachedNearby(double lat,double lon,double radiusM){
        ArrayList<Event> out=new ArrayList<>();HashSet<String> seen=new HashSet<>();
        appendEvents(out,seen,prefs.getString(KEY_QUEUE,"[]"),lat,lon,radiusM);
        appendEvents(out,seen,prefs.getString(KEY_CACHE,"[]"),lat,lon,radiusM);
        out.sort((a,b)->{int d=Long.compare(a.distanceM,b.distanceM);if(d!=0)return d;return Long.compare(b.createdAt,a.createdAt);});
        return out;
    }

    void kickRefresh(double lat,double lon){kickRefresh(lat,lon,false);}
    void kickRefresh(double lat,double lon,boolean force){
        if(!Double.isFinite(lat)||!Double.isFinite(lon)||DriveSettings.offlineTestMode(app))return;
        long now=System.currentTimeMillis();if(!force&&now-lastKickAt<25000L)return;lastKickAt=now;if(!BUSY.compareAndSet(false,true))return;
        NET.execute(()->{try{syncPending();fetchNearby(lat,lon);}finally{BUSY.set(false);}});
    }

    void confirm(long eventId,double lat,double lon){
        if(eventId<=0||DriveSettings.offlineTestMode(app))return;
        NET.execute(()->{try{JSONObject d=new JSONObject();d.put("action","confirm");d.put("event_id",eventId);ApiClient.Response r=new ApiClient(app).post("api/road_live.php",d);if(r.ok()&&r.json().optBoolean("ok",false))fetchNearby(lat,lon);}catch(Throwable ignored){}});
    }

    private void syncPending(){
        JSONArray old=queue(),keep=new JSONArray();ApiClient api=new ApiClient(app);
        for(int i=0;i<old.length();i++){JSONObject src=old.optJSONObject(i);if(src==null)continue;boolean ok=false;try{JSONObject d=new JSONObject(src.toString());d.put("action","report");d.remove("local_id");ApiClient.Response r=api.post("api/road_live.php",d);ok=r.ok()&&r.json().optBoolean("ok",false);}catch(Throwable ignored){}if(!ok)keep.put(src);}
        prefs.edit().putString(KEY_QUEUE,keep.toString()).apply();
    }

    private void fetchNearby(double lat,double lon){
        try{JSONObject d=new JSONObject();d.put("action","nearby");d.put("lat",lat);d.put("lon",lon);d.put("radius_km",15);ApiClient.Response r=new ApiClient(app).post("api/road_live.php",d);JSONObject j=r.json();JSONArray a=j.optJSONArray("events");if(r.ok()&&j.optBoolean("ok",false)&&a!=null)prefs.edit().putString(KEY_CACHE,a.toString()).putLong(KEY_CACHE_AT,System.currentTimeMillis()).apply();}catch(Throwable ignored){}
    }

    private synchronized JSONArray queue(){try{return new JSONArray(prefs.getString(KEY_QUEUE,"[]"));}catch(Throwable e){return new JSONArray();}}
    private synchronized void enqueue(JSONObject o){JSONArray a=queue(),out=new JSONArray();out.put(o);for(int i=0;i<a.length()&&out.length()<100;i++)out.put(a.opt(i));prefs.edit().putString(KEY_QUEUE,out.toString()).apply();}
    private synchronized void prependCache(JSONObject o){try{JSONArray a=new JSONArray(prefs.getString(KEY_CACHE,"[]")),out=new JSONArray();out.put(o);for(int i=0;i<a.length()&&out.length()<120;i++)out.put(a.opt(i));prefs.edit().putString(KEY_CACHE,out.toString()).apply();}catch(Throwable ignored){}}
    private void appendEvents(ArrayList<Event> out,HashSet<String> seen,String raw,double lat,double lon,double radiusM){try{JSONArray a=new JSONArray(raw==null?"[]":raw);long now=System.currentTimeMillis();for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);Event e=Event.from(o,lat,lon);if(e==null||e.distanceM>radiusM)continue;if(e.expiresAt>0&&e.expiresAt<now)continue;String k=e.id!=0?"i"+e.id:e.type+"/"+Math.round(e.lat*10000)+"/"+Math.round(e.lon*10000);if(seen.add(k))out.add(e);}}catch(Throwable ignored){}}

    static String normalizeType(String raw){String t=raw==null?"":raw.trim().toLowerCase(Locale.ROOT);switch(t){case"pothole":case"animal":case"accident":case"construction":case"flooding":case"traffic":case"object":return t;default:return"";}}
    static String typeLabel(String t){switch(normalizeType(t)){case"pothole":return"BURACO";case"animal":return"ANIMAL";case"accident":return"ACIDENTE";case"construction":return"OBRA";case"flooding":return"ALAGAMENTO";case"traffic":return"TRÂNSITO";case"object":return"OBJETO";default:return"ALERTA";}}
}

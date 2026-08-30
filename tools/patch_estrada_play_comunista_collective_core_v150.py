#!/usr/bin/env python3
from pathlib import Path

ROOT=Path('estrada-play-comunista-app')
J=ROOT/'app/src/main/java/com/estradaplay/comunista'

def read(p): return p.read_text(encoding='utf-8')
def write(p,s): p.write_text(s,encoding='utf-8')
def rep(s,old,new,label):
    if old not in s: raise SystemExit('missing anchor: '+label)
    return s.replace(old,new,1)

# Version
p=ROOT/'app/build.gradle'; s=read(p)
s=rep(s,'versionCode 142','versionCode 150','version code')
s=rep(s,"versionName '1.4.2'","versionName '1.5.0'",'version name')
write(p,s)

# Driving preferences used by collective intelligence and trip cost.
write(J/'DriveSettings.java',r'''package com.estradaplay.comunista;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.Calendar;
final class DriveSettings {
    private static final String P="epc_drive_settings_v150";
    static SharedPreferences p(Context c){return c.getSharedPreferences(P,Context.MODE_PRIVATE);}
    static boolean autoNight(Context c){return p(c).getBoolean("auto_night",true);}
    static boolean hudMirror(Context c){return p(c).getBoolean("hud_mirror",true);}
    static boolean collectiveEnabled(Context c){return p(c).getBoolean("collective_enabled",true);}
    static boolean protectImpactVideo(Context c){return p(c).getBoolean("protect_impact_video",true);}
    static boolean nightNow(Context c){if(!autoNight(c))return p(c).getBoolean("night_force",false);int h=Calendar.getInstance().get(Calendar.HOUR_OF_DAY);return h>=19||h<6;}
    static void toggle(Context c,String key,boolean value){p(c).edit().putBoolean(key,value).apply();}
    static float consumptionKml(Context c){return Math.max(1f,p(c).getFloat("consumption_kml",10f));}
    static float fuelPrice(Context c){return Math.max(0f,p(c).getFloat("fuel_price",0f));}
    static void setVehicleCost(Context c,float kmL,float price){p(c).edit().putFloat("consumption_kml",Math.max(1f,kmL)).putFloat("fuel_price",Math.max(0f,price)).apply();}
    static int impactSensitivity(Context c){return Math.max(0,Math.min(2,p(c).getInt("impact_sensitivity",1)));}
    static void setImpactSensitivity(Context c,int value){p(c).edit().putInt("impact_sensitivity",Math.max(0,Math.min(2,value))).apply();}
}
''')

# Road surface sensor. Detection is local and ignored while nearly stopped.
write(J/'RoadSurfaceMonitor.java',r'''package com.estradaplay.comunista;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;

final class RoadSurfaceMonitor implements SensorEventListener {
    interface Listener { void onImpact(Impact impact); }
    static final class Impact {
        final double lat,lon,speedKmh,force;
        final long at;
        Impact(double lat,double lon,double speedKmh,double force,long at){this.lat=lat;this.lon=lon;this.speedKmh=speedKmh;this.force=force;this.at=at;}
    }
    private final Context app; private final SensorManager manager; private final Sensor sensor; private final Listener listener;
    private volatile double lat=Double.NaN,lon=Double.NaN,speedKmh; private long lastImpactAt;
    RoadSurfaceMonitor(Context c,Listener l){app=c.getApplicationContext();listener=l;manager=(SensorManager)app.getSystemService(Context.SENSOR_SERVICE);Sensor linear=manager==null?null:manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);sensor=linear!=null?linear:(manager==null?null:manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER));}
    void start(){if(manager!=null&&sensor!=null)try{manager.registerListener(this,sensor,SensorManager.SENSOR_DELAY_GAME);}catch(Throwable ignored){}}
    void stop(){if(manager!=null)try{manager.unregisterListener(this);}catch(Throwable ignored){}}
    void updateDriveState(Location l,double speed){if(l!=null){lat=l.getLatitude();lon=l.getLongitude();}speedKmh=Math.max(0,speed);}
    @Override public void onSensorChanged(SensorEvent e){if(e==null||e.values==null||e.values.length<3||speedKmh<15||!Double.isFinite(lat)||!Double.isFinite(lon))return;double x=e.values[0],y=e.values[1],z=e.values[2];double mag=Math.sqrt(x*x+y*y+z*z);if(e.sensor!=null&&e.sensor.getType()==Sensor.TYPE_ACCELEROMETER)mag=Math.abs(mag-SensorManager.GRAVITY_EARTH);double threshold=DriveSettings.impactSensitivity(app)==0?7.4:(DriveSettings.impactSensitivity(app)==2?4.8:5.9);long now=System.currentTimeMillis();if(mag<threshold||now-lastImpactAt<4000L)return;lastImpactAt=now;double severity=Math.min(10.0,Math.max(1.0,(mag-threshold+1.0)*1.7));if(listener!=null)listener.onImpact(new Impact(lat,lon,speedKmh,severity,now));}
    @Override public void onAccuracyChanged(Sensor sensor,int accuracy){}
}
''')

# Small local collective queue. Only coordinates/road events are sent; never media.
write(J/'CollectiveRoadStore.java',r'''package com.estradaplay.comunista;
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
''')

# Richer local trip recorder: timeline, road quality and fuel estimate.
write(J/'TripRecorder.java',r'''package com.estradaplay.comunista;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import org.json.JSONArray;
import org.json.JSONObject;
final class TripRecorder {
    private static final String PREFS="epc_trip_history_v140",KEY_HISTORY="history",KEY_ACTIVE="active"; private static final long IDLE_FINISH_MS=5L*60L*1000L;
    private final Context app; private final SharedPreferences prefs; private JSONObject active; private Location last; private long lastMovingAt,lastPersistAt;
    TripRecorder(Context c){app=c.getApplicationContext();prefs=app.getSharedPreferences(PREFS,Context.MODE_PRIVATE);try{String raw=prefs.getString(KEY_ACTIVE,"");if(raw!=null&&!raw.isEmpty())active=new JSONObject(raw);}catch(Throwable ignored){active=null;}}
    synchronized void onLocation(Location loc,double speedKmh){if(loc==null)return;long now=System.currentTimeMillis();if(speedKmh>=4){if(active==null)start(now,loc);lastMovingAt=now;}else if(active!=null&&lastMovingAt>0&&now-lastMovingAt>=IDLE_FINISH_MS){finish("parada");last=new Location(loc);return;}if(active==null){last=new Location(loc);return;}try{double add=0;if(last!=null){float d=last.distanceTo(loc);if(d>=0&&d<=1500)add=d;}active.put("distance_m",active.optDouble("distance_m",0)+add);active.put("max_speed",Math.max(active.optDouble("max_speed",0),Math.max(0,speedKmh)));active.put("last_lat",loc.getLatitude());active.put("last_lon",loc.getLongitude());active.put("updated_at",now);if(now-lastPersistAt>=12000)persistActive();}catch(Throwable ignored){}last=new Location(loc);}
    synchronized void onHazard(String type){if(active==null)return;try{if("RADAR".equals(type))active.put("radars",active.optInt("radars",0)+1);else if("QUEBRA_MOLAS".equals(type))active.put("bumps",active.optInt("bumps",0)+1);else if("CAMERA_MONITORAMENTO".equals(type))active.put("cameras",active.optInt("cameras",0)+1);else active.put("other_alerts",active.optInt("other_alerts",0)+1);addTimeline(type,type,System.currentTimeMillis(),active.optDouble("last_lat",0),active.optDouble("last_lon",0),0);persistActive();}catch(Throwable ignored){}}
    synchronized void onRoadImpact(RoadSurfaceMonitor.Impact x){if(active==null||x==null)return;try{active.put("road_impacts",active.optInt("road_impacts",0)+1);active.put("roughness_sum",active.optDouble("roughness_sum",0)+x.force);addTimeline("VIA_IRREGULAR","Impacto/irregularidade",x.at,x.lat,x.lon,x.force);persistActive();}catch(Throwable ignored){}}
    synchronized void onRestSuggested(){if(active==null)return;try{addTimeline("PAUSA","Pausa sugerida",System.currentTimeMillis(),active.optDouble("last_lat",0),active.optDouble("last_lon",0),0);persistActive();}catch(Throwable ignored){}}
    synchronized void finish(String reason){if(active==null)return;try{long now=System.currentTimeMillis();active.put("ended_at",now);active.put("duration_ms",Math.max(0,now-active.optLong("started_at",now)));active.put("reason",reason==null?"":reason);double km=active.optDouble("distance_m",0)/1000.0;double liters=km/DriveSettings.consumptionKml(app);active.put("fuel_l",liters);active.put("fuel_cost",liters*DriveSettings.fuelPrice(app));int impacts=active.optInt("road_impacts",0);double score=Math.max(1,Math.min(100,100-(impacts*120.0/Math.max(5.0,km))));active.put("road_quality_score",Math.round(score));JSONArray old=history(prefs),out=new JSONArray();out.put(active);for(int i=0;i<old.length()&&out.length()<30;i++)out.put(old.opt(i));prefs.edit().putString(KEY_HISTORY,out.toString()).remove(KEY_ACTIVE).apply();}catch(Throwable ignored){}active=null;last=null;lastMovingAt=0;}
    private void start(long now,Location loc){active=new JSONObject();try{active.put("started_at",now);active.put("updated_at",now);active.put("start_lat",loc.getLatitude());active.put("start_lon",loc.getLongitude());active.put("distance_m",0);active.put("max_speed",0);active.put("radars",0);active.put("bumps",0);active.put("cameras",0);active.put("other_alerts",0);active.put("road_impacts",0);active.put("timeline",new JSONArray());addTimeline("SAIDA","Viagem iniciada",now,loc.getLatitude(),loc.getLongitude(),0);}catch(Throwable ignored){}lastMovingAt=now;persistActive();}
    private void addTimeline(String type,String label,long at,double lat,double lon,double value){try{JSONArray a=active.optJSONArray("timeline");if(a==null)a=new JSONArray();JSONObject e=new JSONObject();e.put("type",type);e.put("label",label);e.put("at",at);e.put("lat",lat);e.put("lon",lon);if(value>0)e.put("value",value);JSONArray out=new JSONArray();int start=Math.max(0,a.length()-38);for(int i=start;i<a.length();i++)out.put(a.opt(i));out.put(e);active.put("timeline",out);}catch(Throwable ignored){}}
    private void persistActive(){lastPersistAt=System.currentTimeMillis();if(active!=null)prefs.edit().putString(KEY_ACTIVE,active.toString()).apply();}
    static JSONArray history(Context c){return history(c.getSharedPreferences(PREFS,Context.MODE_PRIVATE));}private static JSONArray history(SharedPreferences p){try{return new JSONArray(p.getString(KEY_HISTORY,"[]"));}catch(Throwable ignored){return new JSONArray();}}
    static void clear(Context c){c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().remove(KEY_HISTORY).apply();}
}
''')

# RoadPackStore diagnostics for the offline center.
p=J/'RoadPackStore.java'; s=read(p)
anchor='''    synchronized String coreStatesStatus() {\n        StringBuilder out = new StringBuilder();\n        for (String uf : new String[]{"RJ","MG","ES"}) {\n            if (out.length() > 0) out.append(" · ");\n            out.append(uf).append(hasStateLocked(uf) ? " ✓" : " …");\n        }\n        return out.toString();\n    }'''
insert=anchor+'''\n\n    synchronized int stateHazardCount(String uf) {\n        LinkedHashMap<String,RoadHazard> unique=new LinkedHashMap<>();\n        for(Pack p:packs) if("state".equals(p.kind)&&uf.equals(p.uf)) for(RoadHazard h:p.hazards) unique.put(h.id,h);\n        return unique.size();\n    }\n\n    synchronized long stateFetchedAt(String uf) {\n        long best=0; for(Pack p:packs) if("state".equals(p.kind)&&uf.equals(p.uf)) best=Math.max(best,p.fetchedAt); return best;\n    }'''
s=rep(s,anchor,insert,'road pack diagnostics'); write(p,s)

# Service integration: sensor impacts, radar confirmation queue, ahead strip and rest reminder.
p=J/'RoadSafetyService.java'; s=read(p)
s=rep(s,'private static final long ALERT_COOLDOWN_MS = 8L * 60L * 1000L;','private static final long ALERT_COOLDOWN_MS = 8L * 60L * 1000L;\n    static final String ACTION_PREFETCH_CORE = "com.estradaplay.comunista.PREFETCH_CORE";','service action')
s=rep(s,'private TripRecorder tripRecorder;','private TripRecorder tripRecorder;\n    private CollectiveRoadStore collectiveStore;\n    private RoadSurfaceMonitor surfaceMonitor;','service stores')
s=rep(s,'private long lastSafetyVoiceAt;','private long lastSafetyVoiceAt;\n    private long continuousDrivingStartedAt,lastMovingForRestAt;\n    private boolean restSuggested;\n    private long lastUpcomingAt; private String upcomingCache="";','service collective fields')
s=rep(s,'tripRecorder = new TripRecorder(this);','tripRecorder = new TripRecorder(this);\n        collectiveStore = new CollectiveRoadStore(this);\n        surfaceMonitor = new RoadSurfaceMonitor(this, this::handleRoadImpact);\n        surfaceMonitor.start();','service init')
s=rep(s,'@Override public int onStartCommand(Intent intent, int flags, int startId) {\n        if (packs != null && mapRoads != null) { startLocation(); primeOfflineRadarCore(); } else initializeStoresAsync();\n        return START_STICKY;\n    }','@Override public int onStartCommand(Intent intent, int flags, int startId) {\n        if (packs != null && mapRoads != null) { startLocation(); primeOfflineRadarCore(); } else initializeStoresAsync();\n        if(intent!=null&&ACTION_PREFETCH_CORE.equals(intent.getAction())) primeOfflineRadarCore();\n        return START_STICKY;\n    }','service start action')
s=rep(s,'previous = new Location(loc);\n        if (tripRecorder != null) tripRecorder.onLocation(loc, speedKmh);','previous = new Location(loc);\n        if (tripRecorder != null) tripRecorder.onLocation(loc, speedKmh);\n        if (surfaceMonitor != null) surfaceMonitor.updateDriveState(loc, speedKmh);\n        updateRestClock(loc, speedKmh);','drive state sensor')
s=rep(s,'if (tripRecorder != null) tripRecorder.onHazard(best.type);','if (tripRecorder != null) tripRecorder.onHazard(best.type);\n            if (collectiveStore != null && "RADAR".equals(best.type)) collectiveStore.addRadarConfirmation(best,loc);','radar confirm queue')
s=rep(s,'String state = lastStateText;\n            if (now - lastNotificationAt > 7000L) updateNotification("Proteção na estrada ativa", state, false);','String ahead=upcomingSummary(loc.getLatitude(),loc.getLongitude(),heading);\n            String state = ahead.isEmpty()?lastStateText:lastStateText+"\\nÀ frente · "+ahead;\n            if (now - lastNotificationAt > 7000L) updateNotification("Proteção na estrada ativa", state, false);','ahead status')
s=rep(s,'i.putExtra("map_pack_count", mapRoads.packCount());','i.putExtra("map_pack_count", mapRoads.packCount());\n        if(collectiveStore!=null){i.putExtra("collective_impact_count",collectiveStore.impactCount());i.putExtra("collective_queue_count",collectiveStore.queuedCount());}\n        i.putExtra("upcoming_text",upcomingCache);','broadcast collective extras')
s=rep(s,'try { if (offlineVoice != null) offlineVoice.release(); } catch (Throwable ignored) {}','try { if (offlineVoice != null) offlineVoice.release(); } catch (Throwable ignored) {}\n        try { if (surfaceMonitor != null) surfaceMonitor.stop(); } catch (Throwable ignored) {}','surface stop')
# Insert helper methods before thermalStatus.
anchor='''    private int thermalStatus() {'''
helpers=r'''    private void handleRoadImpact(RoadSurfaceMonitor.Impact impact) {
        if(impact==null)return;
        if(tripRecorder!=null)tripRecorder.onRoadImpact(impact);
        if(collectiveStore!=null){collectiveStore.recordImpact(impact);io.execute(()->{try{ensureApiSession(false);collectiveStore.flush(api);}catch(Throwable ignored){}});}
        Intent i=baseBroadcast(impact.lat,impact.lon,impact.speedKmh,"Irregularidade detectada pela suspensão/sensor");
        i.putExtra("road_surface_event",true);i.putExtra("road_surface_force",impact.force);sendBroadcast(i);
    }

    private void updateRestClock(Location loc,double speedKmh){
        long now=System.currentTimeMillis();
        if(speedKmh>=10){if(continuousDrivingStartedAt==0)continuousDrivingStartedAt=now;lastMovingForRestAt=now;if(!restSuggested&&now-continuousDrivingStartedAt>=2L*60L*60L*1000L){restSuggested=true;if(tripRecorder!=null)tripRecorder.onRestSuggested();Intent i=baseBroadcast(loc.getLatitude(),loc.getLongitude(),speedKmh,"Pausa sugerida após 2 horas em movimento");i.putExtra("rest_suggested",true);sendBroadcast(i);updateNotification("Pausa sugerida","Você está há cerca de 2 horas em movimento. Pare quando for seguro.",false);}}else if(lastMovingForRestAt>0&&now-lastMovingForRestAt>=15L*60L*1000L){continuousDrivingStartedAt=0;restSuggested=false;}
    }

    private String upcomingSummary(double lat,double lon,float heading){
        long now=System.currentTimeMillis();if(now-lastUpcomingAt<5000L)return upcomingCache;lastUpcomingAt=now;if(!Float.isFinite(heading)){upcomingCache="";return upcomingCache;}
        try{List<RoadHazard> list=packs.nearby(lat,lon,7000);ArrayList<String> rows=new ArrayList<>();ArrayList<Double> ds=new ArrayList<>();double rad=Math.toRadians(heading);for(RoadHazard h:list){double north=(h.lat-lat)*110540.0;double east=(h.lon-lon)*111320.0*Math.max(.25,Math.cos(Math.toRadians(lat)));double forward=east*Math.sin(rad)+north*Math.cos(rad);double lateral=Math.abs(east*Math.cos(rad)-north*Math.sin(rad));if(forward<150||forward>7000||lateral>220)continue;int at=0;while(at<ds.size()&&ds.get(at)<forward)at++;ds.add(at,forward);String d=forward>=1000?String.format(Locale.getDefault(),"%.1f km",forward/1000.0):Math.round(forward)+" m";String label=h.label()+(h.speed>0&&"RADAR".equals(h.type)?" "+h.speed:"")+" · "+d;rows.add(at,label);if(rows.size()>3){rows.remove(3);ds.remove(3);}}StringBuilder out=new StringBuilder();for(int i=0;i<rows.size();i++){if(i>0)out.append(" → ");out.append(rows.get(i));}upcomingCache=out.toString();return upcomingCache;}catch(Throwable ignored){upcomingCache="";return "";}
    }

'''+anchor
s=rep(s,anchor,helpers,'service helper methods'); write(p,s)

# If dashcam is open and an impact is detected, preserve its rolling segment locally.
p=J/'CameraActivity.java'; s=read(p)
s=rep(s,'SafetyAlertOverlay.show(CameraActivity.this,root,i);','SafetyAlertOverlay.show(CameraActivity.this,root,i);\n        if(i.getBooleanExtra("road_surface_event",false)&&autoDashcam()&&DriveSettings.protectImpactVideo(CameraActivity.this)) preserveMoment();','camera impact preserve')
write(p,s)

print('patched collective core v1.5.0')

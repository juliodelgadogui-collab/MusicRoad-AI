package com.estradaplay.comunista;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

/**
 * TRIP_HUD_V301: estado local de uma viagem. Não depende de servidor e pode
 * sobreviver à perda de sinal ou ao fechamento da tela do mapa.
 */
final class DriveSessionStore {
    private static final String PREF="epc_drive_session_v301";
    private static final String K_ACTIVE="active",K_START="start",K_LAST="last",K_DISTANCE="distance_m",K_MOVING="moving_ms",K_LAST_LAT="last_lat",K_LAST_LON="last_lon",K_LAST_SPEED="last_speed",K_STOPS="stops",K_SUMMARY="last_summary";
    private static final long BREAK_AFTER_MS=2L*60L*60L*1000L;
    private final SharedPreferences p;

    DriveSessionStore(Context c){p=c.getApplicationContext().getSharedPreferences(PREF,Context.MODE_PRIVATE);}

    static final class Snapshot {
        final boolean active,breakSuggested;
        final long startedAt,elapsedMs,movingMs;
        final double currentSpeedKmh,distanceKm,averageSpeedKmh,estimatedFuelLiters;
        final int stopCount;
        Snapshot(boolean a,long s,long e,long m,double cs,double d,double av,double fuel,int stops,boolean br){active=a;startedAt=s;elapsedMs=e;movingMs=m;currentSpeedKmh=cs;distanceKm=d;averageSpeedKmh=av;estimatedFuelLiters=fuel;stopCount=stops;breakSuggested=br;}
        String elapsedLabel(){long min=Math.max(0,elapsedMs/60000L);return String.format(Locale.getDefault(),"%dh %02d",min/60,min%60);}
        String distanceLabel(){return distanceKm<10?String.format(Locale.getDefault(),"%.1f km",distanceKm):Math.round(distanceKm)+" km";}
        String averageLabel(){return Math.round(averageSpeedKmh)+" km/h";}
    }

    synchronized void startIfNeeded(long now){if(p.getBoolean(K_ACTIVE,false))return;p.edit().putBoolean(K_ACTIVE,true).putLong(K_START,now).putLong(K_LAST,now).putLong(K_MOVING,0).putLong(K_DISTANCE,Double.doubleToLongBits(0)).remove(K_LAST_LAT).remove(K_LAST_LON).putString(K_STOPS,"[]").apply();}

    synchronized Snapshot update(Location location,double speedKmh){
        long now=System.currentTimeMillis();startIfNeeded(now);
        long lastAt=p.getLong(K_LAST,now);long dt=Math.max(0,Math.min(30000L,now-lastAt));
        double distance=Double.longBitsToDouble(p.getLong(K_DISTANCE,Double.doubleToLongBits(0)));
        long moving=p.getLong(K_MOVING,0);
        double oldLat=readDouble(K_LAST_LAT),oldLon=readDouble(K_LAST_LON);
        if(location!=null){double lat=location.getLatitude(),lon=location.getLongitude();if(Double.isFinite(oldLat)&&Double.isFinite(oldLon)){float[] out=new float[1];Location.distanceBetween(oldLat,oldLon,lat,lon,out);double step=out[0];if(step>=2&&step<=500)distance+=step;}p.edit().putLong(K_LAST_LAT,Double.doubleToLongBits(lat)).putLong(K_LAST_LON,Double.doubleToLongBits(lon)).apply();}
        double safeSpeed=Math.max(0,Math.min(250,speedKmh));if(safeSpeed>=3)moving+=dt;
        p.edit().putLong(K_LAST,now).putLong(K_DISTANCE,Double.doubleToLongBits(distance)).putLong(K_MOVING,moving).putLong(K_LAST_SPEED,Double.doubleToLongBits(safeSpeed)).apply();
        return snapshot();
    }

    synchronized Snapshot snapshot(){
        boolean active=p.getBoolean(K_ACTIVE,false);long now=System.currentTimeMillis(),start=p.getLong(K_START,now),moving=p.getLong(K_MOVING,0);double distanceM=Double.longBitsToDouble(p.getLong(K_DISTANCE,Double.doubleToLongBits(0)));double current=Double.longBitsToDouble(p.getLong(K_LAST_SPEED,Double.doubleToLongBits(0)));double km=distanceM/1000.0;double avg=moving>0?km/(moving/3600000.0):0;double kmPerLiter=Math.max(3.0,p.getFloat("vehicle_km_l",10f));double fuel=km/kmPerLiter;int stops=stopCount();long elapsed=active?Math.max(0,now-start):0;return new Snapshot(active,start,elapsed,moving,current,km,avg,fuel,stops,moving>=BREAK_AFTER_MS);
    }

    synchronized void registerStop(String type,double lat,double lon){try{JSONArray a=new JSONArray(p.getString(K_STOPS,"[]"));JSONArray out=new JSONArray();JSONObject n=new JSONObject();n.put("type",type==null?"pause":type);n.put("at",System.currentTimeMillis());if(Double.isFinite(lat)&&Double.isFinite(lon)){n.put("lat",lat);n.put("lon",lon);}out.put(n);for(int i=0;i<a.length()&&out.length()<30;i++)out.put(a.opt(i));p.edit().putString(K_STOPS,out.toString()).apply();}catch(Throwable ignored){}}

    synchronized Snapshot finish(){Snapshot s=snapshot();try{JSONObject j=new JSONObject();j.put("finished_at",System.currentTimeMillis());j.put("started_at",s.startedAt);j.put("distance_km",s.distanceKm);j.put("elapsed_ms",s.elapsedMs);j.put("moving_ms",s.movingMs);j.put("average_kmh",s.averageSpeedKmh);j.put("estimated_fuel_l",s.estimatedFuelLiters);j.put("stops",new JSONArray(p.getString(K_STOPS,"[]")));p.edit().putString(K_SUMMARY,j.toString()).putBoolean(K_ACTIVE,false).apply();}catch(Throwable ignored){}return s;}
    String lastSummary(){return p.getString(K_SUMMARY,"");}
    void setVehicleEfficiency(float kmPerLiter){p.edit().putFloat("vehicle_km_l",Math.max(3f,Math.min(40f,kmPerLiter))).apply();}
    private int stopCount(){try{return new JSONArray(p.getString(K_STOPS,"[]")).length();}catch(Throwable e){return 0;}}
    private double readDouble(String key){return p.contains(key)?Double.longBitsToDouble(p.getLong(key,0)):Double.NaN;}
}

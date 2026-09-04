package com.estradaplay.patriota;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;

final class MaintenanceStore {
    private static final String PREF="epp_maintenance_v190", KEY="rows";
    static final class Entry {
        final String id, vehicleId, type, note; final long at,nextAt; final double odometer,nextKm;
        Entry(String id,String vehicleId,String type,String note,long at,double odometer,double nextKm,long nextAt){this.id=id;this.vehicleId=vehicleId;this.type=type;this.note=note;this.at=at;this.odometer=odometer;this.nextKm=nextKm;this.nextAt=nextAt;}
        boolean due(double currentKm,long now){return (nextKm>0&&currentKm>=nextKm)||(nextAt>0&&now>=nextAt);}
        boolean soon(double currentKm,long now){return due(currentKm,now)||(nextKm>0&&currentKm>=nextKm-800)||(nextAt>0&&nextAt-now<=30L*24L*60L*60L*1000L);}
    }
    private static SharedPreferences p(Context c){return c.getSharedPreferences(PREF,Context.MODE_PRIVATE);}
    static synchronized void add(Context c,String vehicleId,String type,double odometer,double intervalKm,int months,String note){
        try{JSONArray old=raw(c),out=new JSONArray();JSONObject o=new JSONObject();long now=System.currentTimeMillis();
            o.put("id","m"+now);o.put("vehicle_id",vehicleId==null?"":vehicleId);o.put("type",type==null?"Revisão":type);o.put("note",note==null?"":note);o.put("at",now);o.put("odometer",Math.max(0,odometer));o.put("next_km",intervalKm>0&&odometer>0?odometer+intervalKm:0);o.put("next_at",months>0?now+months*30L*24L*60L*60L*1000L:0);out.put(o);for(int i=0;i<old.length()&&out.length()<80;i++)out.put(old.opt(i));p(c).edit().putString(KEY,out.toString()).apply();
        }catch(Throwable ignored){}
    }
    static synchronized ArrayList<Entry> list(Context c,String vehicleId){ArrayList<Entry> out=new ArrayList<>();JSONArray a=raw(c);for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;if(vehicleId!=null&&!vehicleId.equals(o.optString("vehicle_id")))continue;out.add(new Entry(o.optString("id"),o.optString("vehicle_id"),o.optString("type"),o.optString("note"),o.optLong("at"),o.optDouble("odometer"),o.optDouble("next_km"),o.optLong("next_at")));}return out;}
    static double currentOdometer(Context c,String vehicleId){double best=0;JSONArray f=FuelingStore.rows(c);for(int i=0;i<f.length();i++){JSONObject o=f.optJSONObject(i);if(o!=null&&vehicleId.equals(o.optString("vehicle_id")))best=Math.max(best,o.optDouble("odometer",0));}for(Entry e:list(c,vehicleId))best=Math.max(best,e.odometer);return best;}
    static int dueCount(Context c,String vehicleId){double km=currentOdometer(c,vehicleId);int n=0;long now=System.currentTimeMillis();for(Entry e:list(c,vehicleId))if(e.soon(km,now))n++;return n;}
    private static JSONArray raw(Context c){try{return new JSONArray(p(c).getString(KEY,"[]"));}catch(Throwable e){return new JSONArray();}}
}

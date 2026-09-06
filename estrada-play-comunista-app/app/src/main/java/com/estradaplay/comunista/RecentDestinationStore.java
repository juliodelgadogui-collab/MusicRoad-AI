package com.estradaplay.comunista;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;

/** Small local MRU list used for one-tap destination recovery. */
final class RecentDestinationStore {
    private static final String PREF="epc_recent_destinations_v310",KEY="rows";
    private static final int MAX=8;
    private RecentDestinationStore(){}

    static void add(Context context, DestinationStore.Destination d){
        if(context==null||d==null||!Double.isFinite(d.lat)||!Double.isFinite(d.lon))return;
        try{
            JSONArray old=raw(context),out=new JSONArray();
            out.put(toJson(d,System.currentTimeMillis()));
            for(int i=0;i<old.length()&&out.length()<MAX;i++){
                JSONObject o=old.optJSONObject(i);if(o==null)continue;
                double lat=o.optDouble("lat",Double.NaN),lon=o.optDouble("lon",Double.NaN);
                if(!Double.isFinite(lat)||!Double.isFinite(lon))continue;
                if(RouteEngine.distanceM(lat,lon,d.lat,d.lon)<80)continue;
                out.put(o);
            }
            context.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().putString(KEY,out.toString()).apply();
        }catch(Throwable ignored){}
    }

    static ArrayList<DestinationStore.Destination> list(Context context){
        ArrayList<DestinationStore.Destination> out=new ArrayList<>();
        JSONArray a=raw(context);
        for(int i=0;i<a.length();i++){
            JSONObject o=a.optJSONObject(i);if(o==null)continue;
            double lat=o.optDouble("lat",Double.NaN),lon=o.optDouble("lon",Double.NaN);
            if(!Double.isFinite(lat)||!Double.isFinite(lon))continue;
            out.add(new DestinationStore.Destination(o.optString("label","Destino recente"),lat,lon));
        }
        return out;
    }

    private static JSONObject toJson(DestinationStore.Destination d,long at)throws Exception{
        JSONObject o=new JSONObject();o.put("label",d.label);o.put("lat",d.lat);o.put("lon",d.lon);o.put("at",at);return o;
    }
    private static JSONArray raw(Context c){try{return new JSONArray(c.getSharedPreferences(PREF,Context.MODE_PRIVATE).getString(KEY,"[]"));}catch(Throwable e){return new JSONArray();}}
}

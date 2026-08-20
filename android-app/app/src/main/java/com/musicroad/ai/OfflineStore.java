package com.musicroad.ai;

import android.content.Context;
import android.content.SharedPreferences;

public final class OfflineStore {
    private final SharedPreferences p;
    public OfflineStore(Context c){p=c.getSharedPreferences("musicroad_offline_v2",Context.MODE_PRIVATE);}
    public void saveAccount(String v){p.edit().putString("account",v).apply();}
    public String account(){return p.getString("account","{}");}
    public void saveRoute(String v){p.edit().putString("route",v).apply();}
    public String route(){return p.getString("route","");}
    public void saveHazards(String v){p.edit().putString("hazards",v).apply();}
    public String hazards(){return p.getString("hazards","");}
    public void setSetupDone(boolean v){p.edit().putBoolean("setup_done",v).apply();}
    public boolean setupDone(){return p.getBoolean("setup_done",false);}
    public void saveLastDestination(String v){p.edit().putString("last_destination",v).apply();}
    public String lastDestination(){return p.getString("last_destination","");}
    public void clearSession(){p.edit().remove("account").remove("route").remove("hazards").apply();}
}

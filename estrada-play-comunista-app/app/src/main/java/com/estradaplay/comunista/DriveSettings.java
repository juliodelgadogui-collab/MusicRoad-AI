package com.estradaplay.comunista;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.Calendar;
final class DriveSettings {
    private static final String P="epc_drive_settings_v140";
    static SharedPreferences p(Context c){return c.getSharedPreferences(P,Context.MODE_PRIVATE);}
    static boolean autoNight(Context c){return p(c).getBoolean("auto_night",true);}
    static boolean hudMirror(Context c){return p(c).getBoolean("hud_mirror",true);}
    static boolean collectiveEnabled(Context c){return p(c).getBoolean("collective_enabled",true);}
    static boolean protectImpactVideo(Context c){return p(c).getBoolean("protect_impact_video",true);}
    static boolean offlineTestMode(Context c){return p(c).getBoolean("offline_test_mode",false);}
    static boolean onlyRoadMode(Context c){return p(c).getBoolean("only_road_mode",false);}
    static boolean manualRain(Context c){return p(c).getBoolean("rain_manual",false);}
    static boolean autoRain(Context c){return p(c).getBoolean("rain_auto",true);}
    static boolean rainAutoDetected(Context c){long at=p(c).getLong("rain_auto_at",0);return p(c).getBoolean("rain_auto_detected",false)&&System.currentTimeMillis()-at<45L*60L*1000L;}
    static boolean rainNow(Context c){return manualRain(c)||(autoRain(c)&&rainAutoDetected(c));}
    static void setRainAutoDetected(Context c,boolean value){p(c).edit().putBoolean("rain_auto_detected",value).putLong("rain_auto_at",System.currentTimeMillis()).apply();}
    static boolean nightNow(Context c){if(!autoNight(c))return p(c).getBoolean("night_force",false);int h=Calendar.getInstance().get(Calendar.HOUR_OF_DAY);return h>=19||h<6;}
    static void toggle(Context c,String key,boolean value){p(c).edit().putBoolean(key,value).apply();}
    static float consumptionKml(Context c){return VehicleProfileStore.active(c).kmL;}
    static float fuelPrice(Context c){return VehicleProfileStore.active(c).fuelPrice;}
    static float tankLiters(Context c){return VehicleProfileStore.active(c).tankL;}
    static float fuelPercent(Context c){return VehicleProfileStore.active(c).fuelPercent;}
    static void setVehicleCost(Context c,float kmL,float price){VehicleProfileStore.Profile a=VehicleProfileStore.active(c);VehicleProfileStore.save(c,new VehicleProfileStore.Profile(a.id,a.name,a.type,Math.max(1f,kmL),Math.max(0f,price),a.tankL,a.fuelPercent));}
    static int impactSensitivity(Context c){return Math.max(0,Math.min(2,p(c).getInt("impact_sensitivity",1)));}
    static void setImpactSensitivity(Context c,int value){p(c).edit().putInt("impact_sensitivity",Math.max(0,Math.min(2,value))).apply();}
}

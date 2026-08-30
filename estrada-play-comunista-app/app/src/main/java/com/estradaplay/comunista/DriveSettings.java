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
    static boolean nightNow(Context c){if(!autoNight(c))return p(c).getBoolean("night_force",false);int h=Calendar.getInstance().get(Calendar.HOUR_OF_DAY);return h>=19||h<6;}
    static void toggle(Context c,String key,boolean value){p(c).edit().putBoolean(key,value).apply();}
    static float consumptionKml(Context c){return Math.max(1f,p(c).getFloat("consumption_kml",10f));}
    static float fuelPrice(Context c){return Math.max(0f,p(c).getFloat("fuel_price",0f));}
    static void setVehicleCost(Context c,float kmL,float price){p(c).edit().putFloat("consumption_kml",Math.max(1f,kmL)).putFloat("fuel_price",Math.max(0f,price)).apply();}
    static int impactSensitivity(Context c){return Math.max(0,Math.min(2,p(c).getInt("impact_sensitivity",1)));}
    static void setImpactSensitivity(Context c,int value){p(c).edit().putInt("impact_sensitivity",Math.max(0,Math.min(2,value))).apply();}
}

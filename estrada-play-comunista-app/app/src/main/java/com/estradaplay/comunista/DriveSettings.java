package com.estradaplay.comunista;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.Calendar;
final class DriveSettings {
    private static final String P="epc_drive_settings_v140";
    static SharedPreferences p(Context c){return c.getSharedPreferences(P,Context.MODE_PRIVATE);}
    static boolean autoNight(Context c){return p(c).getBoolean("auto_night",true);}
    static boolean hudMirror(Context c){return p(c).getBoolean("hud_mirror",true);}
    static boolean nightNow(Context c){if(!autoNight(c))return p(c).getBoolean("night_force",false);int h=Calendar.getInstance().get(Calendar.HOUR_OF_DAY);return h>=19||h<6;}
    static void toggle(Context c,String key,boolean value){p(c).edit().putBoolean(key,value).apply();}
}

package com.estradaplay.patriota;

import android.content.Context;
import android.content.SharedPreferences;
import java.net.URI;

/** Persistent server override so the APK can move to a new hosting without recompilation. */
final class ServerEndpointStore {
    private static final String PREFS="estradaplay_server_endpoint_v1";
    private static final String KEY="base_url";
    private ServerEndpointStore() {}

    static String base(Context c,String fallback){
        String custom=c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY,"");
        String v=custom==null||custom.trim().isEmpty()?fallback:custom;
        return normalize(v);
    }
    static String custom(Context c){
        String v=c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY,"");
        return v==null?"":v.trim();
    }
    static void set(Context c,String value){
        c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(KEY,normalize(value)).apply();
    }
    static void clear(Context c){c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().remove(KEY).apply();}
    static String normalize(String raw){
        String v=raw==null?"":raw.trim();
        if(v.isEmpty()) return "";
        if(!v.startsWith("https://")&&!v.startsWith("http://"))v="https://"+v;
        while(v.endsWith("/"))v=v.substring(0,v.length()-1);
        return v+"/";
    }
    static boolean valid(String raw){
        try{String v=normalize(raw);URI u=new URI(v);return ("https".equalsIgnoreCase(u.getScheme())||"http".equalsIgnoreCase(u.getScheme()))&&u.getHost()!=null&&!u.getHost().isEmpty();}
        catch(Throwable e){return false;}
    }
}

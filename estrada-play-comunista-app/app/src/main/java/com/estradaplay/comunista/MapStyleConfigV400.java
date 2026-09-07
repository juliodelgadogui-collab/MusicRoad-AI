package com.estradaplay.comunista;

import android.content.Context;

import org.json.JSONObject;

import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Cached server-selected map style. Own tiles can be enabled without rebuilding the APK. */
final class MapStyleConfigV400 {
    private static final String PREFS="epc_map_style_v400";
    private static final String KEY="style_url";
    private static final String FALLBACK="https://tiles.openfreemap.org/styles/dark";
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private MapStyleConfigV400(){}

    static String styleUri(Context c){
        String v=c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY,"");
        return valid(v)?v:FALLBACK;
    }

    static void refreshAsync(Context c){
        Context app=c.getApplicationContext();
        IO.execute(()->{
            try{
                ApiClient.Response r=new ApiClient(app).getFast("api/map_config.php");
                JSONObject j=r.json();
                String v=j.optString("style_url","").trim();
                if(r.ok()&&j.optBoolean("ok",false)&&valid(v)) app.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(KEY,v).apply();
            }catch(Throwable ignored){}
        });
    }

    private static boolean valid(String raw){
        if(raw==null||raw.trim().isEmpty())return false;
        try{URI u=new URI(raw.trim());return "https".equalsIgnoreCase(u.getScheme())&&u.getHost()!=null&&!u.getHost().isEmpty()&&u.getUserInfo()==null;}
        catch(Throwable e){return false;}
    }
}

package com.estradaplay.comunista;

import android.content.Context;
import android.content.SharedPreferences;
import java.net.URI;

/** Persistent server override so the APK can move to a new hosting without recompilation. */
final class ServerEndpointStore {
    private static final String PREFS="estradaplay_server_endpoint_v1";
    private static final String KEY="base_url";
    private ServerEndpointStore() {}

    // SERVER_ENDPOINT_HTTPS_V260: the manifest blocks cleartext traffic, so an old/insecure
    // override must never make the whole app point at an endpoint Android itself will reject.
    static String base(Context c,String fallback){
        String custom=c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY,"");
        String selected=custom!=null&&valid(custom)?custom:fallback;
        return normalize(selected);
    }
    static String custom(Context c){
        String v=c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY,"");
        return v==null?"":v.trim();
    }
    static void set(Context c,String value){
        if(!valid(value))throw new IllegalArgumentException("Servidor precisa usar HTTPS válido");
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
        try{
            String v=normalize(raw);URI u=new URI(v);
            return "https".equalsIgnoreCase(u.getScheme())
                    && u.getHost()!=null&&!u.getHost().isEmpty()
                    && u.getUserInfo()==null
                    && u.getQuery()==null
                    && u.getFragment()==null;
        }catch(Throwable e){return false;}
    }
}

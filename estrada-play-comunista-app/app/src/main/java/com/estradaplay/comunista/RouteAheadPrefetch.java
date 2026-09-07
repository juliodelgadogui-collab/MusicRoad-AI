package com.estradaplay.comunista;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ROUTE_PREFETCH_V330: quietly prepares only the first route reserves after calculation.
 * It is intentionally bounded so a long route cannot create a large burst of downloads.
 */
final class RouteAheadPrefetch {
    private static final String PREFS="epc_route_prefetch_v330";
    private static final long SAME_ROUTE_GAP_MS=4L*60L*60L*1000L;
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private static final AtomicBoolean RUNNING=new AtomicBoolean(false);

    private RouteAheadPrefetch(){}

    static void schedule(Context context,RouteEngine.Route route){
        if(context==null||route==null||DriveSettings.offlineTestMode(context))return;
        Context app=context.getApplicationContext();
        int hash=route.geoJson==null?0:route.geoJson.hashCode();
        SharedPreferences p=app.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        long now=System.currentTimeMillis();
        if(hash!=0&&p.getInt("route_hash",0)==hash&&now-p.getLong("at",0L)<SAME_ROUTE_GAP_MS)return;
        if(!RUNNING.compareAndSet(false,true))return;
        p.edit().putInt("route_hash",hash).putLong("at",now).apply();
        IO.execute(()->{
            try{
                // Three samples can cover a very long initial corridor because each reserve itself
                // prepares a broad distance ahead. This keeps server/mobile cost predictable.
                TripOfflinePreparer.prepareAhead(app,route,3);
            }catch(Throwable ignored){}finally{RUNNING.set(false);}
        });
    }
}

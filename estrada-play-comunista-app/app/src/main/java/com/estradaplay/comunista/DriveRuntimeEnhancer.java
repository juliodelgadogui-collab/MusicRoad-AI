package com.estradaplay.comunista;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/**
 * DRIVE_QUALITY_V330: observes the existing road-state stream without creating another GPS listener.
 * It feeds the stable ETA model and emits a short UI-only position estimate during brief GPS gaps.
 */
final class DriveRuntimeEnhancer {
    private final Application app;
    private final Handler main=new Handler(Looper.getMainLooper());
    private long lastRealAt;
    private double lastLat=Double.NaN,lastLon=Double.NaN,lastSpeed,lastHeading=Double.NaN;

    static DriveRuntimeEnhancer install(Application app){
        DriveRuntimeEnhancer x=new DriveRuntimeEnhancer(app);
        IntentFilter f=new IntentFilter(RoadSafetyService.ACTION_STATE);
        try{if(Build.VERSION.SDK_INT>=33)app.registerReceiver(x.receiver,f,Context.RECEIVER_NOT_EXPORTED);else app.registerReceiver(x.receiver,f);}catch(Throwable ignored){}
        x.main.postDelayed(x.gapWatch,4000L);
        return x;
    }

    private DriveRuntimeEnhancer(Application app){this.app=app;}

    private final BroadcastReceiver receiver=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            if(intent==null||intent.getBooleanExtra("estimated_position",false))return;
            double speed=intent.getDoubleExtra("speed_kmh",0.0);
            RouteTravelPace.observe(speed,System.currentTimeMillis());
            double lat=intent.getDoubleExtra("lat",Double.NaN),lon=intent.getDoubleExtra("lon",Double.NaN);
            double heading=intent.getFloatExtra("heading",-1f);
            if(!Double.isFinite(lat)||!Double.isFinite(lon))return;
            lastLat=lat;lastLon=lon;lastSpeed=Math.max(0,speed);
            if(Double.isFinite(heading)&&heading>=0)lastHeading=heading;
            lastRealAt=System.currentTimeMillis();
        }
    };

    private final Runnable gapWatch=new Runnable(){
        @Override public void run(){
            try{
                long now=System.currentTimeMillis(),age=lastRealAt<=0?Long.MAX_VALUE:now-lastRealAt;
                if(age>=7000L&&age<=22000L&&lastSpeed>=10&&Double.isFinite(lastHeading)){
                    RoadTunnelEstimator.Point p=RoadTunnelEstimator.project(lastLat,lastLon,lastHeading,lastSpeed,age);
                    if(p!=null){
                        Intent i=new Intent(RoadSafetyService.ACTION_STATE).setPackage(app.getPackageName());
                        i.putExtra("lat",p.lat);i.putExtra("lon",p.lon);i.putExtra("speed_kmh",lastSpeed);
                        i.putExtra("heading",(float)lastHeading);i.putExtra("estimated_position",true);
                        i.putExtra("gps_fix_age_ms",age);i.putExtra("protection_available",true);
                        i.putExtra("status","GPS temporariamente sem sinal · posição estimada");
                        app.sendBroadcast(i);
                    }
                }
            }catch(Throwable ignored){}finally{main.postDelayed(this,4000L);}
        }
    };
}

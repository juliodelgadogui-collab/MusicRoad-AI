package com.estradaplay.comunista;

import android.content.Context;
import android.os.PowerManager;

/** Battery-aware cadence policy for road services. */
final class DrivePowerPolicy {
    static final class Policy {
        final boolean screenInteractive,powerSave;final long gpsMinMs,mapRefreshMs,syncMs;
        Policy(boolean i,boolean p,long g,long m,long s){screenInteractive=i;powerSave=p;gpsMinMs=g;mapRefreshMs=m;syncMs=s;}
    }
    static Policy current(Context context){
        boolean interactive=true,save=false;try{PowerManager pm=(PowerManager)context.getSystemService(Context.POWER_SERVICE);if(pm!=null){interactive=pm.isInteractive();save=pm.isPowerSaveMode();}}catch(Throwable ignored){}
        if(!interactive)return new Policy(false,save,3000L,30000L,120000L);
        if(save)return new Policy(true,true,2000L,15000L,60000L);
        return new Policy(true,false,1000L,3000L,25000L);
    }
    private DrivePowerPolicy(){}
}

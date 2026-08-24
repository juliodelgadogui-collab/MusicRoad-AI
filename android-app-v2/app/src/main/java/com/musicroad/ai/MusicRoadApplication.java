package com.musicroad.ai;

import android.app.Activity;
import android.app.Application;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;

/** Installs the dedicated native DriveOS shell only while MainActivity is landscape. */
public final class MusicRoadApplication extends Application implements Application.ActivityLifecycleCallbacks {
    private final Handler main=new Handler(Looper.getMainLooper());
    private WeakReference<MainActivity> current=new WeakReference<>(null);

    @Override public void onCreate(){
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
        DriveOsShell.init(this);
    }

    @Override public void onActivityResumed(Activity activity){
        if(activity instanceof MainActivity){
            MainActivity a=(MainActivity)activity;current=new WeakReference<>(a);schedule(a,100);
        }
    }

    @Override public void onConfigurationChanged(Configuration newConfig){
        super.onConfigurationChanged(newConfig);
        MainActivity a=current.get();if(a!=null)schedule(a,140);
    }

    private void schedule(MainActivity activity,long delay){main.postDelayed(()->DriveOsShell.install(activity),delay);}

    @Override public void onActivityCreated(Activity a,Bundle b){}
    @Override public void onActivityStarted(Activity a){}
    @Override public void onActivityPaused(Activity a){}
    @Override public void onActivityStopped(Activity a){}
    @Override public void onActivitySaveInstanceState(Activity a,Bundle b){}
    @Override public void onActivityDestroyed(Activity a){if(a==current.get())current.clear();}
}

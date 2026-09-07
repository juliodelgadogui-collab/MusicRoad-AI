package com.estradaplay.comunista;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;

/** Final visual safeguards for the reference cockpit: dark map tint and compact route cancellation. */
final class RoadMapReferencePolishV350 implements Application.ActivityLifecycleCallbacks {
    private final Application app;private final Handler main=new Handler(Looper.getMainLooper());private WeakReference<RoadMapActivity> resumed=new WeakReference<>(null);
    static void install(Application app){if(app==null)return;RoadMapReferencePolishV350 x=new RoadMapReferencePolishV350(app);app.registerActivityLifecycleCallbacks(x);IntentFilter f=new IntentFilter(RoadSafetyService.ACTION_STATE);try{if(Build.VERSION.SDK_INT>=33)InternalBroadcasts.register(app, x.rx, f);else InternalBroadcasts.register(app, x.rx, f);}catch(Throwable ignored){}}
    private RoadMapReferencePolishV350(Application app){this.app=app;}
    private final BroadcastReceiver rx=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){RoadMapActivity a=resumed.get();if(a!=null&&!a.isFinishing())main.postDelayed(()->apply(a),70L);}};
    private void apply(RoadMapActivity a){FrameLayout root=field(a,"root",FrameLayout.class);RoadMapView map=field(a,"roadMap",RoadMapView.class);if(root==null||map==null)return;try{View tint=field(map,"nightTint",View.class);if(tint!=null)tint.setBackgroundColor(Color.argb(126,3,0,7));}catch(Throwable ignored){}
        Button cancel=findButton(root,"CANCELAR ROTA");boolean active=DestinationStore.read(a)!=null;if(cancel==null)return;cancel.setVisibility(active?View.VISIBLE:View.GONE);if(!active)return;cancel.setText("×  ROTA");cancel.setTextSize(9f);cancel.bringToFront();if(Build.VERSION.SDK_INT>=21)cancel.setElevation(dp(120));int w=root.getWidth(),h=root.getHeight();FrameLayout.LayoutParams p=cancel.getLayoutParams() instanceof FrameLayout.LayoutParams?(FrameLayout.LayoutParams)cancel.getLayoutParams():new FrameLayout.LayoutParams(dp(92),dp(38));p.width=dp(92);p.height=dp(38);if(w>h||"horizontal".equals(BuildConfig.FIXED_LAYOUT)){p.gravity=Gravity.TOP|Gravity.CENTER_HORIZONTAL;p.setMargins(0,dp(106),0,0);}else{p.gravity=Gravity.TOP|Gravity.RIGHT;p.setMargins(0,dp(106),dp(14),0);}cancel.setLayoutParams(p);}
    private Button findButton(View v,String text){if(v instanceof Button&&String.valueOf(((Button)v).getText()).toUpperCase().contains(text))return(Button)v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){Button b=findButton(g.getChildAt(i),text);if(b!=null)return b;}}return null;}
    @SuppressWarnings("unchecked")private<T>T field(Object o,String n,Class<T> type){try{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);Object v=f.get(o);return type.isInstance(v)?(T)v:null;}catch(Throwable ignored){return null;}}
    private int dp(float v){return Math.round(v*app.getResources().getDisplayMetrics().density);}
    @Override public void onActivityResumed(Activity activity){if(activity instanceof RoadMapActivity){RoadMapActivity a=(RoadMapActivity)activity;resumed=new WeakReference<>(a);main.postDelayed(()->apply(a),180L);}}
    @Override public void onActivityPaused(Activity activity){if(resumed.get()==activity)resumed=new WeakReference<>(null);}
    @Override public void onActivityCreated(Activity a,Bundle b){}@Override public void onActivityStarted(Activity a){}@Override public void onActivityStopped(Activity a){}@Override public void onActivitySaveInstanceState(Activity a,Bundle b){}@Override public void onActivityDestroyed(Activity a){}
}

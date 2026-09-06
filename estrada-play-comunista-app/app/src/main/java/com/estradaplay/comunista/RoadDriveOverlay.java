package com.estradaplay.comunista;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;

/** Persistent compact trip HUD fed by the same deterministic road broadcast as the map. */
final class RoadDriveOverlay {
    private static final String TAG="epc-drive-trip-hud-v301";
    private static long lastRenderAt;
    private RoadDriveOverlay(){}

    static void update(Context c,FrameLayout host,Intent i){
        if(c==null||host==null||i==null||c instanceof CameraActivity)return;
        double lat=i.getDoubleExtra("lat",Double.NaN),lon=i.getDoubleExtra("lon",Double.NaN),speed=i.getDoubleExtra("speed_kmh",0);Location l=null;if(Double.isFinite(lat)&&Double.isFinite(lon)){l=new Location("estradaplay");l.setLatitude(lat);l.setLongitude(lon);}
        DriveSessionStore session=new DriveSessionStore(c);DriveSessionStore.Snapshot s=session.update(l,speed);
        long now=System.currentTimeMillis();if(now-lastRenderAt<3000L)return;lastRenderAt=now;
        View old=host.findViewWithTag(TAG);if(old!=null)host.removeView(old);
        LinearLayout card=new LinearLayout(c);card.setTag(TAG);card.setOrientation(LinearLayout.HORIZONTAL);card.setGravity(Gravity.CENTER_VERTICAL);card.setPadding(dp(c,10),dp(c,6),dp(c,10),dp(c,6));card.setBackground(round(c,Color.argb(225,10,6,8),14,Color.rgb(79,39,43)));
        addMetric(c,card,s.elapsedLabel(),"VIAGEM");addMetric(c,card,s.distanceLabel(),"DISTÂNCIA");addMetric(c,card,s.averageLabel(),"MÉDIA");
        if(s.breakSuggested){TextView rest=text(c,"PAUSA",8,Color.rgb(235,190,74),true);rest.setGravity(Gravity.CENTER);rest.setBackground(round(c,Color.rgb(61,38,14),11,Color.rgb(150,103,35)));LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(dp(c,52),dp(c,32));rp.setMargins(dp(c,5),0,0,0);card.addView(rest,rp);}
        int sw=c.getResources().getDisplayMetrics().widthPixels;FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(Math.min(dp(c,330),Math.max(dp(c,230),sw/2)),-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);lp.setMargins(0,0,0,dp(c,12));host.addView(card,lp);if(android.os.Build.VERSION.SDK_INT>=21)card.setElevation(dp(c,40));
    }
    private static void addMetric(Context c,LinearLayout p,String value,String title){LinearLayout x=new LinearLayout(c);x.setOrientation(LinearLayout.VERTICAL);x.setGravity(Gravity.CENTER);TextView v=text(c,value,10,Color.rgb(246,238,224),true);v.setGravity(Gravity.CENTER);TextView t=text(c,title,6.5f,Color.rgb(167,145,142),true);t.setGravity(Gravity.CENTER);x.addView(v);x.addView(t);p.addView(x,new LinearLayout.LayoutParams(0,-2,1));}
    private static TextView text(Context c,String v,float s,int color,boolean bold){TextView t=new TextView(c);t.setText(v);t.setTextSize(s);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private static GradientDrawable round(Context c,int color,int radius,int stroke){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(c,radius));if(stroke!=0)g.setStroke(dp(c,1),stroke);return g;}private static int dp(Context c,float v){return Math.round(v*c.getResources().getDisplayMetrics().density);}
}

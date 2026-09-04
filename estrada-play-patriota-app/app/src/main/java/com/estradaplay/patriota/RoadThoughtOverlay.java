package com.estradaplay.patriota;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

final class RoadThoughtOverlay {
    private static final String TAG="epp-road-thought-v142";
    private RoadThoughtOverlay(){}

    static void show(Activity activity, FrameLayout host, Intent intent){
        if(activity==null||host==null||intent==null)return;
        String hazard=safe(intent.getStringExtra("hazard_label"));
        View old=host.findViewWithTag(TAG);
        if(!hazard.isEmpty()) { if(old!=null)host.removeView(old); return; }
        String body=safe(intent.getStringExtra("thought_text"));
        String author=safe(intent.getStringExtra("thought_author"));
        if(body.isEmpty()||author.isEmpty())return;
        if(old!=null)host.removeView(old);

        LinearLayout card=new LinearLayout(activity);card.setTag(TAG);card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(activity,15),dp(activity,11),dp(activity,15),dp(activity,11));
        card.setBackground(panel(activity,Color.argb(246,22,9,13),Color.rgb(130,78,38)));
        TextView over=t(activity,"PENSAMENTO DA ESTRADA  ·  PARÁFRASE",8,Color.rgb(226,185,76),true);over.setLetterSpacing(.10f);card.addView(over);
        TextView a=t(activity,"INSPIRADO EM "+author.toUpperCase(),10,Color.rgb(239,205,148),true);card.addView(a);
        TextView b=t(activity,body,14,Color.rgb(246,238,224),false);b.setMaxLines(3);card.addView(b);
        card.setClickable(false);card.setFocusable(false);
        if(Build.VERSION.SDK_INT>=21)card.setElevation(dp(activity,80));
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
        lp.setMargins(dp(activity,18),0,dp(activity,18),dp(activity,74));host.addView(card,lp);card.bringToFront();
        card.setAlpha(0f);card.setTranslationY(dp(activity,12));card.animate().alpha(1f).translationY(0).setDuration(180).start();
        host.postDelayed(()->{View current=host.findViewWithTag(TAG);if(current==card)card.animate().alpha(0f).translationY(dp(activity,10)).setDuration(180).withEndAction(()->{try{host.removeView(card);}catch(Throwable ignored){}}).start();},10500L);
    }
    private static TextView t(Activity a,String v,float s,int c,boolean bold){TextView t=new TextView(a);t.setText(v);t.setTextSize(s);t.setTextColor(c);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private static GradientDrawable panel(Activity a,int color,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(a,4));if(stroke!=0)d.setStroke(1,stroke);return d;}
    private static int dp(Activity a,float v){return Math.round(v*a.getResources().getDisplayMetrics().density);}
    private static String safe(String s){return s==null?"":s.trim();}
}

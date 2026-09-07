package com.estradaplay.comunista;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;
import java.util.WeakHashMap;

/** Compact automotive safety card plus deterministic trip/speed/rest alerts. */
final class SafetyAlertOverlay {
    private static final String TAG="epc-v302-alert";
    private static String lastId="";
    private static long lastAt;
    private static final WeakHashMap<LinearLayout,SavedPosition> POSITIONS=new WeakHashMap<>();

    private SafetyAlertOverlay(){}

    static void show(Context c,FrameLayout host,Intent i){
        if(c==null||host==null||i==null)return;
        RoadDriveOverlay.update(c,host,i);
        // Runs after RoadMapActivity has applied the current broadcast/context text.
        host.post(() -> positionPersistentAlert(c,host,i));

        String type=norm(i.getStringExtra("hazard_type"));
        String hazard=safe(i.getStringExtra("hazard_label"));
        double d=i.getDoubleExtra("distance_m",0),speed=i.getDoubleExtra("speed_kmh",0),fuel=i.getDoubleExtra("fuel_range_km",-1);
        int limit=i.getIntExtra("road_limit_kmh",i.getIntExtra("limit_kmh",0));
        DriveSessionStore.Snapshot trip=new DriveSessionStore(c).snapshot();
        RoadAlertEngine.Alert rule=RoadAlertEngine.evaluate(speed,limit,type,hazard,d,fuel,trip.movingMs);
        if(!supported(type)){if(rule.present())showRule(c,host,rule);return;}

        String id=safe(i.getStringExtra("hazard_id"));if(id.isEmpty())id=type+":"+Math.round(d);
        long now=System.currentTimeMillis();if(id.equals(lastId)&&now-lastAt<9000)return;lastId=id;lastAt=now;
        View old=host.findViewWithTag(TAG);if(old!=null)host.removeView(old);

        int accent=color(type);
        LinearLayout card=new LinearLayout(c);card.setTag(TAG);card.setOrientation(LinearLayout.HORIZONTAL);card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(c,14),dp(c,10),dp(c,14),dp(c,10));card.setBackground(round(Color.argb(247,20,8,11),17,accent,2));card.setClickable(true);
        if(android.os.Build.VERSION.SDK_INT>=21)card.setElevation(dp(c,80));
        TextView icon=text(c,icon(type),24,accent,true);icon.setGravity(Gravity.CENTER);card.addView(icon,new LinearLayout.LayoutParams(dp(c,46),dp(c,50)));
        LinearLayout meta=new LinearLayout(c);meta.setOrientation(LinearLayout.VERTICAL);meta.setGravity(Gravity.CENTER_VERTICAL);
        TextView over=text(c,title(type),9,accent,true);over.setLetterSpacing(.11f);meta.addView(over);
        String road=safe(i.getStringExtra("road"));int radarLimit=i.getIntExtra("radar_limit_kmh",i.getIntExtra("limit_kmh",0));
        String main=d>0?distance(d):"À FRENTE";
        if(("RADAR".equals(type)||"SEMAFORO_RADAR".equals(type))&&radarLimit>0)main+="  ·  "+radarLimit+" km/h";
        TextView big=text(c,main,18,Color.rgb(249,239,221),true);meta.addView(big);
        if(!road.isEmpty()){TextView r=text(c,road,10,Color.rgb(181,154,149),false);r.setMaxLines(1);meta.addView(r);}
        card.addView(meta,new LinearLayout.LayoutParams(0,-2,1));
        int delta=i.getIntExtra("overspeed_delta_kmh",0);
        if(delta>=2&&("RADAR".equals(type)||"SEMAFORO_RADAR".equals(type))){TextView red=text(c,"REDUZA\n"+delta+" acima",9,Color.WHITE,true);red.setGravity(Gravity.CENTER);red.setBackground(round(accent,12,0,0));red.setPadding(dp(c,8),dp(c,5),dp(c,8),dp(c,5));card.addView(red,new LinearLayout.LayoutParams(dp(c,76),dp(c,48)));}
        attach(host,card,i.getIntExtra("alert_level",1)>=2?5600L:4600L);
    }

    private static void showRule(Context c,FrameLayout host,RoadAlertEngine.Alert a){
        String id="rule:"+a.code;long now=System.currentTimeMillis();if(id.equals(lastId)&&now-lastAt<15000)return;lastId=id;lastAt=now;
        View old=host.findViewWithTag(TAG);if(old!=null)host.removeView(old);
        int accent=a.priority>=RoadAlertEngine.PRIORITY_URGENT?Color.rgb(239,41,58):(a.priority>=RoadAlertEngine.PRIORITY_CAUTION?Color.rgb(230,155,48):Color.rgb(226,185,76));
        LinearLayout card=new LinearLayout(c);card.setTag(TAG);card.setOrientation(LinearLayout.VERTICAL);card.setGravity(Gravity.CENTER);
        card.setPadding(dp(c,15),dp(c,10),dp(c,15),dp(c,10));card.setBackground(round(Color.argb(247,20,8,11),17,accent,2));
        TextView t=text(c,a.title,11,accent,true);t.setGravity(Gravity.CENTER);t.setLetterSpacing(.08f);card.addView(t);
        TextView detail=text(c,a.detail,15,Color.rgb(249,239,221),true);detail.setGravity(Gravity.CENTER);card.addView(detail);
        attach(host,card,a.priority>=RoadAlertEngine.PRIORITY_URGENT?5600L:4300L);
    }

    /** Active safety card is truly centered in the available screen. */
    private static void attach(FrameLayout host,LinearLayout card,long ttl){
        int sw=host.getResources().getDisplayMetrics().widthPixels;
        int width=Math.min(dp(host.getContext(),420),Math.max(dp(host.getContext(),280),sw-dp(host.getContext(),36)));
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(width,-2,Gravity.CENTER);
        host.addView(card,lp);card.setAlpha(0f);card.setScaleX(.96f);card.setScaleY(.96f);
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(170).start();
        card.setOnClickListener(v->dismiss(host,card));card.postDelayed(()->dismiss(host,card),ttl);
    }

    /**
     * The permanent context card (e.g. WEATHER / chuva à frente) is centered only while
     * it is an actual alert. In its normal "PROTEÇÃO ATIVA" state it returns to the
     * original layout chosen by RoadMapActivity.
     */
    private static void positionPersistentAlert(Context c,FrameLayout host,Intent state){
        LinearLayout card=findPersistentAlert(host);if(card==null)return;
        FrameLayout.LayoutParams lp=card.getLayoutParams() instanceof FrameLayout.LayoutParams?(FrameLayout.LayoutParams)card.getLayoutParams():null;
        if(lp==null)return;
        SavedPosition saved=POSITIONS.get(card);
        if(saved==null){saved=new SavedPosition(lp);POSITIONS.put(card,saved);}

        final double lat=state.getDoubleExtra("lat",Double.NaN),lon=state.getDoubleExtra("lon",Double.NaN);
        card.setLongClickable(true);
        card.setOnLongClickListener(v->{ProtectionDiagnostics.show(c,lat,lon);return true;});

        String first=card.getChildCount()>0&&card.getChildAt(0) instanceof TextView?safe(String.valueOf(((TextView)card.getChildAt(0)).getText())).toUpperCase(Locale.ROOT):"";
        String currentType=norm(state.getStringExtra("hazard_type"));
        boolean hasTransientHazard=supported(currentType)&&!safe(state.getStringExtra("hazard_label")).isEmpty();
        boolean contextualAlert=!hasTransientHazard&&!first.isEmpty()&&!"PROTEÇÃO ATIVA".equals(first);

        if(contextualAlert){
            View parent=card.getParent() instanceof View?(View)card.getParent():null;
            int available=parent!=null&&parent.getWidth()>0?parent.getWidth()-dp(c,44):dp(c,300);
            lp.width=Math.max(saved.width,Math.min(dp(c,330),Math.max(dp(c,230),available)));
            lp.gravity=Gravity.CENTER;lp.setMargins(0,0,0,0);card.setLayoutParams(lp);
            card.setGravity(Gravity.CENTER_VERTICAL);
            if(android.os.Build.VERSION.SDK_INT>=21)card.setElevation(dp(c,72));
        }else{
            saved.restore(lp);card.setLayoutParams(lp);
        }
    }

    private static LinearLayout findPersistentAlert(View view){
        if(view instanceof LinearLayout){
            LinearLayout ll=(LinearLayout)view;
            if(ll.getOrientation()==LinearLayout.VERTICAL&&ll.getChildCount()==3&&ll.getParent() instanceof FrameLayout&&allTextChildren(ll))return ll;
        }
        if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int n=0;n<g.getChildCount();n++){LinearLayout found=findPersistentAlert(g.getChildAt(n));if(found!=null)return found;}}
        return null;
    }

    private static boolean allTextChildren(LinearLayout l){for(int n=0;n<l.getChildCount();n++)if(!(l.getChildAt(n) instanceof TextView))return false;return true;}

    private static boolean supported(String t){return"RADAR".equals(t)||"SEMAFORO_RADAR".equals(t)||"QUEBRA_MOLAS".equals(t)||"CAMERA_MONITORAMENTO".equals(t)||"SEMAFORO".equals(t)||"PEDAGIO".equals(t)||"PASSAGEM_NIVEL".equals(t)||"ACIDENTE".equals(t)||"OBJETO".equals(t);}
    private static int color(String t){if("RADAR".equals(t)||"ACIDENTE".equals(t)||"OBJETO".equals(t))return Color.rgb(239,41,58);if("SEMAFORO_RADAR".equals(t))return Color.rgb(245,73,62);if("QUEBRA_MOLAS".equals(t))return Color.rgb(230,155,48);if("CAMERA_MONITORAMENTO".equals(t))return Color.rgb(226,185,76);if("SEMAFORO".equals(t))return Color.rgb(255,94,80);if("PEDAGIO".equals(t))return Color.rgb(72,205,134);return Color.rgb(230,155,48);}
    private static String icon(String t){if("RADAR".equals(t))return"◎";if("SEMAFORO_RADAR".equals(t))return"◉";if("QUEBRA_MOLAS".equals(t))return"⌁";if("CAMERA_MONITORAMENTO".equals(t))return"◉";if("SEMAFORO".equals(t))return"●";if("PEDAGIO".equals(t))return"$";return"!";}
    private static String title(String t){if("RADAR".equals(t))return"RADAR À FRENTE";if("SEMAFORO_RADAR".equals(t))return"SEMÁFORO FISCALIZADO";if("QUEBRA_MOLAS".equals(t))return"QUEBRA-MOLAS À FRENTE";if("CAMERA_MONITORAMENTO".equals(t))return"CÂMERA À FRENTE";if("SEMAFORO".equals(t))return"SEMÁFORO À FRENTE";if("PEDAGIO".equals(t))return"PEDÁGIO À FRENTE";if("ACIDENTE".equals(t))return"ACIDENTE À FRENTE";if("OBJETO".equals(t))return"OBJETO NA VIA";return"PASSAGEM DE NÍVEL";}
    private static String distance(double d){return d>=1000?String.format(Locale.getDefault(),"%.1f km",d/1000.0):Math.max(10,Math.round(d/10.0)*10)+" m";}
    private static void dismiss(FrameLayout h,View v){if(v==null||v.getParent()==null)return;v.animate().alpha(0).scaleX(.97f).scaleY(.97f).setDuration(140).withEndAction(()->{try{if(v.getParent()==h)h.removeView(v);}catch(Throwable ignored){}}).start();}

    private static String norm(String raw){
        String t=safe(raw).toUpperCase(Locale.ROOT).replace('-','_').replace(' ','_');
        if(t.contains("SEMAFOR")&&(t.contains("RADAR")||t.contains("FISCAL")))return"SEMAFORO_RADAR";
        if(t.contains("QUEBRA")||t.contains("LOMBADA")||t.contains("BUMP"))return"QUEBRA_MOLAS";
        if(t.contains("CAMERA")||t.contains("CÂMERA")||t.contains("MONITOR"))return"CAMERA_MONITORAMENTO";
        if(t.contains("RADAR")||t.contains("SPEED_CAMERA"))return"RADAR";
        if(t.contains("SEMAFOR"))return"SEMAFORO";
        if(t.contains("PEDAG"))return"PEDAGIO";
        if(t.contains("ACIDENT"))return"ACIDENTE";
        if(t.contains("OBJETO")||t.contains("OBJECT"))return"OBJETO";
        if(t.contains("NIVEL")||t.contains("NÍVEL"))return"PASSAGEM_NIVEL";
        return t;
    }

    private static TextView text(Context c,String v,float s,int color,boolean bold){TextView t=new TextView(c);t.setText(v);t.setTextSize(s);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private static GradientDrawable round(int color,int radius,int stroke,int sw){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(radius*density);if(stroke!=0&&sw>0)g.setStroke(Math.max(1,Math.round(sw*density)),stroke);return g;}
    private static float density=1f;
    private static int dp(Context c,float v){density=c.getResources().getDisplayMetrics().density;return Math.round(v*density);}
    private static String safe(String s){return s==null?"":s.trim();}

    private static final class SavedPosition{
        final int width,height,gravity,left,top,right,bottom;
        SavedPosition(FrameLayout.LayoutParams p){width=p.width;height=p.height;gravity=p.gravity;left=p.leftMargin;top=p.topMargin;right=p.rightMargin;bottom=p.bottomMargin;}
        void restore(FrameLayout.LayoutParams p){p.width=width;p.height=height;p.gravity=gravity;p.setMargins(left,top,right,bottom);}
    }
}

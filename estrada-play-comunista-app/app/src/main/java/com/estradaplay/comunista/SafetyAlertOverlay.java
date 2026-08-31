package com.estradaplay.comunista;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** High-priority central road warning. */
final class SafetyAlertOverlay {
    private static final String TAG = "epc-central-safety-alert";
    private static String lastId = "";
    private static long lastAt;
    private SafetyAlertOverlay() {}

    static void show(Context context, FrameLayout host, Intent intent) {
        if (context == null || host == null || intent == null) return;
        String type = normalize(intent.getStringExtra("hazard_type"));
        if (!("RADAR".equals(type) || "QUEBRA_MOLAS".equals(type) || "CAMERA_MONITORAMENTO".equals(type))) return;
        String id = safe(intent.getStringExtra("hazard_id"));
        if (id.isEmpty()) id = type + ":" + Math.round(intent.getDoubleExtra("distance_m", 0));
        long now = System.currentTimeMillis();
        if (id.equals(lastId) && now-lastAt < 12000L) return;
        lastId=id; lastAt=now;
        View old=host.findViewWithTag(TAG); if(old!=null) host.removeView(old);

        int level=intent.getIntExtra("alert_level",1);
        int accent="QUEBRA_MOLAS".equals(type)?Color.rgb(234,145,34):
                ("CAMERA_MONITORAMENTO".equals(type)?Color.rgb(217,190,93):Color.rgb(208,24,45));
        if(level>=2 && "RADAR".equals(type)) accent=Color.rgb(255,45,45);
        int ink=Color.rgb(249,241,226), muted=Color.rgb(191,171,164), panel=Color.rgb(24,8,12);
        FrameLayout overlay=new FrameLayout(context); overlay.setTag(TAG);
        overlay.setBackgroundColor(Color.argb(level>=2?176:132,0,0,0)); overlay.setClickable(true);
        LinearLayout card=new LinearLayout(context); card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL); card.setPadding(dp(context,24),dp(context,20),dp(context,24),dp(context,18));
        card.setBackground(round(panel,22,accent,level>=2?3:2)); if(android.os.Build.VERSION.SDK_INT>=21)card.setElevation(dp(context,36));

        String over="RADAR".equals(type)?(level>=2?"REDUZA AGORA":"ALERTA DE FISCALIZAÇÃO"):
                ("QUEBRA_MOLAS".equals(type)?"ATENÇÃO NA VIA":"MONITORAMENTO DE TRÁFEGO");
        TextView overline=text(context,over,10,accent,true); overline.setLetterSpacing(.13f); overline.setGravity(Gravity.CENTER); card.addView(overline);
        String titleValue="RADAR".equals(type)?"RADAR À FRENTE":("QUEBRA_MOLAS".equals(type)?"QUEBRA-MOLAS À FRENTE":"CÂMERA À FRENTE");
        TextView title=text(context,titleValue,23,ink,true); title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-1,-2);tp.setMargins(0,dp(context,8),0,dp(context,12));card.addView(title,tp);

        int limit=intent.getIntExtra("radar_limit_kmh",intent.getIntExtra("limit_kmh",0));
        if("RADAR".equals(type)){
            TextView sign=text(context,limit>0?String.valueOf(limit):"?",limit>0?46:42,Color.rgb(18,18,18),true);sign.setGravity(Gravity.CENTER);
            sign.setBackground(round(Color.WHITE,100,accent,7));card.addView(sign,new LinearLayout.LayoutParams(dp(context,104),dp(context,104)));
            int delta=intent.getIntExtra("overspeed_delta_kmh",0);
            String l=limit>0?"KM/H · LIMITE DO RADAR":"LIMITE DO RADAR NÃO INFORMADO";
            if(delta>=2)l+="\nVOCÊ ESTÁ "+delta+" KM/H ACIMA";
            TextView lt=text(context,l,11,delta>=2?accent:ink,true);lt.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(context,9),0,0);card.addView(lt,lp);
        }else{
            TextView action=text(context,"QUEBRA_MOLAS".equals(type)?"REDUZA":"ATENÇÃO",30,accent,true);action.setLetterSpacing(.08f);action.setGravity(Gravity.CENTER);card.addView(action);
        }

        double distance=intent.getDoubleExtra("distance_m",0);String road=safe(intent.getStringExtra("road"));
        StringBuilder detail=new StringBuilder();if(distance>0)detail.append(distanceText(distance));if(!road.isEmpty()){if(detail.length()>0)detail.append(" · ");detail.append(road);}if(detail.length()==0)detail.append("Ponto detectado à frente");
        TextView info=text(context,detail.toString(),13,muted,false);info.setGravity(Gravity.CENTER);LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(-1,-2);ip.setMargins(0,dp(context,12),0,0);card.addView(info,ip);

        String source=safe(intent.getStringExtra("source"));if(!source.isEmpty()){
            String confidence=safe(intent.getStringExtra("hazard_confidence"));
            String value="FONTE · "+source.toUpperCase(Locale.ROOT)+(confidence.isEmpty()?"":" · "+confidence);
            TextView src=text(context,value,9,muted,true);src.setGravity(Gravity.CENTER);card.addView(src);
        }
        String next=safe(intent.getStringExtra("next_hazard_label"));double nd=intent.getDoubleExtra("next_distance_m",0);
        if(!next.isEmpty()&&nd>0){TextView n=text(context,"DEPOIS · "+next.toUpperCase(Locale.ROOT)+" · "+distanceText(nd),10,ink,true);n.setGravity(Gravity.CENTER);n.setBackground(round(Color.rgb(42,18,22),8,Color.rgb(82,39,45),1));n.setPadding(dp(context,10),dp(context,7),dp(context,10),dp(context,7));LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,-2);np.setMargins(0,dp(context,10),0,0);card.addView(n,np);}
        TextView close=text(context,"TOQUE PARA FECHAR",9,muted,true);close.setLetterSpacing(.12f);close.setGravity(Gravity.CENTER);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.setMargins(0,dp(context,12),0,0);card.addView(close,cp);
        int screenW=context.getResources().getDisplayMetrics().widthPixels;int width=Math.min(dp(context,430),Math.max(dp(context,280),screenW-dp(context,34)));
        overlay.addView(card,new FrameLayout.LayoutParams(width,-2,Gravity.CENTER));host.addView(overlay,new FrameLayout.LayoutParams(-1,-1));overlay.setAlpha(0);overlay.animate().alpha(1).setDuration(130).start();overlay.setOnClickListener(v->dismiss(host,overlay));overlay.postDelayed(()->dismiss(host,overlay),level>=2?6500L:5200L);
    }
    private static String distanceText(double d){return d>=1000?String.format(Locale.getDefault(),"%.1f km",d/1000.0):Math.max(10,Math.round(d/10.0)*10)+" m";}
    private static void dismiss(FrameLayout host,View overlay){if(host==null||overlay==null||overlay.getParent()==null)return;overlay.animate().alpha(0).setDuration(150).withEndAction(()->{try{if(overlay.getParent()==host)host.removeView(overlay);}catch(Throwable ignored){}}).start();}
    private static String normalize(String raw){String t=safe(raw).toUpperCase(Locale.ROOT).replace('-','_').replace(' ','_');if(t.contains("QUEBRA")||t.contains("LOMBADA")||t.contains("BUMP")||t.contains("HUMP"))return"QUEBRA_MOLAS";if(t.contains("CAMERA")||t.contains("CÂMERA")||t.contains("MONITOR")||t.contains("CCTV")||t.contains("SURVEILLANCE"))return"CAMERA_MONITORAMENTO";if(t.contains("RADAR")||t.contains("SPEED_CAMERA")||t.contains("ENFORCEMENT"))return"RADAR";return t;}
    private static TextView text(Context c,String v,float s,int color,boolean bold){TextView t=new TextView(c);t.setText(v);t.setTextSize(s);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);t.setLineSpacing(0,1.04f);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private static GradientDrawable round(int color,int radius,int stroke,int sw){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dpRaw(radius));if(stroke!=0&&sw>0)d.setStroke(dpRaw(sw),stroke);return d;}
    private static float density=1f;private static int dpRaw(float v){return Math.round(v*density);}private static int dp(Context c,float v){density=c.getResources().getDisplayMetrics().density;return Math.round(v*density);}private static String safe(String s){return s==null?"":s.trim();}
}

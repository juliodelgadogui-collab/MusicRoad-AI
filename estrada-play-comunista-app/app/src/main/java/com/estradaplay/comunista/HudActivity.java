package com.estradaplay.comunista;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

public final class HudActivity extends ComponentActivity {
    private final int BG=Color.BLACK,TEXT=Color.rgb(246,238,224),MUTED=Color.rgb(166,148,145),RED=Color.rgb(255,45,52),GREEN=Color.rgb(72,212,134),GOLD=Color.rgb(226,185,76),BORDER=Color.rgb(76,38,44),SURFACE=Color.rgb(17,10,12);
    private LinearLayout projection;
    private TextView speed,limit,hazard,distance,mirrorState;
    private boolean registered;

    private final BroadcastReceiver rx=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
        if(speed==null)return;
        speed.setText(String.valueOf(Math.max(0,Math.round(i.getDoubleExtra("speed_kmh",0)))));
        int l=i.getIntExtra("road_limit_kmh",0);limit.setText(l>0?String.valueOf(l):"--");
        String h=i.getStringExtra("hazard_label");double d=i.getDoubleExtra("distance_m",0);
        boolean has=h!=null&&!h.trim().isEmpty();hazard.setText(has?h.trim().toUpperCase():"ESTRADA LIVRE");hazard.setTextColor(has?GOLD:GREEN);
        distance.setText(has&&d>0?(d>=1000?String.format(java.util.Locale.getDefault(),"%.1f km",d/1000.0):Math.round(d)+" m"):"PROTEÇÃO ATIVA");
    }};

    @Override protected void onCreate(Bundle b){super.onCreate(b);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);build();}

    private void build(){
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(BG);setContentView(root);
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(14),dp(10),dp(14),dp(12));root.addView(page,new FrameLayout.LayoutParams(-1,-1));

        LinearLayout controls=new LinearLayout(this);controls.setOrientation(LinearLayout.HORIZONTAL);controls.setGravity(Gravity.CENTER_VERTICAL);
        Button back=button("‹ VOLTAR",false);controls.addView(back,new LinearLayout.LayoutParams(dp(96),dp(44)));back.setOnClickListener(v->finish());
        LinearLayout titleBox=new LinearLayout(this);titleBox.setOrientation(LinearLayout.VERTICAL);titleBox.setPadding(dp(10),0,0,0);TextView over=text("HUD DE PARA-BRISA",9,RED,true);over.setLetterSpacing(.12f);titleBox.addView(over);titleBox.addView(text("Projeção",18,TEXT,true));controls.addView(titleBox,new LinearLayout.LayoutParams(0,-2,1));
        Button mirror=button("ESPELHAR",false);controls.addView(mirror,new LinearLayout.LayoutParams(dp(106),dp(44)));mirror.setOnClickListener(v->{boolean next=!DriveSettings.hudMirror(this);DriveSettings.toggle(this,"hud_mirror",next);applyMirror();});page.addView(controls);

        LinearLayout stateRow=new LinearLayout(this);stateRow.setOrientation(LinearLayout.HORIZONTAL);stateRow.setGravity(Gravity.CENTER_VERTICAL);stateRow.setPadding(dp(12),dp(8),dp(12),dp(8));stateRow.setBackground(panel(SURFACE,14,BORDER));
        mirrorState=text("",10,MUTED,true);stateRow.addView(mirrorState,new LinearLayout.LayoutParams(0,-2,1));TextView hint=text("Reflexo no vidro inverte novamente o conteúdo",9,MUTED,false);hint.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);stateRow.addView(hint,new LinearLayout.LayoutParams(0,-2,1));LinearLayout.LayoutParams srp=new LinearLayout.LayoutParams(-1,-2);srp.setMargins(0,dp(8),0,dp(8));page.addView(stateRow,srp);

        projection=new LinearLayout(this);projection.setOrientation(LinearLayout.VERTICAL);projection.setGravity(Gravity.CENTER);projection.setPadding(dp(18),dp(18),dp(18),dp(18));projection.setBackgroundColor(Color.BLACK);page.addView(projection,new LinearLayout.LayoutParams(-1,0,1));

        TextView speedLabel=text("VELOCIDADE",10,MUTED,true);speedLabel.setLetterSpacing(.16f);speedLabel.setGravity(Gravity.CENTER);projection.addView(speedLabel);
        speed=text("0",useLandscape()?112:104,RED,true);speed.setGravity(Gravity.CENTER);projection.addView(speed,new LinearLayout.LayoutParams(-1,-2));
        TextView kmh=text("KM/H",13,MUTED,true);kmh.setGravity(Gravity.CENTER);kmh.setLetterSpacing(.16f);projection.addView(kmh);

        LinearLayout center=new LinearLayout(this);center.setOrientation(LinearLayout.HORIZONTAL);center.setGravity(Gravity.CENTER);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.setMargins(0,dp(18),0,0);projection.addView(center,cp);
        LinearLayout limitBox=new LinearLayout(this);limitBox.setOrientation(LinearLayout.VERTICAL);limitBox.setGravity(Gravity.CENTER);limitBox.setBackground(panel(Color.rgb(18,9,12),18,Color.rgb(238,238,238)));limitBox.setPadding(dp(18),dp(10),dp(18),dp(10));TextView lt=text("LIMITE",10,MUTED,true);lt.setGravity(Gravity.CENTER);limitBox.addView(lt);limit=text("--",34,TEXT,true);limit.setGravity(Gravity.CENTER);limitBox.addView(limit);center.addView(limitBox,new LinearLayout.LayoutParams(dp(128),dp(100)));

        LinearLayout alertBox=new LinearLayout(this);alertBox.setOrientation(LinearLayout.VERTICAL);alertBox.setGravity(Gravity.CENTER_VERTICAL);alertBox.setPadding(dp(16),dp(10),dp(16),dp(10));alertBox.setBackground(panel(Color.rgb(15,8,10),18,BORDER));hazard=text("ESTRADA LIVRE",17,GREEN,true);hazard.setGravity(Gravity.CENTER);distance=text("PROTEÇÃO ATIVA",11,MUTED,true);distance.setGravity(Gravity.CENTER);alertBox.addView(hazard);alertBox.addView(distance);LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(useLandscape()?dp(300):0,dp(100),useLandscape()?0f:1f);ap.setMargins(dp(10),0,0,0);center.addView(alertBox,ap);

        TextView safety=text("HUD é apoio visual. Mantenha atenção na estrada.",9,MUTED,false);safety.setGravity(Gravity.CENTER);LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2);sp.setMargins(0,dp(14),0,0);projection.addView(safety,sp);
        applyMirror();
    }

    private void applyMirror(){boolean mirrored=DriveSettings.hudMirror(this);if(projection!=null)projection.setScaleX(mirrored?-1f:1f);if(mirrorState!=null){mirrorState.setText(mirrored?"ESPELHADO PARA PARA-BRISA":"LEITURA NORMAL NA TELA");mirrorState.setTextColor(mirrored?GOLD:GREEN);}}
    private boolean useLandscape(){return getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;}

    @Override protected void onStart(){super.onStart();IntentFilter f=new IntentFilter(RoadSafetyService.ACTION_STATE);if(Build.VERSION.SDK_INT>=33)registerReceiver(rx,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(rx,f);registered=true;}
    @Override protected void onStop(){if(registered){try{unregisterReceiver(rx);}catch(Throwable ignored){}registered=false;}super.onStop();}

    private TextView text(String v,float s,int c,boolean b){TextView t=new TextView(this);t.setText(v);t.setTextSize(s);t.setTextColor(c);t.setGravity(Gravity.CENTER_VERTICAL);if(b)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private Button button(String v,boolean primary){Button b=new Button(this);b.setText(v);b.setAllCaps(false);b.setTextColor(TEXT);b.setTextSize(9);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setLetterSpacing(.05f);b.setStateListAnimator(null);b.setBackground(panel(primary?RED:SURFACE,13,primary?0:BORDER));return b;}
    private GradientDrawable panel(int color,int radius,int stroke){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));if(stroke!=0)g.setStroke(dp(1),stroke);return g;}private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
}

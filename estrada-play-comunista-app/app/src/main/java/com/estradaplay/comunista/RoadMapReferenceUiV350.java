package com.estradaplay.comunista;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Locale;

/**
 * ROAD_REFERENCE_UI_V350
 * Rebuilds the Estrada screen around the supplied automotive references instead of shrinking the
 * legacy dashboard. The map remains the base layer; safety, route and media engines are untouched.
 */
final class RoadMapReferenceUiV350 implements Application.ActivityLifecycleCallbacks {
    private static final String TAG="epc-road-reference-v350";
    private final Application app;
    private final Handler main=new Handler(Looper.getMainLooper());
    private WeakReference<RoadMapActivity> resumed=new WeakReference<>(null);
    private FrameLayout host;
    private boolean landscapeBuilt;
    private ReferenceSpeedometerView speedometer;
    private TextView gps,limit,guideTitle,guideSub,playerTitle,playerArtist,eta,duration,distance,average;
    private Button play;
    private double lastSpeed;
    private int lastLimit;
    private boolean playing;
    private String musicTitle="Biblioteca offline",musicArtist="Música local";

    static void install(Application app){if(app==null)return;RoadMapReferenceUiV350 x=new RoadMapReferenceUiV350(app);app.registerActivityLifecycleCallbacks(x);x.register();}
    private RoadMapReferenceUiV350(Application a){app=a;}

    private void register(){
        IntentFilter f=new IntentFilter();f.addAction(RoadSafetyService.ACTION_STATE);f.addAction(PlayerService.ACTION_STATE);
        try{if(Build.VERSION.SDK_INT>=33)app.registerReceiver(rx,f,Context.RECEIVER_NOT_EXPORTED);else app.registerReceiver(rx,f);}catch(Throwable ignored){}
    }

    private final BroadcastReceiver rx=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
        if(i==null)return;String a=i.getAction();
        if(RoadSafetyService.ACTION_STATE.equals(a)){lastSpeed=i.getDoubleExtra("speed_kmh",lastSpeed);lastLimit=i.getIntExtra("limit_kmh",i.getIntExtra("road_limit_kmh",lastLimit));}
        else if(PlayerService.ACTION_STATE.equals(a)){String t=i.getStringExtra("title"),ar=i.getStringExtra("artist");if(t!=null&&!t.trim().isEmpty())musicTitle=t.trim();if(ar!=null&&!ar.trim().isEmpty())musicArtist=ar.trim();playing=i.getBooleanExtra("playing",false);}
        RoadMapActivity r=resumed.get();if(r!=null&&!r.isFinishing())main.postDelayed(()->apply(r),35L);
    }};

    private void apply(RoadMapActivity a){
        FrameLayout root=field(a,"root",FrameLayout.class);RoadMapView map=field(a,"roadMap",RoadMapView.class);if(root==null||map==null||!(map.getParent() instanceof FrameLayout))return;
        FrameLayout mapPane=(FrameLayout)map.getParent();int w=root.getWidth(),h=root.getHeight();if(w<=0||h<=0)return;boolean landscape=w>h||"horizontal".equals(BuildConfig.FIXED_LAYOUT);
        FrameLayout.LayoutParams mp=mapPane.getLayoutParams() instanceof FrameLayout.LayoutParams?(FrameLayout.LayoutParams)mapPane.getLayoutParams():new FrameLayout.LayoutParams(-1,-1);mp.width=-1;mp.height=-1;mp.gravity=Gravity.FILL;mp.setMargins(dp(landscape?4:5),dp(landscape?4:5),dp(landscape?4:5),dp(landscape?4:5));mapPane.setLayoutParams(mp);mapPane.setVisibility(View.VISIBLE);
        hideLegacy(a,root,mapPane);
        View old=root.findViewWithTag(TAG);if(!(old instanceof FrameLayout)||host!=old||landscapeBuilt!=landscape){if(old!=null)root.removeView(old);host=new FrameLayout(a);host.setTag(TAG);host.setClickable(false);host.setFocusable(false);root.addView(host,new FrameLayout.LayoutParams(-1,-1));landscapeBuilt=landscape;if(landscape)buildLandscape(a,w,h);else buildPortrait(a,w,h);}sync(a);
    }

    private void hideLegacy(RoadMapActivity a,FrameLayout root,FrameLayout mapPane){
        hideParent(field(a,"navInstructionText",TextView.class),2);hideParent(field(a,"speedText",TextView.class),1);hideParent(field(a,"navEtaText",TextView.class),2);hideParent(field(a,"mapPlayerTitle",TextView.class),2);View hc=field(a,"hazardCard",LinearLayout.class);if(hc!=null)hc.setVisibility(View.GONE);
        for(int n=0;n<root.getChildCount();n++){View v=root.getChildAt(n);if(v==mapPane||v==host||TAG.equals(v.getTag()))continue;String s=textOf(v).toUpperCase(Locale.ROOT);if((s.contains("ESTRADA")&&s.contains("RÁDIO")&&s.contains("VIAGEM"))||s.contains("CENTRAL AUTOMOTIVA")||s.contains("BIBLIOTECA OFFLINE")||s.contains("CANCELAR ROTA"))v.setVisibility(View.GONE);}
    }

    private void buildLandscape(RoadMapActivity a,int w,int h){
        int m=dp(14),gap=dp(10),bottomH=dp(72),sideW=Math.max(dp(255),Math.min(dp(335),(int)(w*.27f)));
        LinearLayout top=panelRow(a,Color.argb(238,5,8,10),20,Color.rgb(172,16,37));top.setPadding(dp(18),dp(8),dp(18),dp(8));
        LinearLayout brand=col(a);TextView b1=txt(a,"★  LIVRE",24,Color.WHITE,true);TextView b2=txt(a,"PROTEÇÃO",9,Color.rgb(190,178,180),false);brand.addView(b1);brand.addView(b2);top.addView(brand,new LinearLayout.LayoutParams(dp(220),-1));
        View div=new View(a);div.setBackgroundColor(Color.rgb(180,20,39));LinearLayout.LayoutParams dv=new LinearLayout.LayoutParams(dp(2),-1);dv.setMargins(dp(8),dp(6),dp(18),dp(6));top.addView(div,dv);
        LinearLayout guidance=col(a);guideTitle=txt(a,"Siga a estrada",20,Color.WHITE,false);guideSub=txt(a,"Navegação ativa",10,Color.rgb(174,160,162),false);guidance.addView(guideTitle);guidance.addView(guideSub);top.addView(guidance,new LinearLayout.LayoutParams(0,-1,1));
        gps=pill(a,"●  GPS ATIVO",Color.rgb(18,218,112),Color.argb(105,0,72,45));top.addView(gps,new LinearLayout.LayoutParams(dp(142),dp(44)));
        FrameLayout.LayoutParams tp=new FrameLayout.LayoutParams(w-sideW-m*3,dp(88),Gravity.TOP|Gravity.LEFT);tp.setMargins(m,m,0,0);host.addView(top,tp);

        LinearLayout speedCard=col(a);speedCard.setGravity(Gravity.CENTER);speedCard.setPadding(dp(8),dp(4),dp(8),dp(4));speedCard.setBackground(round(Color.argb(244,4,5,8),22,Color.rgb(165,16,35),1));speedometer=new ReferenceSpeedometerView(a);speedCard.addView(speedometer,new LinearLayout.LayoutParams(-1,0,1));
        FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(sideW,Math.max(dp(250),(int)((h-bottomH-m*3)*.48f)),Gravity.TOP|Gravity.RIGHT);sp.setMargins(0,m,m,0);host.addView(speedCard,sp);

        LinearLayout player=col(a);player.setPadding(dp(18),dp(14),dp(18),dp(12));player.setBackground(round(Color.argb(244,6,6,9),22,Color.rgb(165,16,35),1));TextView lab=txt(a,"MÚSICA OFFLINE",9,Color.rgb(220,36,58),true);playerTitle=txt(a,musicTitle,18,Color.WHITE,false);playerArtist=txt(a,musicArtist,11,Color.rgb(174,160,162),false);player.addView(lab);player.addView(playerTitle);player.addView(playerArtist);View wave=new View(a);wave.setBackgroundColor(Color.argb(110,185,20,40));LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(-1,dp(2));wp.setMargins(0,dp(18),0,dp(12));player.addView(wave,wp);LinearLayout controls=row(a);Button prev=mediaButton(a,"|◀"),next=mediaButton(a,"▶|");play=mediaButton(a,playing?"Ⅱ":"▶");play.setTextSize(24);play.setBackground(round(Color.rgb(218,13,39),100,0,0));controls.addView(prev,new LinearLayout.LayoutParams(0,dp(76),1));LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(0,dp(86),1.15f);pp.setMargins(dp(10),0,dp(10),0);controls.addView(play,pp);controls.addView(next,new LinearLayout.LayoutParams(0,dp(76),1));player.addView(controls,new LinearLayout.LayoutParams(-1,0,1));prev.setOnClickListener(v->playerAction(a,PlayerService.ACTION_PREVIOUS));play.setOnClickListener(v->playerAction(a,PlayerService.ACTION_TOGGLE));next.setOnClickListener(v->playerAction(a,PlayerService.ACTION_NEXT));
        int playerTop=m+sp.height+gap;FrameLayout.LayoutParams pl=new FrameLayout.LayoutParams(sideW,h-bottomH-m-playerTop-gap,Gravity.TOP|Gravity.RIGHT);pl.setMargins(0,playerTop,m,0);host.addView(player,pl);

        Button central=roundButton(a,"◎\nCENTRAL");central.setOnClickListener(v->a.startActivity(new Intent(a,DriveToolsActivity.class)));FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(dp(96),dp(96),Gravity.BOTTOM|Gravity.LEFT);cp.setMargins(dp(28),0,0,bottomH+m+dp(18));host.addView(central,cp);

        LinearLayout metrics=panelRow(a,Color.argb(244,4,5,8),18,Color.rgb(165,16,35));metrics.setPadding(dp(10),dp(6),dp(10),dp(6));eta=metric(a,"—:—","CHEGADA");duration=metric(a,"—","RESTANTE");distance=metric(a,"—","DISTÂNCIA");average=metric(a,"0 km/h","MÉDIA");metrics.addView(metricWrap(a,"⚑",eta),new LinearLayout.LayoutParams(0,-1,1));metrics.addView(metricWrap(a,"◷",duration),new LinearLayout.LayoutParams(0,-1,1));metrics.addView(metricWrap(a,"╱╲",distance),new LinearLayout.LayoutParams(0,-1,1));metrics.addView(metricWrap(a,"◴",average),new LinearLayout.LayoutParams(0,-1,1));FrameLayout.LayoutParams bm=new FrameLayout.LayoutParams(-1,bottomH,Gravity.BOTTOM);bm.setMargins(m,0,m,m);host.addView(metrics,bm);
    }

    private void buildPortrait(RoadMapActivity a,int w,int h){
        int m=dp(12);LinearLayout top=panelRow(a,Color.argb(238,4,6,9),20,Color.rgb(70,42,47));top.setGravity(Gravity.CENTER_VERTICAL);top.setPadding(dp(10),dp(7),dp(12),dp(7));Button menu=plainButton(a,"☰");menu.setTextSize(24);menu.setOnClickListener(v->a.startActivity(new Intent(a,DriveToolsActivity.class)));top.addView(menu,new LinearLayout.LayoutParams(dp(58),dp(58)));TextView star=txt(a,"★",28,Color.rgb(235,191,66),true);star.setGravity(Gravity.CENTER);top.addView(star,new LinearLayout.LayoutParams(dp(48),-1));LinearLayout brand=col(a);brand.addView(txt(a,"LIVRE",24,Color.WHITE,false));brand.addView(txt(a,"PROTEÇÃO",9,Color.rgb(174,160,162),false));top.addView(brand,new LinearLayout.LayoutParams(0,-1,1));gps=pill(a,"●  GPS ATIVO",Color.rgb(17,221,113),Color.argb(110,0,77,47));top.addView(gps,new LinearLayout.LayoutParams(dp(135),dp(44)));FrameLayout.LayoutParams tp=new FrameLayout.LayoutParams(-1,dp(88),Gravity.TOP);tp.setMargins(m,m,m,0);host.addView(top,tp);

        limit=txt(a,"—",25,Color.rgb(20,20,20),true);limit.setGravity(Gravity.CENTER);GradientDrawable lg=new GradientDrawable();lg.setShape(GradientDrawable.OVAL);lg.setColor(Color.WHITE);lg.setStroke(dp(7),Color.rgb(230,25,39));limit.setBackground(lg);FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(dp(72),dp(72),Gravity.TOP|Gravity.LEFT);lp.setMargins(dp(22),dp(148),0,0);host.addView(limit,lp);

        speedometer=new ReferenceSpeedometerView(a);FrameLayout.LayoutParams sv=new FrameLayout.LayoutParams(Math.min(w-dp(30),dp(330)),Math.min(w-dp(30),dp(330)),Gravity.CENTER_HORIZONTAL|Gravity.TOP);sv.topMargin=Math.max(dp(285),(int)(h*.39f));host.addView(speedometer,sv);

        LinearLayout drive=row(a);drive.setGravity(Gravity.CENTER);Button ptt=actionButton(a,"((•))\nPTT"),mic=actionButton(a,"🎙"),dash=actionButton(a,"▰\nDASH");mic.setTextSize(28);mic.setBackground(round(Color.rgb(220,13,39),22,0,0));ptt.setOnClickListener(v->a.startActivity(new Intent(a,RoadRadioActivity.class)));mic.setOnClickListener(v->CopilotService.requestListenNow(a));dash.setOnClickListener(v->a.startActivity(new Intent(a,CameraActivity.class)));drive.addView(ptt,new LinearLayout.LayoutParams(0,dp(88),1));LinearLayout.LayoutParams mm=new LinearLayout.LayoutParams(0,dp(96),1.15f);mm.setMargins(dp(10),0,dp(10),0);drive.addView(mic,mm);drive.addView(dash,new LinearLayout.LayoutParams(0,dp(88),1));FrameLayout.LayoutParams dr=new FrameLayout.LayoutParams(-1,dp(98),Gravity.BOTTOM);dr.setMargins(dp(22),0,dp(22),dp(164));host.addView(drive,dr);

        LinearLayout player=row(a);player.setGravity(Gravity.CENTER_VERTICAL);player.setPadding(dp(12),dp(7),dp(10),dp(7));player.setBackground(round(Color.argb(240,5,6,9),18,Color.rgb(160,16,35),1));LinearLayout meta=col(a);playerTitle=txt(a,musicTitle,15,Color.WHITE,false);playerArtist=txt(a,musicArtist,10,Color.rgb(174,160,162),false);meta.addView(playerTitle);meta.addView(playerArtist);player.addView(meta,new LinearLayout.LayoutParams(0,-1,1));Button prev=mediaButton(a,"◀"),next=mediaButton(a,"▶|");play=mediaButton(a,playing?"Ⅱ":"▶");play.setBackground(round(Color.rgb(218,13,39),18,0,0));player.addView(prev,new LinearLayout.LayoutParams(dp(54),dp(54)));player.addView(play,new LinearLayout.LayoutParams(dp(62),dp(62)));player.addView(next,new LinearLayout.LayoutParams(dp(54),dp(54)));prev.setOnClickListener(v->playerAction(a,PlayerService.ACTION_PREVIOUS));play.setOnClickListener(v->playerAction(a,PlayerService.ACTION_TOGGLE));next.setOnClickListener(v->playerAction(a,PlayerService.ACTION_NEXT));FrameLayout.LayoutParams pr=new FrameLayout.LayoutParams(-1,dp(76),Gravity.BOTTOM);pr.setMargins(m,0,m,dp(78));host.addView(player,pr);

        LinearLayout nav=panelRow(a,Color.argb(245,4,6,9),18,Color.rgb(62,43,47));nav.setPadding(dp(4),dp(4),dp(4),dp(4));Button mapB=navButton(a,"▣\nMapa",true),routeB=navButton(a,"⌘\nRotas",false),musicB=navButton(a,"♪\nMúsica",false),moreB=navButton(a,"⠿\nMais",false);nav.addView(mapB,new LinearLayout.LayoutParams(0,-1,1));nav.addView(routeB,new LinearLayout.LayoutParams(0,-1,1));nav.addView(musicB,new LinearLayout.LayoutParams(0,-1,1));nav.addView(moreB,new LinearLayout.LayoutParams(0,-1,1));mapB.setOnClickListener(v->{RoadMapView rm=field(a,"roadMap",RoadMapView.class);if(rm!=null)rm.recenter();});routeB.setOnClickListener(v->a.startActivity(new Intent(a,DestinationActivity.class)));musicB.setOnClickListener(v->a.startActivity(new Intent(a,MusicPlayerActivity.class)));moreB.setOnClickListener(v->a.startActivity(new Intent(a,DriveToolsActivity.class)));FrameLayout.LayoutParams np=new FrameLayout.LayoutParams(-1,dp(66),Gravity.BOTTOM);np.setMargins(m,0,m,m);host.addView(nav,np);

        Button rec=roundButton(a,"◎");rec.setTextSize(22);rec.setOnClickListener(v->{RoadMapView rm=field(a,"roadMap",RoadMapView.class);if(rm!=null)rm.recenter();});FrameLayout.LayoutParams rp=new FrameLayout.LayoutParams(dp(54),dp(54),Gravity.RIGHT|Gravity.CENTER_VERTICAL);rp.setMargins(0,0,dp(18),dp(50));host.addView(rec,rp);
    }

    private void sync(RoadMapActivity a){
        if(speedometer!=null)speedometer.setSpeed(lastSpeed);if(limit!=null)limit.setText(lastLimit>0?String.valueOf(lastLimit):"—");if(gps!=null)gps.setText("●  GPS ATIVO");if(playerTitle!=null)playerTitle.setText(musicTitle);if(playerArtist!=null)playerArtist.setText(musicArtist);if(play!=null)play.setText(playing?"Ⅱ":"▶");
        TextView ni=field(a,"navInstructionText",TextView.class),nr=field(a,"navRoadText",TextView.class),ne=field(a,"navEtaText",TextView.class),nrem=field(a,"navRemainingText",TextView.class),nd=field(a,"navDurationText",TextView.class);DestinationStore.Destination dest=DestinationStore.read(a);
        if(guideTitle!=null)guideTitle.setText(dest==null?"Siga a estrada":safeText(ni,"Siga a rota"));if(guideSub!=null)guideSub.setText(dest==null?"Navegação ativa":safeText(nr,dest.label));if(eta!=null)eta.setText(safeText(ne,"—:—"));if(duration!=null)duration.setText(safeText(nd,"—"));if(distance!=null)distance.setText(safeText(nrem,"—"));if(average!=null){try{average.setText(new DriveSessionStore(a).snapshot().averageLabel());}catch(Throwable ignored){average.setText("0 km/h");}}
    }

    private void playerAction(Context c,String action){try{c.startService(new Intent(c,PlayerService.class).setAction(action));}catch(Throwable ignored){}}
    private LinearLayout metricWrap(Context c,String icon,TextView value){LinearLayout r=row(c);r.setGravity(Gravity.CENTER);TextView i=txt(c,icon,22,Color.WHITE,false);i.setGravity(Gravity.CENTER);r.addView(i,new LinearLayout.LayoutParams(dp(48),-1));LinearLayout x=col(c);x.setGravity(Gravity.CENTER_VERTICAL);TextView label=txt(c,String.valueOf(value.getTag()),9,Color.rgb(174,160,162),false);x.addView(label);x.addView(value);r.addView(x,new LinearLayout.LayoutParams(0,-1,1));return r;}
    private TextView metric(Context c,String value,String label){TextView v=txt(c,value,17,Color.WHITE,false);v.setTag(label);return v;}
    private Button mediaButton(Context c,String s){Button b=plainButton(c,s);b.setTextSize(20);b.setTextColor(Color.WHITE);b.setBackground(round(Color.argb(220,8,9,12),100,Color.rgb(115,28,40),1));return b;}
    private Button actionButton(Context c,String s){Button b=plainButton(c,s);b.setTextSize(15);b.setTextColor(Color.WHITE);b.setBackground(round(Color.argb(238,8,10,14),20,Color.rgb(90,40,51),1));return b;}
    private Button navButton(Context c,String s,boolean active){Button b=plainButton(c,s);b.setTextSize(11);b.setTextColor(active?Color.rgb(237,32,55):Color.rgb(220,216,216));if(active)b.setBackground(round(Color.argb(80,160,13,31),14,0,0));return b;}
    private Button roundButton(Context c,String s){Button b=plainButton(c,s);b.setText(s);b.setTextColor(Color.WHITE);b.setTextSize(11);b.setBackground(round(Color.argb(238,5,7,10),100,Color.rgb(187,18,40),2));return b;}
    private Button plainButton(Context c,String s){Button b=new Button(c);b.setText(s);b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setPadding(dp(4),0,dp(4),0);b.setMinHeight(0);b.setMinimumHeight(0);b.setStateListAnimator(null);b.setBackgroundColor(Color.TRANSPARENT);return b;}
    private TextView pill(Context c,String s,int color,int bg){TextView t=txt(c,s,10,color,true);t.setGravity(Gravity.CENTER);t.setBackground(round(bg,100,Color.argb(120,20,190,105),1));return t;}
    private LinearLayout metricParent(TextView v){if(v==null)return null;View p=(View)v.getParent();return p instanceof LinearLayout?(LinearLayout)p:null;}
    private void hideParent(View v,int levels){View x=v;for(int i=0;i<levels&&x!=null;i++)x=x.getParent() instanceof View?(View)x.getParent():null;if(x!=null)x.setVisibility(View.GONE);}
    private String safeText(TextView t,String fallback){if(t==null)return fallback;String s=String.valueOf(t.getText()).trim();return s.isEmpty()?fallback:s;}
    private String textOf(View v){StringBuilder b=new StringBuilder();collect(v,b);return b.toString();}
    private void collect(View v,StringBuilder b){if(v instanceof TextView)b.append(' ').append(((TextView)v).getText());if(v instanceof android.view.ViewGroup){android.view.ViewGroup g=(android.view.ViewGroup)v;for(int i=0;i<g.getChildCount();i++)collect(g.getChildAt(i),b);}}
    private LinearLayout panelRow(Context c,int color,int radius,int stroke){LinearLayout l=row(c);l.setBackground(round(color,radius,stroke,1));return l;}
    private LinearLayout row(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.HORIZONTAL);return l;}
    private LinearLayout col(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private TextView txt(Context c,String s,float z,int color,boolean bold){TextView t=new TextView(c);t.setText(s);t.setTextSize(z);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private GradientDrawable round(int color,int radius,int stroke,int strokeW){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));if(stroke!=0&&strokeW>0)g.setStroke(dp(strokeW),stroke);return g;}
    private int dp(float v){return Math.round(v*app.getResources().getDisplayMetrics().density);}
    @SuppressWarnings("unchecked") private <T>T field(Object o,String name,Class<T> type){try{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);Object v=f.get(o);return type.isInstance(v)?(T)v:null;}catch(Throwable ignored){return null;}}

    @Override public void onActivityResumed(Activity activity){if(activity instanceof RoadMapActivity){RoadMapActivity a=(RoadMapActivity)activity;resumed=new WeakReference<>(a);main.postDelayed(()->apply(a),90L);}}
    @Override public void onActivityPaused(Activity activity){if(resumed.get()==activity)resumed=new WeakReference<>(null);}
    @Override public void onActivityCreated(Activity a,Bundle b){}@Override public void onActivityStarted(Activity a){}@Override public void onActivityStopped(Activity a){}@Override public void onActivitySaveInstanceState(Activity a,Bundle b){}@Override public void onActivityDestroyed(Activity a){}
}

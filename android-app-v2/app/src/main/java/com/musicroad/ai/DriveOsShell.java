package com.musicroad.ai;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import com.mapbox.geojson.Point;
import com.mapbox.maps.CameraOptions;
import com.mapbox.maps.MapboxMap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Dedicated landscape UI for MusicRoad. Portrait remains owned by MainActivity.
 * This shell uses the same native services, Mapbox Maps view, route engine, media
 * service, offline store and alert service instead of creating a second product.
 */
final class DriveOsShell {
    private static final String TAG="musicroad-driveos-shell-v22";
    private static final int HOME=0,MAP=1,MUSIC=2,OFFLINE=3,SETTINGS=4,RADIO=5;
    private static final int BG=Color.rgb(2,7,12),PANEL=Color.rgb(7,15,23),PANEL2=Color.rgb(12,23,34),LINE=Color.rgb(39,55,68),TEXT=Color.rgb(247,250,252),MUTED=Color.rgb(138,158,177),ACCENT=Color.rgb(255,122,26),PURPLE=Color.rgb(152,66,255),GREEN=Color.rgb(43,224,137),RED=Color.rgb(255,77,82);
    private static final String PREFS="musicroad_native_shell_v1",RECENTS="driveos_recent_destinations_v1",FAVORITES="driveos_favorite_destinations_v1";
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static WeakReference<MainActivity> guarded=new WeakReference<>(null);
    private static Context appContext;
    private static boolean playerReceiverRegistered=false;
    private static JSONObject playerState=new JSONObject();
    private static WeakReference<MediaBinding> mediaBinding=new WeakReference<>(null);

    private DriveOsShell(){}

    static void init(Application app){
        appContext=app.getApplicationContext();ensurePlayerReceiver();
    }

    static void install(MainActivity a){
        if(a==null||a.isFinishing())return;
        if(a.getResources().getConfiguration().orientation!=Configuration.ORIENTATION_LANDSCAPE)return;
        Object account=call(a,"hasAccount",new Class<?>[0]);
        if(!(account instanceof Boolean)||!((Boolean)account))return;
        FrameLayout root=(FrameLayout)get(a,"root");if(root==null)return;
        if(root.findViewWithTag(TAG)!=null){startGuardian(a);return;}
        immersive(a);
        root.removeAllViews();
        LinearLayout full=new LinearLayout(a);full.setTag(TAG);full.setOrientation(LinearLayout.VERTICAL);full.setBackgroundColor(BG);root.addView(full,new FrameLayout.LayoutParams(-1,-1));
        full.addView(statusBar(a),new LinearLayout.LayoutParams(-1,dp(a,36)));
        FrameLayout stage=new FrameLayout(a);full.addView(stage,new LinearLayout.LayoutParams(-1,0,1));
        int screen=intField(a,"screen",HOME);
        if(screen==MAP)map(a,stage);else if(screen==HOME)home(a,stage);else if(screen==MUSIC)music(a,stage);else utility(a,stage,screen);
        startGuardian(a);
    }

    private static void startGuardian(MainActivity a){
        guarded=new WeakReference<>(a);
        MAIN.removeCallbacks(GUARDIAN);MAIN.postDelayed(GUARDIAN,700);
    }

    private static final Runnable GUARDIAN=new Runnable(){@Override public void run(){
        MainActivity a=guarded.get();if(a==null||a.isFinishing())return;
        if(a.getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE){
            FrameLayout root=(FrameLayout)get(a,"root");if(root!=null&&root.findViewWithTag(TAG)==null)install(a);
            MAIN.postDelayed(this,700);
        }
    }};

    private static View statusBar(MainActivity a){
        LinearLayout bar=new LinearLayout(a);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(a,12),0,dp(a,12),0);bar.setBackgroundColor(Color.rgb(3,9,14));
        TextView brand=text(a,"MUSICROAD  ·  DRIVE OS",10,ACCENT,true);bar.addView(brand,new LinearLayout.LayoutParams(0,-1,1));
        TextView mode=pill(a,"ANDROID NATIVO",GREEN);bar.addView(mode,new LinearLayout.LayoutParams(-2,dp(a,25)));
        TextView clock=text(a,new SimpleDateFormat("HH:mm",Locale.getDefault()).format(new Date()),12,TEXT,true);clock.setGravity(Gravity.CENTER);bar.addView(clock,new LinearLayout.LayoutParams(dp(a,62),-1));
        return bar;
    }

    private static View rail(MainActivity a,int selected){
        LinearLayout rail=new LinearLayout(a);rail.setOrientation(LinearLayout.VERTICAL);rail.setGravity(Gravity.CENTER_HORIZONTAL);rail.setPadding(dp(a,7),dp(a,10),dp(a,7),dp(a,10));rail.setBackgroundColor(Color.rgb(3,10,16));
        TextView mr=text(a,"MR",13,Color.rgb(28,15,7),true);mr.setGravity(Gravity.CENTER);mr.setBackground(round(a,ACCENT,17,0));rail.addView(mr,new LinearLayout.LayoutParams(dp(a,48),dp(a,48)));
        Space s=new Space(a);rail.addView(s,new LinearLayout.LayoutParams(1,dp(a,12)));
        addRail(a,rail,"⌂","Início",HOME,selected);addRail(a,rail,"♫","Música",MUSIC,selected);addRail(a,rail,"FM","Rádio",RADIO,selected);addRail(a,rail,"⇩","Offline",OFFLINE,selected);addRail(a,rail,"⚙","Ajustes",SETTINGS,selected);
        Space flex=new Space(a);rail.addView(flex,new LinearLayout.LayoutParams(1,0,1));
        TextView gps=text(a,"GPS",9,GREEN,true);gps.setGravity(Gravity.CENTER);rail.addView(gps,new LinearLayout.LayoutParams(-1,dp(a,28)));
        return rail;
    }

    private static void addRail(MainActivity a,LinearLayout rail,String icon,String label,int target,int selected){
        LinearLayout item=new LinearLayout(a);item.setOrientation(LinearLayout.VERTICAL);item.setGravity(Gravity.CENTER);item.setBackground(round(a,target==selected?Color.rgb(45,30,21):Color.TRANSPARENT,15,target==selected?ACCENT:0));
        item.addView(textCenter(a,icon,target==selected?19:17,target==selected?ACCENT:TEXT,true),new LinearLayout.LayoutParams(-1,dp(a,28)));item.addView(textCenter(a,label,8,target==selected?TEXT:MUTED,true),new LinearLayout.LayoutParams(-1,dp(a,18)));rail.addView(item,new LinearLayout.LayoutParams(-1,dp(a,58)));margin(item,0,2,0,2);item.setOnClickListener(v->navigate(a,target));
    }

    private static void home(MainActivity a,FrameLayout stage){
        LinearLayout shell=new LinearLayout(a);shell.setOrientation(LinearLayout.HORIZONTAL);stage.addView(shell,new FrameLayout.LayoutParams(-1,-1));shell.addView(rail(a,HOME),new LinearLayout.LayoutParams(dp(a,76),-1));
        LinearLayout body=new LinearLayout(a);body.setOrientation(LinearLayout.HORIZONTAL);body.setPadding(dp(a,14),dp(a,12),dp(a,14),dp(a,12));shell.addView(body,new LinearLayout.LayoutParams(0,-1,1));
        LinearLayout nav=panel(a);body.addView(nav,new LinearLayout.LayoutParams(0,-1,1.16f));
        LinearLayout h=new LinearLayout(a);h.setGravity(Gravity.CENTER_VERTICAL);h.addView(text(a,"NAVEGAÇÃO",10,ACCENT,true),new LinearLayout.LayoutParams(0,dp(a,28),1));h.addView(pill(a,route(a).length()>1?"VIAGEM ATIVA":"PRONTO",route(a).length()>1?GREEN:MUTED),new LinearLayout.LayoutParams(-2,dp(a,28)));nav.addView(h);
        TextView hero=text(a,route(a).length()>1?"Continue sua viagem":"Para onde vamos?",30,TEXT,true);nav.addView(hero);margin(hero,0,4,0,2);
        String current=stringField(a,"currentDestination","");TextView sub=text(a,route(a).length()>1&&!current.isEmpty()?current:"Mapa, velocidade e alertas em leitura instantânea.",12,MUTED,false);sub.setMaxLines(2);nav.addView(sub);margin(sub,0,0,0,13);
        EditText dest=edit(a,"Cidade, rua ou endereço");dest.setText(stringField(a,"pendingDestination",""));nav.addView(dest,new LinearLayout.LayoutParams(-1,dp(a,54)));
        HorizontalScrollView quick=destinationStrip(a,dest);nav.addView(quick,new LinearLayout.LayoutParams(-1,dp(a,48)));margin(quick,0,7,0,0);
        LinearLayout actions=new LinearLayout(a);Button start=button(a,route(a).length()>1?"ABRIR NAVEGAÇÃO":"INICIAR NAVEGAÇÃO",true),save=button(a,"☆ SALVAR",false);actions.addView(start,new LinearLayout.LayoutParams(0,dp(a,56),1));actions.addView(save,new LinearLayout.LayoutParams(dp(a,108),dp(a,56)));margin(save,8,0,0,0);nav.addView(actions);margin(actions,0,8,0,0);
        start.setOnClickListener(v->{String d=dest.getText().toString().trim();if(route(a).length()>1&&d.isEmpty()){navigate(a,MAP);return;}if(d.length()<2){toast(a,"Digite um destino.");return;}remember(a,d);set(a,"pendingDestination",d);navigate(a,MAP);});
        save.setOnClickListener(v->{String d=dest.getText().toString().trim();if(d.length()<2){toast(a,"Digite um destino para salvar.");return;}favorite(a,d);toast(a,"Destino favorito salvo.");installFresh(a);});
        Space space=new Space(a);nav.addView(space,new LinearLayout.LayoutParams(1,0,1));
        LinearLayout metrics=new LinearLayout(a);nav.addView(metrics,new LinearLayout.LayoutParams(-1,dp(a,88)));Location loc=location(a);metric(a,metrics,loc==null?"0":String.valueOf(Math.max(0,Math.round(loc.getSpeed()*3.6f))),"KM/H",TEXT);double dist=doubleField(a,"currentRouteDistanceMeters",DriveRouteState.distanceMeters());metric(a,metrics,dist>0?DriveGuidance.distanceLabel(dist):"--","VIAGEM",dist>0?GREEN:TEXT);DriveGuidance.Hazard hz=DriveGuidance.nearestHazard(hazards(a),loc==null?Double.NaN:loc.getLatitude(),loc==null?Double.NaN:loc.getLongitude());metric(a,metrics,hz==null?"--":DriveGuidance.distanceLabel(hz.distanceMeters),"ALERTA",hz==null?TEXT:ACCENT);

        LinearLayout right=new LinearLayout(a);right.setOrientation(LinearLayout.VERTICAL);right.setPadding(dp(a,12),0,0,0);body.addView(right,new LinearLayout.LayoutParams(0,-1,.84f));
        LinearLayout media=panel(a);right.addView(media,new LinearLayout.LayoutParams(-1,0,1));media.addView(text(a,"MÍDIA",10,PURPLE,true));LinearLayout mediaBody=new LinearLayout(a);mediaBody.setGravity(Gravity.CENTER_VERTICAL);media.addView(mediaBody,new LinearLayout.LayoutParams(-1,0,1));FrameLayout art=artwork(a,86);mediaBody.addView(art,new LinearLayout.LayoutParams(dp(a,86),dp(a,86)));LinearLayout mt=new LinearLayout(a);mt.setOrientation(LinearLayout.VERTICAL);mt.setPadding(dp(a,13),0,0,0);TextView title=text(a,"Nenhuma música",19,TEXT,true),artist=text(a,"MusicRoad",11,MUTED,false);title.setMaxLines(1);artist.setMaxLines(1);mt.addView(title);mt.addView(artist);mediaBody.addView(mt,new LinearLayout.LayoutParams(0,-1,1));MediaBinding binding=new MediaBinding(title,artist,(ImageView)art.findViewWithTag("art"));mediaBinding=new WeakReference<>(binding);updateMedia(binding);LinearLayout controls=new LinearLayout(a);Button prev=iconButton(a,"⏮"),play=iconButton(a,"▶"),next=iconButton(a,"⏭");controls.addView(prev,new LinearLayout.LayoutParams(0,dp(a,54),1));controls.addView(play,new LinearLayout.LayoutParams(0,dp(a,54),1));controls.addView(next,new LinearLayout.LayoutParams(0,dp(a,54),1));margin(play,7,0,7,0);media.addView(controls);wireMedia(a,prev,play,next);media.setOnClickListener(v->navigate(a,MUSIC));
        LinearLayout tiles=new LinearLayout(a);right.addView(tiles,new LinearLayout.LayoutParams(-1,dp(a,92)));margin(tiles,0,10,0,0);Button radio=tile(a,"FM  RÁDIO","Estações grandes",PURPLE),off=tile(a,"⇩  OFFLINE","Mapas no aparelho",GREEN);tiles.addView(radio,new LinearLayout.LayoutParams(0,-1,1));tiles.addView(off,new LinearLayout.LayoutParams(0,-1,1));margin(off,8,0,0,0);radio.setOnClickListener(v->navigate(a,RADIO));off.setOnClickListener(v->navigate(a,OFFLINE));
        Button settings=button(a,"⚙  SISTEMA, VOZ E ALERTAS",false);right.addView(settings,new LinearLayout.LayoutParams(-1,dp(a,58)));margin(settings,0,10,0,0);settings.setOnClickListener(v->navigate(a,SETTINGS));requestPlayerState(a);
    }

    private static void map(MainActivity a,FrameLayout stage){
        call(a,"locate",new Class<?>[0]);
        LinearLayout drive=new LinearLayout(a);drive.setOrientation(LinearLayout.HORIZONTAL);stage.addView(drive,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout cockpit=new LinearLayout(a);cockpit.setOrientation(LinearLayout.VERTICAL);cockpit.setPadding(dp(a,14),dp(a,10),dp(a,14),dp(a,10));cockpit.setBackgroundColor(Color.rgb(3,9,14));drive.addView(cockpit,new LinearLayout.LayoutParams(dp(a,318),-1));
        LinearLayout brand=new LinearLayout(a);brand.setGravity(Gravity.CENTER_VERTICAL);brand.addView(text(a,"MUSICROAD DRIVE",10,ACCENT,true),new LinearLayout.LayoutParams(0,dp(a,26),1));brand.addView(pill(a,route(a).length()>1?"EM ROTA":"MAPA",route(a).length()>1?GREEN:PURPLE),new LinearLayout.LayoutParams(-2,dp(a,26)));cockpit.addView(brand);
        TextView destination=text(a,stringField(a,"currentDestination","").isEmpty()?"Escolha um destino":stringField(a,"currentDestination",""),10,MUTED,false);destination.setMaxLines(1);cockpit.addView(destination);margin(destination,0,0,0,7);

        LinearLayout maneuver=panel(a);maneuver.setPadding(dp(a,11),dp(a,8),dp(a,11),dp(a,8));cockpit.addView(maneuver,new LinearLayout.LayoutParams(-1,dp(a,116)));LinearLayout top=new LinearLayout(a);top.setGravity(Gravity.CENTER_VERTICAL);TextView arrow=textCenter(a,"↑",33,Color.WHITE,true);arrow.setBackground(round(a,Color.rgb(39,29,19),16,ACCENT));top.addView(arrow,new LinearLayout.LayoutParams(dp(a,60),dp(a,60)));LinearLayout md=new LinearLayout(a);md.setOrientation(LinearLayout.VERTICAL);md.setPadding(dp(a,10),0,0,0);TextView turnDistance=text(a,"--",25,ACCENT,true);md.addView(turnDistance);md.addView(text(a,"PRÓXIMA MANOBRA",8,MUTED,true));top.addView(md,new LinearLayout.LayoutParams(0,-1,1));maneuver.addView(top,new LinearLayout.LayoutParams(-1,dp(a,62)));TextView turn=text(a,route(a).length()>1?"Siga a rota destacada":"Aguardando rota",13,TEXT,true);turn.setMaxLines(2);maneuver.addView(turn,new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout speedRow=new LinearLayout(a);speedRow.setGravity(Gravity.CENTER_VERTICAL);cockpit.addView(speedRow,new LinearLayout.LayoutParams(-1,dp(a,104)));margin(speedRow,0,8,0,0);LinearLayout speedCard=roundPanel(a);TextView speed=textCenter(a,"0",38,TEXT,true);speedCard.addView(speed,new LinearLayout.LayoutParams(-1,0,1));speedCard.addView(textCenter(a,"km/h",9,MUTED,true),new LinearLayout.LayoutParams(-1,dp(a,20)));speedRow.addView(speedCard,new LinearLayout.LayoutParams(dp(a,94),dp(a,94)));LinearLayout limitCard=new LinearLayout(a);limitCard.setOrientation(LinearLayout.VERTICAL);limitCard.setGravity(Gravity.CENTER);limitCard.setBackground(round(a,Color.WHITE,47,RED));TextView limit=textCenter(a,"--",27,Color.rgb(20,25,29),true);limitCard.addView(limit,new LinearLayout.LayoutParams(-1,0,1));limitCard.addView(textCenter(a,"LIMITE",8,Color.rgb(55,55,55),true),new LinearLayout.LayoutParams(-1,dp(a,19)));speedRow.addView(limitCard,new LinearLayout.LayoutParams(dp(a,86),dp(a,86)));margin(limitCard,8,0,0,0);LinearLayout speedCaption=new LinearLayout(a);speedCaption.setOrientation(LinearLayout.VERTICAL);speedCaption.setPadding(dp(a,9),0,0,0);speedCaption.setGravity(Gravity.CENTER_VERTICAL);speedCaption.addView(text(a,"VELOCIDADE",9,MUTED,true));speedCaption.addView(text(a,"Limite da rota",9,Color.rgb(105,126,143),false));speedRow.addView(speedCaption,new LinearLayout.LayoutParams(0,-1,1));

        LinearLayout trip=panel(a);trip.setOrientation(LinearLayout.HORIZONTAL);trip.setPadding(dp(a,6),dp(a,5),dp(a,6),dp(a,5));cockpit.addView(trip,new LinearLayout.LayoutParams(-1,dp(a,62)));margin(trip,0,7,0,0);TextView eta=metric(a,trip,"--:--","ETA",TEXT),remaining=metric(a,trip,"--","RESTANTE",TEXT);

        LinearLayout hazard=panel(a);hazard.setPadding(dp(a,11),dp(a,7),dp(a,11),dp(a,7));cockpit.addView(hazard,new LinearLayout.LayoutParams(-1,dp(a,88)));margin(hazard,0,7,0,0);LinearLayout hh=new LinearLayout(a);hh.setGravity(Gravity.CENTER_VERTICAL);TextView hazardTitle=text(a,"Nenhum alerta próximo",11,TEXT,true);hh.addView(hazardTitle,new LinearLayout.LayoutParams(0,dp(a,23),1));hh.addView(text(a,"⚠",18,ACCENT,true),new LinearLayout.LayoutParams(dp(a,26),dp(a,23)));hazard.addView(hh);LinearLayout hd=new LinearLayout(a);hd.setGravity(Gravity.CENTER_VERTICAL);TextView hazardDistance=text(a,"--",24,ACCENT,true);hd.addView(hazardDistance,new LinearLayout.LayoutParams(0,dp(a,34),1));TextView hazardMeta=text(a,"Radares e quebra-molas",8,MUTED,false);hazardMeta.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);hd.addView(hazardMeta,new LinearLayout.LayoutParams(-2,dp(a,34)));hazard.addView(hd);

        Space flexible=new Space(a);cockpit.addView(flexible,new LinearLayout.LayoutParams(1,0,1));LinearLayout mini=panel(a);mini.setOrientation(LinearLayout.HORIZONTAL);mini.setGravity(Gravity.CENTER_VERTICAL);mini.setPadding(dp(a,9),dp(a,5),dp(a,9),dp(a,5));cockpit.addView(mini,new LinearLayout.LayoutParams(-1,dp(a,64)));TextView note=textCenter(a,"♫",22,PURPLE,true);mini.addView(note,new LinearLayout.LayoutParams(dp(a,38),-1));LinearLayout miniText=new LinearLayout(a);miniText.setOrientation(LinearLayout.VERTICAL);TextView playerTitle=text(a,"Nenhuma música",11,TEXT,true),playerArtist=text(a,"MusicRoad",8,MUTED,false);playerTitle.setMaxLines(1);playerArtist.setMaxLines(1);miniText.addView(playerTitle);miniText.addView(playerArtist);mini.addView(miniText,new LinearLayout.LayoutParams(0,-1,1));Button toggle=iconButton(a,"▶");mini.addView(toggle,new LinearLayout.LayoutParams(dp(a,46),dp(a,46)));toggle.setOnClickListener(v->a.startService(PlaybackService.intentAction(a,PlaybackService.ACTION_TOGGLE)));MediaBinding mb=new MediaBinding(playerTitle,playerArtist,null);mediaBinding=new WeakReference<>(mb);updateMedia(mb);
        LinearLayout lower=new LinearLayout(a);Button home=button(a,"⌂ INÍCIO",false),stop=button(a,"■ ENCERRAR",false);lower.addView(home,new LinearLayout.LayoutParams(0,dp(a,44),1));lower.addView(stop,new LinearLayout.LayoutParams(0,dp(a,44),1));margin(stop,7,0,0,0);cockpit.addView(lower);margin(lower,0,7,0,0);home.setOnClickListener(v->navigate(a,HOME));

        FrameLayout mapStage=new FrameLayout(a);drive.addView(mapStage,new LinearLayout.LayoutParams(0,-1,1));NativeMapView map=new NativeMapView(a);mapStage.addView(map,new FrameLayout.LayoutParams(-1,-1));set(a,"mapView",map);call(a,"loadMap",new Class<?>[0]);call(a,"loadRoute",new Class<?>[0]);JSONArray coords=route(a);JSONArray hz=hazards(a);map.setRoute(coords);map.setRadars(hz);Location loc=location(a);if(loc!=null){map.setUserBearing(loc.hasBearing()?loc.getBearing():0f);map.setUserLocation(loc.getLatitude(),loc.getLongitude());}if(coords.length()>1)map.setDrivingMode(true);

        LinearLayout search=new LinearLayout(a);search.setGravity(Gravity.CENTER_VERTICAL);search.setPadding(dp(a,7),dp(a,6),dp(a,7),dp(a,6));search.setBackground(round(a,Color.argb(239,4,11,17),17,LINE));EditText dest=edit(a,"Destino · cidade, rua ou endereço");dest.setText(stringField(a,"pendingDestination",""));search.addView(dest,new LinearLayout.LayoutParams(0,dp(a,46),1));Button routeButton=button(a,coords.length()>1?"↻":"IR",true);search.addView(routeButton,new LinearLayout.LayoutParams(dp(a,62),dp(a,46)));margin(routeButton,7,0,0,0);set(a,"activeRouteButton",routeButton);FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(Math.min(dp(a,520),Math.max(dp(a,390),(int)(a.getResources().getDisplayMetrics().widthPixels*.42f))),dp(a,58));sp.gravity=Gravity.TOP|Gravity.START;sp.setMargins(dp(a,13),dp(a,12),0,0);mapStage.addView(search,sp);
        LinearLayout tools=new LinearLayout(a);tools.setOrientation(LinearLayout.VERTICAL);Button follow=iconButton(a,"➤"),report=iconButton(a,"⚠");tools.addView(follow,new LinearLayout.LayoutParams(dp(a,56),dp(a,56)));tools.addView(report,new LinearLayout.LayoutParams(dp(a,56),dp(a,56)));margin(report,0,8,0,0);FrameLayout.LayoutParams tp=new FrameLayout.LayoutParams(dp(a,58),-2);tp.gravity=Gravity.BOTTOM|Gravity.END;tp.setMargins(0,0,dp(a,13),dp(a,14));mapStage.addView(tools,tp);follow.setOnClickListener(v->{Location l=location(a);if(l!=null){map.setDrivingMode(true);map.recenter(l.getLatitude(),l.getLongitude());biasCamera(map,l);}});report.setOnClickListener(v->call(a,"reportPointDialog",new Class<?>[0]));
        TextView hiddenStatus=text(a,"",1,Color.TRANSPARENT,false);hiddenStatus.setVisibility(View.GONE);mapStage.addView(hiddenStatus,new FrameLayout.LayoutParams(1,1));routeButton.setOnClickListener(v->startRoute(a,dest.getText().toString(),routeButton,hiddenStatus,mapStage));stop.setOnClickListener(v->stopRoute(a,map));

        set(a,"hudSpeed",speed);set(a,"hudLimit",limit);set(a,"hudRadar",hazardDistance);set(a,"turnInstruction",turn);set(a,"landscapeTripInfo",remaining);set(a,"playerNow",playerTitle);
        CockpitBinding binding=new CockpitBinding(map,arrow,turnDistance,turn,speed,limit,eta,remaining,hazardTitle,hazardDistance,hazardMeta);updateCockpit(a,binding,new int[]{0});
        String pending=stringField(a,"pendingDestination","").trim();if(!pending.isEmpty()&&route(a).length()<2)MAIN.postDelayed(()->{if(location(a)!=null&&route(a).length()<2)startRoute(a,pending,routeButton,hiddenStatus,mapStage);},450);requestPlayerState(a);
    }

    private static void updateCockpit(MainActivity a,CockpitBinding b,int[] step){
        FrameLayout root=(FrameLayout)get(a,"root");if(root==null||root.findViewWithTag(TAG)==null||intField(a,"screen",HOME)!=MAP)return;
        Location loc=location(a);JSONArray coords=route(a),steps=DriveRouteState.steps(),maxspeeds=DriveRouteState.maxspeeds();
        if(loc!=null){
            b.speed.setText(String.valueOf(Math.max(0,Math.round(loc.getSpeed()*3.6f))));int limit=DriveGuidance.speedLimitKph(coords,maxspeeds,loc.getLatitude(),loc.getLongitude());b.limit.setText(limit>0?String.valueOf(limit):"--");
            if(coords.length()>1){DriveGuidance.Maneuver m=DriveGuidance.maneuver(steps,step[0],loc.getLatitude(),loc.getLongitude());step[0]=m.index;b.arrow.setText(m.arrow);b.turnDistance.setText(DriveGuidance.distanceLabel(m.distanceMeters));b.turn.setText(m.instruction);double rem=DriveGuidance.remainingMeters(coords,loc.getLatitude(),loc.getLongitude());b.remaining.setText(DriveGuidance.distanceLabel(rem));double total=DriveRouteState.distanceMeters()>0?DriveRouteState.distanceMeters():doubleField(a,"currentRouteDistanceMeters",0d),duration=DriveRouteState.durationSeconds()>0?DriveRouteState.durationSeconds():doubleField(a,"currentRouteDurationSeconds",0d);double seconds=duration;if(total>0&&rem>0)seconds=duration*Math.min(1d,rem/total);b.eta.setText(new SimpleDateFormat("HH:mm",Locale.getDefault()).format(new Date(System.currentTimeMillis()+Math.max(0L,Math.round(seconds*1000d)))));}
            DriveGuidance.Hazard h=DriveGuidance.nearestHazard(hazards(a),loc.getLatitude(),loc.getLongitude());b.hazardTitle.setText(h==null?"Nenhum alerta próximo":h.label);b.hazardDistance.setText(h==null?"--":DriveGuidance.distanceLabel(h.distanceMeters));b.hazardDistance.setTextColor(h!=null&&h.distanceMeters<220?RED:ACCENT);b.hazardMeta.setText(h==null?"Radares e quebra-molas":(h.speedKph>0?h.speedKph+" km/h":"Alerta MusicRoad"));if(coords.length()>1){b.map.setDrivingMode(true);biasCamera(b.map,loc);}
        }
        MAIN.postDelayed(()->updateCockpit(a,b,step),650);
    }

    private static void music(MainActivity a,FrameLayout stage){
        LinearLayout shell=new LinearLayout(a);shell.setOrientation(LinearLayout.HORIZONTAL);stage.addView(shell,new FrameLayout.LayoutParams(-1,-1));shell.addView(rail(a,MUSIC),new LinearLayout.LayoutParams(dp(a,76),-1));LinearLayout body=new LinearLayout(a);body.setOrientation(LinearLayout.HORIZONTAL);body.setPadding(dp(a,14),dp(a,12),dp(a,14),dp(a,12));shell.addView(body,new LinearLayout.LayoutParams(0,-1,1));
        LinearLayout player=panel(a);player.setPadding(dp(a,18),dp(a,14),dp(a,18),dp(a,14));body.addView(player,new LinearLayout.LayoutParams(dp(a,360),-1));LinearLayout ph=new LinearLayout(a);ph.setGravity(Gravity.CENTER_VERTICAL);ph.addView(text(a,"MUSICROAD PLAYER",10,PURPLE,true),new LinearLayout.LayoutParams(0,dp(a,26),1));ph.addView(pill(a,"DRIVE MEDIA",PURPLE),new LinearLayout.LayoutParams(-2,dp(a,26)));player.addView(ph);FrameLayout art=artwork(a,220);player.addView(art,new LinearLayout.LayoutParams(-1,0,1));margin(art,0,8,0,8);TextView title=text(a,"Nenhuma música",20,TEXT,true),artist=text(a,"MusicRoad",12,MUTED,false);title.setMaxLines(1);artist.setMaxLines(1);player.addView(title);player.addView(artist);margin(artist,0,2,0,9);LinearLayout controls=new LinearLayout(a);Button prev=iconButton(a,"⏮"),play=iconButton(a,"▶"),next=iconButton(a,"⏭");play.setTextSize(26);play.setBackground(round(a,Color.rgb(55,30,82),19,PURPLE));controls.addView(prev,new LinearLayout.LayoutParams(0,dp(a,62),1));controls.addView(play,new LinearLayout.LayoutParams(0,dp(a,62),1));controls.addView(next,new LinearLayout.LayoutParams(0,dp(a,62),1));margin(play,8,0,8,0);player.addView(controls);wireMedia(a,prev,play,next);LinearLayout shortcuts=new LinearLayout(a);Button radio=button(a,"FM / RÁDIO",false),off=button(a,"OFFLINE",false);shortcuts.addView(radio,new LinearLayout.LayoutParams(0,dp(a,47),1));shortcuts.addView(off,new LinearLayout.LayoutParams(0,dp(a,47),1));margin(off,7,0,0,0);player.addView(shortcuts);margin(shortcuts,0,8,0,0);radio.setOnClickListener(v->navigate(a,RADIO));off.setOnClickListener(v->navigate(a,OFFLINE));
        MediaBinding mb=new MediaBinding(title,artist,(ImageView)art.findViewWithTag("art"));mediaBinding=new WeakReference<>(mb);updateMedia(mb);set(a,"playerNow",title);
        FrameLayout library=new FrameLayout(a);body.addView(library,new LinearLayout.LayoutParams(0,-1,1));margin(library,12,0,0,0);renderExistingPane(a,library,"music");requestPlayerState(a);
    }

    private static void utility(MainActivity a,FrameLayout stage,int screen){
        LinearLayout shell=new LinearLayout(a);shell.setOrientation(LinearLayout.HORIZONTAL);stage.addView(shell,new FrameLayout.LayoutParams(-1,-1));shell.addView(rail(a,screen),new LinearLayout.LayoutParams(dp(a,76),-1));FrameLayout pane=new FrameLayout(a);shell.addView(pane,new LinearLayout.LayoutParams(0,-1,1));renderExistingPane(a,pane,screen==OFFLINE?"offline":screen==RADIO?"radio":"settings");
    }

    private static void renderExistingPane(MainActivity a,FrameLayout pane,String method){
        Object old=get(a,"content");Object oldLandscape=get(a,"landscapePaneRender");set(a,"content",pane);set(a,"landscapePaneRender",true);call(a,method,new Class<?>[0]);set(a,"landscapePaneRender",oldLandscape instanceof Boolean?oldLandscape:false);set(a,"content",old);
    }

    private static HorizontalScrollView destinationStrip(MainActivity a,EditText dest){
        HorizontalScrollView scroll=new HorizontalScrollView(a);scroll.setHorizontalScrollBarEnabled(false);LinearLayout row=new LinearLayout(a);row.setGravity(Gravity.CENTER_VERTICAL);scroll.addView(row,new HorizontalScrollView.LayoutParams(-2,-1));List<String> fav=list(a,FAVORITES),recent=list(a,RECENTS);for(String d:fav)addDestinationChip(a,row,"★ "+shortDest(d),d,dest);for(String d:recent)if(!fav.contains(d))addDestinationChip(a,row,"↺ "+shortDest(d),d,dest);if(row.getChildCount()==0){TextView empty=text(a,"Recentes e favoritos aparecerão aqui",10,MUTED,false);row.addView(empty,new LinearLayout.LayoutParams(-2,-1));}return scroll;
    }
    private static void addDestinationChip(MainActivity a,LinearLayout row,String label,String value,EditText dest){Button b=button(a,label,false);b.setTextSize(10);row.addView(b,new LinearLayout.LayoutParams(-2,dp(a,40)));if(row.getChildCount()>1)margin(b,7,0,0,0);b.setOnClickListener(v->dest.setText(value));}
    private static String shortDest(String x){return x==null?"Destino":x.length()>24?x.substring(0,23)+"…":x;}
    private static SharedPreferences prefs(MainActivity a){return a.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    private static List<String> list(MainActivity a,String key){ArrayList<String> out=new ArrayList<>();try{JSONArray j=new JSONArray(prefs(a).getString(key,"[]"));for(int i=0;i<j.length();i++){String v=j.optString(i,"").trim();if(!v.isEmpty()&&!out.contains(v))out.add(v);}}catch(Exception ignored){}return out;}
    private static void remember(MainActivity a,String d){store(a,RECENTS,d);}
    private static void favorite(MainActivity a,String d){store(a,FAVORITES,d);}
    private static void store(MainActivity a,String key,String d){if(d==null||d.trim().isEmpty())return;List<String> values=list(a,key);values.remove(d);values.add(0,d);JSONArray arr=new JSONArray();for(int i=0;i<Math.min(7,values.size());i++)arr.put(values.get(i));prefs(a).edit().putString(key,arr.toString()).apply();}

    private static void startRoute(MainActivity a,String destination,Button button,TextView status,View panel){
        String d=destination==null?"":destination.trim();if(d.length()<2){toast(a,"Informe o destino.");return;}Location l=location(a);if(l==null){call(a,"locate",new Class<?>[0]);toast(a,"Aguardando GPS.");return;}remember(a,d);set(a,"pendingDestination",d);set(a,"activeRouteButton",button);call(a,"calc",new Class<?>[]{String.class,Button.class,TextView.class,View.class},d,button,status,panel);
    }

    private static void stopRoute(MainActivity a,NativeMapView map){
        try{a.startService(NavigationService.stopIntent(a));}catch(Exception ignored){}set(a,"currentRouteCoords",new JSONArray());set(a,"currentHazards",new JSONArray());set(a,"currentDestination","");set(a,"currentRouteDistanceMeters",0d);set(a,"currentRouteDurationSeconds",0d);set(a,"pendingDestination","");DriveRouteState.clear();Object o=get(a,"offline");if(o instanceof OfflineStore)((OfflineStore)o).saveRoute("{}");map.setDrivingMode(false);map.clearRoute();map.setRadars(new JSONArray());toast(a,"Navegação encerrada.");installFresh(a);
    }

    private static void navigate(MainActivity a,int target){call(a,"go",new Class<?>[]{int.class},target);MAIN.postDelayed(()->install(a),80);}
    private static void installFresh(MainActivity a){FrameLayout root=(FrameLayout)get(a,"root");if(root!=null){View tagged=root.findViewWithTag(TAG);if(tagged!=null)tagged.setTag(null);}install(a);}

    private static void biasCamera(NativeMapView map,Location l){
        if(map==null||l==null)return;try{Object raw=get(map,"mapboxMap");if(!(raw instanceof MapboxMap))return;CameraOptions.Builder builder=new CameraOptions.Builder().center(Point.fromLngLat(l.getLongitude(),l.getLatitude())).zoom(16.6).pitch(52.0).bearing(l.hasBearing()?l.getBearing():0d);try{Class<?> edge=Class.forName("com.mapbox.maps.EdgeInsets");Object insets=null;for(Constructor<?> c:edge.getConstructors())if(c.getParameterTypes().length==4){try{insets=c.newInstance((double)dp(map.getContext(),150),0d,(double)dp(map.getContext(),22),0d);break;}catch(Exception ignored){}}if(insets!=null)for(Method m:builder.getClass().getMethods())if(m.getName().equals("padding")&&m.getParameterTypes().length==1&&m.getParameterTypes()[0].isAssignableFrom(edge)){m.invoke(builder,insets);break;}}catch(Throwable ignored){}((MapboxMap)raw).setCamera(builder.build());}catch(Throwable ignored){}
    }

    private static void ensurePlayerReceiver(){
        if(appContext==null||playerReceiverRegistered)return;IntentFilter f=new IntentFilter(PlaybackService.ACTION_STATE);BroadcastReceiver receiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){try{playerState=new JSONObject(i.getStringExtra(PlaybackService.EXTRA_STATE_JSON));MediaBinding b=mediaBinding.get();if(b!=null)updateMedia(b);}catch(Exception ignored){}}};try{if(Build.VERSION.SDK_INT>=33)appContext.registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED);else appContext.registerReceiver(receiver,f);playerReceiverRegistered=true;}catch(Exception ignored){}
    }
    private static void requestPlayerState(Context c){ensurePlayerReceiver();try{c.startService(PlaybackService.intentAction(c,PlaybackService.ACTION_BROADCAST_STATE));}catch(Exception ignored){}}
    private static void updateMedia(MediaBinding b){if(b==null)return;JSONObject tr=playerState.optJSONObject("track");if(tr==null){b.title.setText("Nenhuma música");b.artist.setText("MusicRoad");if(b.art!=null)b.art.setImageDrawable(null);return;}b.title.setText(tr.optString("title","Música"));b.artist.setText(tr.optString("artist","MusicRoad"));if(b.art!=null){try{b.art.setImageDrawable(null);long album=tr.optLong("album_id",0);if(album>0)b.art.setImageURI(Uri.parse("content://media/external/audio/albumart/"+album));}catch(Exception ignored){}}}
    private static void wireMedia(Context c,Button prev,Button play,Button next){prev.setOnClickListener(v->c.startService(PlaybackService.intentAction(c,PlaybackService.ACTION_PREVIOUS)));play.setOnClickListener(v->c.startService(PlaybackService.intentAction(c,PlaybackService.ACTION_TOGGLE)));next.setOnClickListener(v->c.startService(PlaybackService.intentAction(c,PlaybackService.ACTION_NEXT)));}

    private static JSONArray route(MainActivity a){Object v=get(a,"currentRouteCoords");return v instanceof JSONArray?(JSONArray)v:new JSONArray();}
    private static JSONArray hazards(MainActivity a){Object v=get(a,"currentHazards");return v instanceof JSONArray?(JSONArray)v:new JSONArray();}
    private static Location location(MainActivity a){Object v=get(a,"lastLocation");return v instanceof Location?(Location)v:null;}

    private static Object get(Object target,String name){try{Field f=findField(target.getClass(),name);if(f==null)return null;f.setAccessible(true);return f.get(target);}catch(Exception e){return null;}}
    private static void set(Object target,String name,Object value){try{Field f=findField(target.getClass(),name);if(f==null)return;f.setAccessible(true);f.set(target,value);}catch(Exception ignored){}}
    private static Field findField(Class<?> type,String name){Class<?> c=type;while(c!=null){try{return c.getDeclaredField(name);}catch(Exception ignored){c=c.getSuperclass();}}return null;}
    private static Object call(Object target,String name,Class<?>[] signature,Object... args){try{Method m=target.getClass().getDeclaredMethod(name,signature);m.setAccessible(true);return m.invoke(target,args);}catch(Exception e){return null;}}
    private static int intField(Object target,String name,int fallback){Object o=get(target,name);return o instanceof Number?((Number)o).intValue():fallback;}
    private static double doubleField(Object target,String name,double fallback){Object o=get(target,name);return o instanceof Number?((Number)o).doubleValue():fallback;}
    private static String stringField(Object target,String name,String fallback){Object o=get(target,name);return o==null?fallback:String.valueOf(o);}

    private static int dp(Context c,float value){return Math.round(value*c.getResources().getDisplayMetrics().density);}
    private static GradientDrawable round(Context c,int color,int radius,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(c,radius));if(stroke!=0)d.setStroke(dp(c,1),stroke);return d;}
    private static TextView text(Context c,String value,float size,int color,boolean bold){TextView t=new TextView(c);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private static TextView textCenter(Context c,String value,float size,int color,boolean bold){TextView t=text(c,value,size,color,bold);t.setGravity(Gravity.CENTER);return t;}
    private static TextView pill(Context c,String value,int color){TextView t=textCenter(c,value,8,color,true);t.setPadding(dp(c,9),0,dp(c,9),0);t.setBackground(round(c,Color.argb(95,Color.red(color),Color.green(color),Color.blue(color)),13,color));return t;}
    private static Button button(Context c,String value,boolean primary){Button b=new Button(c);b.setText(value);b.setAllCaps(false);b.setTextSize(11);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setTextColor(primary?Color.rgb(27,14,5):TEXT);b.setBackground(round(c,primary?ACCENT:PANEL2,14,primary?0:LINE));b.setMinHeight(0);b.setMinWidth(0);b.setPadding(dp(c,9),0,dp(c,9),0);return b;}
    private static Button iconButton(Context c,String value){Button b=button(c,value,false);b.setTextSize(20);b.setBackground(round(c,Color.rgb(8,17,25),18,LINE));return b;}
    private static EditText edit(Context c,String hint){EditText e=new EditText(c);e.setHint(hint);e.setHintTextColor(Color.rgb(102,121,138));e.setTextColor(TEXT);e.setSingleLine(true);e.setTextSize(14);e.setPadding(dp(c,13),0,dp(c,13),0);e.setBackground(round(c,Color.rgb(5,12,18),13,LINE));return e;}
    private static LinearLayout panel(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(c,14),dp(c,12),dp(c,14),dp(c,12));l.setBackground(round(c,PANEL,18,LINE));return l;}
    private static LinearLayout roundPanel(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.VERTICAL);l.setGravity(Gravity.CENTER);l.setBackground(round(c,Color.rgb(8,17,25),20,LINE));return l;}
    private static TextView metric(Context c,LinearLayout row,String value,String label,int color){LinearLayout box=new LinearLayout(c);box.setOrientation(LinearLayout.VERTICAL);box.setGravity(Gravity.CENTER);TextView v=textCenter(c,value,20,color,true);box.addView(v);box.addView(textCenter(c,label,8,MUTED,true));row.addView(box,new LinearLayout.LayoutParams(0,-1,1));return v;}
    private static Button tile(Context c,String title,String sub,int color){Button b=button(c,title+"\n"+sub,false);b.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);b.setTextColor(color);b.setTextSize(11);return b;}
    private static FrameLayout artwork(Context c,int size){FrameLayout frame=new FrameLayout(c);frame.setBackground(round(c,Color.rgb(18,23,34),20,Color.rgb(65,49,89)));TextView fallback=textCenter(c,"♫",Math.max(42,size/3f),Color.rgb(214,164,255),true);frame.addView(fallback,new FrameLayout.LayoutParams(-1,-1));ImageView art=new ImageView(c);art.setTag("art");art.setScaleType(ImageView.ScaleType.CENTER_CROP);frame.addView(art,new FrameLayout.LayoutParams(-1,-1));return frame;}
    private static void margin(View v,int l,int t,int r,int b){ViewGroup.LayoutParams raw=v.getLayoutParams();ViewGroup.MarginLayoutParams p=raw instanceof ViewGroup.MarginLayoutParams?(ViewGroup.MarginLayoutParams)raw:new ViewGroup.MarginLayoutParams(-1,-2);p.setMargins(dp(v.getContext(),l),dp(v.getContext(),t),dp(v.getContext(),r),dp(v.getContext(),b));v.setLayoutParams(p);}
    private static void immersive(MainActivity a){try{a.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);}catch(Exception ignored){}}
    private static void toast(Context c,String value){Toast.makeText(c,value,Toast.LENGTH_SHORT).show();}

    private static final class MediaBinding {final TextView title,artist;final ImageView art;MediaBinding(TextView t,TextView a,ImageView i){title=t;artist=a;art=i;}}
    private static final class CockpitBinding {final NativeMapView map;final TextView arrow,turnDistance,turn,speed,limit,eta,remaining,hazardTitle,hazardDistance,hazardMeta;CockpitBinding(NativeMapView m,TextView a,TextView td,TextView t,TextView s,TextView l,TextView e,TextView r,TextView ht,TextView hd,TextView hm){map=m;arrow=a;turnDistance=td;turn=t;speed=s;limit=l;eta=e;remaining=r;hazardTitle=ht;hazardDistance=hd;hazardMeta=hm;}}
}

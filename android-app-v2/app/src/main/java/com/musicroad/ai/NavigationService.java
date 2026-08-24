package com.musicroad.ai;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class NavigationService extends Service implements LocationListener {
    public static final String ACTION_START="com.musicroad.ai.NAV_START";
    public static final String ACTION_UPDATE="com.musicroad.ai.NAV_UPDATE";
    public static final String ACTION_ROUTE_CHANGED="com.musicroad.ai.NAV_ROUTE_CHANGED";
    public static final String ACTION_STOP="com.musicroad.ai.NAV_STOP";
    private static final String EXTRA_ROUTE="route_json";
    private static final String EXTRA_HAZARDS="hazards_json";
    private static final String EXTRA_LIMITS="limits_json";
    private static final String EXTRA_DEST="destination";
    private static final String PREFS="musicroad_navigation_v1";
    public static final String ALERT_PREFS="musicroad_alert_prefs_v2";
    private static final String CH_TRIP="musicroad_trip";
    private static final String CH_ALERT="musicroad_road_alert_v2";
    private static final int NOTIF_TRIP=7101;
    private static final int NOTIF_ALERT=7102;
    private static final int[] ALERT_MILESTONES={300,200,100,50};
    private static final int[] BUMP_SEQUENCE_MILESTONES={300,200,100};
    private static final double BUMP_SEQUENCE_GAP_M=110.0;

    private final ArrayList<RoutePoint> route=new ArrayList<>();
    private final ArrayList<Hazard> hazards=new ArrayList<>();
    private final ArrayList<SpeedLimit> limits=new ArrayList<>();
    private final ArrayList<Maneuver> maneuvers=new ArrayList<>();
    private final Set<String> warnedMilestones=new HashSet<>();
    private final Set<String> warnedNow=new HashSet<>();
    private final Set<String> warnedBumpSequenceMilestones=new HashSet<>();
    private final Set<String> warnedBumpNear=new HashSet<>();
    private final Set<String> warnedManeuverPrepare=new HashSet<>();
    private final Set<String> warnedManeuverNow=new HashSet<>();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private LocationManager locationManager;
    private NotificationManager notificationManager;
    private String destination="Destino";
    private double lastProgressM=0;
    private int offRouteSamples=0;
    private long lastSpeedWarningAt=0;
    private long lastHazardVoiceAt=0;
    private double lastAlertLeadM=0;
    private boolean suspendedOffRoute=false;

    public static Intent startIntent(Context c,String routeJson,String hazardsJson,String limitsJson,String destination){
        Intent i=new Intent(c,NavigationService.class).setAction(ACTION_START);
        i.putExtra(EXTRA_ROUTE,routeJson);i.putExtra(EXTRA_HAZARDS,hazardsJson);i.putExtra(EXTRA_LIMITS,limitsJson);i.putExtra(EXTRA_DEST,destination);return i;
    }
    public static Intent updateIntent(Context c,String hazardsJson,String limitsJson){return new Intent(c,NavigationService.class).setAction(ACTION_UPDATE).putExtra(EXTRA_HAZARDS,hazardsJson).putExtra(EXTRA_LIMITS,limitsJson);}
    public static Intent routeChangedIntent(Context c,String routeJson,String hazardsJson,String destination){return new Intent(c,NavigationService.class).setAction(ACTION_ROUTE_CHANGED).putExtra(EXTRA_ROUTE,routeJson).putExtra(EXTRA_HAZARDS,hazardsJson).putExtra(EXTRA_DEST,destination);}
    public static Intent stopIntent(Context c){return new Intent(c,NavigationService.class).setAction(ACTION_STOP);}

    @Override public void onCreate(){super.onCreate();locationManager=(LocationManager)getSystemService(LOCATION_SERVICE);notificationManager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);createChannels();}

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String action=intent==null?null:intent.getAction();
        if(ACTION_STOP.equals(action)){stopNavigation();return START_NOT_STICKY;}
        if(ACTION_START.equals(action)){
            destination=safe(intent.getStringExtra(EXTRA_DEST),"Destino");
            parseRoute(intent.getStringExtra(EXTRA_ROUTE));parseHazards(intent.getStringExtra(EXTRA_HAZARDS));parseLimits(intent.getStringExtra(EXTRA_LIMITS));
            warnedMilestones.clear();warnedNow.clear();warnedBumpSequenceMilestones.clear();warnedBumpNear.clear();warnedManeuverPrepare.clear();warnedManeuverNow.clear();lastProgressM=0;offRouteSamples=0;suspendedOffRoute=false;persistState();
            startForeground(NOTIF_TRIP,tripNotification("GPS ativo · monitorando a rota"));startLocation();return START_STICKY;
        }
        if(ACTION_ROUTE_CHANGED.equals(action)){
            destination=safe(intent.getStringExtra(EXTRA_DEST),destination);
            parseRoute(intent.getStringExtra(EXTRA_ROUTE));parseHazards(intent.getStringExtra(EXTRA_HAZARDS));lastProgressM=0;offRouteSamples=0;suspendedOffRoute=false;persistState();updateTripNotification("Rota Mapbox recalculada · alertas reposicionados");return START_STICKY;
        }
        if(ACTION_UPDATE.equals(action)){
            parseHazards(intent.getStringExtra(EXTRA_HAZARDS));parseLimits(intent.getStringExtra(EXTRA_LIMITS));persistState();updateTripNotification("Fiscalização atualizada · "+hazards.size()+" pontos");return START_STICKY;
        }
        if(route.isEmpty()&&restoreState()){startForeground(NOTIF_TRIP,tripNotification("Rota offline restaurada"));startLocation();return START_STICKY;}
        if(!route.isEmpty()){startForeground(NOTIF_TRIP,tripNotification("GPS ativo · monitorando a rota"));startLocation();return START_STICKY;}
        stopSelf();return START_NOT_STICKY;
    }

    private void createChannels(){
        if(Build.VERSION.SDK_INT<26)return;
        NotificationChannel trip=new NotificationChannel(CH_TRIP,"Viagem MusicRoad",NotificationManager.IMPORTANCE_LOW);trip.setDescription("Mantém GPS e alertas de bordo ativos durante a viagem");trip.setSound(null,null);trip.enableVibration(false);
        NotificationChannel alert=new NotificationChannel(CH_ALERT,"Alertas MusicRoad",NotificationManager.IMPORTANCE_HIGH);alert.setDescription("Alertas temporários configuráveis de fiscalização, segurança e navegação");alert.setSound(null,null);alert.enableVibration(false);
        notificationManager.createNotificationChannel(trip);notificationManager.createNotificationChannel(alert);
    }

    private Notification.Builder builder(String channel){return Build.VERSION.SDK_INT>=26?new Notification.Builder(this,channel):new Notification.Builder(this);}
    private PendingIntent openAppIntent(){Intent i=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);int f=PendingIntent.FLAG_UPDATE_CURRENT;if(Build.VERSION.SDK_INT>=23)f|=PendingIntent.FLAG_IMMUTABLE;return PendingIntent.getActivity(this,91,i,f);}
    private Notification tripNotification(String text){Notification.Builder b=builder(CH_TRIP).setSmallIcon(R.drawable.ic_notification).setContentTitle("MusicRoad · viagem ativa").setContentText(text).setContentIntent(openAppIntent()).setOngoing(true).setCategory(Notification.CATEGORY_NAVIGATION).setOnlyAlertOnce(true);if(Build.VERSION.SDK_INT<26)b.setPriority(Notification.PRIORITY_LOW);return b.build();}
    private void updateTripNotification(String text){if(notificationManager!=null)notificationManager.notify(NOTIF_TRIP,tripNotification(text));}
    private SharedPreferences alertPrefs(){return getSharedPreferences(ALERT_PREFS,MODE_PRIVATE);}
    private boolean pref(String key,boolean def){return alertPrefs().getBoolean(key,def);}
    private boolean milestoneEnabled(int meters){return pref("m"+meters,true);}
    private boolean kindEnabled(Hazard h){return h!=null&&pref(h.kind(),true);}
    private void vibrateAlert(){if(!pref("vibrate",true))return;try{Vibrator v=(Vibrator)getSystemService(VIBRATOR_SERVICE);if(v==null||!v.hasVibrator())return;if(Build.VERSION.SDK_INT>=26)v.vibrate(VibrationEffect.createWaveform(new long[]{0,110,70,110},-1));else v.vibrate(new long[]{0,110,70,110},-1);}catch(Exception ignored){}}
    private void showTransient(String title,String text,long durationMs){vibrateAlert();if(!pref("system",true))return;Notification.Builder b=builder(CH_ALERT).setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(openAppIntent()).setAutoCancel(true).setCategory(Notification.CATEGORY_NAVIGATION).setOnlyAlertOnce(false);if(Build.VERSION.SDK_INT>=26)b.setTimeoutAfter(durationMs);else b.setPriority(Notification.PRIORITY_HIGH);notificationManager.notify(NOTIF_ALERT,b.build());handler.removeCallbacksAndMessages("road-alert");handler.postAtTime(()->notificationManager.cancel(NOTIF_ALERT),"road-alert",System.currentTimeMillis()+durationMs);}

    private void startLocation(){
        if(Build.VERSION.SDK_INT>=23&&checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED){showTransient("GPS necessário","Abra o MusicRoad e permita acesso à localização.",6000);stopNavigation();return;}
        try{locationManager.removeUpdates(this);}catch(Exception ignored){}
        try{if(locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000L,2.5f,this);}catch(Exception ignored){}
        try{if(locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,3500L,8f,this);}catch(Exception ignored){}
    }

    @Override public void onLocationChanged(Location loc){
        if(route.size()<2||loc==null)return;
        Projection p=project(loc.getLatitude(),loc.getLongitude());
        double threshold=Math.max(90.0,Math.max(0,loc.getAccuracy())*1.9);
        if(p.offRouteM>threshold)offRouteSamples++;else offRouteSamples=Math.max(0,offRouteSamples-1);
        if(offRouteSamples>=3){
            if(!suspendedOffRoute){suspendedOffRoute=true;showTransient("Rota alterada","Você saiu do trajeto. O MusicRoad vai recalcular quando a tela estiver ativa.",6500);speak("Atenção. Você saiu da rota. Recalculando trajeto.");}
            updateTripNotification("Fora da rota · aguardando recálculo");return;
        }
        if(suspendedOffRoute&&p.offRouteM<=threshold){suspendedOffRoute=false;updateTripNotification("Rota retomada · alertas ativos");}
        if(p.offRouteM<450&&p.routeM>=lastProgressM-260)lastProgressM=Math.max(lastProgressM,p.routeM);
        double progress=lastProgressM;
        lastAlertLeadM=predictiveLeadMeters(loc);
        int currentLimit=currentSpeedLimit(progress);
        Hazard next=nextHazard(progress,loc);
        if(next!=null&&kindEnabled(next)){if("bump".equals(next.kind()))handleBumpHazard(next,progress);else handleHazard(next,progress);}
        handleManeuver(progress);
        if(currentLimit>0&&loc.hasSpeed())handleSpeeding(Math.round(loc.getSpeed()*3.6f),currentLimit);
        String status;
        if(next==null)status="GPS ativo · sem fiscalização próxima";
        else{
            String label=next.label();
            if("bump".equals(next.kind())){ArrayList<Hazard> seq=bumpSequenceFor(next);if(seq.size()>1){int idx=bumpIndex(seq,next);label="Quebra-mola "+(idx+1)+"/"+seq.size();}}
            double shownDistance=Math.max(0,next.routeM-progress);status=label+" · "+(shownDistance<=25?"agora":formatDistance(shownDistance));
        }
        if(currentLimit>0)status+=" · limite "+currentLimit;updateTripNotification(status);
    }

    private Hazard nextHazard(double progress,Location loc){
        Hazard best=null;double bestD=Double.MAX_VALUE;float movement=loc.hasBearing()?loc.getBearing():routeBearingAt(progress);
        for(Hazard h:hazards){
            double along=h.routeM-progress;if(along<-12||along>5000)continue;if("bump".equals(h.kind())&&along<=0&&warnedNow.contains(h.key))continue;if(!"bump".equals(h.kind())&&along<10&&warnedNow.contains(h.key))continue;if(h.routeDistanceM>360)continue;
            float intended=Float.isNaN(h.heading)?h.routeBearing:h.heading;
            if(loc.hasSpeed()&&loc.getSpeed()>4f&&angleDiff(movement,intended)>100f)continue;
            if(along<bestD){bestD=along;best=h;}
        }
        return best;
    }

    private double predictiveLeadMeters(Location loc){
        if(loc==null)return 0;
        double speed=loc.hasSpeed()?Math.max(0.0,loc.getSpeed()):0.0;
        double accuracy=loc.hasAccuracy()?Math.max(0.0,loc.getAccuracy()):0.0;
        double ageSec=Math.max(0.0,Math.min(3.0,(System.currentTimeMillis()-loc.getTime())/1000.0));
        if(speed<1.2)return Math.min(8.0,accuracy*0.18);
        double lead=speed*(1.8+ageSec)+Math.min(16.0,accuracy*0.45);
        return Math.max(10.0,Math.min(55.0,lead));
    }
    private double predictedHazardDistance(Hazard h,double progress){return Math.max(0,h.routeM-(progress+lastAlertLeadM));}
    private double rawHazardDistance(Hazard h,double progress){return Math.max(0,h.routeM-progress);}
    private double frontThresholdM(){return 22.0+Math.min(14.0,lastAlertLeadM*0.38);}

    private int milestoneFor(double distance){if(distance<=50)return 50;if(distance<=100)return 100;if(distance<=200)return 200;if(distance<=300)return 300;return 0;}
    private void markPassedMilestones(String key,int milestone){for(int m:ALERT_MILESTONES)if(m>=milestone)warnedMilestones.add(key+"@"+m);}
    private void handleHazard(Hazard h,double progress){
        if(!kindEnabled(h))return;double rawDistance=rawHazardDistance(h,progress);double distance=predictedHazardDistance(h,progress);String key=h.key;int milestone=milestoneFor(distance);
        if(milestone>0&&milestoneEnabled(milestone)){
            String mk=key+"@"+milestone;
            if(!warnedMilestones.contains(mk)){
                markPassedMilestones(key,milestone);
                String text=h.text(milestone);String voice=h.voice(milestone);
                showTransient(h.title(),text,milestone<=50?4200:5000);speak(voice);lastHazardVoiceAt=System.currentTimeMillis();
            }
        }
        if(pref("front",true)&&rawDistance<=frontThresholdM()&&!warnedNow.contains(key)){
            warnedNow.add(key);String text=h.frontText();showTransient(h.frontTitle(),text,4600);speak(h.frontVoice());lastHazardVoiceAt=System.currentTimeMillis();
        }
    }

    private ArrayList<Hazard> bumpSequenceFor(Hazard target){
        ArrayList<Hazard> bumps=new ArrayList<>();
        for(Hazard h:hazards)if("bump".equals(h.kind())&&h.routeDistanceM<=360)bumps.add(h);
        bumps.sort((a,b)->Double.compare(a.routeM,b.routeM));
        int center=-1;
        for(int i=0;i<bumps.size();i++)if(bumps.get(i).key.equals(target.key)){center=i;break;}
        ArrayList<Hazard> seq=new ArrayList<>();if(center<0){seq.add(target);return seq;}
        int left=center,right=center;
        while(left>0&&bumps.get(left).routeM-bumps.get(left-1).routeM<=BUMP_SEQUENCE_GAP_M)left--;
        while(right+1<bumps.size()&&bumps.get(right+1).routeM-bumps.get(right).routeM<=BUMP_SEQUENCE_GAP_M)right++;
        for(int i=left;i<=right;i++)seq.add(bumps.get(i));
        return seq;
    }
    private int bumpIndex(ArrayList<Hazard> seq,Hazard h){for(int i=0;i<seq.size();i++)if(seq.get(i).key.equals(h.key))return i;return 0;}
    private String bumpSequenceKey(ArrayList<Hazard> seq){if(seq.isEmpty())return"bump-seq";return"bump-seq:"+seq.get(0).key+":"+seq.get(seq.size()-1).key;}
    private void markBumpSequenceMilestones(String key,int milestone){for(int m:BUMP_SEQUENCE_MILESTONES)if(m>=milestone)warnedBumpSequenceMilestones.add(key+"@"+m);}
    private void handleBumpHazard(Hazard h,double progress){
        ArrayList<Hazard> seq=bumpSequenceFor(h);if(seq.size()<2){handleHazard(h,progress);return;}
        double rawDistance=rawHazardDistance(h,progress);double distance=predictedHazardDistance(h,progress);int idx=bumpIndex(seq,h),total=seq.size(),remaining=Math.max(1,total-idx);String seqKey=bumpSequenceKey(seq);int milestone=milestoneFor(distance);
        if(milestone>=100&&milestoneEnabled(milestone)){
            String mk=seqKey+"@"+milestone;
            if(!warnedBumpSequenceMilestones.contains(mk)){
                markBumpSequenceMilestones(seqKey,milestone);
                String countText=remaining==total?"Sequência de "+total+" quebra-molas":remaining+" quebra-molas ainda na sequência";
                showTransient("SEQUÊNCIA DE QUEBRA-MOLAS",countText+" · próximo em "+milestone+" m",5000);
                speak("Atenção. "+countText+". Próximo quebra-mola em "+milestone+" metros.");lastHazardVoiceAt=System.currentTimeMillis();
            }
        }
        if(pref("front",true)&&rawDistance<=frontThresholdM()&&!warnedNow.contains(h.key)){
            warnedNow.add(h.key);boolean last=idx==total-1;
            String title=last?"ÚLTIMO QUEBRA-MOLA":"QUEBRA-MOLA "+(idx+1)+" DE "+total;
            String text=last?"Último da sequência · na sua frente":"Na sua frente · sequência de "+total;
            String voice=last?"Atenção. Último quebra-mola da sequência na sua frente.":"Atenção. Quebra-mola "+(idx+1)+" de "+total+" na sua frente.";
            showTransient(title,text,4600);speak(voice);lastHazardVoiceAt=System.currentTimeMillis();
        }
    }

    private Maneuver nextManeuver(double progress){for(Maneuver m:maneuvers)if(m.routeM>=progress-25)return m;return null;}
    private void handleManeuver(double progress){
        if(!pref("maneuvers",true))return;Maneuver m=nextManeuver(progress);if(m==null)return;double distance=m.routeM-progress;if(distance<-25||distance>380)return;String key=m.key;long now=System.currentTimeMillis();
        if(distance<=300&&distance>60&&!warnedManeuverPrepare.contains(key)){
            if(now-lastHazardVoiceAt<2600)return;
            warnedManeuverPrepare.add(key);int meters=distance>230?300:distance>150?200:100;String action=m.action();showTransient("PRÓXIMA MANOBRA","Em "+meters+" m · "+action,4200);speak("Em "+meters+" metros. "+action+".");
        }
        if(distance<=55&&distance>=-20&&!warnedManeuverNow.contains(key)){
            warnedManeuverNow.add(key);String action=m.action();showTransient(m.title(),action,4800);speak(m.nowVoice());
        }
    }

    private void handleSpeeding(long kmh,int limit){if(!pref("speeding",true))return;long now=System.currentTimeMillis();if(kmh<=limit+3||now-lastSpeedWarningAt<22000)return;lastSpeedWarningAt=now;showTransient("REDUZA A VELOCIDADE","Você está a "+kmh+" km/h · limite "+limit+" km/h",5200);speak("Atenção. Reduza a velocidade. Limite de "+limit+" quilômetros por hora.");}
    private void speak(String text){if(!pref("voice",true))return;try{Intent i=PlaybackService.intentAction(this,PlaybackService.ACTION_SPEAK);i.putExtra(PlaybackService.EXTRA_TEXT,text);startService(i);}catch(Exception ignored){}}
    private int currentSpeedLimit(double progress){SpeedLimit found=null;for(SpeedLimit s:limits){if(s.routeM>progress+80)break;if(progress-s.routeM<=6000)found=s;}return found==null?0:found.speed;}

    private void parseRoute(String json){route.clear();if(json==null)return;try{JSONArray a=new JSONArray(json);double total=0;RoutePoint prev=null;for(int i=0;i<a.length();i++){JSONArray c=a.optJSONArray(i);if(c==null||c.length()<2)continue;double lon=c.optDouble(0,Double.NaN),lat=c.optDouble(1,Double.NaN);if(Double.isNaN(lat)||Double.isNaN(lon))continue;RoutePoint rp=new RoutePoint(lat,lon,total);if(prev!=null){float[] d=new float[1];Location.distanceBetween(prev.lat,prev.lon,lat,lon,d);total+=d[0];rp.cumM=total;}route.add(rp);prev=rp;}}catch(Exception ignored){}}
    private void parseHazards(String json){hazards.clear();if(json==null)return;try{JSONArray a=new JSONArray(json);for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;double lat=o.optDouble("latitude",Double.NaN),lon=o.optDouble("longitude",Double.NaN);if(Double.isNaN(lat)||Double.isNaN(lon))continue;Projection p=project(lat,lon);double routeM=o.has("route_m")?o.optDouble("route_m",p.routeM):p.routeM;if(Double.isNaN(routeM))routeM=p.routeM;Hazard h=new Hazard();h.lat=lat;h.lon=lon;h.routeM=routeM;h.routeDistanceM=p.offRouteM;h.routeBearing=routeBearingAt(routeM);h.key=safe(o.optString("external_id",""),"p-"+i+"-"+lat+"-"+lon);h.type=o.optString("tipo","RADAR");h.source=o.optString("fonte","");h.speed=o.optInt("velocidade",0);h.heading=o.has("heading")?(float)o.optDouble("heading",Double.NaN):Float.NaN;hazards.add(h);}}catch(Exception ignored){}hazards.sort((a,b)->Double.compare(a.routeM,b.routeM));}
    private void parseLimits(String json){
        limits.clear();maneuvers.clear();if(json==null)return;
        try{JSONArray a=new JSONArray(json);for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;
            if("maneuver".equalsIgnoreCase(o.optString("nav_type",""))){
                double routeM=o.optDouble("route_m",Double.NaN);if(Double.isNaN(routeM))continue;Maneuver m=new Maneuver();m.routeM=routeM;m.key=safe(o.optString("key",""),"nav-"+i+"-"+routeM);m.type=o.optString("type","turn");m.modifier=o.optString("modifier","");m.name=o.optString("name","");m.destinations=o.optString("destinations","");m.exit=o.optInt("exit",0);maneuvers.add(m);continue;
            }
            int speed=o.optInt("velocidade",0);if(speed<10||speed>180)continue;double routeM=o.optDouble("route_m",Double.NaN);if(Double.isNaN(routeM)){double lat=o.optDouble("latitude",Double.NaN),lon=o.optDouble("longitude",Double.NaN);if(Double.isNaN(lat)||Double.isNaN(lon))continue;routeM=project(lat,lon).routeM;}limits.add(new SpeedLimit(routeM,speed));
        }}catch(Exception ignored){}limits.sort((a,b)->Double.compare(a.routeM,b.routeM));maneuvers.sort((a,b)->Double.compare(a.routeM,b.routeM));
    }

    private Projection project(double lat,double lon){Projection best=new Projection();best.offRouteM=Double.MAX_VALUE;best.routeM=0;if(route.size()<2)return best;double latScale=110540.0;double lonScale=111320.0*Math.max(.2,Math.cos(Math.toRadians(lat)));for(int i=1;i<route.size();i++){RoutePoint a=route.get(i-1),b=route.get(i);double ax=(a.lon-lon)*lonScale,ay=(a.lat-lat)*latScale,bx=(b.lon-lon)*lonScale,by=(b.lat-lat)*latScale,vx=bx-ax,vy=by-ay,den=vx*vx+vy*vy,t=den>0?-(ax*vx+ay*vy)/den:0;t=Math.max(0,Math.min(1,t));double x=ax+t*vx,y=ay+t*vy,d=Math.sqrt(x*x+y*y);if(d<best.offRouteM){best.offRouteM=d;best.routeM=a.cumM+(b.cumM-a.cumM)*t;}}return best;}
    private float routeBearingAt(double routeM){if(route.size()<2)return Float.NaN;int idx=0;for(int i=1;i<route.size();i++){if(route.get(i).cumM>=routeM){idx=i-1;break;}idx=i-1;}RoutePoint a=route.get(Math.max(0,Math.min(idx,route.size()-2))),b=route.get(Math.max(1,Math.min(idx+1,route.size()-1)));float[] res=new float[2];Location.distanceBetween(a.lat,a.lon,b.lat,b.lon,res);return res.length>1?res[1]:Float.NaN;}
    private static float angleDiff(float a,float b){if(Float.isNaN(a)||Float.isNaN(b))return 0;float d=Math.abs((a-b)%360f);return d>180f?360f-d:d;}
    private static String formatDistance(double m){return m>=1000?String.format(Locale.US,"%.1f km",m/1000.0):Math.max(0,(int)Math.round(m))+" m";}
    private static String safe(String s,String fallback){return s==null||s.trim().isEmpty()?fallback:s.trim();}

    private void persistState(){try{getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString("route",routeToJson()).putString("hazards",hazardsToJson()).putString("limits",limitsToJson()).putString("destination",destination).apply();}catch(Exception ignored){}}
    private boolean restoreState(){try{SharedPreferences p=getSharedPreferences(PREFS,MODE_PRIVATE);parseRoute(p.getString("route",null));parseHazards(p.getString("hazards","[]"));parseLimits(p.getString("limits","[]"));destination=p.getString("destination","Destino");return route.size()>1;}catch(Exception e){return false;}}
    private String routeToJson(){JSONArray a=new JSONArray();for(RoutePoint p:route){try{JSONArray c=new JSONArray();c.put(p.lon);c.put(p.lat);a.put(c);}catch(Exception ignored){}}return a.toString();}
    private String hazardsToJson(){JSONArray a=new JSONArray();for(Hazard h:hazards){JSONObject o=new JSONObject();try{o.put("external_id",h.key);o.put("latitude",h.lat);o.put("longitude",h.lon);o.put("route_m",h.routeM);o.put("velocidade",h.speed);o.put("tipo",h.type);o.put("fonte",h.source);o.put("alert_radius_m",300);if(!Float.isNaN(h.heading))o.put("heading",h.heading);}catch(Exception ignored){}a.put(o);}return a.toString();}
    private String limitsToJson(){JSONArray a=new JSONArray();for(SpeedLimit s:limits){JSONObject o=new JSONObject();try{o.put("route_m",s.routeM);o.put("velocidade",s.speed);}catch(Exception ignored){}a.put(o);}for(Maneuver m:maneuvers){JSONObject o=new JSONObject();try{o.put("nav_type","maneuver");o.put("route_m",m.routeM);o.put("key",m.key);o.put("type",m.type);o.put("modifier",m.modifier);o.put("name",m.name);o.put("destinations",m.destinations);o.put("exit",m.exit);}catch(Exception ignored){}a.put(o);}return a.toString();}

    private void stopNavigation(){try{if(locationManager!=null)locationManager.removeUpdates(this);}catch(Exception ignored){}handler.removeCallbacksAndMessages(null);if(notificationManager!=null)notificationManager.cancel(NOTIF_ALERT);getSharedPreferences(PREFS,MODE_PRIVATE).edit().clear().apply();if(Build.VERSION.SDK_INT>=24)stopForeground(STOP_FOREGROUND_REMOVE);else stopForeground(true);stopSelf();}
    @Override public void onProviderEnabled(String provider){}
    @Override public void onProviderDisabled(String provider){}
    @Override public void onStatusChanged(String provider,int status,Bundle extras){}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){try{if(locationManager!=null)locationManager.removeUpdates(this);}catch(Exception ignored){}handler.removeCallbacksAndMessages(null);super.onDestroy();}

    private static final class RoutePoint{double lat,lon,cumM;RoutePoint(double lat,double lon,double cumM){this.lat=lat;this.lon=lon;this.cumM=cumM;}}
    private static final class Projection{double offRouteM,routeM;}
    private static final class SpeedLimit{double routeM;int speed;SpeedLimit(double routeM,int speed){this.routeM=routeM;this.speed=speed;}}
    private static final class Maneuver{
        String key,type,modifier,name,destinations;double routeM;int exit;
        private String road(){String n=name==null?"":name.trim();return n.isEmpty()?"":" na "+n;}
        String action(){String t=type==null?"turn":type.toLowerCase(Locale.ROOT),m=modifier==null?"":modifier.toLowerCase(Locale.ROOT),r=road();
            if(t.equals("arrive"))return"Destino à frente";
            if(t.contains("roundabout")||t.contains("rotary"))return exit>0?"Na rotatória, pegue a saída número "+exit+r:"Entre na rotatória"+r;
            if(t.equals("off ramp")||t.equals("exit roundabout")||t.equals("exit rotary"))return"Pegue a saída"+(m.contains("left")?" à esquerda":m.contains("right")?" à direita":"")+r;
            if(t.equals("on ramp"))return"Pegue o acesso"+(m.contains("left")?" à esquerda":m.contains("right")?" à direita":"")+r;
            if(t.equals("fork"))return"Mantenha-se "+(m.contains("left")?"à esquerda":m.contains("right")?"à direita":"em frente")+r;
            if(t.equals("merge"))return"Entre "+(m.contains("left")?"à esquerda":m.contains("right")?"à direita":"na via")+r;
            if(m.equals("uturn"))return"Faça o retorno";if(m.contains("left"))return"Vire à esquerda"+r;if(m.contains("right"))return"Vire à direita"+r;return"Siga em frente"+r;
        }
        String title(){String a=action().toUpperCase(Locale.ROOT);if(a.startsWith("VIRE À DIREITA"))return"VIRE À DIREITA";if(a.startsWith("VIRE À ESQUERDA"))return"VIRE À ESQUERDA";if(a.startsWith("FAÇA O RETORNO"))return"FAÇA O RETORNO";if(a.startsWith("NA ROTATÓRIA"))return"ROTATÓRIA";if(a.startsWith("PEGUE A SAÍDA"))return"PEGUE A SAÍDA";if(a.startsWith("PEGUE O ACESSO"))return"PEGUE O ACESSO";if(a.startsWith("DESTINO"))return"CHEGANDO AO DESTINO";return"SIGA EM FRENTE";}
        String nowVoice(){String a=action();return type!=null&&type.equalsIgnoreCase("arrive")?"Você chegou ao destino.":a+" agora.";}
    }
    private static final class Hazard{
        String key,type,source;double lat,lon,routeM,routeDistanceM;int speed;float heading,routeBearing;
        String kind(){String t=type==null?"":type.toUpperCase(Locale.ROOT);if(t.contains("VIDEO")||t.contains("OCR")||t.contains("MONITOR"))return"video";if(t.contains("QUEBRA")||t.contains("LOMBADA")||t.contains("BUMP")||t.contains("HUMP")||t.contains("CALMING"))return"bump";if(t.contains("SEMAFOR")||t.contains("SEMÁFOR")||t.contains("SIGNAL")||t.contains("AVAN"))return"signal";if(t.contains("PORTAT")||t.contains("MOVEL")||t.contains("MÓVEL"))return"portable";return"radar";}
        boolean isSpeedEnforcement(){String k=kind();return k.equals("radar")||k.equals("portable");}
        String label(){String k=kind();if(k.equals("video"))return"Videomonitoramento";if(k.equals("bump"))return"Quebra-mola";if(k.equals("signal"))return type!=null&&type.toUpperCase(Locale.ROOT).contains("FISCALIZ")?"Fiscalização semafórica":"Semáforo";if(k.equals("portable"))return"Fiscalização portátil";return"Radar";}
        String title(){String k=kind();if(k.equals("video"))return"ÁREA MONITORADA";if(k.equals("bump"))return"QUEBRA-MOLA À FRENTE";if(k.equals("signal"))return label().toUpperCase(Locale.ROOT);if(k.equals("portable"))return"FISCALIZAÇÃO PORTÁTIL";return"RADAR À FRENTE";}
        String text(int meters){return label()+" · "+meters+" m"+(isSpeedEnforcement()&&speed>0?" · limite "+speed+" km/h":"");}
        String voice(int meters){if(isSpeedEnforcement()&&speed>0)return label()+" de "+speed+" quilômetros por hora em "+meters+" metros.";return "Atenção. "+label()+" em "+meters+" metros.";}
        String frontTitle(){return(label()+" NA SUA FRENTE").toUpperCase(Locale.ROOT);}
        String frontText(){return label()+" · agora"+(isSpeedEnforcement()&&speed>0?" · limite "+speed+" km/h":"");}
        String frontVoice(){if(isSpeedEnforcement()&&speed>0)return label()+" de "+speed+" quilômetros por hora na sua frente.";return "Atenção. "+label()+" na sua frente.";}
    }
}

#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "estrada-play-comunista-app"
JAVA = APP / "app/src/main/java/com/estradaplay/comunista"


def read(p): return p.read_text(encoding="utf-8")
def write(p, s): p.parent.mkdir(parents=True, exist_ok=True); p.write_text(s, encoding="utf-8")
def replace_once(s, old, new, label):
    if new in s: return s
    if old not in s: raise SystemExit(f"Trecho não encontrado: {label}")
    return s.replace(old, new, 1)

# ---------------------------------------------------------------------------
# App 1.6.1 Universal — Estrada Rádio (WebRTC P2P, signaling metadata only)
# ---------------------------------------------------------------------------
gradle = APP / "app/build.gradle"
s = read(gradle)
s = re.sub(r"versionCode\s+160\b", "versionCode 161", s, count=1)
s = re.sub(r"versionName\s+'1\.6\.0'", "versionName '1.6.1'", s, count=1)
if "io.github.webrtc-sdk:android:144.7559.09" not in s:
    s = s.replace("    implementation 'com.google.mlkit:text-recognition:16.0.1'\n", "    implementation 'com.google.mlkit:text-recognition:16.0.1'\n    implementation 'io.github.webrtc-sdk:android:144.7559.09'\n")
write(gradle, s)

manifest = APP / "app/src/main/AndroidManifest.xml"
s = read(manifest)
if "FOREGROUND_SERVICE_MICROPHONE" not in s:
    s = s.replace('    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />\n', '    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />\n    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />\n')
if '.RoadRadioActivity' not in s:
    marker = '        <activity android:name=".RoadFavoritesActivity" android:exported="false" android:screenOrientation="${appOrientation}" />\n'
    s = s.replace(marker, marker + '        <activity android:name=".RoadRadioActivity" android:exported="false" android:screenOrientation="${appOrientation}" />\n')
if '.RoadRadioService' not in s:
    marker = '''        <service\n            android:name=".RoadSafetyService"\n            android:exported="false"\n            android:stopWithTask="true"\n            android:foregroundServiceType="location" />\n'''
    addition = marker + '''\n        <service\n            android:name=".RoadRadioService"\n            android:exported="false"\n            android:stopWithTask="true"\n            android:foregroundServiceType="microphone" />\n'''
    s = replace_once(s, marker, addition, "RoadRadioService manifest")
write(manifest, s)

# Safety audio priority broadcast: radio silences itself while road voice speaks.
road_service = JAVA / "RoadSafetyService.java"
s = read(road_service)
if "ACTION_SAFETY_AUDIO" not in s:
    s = s.replace('    static final String ACTION_PREFETCH_CORE = "com.estradaplay.comunista.PREFETCH_CORE";\n', '    static final String ACTION_PREFETCH_CORE = "com.estradaplay.comunista.PREFETCH_CORE";\n    static final String ACTION_SAFETY_AUDIO = "com.estradaplay.comunista.SAFETY_AUDIO";\n')
    s = s.replace('''    private void beginVoiceDucking(int token) {\n        if (token <= 0 || token != activeVoiceToken) return;\n        duckOwnPlayer(true);\n''', '''    private void beginVoiceDucking(int token) {\n        if (token <= 0 || token != activeVoiceToken) return;\n        sendSafetyAudioState(true);\n        duckOwnPlayer(true);\n''')
    s = s.replace('''    private void forceRestoreAudio() {\n        duckOwnPlayer(false);\n''', '''    private void forceRestoreAudio() {\n        sendSafetyAudioState(false);\n        duckOwnPlayer(false);\n''')
    anchor = '''    private void requestVoiceFocus() {\n'''
    helper = '''    private void sendSafetyAudioState(boolean active) {\n        try {\n            Intent i = new Intent(ACTION_SAFETY_AUDIO).setPackage(getPackageName());\n            i.putExtra("active", active);\n            sendBroadcast(i);\n        } catch (Throwable ignored) {}\n    }\n\n'''
    s = s.replace(anchor, helper + anchor, 1)
write(road_service, s)

# Central Inteligente shortcut.
drive_tools = JAVA / "DriveToolsActivity.java"
s = read(drive_tools)
if 'RÁDIO DA RODOVIA' not in s:
    s = s.replace('button("CENTRAL OFFLINE RJ / MG / ES",OfflineCenterActivity.class);', 'button("RÁDIO DA RODOVIA · PTT",RoadRadioActivity.class);button("CENTRAL OFFLINE RJ / MG / ES",OfflineCenterActivity.class);')
write(drive_tools, s)

# Voice command shortcut.
voice_cmd = JAVA / "VoiceCommandActivity.java"
s = read(voice_cmd)
if 'RoadRadioActivity.class' not in s:
    s = s.replace('if(q.contains("câmera")||q.contains("camera")){open(CameraActivity.class);return;}', 'if(q.contains("rádio")||q.contains("radio")){open(RoadRadioActivity.class);return;}if(q.contains("câmera")||q.contains("camera")){open(CameraActivity.class);return;}')
    s = s.replace('‘definir destino’', '‘definir destino’, ‘rádio da estrada’')
write(voice_cmd, s)

write(JAVA / "RoadIdentityResolver.java", r'''package com.estradaplay.comunista;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves the current named highway using only the locally cached road geometry. */
final class RoadIdentityResolver {
    private static final Pattern ROAD = Pattern.compile("(?i)\\b(BR|RJ|MG|ES|SP)[-\\s]?(\\d{1,4})\\b");

    static final class Identity {
        final String road, uf, direction, segment, roomKey, label;
        Identity(String road, String uf, String direction, String segment) {
            this.road=road; this.uf=uf; this.direction=direction; this.segment=segment;
            String dirKey = direction.length() > 0 ? direction.substring(0,1) : "G";
            roomKey = (road.replace("-","")+"|"+uf+"|"+dirKey+"|"+segment).toUpperCase(Locale.ROOT);
            label = road+" · "+uf+" · "+direction;
        }
    }

    private RoadIdentityResolver() {}

    static Identity resolve(OfflineRoadStore store, double lat, double lon, float heading) {
        if (store == null || !Double.isFinite(lat) || !Double.isFinite(lon)) return null;
        try {
            JSONObject root = new JSONObject(store.combinedGeoJson(lat, lon));
            JSONArray features = root.optJSONArray("features");
            if (features == null) return null;
            double best = Double.MAX_VALUE;
            String bestRoad = "";
            int max = Math.min(features.length(), 12000);
            for (int i=0;i<max;i++) {
                JSONObject f=features.optJSONObject(i); if(f==null)continue;
                JSONObject props=f.optJSONObject("properties"); if(props==null)props=new JSONObject();
                String road=canonical(props.optString("ref",""));
                if(road.isEmpty()) road=canonical(props.optString("name",""));
                if(road.isEmpty()) continue;
                JSONObject g=f.optJSONObject("geometry"); if(g==null||!"LineString".equalsIgnoreCase(g.optString("type","")))continue;
                JSONArray c=g.optJSONArray("coordinates"); if(c==null||c.length()<2)continue;
                for(int j=1;j<c.length();j++){
                    JSONArray a=c.optJSONArray(j-1),b=c.optJSONArray(j); if(a==null||b==null||a.length()<2||b.length()<2)continue;
                    double d=segmentDistance(lat,lon,a.optDouble(1,Double.NaN),a.optDouble(0,Double.NaN),b.optDouble(1,Double.NaN),b.optDouble(0,Double.NaN));
                    if(d<best){best=d;bestRoad=road;}
                }
            }
            if(bestRoad.isEmpty()||best>95.0)return null;
            String uf=uf(lat,lon,bestRoad);
            String direction=direction(heading);
            String segment=segment(lat,lon);
            return new Identity(bestRoad,uf,direction,segment);
        } catch(Throwable ignored){ return null; }
    }

    private static String canonical(String raw){
        if(raw==null)return""; Matcher m=ROAD.matcher(raw.toUpperCase(Locale.ROOT));
        if(!m.find())return""; return m.group(1).toUpperCase(Locale.ROOT)+"-"+m.group(2);
    }
    private static String direction(float h){
        if(!Float.isFinite(h))return"GERAL"; float v=((h%360)+360)%360;
        if(v<45||v>=315)return"NORTE"; if(v<135)return"LESTE"; if(v<225)return"SUL"; return"OESTE";
    }
    private static String uf(double lat,double lon,String road){
        if(lat>=-23.40&&lat<=-20.75&&lon>=-44.95&&lon<=-40.70)return"RJ";
        if(lat>=-22.95&&lat<=-14.00&&lon>=-51.10&&lon<=-39.80)return"MG";
        if(lat>=-21.35&&lat<=-17.85&&lon>=-41.95&&lon<=-39.55)return"ES";
        if(lat>=-25.40&&lat<=-19.70&&lon>=-53.20&&lon<=-44.00)return"SP";
        if(road.startsWith("RJ-"))return"RJ"; if(road.startsWith("MG-"))return"MG"; if(road.startsWith("ES-"))return"ES"; if(road.startsWith("SP-"))return"SP";
        return"BR";
    }
    private static String segment(double lat,double lon){
        int a=(int)Math.floor((lat+35.0)*5.0), b=(int)Math.floor((lon+75.0)*5.0);
        return a+"-"+b;
    }
    private static double segmentDistance(double lat,double lon,double lat1,double lon1,double lat2,double lon2){
        if(!Double.isFinite(lat1)||!Double.isFinite(lon1)||!Double.isFinite(lat2)||!Double.isFinite(lon2))return Double.MAX_VALUE;
        double cos=Math.max(.25,Math.cos(Math.toRadians(lat))); double x1=(lon1-lon)*111320*cos,y1=(lat1-lat)*110540,x2=(lon2-lon)*111320*cos,y2=(lat2-lat)*110540;
        double dx=x2-x1,dy=y2-y1,den=dx*dx+dy*dy,t=den<.001?0:-(x1*dx+y1*dy)/den; t=Math.max(0,Math.min(1,t));
        return Math.hypot(x1+t*dx,y1+t*dy);
    }
}
''')

write(JAVA / "RoadRadioActivity.java", r'''package com.estradaplay.comunista;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.activity.ComponentActivity;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.*;

/** Driver-facing push-to-talk radio. Microphone is hot only while PTT is held. */
public final class RoadRadioActivity extends ComponentActivity {
    private static final int REQ_MIC=6101;
    private final int BG=Color.rgb(8,5,7),TEXT=Color.rgb(246,238,224),MUTED=Color.rgb(174,151,146),RED=Color.rgb(190,18,38),GOLD=Color.rgb(226,185,76),GREEN=Color.rgb(72,212,134);
    private TextView room,status,people,alerts; private Button join,ptt,mute; private boolean joined,muted,registered;
    private BroadcastReceiver rx;

    @Override protected void onCreate(Bundle b){super.onCreate(b);build();}
    private void build(){
        ScrollView sv=new ScrollView(this); LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);p.setPadding(dp(18),dp(18),dp(18),dp(30));p.setBackgroundColor(BG);sv.addView(p);setContentView(sv);
        TextView k=t("ESTRADA PLAY · COMUNICAÇÃO",10,GOLD,true);k.setLetterSpacing(.13f);p.addView(k);p.addView(t("RÁDIO DA RODOVIA",29,TEXT,true));p.addView(t("Sala automática pela rodovia, sentido e trecho aproximado. Áudio WebRTC vai direto entre os aparelhos e não fica gravado no servidor.",12,MUTED,false));
        room=t("IDENTIFICANDO RODOVIA…",18,TEXT,true);room.setPadding(dp(14),dp(14),dp(14),dp(14));room.setBackground(box(Color.rgb(25,10,14),12,RED));p.addView(room,new LinearLayout.LayoutParams(-1,-2));
        people=t("0 motoristas no trecho",13,GREEN,true);p.addView(people);status=t("Rádio desligado",12,MUTED,false);p.addView(status);
        join=button("ENTRAR NO RÁDIO",RED);p.addView(join,new LinearLayout.LayoutParams(-1,dp(58)));join.setOnClickListener(v->{if(joined)send(RoadRadioService.ACTION_LEAVE);else enter();});
        ptt=button("SEGURE PARA FALAR",Color.rgb(78,18,28));p.addView(ptt,new LinearLayout.LayoutParams(-1,dp(92)));ptt.setEnabled(false);ptt.setOnTouchListener((v,e)->{if(!joined)return false;int a=e.getActionMasked();if(a==MotionEvent.ACTION_DOWN){send(RoadRadioService.ACTION_PTT_ON);ptt.setText("FALANDO… SOLTE PARA OUVIR");return true;}if(a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_CANCEL){send(RoadRadioService.ACTION_PTT_OFF);ptt.setText("SEGURE PARA FALAR");return true;}return true;});
        mute=button("SILENCIAR RÁDIO",Color.rgb(42,22,25));p.addView(mute,new LinearLayout.LayoutParams(-1,dp(52)));mute.setOnClickListener(v->{muted=!muted;Intent i=new Intent(this,RoadRadioService.class).setAction(RoadRadioService.ACTION_MUTE);i.putExtra("muted",muted);startService(i);renderMute();});
        TextView q=t("ALERTA RÁPIDO DO TRECHO",11,GOLD,true);q.setPadding(0,dp(18),0,dp(6));p.addView(q);
        LinearLayout r1=row();quick(r1,"ACIDENTE","ACIDENTE");quick(r1,"OBJETO","OBJETO");p.addView(r1);LinearLayout r2=row();quick(r2,"OBRA","OBRA");quick(r2,"TRÂNSITO","TRANSITO");p.addView(r2);LinearLayout r3=row();quick(r3,"CHUVA FORTE","CHUVA");p.addView(r3);
        alerts=t("Nenhum alerta recente recebido nesta sala.",12,MUTED,false);alerts.setPadding(dp(12),dp(12),dp(12),dp(12));alerts.setBackground(box(Color.rgb(18,9,12),10,Color.rgb(76,38,43)));p.addView(alerts,new LinearLayout.LayoutParams(-1,-2));
        p.addView(t("SEGURANÇA · alertas de radar, limite e quebra-molas silenciam o rádio automaticamente. Fechar o app encerra o rádio. O microfone não permanece aberto fora do PTT.",10,MUTED,false));
    }
    private void enter(){if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQ_MIC);return;}try{Intent s=new Intent(this,RoadSafetyService.class);if(Build.VERSION.SDK_INT>=26)startForegroundService(s);else startService(s);}catch(Throwable ignored){}Intent i=new Intent(this,RoadRadioService.class).setAction(RoadRadioService.ACTION_JOIN);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);status.setText("Identificando a estrada e entrando na sala…");}
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==REQ_MIC&&g.length>0&&g[0]==PackageManager.PERMISSION_GRANTED)enter();}
    private void quick(LinearLayout row,String label,String type){Button b=button(label,Color.rgb(45,18,23));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(50),1);lp.setMargins(dp(3),dp(3),dp(3),dp(3));row.addView(b,lp);b.setOnClickListener(v->{if(!joined){Toast.makeText(this,"Entre no rádio primeiro.",Toast.LENGTH_SHORT).show();return;}Intent i=new Intent(this,RoadRadioService.class).setAction(RoadRadioService.ACTION_ALERT);i.putExtra("alert_type",type);startService(i);Toast.makeText(this,"Alerta enviado ao trecho.",Toast.LENGTH_SHORT).show();});}
    private void send(String a){Intent i=new Intent(this,RoadRadioService.class).setAction(a);startService(i);}
    @Override protected void onStart(){super.onStart();rx=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){joined=i.getBooleanExtra("joined",false);muted=i.getBooleanExtra("muted",false);String label=i.getStringExtra("room_label");room.setText(label==null||label.isEmpty()?"IDENTIFICANDO RODOVIA…":label.toUpperCase(Locale.ROOT));int n=i.getIntExtra("participants",0);people.setText(n+" motorista"+(n==1?"":"s")+" no trecho");String st=i.getStringExtra("status");status.setText(st==null?"":st);join.setText(joined?"SAIR DO RÁDIO":"ENTRAR NO RÁDIO");ptt.setEnabled(joined&&!muted);String raw=i.getStringExtra("alerts_json");renderAlerts(raw);renderMute();}};IntentFilter f=new IntentFilter(RoadRadioService.ACTION_STATE);if(Build.VERSION.SDK_INT>=33)registerReceiver(rx,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(rx,f);registered=true;}
    @Override protected void onStop(){if(registered)try{unregisterReceiver(rx);}catch(Throwable ignored){}registered=false;send(RoadRadioService.ACTION_QUERY);super.onStop();}
    private void renderMute(){mute.setText(muted?"ATIVAR ÁUDIO DO RÁDIO":"SILENCIAR RÁDIO");ptt.setEnabled(joined&&!muted);}
    private void renderAlerts(String raw){if(raw==null||raw.isEmpty())return;try{JSONArray a=new JSONArray(raw);if(a.length()==0){alerts.setText("Nenhum alerta recente recebido nesta sala.");return;}StringBuilder x=new StringBuilder("ALERTAS RECENTES\n");for(int i=0;i<Math.min(6,a.length());i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;if(x.length()>17)x.append('\n');x.append(o.optString("type","ALERTA").replace('_',' '));long at=o.optLong("at",0);if(at>0)x.append(" · ").append(new SimpleDateFormat("HH:mm",Locale.getDefault()).format(new Date(at*1000L)));}alerts.setText(x.toString());}catch(Throwable ignored){}}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}private Button button(String v,int c){Button b=new Button(this);b.setText(v);b.setTextColor(TEXT);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setBackground(box(c,10,Color.rgb(94,43,50)));return b;}private TextView t(String v,float s,int c,boolean b){TextView t=new TextView(this);t.setText(v);t.setTextSize(s);t.setTextColor(c);if(b)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);t.setPadding(0,dp(5),0,dp(5));return t;}private GradientDrawable box(int c,int r,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(c);d.setCornerRadius(dp(r));d.setStroke(dp(1),stroke);return d;}private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
''')

write(JAVA / "RoadRadioService.java", r'''package com.estradaplay.comunista;

import android.app.*;
import android.content.*;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioManager;
import android.os.*;
import androidx.core.app.NotificationCompat;
import org.json.*;
import org.webrtc.*;
import org.webrtc.audio.JavaAudioDeviceModule;
import java.util.*;
import java.util.concurrent.*;

/**
 * Audio-only P2P WebRTC road radio. Server receives presence and SDP/ICE signaling only.
 * No microphone audio is uploaded or persisted by the EstradaPlay server.
 */
public final class RoadRadioService extends Service {
    static final String ACTION_JOIN="com.estradaplay.comunista.radio.JOIN",ACTION_LEAVE="com.estradaplay.comunista.radio.LEAVE",ACTION_PTT_ON="com.estradaplay.comunista.radio.PTT_ON",ACTION_PTT_OFF="com.estradaplay.comunista.radio.PTT_OFF",ACTION_MUTE="com.estradaplay.comunista.radio.MUTE",ACTION_ALERT="com.estradaplay.comunista.radio.ALERT",ACTION_QUERY="com.estradaplay.comunista.radio.QUERY",ACTION_STATE="com.estradaplay.comunista.radio.STATE";
    private static final String CHANNEL="epc_road_radio";private static final int NOTIF=6210;
    private final Handler main=new Handler(Looper.getMainLooper());private final ExecutorService io=Executors.newSingleThreadExecutor();private final ConcurrentHashMap<String,PeerState> peers=new ConcurrentHashMap<>();
    private ApiClient api;private OfflineRoadStore roads;private RoadIdentityResolver.Identity identity;private boolean wanted,joined,muted,ptt,safetyMuted,registered;private int participants;private long lastSignalId,lastResolveAt;private double lat=Double.NaN,lon=Double.NaN;private float heading=Float.NaN;private String status="Rádio desligado",alertsJson="[]",self="";
    private PeerConnectionFactory factory;private JavaAudioDeviceModule adm;private AudioSource audioSource;private org.webrtc.AudioTrack localTrack;private final ArrayList<PeerConnection.IceServer> iceServers=new ArrayList<>();
    private BroadcastReceiver roadRx;
    private final Runnable tick=new Runnable(){@Override public void run(){if(wanted)io.execute(()->{try{resolveAndSync();}catch(Throwable e){setStatus("Rádio aguardando conexão…");}});main.postDelayed(this,3000L);}};

    @Override public void onCreate(){super.onCreate();api=new ApiClient(this);createChannel();startForeground(NOTIF,notification("Rádio da rodovia","Desligado"));roads=null;io.execute(()->{try{roads=new OfflineRoadStore(getApplicationContext());seedLocation();}catch(Throwable ignored){}});registerRoad();main.post(tick);}
    @Override public int onStartCommand(Intent i,int flags,int id){String a=i==null?"":i.getAction();if(ACTION_JOIN.equals(a)){wanted=true;setStatus("Identificando rodovia…");io.execute(this::resolveAndSyncSafe);}else if(ACTION_LEAVE.equals(a)){wanted=false;ptt=false;io.execute(this::leave);setStatus("Rádio desligado");}else if(ACTION_PTT_ON.equals(a)){setPtt(true);}else if(ACTION_PTT_OFF.equals(a)){setPtt(false);}else if(ACTION_MUTE.equals(a)){muted=i.getBooleanExtra("muted",!muted);applyRemoteAudio();sendState();}else if(ACTION_ALERT.equals(a)){String t=i.getStringExtra("alert_type");io.execute(()->postAlert(t));}else if(ACTION_QUERY.equals(a)){sendState();}return START_NOT_STICKY;}
    @Override public IBinder onBind(Intent i){return null;}

    private void registerRoad(){roadRx=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){if(RoadSafetyService.ACTION_SAFETY_AUDIO.equals(i.getAction())){safetyMuted=i.getBooleanExtra("active",false);if(safetyMuted)setPtt(false);applyRemoteAudio();sendState();return;}double a=i.getDoubleExtra("lat",Double.NaN),b=i.getDoubleExtra("lon",Double.NaN);float h=i.getFloatExtra("heading",Float.NaN);if(Double.isFinite(a)&&Double.isFinite(b)){lat=a;lon=b;}if(Float.isFinite(h))heading=h;if(wanted&&System.currentTimeMillis()-lastResolveAt>30000L)io.execute(RoadRadioService.this::resolveAndSyncSafe);}};IntentFilter f=new IntentFilter();f.addAction(RoadSafetyService.ACTION_STATE);f.addAction(RoadSafetyService.ACTION_SAFETY_AUDIO);if(Build.VERSION.SDK_INT>=33)registerReceiver(roadRx,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(roadRx,f);registered=true;}
    private void seedLocation(){try{if(checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)!=android.content.pm.PackageManager.PERMISSION_GRANTED)return;LocationManager lm=(LocationManager)getSystemService(LOCATION_SERVICE);Location a=lm.getLastKnownLocation(LocationManager.GPS_PROVIDER),b=lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);Location x=a==null?b:(b==null||a.getTime()>=b.getTime()?a:b);if(x!=null){lat=x.getLatitude();lon=x.getLongitude();if(x.hasBearing())heading=x.getBearing();}}catch(Throwable ignored){}}

    private void resolveAndSyncSafe(){try{resolveAndSync();}catch(Throwable ignored){}}
    private void resolveAndSync() throws Exception {if(!wanted)return;if(!ensureSession()){setStatus("Entre novamente no Estrada Play para usar o rádio.");return;}long now=System.currentTimeMillis();if(identity==null||now-lastResolveAt>30000L){lastResolveAt=now;RoadIdentityResolver.Identity next=RoadIdentityResolver.resolve(roads,lat,lon,heading);if(next==null){setStatus("Ainda não reconheci uma rodovia nomeada neste ponto.");return;}if(identity==null||!identity.roomKey.equals(next.roomKey)){leaveNetworkOnly();identity=next;join();return;}identity=next;}if(!joined){join();return;}heartbeat();}
    private boolean ensureSession(){try{if(api.cookie()!=null&&!api.cookie().trim().isEmpty())return true;JSONObject d=new JSONObject();d.put("device_token",DeviceIdentity.token(this));d.put("device_label",DeviceIdentity.label());d.put("app_version",BuildConfig.VERSION_NAME);ApiClient.Response r=api.post("api/native_app.php?action=device_login",d);return r.ok()&&r.json().optBoolean("ok",false);}catch(Throwable e){return false;}}
    private JSONObject base(){JSONObject d=new JSONObject();try{d.put("room_key",identity==null?"":identity.roomKey);d.put("road",identity==null?"":identity.road);d.put("direction",identity==null?"":identity.direction);d.put("segment",identity==null?"":identity.segment);d.put("nickname",nickname());d.put("app_version",BuildConfig.VERSION_NAME);}catch(Throwable ignored){}return d;}
    private String nickname(){String t=DeviceIdentity.token(this);return"MOTORISTA-"+(t.length()>4?t.substring(t.length()-4).toUpperCase(Locale.ROOT):"0000");}
    private void join(){if(identity==null)return;try{ApiClient.Response r=api.post("api/radio.php?action=join",base());JSONObject j=r.json();if(!r.ok()||!j.optBoolean("ok",false)){joined=false;setStatus(j.optString("error","Não consegui entrar no rádio."));return;}self=j.optString("self",DeviceIdentity.token(this));loadIce(j.optJSONArray("ice_servers"));initRtc();joined=true;consume(j);setStatus("Rádio conectado · segure PTT para falar");}catch(Throwable e){joined=false;setStatus("Rádio sem conexão agora.");}}
    private void heartbeat(){if(identity==null||!joined)return;try{JSONObject d=base();d.put("since_signal_id",lastSignalId);ApiClient.Response r=api.post("api/radio.php?action=heartbeat",d);JSONObject j=r.json();if(!r.ok()||!j.optBoolean("ok",false)){joined=false;setStatus(j.optString("error","Reconectando rádio…"));return;}consume(j);}catch(Throwable e){setStatus("Sinalização instável · tentando novamente");}}
    private void consume(JSONObject j){JSONArray ps=j.optJSONArray("peers");HashSet<String> live=new HashSet<>();if(ps!=null)for(int i=0;i<ps.length();i++){String p=ps.optString(i,"");if(p.isEmpty()||p.equals(self))continue;live.add(p);PeerState st=ensurePeer(p);if(st!=null&&!st.offered&&self.compareTo(p)<0)createOffer(st);}participants=Math.min(8,(ps==null?0:ps.length())+1);for(Map.Entry<String,PeerState> e:peers.entrySet())if(!live.contains(e.getKey())){e.getValue().close();peers.remove(e.getKey());}JSONArray sig=j.optJSONArray("signals");if(sig!=null)for(int i=0;i<sig.length();i++){JSONObject x=sig.optJSONObject(i);if(x==null)continue;lastSignalId=Math.max(lastSignalId,x.optLong("id",0));handleSignal(x);}JSONArray al=j.optJSONArray("alerts");alertsJson=al==null?"[]":al.toString();sendState();}
    private void loadIce(JSONArray a){iceServers.clear();if(a!=null)for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;String u=o.optString("url","");if(u.isEmpty())continue;PeerConnection.IceServer.Builder b=PeerConnection.IceServer.builder(u);String user=o.optString("username",""),pass=o.optString("password","");if(!user.isEmpty())b.setUsername(user);if(!pass.isEmpty())b.setPassword(pass);iceServers.add(b.createIceServer());}if(iceServers.isEmpty())iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());}
    private void initRtc(){if(factory!=null)return;PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(getApplicationContext()).createInitializationOptions());adm=JavaAudioDeviceModule.builder(getApplicationContext()).createAudioDeviceModule();factory=PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory();audioSource=factory.createAudioSource(new MediaConstraints());localTrack=factory.createAudioTrack("epc-radio-audio",audioSource);localTrack.setEnabled(false);}
    private PeerState ensurePeer(String id){if(id==null||id.isEmpty()||factory==null)return null;PeerState old=peers.get(id);if(old!=null)return old;PeerConnection.RTCConfiguration cfg=new PeerConnection.RTCConfiguration(iceServers);cfg.sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN;PeerState st=new PeerState(id);PeerConnection pc=factory.createPeerConnection(cfg,new PeerConnection.Observer(){public void onSignalingChange(PeerConnection.SignalingState s){}public void onIceConnectionChange(PeerConnection.IceConnectionState s){if(s==PeerConnection.IceConnectionState.FAILED||s==PeerConnection.IceConnectionState.CLOSED){}}public void onIceConnectionReceivingChange(boolean b){}public void onIceGatheringChange(PeerConnection.IceGatheringState s){}public void onIceCandidate(IceCandidate c){sendSignal(id,"ice",iceJson(c));}public void onIceCandidatesRemoved(IceCandidate[] c){}public void onAddStream(MediaStream s){}public void onRemoveStream(MediaStream s){}public void onDataChannel(DataChannel d){}public void onRenegotiationNeeded(){}public void onAddTrack(RtpReceiver r,MediaStream[] streams){MediaStreamTrack t=r.track();if(t instanceof org.webrtc.AudioTrack){st.remote=(org.webrtc.AudioTrack)t;applyRemoteAudio();}}});if(pc==null)return null;st.pc=pc;try{pc.addTrack(localTrack,Collections.singletonList("epc-radio"));}catch(Throwable ignored){}PeerState race=peers.putIfAbsent(id,st);if(race!=null){st.close();return race;}return st;}
    private void createOffer(PeerState st){if(st==null||st.pc==null||st.offered)return;st.offered=true;st.pc.createOffer(new SDP(){@Override public void onCreateSuccess(SessionDescription d){st.pc.setLocalDescription(new SDP(){@Override public void onSetSuccess(){sendSignal(st.id,"offer",sdpJson(d));}},d);}},new MediaConstraints());}
    private void createAnswer(PeerState st){if(st==null||st.pc==null)return;st.pc.createAnswer(new SDP(){@Override public void onCreateSuccess(SessionDescription d){st.pc.setLocalDescription(new SDP(){@Override public void onSetSuccess(){sendSignal(st.id,"answer",sdpJson(d));}},d);}},new MediaConstraints());}
    private void handleSignal(JSONObject x){String from=x.optString("from","");String type=x.optString("type","");JSONObject p=x.optJSONObject("payload");if(from.isEmpty()||p==null)return;PeerState st=ensurePeer(from);if(st==null)return;if("offer".equals(type)||"answer".equals(type)){SessionDescription.Type ty="offer".equals(type)?SessionDescription.Type.OFFER:SessionDescription.Type.ANSWER;SessionDescription sd=new SessionDescription(ty,p.optString("sdp",""));st.pc.setRemoteDescription(new SDP(){@Override public void onSetSuccess(){flushIce(st);if("offer".equals(type))createAnswer(st);}},sd);}else if("ice".equals(type)){IceCandidate c=new IceCandidate(p.optString("mid",null),p.optInt("index",0),p.optString("candidate",""));if(st.pc.getRemoteDescription()==null)st.pending.add(c);else st.pc.addIceCandidate(c);}}
    private void flushIce(PeerState st){for(IceCandidate c:st.pending)try{st.pc.addIceCandidate(c);}catch(Throwable ignored){}st.pending.clear();}
    private JSONObject sdpJson(SessionDescription d){JSONObject o=new JSONObject();try{o.put("sdp",d.description);}catch(Throwable ignored){}return o;}private JSONObject iceJson(IceCandidate c){JSONObject o=new JSONObject();try{o.put("mid",c.sdpMid);o.put("index",c.sdpMLineIndex);o.put("candidate",c.sdp);}catch(Throwable ignored){}return o;}
    private void sendSignal(String to,String type,JSONObject payload){if(!joined||identity==null)return;io.execute(()->{try{JSONObject d=base();d.put("to",to);d.put("signal_type",type);d.put("payload",payload);api.post("api/radio.php?action=signal",d);}catch(Throwable ignored){}});}
    private void postAlert(String type){if(!joined||identity==null||type==null)return;try{JSONObject d=base();d.put("alert_type",type);ApiClient.Response r=api.post("api/radio.php?action=alert",d);JSONObject j=r.json();if(r.ok()&&j.optBoolean("ok",false))setStatus("Alerta do trecho enviado.");else setStatus(j.optString("error","Não consegui enviar o alerta."));}catch(Throwable e){setStatus("Sem conexão para enviar alerta.");}}
    private void setPtt(boolean on){if(!joined||muted||safetyMuted)on=false;ptt=on;if(localTrack!=null)localTrack.setEnabled(on);sendState();updateNotification();}
    private void applyRemoteAudio(){boolean enabled=joined&&!muted&&!safetyMuted;for(PeerState p:peers.values())if(p.remote!=null)try{p.remote.setEnabled(enabled);}catch(Throwable ignored){}}
    private void leave(){try{if(joined&&identity!=null)api.post("api/radio.php?action=leave",base());}catch(Throwable ignored){}leaveNetworkOnly();identity=null;participants=0;sendState();}
    private void leaveNetworkOnly(){joined=false;ptt=false;if(localTrack!=null)localTrack.setEnabled(false);for(PeerState p:peers.values())p.close();peers.clear();participants=0;lastSignalId=0;}
    private void setStatus(String s){status=s==null?"":s;sendState();updateNotification();}
    private void sendState(){Intent i=new Intent(ACTION_STATE).setPackage(getPackageName());i.putExtra("joined",joined);i.putExtra("muted",muted);i.putExtra("ptt",ptt);i.putExtra("participants",participants);i.putExtra("room_label",identity==null?"":identity.label);i.putExtra("status",status+(safetyMuted?" · alerta de segurança em prioridade":""));i.putExtra("alerts_json",alertsJson);sendBroadcast(i);}
    private void createChannel(){if(Build.VERSION.SDK_INT<26)return;NotificationManager n=getSystemService(NotificationManager.class);if(n!=null){NotificationChannel c=new NotificationChannel(CHANNEL,"Rádio da rodovia",NotificationManager.IMPORTANCE_LOW);c.setSound(null,null);n.createNotificationChannel(c);}}
    private Notification notification(String title,String text){Intent open=new Intent(this,RoadRadioActivity.class);PendingIntent p=PendingIntent.getActivity(this,62,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);return b.setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle(title).setContentText(text).setContentIntent(p).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).build();}
    private void updateNotification(){NotificationManager n=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);if(n!=null)n.notify(NOTIF,notification(identity==null?"Rádio da rodovia":identity.road,ptt?"Transmitindo PTT":(joined?participants+" no trecho":"Desligado")));}
    @Override public void onTaskRemoved(Intent root){wanted=false;io.execute(this::leave);stopSelf();super.onTaskRemoved(root);}
    @Override public void onDestroy(){wanted=false;main.removeCallbacks(tick);if(registered)try{unregisterReceiver(roadRx);}catch(Throwable ignored){}registered=false;try{leaveNetworkOnly();}catch(Throwable ignored){}try{if(localTrack!=null)localTrack.dispose();}catch(Throwable ignored){}try{if(audioSource!=null)audioSource.dispose();}catch(Throwable ignored){}try{if(factory!=null)factory.dispose();}catch(Throwable ignored){}try{if(adm!=null)adm.release();}catch(Throwable ignored){}io.shutdownNow();super.onDestroy();}
    private static final class PeerState{final String id;PeerConnection pc;org.webrtc.AudioTrack remote;boolean offered;final ArrayList<IceCandidate> pending=new ArrayList<>();PeerState(String id){this.id=id;}void close(){try{if(remote!=null)remote.setEnabled(false);}catch(Throwable ignored){}try{if(pc!=null){pc.close();pc.dispose();}}catch(Throwable ignored){}}}
    private static class SDP implements SdpObserver{public void onCreateSuccess(SessionDescription d){}public void onSetSuccess(){}public void onCreateFailure(String s){}public void onSetFailure(String s){}}
}
''')

# ---------------------------------------------------------------------------
# Server 500 MB v5 — presence/signaling/alerts only. Never stores voice media.
# ---------------------------------------------------------------------------
write(ROOT / "api/radio.php", r'''<?php
declare(strict_types=1);
require_once __DIR__.'/bootstrap.php';
require_once __DIR__.'/native_auth.php';
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
$body=input_json();$user=native_require_json_user($body);$pdo=db();$driver=(string)$pdo->getAttribute(PDO::ATTR_DRIVER_NAME);

function radio_schema(PDO $pdo,string $driver):void{
 if($driver==='mysql'){
  $pdo->exec("CREATE TABLE IF NOT EXISTS radio_presence (device_token VARCHAR(128) PRIMARY KEY,user_id BIGINT NULL,room_key VARCHAR(190) NOT NULL,road VARCHAR(32) NOT NULL,direction VARCHAR(16) NOT NULL,segment VARCHAR(40) NOT NULL,nickname VARCHAR(40) NOT NULL,joined_at DATETIME NOT NULL,last_seen_at DATETIME NOT NULL,KEY idx_radio_room_seen(room_key,last_seen_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
  $pdo->exec("CREATE TABLE IF NOT EXISTS radio_signals (id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,room_key VARCHAR(190) NOT NULL,from_device VARCHAR(128) NOT NULL,to_device VARCHAR(128) NOT NULL,signal_type VARCHAR(12) NOT NULL,payload MEDIUMTEXT NOT NULL,created_at DATETIME NOT NULL,KEY idx_radio_signal_to(to_device,id),KEY idx_radio_signal_room(room_key,created_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
  $pdo->exec("CREATE TABLE IF NOT EXISTS radio_alerts (id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,room_key VARCHAR(190) NOT NULL,device_token VARCHAR(128) NOT NULL,alert_type VARCHAR(24) NOT NULL,created_at DATETIME NOT NULL,expires_at DATETIME NOT NULL,KEY idx_radio_alert_room(room_key,expires_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
 }else{
  $pdo->exec("CREATE TABLE IF NOT EXISTS radio_presence (device_token TEXT PRIMARY KEY,user_id INTEGER,room_key TEXT NOT NULL,road TEXT NOT NULL,direction TEXT NOT NULL,segment TEXT NOT NULL,nickname TEXT NOT NULL,joined_at TEXT NOT NULL,last_seen_at TEXT NOT NULL)");
  $pdo->exec("CREATE TABLE IF NOT EXISTS radio_signals (id INTEGER PRIMARY KEY AUTOINCREMENT,room_key TEXT NOT NULL,from_device TEXT NOT NULL,to_device TEXT NOT NULL,signal_type TEXT NOT NULL,payload TEXT NOT NULL,created_at TEXT NOT NULL)");
  $pdo->exec("CREATE TABLE IF NOT EXISTS radio_alerts (id INTEGER PRIMARY KEY AUTOINCREMENT,room_key TEXT NOT NULL,device_token TEXT NOT NULL,alert_type TEXT NOT NULL,created_at TEXT NOT NULL,expires_at TEXT NOT NULL)");
 }
}
function radio_clean(PDO $pdo):void{try{$pdo->prepare('DELETE FROM radio_presence WHERE last_seen_at < ?')->execute([date('Y-m-d H:i:s',time()-20)]);$pdo->prepare('DELETE FROM radio_signals WHERE created_at < ?')->execute([date('Y-m-d H:i:s',time()-120)]);$pdo->prepare('DELETE FROM radio_alerts WHERE expires_at < ?')->execute([date('Y-m-d H:i:s')]);}catch(Throwable $e){}}
function radio_room(array $b):array{$room=strtoupper(trim((string)($b['room_key']??'')));$road=strtoupper(trim((string)($b['road']??'')));$dir=strtoupper(trim((string)($b['direction']??'')));$seg=trim((string)($b['segment']??''));if(!preg_match('/^[A-Z0-9|_-]{5,190}$/',$room)||!preg_match('/^(BR|RJ|MG|ES|SP)-[0-9]{1,4}$/',$road))json_response(['ok'=>false,'error'=>'Rodovia ainda não reconhecida.'],422);return[$room,$road,substr($dir,0,16),substr($seg,0,40)];}
function radio_peers(PDO $pdo,string $room,string $self):array{$q=$pdo->prepare('SELECT device_token FROM radio_presence WHERE room_key=? AND last_seen_at>=? AND device_token<>? ORDER BY last_seen_at DESC LIMIT 7');$q->execute([$room,date('Y-m-d H:i:s',time()-20),$self]);return array_values(array_map(fn($r)=>(string)$r['device_token'],$q->fetchAll()?:[]));}
function radio_alert_rows(PDO $pdo,string $room):array{$q=$pdo->prepare('SELECT alert_type,created_at FROM radio_alerts WHERE room_key=? AND expires_at>=? ORDER BY id DESC LIMIT 8');$q->execute([$room,date('Y-m-d H:i:s')]);$out=[];foreach($q->fetchAll()?:[] as $r)$out[]=['type'=>(string)$r['alert_type'],'at'=>strtotime((string)$r['created_at'])];return$out;}
function radio_ice():array{$out=[['url'=>'stun:stun.l.google.com:19302']];$url=trim((string)app_setting('radio_turn_url',''));if($url!=='')$out[]=['url'=>$url,'username'=>(string)app_setting('radio_turn_username',''),'password'=>(string)app_setting('radio_turn_password','')];return$out;}
radio_schema($pdo,$driver);radio_clean($pdo);if(app_setting('radio_enabled','1')==='0')json_response(['ok'=>false,'error'=>'Rádio temporariamente desativado pelo servidor.'],503);
$device=native_device_token_from_request($body);if($device==='')json_response(['ok'=>false,'error'=>'Dispositivo inválido.'],401);$action=strtolower(trim((string)($_GET['action']??'')));
if($action==='leave'){try{$pdo->prepare('DELETE FROM radio_presence WHERE device_token=?')->execute([$device]);}catch(Throwable $e){}json_response(['ok'=>true]);}
[$room,$road,$direction,$segment]=radio_room($body);$nickname=substr(preg_replace('/[^A-Z0-9_-]/i','',strtoupper((string)($body['nickname']??'MOTORISTA'))),0,40);if($nickname==='')$nickname='MOTORISTA';$now=date('Y-m-d H:i:s');
if($action==='join'){
 $q=$pdo->prepare('SELECT COUNT(*) FROM radio_presence WHERE room_key=? AND last_seen_at>=? AND device_token<>?');$q->execute([$room,date('Y-m-d H:i:s',time()-20),$device]);if((int)$q->fetchColumn()>=7)json_response(['ok'=>false,'error'=>'Este trecho já atingiu 8 participantes. Avance alguns quilômetros ou tente novamente.'],409);
 try{if($driver==='mysql'){$q=$pdo->prepare("INSERT INTO radio_presence(device_token,user_id,room_key,road,direction,segment,nickname,joined_at,last_seen_at) VALUES(?,?,?,?,?,?,?,NOW(),NOW()) ON DUPLICATE KEY UPDATE user_id=VALUES(user_id),room_key=VALUES(room_key),road=VALUES(road),direction=VALUES(direction),segment=VALUES(segment),nickname=VALUES(nickname),last_seen_at=NOW()");$q->execute([$device,(int)$user['id'],$room,$road,$direction,$segment,$nickname]);}else{$pdo->prepare('DELETE FROM radio_presence WHERE device_token=?')->execute([$device]);$pdo->prepare('INSERT INTO radio_presence(device_token,user_id,room_key,road,direction,segment,nickname,joined_at,last_seen_at) VALUES(?,?,?,?,?,?,?,?,?)')->execute([$device,(int)$user['id'],$room,$road,$direction,$segment,$nickname,$now,$now]);}}catch(Throwable $e){error_log('RADIO_JOIN '.$e->getMessage());json_response(['ok'=>false,'error'=>'Não foi possível entrar no rádio.'],503);}json_response(['ok'=>true,'self'=>$device,'room'=>['key'=>$room,'road'=>$road,'direction'=>$direction,'segment'=>$segment],'peers'=>radio_peers($pdo,$room,$device),'alerts'=>radio_alert_rows($pdo,$room),'ice_servers'=>radio_ice(),'max_participants'=>8,'audio_stored'=>false]);
}
// Heartbeats also keep room membership fresh; device must already be joined to the same room.
$q=$pdo->prepare('SELECT room_key FROM radio_presence WHERE device_token=? LIMIT 1');$q->execute([$device]);$current=(string)($q->fetchColumn()?:'');if($current!==$room)json_response(['ok'=>false,'error'=>'Entre novamente na sala.'],409);$pdo->prepare('UPDATE radio_presence SET last_seen_at=? WHERE device_token=?')->execute([$now,$device]);
if($action==='signal'){$to=strtolower(trim((string)($body['to']??'')));$type=strtolower(trim((string)($body['signal_type']??'')));$payload=$body['payload']??null;if(!preg_match('/^[a-f0-9]{32,128}$/',$to)||!in_array($type,['offer','answer','ice'],true)||!is_array($payload))json_response(['ok'=>false,'error'=>'Sinal inválido.'],422);$check=$pdo->prepare('SELECT COUNT(*) FROM radio_presence WHERE device_token=? AND room_key=? AND last_seen_at>=?');$check->execute([$to,$room,date('Y-m-d H:i:s',time()-20)]);if((int)$check->fetchColumn()!==1)json_response(['ok'=>false,'error'=>'Motorista não está mais no trecho.'],410);$raw=json_encode($payload,JSON_UNESCAPED_SLASHES);if($raw===false||strlen($raw)>24000)json_response(['ok'=>false,'error'=>'Sinal muito grande.'],422);$rate=$pdo->prepare('SELECT COUNT(*) FROM radio_signals WHERE from_device=? AND created_at>=?');$rate->execute([$device,date('Y-m-d H:i:s',time()-60)]);if((int)$rate->fetchColumn()>160)json_response(['ok'=>false,'error'=>'Muitas tentativas de conexão. Aguarde alguns segundos.'],429);$pdo->prepare('INSERT INTO radio_signals(room_key,from_device,to_device,signal_type,payload,created_at) VALUES(?,?,?,?,?,?)')->execute([$room,$device,$to,$type,$raw,$now]);json_response(['ok'=>true]);}
if($action==='alert'){$type=strtoupper(trim((string)($body['alert_type']??'')));if(!in_array($type,['ACIDENTE','OBJETO','OBRA','TRANSITO','CHUVA'],true))json_response(['ok'=>false,'error'=>'Alerta inválido.'],422);$q=$pdo->prepare('SELECT COUNT(*) FROM radio_alerts WHERE room_key=? AND device_token=? AND alert_type=? AND created_at>=?');$q->execute([$room,$device,$type,date('Y-m-d H:i:s',time()-120)]);if((int)$q->fetchColumn()>0)json_response(['ok'=>true,'duplicate'=>true]);$pdo->prepare('INSERT INTO radio_alerts(room_key,device_token,alert_type,created_at,expires_at) VALUES(?,?,?,?,?)')->execute([$room,$device,$type,$now,date('Y-m-d H:i:s',time()+1800)]);json_response(['ok'=>true,'stored'=>true]);}
if($action==='heartbeat'){$since=max(0,(int)($body['since_signal_id']??0));$q=$pdo->prepare('SELECT id,from_device,signal_type,payload FROM radio_signals WHERE to_device=? AND room_key=? AND id>? ORDER BY id ASC LIMIT 120');$q->execute([$device,$room,$since]);$signals=[];foreach($q->fetchAll()?:[] as $r){$p=json_decode((string)$r['payload'],true);$signals[]=['id'=>(int)$r['id'],'from'=>(string)$r['from_device'],'type'=>(string)$r['signal_type'],'payload'=>is_array($p)?$p:[]];}json_response(['ok'=>true,'peers'=>radio_peers($pdo,$room,$device),'signals'=>$signals,'alerts'=>radio_alert_rows($pdo,$room),'audio_stored'=>false]);}
json_response(['ok'=>false,'error'=>'Ação inválida.'],404);
''')

write(ROOT / "admin_radio.php", r'''<?php
declare(strict_types=1);require_once __DIR__.'/api/bootstrap.php';ensure_default_users();$user=require_admin();$pdo=db();require_once __DIR__.'/api/radio.php';
'''.replace("require_once __DIR__.'/api/radio.php';", "") + r'''$message='';if($_SERVER['REQUEST_METHOD']==='POST'){require_csrf();$enabled=($_POST['enabled']??'1')==='1'?'1':'0';set_app_setting('radio_enabled',$enabled);$message=$enabled==='1'?'Rádio liberado.':'Rádio desativado pelo servidor.';}
$enabled=app_setting('radio_enabled','1')!=='0';$rooms=[];$alerts=[];try{$cut=date('Y-m-d H:i:s',time()-20);$q=$pdo->prepare('SELECT room_key,road,direction,segment,COUNT(*) participants,MAX(last_seen_at) last_seen FROM radio_presence WHERE last_seen_at>=? GROUP BY room_key,road,direction,segment ORDER BY participants DESC,last_seen DESC LIMIT 100');$q->execute([$cut]);$rooms=$q->fetchAll()?:[];$q=$pdo->query('SELECT room_key,alert_type,created_at,expires_at FROM radio_alerts WHERE expires_at>=NOW() ORDER BY id DESC LIMIT 100');$alerts=$q->fetchAll()?:[];}catch(Throwable $e){}
function ar_h($v){return htmlspecialchars((string)$v,ENT_QUOTES,'UTF-8');}?>
<!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="robots" content="noindex,nofollow"><title>Rádio da Rodovia</title><link rel="stylesheet" href="assets/css/app.css?v=1.0.0"><style>.rgrid{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:10px}.rtable td,.rtable th{vertical-align:top}.on{color:#45d483}.off{color:#ff646b}</style></head><body data-role="admin"><main class="admin-main"><div class="toolbar admin-toolbar"><div><p class="eyebrow">ESTRADAPLAY · SERVER V5</p><h1>Rádio da Rodovia</h1><p class="muted">Presença e sinalização WebRTC. O servidor não recebe nem grava o áudio dos motoristas.</p></div><div class="row"><a class="button secondary" href="admin_server.php">Central do Servidor</a><a class="button secondary" href="admin.php">Painel Admin</a></div></div>
<?php if($message):?><div class="panel success"><?=ar_h($message)?></div><?php endif;?>
<section class="panel"><div class="rgrid"><article class="panel"><span class="muted">Estado</span><h2 class="<?=$enabled?'on':'off'?>"><?=$enabled?'ATIVO':'DESATIVADO'?></h2></article><article class="panel"><span class="muted">Salas ativas</span><h2><?=count($rooms)?></h2></article><article class="panel"><span class="muted">Motoristas conectados</span><h2><?=array_sum(array_map(fn($r)=>(int)$r['participants'],$rooms))?></h2></article><article class="panel"><span class="muted">Áudio armazenado</span><h2>NÃO</h2></article></div><form method="post"><input type="hidden" name="csrf" value="<?=ar_h(csrf_token())?>"><input type="hidden" name="enabled" value="<?=$enabled?'0':'1'?>"><button class="button <?=$enabled?'secondary':''?>" type="submit"><?=$enabled?'DESATIVAR RÁDIO':'ATIVAR RÁDIO'?></button></form></section>
<section class="panel"><h2>Salas por trecho</h2><div class="table-wrap"><table class="rtable"><thead><tr><th>Rodovia</th><th>Sentido</th><th>Trecho</th><th>Participantes</th><th>Último sinal</th></tr></thead><tbody><?php if(!$rooms):?><tr><td colspan="5">Nenhuma sala ativa agora.</td></tr><?php endif;?><?php foreach($rooms as $r):?><tr><td><strong><?=ar_h($r['road'])?></strong></td><td><?=ar_h($r['direction'])?></td><td><?=ar_h($r['segment'])?></td><td><?= (int)$r['participants']?> / 8</td><td><?=ar_h($r['last_seen'])?></td></tr><?php endforeach;?></tbody></table></div></section>
<section class="panel"><h2>Alertas rápidos ativos</h2><p class="muted">Expiram em 30 minutos e não entram automaticamente na base permanente de perigos.</p><div class="table-wrap"><table class="rtable"><thead><tr><th>Sala</th><th>Tipo</th><th>Quando</th><th>Expira</th></tr></thead><tbody><?php if(!$alerts):?><tr><td colspan="4">Nenhum alerta temporário ativo.</td></tr><?php endif;?><?php foreach($alerts as $a):?><tr><td><?=ar_h($a['room_key'])?></td><td><strong><?=ar_h($a['alert_type'])?></strong></td><td><?=ar_h($a['created_at'])?></td><td><?=ar_h($a['expires_at'])?></td></tr><?php endforeach;?></tbody></table></div></section>
</main></body></html>
''')

# Server version/capabilities.
server_intel = ROOT / "api/server_intelligent.php"
s = read(server_intel)
s = s.replace("const ESTRADAPLAY_SERVER_INTELLIGENT_VERSION = '500MB-v4';", "const ESTRADAPLAY_SERVER_INTELLIGENT_VERSION = '500MB-v5';")
if "road_radio" not in s:
    s = s.replace("        'offline_state_packs'=>['RJ','MG','ES'],\n", "        'offline_state_packs'=>['RJ','MG','ES'],\n        'road_radio'=>true,\n        'radio_audio_stored'=>false,\n        'radio_room_max'=>8,\n")
write(server_intel, s)

admin_server = ROOT / "admin_server.php"
s = read(admin_server)
s = s.replace('ESTRADAPLAY · SERVIDOR 500 MB V4','ESTRADAPLAY · SERVIDOR 500 MB V5')
if 'admin_radio.php' not in s:
    s = s.replace('<a class="button secondary" href="admin_collective.php">Inteligência Coletiva</a>', '<a class="button secondary" href="admin_collective.php">Inteligência Coletiva</a><a class="button secondary" href="admin_radio.php">Rádio da Rodovia</a>')
write(admin_server, s)

write(APP / "RADIO-1.6.1.md", '''# Estrada Play Comunista Universal 1.6.1 — Estrada Rádio\n\n- PTT real-time audio via WebRTC peer-to-peer.\n- Server stores presence and SDP/ICE signaling only; never voice audio.\n- Automatic room from locally recognized road + direction + approximate ~20 km cell.\n- Public room capped at 8 participants.\n- Safety voice has absolute priority and temporarily mutes radio.\n- Quick temporary alerts: accident, object, roadworks, traffic, heavy rain.\n- Radio foreground service is non-sticky and stopWithTask=true.\n- Server v5 has admin kill switch and active-room dashboard.\n- STUN by default; optional TURN credentials via app settings: radio_turn_url/user/password.\n''')

print("Estrada Play 1.6.1 + Server v5 radio patch applied")

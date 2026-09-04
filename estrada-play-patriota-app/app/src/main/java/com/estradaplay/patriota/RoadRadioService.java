package com.estradaplay.patriota;

import android.app.*;
import android.content.*;
import android.location.Location;
import android.location.LocationManager;
import android.os.*;
import org.json.*;
import org.webrtc.*;
import org.webrtc.audio.JavaAudioDeviceModule;
import java.util.*;
import java.util.concurrent.*;

/**
 * Audio-only P2P WebRTC road radio. Server receives presence and SDP/ICE signaling only.
 * No microphone audio is uploaded or persisted by the EstradaPlay server.
 *
 * PTT_STABILITY_V234: joining a room no longer initializes native WebRTC audio while the driver is
 * alone. RTC starts only when a real peer exists and is torn down again when the room becomes empty.
 * The service is also isolated in :radio by the manifest so a vendor/native audio failure cannot
 * take the Central, navigation or road-safety process down with it.
 */
public final class RoadRadioService extends Service {
    static final String ACTION_JOIN="com.estradaplay.patriota.radio.JOIN",ACTION_LEAVE="com.estradaplay.patriota.radio.LEAVE",ACTION_PTT_ON="com.estradaplay.patriota.radio.PTT_ON",ACTION_PTT_OFF="com.estradaplay.patriota.radio.PTT_OFF",ACTION_MUTE="com.estradaplay.patriota.radio.MUTE",ACTION_ALERT="com.estradaplay.patriota.radio.ALERT",ACTION_QUERY="com.estradaplay.patriota.radio.QUERY",ACTION_SET_ROAD="com.estradaplay.patriota.radio.SET_ROAD",ACTION_STATE="com.estradaplay.patriota.radio.STATE";
    private static final String CHANNEL="epp_road_radio";
    private static final int NOTIF=6210;

    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final ConcurrentHashMap<String,PeerState> peers=new ConcurrentHashMap<>();

    private ApiClient api;
    private OfflineRoadStore roads;
    private RoadIdentityResolver.Identity identity;
    private String manualRoad="",autoRoadHint="";
    private boolean wanted,joined,muted,ptt,safetyMuted,registered,rtcFailed;
    private int participants;
    private long lastSignalId,lastResolveAt;
    private double lat=Double.NaN,lon=Double.NaN;
    private float heading=Float.NaN;
    private String status="Rádio desligado",alertsJson="[]",self="";

    private PeerConnectionFactory factory;
    private org.webrtc.audio.AudioDeviceModule adm;
    private AudioSource audioSource;
    private org.webrtc.AudioTrack localTrack;
    private final ArrayList<PeerConnection.IceServer> iceServers=new ArrayList<>();
    private BroadcastReceiver roadRx;

    private final Runnable tick=new Runnable(){@Override public void run(){
        if(wanted)io.execute(()->{try{resolveAndSync();}catch(Throwable e){setStatus("Rádio aguardando conexão…");}});
        main.postDelayed(this,3000L);
    }};

    @Override public void onCreate(){
        super.onCreate();
        api=new ApiClient(this);
        manualRoad=KnownRoadCatalog.selected(this);
        createChannel();
        startForeground(NOTIF,notification("Rádio da rodovia","Desligado"));
        roads=null;
        io.execute(()->{try{roads=new OfflineRoadStore(getApplicationContext());seedLocation();}catch(Throwable ignored){}});
        registerRoad();
        main.post(tick);
    }

    @Override public int onStartCommand(Intent i,int flags,int id){
        String a=i==null?"":i.getAction();
        if(ACTION_JOIN.equals(a)){
            wanted=true;
            rtcFailed=false;
            setStatus("Identificando rodovia…");
            io.execute(this::resolveAndSyncSafe);
        }else if(ACTION_LEAVE.equals(a)){
            wanted=false;ptt=false;io.execute(this::leave);setStatus("Rádio desligado");
        }else if(ACTION_PTT_ON.equals(a)){
            setPtt(true);
        }else if(ACTION_PTT_OFF.equals(a)){
            setPtt(false);
        }else if(ACTION_MUTE.equals(a)){
            muted=i.getBooleanExtra("muted",!muted);applyRemoteAudio();sendState();
        }else if(ACTION_SET_ROAD.equals(a)){
            String r=KnownRoadCatalog.canonical(i.getStringExtra("road"));
            if(r.isEmpty()){
                manualRoad="";KnownRoadCatalog.clear(this);identity=null;
                if(wanted)io.execute(this::resolveAndSyncSafe);
                setStatus("Identificação automática de rodovia ativada.");
            }else{
                manualRoad=r;KnownRoadCatalog.select(this,r);identity=null;
                if(wanted)io.execute(this::resolveAndSyncSafe);
                setStatus("Rodovia escolhida: "+r+" · confirmando trecho pelo GPS…");
            }
        }else if(ACTION_ALERT.equals(a)){
            String t=i.getStringExtra("alert_type");io.execute(()->postAlert(t));
        }else if(ACTION_QUERY.equals(a)){
            sendState();
        }
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent i){return null;}

    private void registerRoad(){
        roadRx=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
            if(RoadSafetyService.ACTION_SAFETY_AUDIO.equals(i.getAction())){
                safetyMuted=i.getBooleanExtra("active",false);
                if(safetyMuted)setPtt(false);
                applyRemoteAudio();sendState();return;
            }
            double a=i.getDoubleExtra("lat",Double.NaN),b=i.getDoubleExtra("lon",Double.NaN);
            float h=i.getFloatExtra("heading",Float.NaN);
            String roadHint=KnownRoadCatalog.canonical(i.getStringExtra("road"));
            if(!roadHint.isEmpty())autoRoadHint=roadHint;
            if(Double.isFinite(a)&&Double.isFinite(b)){lat=a;lon=b;}
            if(Float.isFinite(h))heading=h;
            if(wanted&&System.currentTimeMillis()-lastResolveAt>30000L)io.execute(RoadRadioService.this::resolveAndSyncSafe);
        }};
        IntentFilter f=new IntentFilter();
        f.addAction(RoadSafetyService.ACTION_STATE);
        f.addAction(RoadSafetyService.ACTION_SAFETY_AUDIO);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(roadRx,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(roadRx,f);
        registered=true;
    }

    private void seedLocation(){
        try{
            if(checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)!=android.content.pm.PackageManager.PERMISSION_GRANTED)return;
            LocationManager lm=(LocationManager)getSystemService(LOCATION_SERVICE);
            Location a=lm.getLastKnownLocation(LocationManager.GPS_PROVIDER),b=lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            Location x=a==null?b:(b==null||a.getTime()>=b.getTime()?a:b);
            if(x!=null){lat=x.getLatitude();lon=x.getLongitude();if(x.hasBearing())heading=x.getBearing();}
        }catch(Throwable ignored){}
    }

    private void resolveAndSyncSafe(){try{resolveAndSync();}catch(Throwable ignored){}}

    // MANUAL_ROAD_OVERRIDE_V177: an explicit driver choice wins until AUTOMATIC mode is selected again.
    private void resolveAndSync() throws Exception {
        if(!wanted)return;
        if(!ensureSession()){setStatus("Entre novamente no Estrada Play para usar o rádio.");return;}
        long now=System.currentTimeMillis();
        if(identity==null||now-lastResolveAt>30000L){
            lastResolveAt=now;
            RoadIdentityResolver.Identity next=null;
            if(!manualRoad.isEmpty())next=RoadIdentityResolver.fromKnownRoad(manualRoad,lat,lon,heading);
            if(next==null)next=RoadIdentityResolver.resolve(roads,lat,lon,heading);
            if(next==null&&!autoRoadHint.isEmpty())next=RoadIdentityResolver.fromKnownRoad(autoRoadHint,lat,lon,heading);
            if(next==null){setStatus("Não identifiquei a rodovia. Defina uma rodovia de apoio.");return;}
            if(identity==null||!identity.roomKey.equals(next.roomKey)){
                leaveNetworkOnly();identity=next;join();return;
            }
            identity=next;
        }
        if(!joined){join();return;}
        heartbeat();
    }

    private boolean ensureSession(){
        try{
            if(api.cookie()!=null&&!api.cookie().trim().isEmpty())return true;
            JSONObject d=new JSONObject();
            d.put("device_token",DeviceIdentity.token(this));
            d.put("device_label",DeviceIdentity.label());
            d.put("app_version",BuildConfig.VERSION_NAME);
            ApiClient.Response r=api.post("api/native_app.php?action=device_login",d);
            return r.ok()&&r.json().optBoolean("ok",false);
        }catch(Throwable e){return false;}
    }

    private JSONObject base(){
        JSONObject d=new JSONObject();
        try{
            d.put("room_key",identity==null?"":identity.roomKey);
            d.put("road",identity==null?"":identity.road);
            d.put("direction",identity==null?"":identity.direction);
            d.put("segment",identity==null?"":identity.segment);
            d.put("nickname",nickname());
            d.put("app_version",BuildConfig.VERSION_NAME);
        }catch(Throwable ignored){}
        return d;
    }

    private String nickname(){String t=DeviceIdentity.token(this);return"MOTORISTA-"+(t.length()>4?t.substring(t.length()-4).toUpperCase(Locale.ROOT):"0000");}

    private void join(){
        if(identity==null)return;
        try{
            ApiClient.Response r=api.post("api/radio.php?action=join",base());
            JSONObject j=r.json();
            if(!r.ok()||!j.optBoolean("ok",false)){
                joined=false;setStatus(j.optString("error","Não consegui entrar no rádio."));return;
            }
            self=j.optString("self",DeviceIdentity.token(this));
            loadIce(j.optJSONArray("ice_servers"));
            // PTT_STABILITY_V234: presence first. Native audio is lazy and only starts with a peer.
            joined=true;
            consume(j);
        }catch(Throwable e){joined=false;setStatus("Rádio sem conexão agora.");}
    }

    private void heartbeat(){
        if(identity==null||!joined)return;
        try{
            JSONObject d=base();d.put("since_signal_id",lastSignalId);
            ApiClient.Response r=api.post("api/radio.php?action=heartbeat",d);
            JSONObject j=r.json();
            if(!r.ok()||!j.optBoolean("ok",false)){
                joined=false;setStatus(j.optString("error","Reconectando rádio…"));return;
            }
            consume(j);
        }catch(Throwable e){setStatus("Sinalização instável · tentando novamente");}
    }

    private void consume(JSONObject j){
        JSONArray ps=j.optJSONArray("peers");
        HashSet<String> live=new HashSet<>();
        if(ps!=null){
            for(int i=0;i<ps.length();i++){
                String p=ps.optString(i,"");
                if(p.isEmpty()||p.equals(self))continue;
                live.add(p);
            }
        }
        participants=Math.min(8,live.size()+1);

        if(!live.isEmpty() && ensureRtcReady()){
            for(String p:live){
                PeerState st=ensurePeer(p);
                if(st!=null&&!st.offered&&self.compareTo(p)<0)createOffer(st);
            }
        }

        for(Map.Entry<String,PeerState> e:new ArrayList<>(peers.entrySet())){
            if(!live.contains(e.getKey())){
                e.getValue().close();peers.remove(e.getKey());
            }
        }

        JSONArray sig=j.optJSONArray("signals");
        if(sig!=null && !live.isEmpty() && ensureRtcReady()){
            for(int i=0;i<sig.length();i++){
                JSONObject x=sig.optJSONObject(i);if(x==null)continue;
                lastSignalId=Math.max(lastSignalId,x.optLong("id",0));
                handleSignal(x);
            }
        }

        if(live.isEmpty()){
            shutdownRtc(false);
            ptt=false;
            status="Rádio conectado · aguardando outro motorista";
        }else if(rtcFailed){
            ptt=false;
            status="Sala conectada · áudio PTT indisponível neste aparelho";
        }else{
            status="Rádio conectado · "+participants+" no trecho";
        }

        JSONArray al=j.optJSONArray("alerts");
        alertsJson=al==null?"[]":al.toString();
        sendState();updateNotification();
    }

    private void loadIce(JSONArray a){
        iceServers.clear();
        if(a!=null)for(int i=0;i<a.length();i++){
            JSONObject o=a.optJSONObject(i);if(o==null)continue;
            String u=o.optString("url","");if(u.isEmpty())continue;
            PeerConnection.IceServer.Builder b=PeerConnection.IceServer.builder(u);
            String user=o.optString("username",""),pass=o.optString("password","");
            if(!user.isEmpty())b.setUsername(user);if(!pass.isEmpty())b.setPassword(pass);
            iceServers.add(b.createIceServer());
        }
        if(iceServers.isEmpty())iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
    }

    private boolean ensureRtcReady(){
        if(factory!=null && localTrack!=null)return true;
        if(rtcFailed)return false;
        try{
            initRtc();
            return factory!=null && localTrack!=null;
        }catch(Throwable e){
            rtcFailed=true;
            shutdownRtc(true);
            status="Sala conectada · áudio PTT indisponível neste aparelho";
            sendState();updateNotification();
            return false;
        }
    }

    private void initRtc(){
        if(factory!=null)return;
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(getApplicationContext()).createInitializationOptions());
        adm=JavaAudioDeviceModule.builder(getApplicationContext()).createAudioDeviceModule();
        factory=PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory();
        audioSource=factory.createAudioSource(new MediaConstraints());
        localTrack=factory.createAudioTrack("epp-radio-audio",audioSource);
        localTrack.setEnabled(false);
    }

    private PeerState ensurePeer(String id){
        if(id==null||id.isEmpty()||factory==null||localTrack==null)return null;
        PeerState old=peers.get(id);if(old!=null)return old;
        PeerConnection.RTCConfiguration cfg=new PeerConnection.RTCConfiguration(iceServers);
        cfg.sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN;
        PeerState st=new PeerState(id);
        PeerConnection pc=factory.createPeerConnection(cfg,new PeerConnection.Observer(){
            public void onSignalingChange(PeerConnection.SignalingState s){}
            public void onIceConnectionChange(PeerConnection.IceConnectionState s){}
            public void onIceConnectionReceivingChange(boolean b){}
            public void onIceGatheringChange(PeerConnection.IceGatheringState s){}
            public void onIceCandidate(IceCandidate c){sendSignal(id,"ice",iceJson(c));}
            public void onIceCandidatesRemoved(IceCandidate[] c){}
            public void onAddStream(MediaStream s){}
            public void onRemoveStream(MediaStream s){}
            public void onDataChannel(DataChannel d){}
            public void onRenegotiationNeeded(){}
            public void onAddTrack(RtpReceiver r,MediaStream[] streams){MediaStreamTrack t=r.track();if(t instanceof org.webrtc.AudioTrack){st.remote=(org.webrtc.AudioTrack)t;applyRemoteAudio();}}
        });
        if(pc==null)return null;
        st.pc=pc;
        try{pc.addTrack(localTrack,Collections.singletonList("epp-radio"));}catch(Throwable ignored){}
        PeerState race=peers.putIfAbsent(id,st);
        if(race!=null){st.close();return race;}
        return st;
    }

    private void createOffer(PeerState st){
        if(st==null||st.pc==null||st.offered)return;
        st.offered=true;
        st.pc.createOffer(new SDP(){@Override public void onCreateSuccess(SessionDescription d){st.pc.setLocalDescription(new SDP(){@Override public void onSetSuccess(){sendSignal(st.id,"offer",sdpJson(d));}},d);}},new MediaConstraints());
    }

    private void createAnswer(PeerState st){
        if(st==null||st.pc==null)return;
        st.pc.createAnswer(new SDP(){@Override public void onCreateSuccess(SessionDescription d){st.pc.setLocalDescription(new SDP(){@Override public void onSetSuccess(){sendSignal(st.id,"answer",sdpJson(d));}},d);}},new MediaConstraints());
    }

    private void handleSignal(JSONObject x){
        if(!ensureRtcReady())return;
        String from=x.optString("from","");String type=x.optString("type","");JSONObject p=x.optJSONObject("payload");
        if(from.isEmpty()||p==null)return;
        PeerState st=ensurePeer(from);if(st==null)return;
        if("offer".equals(type)||"answer".equals(type)){
            SessionDescription.Type ty="offer".equals(type)?SessionDescription.Type.OFFER:SessionDescription.Type.ANSWER;
            SessionDescription sd=new SessionDescription(ty,p.optString("sdp",""));
            st.pc.setRemoteDescription(new SDP(){@Override public void onSetSuccess(){flushIce(st);if("offer".equals(type))createAnswer(st);}},sd);
        }else if("ice".equals(type)){
            IceCandidate c=new IceCandidate(p.optString("mid",null),p.optInt("index",0),p.optString("candidate",""));
            if(st.pc.getRemoteDescription()==null)st.pending.add(c);else st.pc.addIceCandidate(c);
        }
    }

    private void flushIce(PeerState st){for(IceCandidate c:st.pending)try{st.pc.addIceCandidate(c);}catch(Throwable ignored){}st.pending.clear();}
    private JSONObject sdpJson(SessionDescription d){JSONObject o=new JSONObject();try{o.put("sdp",d.description);}catch(Throwable ignored){}return o;}
    private JSONObject iceJson(IceCandidate c){JSONObject o=new JSONObject();try{o.put("mid",c.sdpMid);o.put("index",c.sdpMLineIndex);o.put("candidate",c.sdp);}catch(Throwable ignored){}return o;}

    private void sendSignal(String to,String type,JSONObject payload){
        if(!joined||identity==null)return;
        io.execute(()->{try{JSONObject d=base();d.put("to",to);d.put("signal_type",type);d.put("payload",payload);api.post("api/radio.php?action=signal",d);}catch(Throwable ignored){}});
    }

    private void postAlert(String type){
        if(!joined||identity==null||type==null)return;
        try{
            JSONObject d=base();d.put("alert_type",type);
            ApiClient.Response r=api.post("api/radio.php?action=alert",d);JSONObject j=r.json();
            if(r.ok()&&j.optBoolean("ok",false))setStatus("Alerta do trecho enviado.");else setStatus(j.optString("error","Não consegui enviar o alerta."));
        }catch(Throwable e){setStatus("Sem conexão para enviar alerta.");}
    }

    private void setPtt(boolean on){
        if(!joined||muted||safetyMuted)on=false;
        if(on && peers.isEmpty()){
            ptt=false;setStatus("Aguardando outro motorista para liberar o PTT.");return;
        }
        if(on && !ensureRtcReady()){
            ptt=false;setStatus("Áudio PTT indisponível neste aparelho.");return;
        }
        ptt=on;
        if(localTrack!=null)try{localTrack.setEnabled(on);}catch(Throwable ignored){}
        sendState();updateNotification();
    }

    private void applyRemoteAudio(){
        boolean enabled=joined&&!muted&&!safetyMuted;
        for(PeerState p:peers.values())if(p.remote!=null)try{p.remote.setEnabled(enabled);}catch(Throwable ignored){}
    }

    private void leave(){
        try{if(joined&&identity!=null)api.post("api/radio.php?action=leave",base());}catch(Throwable ignored){}
        leaveNetworkOnly();identity=null;participants=0;sendState();
    }

    private void leaveNetworkOnly(){
        joined=false;ptt=false;
        shutdownRtc(false);
        participants=0;lastSignalId=0;
    }

    private void shutdownRtc(boolean preserveFailure){
        if(localTrack!=null)try{localTrack.setEnabled(false);}catch(Throwable ignored){}
        for(PeerState p:peers.values())p.close();peers.clear();
        try{if(localTrack!=null)localTrack.dispose();}catch(Throwable ignored){}localTrack=null;
        try{if(audioSource!=null)audioSource.dispose();}catch(Throwable ignored){}audioSource=null;
        try{if(factory!=null)factory.dispose();}catch(Throwable ignored){}factory=null;
        try{if(adm!=null)adm.release();}catch(Throwable ignored){}adm=null;
        if(!preserveFailure)rtcFailed=false;
    }

    private void setStatus(String s){status=s==null?"":s;sendState();updateNotification();}

    private void sendState(){
        Intent i=new Intent(ACTION_STATE).setPackage(getPackageName());
        i.putExtra("joined",joined);i.putExtra("muted",muted);i.putExtra("ptt",ptt);i.putExtra("participants",participants);
        i.putExtra("room_label",identity==null?"":identity.label);i.putExtra("selected_road",manualRoad);i.putExtra("road_mode",manualRoad.isEmpty()?"auto":"manual");
        i.putExtra("status",status+(safetyMuted?" · alerta de segurança em prioridade":""));i.putExtra("alerts_json",alertsJson);
        sendBroadcast(i);
    }

    private void createChannel(){
        if(Build.VERSION.SDK_INT<26)return;
        NotificationManager n=getSystemService(NotificationManager.class);
        if(n!=null){NotificationChannel c=new NotificationChannel(CHANNEL,"Rádio da rodovia",NotificationManager.IMPORTANCE_LOW);c.setSound(null,null);n.createNotificationChannel(c);}
    }

    private Notification notification(String title,String text){
        Intent open=new Intent(this,RoadRadioActivity.class);
        PendingIntent p=PendingIntent.getActivity(this,62,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle(title).setContentText(text).setContentIntent(p).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).build();
    }

    private void updateNotification(){
        NotificationManager n=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(n!=null)n.notify(NOTIF,notification(identity==null?"Rádio da rodovia":identity.road,ptt?"Transmitindo PTT":(joined?participants+" no trecho":"Desligado")));
    }

    @Override public void onTaskRemoved(Intent root){wanted=false;io.execute(this::leave);stopSelf();super.onTaskRemoved(root);}

    @Override public void onDestroy(){
        wanted=false;main.removeCallbacks(tick);
        if(registered)try{unregisterReceiver(roadRx);}catch(Throwable ignored){}registered=false;
        try{shutdownRtc(true);}catch(Throwable ignored){}
        io.shutdownNow();super.onDestroy();
    }

    private static final class PeerState{
        final String id;PeerConnection pc;org.webrtc.AudioTrack remote;boolean offered;final ArrayList<IceCandidate> pending=new ArrayList<>();
        PeerState(String id){this.id=id;}
        void close(){try{if(remote!=null)remote.setEnabled(false);}catch(Throwable ignored){}try{if(pc!=null){pc.close();pc.dispose();}}catch(Throwable ignored){}}
    }

    private static class SDP implements SdpObserver{
        public void onCreateSuccess(SessionDescription d){}
        public void onSetSuccess(){}
        public void onCreateFailure(String s){}
        public void onSetFailure(String s){}
    }
}

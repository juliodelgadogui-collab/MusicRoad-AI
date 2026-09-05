package com.musicroad.ai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlaybackService extends Service {
    public static final String ACTION_SET_QUEUE="com.musicroad.ai.SET_QUEUE";
    public static final String ACTION_TOGGLE="com.musicroad.ai.TOGGLE";
    public static final String ACTION_PLAY="com.musicroad.ai.PLAY";
    public static final String ACTION_PAUSE="com.musicroad.ai.PAUSE";
    public static final String ACTION_NEXT="com.musicroad.ai.NEXT";
    public static final String ACTION_PREVIOUS="com.musicroad.ai.PREVIOUS";
    public static final String ACTION_SEEK_PERCENT="com.musicroad.ai.SEEK_PERCENT";
    public static final String ACTION_VOLUME="com.musicroad.ai.VOLUME";
    public static final String ACTION_BROADCAST_STATE="com.musicroad.ai.BROADCAST_STATE";
    public static final String ACTION_SPEAK="com.musicroad.ai.SPEAK";
    public static final String ACTION_TTS_CONFIG="com.musicroad.ai.TTS_CONFIG";
    public static final String ACTION_STATE="com.musicroad.ai.STATE";
    public static final String EXTRA_QUEUE_JSON="queue";
    public static final String EXTRA_INDEX="index";
    public static final String EXTRA_AUTOPLAY="autoplay";
    public static final String EXTRA_PERCENT="percent";
    public static final String EXTRA_VOLUME="volume";
    public static final String EXTRA_TEXT="text";
    public static final String EXTRA_FORCE="force";
    public static final String EXTRA_STATE_JSON="state_json";

    private static final String CHANNEL="musicroad_playback";
    private static final int NOTIFICATION_ID=73;

    private final List<MusicTrack> queue=new ArrayList<>();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService mediaIo=Executors.newSingleThreadExecutor();
    private MediaPlayer player;
    private MediaSession mediaSession;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private TextToSpeech tts;
    private boolean ttsReady=false;
    private boolean prepared=false;
    private boolean resolving=false;
    private boolean playWhenReady=false;
    private boolean resumeOnFocusGain=false;
    private int index=-1;
    private int prepareGeneration=0;
    private int driveRetryStage=0;
    private float userVolume=1f;
    private float focusDuck=1f;
    private float alertDuck=1f;
    private String lastError="";
    private String lastErrorCode="";
    private String transportMode="";
    private long prepareStartedAt=0L;
    private long lastStartupMs=0L;

    private final AudioManager.OnAudioFocusChangeListener focusListener = focus -> {
        if (focus == AudioManager.AUDIOFOCUS_GAIN) {
            focusDuck=1f; applyVolume();
            if (resumeOnFocusGain) { resumeOnFocusGain=false; play(); }
        } else if (focus == AudioManager.AUDIOFOCUS_LOSS) {
            resumeOnFocusGain=false; pause();
        } else if (focus == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            resumeOnFocusGain=isPlaying(); pause();
        } else if (focus == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            focusDuck=.28f; applyVolume();
        }
    };

    private final Runnable stateTicker=new Runnable(){
        @Override public void run(){
            broadcastState(); updateMediaSessionState();
            if(player!=null && (player.isPlaying() || !prepared)) handler.postDelayed(this,1000);
        }
    };

    public static Intent intentAction(Context c,String action){ return new Intent(c,PlaybackService.class).setAction(action); }
    public static Intent intentSetQueue(Context c,String json,int start,boolean autoplay){ return intentAction(c,ACTION_SET_QUEUE).putExtra(EXTRA_QUEUE_JSON,json).putExtra(EXTRA_INDEX,start).putExtra(EXTRA_AUTOPLAY,autoplay); }

    @Override public void onCreate(){
        super.onCreate();
        MusicOfflineStore.init(this);
        createChannel();
        audioManager=(AudioManager)getSystemService(AUDIO_SERVICE);
        createMediaSession();
        createTts();
    }

    @Override public IBinder onBind(Intent intent){ return null; }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null)return START_STICKY;
        String a=intent.getAction();
        if(ACTION_SET_QUEUE.equals(a)) setQueue(intent.getStringExtra(EXTRA_QUEUE_JSON),intent.getIntExtra(EXTRA_INDEX,0),intent.getBooleanExtra(EXTRA_AUTOPLAY,true));
        else if(ACTION_TOGGLE.equals(a)){ if(isPlaying())pause(); else play(); }
        else if(ACTION_PLAY.equals(a))play();
        else if(ACTION_PAUSE.equals(a))pause();
        else if(ACTION_NEXT.equals(a))next();
        else if(ACTION_PREVIOUS.equals(a))previous();
        else if(ACTION_SEEK_PERCENT.equals(a))seek(intent.getDoubleExtra(EXTRA_PERCENT,0));
        else if(ACTION_VOLUME.equals(a)){ userVolume=Math.max(0f,Math.min(1f,intent.getFloatExtra(EXTRA_VOLUME,1f))); applyVolume(); }
        else if(ACTION_TTS_CONFIG.equals(a)) applyTtsPreferences();
        else if(ACTION_SPEAK.equals(a)) speakAlert(intent.getStringExtra(EXTRA_TEXT),intent.getBooleanExtra(EXTRA_FORCE,false));
        broadcastState();
        return START_STICKY;
    }

    private void setQueue(String json,int start,boolean autoplay){
        queue.clear(); lastError=""; lastErrorCode="";
        try{
            JSONArray arr=new JSONArray(json==null?"[]":json);
            for(int i=0;i<arr.length();i++){
                JSONObject o=arr.optJSONObject(i);
                if(o!=null)queue.add(MusicTrack.fromJson(o));
            }
        }catch(Exception e){lastError="Fila de reprodução inválida.";lastErrorCode="QUEUE_INVALID";}
        if(queue.isEmpty()){broadcastState();return;}
        index=Math.max(0,Math.min(start,queue.size()-1));
        driveRetryStage=0;
        prepare(autoplay,false);
    }

    private void prepare(boolean autoplay,boolean forceDriveRefresh){
        final int generation=++prepareGeneration;
        releasePlayerOnly();
        MusicTrack original=current();
        if(original==null)return;
        MusicTrack local=MusicOfflineStore.preferLocal(original);
        MusicTrack effective=local==null?original:local;
        if(effective.source==null || effective.source.trim().isEmpty()){
            lastError="Fonte de áudio não disponível.";lastErrorCode="SOURCE_EMPTY";broadcastState();handler.postDelayed(this::next,700);return;
        }

        prepared=false;
        resolving=false;
        playWhenReady=autoplay;
        lastError="";
        lastErrorCode="";
        prepareStartedAt=System.currentTimeMillis();
        lastStartupMs=0L;

        String source=effective.source.trim();
        String scheme=Uri.parse(source).getScheme();
        boolean localSource="file".equalsIgnoreCase(scheme)||"content".equalsIgnoreCase(scheme);
        if(localSource){
            transportMode="OFFLINE";
            openPlayer(effective,source,transportMode,generation);
            return;
        }

        if(DriveMediaResolver.isDriveTrack(effective)){
            resolving=true;
            transportMode="DRIVE_RESOLVING";
            startForeground(NOTIFICATION_ID,notification());
            updateMediaMetadata();
            broadcastState();
            mediaIo.execute(()->{
                DriveMediaResolver.Result result=DriveMediaResolver.resolve(getApplicationContext(),effective,forceDriveRefresh);
                handler.post(()->{
                    if(generation!=prepareGeneration)return;
                    resolving=false;
                    if(result.ok){
                        transportMode=result.mode.isEmpty()?"DRIVE_DIRECT":result.mode;
                        openPlayer(effective,result.url,transportMode,generation);
                    }else{
                        // Compatibility rescue only. No song is stored on the server; older
                        // Drive endpoints can still proxy a problematic public file.
                        lastError=result.error.isEmpty()?"Não foi possível resolver a música no Google Drive.":result.error;
                        lastErrorCode="DRIVE_RESOLVE";
                        if(driveRetryStage>=1){
                            transportMode="DRIVE_RESCUE";
                            openPlayer(effective,effective.source,transportMode,generation);
                        }else{
                            driveRetryStage=1;
                            DriveMediaResolver.invalidate(effective);
                            prepare(playWhenReady,true);
                        }
                    }
                });
            });
            return;
        }

        transportMode="HTTP";
        openPlayer(effective,source,transportMode,generation);
    }

    private void openPlayer(MusicTrack track,String source,String mode,int generation){
        if(generation!=prepareGeneration)return;
        try{
            player=new MediaPlayer();
            AudioAttributes attrs=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
            player.setAudioAttributes(attrs);
            Uri uri=Uri.parse(source);
            String scheme=uri.getScheme()==null?"":uri.getScheme().toLowerCase(Locale.ROOT);
            if("http".equals(scheme)||"https".equals(scheme)){
                Map<String,String> headers=new HashMap<>();
                headers.put("User-Agent","MusicRoadAndroid/"+BuildConfig.VERSION_NAME);
                // Cookies are sent only to the MusicRoad compatibility endpoint. Direct
                // Google media URLs are public/short-lived and do not receive app cookies.
                if(source.contains("/api/")){
                    String cookie=DriveMediaResolver.nativeCookie(this);
                    if(cookie==null||cookie.trim().isEmpty())cookie=CookieManager.getInstance().getCookie(source);
                    if(cookie!=null&&!cookie.isEmpty())headers.put("Cookie",cookie);
                }
                player.setDataSource(this,uri,headers);
            } else player.setDataSource(this,uri);
            applyVolume();
            player.setOnPreparedListener(mp->{
                if(generation!=prepareGeneration)return;
                prepared=true;
                resolving=false;
                lastStartupMs=Math.max(0L,System.currentTimeMillis()-prepareStartedAt);
                lastError="";lastErrorCode="";
                if(playWhenReady && requestAudioFocus()) mp.start();
                updateForeground(); updateMediaMetadata(); startTicker();
                prefetchNext();
            });
            player.setOnCompletionListener(mp->next());
            player.setOnErrorListener((mp,what,extra)->{
                if(generation!=prepareGeneration)return true;
                handlePlayerError(track,mode,what,extra);
                return true;
            });
            startForeground(NOTIFICATION_ID,notification());
            updateMediaMetadata();
            broadcastState();
            player.prepareAsync();
            startTicker();
        }catch(Exception e){
            handleOpenFailure(track,mode,e);
        }
    }

    private void handleOpenFailure(MusicTrack track,String mode,Exception e){
        lastError="Falha ao abrir a música: "+(e.getMessage()==null?"fonte inválida":e.getMessage());
        lastErrorCode="OPEN_FAILED";
        broadcastState();
        if(DriveMediaResolver.isDriveTrack(track)&&!"DRIVE_RESCUE".equals(mode)&&driveRetryStage<2){
            driveRetryStage++;
            DriveMediaResolver.invalidate(track);
            handler.postDelayed(()->prepare(playWhenReady,true),180);
        }else handler.postDelayed(this::next,900);
    }

    private void handlePlayerError(MusicTrack track,String mode,int what,int extra){
        lastError="Não foi possível reproduzir esta faixa.";
        lastErrorCode="MEDIA_"+what+"_"+extra;
        broadcastState();
        if(DriveMediaResolver.isDriveTrack(track)&&!"DRIVE_RESCUE".equals(mode)&&driveRetryStage<2){
            driveRetryStage++;
            DriveMediaResolver.invalidate(track);
            handler.postDelayed(()->prepare(playWhenReady,true),220);
        }else if(DriveMediaResolver.isDriveTrack(track)&&!"DRIVE_RESCUE".equals(mode)){
            int generation=++prepareGeneration;
            releasePlayerOnly();
            transportMode="DRIVE_RESCUE";
            openPlayer(track,track.source,transportMode,generation);
        }else handler.postDelayed(this::next,1000);
    }

    private void prefetchNext(){
        if(queue.size()<2)return;
        int nextIndex=(index+1)%queue.size();
        MusicTrack next=queue.get(nextIndex);
        MusicTrack local=MusicOfflineStore.preferLocal(next);
        if(local!=null&&local.source!=null&&local.source.startsWith("file://"))return;
        DriveMediaResolver.prefetch(this,next);
    }

    private MusicTrack current(){ return index>=0&&index<queue.size()?queue.get(index):null; }
    private boolean isPlaying(){ try{return player!=null&&prepared&&player.isPlaying();}catch(Exception ignored){return false;} }

    private void play(){
        playWhenReady=true;
        if(player==null){ if(!queue.isEmpty()){driveRetryStage=0;prepare(true,false);} return; }
        if(!prepared)return;
        if(!requestAudioFocus())return;
        try{player.start();lastError="";lastErrorCode="";}catch(Exception e){lastError="Falha ao iniciar reprodução.";lastErrorCode="PLAY_FAILED";}
        updateForeground();startTicker();
    }

    private void pause(){
        playWhenReady=false;
        if(player!=null&&prepared){try{player.pause();}catch(Exception ignored){}}
        updateForeground();
    }

    private void next(){
        if(queue.isEmpty())return;
        index=(index+1)%queue.size();
        driveRetryStage=0;
        prepare(true,false);
    }

    private void previous(){
        if(queue.isEmpty())return;
        try{ if(player!=null&&prepared&&player.getCurrentPosition()>5000){player.seekTo(0);broadcastState();return;} }catch(Exception ignored){}
        index=(index-1+queue.size())%queue.size();
        driveRetryStage=0;
        prepare(true,false);
    }

    private void seek(double percent){
        if(player==null||!prepared)return;
        try{int d=player.getDuration();player.seekTo((int)(d*Math.max(0,Math.min(100,percent))/100d));broadcastState();}catch(Exception ignored){}
    }

    private void applyVolume(){
        if(player==null)return;
        float v=Math.max(0f,Math.min(1f,userVolume*focusDuck*alertDuck));
        try{player.setVolume(v,v);}catch(Exception ignored){}
    }

    private boolean requestAudioFocus(){
        if(audioManager==null)return true;
        if(Build.VERSION.SDK_INT>=26){
            if(focusRequest==null){
                AudioAttributes attrs=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
                focusRequest=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs).setOnAudioFocusChangeListener(focusListener).setAcceptsDelayedFocusGain(false).setWillPauseWhenDucked(false).build();
            }
            return audioManager.requestAudioFocus(focusRequest)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
        return audioManager.requestAudioFocus(focusListener,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonAudioFocus(){
        if(audioManager==null)return;
        if(Build.VERSION.SDK_INT>=26&&focusRequest!=null)audioManager.abandonAudioFocusRequest(focusRequest);
        else audioManager.abandonAudioFocus(focusListener);
    }

    private SharedPreferences alertPrefs(){return getSharedPreferences(NavigationService.ALERT_PREFS,MODE_PRIVATE);}
    private void createTts(){
        tts=new TextToSpeech(getApplicationContext(),status->{
            ttsReady=status==TextToSpeech.SUCCESS;
            if(ttsReady){applyTtsPreferences();tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
                @Override public void onStart(String utteranceId){ }
                @Override public void onDone(String utteranceId){handler.post(PlaybackService.this::restoreAfterAlert);}
                @Override public void onError(String utteranceId){handler.post(PlaybackService.this::restoreAfterAlert);}
            });}
        });
    }
    private void applyTtsPreferences(){if(!ttsReady||tts==null)return;SharedPreferences p=alertPrefs();try{tts.setLanguage(new Locale("pt","BR"));String name=p.getString("voiceName","");if(name!=null&&!name.isEmpty()){Set<Voice> voices=tts.getVoices();if(voices!=null)for(Voice v:voices)if(name.equals(v.getName())){tts.setVoice(v);break;}}tts.setSpeechRate(Math.max(.6f,Math.min(1.6f,p.getFloat("voiceRate",1f))));tts.setPitch(Math.max(.6f,Math.min(1.5f,p.getFloat("voicePitch",1f))));}catch(Exception ignored){}}

    private void speakAlert(String text,boolean force){
        if(text==null||text.trim().isEmpty())return;if(!force&&!alertPrefs().getBoolean("voice",true))return;applyTtsPreferences();
        alertDuck=.24f;applyVolume();
        if(ttsReady&&tts!=null){
            String id="mr-alert-"+System.currentTimeMillis();
            tts.speak(text,TextToSpeech.QUEUE_FLUSH,null,id);
            handler.removeCallbacks(restoreAlertFallback);
            handler.postDelayed(restoreAlertFallback,12000);
        } else {
            handler.removeCallbacks(restoreAlertFallback);
            handler.postDelayed(restoreAlertFallback,1800);
        }
    }

    private final Runnable restoreAlertFallback=this::restoreAfterAlert;
    private void restoreAfterAlert(){ handler.removeCallbacks(restoreAlertFallback);alertDuck=1f;applyVolume(); }

    private void createMediaSession(){
        mediaSession=new MediaSession(this,"MusicRoadPlayback");
        mediaSession.setCallback(new MediaSession.Callback(){
            @Override public void onPlay(){play();}
            @Override public void onPause(){pause();}
            @Override public void onSkipToNext(){next();}
            @Override public void onSkipToPrevious(){previous();}
            @Override public void onSeekTo(long pos){if(player!=null&&prepared)try{player.seekTo((int)Math.max(0,pos));}catch(Exception ignored){}}
        });
        mediaSession.setActive(true);
    }

    private void updateMediaMetadata(){
        if(mediaSession==null)return;
        MusicTrack t=current();
        if(t==null)return;
        MediaMetadata.Builder b=new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,t.title).putString(MediaMetadata.METADATA_KEY_ARTIST,t.artist).putString(MediaMetadata.METADATA_KEY_ALBUM,t.album);
        if(t.durationMs>0)b.putLong(MediaMetadata.METADATA_KEY_DURATION,t.durationMs);
        mediaSession.setMetadata(b.build());
    }

    private void updateMediaSessionState(){
        if(mediaSession==null)return;
        long pos=0;try{if(player!=null&&prepared)pos=player.getCurrentPosition();}catch(Exception ignored){}
        int state=player==null?(resolving?PlaybackState.STATE_BUFFERING:PlaybackState.STATE_STOPPED):(!prepared?PlaybackState.STATE_BUFFERING:(isPlaying()?PlaybackState.STATE_PLAYING:PlaybackState.STATE_PAUSED));
        long actions=PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_PLAY_PAUSE|PlaybackState.ACTION_SKIP_TO_NEXT|PlaybackState.ACTION_SKIP_TO_PREVIOUS|PlaybackState.ACTION_SEEK_TO;
        mediaSession.setPlaybackState(new PlaybackState.Builder().setActions(actions).setState(state,pos,isPlaying()?1f:0f).build());
    }

    private void createChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel c=new NotificationChannel(CHANNEL,"Reprodução MusicRoad",NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Player e controles de bordo do MusicRoad");c.setShowBadge(false);
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
    }

    private PendingIntent action(String action,int code){ Intent i=intentAction(this,action);return PendingIntent.getService(this,code,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE); }

    private Notification notification(){
        MusicTrack t=current();String title=t==null?"MusicRoad AI":t.title;String artist=t==null?"Controle de bordo":t.artist;
        Intent open=new Intent(this,MainActivity.class);open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content=PendingIntent.getActivity(this,1,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        String subtitle=artist+(transportMode.isEmpty()?"":" · "+transportLabel());
        b.setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(subtitle).setContentIntent(content).setOngoing(isPlaying()).setShowWhen(false)
            .addAction(new Notification.Action.Builder(R.drawable.ic_notification,"Anterior",action(ACTION_PREVIOUS,2)).build())
            .addAction(new Notification.Action.Builder(R.drawable.ic_notification,isPlaying()?"Pausar":"Tocar",action(ACTION_TOGGLE,3)).build())
            .addAction(new Notification.Action.Builder(R.drawable.ic_notification,"Próxima",action(ACTION_NEXT,4)).build());
        if(Build.VERSION.SDK_INT>=21&&mediaSession!=null)b.setStyle(new Notification.MediaStyle().setMediaSession(mediaSession.getSessionToken()).setShowActionsInCompactView(0,1,2));
        return b.build();
    }

    private String transportLabel(){
        if("OFFLINE".equals(transportMode))return "OFFLINE";
        if(transportMode.startsWith("DRIVE"))return "DRIVE";
        return "ONLINE";
    }

    private void updateForeground(){
        if(player!=null||resolving)startForeground(NOTIFICATION_ID,notification());
        updateMediaSessionState();broadcastState();
    }

    private void startTicker(){handler.removeCallbacks(stateTicker);handler.post(stateTicker);}

    private void broadcastState(){
        try{
            JSONObject o=new JSONObject();MusicTrack t=current();boolean playing=isPlaying();
            o.put("playing",playing);o.put("prepared",prepared);o.put("resolving",resolving);o.put("index",index);o.put("count",queue.size());
            o.put("source_mode",transportMode);o.put("source_label",transportLabel());o.put("startup_ms",lastStartupMs);
            if(t!=null){
                o.put("id",t.id);o.put("title",t.title);o.put("artist",t.artist);o.put("album",t.album);o.put("origin",t.origin);
                JSONObject track=t.toJson();
                track.put("playing",playing);track.put("prepared",prepared);track.put("resolving",resolving);track.put("source_mode",transportMode);track.put("source_label",transportLabel());
                String displayArtist=t.artist==null||t.artist.trim().isEmpty()?"MusicRoad":t.artist;
                String state=playing?"Tocando":(prepared?"Pausado":"Carregando");
                track.put("artist",displayArtist+" · "+state+(transportMode.isEmpty()?"":" · "+transportLabel()));
                o.put("track",track);
            }
            long pos=0,dur=0;
            if(player!=null&&prepared){try{pos=player.getCurrentPosition();dur=player.getDuration();}catch(Exception ignored){}}
            o.put("positionMs",pos);o.put("durationMs",dur);o.put("position_ms",pos);o.put("duration_ms",dur);
            if(!lastError.isEmpty())o.put("error",lastError);
            if(!lastErrorCode.isEmpty())o.put("error_code",lastErrorCode);
            Intent i=new Intent(ACTION_STATE);i.setPackage(getPackageName());i.putExtra(EXTRA_STATE_JSON,o.toString());sendBroadcast(i);
        }catch(Exception ignored){}
    }

    private void releasePlayerOnly(){
        handler.removeCallbacks(stateTicker);
        if(player!=null){try{player.stop();}catch(Exception ignored){}try{player.reset();}catch(Exception ignored){}try{player.release();}catch(Exception ignored){}player=null;}
        prepared=false;
        resolving=false;
    }

    private void releasePlayer(){
        ++prepareGeneration;
        releasePlayerOnly();
    }

    @Override public void onDestroy(){
        releasePlayer();restoreAfterAlert();abandonAudioFocus();
        mediaIo.shutdownNow();
        if(tts!=null){try{tts.stop();tts.shutdown();}catch(Exception ignored){}}
        if(mediaSession!=null){mediaSession.setActive(false);mediaSession.release();}
        stopForeground(true);super.onDestroy();
    }
}

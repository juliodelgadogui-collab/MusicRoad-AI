package com.musicroad.ai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

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
    public static final String ACTION_STATE="com.musicroad.ai.STATE";
    public static final String EXTRA_QUEUE_JSON="queue";
    public static final String EXTRA_INDEX="index";
    public static final String EXTRA_AUTOPLAY="autoplay";
    public static final String EXTRA_PERCENT="percent";
    public static final String EXTRA_VOLUME="volume";
    public static final String EXTRA_STATE_JSON="state_json";
    private static final String CHANNEL="musicroad_playback";
    private static final int NOTIFICATION_ID=73;

    private final List<MusicTrack> queue=new ArrayList<>();
    private MediaPlayer player;
    private int index=-1;
    private float volume=1f;

    public static Intent intentAction(Context c,String action){ return new Intent(c,PlaybackService.class).setAction(action); }
    public static Intent intentSetQueue(Context c,String json,int start,boolean autoplay){ return intentAction(c,ACTION_SET_QUEUE).putExtra(EXTRA_QUEUE_JSON,json).putExtra(EXTRA_INDEX,start).putExtra(EXTRA_AUTOPLAY,autoplay); }

    @Override public void onCreate(){ super.onCreate(); createChannel(); }
    @Override public IBinder onBind(Intent intent){ return null; }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null)return START_NOT_STICKY;
        String a=intent.getAction();
        if(ACTION_SET_QUEUE.equals(a)) setQueue(intent.getStringExtra(EXTRA_QUEUE_JSON),intent.getIntExtra(EXTRA_INDEX,0),intent.getBooleanExtra(EXTRA_AUTOPLAY,true));
        else if(ACTION_TOGGLE.equals(a)){ if(player!=null&&player.isPlaying())pause(); else play(); }
        else if(ACTION_PLAY.equals(a))play();
        else if(ACTION_PAUSE.equals(a))pause();
        else if(ACTION_NEXT.equals(a))next();
        else if(ACTION_PREVIOUS.equals(a))previous();
        else if(ACTION_SEEK_PERCENT.equals(a))seek(intent.getDoubleExtra(EXTRA_PERCENT,0));
        else if(ACTION_VOLUME.equals(a)){ volume=Math.max(0f,Math.min(1f,intent.getFloatExtra(EXTRA_VOLUME,1f))); if(player!=null)player.setVolume(volume,volume); }
        broadcastState();
        return START_NOT_STICKY;
    }

    private void setQueue(String json,int start,boolean autoplay){
        queue.clear();
        try{ JSONArray arr=new JSONArray(json==null?"[]":json); for(int i=0;i<arr.length();i++){JSONObject o=arr.optJSONObject(i);if(o!=null)queue.add(MusicTrack.fromJson(o));} }catch(Exception ignored){}
        if(queue.isEmpty()){stopSelf();return;} index=Math.max(0,Math.min(start,queue.size()-1)); prepare(autoplay);
    }

    private void prepare(boolean autoplay){
        releasePlayer(); MusicTrack t=current(); if(t==null)return;
        try{
            player=new MediaPlayer(); player.setDataSource(this,Uri.parse(t.source)); player.setVolume(volume,volume);
            player.setOnPreparedListener(mp->{ if(autoplay)mp.start(); updateForeground(); broadcastState(); });
            player.setOnCompletionListener(mp->next()); player.setOnErrorListener((mp,what,extra)->{next();return true;});
            player.prepareAsync(); startForeground(NOTIFICATION_ID,notification(false));
        }catch(Exception e){ next(); }
    }

    private MusicTrack current(){ return index>=0&&index<queue.size()?queue.get(index):null; }
    private void play(){ if(player!=null){try{player.start();}catch(Exception ignored){} updateForeground();} }
    private void pause(){ if(player!=null){try{player.pause();}catch(Exception ignored){} updateForeground();} }
    private void next(){ if(queue.isEmpty())return; index=(index+1)%queue.size(); prepare(true); }
    private void previous(){ if(queue.isEmpty())return; index=(index-1+queue.size())%queue.size(); prepare(true); }
    private void seek(double percent){ if(player==null)return; try{int d=player.getDuration();player.seekTo((int)(d*Math.max(0,Math.min(100,percent))/100d));}catch(Exception ignored){} }

    private void createChannel(){ if(Build.VERSION.SDK_INT>=26){ NotificationChannel c=new NotificationChannel(CHANNEL,"Reprodução MusicRoad",NotificationManager.IMPORTANCE_LOW); c.setDescription("Controles do player MusicRoad"); ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c); } }
    private PendingIntent action(String action,int code){ Intent i=intentAction(this,action); return PendingIntent.getService(this,code,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE); }
    private Notification notification(boolean ignored){
        MusicTrack t=current(); String title=t==null?"MusicRoad AI":t.title; String artist=t==null?"Player":t.artist;
        Intent open=new Intent(this,MainActivity.class); PendingIntent content=PendingIntent.getActivity(this,1,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(artist).setContentIntent(content).setOngoing(player!=null&&player.isPlaying()).setShowWhen(false)
                .addAction(new Notification.Action.Builder(R.drawable.ic_notification,"Anterior",action(ACTION_PREVIOUS,2)).build())
                .addAction(new Notification.Action.Builder(R.drawable.ic_notification,player!=null&&player.isPlaying()?"Pausar":"Tocar",action(ACTION_TOGGLE,3)).build())
                .addAction(new Notification.Action.Builder(R.drawable.ic_notification,"Próxima",action(ACTION_NEXT,4)).build());
        return b.build();
    }
    private void updateForeground(){ startForeground(NOTIFICATION_ID,notification(false)); broadcastState(); }

    private void broadcastState(){
        try{
            JSONObject o=new JSONObject(); MusicTrack t=current(); boolean playing=player!=null&&player.isPlaying();
            o.put("playing",playing);o.put("index",index);o.put("count",queue.size());
            if(t!=null){o.put("id",t.id);o.put("title",t.title);o.put("artist",t.artist);o.put("album",t.album);}
            if(player!=null){try{o.put("position_ms",player.getCurrentPosition());o.put("duration_ms",player.getDuration());}catch(Exception ignored){}}
            Intent i=new Intent(ACTION_STATE);i.setPackage(getPackageName());i.putExtra(EXTRA_STATE_JSON,o.toString());sendBroadcast(i);
        }catch(Exception ignored){}
    }

    private void releasePlayer(){ if(player!=null){try{player.stop();}catch(Exception ignored){}try{player.release();}catch(Exception ignored){}player=null;} }
    @Override public void onDestroy(){ releasePlayer(); stopForeground(true); super.onDestroy(); }
}

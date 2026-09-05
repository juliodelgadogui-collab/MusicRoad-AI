package com.musicroad.ai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Downloads selected Google Drive folders directly to the phone. */
public final class OfflineFolderDownloadService extends Service {
    private static final String CHANNEL="musicroad_offline_music";
    private static final int NOTIFICATION_ID=94;
    private static final String EXTRA_FOLDERS="folders";
    public static final String ACTION_PROGRESS="com.musicroad.ai.OFFLINE_FOLDER_PROGRESS";
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private volatile boolean running=false;

    static Intent startIntent(Context c,ArrayList<String> folders){
        return new Intent(c,OfflineFolderDownloadService.class).putStringArrayListExtra(EXTRA_FOLDERS,folders);
    }

    @Override public void onCreate(){super.onCreate();createChannel();}
    @Override public IBinder onBind(Intent intent){return null;}

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        ArrayList<String> folders=intent==null?null:intent.getStringArrayListExtra(EXTRA_FOLDERS);
        if(folders==null||folders.isEmpty()){stopSelf(startId);return START_NOT_STICKY;}
        startForeground(NOTIFICATION_ID,notification("Preparando músicas offline…",0,0,false));
        if(running)return START_REDELIVER_INTENT;
        running=true;
        ArrayList<String> copy=new ArrayList<>(folders);
        io.execute(()->runDownload(copy,startId));
        return START_REDELIVER_INTENT;
    }

    private void runDownload(ArrayList<String> folders,int startId){
        Set<String> wanted=new HashSet<>();for(String f:folders)wanted.add(normalize(f));
        OfflineFolderManager.loadDriveTracks(this,(tracks,error)->{
            if(error!=null){finishWith("Falha: "+error,startId);return;}
            io.execute(()->downloadTracks(tracks,wanted,folders,startId));
        });
    }

    private void downloadTracks(List<MusicTrack> tracks,Set<String>wanted,List<String>originalFolders,int startId){
        ArrayList<MusicTrack> selected=new ArrayList<>();
        for(MusicTrack t:tracks)if(wanted.contains(normalize(OfflineFolderManager.folderKey(t))))selected.add(t);
        if(selected.isEmpty()){finishWith("Nenhuma música encontrada nas pastas escolhidas.",startId);return;}
        int ok=0,failed=0,skipped=0,total=selected.size();
        for(int i=0;i<selected.size();i++){
            MusicTrack t=selected.get(i);
            if(MusicOfflineStore.isDownloaded(t)){
                skipped++;
                update("Já offline: "+t.title,i+1,total,false);
                continue;
            }
            final int position=i+1;
            MusicDirectDownload.Result result=MusicDirectDownload.downloadBlocking(this,t,(done,size)->{
                int pct=size>0?(int)Math.min(99,(done*100L)/size):0;
                update("Baixando "+t.title+(size>0?" · "+pct+"%":""),position-1,total,false);
            });
            if(result.ok)ok++;else failed++;
            update((result.ok?"Offline: ":"Falhou: ")+t.title,position,total,false);
            NativeAppGuard.invalidateMusicUi();
        }
        OfflineFolderManager.rememberSelected(this,originalFolders);
        String msg="Concluído · "+ok+" baixadas"+(skipped>0?" · "+skipped+" já estavam offline":"")+(failed>0?" · "+failed+" falharam":"");
        update(msg,total,total,true);
        NativeAppGuard.invalidateMusicUi();
        running=false;
        stopForeground(false);
        stopSelf(startId);
    }

    private void finishWith(String message,int startId){
        update(message,0,0,true);running=false;stopForeground(false);stopSelf(startId);
    }

    private void update(String text,int done,int total,boolean finished){
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(nm!=null)nm.notify(NOTIFICATION_ID,notification(text,done,total,finished));
        Intent i=new Intent(ACTION_PROGRESS).setPackage(getPackageName());
        i.putExtra("message",text);i.putExtra("done",done);i.putExtra("total",total);i.putExtra("finished",finished);sendBroadcast(i);
    }

    private Notification notification(String text,int done,int total,boolean finished){
        Intent open=new Intent(this,MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi=PendingIntent.getActivity(this,94,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_notification).setContentTitle(finished?"Músicas offline":"Baixando músicas offline").setContentText(text)
                .setContentIntent(pi).setOnlyAlertOnce(true).setOngoing(!finished).setShowWhen(false);
        if(total>0&&!finished)b.setProgress(total,Math.max(0,Math.min(total,done)),false);else if(!finished)b.setProgress(0,0,true);
        return b.build();
    }

    private void createChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel c=new NotificationChannel(CHANNEL,"Downloads de músicas",NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Pastas de músicas baixadas para uso offline");c.setShowBadge(false);
            NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);if(nm!=null)nm.createNotificationChannel(c);
        }
    }

    private static String normalize(String s){return s==null?"":s.replace('\\','/').replace(" / ","/").replaceAll("/+","/").trim().toLowerCase(Locale.ROOT);}

    @Override public void onDestroy(){running=false;io.shutdownNow();super.onDestroy();}
}

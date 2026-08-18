package com.musicroad.ai;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import java.util.Locale;

public final class NativeBridge {
    private final MainActivity activity;
    NativeBridge(MainActivity activity){this.activity=activity;}

    @JavascriptInterface public String platform(){return "android";}
    @JavascriptInterface public String version(){return BuildConfig.VERSION_NAME;}
    @JavascriptInterface public int versionCode(){return BuildConfig.VERSION_CODE;}
    @JavascriptInterface public boolean hasAudioPermission(){return NativeMusicRepository.hasPermission(activity);}
    @JavascriptInterface public void requestAudioPermission(){activity.runOnUiThread(activity::requestAudioPermission);}
    @JavascriptInterface public String getMusicLibrary(){return NativeMusicRepository.scanAsJson(activity).toString();}

    @JavascriptInterface public void scanMusicLibraryAsync(){dispatchLibraryAsync(0);}

    private WebView findWebView(){
        try{
            View content=activity.findViewById(android.R.id.content);if(!(content instanceof ViewGroup))return null;
            ViewGroup group=(ViewGroup)content;
            for(int i=0;i<group.getChildCount();i++){View child=group.getChildAt(i);if(child instanceof WebView)return (WebView)child;}
        }catch(Exception ignored){}
        return null;
    }
    private void dispatchJs(String js){WebView web=findWebView();if(web!=null)web.evaluateJavascript(js,null);}
    private void dispatchLibraryAsync(long delayMs){
        new Thread(() -> {
            try{if(delayMs>0)Thread.sleep(delayMs);}catch(InterruptedException ignored){}
            final String json=NativeMusicRepository.scanAsJson(activity).toString();
            activity.runOnUiThread(() -> dispatchJs("document.dispatchEvent(new CustomEvent('mr:native-library',{detail:JSON.parse("+org.json.JSONObject.quote(json)+")}));"));
        },"MusicRoad-MediaStore").start();
    }

    @JavascriptInterface public long downloadForOffline(String url,String filename,String mimeType){
        if(url==null||url.trim().isEmpty())return -1;
        try{
            String safe=(filename==null||filename.trim().isEmpty()?"musica":filename).replaceAll("[\\\\/:*?\"<>|]+","_").trim();
            String mt=mimeType==null?"":mimeType.toLowerCase(Locale.ROOT);
            if(!safe.matches("(?i).*\\.(mp3|m4a|aac|flac|wav|ogg|opus)$"))safe+=mt.contains("flac")?".flac":mt.contains("wav")?".wav":mt.contains("ogg")?".ogg":mt.contains("mp4")||mt.contains("m4a")?".m4a":".mp3";
            DownloadManager.Request req=new DownloadManager.Request(Uri.parse(url));
            if(!mt.isEmpty())req.setMimeType(mimeType);
            String cookie=CookieManager.getInstance().getCookie(url);if(cookie!=null&&!cookie.isEmpty())req.addRequestHeader("Cookie",cookie);
            req.addRequestHeader("User-Agent","MusicRoadAndroid/4.2");
            req.setTitle(safe);req.setDescription("MusicRoad · música offline");req.setAllowedOverMetered(true);req.setAllowedOverRoaming(true);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC,"MusicRoad/"+safe);
            DownloadManager dm=(DownloadManager)activity.getSystemService(Context.DOWNLOAD_SERVICE);long id=dm.enqueue(req);
            final long target=id;
            BroadcastReceiver receiver=new BroadcastReceiver(){
                @Override public void onReceive(Context context,Intent intent){
                    if(!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())||intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID,-1)!=target)return;
                    try{activity.unregisterReceiver(this);}catch(Exception ignored){}
                    activity.runOnUiThread(()->{Toast.makeText(activity,"Música salva em Música/MusicRoad para ouvir offline.",Toast.LENGTH_SHORT).show();dispatchJs("document.dispatchEvent(new CustomEvent('mr:download-complete'));" );});
                    dispatchLibraryAsync(1400);
                }
            };
            IntentFilter filter=new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
            if(Build.VERSION.SDK_INT>=33)activity.registerReceiver(receiver,filter,Context.RECEIVER_EXPORTED);else activity.registerReceiver(receiver,filter);
            activity.runOnUiThread(()->Toast.makeText(activity,"Baixando música para o celular...",Toast.LENGTH_SHORT).show());
            return id;
        }catch(Exception e){activity.runOnUiThread(()->Toast.makeText(activity,"Não foi possível iniciar o download.",Toast.LENGTH_SHORT).show());return -1;}
    }

    @JavascriptInterface public void requestAppUpdate(String url,String version){activity.runOnUiThread(()->activity.requestAppUpdate(url,version));}
    @JavascriptInterface public void haptic(){activity.runOnUiThread(() -> {try{activity.getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);}catch(Exception ignored){}});}

    @JavascriptInterface public void playQueue(String queueJson,int startIndex){activity.startService(PlaybackService.intentSetQueue(activity,queueJson,startIndex,true));}
    @JavascriptInterface public void togglePlay(){activity.startService(PlaybackService.intentAction(activity,PlaybackService.ACTION_TOGGLE));}
    @JavascriptInterface public void play(){activity.startService(PlaybackService.intentAction(activity,PlaybackService.ACTION_PLAY));}
    @JavascriptInterface public void pause(){activity.startService(PlaybackService.intentAction(activity,PlaybackService.ACTION_PAUSE));}
    @JavascriptInterface public void next(){activity.startService(PlaybackService.intentAction(activity,PlaybackService.ACTION_NEXT));}
    @JavascriptInterface public void previous(){activity.startService(PlaybackService.intentAction(activity,PlaybackService.ACTION_PREVIOUS));}
    @JavascriptInterface public void seekToPercent(double percent){Intent i=PlaybackService.intentAction(activity,PlaybackService.ACTION_SEEK_PERCENT);i.putExtra(PlaybackService.EXTRA_PERCENT,Math.max(0.0,Math.min(100.0,percent)));activity.startService(i);}
    @JavascriptInterface public void setPlayerVolume(double volume){Intent i=PlaybackService.intentAction(activity,PlaybackService.ACTION_VOLUME);i.putExtra(PlaybackService.EXTRA_VOLUME,(float)Math.max(0.0,Math.min(1.0,volume)));activity.startService(i);}
    @JavascriptInterface public void speakAlert(String text){Intent i=PlaybackService.intentAction(activity,PlaybackService.ACTION_SPEAK);i.putExtra(PlaybackService.EXTRA_TEXT,text);activity.startService(i);}
    @JavascriptInterface public void requestPlaybackState(){activity.startService(PlaybackService.intentAction(activity,PlaybackService.ACTION_BROADCAST_STATE));}
    @JavascriptInterface public void setTripMode(boolean active){activity.runOnUiThread(()->activity.setTripMode(active));}
    @JavascriptInterface public void openNativeLibrary(){activity.runOnUiThread(()->activity.startActivity(new Intent(activity,LocalLibraryActivity.class)));}
}

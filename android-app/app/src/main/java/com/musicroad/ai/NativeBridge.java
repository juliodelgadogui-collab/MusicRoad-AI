package com.musicroad.ai;

import android.Manifest;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

public final class NativeBridge {
    private static final String DOWNLOAD_PREFS="musicroad_offline_downloads_v1";
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
            activity.runOnUiThread(() -> dispatchJs("document.dispatchEvent(new CustomEvent('mr:native-library',{detail:JSON.parse("+JSONObject.quote(json)+")}));"));
        },"MusicRoad-MediaStore").start();
    }

    private SharedPreferences downloadPrefs(){return activity.getSharedPreferences(DOWNLOAD_PREFS,Context.MODE_PRIVATE);}
    private static String pkey(String prefix,String key){return prefix+(key==null?"":key);}

    private JSONObject offlineDownloadState(String key){
        JSONObject out=new JSONObject();
        try{
            SharedPreferences prefs=downloadPrefs();
            long id=prefs.getLong(pkey("id:",key),-1);
            boolean done=prefs.getBoolean(pkey("done:",key),false);
            if(id<=0){out.put("state",done?"downloaded":"none");out.put("progress",done?100:0);return out;}
            DownloadManager dm=(DownloadManager)activity.getSystemService(Context.DOWNLOAD_SERVICE);
            try(Cursor c=dm.query(new DownloadManager.Query().setFilterById(id))){
                if(c==null||!c.moveToFirst()){
                    out.put("state",done?"downloaded":"none");out.put("progress",done?100:0);return out;
                }
                int status=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                long bytes=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                long total=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                int progress=total>0?(int)Math.max(0,Math.min(100,(bytes*100L)/total)):0;
                if(status==DownloadManager.STATUS_SUCCESSFUL){
                    prefs.edit().putBoolean(pkey("done:",key),true).apply();out.put("state","downloaded");out.put("progress",100);
                }else if(status==DownloadManager.STATUS_RUNNING){out.put("state","downloading");out.put("progress",Math.max(1,progress));}
                else if(status==DownloadManager.STATUS_PENDING){out.put("state","downloading");out.put("progress",Math.max(1,progress));}
                else if(status==DownloadManager.STATUS_PAUSED){out.put("state","paused");out.put("progress",progress);}
                else if(status==DownloadManager.STATUS_FAILED){prefs.edit().remove(pkey("id:",key)).putBoolean(pkey("done:",key),false).apply();out.put("state","failed");out.put("progress",0);}
                else{out.put("state",done?"downloaded":"none");out.put("progress",done?100:progress);}
            }
        }catch(Exception e){try{out.put("state","none");out.put("progress",0);}catch(Exception ignored){}}
        return out;
    }

    @JavascriptInterface public String getOfflineDownloadStates(String keysJson){
        JSONObject out=new JSONObject();
        try{
            JSONArray keys=new JSONArray(keysJson==null?"[]":keysJson);
            for(int i=0;i<keys.length();i++){
                String key=keys.optString(i,"");if(key.isEmpty())continue;
                out.put(key,offlineDownloadState(key));
            }
        }catch(Exception ignored){}
        return out.toString();
    }

    private long enqueueOfflineDownload(String url,String filename,String mimeType,String offlineKey){
        if(url==null||url.trim().isEmpty())return -1;
        try{
            if(offlineKey!=null&&!offlineKey.isEmpty()){
                JSONObject existing=offlineDownloadState(offlineKey);
                if("downloaded".equals(existing.optString("state")))return downloadPrefs().getLong(pkey("id:",offlineKey),-1);
                if("downloading".equals(existing.optString("state"))||"paused".equals(existing.optString("state")))return downloadPrefs().getLong(pkey("id:",offlineKey),-1);
            }
            String safe=(filename==null||filename.trim().isEmpty()?"musica":filename).replaceAll("[\\\\/:*?\"<>|]+","_").trim();
            String mt=mimeType==null?"":mimeType.toLowerCase(Locale.ROOT);
            if(!safe.matches("(?i).*\\.(mp3|m4a|aac|flac|wav|ogg|opus)$"))safe+=mt.contains("flac")?".flac":mt.contains("wav")?".wav":mt.contains("ogg")?".ogg":mt.contains("mp4")||mt.contains("m4a")?".m4a":".mp3";
            DownloadManager.Request req=new DownloadManager.Request(Uri.parse(url));
            if(!mt.isEmpty())req.setMimeType(mimeType);
            String cookie=CookieManager.getInstance().getCookie(url);if(cookie!=null&&!cookie.isEmpty())req.addRequestHeader("Cookie",cookie);
            req.addRequestHeader("User-Agent","MusicRoadAndroid/"+BuildConfig.VERSION_NAME);
            req.setTitle(safe);req.setDescription("MusicRoad · música offline");req.setAllowedOverMetered(true);req.setAllowedOverRoaming(true);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC,"MusicRoad/"+safe);
            DownloadManager dm=(DownloadManager)activity.getSystemService(Context.DOWNLOAD_SERVICE);long id=dm.enqueue(req);
            if(offlineKey!=null&&!offlineKey.isEmpty())downloadPrefs().edit().putLong(pkey("id:",offlineKey),id).putBoolean(pkey("done:",offlineKey),false).putString(pkey("file:",offlineKey),safe).apply();
            final long target=id;final String targetKey=offlineKey;
            BroadcastReceiver receiver=new BroadcastReceiver(){
                @Override public void onReceive(Context context,Intent intent){
                    if(!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())||intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID,-1)!=target)return;
                    try{activity.unregisterReceiver(this);}catch(Exception ignored){}
                    JSONObject state=targetKey==null?new JSONObject():offlineDownloadState(targetKey);
                    String status=state.optString("state","downloaded");
                    if(targetKey!=null&&!targetKey.isEmpty()&&"downloaded".equals(status))downloadPrefs().edit().putBoolean(pkey("done:",targetKey),true).apply();
                    activity.runOnUiThread(()->{
                        if("downloaded".equals(status))Toast.makeText(activity,"Música baixada ✓ pronta para ouvir offline.",Toast.LENGTH_SHORT).show();
                        else Toast.makeText(activity,"O download da música não foi concluído.",Toast.LENGTH_SHORT).show();
                        String detail=targetKey==null?"{}":"{key:"+JSONObject.quote(targetKey)+",state:"+JSONObject.quote(status)+",progress:"+("downloaded".equals(status)?100:0)+"}";
                        dispatchJs("document.dispatchEvent(new CustomEvent('mr:download-complete',{detail:"+detail+"}));");
                    });
                    if("downloaded".equals(status))dispatchLibraryAsync(1100);
                }
            };
            IntentFilter filter=new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
            if(Build.VERSION.SDK_INT>=33)activity.registerReceiver(receiver,filter,Context.RECEIVER_EXPORTED);else activity.registerReceiver(receiver,filter);
            activity.runOnUiThread(()->Toast.makeText(activity,"Baixando para Música/MusicRoad...",Toast.LENGTH_SHORT).show());
            return id;
        }catch(Exception e){activity.runOnUiThread(()->Toast.makeText(activity,"Não foi possível iniciar o download.",Toast.LENGTH_SHORT).show());return -1;}
    }

    @JavascriptInterface public long downloadForOffline(String url,String filename,String mimeType){return enqueueOfflineDownload(url,filename,mimeType,null);}
    @JavascriptInterface public long downloadForOfflineTrack(String url,String filename,String mimeType,String offlineKey){return enqueueOfflineDownload(url,filename,mimeType,offlineKey);}

    @JavascriptInterface public boolean startNavigation(String routeJson,String hazardsJson,String speedLimitsJson,String destination){
        if(Build.VERSION.SDK_INT>=23 && activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED && activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            activity.runOnUiThread(()->Toast.makeText(activity,"Permita o GPS para ativar o motor de bordo.",Toast.LENGTH_LONG).show());
            return false;
        }
        try{
            Intent i=NavigationService.startIntent(activity,routeJson,hazardsJson,speedLimitsJson,destination);
            if(Build.VERSION.SDK_INT>=26)activity.startForegroundService(i);else activity.startService(i);
            return true;
        }catch(Exception e){activity.runOnUiThread(()->Toast.makeText(activity,"Não foi possível iniciar o motor de bordo.",Toast.LENGTH_SHORT).show());return false;}
    }
    @JavascriptInterface public void updateNavigation(String hazardsJson,String speedLimitsJson){try{activity.startService(NavigationService.updateIntent(activity,hazardsJson,speedLimitsJson));}catch(Exception ignored){}}
    @JavascriptInterface public void stopNavigation(){try{activity.startService(NavigationService.stopIntent(activity));}catch(Exception ignored){activity.stopService(new Intent(activity,NavigationService.class));}}

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

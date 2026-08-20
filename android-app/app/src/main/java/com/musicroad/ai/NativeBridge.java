package com.musicroad.ai;

import android.Manifest;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.security.MessageDigest;

public final class NativeBridge {
    private static final String DOWNLOAD_PREFS="musicroad_offline_downloads_v1";
    private static final String[] ALERT_BOOLEAN_KEYS={"voice","visual","system","vibrate","maneuvers","speeding","m300","m200","m100","m50","front","radar","portable","video","signal","bump"};
    private final MainActivity activity;
    private final OfflineStore offlineStore;
    NativeBridge(MainActivity activity){this.activity=activity;this.offlineStore=new OfflineStore(activity);}

    @JavascriptInterface public String getTrialDeviceToken(){return DeviceIdentity.token(activity);}
    @JavascriptInterface public String platform(){return "android";}
    @JavascriptInterface public String version(){return BuildConfig.VERSION_NAME;}
    @JavascriptInterface public int versionCode(){return BuildConfig.VERSION_CODE;}
    @JavascriptInterface public String deviceName(){return DeviceIdentity.label();}
    @JavascriptInterface public String getServerUrl(){return activity.getConfiguredServerUrl();}
    @JavascriptInterface public boolean setServerUrl(String url){return activity.setConfiguredServerUrl(url);}
    @JavascriptInterface public boolean isSetupComplete(){return activity.isSetupComplete();}
    @JavascriptInterface public void setSetupComplete(boolean done){activity.setSetupComplete(done);}
    @JavascriptInterface public boolean isNetworkAvailable(){return activity.isNetworkAvailable();}
    @JavascriptInterface public void pingServer(){activity.deviceAuthAsync("ping",DeviceIdentity.token(activity));}
    @JavascriptInterface public void autoLoginDevice(){activity.deviceAuthAsync("login",DeviceIdentity.token(activity));}
    @JavascriptInterface public void removeRegisteredDevice(String csrf){activity.deviceAuthAsync("remove",DeviceIdentity.token(activity),csrf);}
    @JavascriptInterface public void openOnlineApp(){activity.openRemotePath("");}
    @JavascriptInterface public void openLogin(){activity.openRemotePath("login.php");}
    @JavascriptInterface public void openRegister(){activity.openRemotePath("register.php");}
    @JavascriptInterface public void openOfflineCore(){activity.openOfflineCore();}
    @JavascriptInterface public boolean storeOfflinePack(String key,String json){return offlineStore.put(key,json);}
    @JavascriptInterface public String readOfflinePack(String key){return offlineStore.get(key);}
    @JavascriptInterface public boolean offlinePackExists(String key){return offlineStore.has(key);}
    @JavascriptInterface public String listOfflinePackKeys(){return offlineStore.listJson();}
    @JavascriptInterface public boolean removeOfflineState(String uf){if(uf==null)return false;return offlineStore.removePrefix("state/"+uf.toUpperCase(Locale.ROOT)+"/");}
    @JavascriptInterface public void saveAccountSnapshot(String json){offlineStore.saveAccount(json);}
    @JavascriptInterface public String getAccountSnapshot(){return offlineStore.account();}
    @JavascriptInterface public void saveLastRouteSnapshot(String json){offlineStore.saveRoute(json);}
    @JavascriptInterface public String getLastRouteSnapshot(){return offlineStore.route();}
    @JavascriptInterface public void setLandscapeMode(boolean enabled){activity.runOnUiThread(()->{try{activity.setRequestedOrientation(enabled?android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE:android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);}catch(Exception ignored){}});}
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
    private SharedPreferences alertPrefs(){return activity.getSharedPreferences(NavigationService.ALERT_PREFS,Context.MODE_PRIVATE);}
    private static String pkey(String prefix,String key){return prefix+(key==null?"":key);}

    @JavascriptInterface public void setAlertPreferences(String json){try{JSONObject o=new JSONObject(json==null?"{}":json);SharedPreferences.Editor e=alertPrefs().edit();for(String key:ALERT_BOOLEAN_KEYS)if(o.has(key))e.putBoolean(key,o.optBoolean(key,true));if(o.has("voiceName"))e.putString("voiceName",o.optString("voiceName",""));if(o.has("voiceRate"))e.putFloat("voiceRate",(float)Math.max(.6,Math.min(1.6,o.optDouble("voiceRate",1))));if(o.has("voicePitch"))e.putFloat("voicePitch",(float)Math.max(.6,Math.min(1.5,o.optDouble("voicePitch",1))));e.apply();activity.startService(PlaybackService.intentAction(activity,PlaybackService.ACTION_TTS_CONFIG));}catch(Exception ignored){}}
    @JavascriptInterface public void setTtsPreferences(String voiceName,double rate,double pitch){alertPrefs().edit().putString("voiceName",voiceName==null?"":voiceName).putFloat("voiceRate",(float)Math.max(.6,Math.min(1.6,rate))).putFloat("voicePitch",(float)Math.max(.6,Math.min(1.5,pitch))).apply();activity.startService(PlaybackService.intentAction(activity,PlaybackService.ACTION_TTS_CONFIG));}
    @JavascriptInterface public void testTtsVoice(String text){Intent i=PlaybackService.intentAction(activity,PlaybackService.ACTION_SPEAK);i.putExtra(PlaybackService.EXTRA_TEXT,text==null?"Olá. Esta é a voz do copiloto MusicRoad.":text);i.putExtra(PlaybackService.EXTRA_FORCE,true);activity.startService(i);}
    @JavascriptInterface public void requestTtsVoices(){activity.runOnUiThread(()->{final TextToSpeech[] holder=new TextToSpeech[1];holder[0]=new TextToSpeech(activity.getApplicationContext(),status->{JSONArray out=new JSONArray();try{if(status==TextToSpeech.SUCCESS&&holder[0]!=null){Set<Voice> set=holder[0].getVoices();List<Voice> voices=new ArrayList<>();if(set!=null)for(Voice v:set){Locale loc=v.getLocale();if(loc!=null&&"pt".equalsIgnoreCase(loc.getLanguage()))voices.add(v);}voices.sort(Comparator.comparing((Voice v)->v.isNetworkConnectionRequired()).thenComparing(Voice::getName));for(Voice v:voices){JSONObject o=new JSONObject();o.put("name",v.getName());o.put("locale",v.getLocale()==null?"pt-BR":v.getLocale().toLanguageTag());o.put("network",v.isNetworkConnectionRequired());out.put(o);}}}catch(Exception ignored){}dispatchJs("document.dispatchEvent(new CustomEvent('mr:tts-voices',{detail:{voices:"+out.toString()+"}}));");try{if(holder[0]!=null)holder[0].shutdown();}catch(Exception ignored){}});});}

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
            Uri source=Uri.parse(url);
            if(!activity.isTrustedAppUri(source))return -1;
            if(offlineKey!=null&&!offlineKey.isEmpty()){
                JSONObject existing=offlineDownloadState(offlineKey);
                if("downloaded".equals(existing.optString("state")))return downloadPrefs().getLong(pkey("id:",offlineKey),-1);
                if("downloading".equals(existing.optString("state"))||"paused".equals(existing.optString("state")))return downloadPrefs().getLong(pkey("id:",offlineKey),-1);
            }
            String safe=(filename==null||filename.trim().isEmpty()?"musica":filename).replaceAll("[\\\\/:*?\"<>|]+","_").trim();
            String mt=mimeType==null?"":mimeType.toLowerCase(Locale.ROOT);
            if(!safe.matches("(?i).*\\.(mp3|m4a|aac|flac|wav|ogg|opus)$"))safe+=mt.contains("flac")?".flac":mt.contains("wav")?".wav":mt.contains("ogg")?".ogg":mt.contains("mp4")||mt.contains("m4a")?".m4a":".mp3";
            DownloadManager.Request req=new DownloadManager.Request(source);
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

    private JSONObject findFmRadioApp(){JSONObject out=new JSONObject();try{PackageManager pm=activity.getPackageManager();Intent q=new Intent(Intent.ACTION_MAIN);q.addCategory(Intent.CATEGORY_LAUNCHER);List<ResolveInfo> apps=pm.queryIntentActivities(q,PackageManager.MATCH_DEFAULT_ONLY);ResolveInfo best=null;String labelBest="";for(ResolveInfo r:apps){String pkg=r.activityInfo==null?"":r.activityInfo.packageName;if(pkg.equals(activity.getPackageName()))continue;String label=String.valueOf(r.loadLabel(pm));String l=label.toLowerCase(Locale.ROOT),pk=pkg.toLowerCase(Locale.ROOT);boolean match=l.contains("rádio")||l.contains("radio")||l.equals("fm")||l.startsWith("fm ")||l.endsWith(" fm")||pk.contains("fmradio")||pk.contains("fm.radio")||pk.endsWith(".radio");if(match){best=r;labelBest=label;break;}}if(best!=null&&best.activityInfo!=null){out.put("available",true);out.put("label",labelBest);out.put("package",best.activityInfo.packageName);out.put("class",best.activityInfo.name);}else out.put("available",false);}catch(Exception e){try{out.put("available",false);}catch(Exception ignored){}}return out;}
    @JavascriptInterface public String getFmRadioStatus(){return findFmRadioApp().toString();}
    @JavascriptInterface public boolean openFmRadioApp(){JSONObject f=findFmRadioApp();if(!f.optBoolean("available",false))return false;try{String pkg=f.optString("package",""),cls=f.optString("class","");Intent launch=activity.getPackageManager().getLaunchIntentForPackage(pkg);if(launch==null&&!cls.isEmpty()){launch=new Intent(Intent.ACTION_MAIN);launch.addCategory(Intent.CATEGORY_LAUNCHER);launch.setClassName(pkg,cls);}if(launch==null)return false;launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);Intent finalLaunch=launch;activity.runOnUiThread(()->{try{activity.startActivity(finalLaunch);}catch(Exception e){Toast.makeText(activity,"Não foi possível abrir o rádio FM do aparelho.",Toast.LENGTH_SHORT).show();}});return true;}catch(Exception e){return false;}}

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
    @JavascriptInterface public void requestAppUpdateWithHash(String url,String version,String sha256){activity.runOnUiThread(()->activity.requestAppUpdate(url,version,sha256));}
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

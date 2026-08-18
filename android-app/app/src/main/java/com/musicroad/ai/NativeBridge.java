package com.musicroad.ai;

import android.content.Intent;
import android.webkit.JavascriptInterface;

public final class NativeBridge {
    private final MainActivity activity;
    NativeBridge(MainActivity activity){this.activity=activity;}

    @JavascriptInterface public String platform(){return "android";}
    @JavascriptInterface public String version(){return BuildConfig.VERSION_NAME;}
    @JavascriptInterface public boolean hasAudioPermission(){return NativeMusicRepository.hasPermission(activity);}
    @JavascriptInterface public void requestAudioPermission(){activity.runOnUiThread(activity::requestAudioPermission);}
    @JavascriptInterface public String getMusicLibrary(){return NativeMusicRepository.scanAsJson(activity).toString();}

    @JavascriptInterface public void playQueue(String queueJson,int startIndex){activity.startService(PlaybackService.intentSetQueue(activity,queueJson,startIndex,true));}
    @JavascriptInterface public void togglePlay(){activity.startService(PlaybackService.intentAction(activity,PlaybackService.ACTION_TOGGLE));}
    @JavascriptInterface public void play(){activity.startService(PlaybackService.intentAction(activity,PlaybackService.ACTION_PLAY));}
    @JavascriptInterface public void pause(){activity.startService(PlaybackService.intentAction(activity,PlaybackService.ACTION_PAUSE));}
    @JavascriptInterface public void next(){activity.startService(PlaybackService.intentAction(activity,PlaybackService.ACTION_NEXT));}
    @JavascriptInterface public void previous(){activity.startService(PlaybackService.intentAction(activity,PlaybackService.ACTION_PREVIOUS));}

    @JavascriptInterface public void seekToPercent(double percent){
        Intent i=PlaybackService.intentAction(activity,PlaybackService.ACTION_SEEK_PERCENT);
        i.putExtra(PlaybackService.EXTRA_PERCENT,Math.max(0.0,Math.min(100.0,percent)));activity.startService(i);
    }
    @JavascriptInterface public void setPlayerVolume(double volume){
        Intent i=PlaybackService.intentAction(activity,PlaybackService.ACTION_VOLUME);
        i.putExtra(PlaybackService.EXTRA_VOLUME,(float)Math.max(0.0,Math.min(1.0,volume)));activity.startService(i);
    }
    @JavascriptInterface public void speakAlert(String text){
        Intent i=PlaybackService.intentAction(activity,PlaybackService.ACTION_SPEAK);i.putExtra(PlaybackService.EXTRA_TEXT,text);activity.startService(i);
    }
    @JavascriptInterface public void requestPlaybackState(){activity.startService(PlaybackService.intentAction(activity,PlaybackService.ACTION_BROADCAST_STATE));}
    @JavascriptInterface public void setTripMode(boolean active){activity.runOnUiThread(()->activity.setTripMode(active));}
    @JavascriptInterface public void openNativeLibrary(){activity.runOnUiThread(()->activity.startActivity(new Intent(activity,LocalLibraryActivity.class)));}
}

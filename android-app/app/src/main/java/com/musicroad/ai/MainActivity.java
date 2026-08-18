package com.musicroad.ai;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_LOCATION=1001;
    private static final int REQ_FILES=1002;
    private static final int REQ_AUDIO=1003;
    private static final int REQ_NOTIFICATIONS=1004;
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private GeolocationPermissions.Callback geoCallback;
    private String geoOrigin;
    private long lastBackPressedAt=0;

    private final BroadcastReceiver playbackReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            if(!PlaybackService.ACTION_STATE.equals(intent.getAction())||webView==null)return;
            String json=intent.getStringExtra(PlaybackService.EXTRA_STATE_JSON);if(json==null)return;
            runOnUiThread(()->webView.evaluateJavascript("if(window.Player&&Player.onNativeState){Player.onNativeState("+json+");}",null));
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(8,17,31));
        getWindow().setNavigationBarColor(Color.rgb(5,12,22));
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        webView=new WebView(this);
        webView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
        webView.setOverScrollMode(android.view.View.OVER_SCROLL_NEVER);
        setContentView(webView);
        configureWebView();registerPlaybackReceiver();requestNotificationPermissionIfNeeded();
        if(savedInstanceState==null){
            String url=BuildConfig.MUSICROAD_URL;
            if(url.contains("SEU-DOMINIO")){Toast.makeText(this,"Configure MUSICROAD_URL. A biblioteca nativa continua disponível offline.",Toast.LENGTH_LONG).show();webView.loadUrl("file:///android_asset/offline.html?unconfigured=1");}
            else webView.loadUrl(url);
        }else webView.restoreState(savedInstanceState);
    }

    private void configureWebView(){
        CookieManager cookies=CookieManager.getInstance();cookies.setAcceptCookie(true);cookies.setAcceptThirdPartyCookies(webView,true);
        WebSettings s=webView.getSettings();
        s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setDatabaseEnabled(true);s.setGeolocationEnabled(true);
        s.setAllowFileAccess(true);s.setAllowContentAccess(true);s.setMediaPlaybackRequiresUserGesture(false);s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);s.setDisplayZoomControls(false);s.setUseWideViewPort(true);s.setLoadWithOverviewMode(false);s.setTextZoom(100);
        s.setUserAgentString(s.getUserAgentString()+" MusicRoadAndroid/3.0");
        if(Build.VERSION.SDK_INT>=26)s.setSafeBrowsingEnabled(true);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        webView.addJavascriptInterface(new NativeBridge(this),"MusicRoadAndroid");
        webView.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){
                Uri uri=request.getUrl();String scheme=uri.getScheme()==null?"":uri.getScheme().toLowerCase(Locale.ROOT);
                if(("http".equals(scheme)||"https".equals(scheme))&&isTrustedAppUri(uri))return false;
                try{startActivity(new Intent(Intent.ACTION_VIEW,uri));}catch(ActivityNotFoundException ex){Toast.makeText(MainActivity.this,"Não há aplicativo para abrir este link.",Toast.LENGTH_SHORT).show();}
                return true;
            }
            @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){
                super.onReceivedError(view,request,error);if(request.isForMainFrame())view.loadUrl("file:///android_asset/offline.html?url="+Uri.encode(BuildConfig.MUSICROAD_URL));
            }
            @Override public void onPageFinished(WebView view,String url){
                super.onPageFinished(view,url);
                view.evaluateJavascript("document.documentElement.classList.add('native-app');window.MusicRoadNativeInfo={platform:'android',version:'"+BuildConfig.VERSION_NAME+"',mediaStore:true,nativePlayer:true,cockpit:true};document.dispatchEvent(new CustomEvent('mr:native-ready'));",null);
                startService(PlaybackService.intentAction(MainActivity.this,PlaybackService.ACTION_BROADCAST_STATE));
            }
        });
        webView.setWebChromeClient(new WebChromeClient(){
            @Override public void onGeolocationPermissionsShowPrompt(String origin,GeolocationPermissions.Callback callback){
                if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED){callback.invoke(origin,true,false);return;}
                geoOrigin=origin;geoCallback=callback;requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},REQ_LOCATION);
            }
            @Override public boolean onShowFileChooser(WebView webView,ValueCallback<Uri[]> cb,FileChooserParams params){
                if(fileCallback!=null)fileCallback.onReceiveValue(null);fileCallback=cb;
                Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("audio/*");i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
                try{startActivityForResult(Intent.createChooser(i,"Escolher músicas"),REQ_FILES);}catch(ActivityNotFoundException ex){fileCallback.onReceiveValue(null);fileCallback=null;}return true;
            }
        });
        webView.setDownloadListener((url,userAgent,contentDisposition,mimeType,contentLength)->{
            try{DownloadManager.Request r=new DownloadManager.Request(Uri.parse(url));r.setMimeType(mimeType);String cookie=CookieManager.getInstance().getCookie(url);if(cookie!=null)r.addRequestHeader("Cookie",cookie);r.addRequestHeader("User-Agent",userAgent);String fn=URLUtil.guessFileName(url,contentDisposition,mimeType);r.setTitle(fn);r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,fn);((DownloadManager)getSystemService(DOWNLOAD_SERVICE)).enqueue(r);}catch(Exception ignored){}
        });
    }

    private boolean isTrustedAppUri(Uri uri){
        try{Uri base=Uri.parse(BuildConfig.MUSICROAD_URL);if(base.getHost()==null||uri.getHost()==null)return false;return normalizedPort(base)==normalizedPort(uri)&&base.getHost().equalsIgnoreCase(uri.getHost())&&base.getScheme()!=null&&base.getScheme().equalsIgnoreCase(uri.getScheme());}catch(Exception ignored){return false;}
    }
    private int normalizedPort(Uri uri){if(uri.getPort()!=-1)return uri.getPort();if("https".equalsIgnoreCase(uri.getScheme()))return 443;if("http".equalsIgnoreCase(uri.getScheme()))return 80;return -1;}
    private boolean isAtAppRoot(){
        try{Uri u=Uri.parse(webView.getUrl());Uri base=Uri.parse(BuildConfig.MUSICROAD_URL);if(u.getHost()==null||base.getHost()==null||!u.getHost().equalsIgnoreCase(base.getHost()))return false;String path=u.getPath()==null?"/":u.getPath();String basePath=base.getPath()==null?"/":base.getPath();return path.equals(basePath)||path.equals(basePath+"index.php")||path.equals("/index.php");}catch(Exception ignored){return false;}
    }

    void requestAudioPermission(){if(NativeMusicRepository.hasPermission(this)){dispatchAudioPermission(true);return;}if(Build.VERSION.SDK_INT>=23)requestPermissions(new String[]{NativeMusicRepository.permissionName()},REQ_AUDIO);}
    private void dispatchAudioPermission(boolean granted){if(webView!=null)webView.evaluateJavascript("document.dispatchEvent(new CustomEvent('mr:native-audio-permission',{detail:{granted:"+granted+"}}));",null);}
    private void requestNotificationPermissionIfNeeded(){if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},REQ_NOTIFICATIONS);}
    void setTripMode(boolean active){if(active)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}

    private void registerPlaybackReceiver(){IntentFilter f=new IntentFilter(PlaybackService.ACTION_STATE);if(Build.VERSION.SDK_INT>=33)registerReceiver(playbackReceiver,f,RECEIVER_NOT_EXPORTED);else registerReceiver(playbackReceiver,f);}
    @Override protected void onSaveInstanceState(Bundle outState){webView.saveState(outState);super.onSaveInstanceState(outState);}
    @Override protected void onResume(){super.onResume();if(webView!=null)startService(PlaybackService.intentAction(this,PlaybackService.ACTION_BROADCAST_STATE));}

    @Override public void onBackPressed(){
        if(webView==null){super.onBackPressed();return;}
        webView.evaluateJavascript("(function(){try{return !!(window.MusicRoadHandleBack&&window.MusicRoadHandleBack());}catch(e){return false;}})()",value->{
            boolean handled="true".equals(value)||"\"true\"".equals(value);
            if(!handled)runOnUiThread(this::handleBackFallback);
        });
    }
    private void handleBackFallback(){
        if(webView.canGoBack()&&!isAtAppRoot()){webView.goBack();return;}
        long now=System.currentTimeMillis();
        if(now-lastBackPressedAt<2200){finishAndRemoveTask();return;}
        lastBackPressedAt=now;Toast.makeText(this,"Toque em Voltar novamente para sair do MusicRoad.",Toast.LENGTH_SHORT).show();
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);if(requestCode!=REQ_FILES||fileCallback==null)return;Uri[] results=null;
        if(resultCode==RESULT_OK&&data!=null){if(data.getClipData()!=null){int count=data.getClipData().getItemCount();results=new Uri[count];for(int i=0;i<count;i++)results[i]=data.getClipData().getItemAt(i).getUri();}else if(data.getData()!=null)results=new Uri[]{data.getData()};}
        fileCallback.onReceiveValue(results);fileCallback=null;
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if(requestCode==REQ_LOCATION&&geoCallback!=null){boolean g=grantResults.length>0&&grantResults[0]==PackageManager.PERMISSION_GRANTED;geoCallback.invoke(geoOrigin,g,false);geoCallback=null;geoOrigin=null;}
        else if(requestCode==REQ_AUDIO){boolean g=grantResults.length>0&&grantResults[0]==PackageManager.PERMISSION_GRANTED;dispatchAudioPermission(g);}
    }

    @Override protected void onDestroy(){try{unregisterReceiver(playbackReceiver);}catch(Exception ignored){}setTripMode(false);if(webView!=null){webView.loadUrl("about:blank");webView.stopLoading();webView.removeJavascriptInterface("MusicRoadAndroid");webView.destroy();}super.onDestroy();}
}

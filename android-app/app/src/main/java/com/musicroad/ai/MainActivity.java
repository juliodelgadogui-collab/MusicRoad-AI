package com.musicroad.ai;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
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

import java.io.File;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_LOCATION=1001;
    private static final int REQ_FILES=1002;
    private static final int REQ_AUDIO=1003;
    private static final int REQ_NOTIFICATIONS=1004;
    private static final String PREFS="musicroad_native";
    private static final String PREF_UPDATE_ID="update_download_id";
    private static final String APK_MIME="application/vnd.android.package-archive";

    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private GeolocationPermissions.Callback geoCallback;
    private String geoOrigin;
    private long lastBackPressedAt=0;
    private long updateDownloadId=-1;
    private String pendingUpdateUrl;
    private String pendingUpdateVersion="";

    private final BroadcastReceiver playbackReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            if(!PlaybackService.ACTION_STATE.equals(intent.getAction())||webView==null)return;
            String json=intent.getStringExtra(PlaybackService.EXTRA_STATE_JSON);if(json==null)return;
            runOnUiThread(()->webView.evaluateJavascript("if(window.Player&&Player.onNativeState){Player.onNativeState("+json+");}",null));
        }
    };

    private final BroadcastReceiver updateReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            if(!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction()))return;
            long id=intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID,-1);
            long expected=updateDownloadId;
            if(expected<=0)expected=getPreferencesStore().getLong(PREF_UPDATE_ID,-1);
            if(id>0&&id==expected)runOnUiThread(()->installDownloadedUpdate(id));
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(8,17,31));
        getWindow().setNavigationBarColor(Color.rgb(5,12,22));
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        webView=new WebView(this);
        webView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
        webView.setBackgroundColor(Color.rgb(8,17,31));
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        applySystemBarInsets(webView);
        setContentView(webView);
        configureWebView();registerPlaybackReceiver();registerUpdateReceiver();requestNotificationPermissionIfNeeded();
        updateDownloadId=getPreferencesStore().getLong(PREF_UPDATE_ID,-1);
        if(savedInstanceState==null){
            String url=BuildConfig.MUSICROAD_URL;
            if(url.contains("SEU-DOMINIO")){Toast.makeText(this,"Configure MUSICROAD_URL. A biblioteca nativa continua disponível offline.",Toast.LENGTH_LONG).show();webView.loadUrl("file:///android_asset/offline.html?unconfigured=1");}
            else webView.loadUrl(url);
        }else webView.restoreState(savedInstanceState);
    }

    private SharedPreferences getPreferencesStore(){return getSharedPreferences(PREFS,MODE_PRIVATE);}

    private void applySystemBarInsets(View view){
        view.setOnApplyWindowInsetsListener((v,insets)->{
            int left,top,right,bottom;
            if(Build.VERSION.SDK_INT>=30){
                Insets bars=insets.getInsets(WindowInsets.Type.systemBars());
                left=bars.left;top=bars.top;right=bars.right;bottom=bars.bottom;
            }else{
                left=insets.getSystemWindowInsetLeft();top=insets.getSystemWindowInsetTop();
                right=insets.getSystemWindowInsetRight();bottom=insets.getSystemWindowInsetBottom();
            }
            v.setPadding(left,top,right,bottom);
            return insets;
        });
        view.requestApplyInsets();
    }

    private void configureWebView(){
        CookieManager cookies=CookieManager.getInstance();cookies.setAcceptCookie(true);cookies.setAcceptThirdPartyCookies(webView,true);
        WebSettings s=webView.getSettings();
        s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setDatabaseEnabled(true);s.setGeolocationEnabled(true);
        s.setAllowFileAccess(true);s.setAllowContentAccess(true);s.setMediaPlaybackRequiresUserGesture(false);s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);s.setDisplayZoomControls(false);s.setUseWideViewPort(false);s.setLoadWithOverviewMode(false);s.setTextZoom(100);
        s.setUserAgentString(s.getUserAgentString()+" MusicRoadAndroid/4.2");
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
                super.onPageFinished(view,url);injectNativeUi(view);
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

    private void injectNativeUi(WebView view){
        String js="(function(){document.documentElement.classList.add('native-app');window.MusicRoadNativeInfo={platform:'android',version:'"+BuildConfig.VERSION_NAME+"',versionCode:"+BuildConfig.VERSION_CODE+",mediaStore:true,nativePlayer:true,cockpit:true,appUpdate:true};var old=document.getElementById('mr-native-ui');if(old)old.remove();document.dispatchEvent(new CustomEvent('mr:native-ready'));})();";
        view.evaluateJavascript(js,null);
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

    void requestAppUpdate(String url,String version){
        Uri uri;
        try{uri=Uri.parse(url);}catch(Exception e){Toast.makeText(this,"URL de atualização inválida.",Toast.LENGTH_SHORT).show();return;}
        if(!isTrustedAppUri(uri)){Toast.makeText(this,"Atualização recusada: origem não confiável.",Toast.LENGTH_LONG).show();return;}
        pendingUpdateUrl=url;pendingUpdateVersion=version==null?"":version;
        if(Build.VERSION.SDK_INT>=26&&!getPackageManager().canRequestPackageInstalls()){
            Toast.makeText(this,"Autorize o MusicRoad a instalar atualizações e volte ao app.",Toast.LENGTH_LONG).show();
            try{startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())));}catch(Exception e){Toast.makeText(this,"Abra as configurações e permita instalar apps desta fonte.",Toast.LENGTH_LONG).show();}
            return;
        }
        startAppUpdateDownload(url,pendingUpdateVersion);
    }

    private void startAppUpdateDownload(String url,String version){
        try{
            File dir=getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);if(dir!=null){File old=new File(dir,"MusicRoad-AI-update.apk");if(old.exists())old.delete();}
            DownloadManager.Request r=new DownloadManager.Request(Uri.parse(url));r.setMimeType(APK_MIME);
            String cookie=CookieManager.getInstance().getCookie(url);if(cookie!=null&&!cookie.isEmpty())r.addRequestHeader("Cookie",cookie);
            r.addRequestHeader("User-Agent","MusicRoadAndroid/4.2");
            r.setTitle(version==null||version.isEmpty()?"Atualização MusicRoad":"MusicRoad "+version);
            r.setDescription("Baixando atualização do aplicativo");
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setAllowedOverMetered(true);r.setAllowedOverRoaming(true);
            r.setDestinationInExternalFilesDir(this,Environment.DIRECTORY_DOWNLOADS,"MusicRoad-AI-update.apk");
            DownloadManager dm=(DownloadManager)getSystemService(DOWNLOAD_SERVICE);updateDownloadId=dm.enqueue(r);
            getPreferencesStore().edit().putLong(PREF_UPDATE_ID,updateDownloadId).apply();
            pendingUpdateUrl=null;pendingUpdateVersion="";
            dispatchWebEvent("mr:update-download-started");
            Toast.makeText(this,"Baixando atualização do MusicRoad...",Toast.LENGTH_SHORT).show();
        }catch(Exception e){Toast.makeText(this,"Não foi possível iniciar a atualização.",Toast.LENGTH_LONG).show();}
    }

    private void installDownloadedUpdate(long id){
        DownloadManager dm=(DownloadManager)getSystemService(DOWNLOAD_SERVICE);
        DownloadManager.Query q=new DownloadManager.Query().setFilterById(id);
        try(Cursor c=dm.query(q)){
            if(c==null||!c.moveToFirst())return;
            int status=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if(status==DownloadManager.STATUS_FAILED){clearUpdateDownload();Toast.makeText(this,"Falha ao baixar a atualização.",Toast.LENGTH_LONG).show();return;}
            if(status!=DownloadManager.STATUS_SUCCESSFUL)return;
        }catch(Exception e){return;}
        Uri apk=dm.getUriForDownloadedFile(id);if(apk==null){clearUpdateDownload();return;}
        try{
            Intent install=new Intent(Intent.ACTION_VIEW);install.setDataAndType(apk,APK_MIME);install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);
            dispatchWebEvent("mr:update-installing");clearUpdateDownload();startActivity(install);
        }catch(ActivityNotFoundException e){Toast.makeText(this,"Instalador do Android não encontrado.",Toast.LENGTH_LONG).show();}
    }

    private void clearUpdateDownload(){updateDownloadId=-1;getPreferencesStore().edit().remove(PREF_UPDATE_ID).apply();}
    private void dispatchWebEvent(String name){if(webView!=null)webView.evaluateJavascript("document.dispatchEvent(new CustomEvent('"+name+"'));",null);}

    private void registerPlaybackReceiver(){IntentFilter f=new IntentFilter(PlaybackService.ACTION_STATE);if(Build.VERSION.SDK_INT>=33)registerReceiver(playbackReceiver,f,RECEIVER_NOT_EXPORTED);else registerReceiver(playbackReceiver,f);}
    private void registerUpdateReceiver(){IntentFilter f=new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);if(Build.VERSION.SDK_INT>=33)registerReceiver(updateReceiver,f,RECEIVER_EXPORTED);else registerReceiver(updateReceiver,f);}
    @Override protected void onSaveInstanceState(Bundle outState){webView.saveState(outState);super.onSaveInstanceState(outState);}
    @Override protected void onResume(){
        super.onResume();if(webView!=null)startService(PlaybackService.intentAction(this,PlaybackService.ACTION_BROADCAST_STATE));
        if(pendingUpdateUrl!=null&&(Build.VERSION.SDK_INT<26||getPackageManager().canRequestPackageInstalls())){String u=pendingUpdateUrl,v=pendingUpdateVersion;pendingUpdateUrl=null;pendingUpdateVersion="";startAppUpdateDownload(u,v);}
        long id=updateDownloadId>0?updateDownloadId:getPreferencesStore().getLong(PREF_UPDATE_ID,-1);if(id>0)installDownloadedUpdate(id);
    }

    @Override public void onBackPressed(){
        if(webView==null){super.onBackPressed();return;}
        webView.evaluateJavascript("(function(){try{return !!(window.MusicRoadHandleBack&&window.MusicRoadHandleBack());}catch(e){return false;}})()",value->{
            boolean handled="true".equals(value)||"\"true\"".equals(value);if(!handled)runOnUiThread(this::handleBackFallback);
        });
    }
    private void handleBackFallback(){
        if(webView.canGoBack()&&!isAtAppRoot()){webView.goBack();return;}
        long now=System.currentTimeMillis();if(now-lastBackPressedAt<2200){finishAndRemoveTask();return;}lastBackPressedAt=now;Toast.makeText(this,"Toque em Voltar novamente para sair do MusicRoad.",Toast.LENGTH_SHORT).show();
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

    @Override protected void onDestroy(){try{unregisterReceiver(playbackReceiver);}catch(Exception ignored){}try{unregisterReceiver(updateReceiver);}catch(Exception ignored){}setTripMode(false);if(webView!=null){webView.loadUrl("about:blank");webView.stopLoading();webView.removeJavascriptInterface("MusicRoadAndroid");webView.destroy();}super.onDestroy();}
}

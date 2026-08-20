from pathlib import Path
import re

ROOT=Path(__file__).resolve().parents[1]
APP=ROOT/'app'
JAVA=APP/'src/main/java/com/musicroad/ai'
ASSETS=APP/'src/main/assets'


def rep(path,old,new,label):
    s=path.read_text()
    if old not in s:
        raise SystemExit(f'1.1 offline patch failed: {label} not found in {path.name}')
    path.write_text(s.replace(old,new,1))


def ensure_import(path, anchor, import_line):
    s=path.read_text()
    if import_line in s:return
    if anchor not in s:raise SystemExit(f'import anchor missing {path.name}: {anchor}')
    path.write_text(s.replace(anchor,anchor+'\n'+import_line,1))


def patch_version():
    p=ROOT/'app/build.gradle';s=p.read_text()
    s=re.sub(r'versionCode\s+\d+','versionCode 25',s,count=1)
    s=re.sub(r"versionName\s+'[^']+'","versionName '1.1.0'",s,count=1)
    p.write_text(s)


def write_device_identity():
    JAVA.joinpath('DeviceIdentity.java').write_text(r'''package com.musicroad.ai;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.provider.Settings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

final class DeviceIdentity {
    private DeviceIdentity(){}
    static String sha256(byte[] data){try{MessageDigest d=MessageDigest.getInstance("SHA-256");byte[] b=d.digest(data);StringBuilder out=new StringBuilder();for(byte x:b)out.append(String.format(Locale.ROOT,"%02x",x&0xff));return out.toString();}catch(Exception e){return Integer.toHexString(java.util.Arrays.hashCode(data));}}
    static String sha256(String value){return sha256((value==null?"":value).getBytes(StandardCharsets.UTF_8));}
    static String signingDigest(Context c){try{PackageManager pm=c.getPackageManager();PackageInfo pi;if(Build.VERSION.SDK_INT>=28){pi=pm.getPackageInfo(c.getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES);Signature[] a=pi.signingInfo==null?null:pi.signingInfo.getApkContentsSigners();if(a!=null&&a.length>0)return sha256(a[0].toByteArray());}else{pi=pm.getPackageInfo(c.getPackageName(),PackageManager.GET_SIGNATURES);if(pi.signatures!=null&&pi.signatures.length>0)return sha256(pi.signatures[0].toByteArray());}}catch(Exception ignored){}return "nosig";}
    static String token(Context c){try{String id=Settings.Secure.getString(c.getContentResolver(),Settings.Secure.ANDROID_ID);if(id==null||id.trim().isEmpty()){android.content.SharedPreferences p=c.getSharedPreferences("musicroad_trial_identity",Context.MODE_PRIVATE);id=p.getString("fallback","");if(id.isEmpty()){id=UUID.randomUUID().toString();p.edit().putString("fallback",id).apply();}}return "android:"+sha256(c.getPackageName()+"|"+signingDigest(c)+"|"+id);}catch(Exception e){return "";}}
    static String label(){String m=(Build.MANUFACTURER==null?"":Build.MANUFACTURER.trim()),model=(Build.MODEL==null?"":Build.MODEL.trim());String x=(m+" "+model).trim();return x.isEmpty()?"Android":x;}
}
''')


def write_offline_store():
    JAVA.joinpath('OfflineStore.java').write_text(r'''package com.musicroad.ai;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

final class OfflineStore {
    private static final String PREFS="musicroad_offline_native_v1",KEYS="keys";
    private final Context context;
    OfflineStore(Context c){context=c.getApplicationContext();}
    private File root(){File d=new File(context.getFilesDir(),"offline-packs");if(!d.exists())d.mkdirs();return d;}
    private static boolean valid(String k){return k!=null&&k.length()>0&&k.length()<220&&!k.contains("..")&&k.matches("[A-Za-z0-9._/-]+");}
    private File file(String key){return new File(root(),DeviceIdentity.sha256(key)+".json.gz");}
    private SharedPreferences prefs(){return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    synchronized boolean put(String key,String json){if(!valid(key)||json==null)return false;try(FileOutputStream fos=new FileOutputStream(file(key));GZIPOutputStream gz=new GZIPOutputStream(fos)){gz.write(json.getBytes(StandardCharsets.UTF_8));Set<String>s=new HashSet<>(prefs().getStringSet(KEYS,new HashSet<>()));s.add(key);prefs().edit().putStringSet(KEYS,s).apply();return true;}catch(Exception e){return false;}}
    synchronized String get(String key){if(!valid(key))return "";File f=file(key);if(!f.isFile())return "";try(FileInputStream fis=new FileInputStream(f);GZIPInputStream gz=new GZIPInputStream(fis);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=gz.read(b))>0)out.write(b,0,n);return out.toString(StandardCharsets.UTF_8.name());}catch(Exception e){return "";}}
    synchronized boolean has(String key){return valid(key)&&file(key).isFile();}
    synchronized String listJson(){try{org.json.JSONArray a=new org.json.JSONArray();ArrayList<String> keys=new ArrayList<>(prefs().getStringSet(KEYS,new HashSet<>()));java.util.Collections.sort(keys);for(String k:keys)if(has(k))a.put(k);return a.toString();}catch(Exception e){return "[]";}}
    synchronized boolean removePrefix(String prefix){boolean changed=false;Set<String>s=new HashSet<>(prefs().getStringSet(KEYS,new HashSet<>()));for(String k:new ArrayList<>(s))if(k.startsWith(prefix)){File f=file(k);if(f.exists())f.delete();s.remove(k);changed=true;}if(changed)prefs().edit().putStringSet(KEYS,s).apply();return changed;}
    void saveAccount(String json){prefs().edit().putString("account",json==null?"{}":json).apply();}
    String account(){return prefs().getString("account","{}");}
    void saveRoute(String json){put("trip/last",json==null?"{}":json);}
    String route(){return get("trip/last");}
}
''')


def patch_bridge():
    p=JAVA/'NativeBridge.java'
    s=p.read_text()
    s=re.sub(r'private static String sha256\(String value\)\{.*?\}\n\s*@JavascriptInterface public String getTrialDeviceToken\(\)\{.*?\}\n',
             '@JavascriptInterface public String getTrialDeviceToken(){return DeviceIdentity.token(activity);}\n',s,count=1,flags=re.S)
    if 'private final OfflineStore offlineStore;' not in s:
        s=s.replace('    private final MainActivity activity;\n    NativeBridge(MainActivity activity){this.activity=activity;}',
                    '    private final MainActivity activity;\n    private final OfflineStore offlineStore;\n    NativeBridge(MainActivity activity){this.activity=activity;this.offlineStore=new OfflineStore(activity);}',1)
    anchor='    @JavascriptInterface public int versionCode(){return BuildConfig.VERSION_CODE;}\n'
    if anchor not in s:raise SystemExit('bridge versionCode anchor missing')
    add=anchor+r'''    @JavascriptInterface public String deviceName(){return DeviceIdentity.label();}
    @JavascriptInterface public String getServerUrl(){return activity.getConfiguredServerUrl();}
    @JavascriptInterface public boolean setServerUrl(String url){return activity.setConfiguredServerUrl(url);}
    @JavascriptInterface public boolean isSetupComplete(){return activity.isSetupComplete();}
    @JavascriptInterface public void setSetupComplete(boolean done){activity.setSetupComplete(done);}
    @JavascriptInterface public boolean isNetworkAvailable(){return activity.isNetworkAvailable();}
    @JavascriptInterface public void pingServer(){activity.deviceAuthAsync("ping",DeviceIdentity.token(activity));}
    @JavascriptInterface public void autoLoginDevice(){activity.deviceAuthAsync("login",DeviceIdentity.token(activity));}
    @JavascriptInterface public void removeRegisteredDevice(){activity.deviceAuthAsync("remove",DeviceIdentity.token(activity));}
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
'''
    s=s.replace(anchor,add,1)
    p.write_text(s)


def patch_main():
    p=JAVA/'MainActivity.java'
    for anchor,imp in [
      ('import android.net.Uri;','import android.net.ConnectivityManager;\nimport android.net.Network;\nimport android.net.NetworkCapabilities;'),
      ('import java.io.File;','import java.io.BufferedReader;\nimport java.io.InputStreamReader;\nimport java.io.OutputStream;\nimport java.net.HttpURLConnection;\nimport java.net.URL;\nimport java.net.URLEncoder;'),
      ('import java.util.Locale;','import java.util.List;\nimport java.util.Map;\nimport java.util.Locale;'),
    ]:
        if imp.split('\n')[0] not in p.read_text():rep(p,anchor,anchor+'\n'+imp,'main import '+imp.split('\n')[0])
    s=p.read_text()
    s=s.replace('    private static final String PREF_UPDATE_ID="update_download_id";', '    private static final String PREF_UPDATE_ID="update_download_id";\n    private static final String PREF_SERVER_URL="server_url";\n    private static final String PREF_SETUP="setup_complete";',1)
    old='''        if(savedInstanceState==null){\n            String url=BuildConfig.MUSICROAD_URL;\n            if(url.contains("SEU-DOMINIO")){Toast.makeText(this,"Configure MUSICROAD_URL. A biblioteca nativa continua disponível offline.",Toast.LENGTH_LONG).show();webView.loadUrl("file:///android_asset/offline.html?unconfigured=1");}\n            else webView.loadUrl(url);\n        }else webView.restoreState(savedInstanceState);'''
    new='''        if(savedInstanceState==null){\n            if(!isSetupComplete())webView.loadUrl("file:///android_asset/setup.html");\n            else if(!isNetworkAvailable())openOfflineCore();\n            else webView.loadUrl("file:///android_asset/launcher.html");\n        }else webView.restoreState(savedInstanceState);'''
    if old not in s:raise SystemExit('main startup block missing')
    s=s.replace(old,new,1)
    s=s.replace('super.onReceivedError(view,request,error);if(request.isForMainFrame())view.loadUrl("file:///android_asset/offline.html?url="+Uri.encode(BuildConfig.MUSICROAD_URL));', 'super.onReceivedError(view,request,error);if(request.isForMainFrame())openOfflineCore();',1)
    s=s.replace('Uri base=Uri.parse(BuildConfig.MUSICROAD_URL);', 'Uri base=Uri.parse(getConfiguredServerUrl());')
    anchor='    private SharedPreferences getPreferencesStore(){return getSharedPreferences(PREFS,MODE_PRIVATE);}\n'
    if anchor not in s:raise SystemExit('prefs anchor missing')
    methods=anchor+r'''
    String getConfiguredServerUrl(){String v=getPreferencesStore().getString(PREF_SERVER_URL,BuildConfig.MUSICROAD_URL);if(v==null)v=BuildConfig.MUSICROAD_URL;v=v.trim();if(!v.endsWith("/"))v+="/";return v;}
    boolean setConfiguredServerUrl(String value){try{Uri u=Uri.parse(value==null?"":value.trim());if(!"https".equalsIgnoreCase(u.getScheme())||u.getHost()==null||u.getHost().trim().isEmpty())return false;String v=u.toString();if(!v.endsWith("/"))v+="/";getPreferencesStore().edit().putString(PREF_SERVER_URL,v).apply();CookieManager.getInstance().flush();return true;}catch(Exception e){return false;}}
    boolean isSetupComplete(){return getPreferencesStore().getBoolean(PREF_SETUP,false);}
    void setSetupComplete(boolean done){getPreferencesStore().edit().putBoolean(PREF_SETUP,done).apply();}
    boolean isNetworkAvailable(){try{ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);if(cm==null)return false;if(Build.VERSION.SDK_INT>=23){Network n=cm.getActiveNetwork();if(n==null)return false;NetworkCapabilities c=cm.getNetworkCapabilities(n);return c!=null&&(c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)||c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));}android.net.NetworkInfo i=cm.getActiveNetworkInfo();return i!=null&&i.isConnected();}catch(Exception e){return false;}}
    void openOfflineCore(){runOnUiThread(()->{if(webView!=null)webView.loadUrl("file:///android_asset/offline_core.html");});}
    void openRemotePath(String path){runOnUiThread(()->{String base=getConfiguredServerUrl();String p=path==null?"":path;if(p.startsWith("/"))p=p.substring(1);if(webView!=null)webView.loadUrl(base+p);});}
    private String readConnection(HttpURLConnection c){try{java.io.InputStream in=(c.getResponseCode()>=400?c.getErrorStream():c.getInputStream());if(in==null)return "{}";BufferedReader r=new BufferedReader(new InputStreamReader(in,java.nio.charset.StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();String line;while((line=r.readLine())!=null)b.append(line);return b.toString();}catch(Exception e){return "{}";}}
    private void copyResponseCookies(HttpURLConnection c){try{Map<String,List<String>> h=c.getHeaderFields();if(h==null)return;CookieManager cm=CookieManager.getInstance();for(Map.Entry<String,List<String>> e:h.entrySet()){if(e.getKey()!=null&&"set-cookie".equalsIgnoreCase(e.getKey()))for(String v:e.getValue())if(v!=null)cm.setCookie(getConfiguredServerUrl(),v);}cm.flush();}catch(Exception ignored){}}
    void deviceAuthAsync(String action,String token){new Thread(()->{org.json.JSONObject out=new org.json.JSONObject();try{String endpoint=getConfiguredServerUrl()+"api/device_auth.php";HttpURLConnection c=(HttpURLConnection)new URL(endpoint).openConnection();c.setConnectTimeout(9000);c.setReadTimeout(12000);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8");c.setRequestProperty("Accept","application/json");c.setRequestProperty("User-Agent","MusicRoadAndroid/"+BuildConfig.VERSION_NAME);String cookie=CookieManager.getInstance().getCookie(getConfiguredServerUrl());if(cookie!=null&&!cookie.isEmpty())c.setRequestProperty("Cookie",cookie);String body="action="+URLEncoder.encode(action,"UTF-8")+"&device_token="+URLEncoder.encode(token==null?"":token,"UTF-8")+"&device_label="+URLEncoder.encode(DeviceIdentity.label(),"UTF-8")+"&app_version="+URLEncoder.encode(BuildConfig.VERSION_NAME,"UTF-8");try(OutputStream os=c.getOutputStream()){os.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));}String raw=readConnection(c);copyResponseCookies(c);try{out=new org.json.JSONObject(raw);}catch(Exception parse){out=new org.json.JSONObject();out.put("ok",false);out.put("error","Resposta inválida do servidor.");}out.put("http_status",c.getResponseCode());c.disconnect();}catch(Exception e){try{out.put("ok",false);out.put("error","Servidor indisponível.");}catch(Exception ignored){}}try{out.put("action",action);}catch(Exception ignored){}final String json=out.toString();if("login".equals(action)&&out.optBoolean("ok",false))setSetupComplete(true);if("remove".equals(action)&&out.optBoolean("ok",false))setSetupComplete(false);runOnUiThread(()->{if(webView!=null)webView.evaluateJavascript("document.dispatchEvent(new CustomEvent('mr:device-auth',{detail:"+json+"}));",null);});},"MusicRoad-DeviceAuth").start();}
'''
    s=s.replace(anchor,methods,1)
    p.write_text(s)


def write_assets():
    ASSETS.mkdir(parents=True,exist_ok=True)
    ASSETS.joinpath('launcher.html').write_text(r'''<!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover"><meta name="theme-color" content="#06090d"><style>*{box-sizing:border-box}body{margin:0;background:#06090d;color:#edf3f7;font-family:system-ui,-apple-system,sans-serif;display:grid;place-items:center;min-height:100vh}.logo{width:76px;height:76px;border-radius:24px;background:#ff7a1a;color:#111;display:grid;place-items:center;font-weight:950;font-size:24px;box-shadow:0 16px 50px #ff7a1a33}.t{text-align:center;margin-top:18px;font-weight:800}.s{color:#7f909d;font-size:12px;margin-top:7px;text-align:center}.dot{display:inline-block;width:7px;height:7px;border-radius:50%;background:#4bd590;margin-right:6px}</style></head><body><div><div class="logo">MR</div><div class="t">MusicRoad</div><div class="s" id="status"><span class="dot"></span>Preparando sua conta…</div></div><script>(function(){const A=window.MusicRoadAndroid,st=document.getElementById('status');if(!A){st.textContent='Integração Android indisponível.';return}if(!A.isNetworkAvailable()){A.openOfflineCore();return}document.addEventListener('mr:device-auth',e=>{const d=e.detail||{};if(d.action!=='login')return;if(d.ok){st.textContent='Conta reconhecida. Abrindo MusicRoad…';setTimeout(()=>A.openOnlineApp(),180)}else{st.textContent='Abrindo sua sessão…';setTimeout(()=>A.openOnlineApp(),180)}});A.autoLoginDevice();setTimeout(()=>{try{A.openOnlineApp()}catch(_){}},6000)})();</script></body></html>''')
    ASSETS.joinpath('setup.html').write_text(r'''<!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover"><meta name="theme-color" content="#06090d"><style>*{box-sizing:border-box}body{margin:0;background:radial-gradient(circle at 20% 0,#18212a 0,#080d12 40%,#05080b 100%);color:#eef3f6;font-family:system-ui,-apple-system,sans-serif;min-height:100vh}.wrap{max-width:620px;margin:auto;padding:28px 20px 40px}.brand{display:flex;align-items:center;gap:14px}.mark{width:58px;height:58px;border-radius:19px;background:#ff7a1a;color:#111;display:grid;place-items:center;font-weight:950;font-size:19px}.brand b{font-size:22px}.brand small{display:block;color:#ff9b54;letter-spacing:.18em;font-size:9px;margin-top:3px}.hero{margin:38px 0 24px}.hero small{color:#6fe0ad;font-weight:900;letter-spacing:.14em}.hero h1{font-size:34px;line-height:1.05;margin:10px 0}.hero p{color:#91a1ad;line-height:1.55}.card{background:#0b1218;border:1px solid #24313b;border-radius:20px;padding:18px;margin-top:14px}.card h3{margin:0 0 7px}.card p{margin:0 0 14px;color:#82939f;font-size:13px;line-height:1.45}input{width:100%;height:52px;border-radius:14px;border:1px solid #30404c;background:#070c10;color:#fff;padding:0 14px;font-size:14px;outline:none}.row{display:grid;grid-template-columns:1fr auto;gap:9px}.btn{height:52px;border:0;border-radius:14px;padding:0 18px;font-weight:900;background:#ff7a1a;color:#111}.btn.alt{background:#15212a;color:#eef3f6;border:1px solid #30414e}.actions{display:grid;grid-template-columns:1fr 1fr;gap:9px;margin-top:10px}.status{margin-top:12px;padding:11px 12px;border-radius:12px;background:#091015;color:#8fa0ac;font-size:12px}.ok{color:#77e7b4}.warn{color:#ffc277}.tiny{font-size:10px;color:#657681;margin-top:14px;line-height:1.5}@media(max-width:500px){.row,.actions{grid-template-columns:1fr}.hero h1{font-size:29px}}</style></head><body><main class="wrap"><div class="brand"><div class="mark">MR</div><div><b>MusicRoad</b><small>CONFIGURAÇÃO INICIAL</small></div></div><section class="hero"><small>PRIMEIRO ACESSO</small><h1>Prepare o app uma vez.</h1><p>O MusicRoad salva a configuração no aparelho, vincula este dispositivo à sua conta e mantém um núcleo local para quando a internet cair.</p></section><section class="card"><h3>1. Servidor</h3><p>Use o endereço do seu MusicRoad. Em produção ele já vem preenchido.</p><div class="row"><input id="server" inputmode="url"><button class="btn" id="connect">CONECTAR</button></div><div class="status" id="serverStatus">Aguardando conexão.</div></section><section class="card" id="accountCard" hidden><h3>2. Sua conta</h3><p id="accountText">Verificando se este aparelho já está vinculado a uma conta.</p><div class="actions" id="accountActions" hidden><button class="btn" id="create">CRIAR CONTA</button><button class="btn alt" id="login">JÁ TENHO CONTA</button></div></section><section class="card"><h3>3. Permissões</h3><p>GPS e músicas ficam no Android. Você pode conceder agora ou quando usar cada recurso.</p><button class="btn alt" id="permissions">CONFIGURAR GPS E MÚSICA</button><div class="status" id="permStatus">Você continua no controle das permissões.</div></section><div class="tiny">Ao reinstalar o APK no mesmo aparelho e com a mesma assinatura, o MusicRoad consegue reconhecer o dispositivo pelo identificador derivado do Android. O ANDROID_ID bruto não é enviado ao servidor.</div></main><script>(function(){const A=window.MusicRoadAndroid,$=id=>document.getElementById(id),server=$('server'),st=$('serverStatus'),card=$('accountCard'),txt=$('accountText'),actions=$('accountActions');server.value=A?.getServerUrl?.()||'';function ping(){if(!A)return;const ok=A.setServerUrl(server.value.trim());if(!ok){st.textContent='Use um endereço HTTPS válido.';st.className='status warn';return}st.textContent='Conectando…';A.pingServer()}document.addEventListener('mr:device-auth',e=>{const d=e.detail||{};if(d.action==='ping'){if(!d.ok){st.textContent=d.error||'Servidor indisponível.';st.className='status warn';return}st.textContent='Servidor MusicRoad conectado ✓';st.className='status ok';card.hidden=false;txt.textContent='Procurando uma conta já vinculada a este aparelho…';A.autoLoginDevice();return}if(d.action==='login'){card.hidden=false;if(d.ok){txt.textContent='Conta encontrada: '+(d.user?.name||d.user?.username||'usuário')+'. Entrando automaticamente…';actions.hidden=true;A.setSetupComplete(true);setTimeout(()=>A.openOnlineApp(),600)}else{txt.textContent='Este aparelho ainda não está vinculado. Crie uma conta ou entre na sua conta atual.';actions.hidden=false}}});$('connect').onclick=ping;$('create').onclick=()=>A.openRegister();$('login').onclick=()=>A.openLogin();$('permissions').onclick=()=>{try{A.requestAudioPermission()}catch(_){};try{navigator.geolocation.getCurrentPosition(()=>{$('permStatus').textContent='GPS liberado ✓';$('permStatus').className='status ok'},()=>{$('permStatus').textContent='Você pode liberar o GPS depois em Ajustes.'},{enableHighAccuracy:true,timeout:10000})}catch(_){} };if(A?.isNetworkAvailable?.())setTimeout(ping,350);else{st.textContent='Sem internet. Conecte-se para concluir o primeiro acesso.';st.className='status warn'}})();</script></body></html>''')
    ASSETS.joinpath('offline_core.html').write_text(r'''<!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover"><meta name="theme-color" content="#05080b"><style>*{box-sizing:border-box}body{margin:0;background:#05080b;color:#edf2f5;font-family:system-ui,-apple-system,sans-serif;min-height:100vh}.top{height:66px;display:flex;align-items:center;gap:12px;padding:0 15px;border-bottom:1px solid #202a31;background:#080d11}.mark{width:40px;height:40px;border-radius:13px;background:#ff7a1a;color:#111;display:grid;place-items:center;font-weight:950}.top b{font-size:16px}.top small{display:block;color:#6ddb9f;font-size:9px;letter-spacing:.12em}.online{margin-left:auto;border:1px solid #31414c;background:#111a20;color:#d9e2e7;border-radius:12px;padding:10px 12px;font-weight:800}.nav{display:grid;grid-template-columns:repeat(4,1fr);gap:6px;padding:8px;background:#070b0e;border-bottom:1px solid #1e2930}.nav button{height:46px;border:0;border-radius:12px;background:#0d151b;color:#8c9ba5;font-weight:850}.nav button.active{background:#ff7a1a;color:#111}.screen{display:none;padding:14px;max-width:900px;margin:auto}.screen.active{display:block}.card{background:#0b1217;border:1px solid #202d36;border-radius:18px;padding:16px;margin-bottom:11px}.card h2,.card h3{margin:0 0 7px}.muted{color:#7d8e99;font-size:12px;line-height:1.45}.big{font-size:27px;font-weight:900}.pill{display:inline-flex;padding:6px 9px;border-radius:999px;background:#0b3022;color:#7ae0ad;font-size:10px;font-weight:900}.btn{width:100%;min-height:50px;border:0;border-radius:14px;background:#ff7a1a;color:#111;font-weight:950;margin-top:9px}.btn.alt{background:#152029;color:#eef2f4;border:1px solid #2d3b45}canvas{width:100%;height:60vh;min-height:340px;background:#081015;border:1px solid #22313a;border-radius:18px;display:block}.mapbar{display:flex;gap:8px;margin-bottom:9px}.mapbar>div{flex:1}.state{font-size:12px;color:#8fa0aa}.list{display:grid;gap:8px}.item{background:#0b1318;border:1px solid #223039;border-radius:13px;padding:12px}.hidden{display:none!important}@media(orientation:landscape){.screen{max-width:none}.map-layout{display:grid;grid-template-columns:minmax(0,1.8fr) minmax(250px,.7fr);gap:10px}.map-layout canvas{height:calc(100vh - 150px)}} </style></head><body><header class="top"><div class="mark">MR</div><div><b>MusicRoad</b><small>OFFLINE CORE</small></div><button class="online" id="sync">TENTAR ONLINE</button></header><nav class="nav"><button data-s="home" class="active">INÍCIO</button><button data-s="map">MAPA</button><button data-s="music">MÚSICA</button><button data-s="settings">AJUSTES</button></nav><section id="home" class="screen active"><div class="card"><span class="pill">FUNCIONANDO SEM INTERNET</span><h2 id="hello">MusicRoad offline</h2><p class="muted">O app abriu a interface que está dentro do próprio APK. Não depende da página do servidor para iniciar.</p></div><div class="card"><h3>Última viagem</h3><div class="big" id="dest">Nenhuma rota salva</div><p class="muted" id="tripInfo">Abra uma rota quando estiver online para mantê-la no aparelho.</p><button class="btn alt" data-go="map">ABRIR MAPA OFFLINE</button></div><div class="card"><h3>Dados no aparelho</h3><div id="packs" class="state">Verificando mapas e radares…</div></div></section><section id="map" class="screen"><div class="map-layout"><div><div class="mapbar"><div><b>Mapa baixado</b><div class="state" id="gps">GPS aguardando…</div></div></div><canvas id="cv"></canvas></div><div><div class="card"><h3 id="city">Região offline</h3><p class="muted" id="mapInfo">O GPS seleciona automaticamente o município baixado.</p></div><div class="card"><h3>Última rota</h3><p class="muted" id="routeInfo">Sem rota salva.</p></div></div></div></section><section id="music" class="screen"><div class="card"><h2>Música do aparelho</h2><p class="muted">A biblioteca nativa funciona sem internet porque as músicas são lidas diretamente do Android.</p><button class="btn" id="library">ABRIR BIBLIOTECA DO CELULAR</button></div></section><section id="settings" class="screen"><div class="card"><h3>Conta lembrada</h3><p class="muted" id="account">Nenhum perfil local.</p></div><div class="card"><h3>Servidor</h3><p class="muted" id="server"></p><button class="btn alt" id="retry">CONECTAR NOVAMENTE</button></div><div class="card"><h3>Limite do offline</h3><p class="muted">Mapa, radares, GPS, música local e a última rota funcionam aqui. Criar uma rota totalmente nova ainda requer internet nesta versão.</p></div></section><script>(function(){const A=window.MusicRoadAndroid,$=id=>document.getElementById(id);document.querySelectorAll('.nav button').forEach(b=>b.onclick=()=>{document.querySelectorAll('.nav button').forEach(x=>x.classList.toggle('active',x===b));document.querySelectorAll('.screen').forEach(x=>x.classList.toggle('active',x.id===b.dataset.s));if(b.dataset.s==='map')draw()});document.querySelectorAll('[data-go]').forEach(b=>b.onclick=()=>document.querySelector(`.nav button[data-s="${b.dataset.go}"]`).click());$('sync').onclick=$('retry').onclick=()=>A?.openOnlineApp?.();$('library').onclick=()=>A?.openNativeLibrary?.();$('server').textContent=A?.getServerUrl?.()||'';let account={};try{account=JSON.parse(A?.getAccountSnapshot?.()||'{}')}catch(_){};$('account').textContent=account?.name?`${account.name} · ${account.role||'conta'}`:'Perfil será restaurado quando houver internet.';$('hello').textContent=account?.name?`Olá, ${account.name}`:'MusicRoad offline';let trip={};try{trip=JSON.parse(A?.getLastRouteSnapshot?.()||'{}')}catch(_){};$('dest').textContent=trip?.destination||'Nenhuma rota salva';const coords=trip?.data?.route?.geometry?.coordinates||[];$('tripInfo').textContent=coords.length?`Última rota salva · ${coords.length} pontos de geometria`:'Abra uma rota quando estiver online para mantê-la no aparelho.';$('routeInfo').textContent=trip?.destination?`Destino: ${trip.destination}`:'Sem rota salva.';let keys=[];try{keys=JSON.parse(A?.listOfflinePackKeys?.()||'[]')}catch(_){};const states=[...new Set(keys.map(k=>/^state\/([A-Z]{2})\//.exec(k)?.[1]).filter(Boolean))];$('packs').textContent=states.length?`Estados encontrados: ${states.join(', ')} · ${keys.filter(k=>k.includes('/city/')).length} municípios salvos`:'Nenhum mapa estadual foi baixado ainda.';function dec(str){const out=[];let i=0,lat=0,lon=0;str=String(str||'');while(i<str.length){let sh=0,r=0,b;do{b=str.charCodeAt(i++)-63;r|=(b&31)<<sh;sh+=5}while(b>=32&&i<str.length);lat+=(r&1)?~(r>>1):(r>>1);sh=0;r=0;do{b=str.charCodeAt(i++)-63;r|=(b&31)<<sh;sh+=5}while(b>=32&&i<str.length);lon+=(r&1)?~(r>>1):(r>>1);out.push([lat/1e5,lon/1e5])}return out}let pos=null,pack=null,bbox=null;function findPack(lat,lon){let idx={};try{idx=JSON.parse(A?.readOfflinePack?.('state/index')||'{}')}catch(_){};for(const [uf,st] of Object.entries(idx||{}))for(const [code,m] of Object.entries(st?.cities||{})){const b=m?.bbox;if(Array.isArray(b)&&lon>=b[0]&&lon<=b[2]&&lat>=b[1]&&lat<=b[3]){try{const p=JSON.parse(A.readOfflinePack(`state/${uf}/city/${code}`)||'null');if(p?.roads){pack=p;bbox=p.bbox;$('city').textContent=`${p.city?.name||code} · ${uf}`;$('mapInfo').textContent=`${p.road_count||p.roads.length} vias carregadas do armazenamento do app`;draw();return true}}catch(_){}}}return false}function draw(){const c=$('cv'),d=devicePixelRatio||1,w=Math.max(300,c.clientWidth),h=Math.max(300,c.clientHeight);c.width=w*d;c.height=h*d;const x=c.getContext('2d');x.scale(d,d);x.fillStyle='#081015';x.fillRect(0,0,w,h);let b=bbox;if(!b&&coords.length){let xs=coords.map(p=>+p[0]),ys=coords.map(p=>+p[1]);b=[Math.min(...xs),Math.min(...ys),Math.max(...xs),Math.max(...ys)]}if(!b){x.fillStyle='#81919b';x.font='14px system-ui';x.fillText('Baixe um mapa por estado enquanto estiver online.',20,36);return}const pad=24,dx=Math.max(.0001,b[2]-b[0]),dy=Math.max(.0001,b[3]-b[1]),sx=(w-pad*2)/dx,sy=(h-pad*2)/dy,sc=Math.min(sx,sy),px=lon=>pad+(lon-b[0])*sc,py=lat=>h-pad-(lat-b[1])*sc;if(pack?.roads){x.lineCap='round';for(const r of pack.roads){const pts=dec(r?.[3]);if(pts.length<2)continue;const cl=Number(r?.[0]||8);x.strokeStyle=cl<=2?'#607b8d':cl<=4?'#4d6778':'#324854';x.lineWidth=cl<=2?2.6:cl<=4?1.9:1;x.beginPath();pts.forEach((p,i)=>i?x.lineTo(px(p[1]),py(p[0])):x.moveTo(px(p[1]),py(p[0])));x.stroke()}}if(coords.length){x.strokeStyle='#ff7a1a';x.lineWidth=3;x.beginPath();coords.forEach((p,i)=>i?x.lineTo(px(+p[0]),py(+p[1])):x.moveTo(px(+p[0]),py(+p[1])));x.stroke()}if(pos){x.fillStyle='#62e2ab';x.beginPath();x.arc(px(pos.lon),py(pos.lat),6,0,Math.PI*2);x.fill();x.strokeStyle='#fff';x.lineWidth=2;x.stroke()}}try{navigator.geolocation.watchPosition(p=>{pos={lat:p.coords.latitude,lon:p.coords.longitude};$('gps').textContent=`GPS ${pos.lat.toFixed(5)}, ${pos.lon.toFixed(5)} · ±${Math.round(p.coords.accuracy||0)} m`;if(!pack)findPack(pos.lat,pos.lon);draw()},()=>{$('gps').textContent='GPS indisponível. Libere a localização nos ajustes.'},{enableHighAccuracy:true,maximumAge:3000,timeout:15000})}catch(_){}window.addEventListener('resize',draw);draw()})();</script></body></html>''')


patch_version();write_device_identity();write_offline_store();patch_bridge();patch_main();write_assets()
print('MusicRoad 1.1 offline core + registered device patch applied')

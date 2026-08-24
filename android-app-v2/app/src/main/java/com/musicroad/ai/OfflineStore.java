package com.musicroad.ai;

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

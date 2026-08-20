package com.musicroad.ai;

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
    static String token(Context c){try{String id=Settings.Secure.getString(c.getContentResolver(),Settings.Secure.ANDROID_ID);if(id==null||id.trim().isEmpty()){android.content.SharedPreferences p=c.getSharedPreferences("musicroad_trial_identity",Context.MODE_PRIVATE);id=p.getString("fallback","");if(id.isEmpty()){id=UUID.randomUUID().toString();p.edit().putString("fallback",id).apply();}}return "android:"+sha256(c.getPackageName()+"|"+id);}catch(Exception e){return "";}}
    static String label(){String m=(Build.MANUFACTURER==null?"":Build.MANUFACTURER.trim()),model=(Build.MODEL==null?"":Build.MODEL.trim());String x=(m+" "+model).trim();return x.isEmpty()?"Android":x;}
}

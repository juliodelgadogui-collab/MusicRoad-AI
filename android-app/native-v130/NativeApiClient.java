package com.musicroad.ai;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

final class NativeApiClient {
    static final class Response {
        final int code;
        final String body;
        Response(int code,String body){this.code=code;this.body=body==null?"":body;}
        JSONObject json(){try{return new JSONObject(body);}catch(Exception e){return new JSONObject();}}
        boolean ok(){return code>=200&&code<300;}
    }

    private final SharedPreferences prefs;
    private static final String PREFS="musicroad_native_api_v1";
    private static final String KEY_COOKIE="cookie";

    NativeApiClient(Context c){prefs=c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);}

    String cookie(){return prefs.getString(KEY_COOKIE,"");}
    void clearCookie(){prefs.edit().remove(KEY_COOKIE).apply();}

    Response get(String base,String path) throws Exception {return request(base,path,"GET",null,25000,16*1024*1024);}
    Response getLarge(String base,String path) throws Exception {return request(base,path,"GET",null,90000,70*1024*1024);}
    Response post(String base,String path,JSONObject data) throws Exception {return request(base,path,"POST",data==null?"{}":data.toString(),30000,8*1024*1024);}

    private Response request(String base,String path,String method,String json,int timeout,int maxBytes) throws Exception {
        String b=normalizeBase(base);
        String p=path==null?"":path;
        while(p.startsWith("/"))p=p.substring(1);
        URL url=new URL(b+p);
        HttpURLConnection c=(HttpURLConnection)url.openConnection();
        c.setConnectTimeout(Math.min(timeout,12000));
        c.setReadTimeout(timeout);
        c.setInstanceFollowRedirects(false);
        c.setRequestMethod(method);
        c.setRequestProperty("Accept","application/json");
        c.setRequestProperty("X-MusicRoad-Native","1");
        c.setRequestProperty("User-Agent","MusicRoadAndroid/"+BuildConfig.VERSION_NAME+" NativeCore");
        String ck=cookie();if(!ck.isEmpty())c.setRequestProperty("Cookie",ck);
        if(json!=null){
            c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");
            byte[] bytes=json.getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(bytes.length);
            try(OutputStream out=c.getOutputStream()){out.write(bytes);}
        }
        int code=c.getResponseCode();
        saveCookies(c.getHeaderFields());
        InputStream in=code>=400?c.getErrorStream():c.getInputStream();
        String body=read(in,maxBytes);
        c.disconnect();
        return new Response(code,body);
    }

    private void saveCookies(Map<String,List<String>> headers){
        if(headers==null)return;
        StringBuilder jar=new StringBuilder();
        for(Map.Entry<String,List<String>> e:headers.entrySet()){
            String k=e.getKey();if(k==null||!"set-cookie".equalsIgnoreCase(k)||e.getValue()==null)continue;
            for(String raw:e.getValue()){
                if(raw==null)continue;int semi=raw.indexOf(';');String pair=(semi>=0?raw.substring(0,semi):raw).trim();
                if(pair.isEmpty()||pair.endsWith("="))continue;
                if(jar.length()>0)jar.append("; ");jar.append(pair);
            }
        }
        if(jar.length()>0)prefs.edit().putString(KEY_COOKIE,jar.toString()).apply();
    }

    private static String read(InputStream in,int maxBytes) throws Exception {
        if(in==null)return "";
        try(BufferedInputStream bin=new BufferedInputStream(in);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[16384];int n,total=0;
            while((n=bin.read(b))!=-1){total+=n;if(total>maxBytes)throw new IllegalStateException("response_too_large");out.write(b,0,n);}
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    static String normalizeBase(String value){String s=value==null?"":value.trim();if(!s.endsWith("/"))s+="/";return s;}
}

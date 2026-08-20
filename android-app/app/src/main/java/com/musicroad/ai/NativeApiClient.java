package com.musicroad.ai;

import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class NativeApiClient {
    static { CookieManager cm=new CookieManager(); cm.setCookiePolicy(CookiePolicy.ACCEPT_ALL); CookieHandler.setDefault(cm); }
    private final String base;
    public NativeApiClient(String url){base=normalizeBase(url);}
    public String base(){return base;}
    public static String normalizeBase(String s){
        if(s==null)s="";s=s.trim();if(!s.endsWith("/"))s+="/";return s;
    }
    public Response get(String path) throws Exception { return request("GET",path,null); }
    public Response post(String path, JSONObject body) throws Exception { return request("POST",path,body==null?"{}":body.toString()); }
    private Response request(String method,String path,String payload) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(base+path).openConnection();
        c.setConnectTimeout(10000);c.setReadTimeout(25000);c.setRequestMethod(method);c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Accept","application/json");c.setRequestProperty("User-Agent","MusicRoadAndroid/"+BuildConfig.VERSION_NAME+" NativeCore");c.setRequestProperty("X-MusicRoad-Native","1");
        if(payload!=null){byte[] b=payload.getBytes(StandardCharsets.UTF_8);c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");c.setFixedLengthStreamingMode(b.length);try(OutputStream os=c.getOutputStream()){os.write(b);}}
        int code=c.getResponseCode();InputStream in=code>=400?c.getErrorStream():c.getInputStream();String text=read(in);c.disconnect();return new Response(code,text);
    }
    private static String read(InputStream in)throws Exception{if(in==null)return "";StringBuilder s=new StringBuilder();try(BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null)s.append(line);}return s.toString();}
    public static final class Response{
        public final int code;public final String body;Response(int c,String b){code=c;body=b==null?"":b;}
        public boolean ok(){return code>=200&&code<300;}
        public JSONObject json(){try{return new JSONObject(body);}catch(Exception e){return new JSONObject();}}
    }
}

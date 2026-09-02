package com.estradaplay.comunista;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Locale;

final class ConvoyStore {
    private static final String PREF="epc_convoy_v180",KEY_CODE="code";
    private final Context app; private final SharedPreferences prefs; private final ApiClient api;
    ConvoyStore(Context c){app=c.getApplicationContext();prefs=app.getSharedPreferences(PREF,Context.MODE_PRIVATE);api=new ApiClient(app);}

    static final class Member {
        final String device,nickname; final double lat,lon,speedKmh; final float heading; final long lastSeen; final boolean self;
        Member(String device,String nickname,double lat,double lon,double speedKmh,float heading,long lastSeen,boolean self){this.device=device;this.nickname=nickname;this.lat=lat;this.lon=lon;this.speedKmh=speedKmh;this.heading=heading;this.lastSeen=lastSeen;this.self=self;}
        static Member from(JSONObject o){if(o==null)return null;return new Member(o.optString("device",""),o.optString("nickname","MOTORISTA"),o.isNull("lat")?Double.NaN:o.optDouble("lat",Double.NaN),o.isNull("lon")?Double.NaN:o.optDouble("lon",Double.NaN),o.optDouble("speed_kmh",0),o.isNull("heading")?Float.NaN:(float)o.optDouble("heading",Double.NaN),o.optLong("last_seen",0),o.optBoolean("self",false));}
        String shortName(){String n=nickname==null?"MOTORISTA":nickname.trim();if(n.length()>12)n=n.substring(0,12);return n;}
    }

    String code(){return normalize(prefs.getString(KEY_CODE,""));}
    void clear(){prefs.edit().remove(KEY_CODE).apply();}

    JSONObject create(Location l) throws Exception {JSONObject d=base("create",l);d.put("title","Comboio Estrada Play");JSONObject j=api.post("api/convoy.php",d).json();if(j.optBoolean("ok",false)){String c=normalize(j.optString("code",""));if(!c.isEmpty())prefs.edit().putString(KEY_CODE,c).apply();}return j;}
    JSONObject join(String code,Location l) throws Exception {String c=normalize(code);JSONObject d=base("join",l);d.put("code",c);JSONObject j=api.post("api/convoy.php",d).json();if(j.optBoolean("ok",false))prefs.edit().putString(KEY_CODE,c).apply();return j;}
    JSONObject ping(Location l) throws Exception {String c=code();if(c.isEmpty())return new JSONObject();JSONObject d=base("ping",l);d.put("code",c);return api.post("api/convoy.php",d).json();}
    JSONObject state() throws Exception {String c=code();if(c.isEmpty())return new JSONObject();JSONObject d=base("state",null);d.put("code",c);return api.post("api/convoy.php",d).json();}
    JSONObject leave() throws Exception {String c=code();JSONObject d=base("leave",null);d.put("code",c);JSONObject j=api.post("api/convoy.php",d).json();if(j.optBoolean("ok",false))clear();return j;}

    ArrayList<Member> members(JSONObject j){ArrayList<Member> out=new ArrayList<>();if(j==null)return out;JSONArray a=j.optJSONArray("members");if(a==null)return out;for(int i=0;i<a.length();i++){Member m=Member.from(a.optJSONObject(i));if(m!=null)out.add(m);}return out;}

    private JSONObject base(String action,Location l){JSONObject d=new JSONObject();try{d.put("action",action);d.put("nickname",nickname());if(l!=null){d.put("lat",l.getLatitude());d.put("lon",l.getLongitude());d.put("speed_kmh",l.hasSpeed()?Math.max(0,l.getSpeed()*3.6):0);if(l.hasBearing())d.put("heading",l.getBearing());}}catch(Throwable ignored){}return d;}
    private String nickname(){String t=DeviceIdentity.token(app);String suffix=t==null||t.length()<4?"0000":t.substring(t.length()-4).toUpperCase(Locale.ROOT);return"MOTORISTA-"+suffix;}
    static String normalize(String raw){if(raw==null)return"";StringBuilder b=new StringBuilder();String x=raw.toUpperCase(Locale.ROOT);for(int i=0;i<x.length()&&b.length()<8;i++){char c=x.charAt(i);if((c>='A'&&c<='Z')||(c>='0'&&c<='9'))b.append(c);}return b.toString();}
}

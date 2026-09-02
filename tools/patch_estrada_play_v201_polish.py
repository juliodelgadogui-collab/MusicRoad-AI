#!/usr/bin/env python3
from pathlib import Path
import re

ROOT=Path(__file__).resolve().parents[1]
APP=ROOT/'estrada-play-comunista-app'
JAVA=APP/'app/src/main/java/com/estradaplay/comunista'


def replace_method(src, signature, replacement):
    start=src.find(signature)
    if start<0: raise SystemExit('signature not found: '+signature)
    brace=src.find('{',start); depth=0; i=brace
    in_str=in_chr=esc=line=block=False
    while i<len(src):
        c=src[i]; n=src[i+1] if i+1<len(src) else ''
        if line:
            if c=='\n': line=False
        elif block:
            if c=='*' and n=='/': block=False; i+=1
        elif in_str:
            if esc: esc=False
            elif c=='\\': esc=True
            elif c=='"': in_str=False
        elif in_chr:
            if esc: esc=False
            elif c=='\\': esc=True
            elif c=="'": in_chr=False
        else:
            if c=='/' and n=='/': line=True; i+=1
            elif c=='/' and n=='*': block=True; i+=1
            elif c=='"': in_str=True
            elif c=="'": in_chr=True
            elif c=='{': depth+=1
            elif c=='}':
                depth-=1
                if depth==0: return src[:start]+replacement.rstrip()+src[i+1:]
        i+=1
    raise SystemExit('closing brace not found: '+signature)

# version
gradle=APP/'app/build.gradle'
g=gradle.read_text()
g=re.sub(r'versionCode\s+\d+','versionCode 201',g,1)
g=re.sub(r"versionName\s+'[^']+'","versionName '2.0.1'",g,1)
gradle.write_text(g)

# richer OSRM route model
(JAVA/'RouteEngine.java').write_text(r'''package com.estradaplay.comunista;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

// NAV_POLISH_V201: routing separates maneuver distance, action and road for a real automotive HUD.
final class RouteEngine {
    static final class Route {
        final String geoJson;
        final double distanceM, durationS, nextDistanceM;
        final String nextInstruction, nextRoad, maneuverType, maneuverModifier;
        Route(String geoJson,double distanceM,double durationS,String instruction,double nextDistanceM,String road,String type,String modifier){
            this.geoJson=geoJson;this.distanceM=distanceM;this.durationS=durationS;this.nextInstruction=instruction==null?"":instruction;
            this.nextDistanceM=Math.max(0,nextDistanceM);this.nextRoad=road==null?"":road;this.maneuverType=type==null?"":type;this.maneuverModifier=modifier==null?"":modifier;
        }
        String summary(){String d=distanceM>=1000?String.format(Locale.getDefault(),"%.1f km",distanceM/1000.0):Math.max(0,Math.round(distanceM))+" m";long min=Math.max(1,Math.round(durationS/60.0)),h=min/60,m=min%60;return d+" · "+(h>0?h+"h "+m+"min":m+" min");}
    }
    private static final class Maneuver {final String action,road,type,modifier;final double distance;Maneuver(String a,String r,String t,String m,double d){action=a;road=r;type=t;modifier=m;distance=d;}}
    private RouteEngine(){}

    static Route fetch(double fromLat,double fromLon,double toLat,double toLon)throws Exception{
        String url=String.format(Locale.US,"https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson&steps=true&alternatives=true&continue_straight=true&radiuses=500;500",fromLon,fromLat,toLon,toLat);
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(10000);c.setReadTimeout(25000);c.setInstanceFollowRedirects(true);c.setRequestProperty("Accept","application/json");c.setRequestProperty("User-Agent","EstradaPlayComunista/2.0.1 Android");
        int code=c.getResponseCode();if(code<200||code>=300){c.disconnect();throw new Exception("HTTP "+code);}String raw;
        try(InputStream in=c.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){byte[]buf=new byte[32768];int n;while((n=in.read(buf))>0){bytes.write(buf,0,n);if(bytes.size()>8_000_000)throw new Exception("Rota grande demais");}raw=new String(bytes.toByteArray(),StandardCharsets.UTF_8);}finally{c.disconnect();}
        JSONObject root=new JSONObject(raw);JSONArray routes=root.optJSONArray("routes");if(routes==null||routes.length()==0)throw new Exception("Rota não encontrada");JSONObject r=chooseRoute(routes,fromLat,fromLon,toLat,toLon);if(r==null)throw new Exception("Rota incoerente para este destino");JSONObject geometry=r.optJSONObject("geometry");if(geometry==null)throw new Exception("Geometria ausente");
        JSONObject feature=new JSONObject();feature.put("type","Feature");feature.put("properties",new JSONObject());feature.put("geometry",geometry);JSONArray features=new JSONArray();features.put(feature);JSONObject collection=new JSONObject();collection.put("type","FeatureCollection");collection.put("features",features);
        Maneuver m=firstManeuver(r);return new Route(collection.toString(),r.optDouble("distance",0),r.optDouble("duration",0),m.action,m.distance,m.road,m.type,m.modifier);
    }

    private static JSONObject chooseRoute(JSONArray routes,double fromLat,double fromLon,double toLat,double toLon){ArrayList<JSONObject> valid=new ArrayList<>();double fastest=Double.POSITIVE_INFINITY,straight=distanceM(fromLat,fromLon,toLat,toLon);for(int i=0;i<routes.length();i++){JSONObject r=routes.optJSONObject(i);if(r==null)continue;double dist=r.optDouble("distance",0),dur=r.optDouble("duration",0);JSONObject g=r.optJSONObject("geometry");if(dist<=0||dur<=0||g==null)continue;JSONArray coords=g.optJSONArray("coordinates");if(coords==null||coords.length()<2)continue;JSONArray first=coords.optJSONArray(0),last=coords.optJSONArray(coords.length()-1);if(first==null||last==null||first.length()<2||last.length()<2)continue;double startGap=distanceM(fromLat,fromLon,first.optDouble(1),first.optDouble(0)),endGap=distanceM(toLat,toLon,last.optDouble(1),last.optDouble(0));if(startGap>1200||endGap>1200)continue;valid.add(r);fastest=Math.min(fastest,dur);}if(valid.isEmpty())return null;JSONObject best=null;double scoreBest=Double.POSITIVE_INFINITY;for(JSONObject r:valid){double dist=r.optDouble("distance",0),dur=r.optDouble("duration",0),score=dist+Math.max(0,dur-fastest)*8.0;if(score<scoreBest){scoreBest=score;best=r;}}if(best==null)return null;double chosen=best.optDouble("distance",0);if(straight>=1200&&straight<=15000){double max=Math.max(straight*3.2,straight+12000);if(chosen>max)return null;}return best;}
    private static double distanceM(double lat1,double lon1,double lat2,double lon2){if(!Double.isFinite(lat1)||!Double.isFinite(lon1)||!Double.isFinite(lat2)||!Double.isFinite(lon2))return Double.POSITIVE_INFINITY;double p1=Math.toRadians(lat1),p2=Math.toRadians(lat2),dLat=p2-p1,dLon=Math.toRadians(lon2-lon1),a=Math.sin(dLat/2)*Math.sin(dLat/2)+Math.cos(p1)*Math.cos(p2)*Math.sin(dLon/2)*Math.sin(dLon/2);return 6371000.0*2.0*Math.atan2(Math.sqrt(a),Math.sqrt(Math.max(0,1-a)));}
    private static Maneuver firstManeuver(JSONObject route){JSONArray legs=route.optJSONArray("legs");if(legs==null||legs.length()==0)return new Maneuver("Siga na rota","","","",0);JSONObject leg=legs.optJSONObject(0);JSONArray steps=leg==null?null:leg.optJSONArray("steps");if(steps==null)return new Maneuver("Siga na rota","","","",0);double before=0;for(int i=0;i<steps.length();i++){JSONObject step=steps.optJSONObject(i);if(step==null)continue;JSONObject man=step.optJSONObject("maneuver");if(man==null){before+=Math.max(0,step.optDouble("distance",0));continue;}String type=man.optString("type",""),modifier=man.optString("modifier",""),road=step.optString("name","").trim();if("depart".equals(type)){before+=Math.max(0,step.optDouble("distance",0));continue;}String action;if("arrive".equals(type))action="Chegada ao destino";else if("roundabout".equals(type)||"rotary".equals(type))action="Entre na rotatória";else if(modifier.contains("slight right"))action="Mantenha-se levemente à direita";else if(modifier.contains("slight left"))action="Mantenha-se levemente à esquerda";else if(modifier.contains("right"))action="Vire à direita";else if(modifier.contains("left"))action="Vire à esquerda";else if("merge".equals(type))action="Entre na via";else action="Siga em frente";return new Maneuver(action,road,type,modifier,before,type.equals("arrive")?Math.max(before,20):Math.max(before,15));}return new Maneuver("Siga na rota","","","",0);}
}
''')

# motion helper
(JAVA/'EpcMotion.java').write_text(r'''package com.estradaplay.comunista;
import android.view.View;
import android.view.ViewGroup;
final class EpcMotion {
    private EpcMotion(){}
    static void fadeIn(View v){if(v==null)return;v.setAlpha(0.88f);v.animate().alpha(1f).setDuration(180L).start();}
    static void stagger(ViewGroup g){if(g==null)return;int count=Math.min(g.getChildCount(),18);for(int i=0;i<count;i++){View v=g.getChildAt(i);v.setAlpha(0f);v.setTranslationY(18f);v.animate().alpha(1f).translationY(0f).setStartDelay(i*22L).setDuration(190L).start();}}
}
''')

# map styling / tint
rv=JAVA/'RoadMapView.java'; s=rv.read_text()
s=s.replace('private static final String LOCAL_STYLE = "{\\"version\\":8,\\"name\\":\\"EstradaPlay Offline\\",\\"sources\\":{},\\"layers\\":[{\\"id\\":\\"background\\",\\"type\\":\\"background\\",\\"paint\\":{\\"background-color\\":\\"#070a0f\\"}}]}";', 'private static final String LOCAL_STYLE = "{\\"version\\":8,\\"name\\":\\"EPC Night\\",\\"sources\\":{},\\"layers\\":[{\\"id\\":\\"background\\",\\"type\\":\\"background\\",\\"paint\\":{\\"background-color\\":\\"#080507\\"}}]}";')
s=s.replace('private final HazardOverlay overlay;\n    private final TextView fallback;', 'private final HazardOverlay overlay;\n    private final TextView fallback;\n    private final View nightTint;')
s=s.replace('        initMapLibre();\n        overlay = new HazardOverlay(context);', '        initMapLibre();\n        nightTint = new View(context);\n        nightTint.setBackgroundColor(Color.argb(52, 10, 0, 5));\n        nightTint.setClickable(false);\n        nightTint.setFocusable(false);\n        addView(nightTint, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));\n        overlay = new HazardOverlay(context);')
s=s.replace('lineColor("#26313d"), lineWidth(5.2f), lineOpacity(0.96f)', 'lineColor("#21171a"), lineWidth(5.8f), lineOpacity(0.98f)')
s=s.replace('lineColor("#d7dde3"), lineWidth(2.5f), lineOpacity(0.92f)', 'lineColor("#9d918d"), lineWidth(2.7f), lineOpacity(0.90f)')
s=s.replace('lineColor("#2b080d"), lineWidth(8.5f), lineOpacity(0.92f)', 'lineColor("#4d0713"), lineWidth(10.4f), lineOpacity(0.96f)')
s=s.replace('lineColor("#e01e2f"), lineWidth(5.3f), lineOpacity(0.98f)', 'lineColor("#ff3048"), lineWidth(6.2f), lineOpacity(1.0f)')
s=s.replace('setCamera(userLat, userLon, offlineStyle ? 14.8 : 16.1, offlineStyle ? 38.0 : 50.0, bearing)', 'setCamera(userLat, userLon, offlineStyle ? 15.0 : 16.35, offlineStyle ? 42.0 : 56.0, bearing)')
s=s.replace('setCamera(lat, lon, offlineStyle ? 14.8 : 16.1, offlineStyle ? 38.0 : 50.0, bearing)', 'setCamera(lat, lon, offlineStyle ? 15.0 : 16.35, offlineStyle ? 42.0 : 56.0, bearing)')
rv.write_text(s)

# compact non-modal safety alert
(JAVA/'SafetyAlertOverlay.java').write_text(r'''package com.estradaplay.comunista;
import android.content.Context;import android.content.Intent;import android.graphics.Color;import android.graphics.Typeface;import android.graphics.drawable.GradientDrawable;import android.view.Gravity;import android.view.View;import android.widget.FrameLayout;import android.widget.LinearLayout;import android.widget.TextView;import java.util.Locale;
// ALERT_CARD_V201: compact automotive alert that never blocks the whole map.
final class SafetyAlertOverlay{
 private static final String TAG="epc-v201-alert";private static String lastId="";private static long lastAt;private SafetyAlertOverlay(){}
 static void show(Context c,FrameLayout host,Intent i){if(c==null||host==null||i==null)return;String type=norm(i.getStringExtra("hazard_type"));if(!supported(type))return;String id=safe(i.getStringExtra("hazard_id"));if(id.isEmpty())id=type+":"+Math.round(i.getDoubleExtra("distance_m",0));long now=System.currentTimeMillis();if(id.equals(lastId)&&now-lastAt<9000)return;lastId=id;lastAt=now;View old=host.findViewWithTag(TAG);if(old!=null)host.removeView(old);
  int accent=color(type);LinearLayout card=new LinearLayout(c);card.setTag(TAG);card.setOrientation(LinearLayout.HORIZONTAL);card.setGravity(Gravity.CENTER_VERTICAL);card.setPadding(dp(c,14),dp(c,10),dp(c,14),dp(c,10));card.setBackground(round(Color.argb(247,20,8,11),17,accent,2));card.setClickable(true);if(android.os.Build.VERSION.SDK_INT>=21)card.setElevation(dp(c,52));
  TextView icon=text(c,icon(type),24,accent,true);icon.setGravity(Gravity.CENTER);card.addView(icon,new LinearLayout.LayoutParams(dp(c,46),dp(c,50)));
  LinearLayout meta=new LinearLayout(c);meta.setOrientation(LinearLayout.VERTICAL);TextView over=text(c,title(type),9,accent,true);over.setLetterSpacing(.11f);meta.addView(over);double d=i.getDoubleExtra("distance_m",0);String road=safe(i.getStringExtra("road"));int limit=i.getIntExtra("radar_limit_kmh",i.getIntExtra("limit_kmh",0));String main=d>0?distance(d):"À FRENTE";if("RADAR".equals(type)&&limit>0)main+="  ·  "+limit+" km/h";TextView big=text(c,main,18,Color.rgb(249,239,221),true);meta.addView(big);if(!road.isEmpty()){TextView r=text(c,road,10,Color.rgb(181,154,149),false);r.setMaxLines(1);meta.addView(r);}card.addView(meta,new LinearLayout.LayoutParams(0,-2,1));
  int delta=i.getIntExtra("overspeed_delta_kmh",0);if(delta>=2&&"RADAR".equals(type)){TextView red=text(c,"REDUZA\n"+delta+" acima",9,Color.WHITE,true);red.setGravity(Gravity.CENTER);red.setBackground(round(accent,12,0,0));red.setPadding(dp(c,8),dp(c,5),dp(c,8),dp(c,5));card.addView(red,new LinearLayout.LayoutParams(dp(c,76),dp(c,48)));}
  int sw=c.getResources().getDisplayMetrics().widthPixels;int width=Math.min(dp(c,420),Math.max(dp(c,270),sw-dp(c,28)));FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(width,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);lp.setMargins(0,dp(c,112),0,0);host.addView(card,lp);card.setAlpha(0f);card.setTranslationY(-dp(c,18));card.animate().alpha(1f).translationY(0).setDuration(170).start();card.setOnClickListener(v->dismiss(host,card));card.postDelayed(()->dismiss(host,card),i.getIntExtra("alert_level",1)>=2?5200L:4200L);
 }
 private static boolean supported(String t){return"RADAR".equals(t)||"QUEBRA_MOLAS".equals(t)||"CAMERA_MONITORAMENTO".equals(t)||"SEMAFORO".equals(t)||"PEDAGIO".equals(t)||"PASSAGEM_NIVEL".equals(t);}
 private static int color(String t){if("RADAR".equals(t))return Color.rgb(239,41,58);if("QUEBRA_MOLAS".equals(t))return Color.rgb(230,155,48);if("CAMERA_MONITORAMENTO".equals(t))return Color.rgb(226,185,76);if("SEMAFORO".equals(t))return Color.rgb(255,94,80);if("PEDAGIO".equals(t))return Color.rgb(72,205,134);return Color.rgb(230,155,48);}
 private static String icon(String t){if("RADAR".equals(t))return"◎";if("QUEBRA_MOLAS".equals(t))return"⌁";if("CAMERA_MONITORAMENTO".equals(t))return"◉";if("SEMAFORO".equals(t))return"●";if("PEDAGIO".equals(t))return"$";return"!";}
 private static String title(String t){if("RADAR".equals(t))return"RADAR À FRENTE";if("QUEBRA_MOLAS".equals(t))return"QUEBRA-MOLAS À FRENTE";if("CAMERA_MONITORAMENTO".equals(t))return"CÂMERA À FRENTE";if("SEMAFORO".equals(t))return"SEMÁFORO À FRENTE";if("PEDAGIO".equals(t))return"PEDÁGIO À FRENTE";return"PASSAGEM DE NÍVEL";}
 private static String distance(double d){return d>=1000?String.format(Locale.getDefault(),"%.1f km",d/1000.0):Math.max(10,Math.round(d/10.0)*10)+" m";}
 private static void dismiss(FrameLayout h,View v){if(v==null||v.getParent()==null)return;v.animate().alpha(0).translationY(-12).setDuration(140).withEndAction(()->{try{if(v.getParent()==h)h.removeView(v);}catch(Throwable ignored){}}).start();}
 private static String norm(String raw){String t=safe(raw).toUpperCase(Locale.ROOT).replace('-','_').replace(' ','_');if(t.contains("QUEBRA")||t.contains("LOMBADA")||t.contains("BUMP"))return"QUEBRA_MOLAS";if(t.contains("CAMERA")||t.contains("CÂMERA")||t.contains("MONITOR"))return"CAMERA_MONITORAMENTO";if(t.contains("RADAR")||t.contains("SPEED_CAMERA"))return"RADAR";if(t.contains("SEMAFOR"))return"SEMAFORO";if(t.contains("PEDAG"))return"PEDAGIO";if(t.contains("NIVEL")||t.contains("NÍVEL"))return"PASSAGEM_NIVEL";return t;}
 private static TextView text(Context c,String v,float s,int color,boolean bold){TextView t=new TextView(c);t.setText(v);t.setTextSize(s);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
 private static GradientDrawable round(int color,int radius,int stroke,int sw){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(radius*density);if(stroke!=0&&sw>0)g.setStroke(Math.max(1,Math.round(sw*density)),stroke);return g;}private static float density=1f;private static int dp(Context c,float v){density=c.getResources().getDisplayMetrics().density;return Math.round(v*density);}private static String safe(String s){return s==null?"":s.trim();}
}
''')

# RoadMapActivity
p=JAVA/'RoadMapActivity.java'; s=p.read_text()
s=s.replace('private TextView navInstructionText, navLimitText, navWeatherText;', 'private TextView navInstructionText, navLimitText, navWeatherText, navDistanceText, navRoadText, navEtaText, navRemainingText, navDurationText, navTurnText;\n    private TextView mapPlayerTitle, mapPlayerArtist; private Button mapPlayerToggle; private boolean mapPlayerPlaying; private boolean playerReceiverRegistered; private LinearLayout hazardCard;')
receiver=r'''    private final BroadcastReceiver playerReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String title=intent.getStringExtra("title"),artist=intent.getStringExtra("artist");mapPlayerPlaying=intent.getBooleanExtra("playing",false);
            if(mapPlayerTitle!=null)mapPlayerTitle.setText(title==null||title.trim().isEmpty()?"Biblioteca offline":title.trim());
            if(mapPlayerArtist!=null)mapPlayerArtist.setText(artist==null||artist.trim().isEmpty()?"Música local":artist.trim());
            if(mapPlayerToggle!=null)mapPlayerToggle.setText(mapPlayerPlaying?"Ⅱ":"▶");
        }
    };

'''
s=s.replace('    private final Runnable clockTick = new Runnable() {', receiver+'    private final Runnable clockTick = new Runnable() {')
s=s.replace('        registerRoadReceiver();\n        seedLocation();', '        registerRoadReceiver();\n        registerPlayerReceiver();\n        queryPlayerState();\n        seedLocation();')
s=s.replace('                refreshDestinationRoute(lat, lon);\n            }', '                refreshDestinationRoute(lat, lon);\n                updateRouteProgress(lat, lon);\n            }',1)
s=s.replace('            if (speedText != null) speedText.setText(String.valueOf(Math.max(0, Math.round(speed))));\n            if (navLimitText != null) navLimitText.setText(limit > 0 ? String.valueOf(limit) : "—");', '            if (speedText != null) { speedText.setText(String.valueOf(Math.max(0, Math.round(speed)))); speedText.setTextColor(limit>0 && speed>limit+2 ? Color.rgb(255,67,67) : TEXT); }\n            if (navLimitText != null) navLimitText.setText(limit > 0 ? String.valueOf(limit) : "—");\n            if (hazardCard != null) hazardCard.setBackground(panel(17, Color.argb(240,17,9,11), hasHazardColor(type)));')

MAP=r'''private FrameLayout buildMapPane(int height, boolean compact) {
        FrameLayout pane=new FrameLayout(this);pane.setBackground(panel(26,Color.rgb(8,5,7),Color.rgb(73,35,40)));pane.setClipToPadding(true);roadMap=new RoadMapView(this);pane.addView(roadMap,new FrameLayout.LayoutParams(-1,-1));
        int inset=clamp(Math.round(height*.021f),dp(8),dp(15));int guideH=clamp(Math.round(height*.145f),dp(88),dp(112));
        LinearLayout guidance=new LinearLayout(this);guidance.setOrientation(LinearLayout.HORIZONTAL);guidance.setGravity(Gravity.CENTER_VERTICAL);guidance.setPadding(dp(12),dp(8),dp(12),dp(8));guidance.setBackground(panel(18,Color.argb(245,11,7,9),Color.rgb(94,51,46)));
        navTurnText=label(destination==null?"★":"↑",compact?30:40,destination==null?Color.rgb(226,185,76):Color.rgb(255,78,85),true);navTurnText.setGravity(Gravity.CENTER);guidance.addView(navTurnText,new LinearLayout.LayoutParams(dp(62),-1));
        LinearLayout distanceBox=new LinearLayout(this);distanceBox.setOrientation(LinearLayout.VERTICAL);distanceBox.setGravity(Gravity.CENTER_VERTICAL);navDistanceText=label(destination==null?"LIVRE":"—",compact?20:27,TEXT,true);distanceBox.addView(navDistanceText);TextView until=label(destination==null?"PROTEÇÃO":"ATÉ A MANOBRA",7,MUTED,true);until.setLetterSpacing(.08f);distanceBox.addView(until);guidance.addView(distanceBox,new LinearLayout.LayoutParams(compact?dp(82):dp(108),-1));
        LinearLayout gText=new LinearLayout(this);gText.setOrientation(LinearLayout.VERTICAL);gText.setGravity(Gravity.CENTER_VERTICAL);navInstructionText=label(destination==null?"Siga a estrada":"Calculando rota…",compact?14:18,TEXT,true);navInstructionText.setMaxLines(1);gText.addView(navInstructionText);navRoadText=label(destination==null?"Alertas e clima ativos":"",compact?9:11,ACCENT,true);navRoadText.setMaxLines(1);gText.addView(navRoadText);navWeatherText=label(RoadWeatherMonitor.compactStatus(this),8,Color.rgb(226,185,76),true);navWeatherText.setMaxLines(1);gText.addView(navWeatherText);guidance.addView(gText,new LinearLayout.LayoutParams(0,-1,1));gpsText=label("GPS",8,GREEN,true);gpsText.setGravity(Gravity.CENTER);gpsText.setBackground(panel(100,Color.argb(190,18,54,39),0));guidance.addView(gpsText,new LinearLayout.LayoutParams(dp(52),dp(32)));FrameLayout.LayoutParams gp=new FrameLayout.LayoutParams(-1,guideH,Gravity.TOP);gp.setMargins(inset,inset,inset,0);pane.addView(guidance,gp);
        LinearLayout speed=new LinearLayout(this);speed.setOrientation(LinearLayout.VERTICAL);speed.setGravity(Gravity.CENTER);speed.setBackground(panel(100,Color.argb(246,12,8,9),Color.rgb(126,59,46)));speedText=label("0",compact?28:36,TEXT,true);speedText.setGravity(Gravity.CENTER);speed.addView(speedText);TextView kmh=label("km/h",8,MUTED,true);kmh.setGravity(Gravity.CENTER);speed.addView(kmh);navLimitText=label("—",compact?14:17,Color.rgb(111,13,25),true);navLimitText.setGravity(Gravity.CENTER);navLimitText.setBackground(panel(100,Color.rgb(248,238,220),Color.rgb(184,20,38)));speed.addView(navLimitText,new LinearLayout.LayoutParams(dp(42),dp(30)));FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(dp(94),dp(120),Gravity.RIGHT|Gravity.TOP);sp.setMargins(0,inset+guideH+dp(10),inset,0);pane.addView(speed,sp);
        hazardCard=new LinearLayout(this);hazardCard.setOrientation(LinearLayout.VERTICAL);hazardCard.setGravity(Gravity.CENTER_VERTICAL);hazardCard.setPadding(dp(12),dp(9),dp(12),dp(9));hazardCard.setBackground(panel(17,Color.argb(240,17,9,11),Color.rgb(92,40,43)));protectionText=label("PROTEÇÃO ATIVA",7,GREEN,true);protectionText.setLetterSpacing(.11f);hazardCard.addView(protectionText);hazardTitle=label("Estrada livre à frente",compact?12:15,TEXT,true);hazardTitle.setMaxLines(1);hazardCard.addView(hazardTitle);hazardDetail=label("Monitorando seu sentido",8,MUTED,false);hazardDetail.setMaxLines(1);hazardCard.addView(hazardDetail);FrameLayout.LayoutParams ap=new FrameLayout.LayoutParams(compact?dp(170):dp(230),-2,Gravity.RIGHT|Gravity.CENTER_VERTICAL);ap.setMargins(0,dp(34),inset,0);pane.addView(hazardCard,ap);
        LinearLayout metrics=new LinearLayout(this);metrics.setOrientation(LinearLayout.HORIZONTAL);metrics.setGravity(Gravity.CENTER);metrics.setPadding(dp(8),dp(7),dp(8),dp(7));metrics.setBackground(panel(16,Color.argb(240,11,8,9),Color.rgb(72,38,38)));navEtaText=metric("--:--","CHEGADA");navRemainingText=metric("—","RESTANTE");navDurationText=metric("—","DURAÇÃO");metrics.addView(navEtaText,new LinearLayout.LayoutParams(0,dp(46),1));metrics.addView(navRemainingText,new LinearLayout.LayoutParams(0,dp(46),1));metrics.addView(navDurationText,new LinearLayout.LayoutParams(0,dp(46),1));FrameLayout.LayoutParams mp=new FrameLayout.LayoutParams(compact?-1:dp(390),dp(62),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);mp.setMargins(inset,0,inset,compact?dp(88):inset);pane.addView(metrics,mp);
        mapStateText=label("Preparando mapa e proteção…",7,Color.rgb(193,169,159),true);mapStateText.setGravity(Gravity.CENTER);mapStateText.setMaxLines(1);FrameLayout.LayoutParams stateLp=new FrameLayout.LayoutParams(-1,dp(26),Gravity.BOTTOM);stateLp.setMargins(inset,0,inset,compact?dp(62):dp(68));pane.addView(mapStateText,stateLp);
        return pane;
    }'''
s=replace_method(s,'private FrameLayout buildMapPane(int height, boolean compact)',MAP)

RIGHT=r'''private LinearLayout buildRightPanel(int height, boolean compact, boolean ultrawide) {
        LinearLayout right=new LinearLayout(this);right.setOrientation(LinearLayout.VERTICAL);int pad=clamp(Math.round(height*.022f),dp(10),dp(17));right.setPadding(pad,pad,pad,pad);right.setBackground(panel(25,Color.rgb(12,7,9),Color.rgb(72,35,40)));
        LinearLayout head=new LinearLayout(this);head.setOrientation(LinearLayout.HORIZONTAL);head.setGravity(Gravity.CENTER_VERTICAL);TextView star=label("★",20,Color.rgb(226,185,76),true);head.addView(star,new LinearLayout.LayoutParams(dp(34),dp(38)));LinearLayout titles=new LinearLayout(this);titles.setOrientation(LinearLayout.VERTICAL);titles.addView(label("EPC",compact?18:22,TEXT,true));TextView ss=label("CENTRAL AUTOMOTIVA",7,ACCENT,true);ss.setLetterSpacing(.12f);titles.addView(ss);head.addView(titles,new LinearLayout.LayoutParams(0,-2,1));clockText=label("--:--",compact?17:20,TEXT,true);head.addView(clockText);right.addView(head);int gap=clamp(Math.round(height*.016f),dp(7),dp(11));
        LinearLayout route=card();LinearLayout.LayoutParams rlp=new LinearLayout.LayoutParams(-1,0,.30f);rlp.setMargins(0,gap,0,0);right.addView(route,rlp);TextView ro=label(destination==null?"RODAGEM LIVRE":"ROTA ATIVA",8,destination==null?GREEN:ACCENT,true);ro.setLetterSpacing(.11f);route.addView(ro);route.addView(label(destination==null?"Estrada sob proteção":shortDestination(destination.label),compact?14:17,TEXT,true));destinationText=label(destination==null?"Radares, clima e alertas ativos.":"Calculando percurso…",compact?9:10,MUTED,false);route.addView(destinationText,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout quick=card();LinearLayout.LayoutParams qlp=new LinearLayout.LayoutParams(-1,0,.25f);qlp.setMargins(0,gap,0,0);right.addView(quick,qlp);TextView qo=label("ACESSO RÁPIDO",8,ACCENT,true);qo.setLetterSpacing(.11f);quick.addView(qo);LinearLayout q1=new LinearLayout(this);q1.setOrientation(LinearLayout.HORIZONTAL);Button radio=action("PTT",false),hud=action("HUD",false),dash=action("DASH",false),central=action("CENTRAL",false);for(Button b:new Button[]{radio,hud,dash,central})q1.addView(b,new LinearLayout.LayoutParams(0,dp(44),1));quick.addView(q1);radio.setOnClickListener(v->startActivity(new Intent(this,RoadRadioActivity.class)));hud.setOnClickListener(v->startActivity(new Intent(this,HudActivity.class)));dash.setOnClickListener(v->startActivity(new Intent(this,CameraActivity.class)));central.setOnClickListener(v->startActivity(new Intent(this,DriveToolsActivity.class)));
        LinearLayout media=card();LinearLayout.LayoutParams mlp=new LinearLayout.LayoutParams(-1,0,.45f);mlp.setMargins(0,gap,0,0);right.addView(media,mlp);TextView mo=label("MÚSICA OFFLINE",8,ACCENT,true);mo.setLetterSpacing(.11f);media.addView(mo);mapPlayerTitle=label("Biblioteca offline",compact?14:17,TEXT,true);mapPlayerTitle.setMaxLines(1);media.addView(mapPlayerTitle);mapPlayerArtist=label("Música local",compact?9:10,MUTED,false);mapPlayerArtist.setMaxLines(1);media.addView(mapPlayerArtist);View spacer=new View(this);media.addView(spacer,new LinearLayout.LayoutParams(1,0,1));LinearLayout controls=new LinearLayout(this);controls.setOrientation(LinearLayout.HORIZONTAL);Button prev=action("◀",false);mapPlayerToggle=action(mapPlayerPlaying?"Ⅱ":"▶",true);Button next=action("▶|",false);controls.addView(prev,new LinearLayout.LayoutParams(0,dp(46),1));controls.addView(mapPlayerToggle,new LinearLayout.LayoutParams(0,dp(46),1));controls.addView(next,new LinearLayout.LayoutParams(0,dp(46),1));media.addView(controls);Button open=action("ABRIR PLAYER",false);media.addView(open,new LinearLayout.LayoutParams(-1,dp(40)));prev.setOnClickListener(v->playerCommand(PlayerService.ACTION_PREVIOUS));mapPlayerToggle.setOnClickListener(v->playerCommand(PlayerService.ACTION_TOGGLE));next.setOnClickListener(v->playerCommand(PlayerService.ACTION_NEXT));open.setOnClickListener(v->startActivity(new Intent(this,MusicPlayerActivity.class)));queryPlayerState();return right;
    }'''
s=replace_method(s,'private LinearLayout buildRightPanel(int height, boolean compact, boolean ultrawide)',RIGHT)

ROUTE=r'''private void refreshDestinationRoute(double lat, double lon) {
        DestinationStore.Destination d=destination;if(d==null||!Double.isFinite(lat)||!Double.isFinite(lon)){if(roadMap!=null)roadMap.setRouteGeoJson(null);return;}long now=System.currentTimeMillis();if(routeLoading.get())return;if(activeRoute!=null&&Double.isFinite(lastRouteLat)&&Double.isFinite(lastRouteLon)){double moved=RoadPackStore.distanceM(lat,lon,lastRouteLat,lastRouteLon);if(moved<700&&now-lastRouteAt<120_000L)return;}if(!routeLoading.compareAndSet(false,true))return;if(destinationText!=null)destinationText.setText("Calculando rota…");routeIo.execute(()->{try{RouteEngine.Route route=RouteEngine.fetch(lat,lon,d.lat,d.lon);activeRoute=route;lastRouteAt=System.currentTimeMillis();lastRouteLat=lat;lastRouteLon=lon;ui.post(()->{if(roadMap!=null)roadMap.setRouteGeoJson(route.geoJson);if(universalRouteText!=null)universalRouteText.setText("ROTA · "+route.summary());renderRouteUi(route,0);});}catch(Throwable e){ui.post(()->{if(navInstructionText!=null)navInstructionText.setText("Rota online indisponível");if(navRoadText!=null)navRoadText.setText("A proteção da estrada continua ativa");if(destinationText!=null)destinationText.setText("Rota online indisponível. A proteção continua ativa.");});}finally{routeLoading.set(false);}});
    }'''
s=replace_method(s,'private void refreshDestinationRoute(double lat, double lon)',ROUTE)

helpers=r'''    // NAV_POLISH_V201 helpers
    private TextView metric(String value,String label){TextView t=this.label(value+"\n"+label,11,TEXT,true);t.setGravity(Gravity.CENTER);t.setLineSpacing(0,.95f);return t;}
    private int hasHazardColor(String type){String t=type==null?"":type.toUpperCase(Locale.ROOT);if(t.contains("RADAR"))return Color.rgb(239,41,58);if(t.contains("QUEBRA")||t.contains("LOMBADA"))return Color.rgb(230,155,48);if(t.contains("CAMERA")||t.contains("CÂMERA"))return Color.rgb(226,185,76);return Color.rgb(92,40,43);}
    private String maneuverGlyph(RouteEngine.Route r){if(r==null)return"↑";String m=r.maneuverModifier==null?"":r.maneuverModifier;if(r.maneuverType.contains("roundabout")||r.maneuverType.contains("rotary"))return"↻";if(r.maneuverType.contains("arrive"))return"★";if(m.contains("right"))return"↱";if(m.contains("left"))return"↰";return"↑";}
    private String navDistance(double m){if(m<=0)return"AGORA";if(m>=1000)return String.format(Locale.getDefault(),"%.1f km",m/1000.0);if(m<120)return Math.max(10,Math.round(m/10.0)*10)+" m";return Math.max(50,Math.round(m/50.0)*50)+" m";}
    private String remainDistance(double m){return m>=1000?String.format(Locale.getDefault(),"%.0f km",m/1000.0):Math.max(0,Math.round(m))+" m";}
    private String durationText(double seconds){long min=Math.max(0,Math.round(seconds/60.0)),h=min/60,m=min%60;return h>0?h+"h "+m+"m":m+" min";}
    private void renderRouteUi(RouteEngine.Route route,double moved){if(route==null)return;double rem=Math.max(0,route.distanceM-moved),ratio=route.distanceM<=0?0:Math.min(1,rem/route.distanceM),dur=Math.max(0,route.durationS*ratio),next=Math.max(0,route.nextDistanceM-moved);if(navTurnText!=null)navTurnText.setText(maneuverGlyph(route));if(navDistanceText!=null)navDistanceText.setText(navDistance(next));if(navInstructionText!=null)navInstructionText.setText(route.nextInstruction.isEmpty()?"Siga na rota":route.nextInstruction);if(navRoadText!=null)navRoadText.setText(route.nextRoad.isEmpty()?shortDestination(destination==null?"Destino":destination.label):route.nextRoad);if(navEtaText!=null){String eta=new SimpleDateFormat("HH:mm",Locale.getDefault()).format(new Date(System.currentTimeMillis()+(long)(dur*1000)));navEtaText.setText(eta+"\nCHEGADA");}if(navRemainingText!=null)navRemainingText.setText(remainDistance(rem)+"\nRESTANTE");if(navDurationText!=null)navDurationText.setText(durationText(dur)+"\nDURAÇÃO");if(destinationText!=null)destinationText.setText(remainDistance(rem)+" · "+durationText(dur)+"\n"+(route.nextInstruction.isEmpty()?"Siga na rota":route.nextInstruction));}
    private void updateRouteProgress(double lat,double lon){RouteEngine.Route r=activeRoute;if(r==null||!Double.isFinite(lastRouteLat)||!Double.isFinite(lastRouteLon))return;double moved=RoadPackStore.distanceM(lat,lon,lastRouteLat,lastRouteLon);renderRouteUi(r,Math.min(r.distanceM,moved));}
    private void playerCommand(String action){try{Intent i=new Intent(this,PlayerService.class).setAction(action);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}catch(Throwable ignored){}}
    private void queryPlayerState(){try{startService(new Intent(this,PlayerService.class).setAction(PlayerService.ACTION_QUERY_STATE));}catch(Throwable ignored){}}
    private void registerPlayerReceiver(){if(playerReceiverRegistered)return;try{IntentFilter f=new IntentFilter(PlayerService.ACTION_STATE);if(Build.VERSION.SDK_INT>=33)registerReceiver(playerReceiver,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(playerReceiver,f);playerReceiverRegistered=true;}catch(Throwable ignored){}}
    private void addPortraitPlayer(int width,int outer){LinearLayout strip=new LinearLayout(this);strip.setOrientation(LinearLayout.HORIZONTAL);strip.setGravity(Gravity.CENTER_VERTICAL);strip.setPadding(dp(11),dp(8),dp(9),dp(8));strip.setBackground(panel(17,Color.argb(246,14,8,10),Color.rgb(80,39,40)));TextView mark=label("★",20,Color.rgb(226,185,76),true);mark.setGravity(Gravity.CENTER);strip.addView(mark,new LinearLayout.LayoutParams(dp(42),dp(52)));LinearLayout meta=new LinearLayout(this);meta.setOrientation(LinearLayout.VERTICAL);mapPlayerTitle=label("Biblioteca offline",13,TEXT,true);mapPlayerTitle.setMaxLines(1);mapPlayerArtist=label("Música local",8,MUTED,false);mapPlayerArtist.setMaxLines(1);meta.addView(mapPlayerTitle);meta.addView(mapPlayerArtist);strip.addView(meta,new LinearLayout.LayoutParams(0,-2,1));Button prev=action("◀",false);mapPlayerToggle=action(mapPlayerPlaying?"Ⅱ":"▶",true);Button next=action("▶|",false);strip.addView(prev,new LinearLayout.LayoutParams(dp(48),dp(48)));strip.addView(mapPlayerToggle,new LinearLayout.LayoutParams(dp(54),dp(52)));strip.addView(next,new LinearLayout.LayoutParams(dp(48),dp(48)));prev.setOnClickListener(v->playerCommand(PlayerService.ACTION_PREVIOUS));mapPlayerToggle.setOnClickListener(v->playerCommand(PlayerService.ACTION_TOGGLE));next.setOnClickListener(v->playerCommand(PlayerService.ACTION_NEXT));strip.setOnClickListener(v->startActivity(new Intent(this,MusicPlayerActivity.class)));FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(Math.max(dp(270),width-outer*2-dp(16)),dp(70),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);lp.setMargins(0,0,0,outer+dp(7));root.addView(strip,lp);if(Build.VERSION.SDK_INT>=21)strip.setElevation(dp(76));strip.bringToFront();queryPlayerState();}

'''
s=s.replace('    private void installUniversalDriveWidgets(int width,int height){',helpers+'    private void installUniversalDriveWidgets(int width,int height){')
s=s.replace('if(Build.VERSION.SDK_INT>=21)recenter.setElevation(dp(74));\n    }', 'if(Build.VERSION.SDK_INT>=21)recenter.setElevation(dp(74));\n        addPortraitPlayer(width, outer);\n    }',1)
s=s.replace('        if (usePortraitLayout()) {\n            buildPortraitUi(width, height);\n        } else {\n            buildLandscapeUi(width, height);\n        }\n    }', '        if (usePortraitLayout()) { buildPortraitUi(width, height); } else { buildLandscapeUi(width, height); }\n        root.postDelayed(() -> EpcMotion.fadeIn(root), 35L);\n    }',1)
s=s.replace('            receiverRegistered = false;\n        }', '            receiverRegistered = false;\n        }\n        if (playerReceiverRegistered) { try { unregisterReceiver(playerReceiver); } catch (Throwable ignored) {} playerReceiverRegistered=false; }',1)
p.write_text(s)

# Home/Central motion
main=JAVA/'MainActivity.java';m=main.read_text();needle='        foot.setGravity(Gravity.CENTER); page.addView(foot); margins(foot,0,18,0,0);\n    }';m=m.replace(needle,'        foot.setGravity(Gravity.CENTER); page.addView(foot); margins(foot,0,18,0,0);\n        page.postDelayed(() -> EpcMotion.stagger(page), 55L);\n    }',1);main.write_text(m)
drive=JAVA/'DriveToolsActivity.java';d=drive.read_text();needle='page.addView(maintenance);margins(maintenance,0,10,0,0);\n    }';d=d.replace(needle,'page.addView(maintenance);margins(maintenance,0,10,0,0);\n        page.postDelayed(() -> EpcMotion.stagger(page), 55L);\n    }',1);drive.write_text(d)

print('EPC 2.0.1 automotive polish applied')

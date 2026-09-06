package com.estradaplay.comunista;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * National road-safety cache. Brazil-wide data lives on the server; the device keeps
 * only nearby tiles, trip corridors and optional legacy state snapshots. This avoids
 * loading the whole country into RAM while preserving offline alerts on travelled roads.
 */
final class RoadPackStore {
    private static final long TILE_FRESH_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final long CORRIDOR_FRESH_MS = 24L * 60L * 60L * 1000L;
    private static final long STATE_FRESH_MS = 30L * 24L * 60L * 60L * 1000L;
    private static final long MAX_AGE_MS = 120L * 24L * 60L * 60L * 1000L;
    private static final int PACK_RADIUS_M = 22000;
    private static final int RESERVE_DISTANCE_M = 250000;
    private static final int RESERVE_WIDTH_M = 22000;
    private static final double GRID_DEG = 0.16;
    private static final double CELL_DEG = 0.01;
    private static final int MAX_PACKS = 48;
    private static final String[] BRAZIL_UFS = {
            "AC","AL","AP","AM","BA","CE","DF","ES","GO","MA","MT","MS","MG","PA",
            "PB","PR","PE","PI","RJ","RN","RS","RO","RR","SC","SP","SE","TO"
    };

    private final Context app;
    private final File dir;
    private final ArrayList<Pack> packs = new ArrayList<>();
    private int cachedHazardCount = -1;

    RoadPackStore(Context context) {
        app = context.getApplicationContext();
        dir = new File(app.getFilesDir(), "road_safety_packs");
        if (!dir.exists()) dir.mkdirs();
        migrateEsStateV13();
        loadDisk();
    }

    private void migrateEsStateV13() {
        try {
            android.content.SharedPreferences m = app.getSharedPreferences("estradaplay_migrations", Context.MODE_PRIVATE);
            if (m.getBoolean("es_state_v13_reset", false)) return;
            File legacy = new File(dir, "state_es.json");
            if (legacy.isFile()) legacy.delete();
            m.edit().putBoolean("es_state_v13_reset", true).apply();
        } catch (Throwable ignored) {}
    }

    synchronized int packCount() { return packs.size(); }
    synchronized int statePackCount() { int n=0;for(Pack p:packs)if("state".equals(p.kind))n++;return n; }

    // Compatibility with the old three-state bootstrap. The national architecture no longer
    // requires RJ/MG/ES to be downloaded at startup; coverage is fetched for the road being driven.
    synchronized int coreStatePackCount() { return statePackCount(); }
    synchronized boolean coreStatesReady() { return true; }
    synchronized String coreStatesStatus() { return "Proteção nacional ativa"; }
    boolean prefetchCoreStates(ApiClient api) { return true; }

    synchronized int stateHazardCount(String uf) {
        LinkedHashMap<String,RoadHazard> unique=new LinkedHashMap<>();
        for(Pack p:packs)if("state".equals(p.kind)&&uf.equals(p.uf))for(RoadHazard h:p.hazards)unique.put(h.id,h);
        return unique.size();
    }

    synchronized long stateFetchedAt(String uf) {
        long best=0;for(Pack p:packs)if("state".equals(p.kind)&&uf.equals(p.uf))best=Math.max(best,p.fetchedAt);return best;
    }

    synchronized int hazardCount() {
        if(cachedHazardCount>=0)return cachedHazardCount;
        LinkedHashMap<String,RoadHazard> unique=new LinkedHashMap<>();
        for(Pack p:packs)for(RoadHazard h:p.hazards)unique.put(h.id,h);
        return cachedHazardCount=unique.size();
    }

    synchronized boolean hasAnyCoverage(double lat,double lon) {
        String uf=guessUfFast(lat,lon);
        for(Pack p:packs){
            if("state".equals(p.kind)&&!uf.isEmpty()&&uf.equals(p.uf))return true;
            if(distanceM(lat,lon,p.lat,p.lon)<=p.radiusM*.98)return true;
        }
        return false;
    }

    synchronized boolean hasFreshCoreCoverage(double lat,double lon) {
        long now=System.currentTimeMillis();
        for(Pack p:packs){
            if("state".equals(p.kind))continue;
            if(now-p.fetchedAt>freshMs(p))continue;
            if(distanceM(lat,lon,p.lat,p.lon)<=Math.max(10000,p.radiusM*.55))return true;
        }
        return false;
    }

    synchronized boolean needsPreparation(double lat,double lon,float heading) {
        if(!hasFreshCoreCoverage(lat,lon))return true;
        return Float.isFinite(heading)&&!hasFreshCorridorLocked(lat,lon,heading);
    }

    synchronized String status(double lat,double lon) {
        return hasAnyCoverage(lat,lon)?"Proteção offline pronta":"Proteção offline preparando";
    }

    boolean prepareTravelReserve(ApiClient api,double lat,double lon,float heading) {
        if(api==null)return false;
        boolean ok=fetchCoverage(api,lat,lon);
        if(Float.isFinite(heading))ok=fetchCorridor(api,lat,lon,heading)||ok;
        // State packages remain available as a fallback, but are not required for normal national use.
        if(!ok){String uf=resolveUf(lat,lon);if(isSupportedUf(uf))ok=fetchStateCoverage(api,uf);}
        if(!ok)ok=fetchOpenStreetMapSafetyNear(lat,lon);
        return ok;
    }

    boolean fetchCoverage(ApiClient api,double lat,double lon) {
        if(api==null)return false;
        if(hasFreshCoreCoverage(lat,lon))return true;
        double centerLat=Math.round(lat/GRID_DEG)*GRID_DEG,centerLon=Math.round(lon/GRID_DEG)*GRID_DEG;
        String key=packKey(centerLat,centerLon);
        synchronized(this){Pack existing=findByKey(key);if(existing!=null&&System.currentTimeMillis()-existing.fetchedAt<=freshMs(existing))return true;}
        try{
            String path=String.format(Locale.US,"api/road_pack.php?lat=%.6f&lon=%.6f&radius=%d",centerLat,centerLon,PACK_RADIUS_M);
            ApiClient.Response response=api.get(path);JSONObject json=response.json();JSONArray hazards=json.optJSONArray("hazards");JSONObject coverage=json.optJSONObject("coverage");
            boolean sourceOk=coverage==null||coverage.optBoolean("source_ok",coverage.optBoolean("osm_ok",true));
            if(!response.ok()||!json.optBoolean("ok",false)||hazards==null){return legacyOrDirectFallback(api,lat,lon);}
            // An empty but healthy national tile is still valid coverage: there may simply be no point nearby.
            if(hazards.length()==0&&!sourceOk)return legacyOrDirectFallback(api,lat,lon);
            JSONObject stored=new JSONObject();stored.put("key",key);stored.put("kind","tile");stored.put("center_lat",centerLat);stored.put("center_lon",centerLon);
            stored.put("radius_m",json.optInt("radius_m",PACK_RADIUS_M));stored.put("fetched_at",System.currentTimeMillis());stored.put("source_ok",sourceOk);stored.put("hazards",hazards);stored.put("coverage",coverage);
            return savePack(key,stored);
        }catch(Exception ignored){return legacyOrDirectFallback(api,lat,lon);}
    }

    private boolean legacyOrDirectFallback(ApiClient api,double lat,double lon) {
        if(fetchMusicRoadNearRadars(api,lat,lon))return true;
        String uf=resolveUf(lat,lon);
        if(isSupportedUf(uf)){
            if(fetchMusicRoadStateRadars(api,uf))return true;
            if(fetchStateCoverage(api,uf))return true;
        }
        return fetchOpenStreetMapSafetyNear(lat,lon);
    }

    private boolean fetchCorridor(ApiClient api,double lat,double lon,float heading) {
        synchronized(this){if(hasFreshCorridorLocked(lat,lon,heading))return true;}
        try{
            String path=String.format(Locale.US,"api/road_corridor_pack.php?lat=%.6f&lon=%.6f&heading=%.1f&distance=%d&width=%d",lat,lon,heading,RESERVE_DISTANCE_M,RESERVE_WIDTH_M);
            ApiClient.Response response=api.getLong(path);JSONObject json=response.json();JSONArray hazards=json.optJSONArray("hazards");JSONObject coverage=json.optJSONObject("coverage");
            boolean sourceOk=coverage==null||coverage.optBoolean("source_ok",coverage.optBoolean("osm_ok",true));
            if(!response.ok()||!json.optBoolean("ok",false)||hazards==null)return false;
            if(hazards.length()==0&&!sourceOk)return false;
            JSONObject center=json.optJSONObject("center");double[] fallback=destination(lat,lon,heading,RESERVE_DISTANCE_M/2.0);
            double centerLat=center==null?fallback[0]:center.optDouble("lat",fallback[0]);double centerLon=center==null?fallback[1]:center.optDouble("lon",fallback[1]);
            String key=corridorKey(lat,lon,heading);JSONObject stored=new JSONObject();stored.put("key",key);stored.put("kind","corridor");stored.put("center_lat",centerLat);stored.put("center_lon",centerLon);
            stored.put("radius_m",RESERVE_DISTANCE_M/2+RESERVE_WIDTH_M+18000);stored.put("start_lat",lat);stored.put("start_lon",lon);stored.put("heading",heading);stored.put("distance_m",json.optInt("distance_m",RESERVE_DISTANCE_M));stored.put("width_m",json.optInt("width_m",RESERVE_WIDTH_M));stored.put("fetched_at",System.currentTimeMillis());stored.put("source_ok",sourceOk);stored.put("hazards",hazards);stored.put("coverage",coverage);
            return savePack(key,stored);
        }catch(Exception ignored){return false;}
    }

    private boolean fetchMusicRoadNearRadars(ApiClient api,double lat,double lon) {
        if(api==null)return false;
        try{
            String path=String.format(Locale.US,"api/radars.php?action=near&lat=%.6f&lon=%.6f&radius=50000",lat,lon);ApiClient.Response response=api.getLong(path);JSONObject json=response.json();JSONArray radars=json.optJSONArray("radars");
            if(!response.ok()||!json.optBoolean("ok",false)||radars==null||radars.length()==0)return false;
            double centerLat=Math.round(lat/GRID_DEG)*GRID_DEG,centerLon=Math.round(lon/GRID_DEG)*GRID_DEG;String key="mrnear_"+packKey(centerLat,centerLon);JSONObject stored=new JSONObject();
            stored.put("key",key);stored.put("kind","tile");stored.put("center_lat",centerLat);stored.put("center_lon",centerLon);stored.put("radius_m",50000);stored.put("fetched_at",System.currentTimeMillis());stored.put("source_ok",true);stored.put("hazards",radars);return savePack(key,stored);
        }catch(Exception ignored){return false;}
    }

    /** Direct public fallback when the Estrada Play service is unavailable. */
    private boolean fetchOpenStreetMapSafetyNear(double lat,double lon) {
        try{
            String query=String.format(Locale.US,
                    "[out:json][timeout:32];("+
                    "node(around:50000,%.6f,%.6f)[\"highway\"=\"speed_camera\"];"+
                    "node(around:50000,%.6f,%.6f)[\"enforcement\"~\"maxspeed|traffic_signals|redlight\"];"+
                    "node(around:50000,%.6f,%.6f)[\"highway\"=\"traffic_signals\"];"+
                    "node(around:50000,%.6f,%.6f)[\"highway\"=\"speed_bump\"];"+
                    "node(around:50000,%.6f,%.6f)[\"traffic_calming\"~\"bump|hump|table|cushion|yes\"];"+
                    "node(around:50000,%.6f,%.6f)[\"man_made\"=\"surveillance\"][\"surveillance\"=\"traffic\"];"+
                    "node(around:50000,%.6f,%.6f)[\"man_made\"=\"surveillance\"][\"surveillance:zone\"=\"traffic\"];"+
                    "node(around:50000,%.6f,%.6f)[\"camera:type\"=\"traffic\"];"+
                    ");out tags;",
                    lat,lon,lat,lon,lat,lon,lat,lon,lat,lon,lat,lon,lat,lon,lat,lon);
            String target="https://overpass-api.de/api/interpreter?data="+URLEncoder.encode(query,"UTF-8");JSONObject root=directHttpJson(target,18_000_000);JSONArray elements=root.optJSONArray("elements");if(elements==null)return false;
            JSONArray hazards=new JSONArray();
            for(int i=0;i<elements.length();i++){
                JSONObject e=elements.optJSONObject(i);if(e==null)continue;double rlat=e.optDouble("lat",Double.NaN),rlon=e.optDouble("lon",Double.NaN);if(!Double.isFinite(rlat)||!Double.isFinite(rlon))continue;JSONObject tags=e.optJSONObject("tags");if(tags==null)tags=new JSONObject();
                String type=directType(tags);if(type.isEmpty())continue;JSONObject h=new JSONObject();h.put("id","osm-node-"+e.optLong("id",i));h.put("type",type);h.put("lat",rlat);h.put("lon",rlon);h.put("road",tags.optString("ref",tags.optString("name","")));h.put("speed",parseOsmSpeed(tags.optString("maxspeed","")));
                String direction=tags.optString("direction",tags.optString("camera:direction",""));try{h.put("heading",Double.parseDouble(direction.trim()));}catch(Throwable ignored){}h.put("source","OpenStreetMap");hazards.put(h);
            }
            double centerLat=Math.round(lat/GRID_DEG)*GRID_DEG,centerLon=Math.round(lon/GRID_DEG)*GRID_DEG;String key="osmnear_"+packKey(centerLat,centerLon);JSONObject stored=new JSONObject();stored.put("key",key);stored.put("kind","tile");stored.put("center_lat",centerLat);stored.put("center_lon",centerLon);stored.put("radius_m",50000);stored.put("fetched_at",System.currentTimeMillis());stored.put("source_ok",true);stored.put("hazards",hazards);return savePack(key,stored);
        }catch(Throwable ignored){return false;}
    }

    private static String directType(JSONObject tags) {
        String highway=tags.optString("highway","").toLowerCase(Locale.ROOT),enforcement=tags.optString("enforcement","").toLowerCase(Locale.ROOT),calming=tags.optString("traffic_calming","").toLowerCase(Locale.ROOT);
        if("speed_camera".equals(highway)||enforcement.contains("maxspeed"))return "RADAR";
        if(enforcement.contains("redlight")||enforcement.contains("traffic_signals"))return "SEMAFORO_RADAR";
        if("traffic_signals".equals(highway))return "SEMAFORO";
        if("speed_bump".equals(highway)||calming.matches("bump|hump|table|cushion|yes"))return "QUEBRA_MOLAS";
        String man=tags.optString("man_made","").toLowerCase(Locale.ROOT),surv=tags.optString("surveillance","").toLowerCase(Locale.ROOT),zone=tags.optString("surveillance:zone","").toLowerCase(Locale.ROOT),camera=tags.optString("camera:type","").toLowerCase(Locale.ROOT);
        if(("surveillance".equals(man)&&("traffic".equals(surv)||"traffic".equals(zone)))||"traffic".equals(camera))return "CAMERA_MONITORAMENTO";
        return "";
    }

    private static int parseOsmSpeed(String raw) {
        if(raw==null)return 0;String t=raw.trim().toLowerCase(Locale.ROOT),digits=t.replaceAll("[^0-9]","");if(digits.isEmpty())return 0;
        try{int v=Integer.parseInt(digits);if(t.contains("mph"))v=(int)Math.round(v*1.609344);return v>=10&&v<=180?v:0;}catch(Throwable ignored){return 0;}
    }

    private static JSONObject directHttpJson(String target,int maxBytes)throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(target).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(50000);c.setInstanceFollowRedirects(true);c.setRequestProperty("Accept","application/json");c.setRequestProperty("User-Agent","EstradaPlayComunista/3.0 Android");int code=c.getResponseCode();if(code<200||code>=300){c.disconnect();throw new Exception("HTTP "+code);}
        try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buf=new byte[32768];int n;while((n=in.read(buf))>0){out.write(buf,0,n);if(out.size()>maxBytes)throw new Exception("Resposta grande demais");}return new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));}finally{c.disconnect();}
    }

    /** Legacy MusicRoad full-state source retained for its four historical states. */
    private boolean fetchMusicRoadStateRadars(ApiClient api,String uf) {
        if(api==null||!isSupportedUf(uf))return false;synchronized(this){if(hasFreshStateWithHazardsLocked(uf))return true;}String[] info=musicRoadStateInfo(uf);if(info==null)return false;
        try{
            String path="api/offline_state.php?kind=radars&state_id="+info[0]+"&uf="+URLEncoder.encode(uf,"UTF-8")+"&state="+URLEncoder.encode(info[1],"UTF-8");ApiClient.Response response=api.getLong(path);JSONObject json=response.json();JSONArray radars=json.optJSONArray("radars");if(!response.ok()||!json.optBoolean("ok",false)||radars==null||radars.length()==0)return false;
            return saveStatePack(uf,radars,true,json.optJSONObject("coverage"));
        }catch(Exception ignored){return false;}
    }

    private synchronized boolean hasFreshStateWithHazardsLocked(String uf) {
        long now=System.currentTimeMillis();for(Pack p:packs)if("state".equals(p.kind)&&uf.equals(p.uf)&&now-p.fetchedAt<=freshMs(p)&&p.hazards!=null&&!p.hazards.isEmpty())return true;return false;
    }

    private static String[] musicRoadStateInfo(String uf) {
        if("SP".equals(uf))return new String[]{"35","São Paulo"};if("MG".equals(uf))return new String[]{"31","Minas Gerais"};if("ES".equals(uf))return new String[]{"32","Espírito Santo"};if("RJ".equals(uf))return new String[]{"33","Rio de Janeiro"};return null;
    }

    private boolean fetchStateCoverage(ApiClient api,String uf) {
        if(api==null||!isSupportedUf(uf))return false;synchronized(this){if(hasFreshStateLocked(uf))return true;}
        try{
            ApiClient.Response response=api.getLong("api/road_state_pack.php?uf="+URLEncoder.encode(uf,"UTF-8"));JSONObject json=response.json();JSONArray hazards=json.optJSONArray("hazards");if(!response.ok()||!json.optBoolean("ok",false)||hazards==null||hazards.length()==0)return false;JSONObject coverage=json.optJSONObject("coverage");boolean sourceOk=coverage==null||coverage.optBoolean("source_ok",coverage.optBoolean("osm_ok",true));return saveStatePack(uf,hazards,sourceOk,coverage);
        }catch(Exception ignored){return false;}
    }

    private boolean saveStatePack(String uf,JSONArray hazards,boolean sourceOk,JSONObject coverage)throws Exception {
        double[] g=stateGeometry(uf);String key="state_"+uf.toLowerCase(Locale.ROOT);JSONObject stored=new JSONObject();stored.put("key",key);stored.put("kind","state");stored.put("uf",uf);stored.put("center_lat",g[0]);stored.put("center_lon",g[1]);stored.put("radius_m",(int)g[2]);stored.put("fetched_at",System.currentTimeMillis());stored.put("source_ok",sourceOk);stored.put("hazards",hazards);if(coverage!=null)stored.put("coverage",coverage);return savePack(key,stored);
    }

    private boolean savePack(String key,JSONObject stored) {
        try{
            File target=new File(dir,key+".json"),temp=new File(dir,key+".json.tmp");writeText(temp,stored.toString());Pack parsed=parsePack(temp,stored);if(parsed==null){temp.delete();return false;}if(target.exists()&&!target.delete()){temp.delete();return false;}if(!temp.renameTo(target)){writeText(target,stored.toString());temp.delete();}Pack p=parsePack(target,stored);if(p==null)return false;synchronized(this){Pack old=findByKey(key);if(old!=null)packs.remove(old);packs.add(p);cleanupLocked();}return true;
        }catch(Exception e){return false;}
    }

    synchronized List<RoadHazard> nearby(double lat,double lon,double maxMeters) {
        LinkedHashMap<String,RoadHazard> out=new LinkedHashMap<>();int baseY=cell(lat),baseX=cell(lon),span=Math.max(1,(int)Math.ceil(maxMeters/900.0));
        for(Pack p:packs){if(distanceM(lat,lon,p.lat,p.lon)>p.radiusM+maxMeters+2500)continue;for(int y=baseY-span;y<=baseY+span;y++)for(int x=baseX-span;x<=baseX+span;x++){List<RoadHazard> bucket=p.index.get(cellKey(y,x));if(bucket==null)continue;for(RoadHazard h:bucket)if(distanceM(lat,lon,h.lat,h.lon)<=maxMeters)out.put(h.id,h);}}
        return new ArrayList<>(out.values());
    }

    private synchronized Pack findByKey(String key){for(Pack p:packs)if(p.key.equals(key))return p;return null;}
    private boolean hasFreshStateLocked(String uf){long now=System.currentTimeMillis();for(Pack p:packs)if("state".equals(p.kind)&&uf.equals(p.uf)&&!p.hazards.isEmpty()&&now-p.fetchedAt<=freshMs(p))return true;return false;}
    private boolean hasStateLocked(String uf){for(Pack p:packs)if("state".equals(p.kind)&&uf.equals(p.uf)&&!p.hazards.isEmpty())return true;return false;}
    private boolean hasFreshCorridorLocked(double lat,double lon,float heading){long now=System.currentTimeMillis();for(Pack p:packs){if(!"corridor".equals(p.kind)||now-p.fetchedAt>freshMs(p))continue;if(distanceM(lat,lon,p.startLat,p.startLon)>42000)continue;if(angleDiff(heading,p.heading)>32.0)continue;return true;}return false;}
    private boolean hasRecentCorridorNearLocked(double lat,double lon){long now=System.currentTimeMillis();for(Pack p:packs)if("corridor".equals(p.kind)&&now-p.fetchedAt<7L*24L*60L*60L*1000L&&distanceM(lat,lon,p.startLat,p.startLon)<90000)return true;return false;}
    private long freshMs(Pack p){long base="state".equals(p.kind)?STATE_FRESH_MS:("corridor".equals(p.kind)?CORRIDOR_FRESH_MS:TILE_FRESH_MS);if(!p.sourceOk)return Math.min(base,12L*60L*60L*1000L);return base;}

    private void loadDisk() {
        File[] files=dir.listFiles((d,n)->n.endsWith(".json"));if(files==null)return;long now=System.currentTimeMillis();
        for(File f:files){try{JSONObject json=new JSONObject(readText(f));long fetched=json.optLong("fetched_at",f.lastModified());String kind=json.optString("kind","tile");if(!"state".equals(kind)&&now-fetched>MAX_AGE_MS){f.delete();continue;}Pack p=parsePack(f,json);if(p!=null&&"state".equals(p.kind)&&p.hazards.isEmpty()){f.delete();continue;}if(p!=null)packs.add(p);}catch(Exception ignored){}}
        synchronized(this){cleanupLocked();}
    }

    private Pack parsePack(File file,JSONObject json) {
        try{
            String key=json.optString("key",file.getName().replace(".json","")),kind=json.optString("kind","tile"),uf=json.optString("uf","");double lat=json.optDouble("center_lat",Double.NaN),lon=json.optDouble("center_lon",Double.NaN);int radius=json.optInt("radius_m",PACK_RADIUS_M);long fetched=json.optLong("fetched_at",file.lastModified());double startLat=json.optDouble("start_lat",lat),startLon=json.optDouble("start_lon",lon);float heading=(float)json.optDouble("heading",Double.NaN);boolean sourceOk=json.optBoolean("source_ok",true);JSONArray arr=json.optJSONArray("hazards");if(!Double.isFinite(lat)||!Double.isFinite(lon)||arr==null)return null;
            ArrayList<RoadHazard> hazards=new ArrayList<>();HashMap<Long,List<RoadHazard>> index=new HashMap<>();for(int i=0;i<arr.length();i++){RoadHazard h=RoadHazard.fromJson(arr.optJSONObject(i));if(h==null)continue;hazards.add(h);long ck=cellKey(cell(h.lat),cell(h.lon));List<RoadHazard> bucket=index.get(ck);if(bucket==null){bucket=new ArrayList<>();index.put(ck,bucket);}bucket.add(h);}return new Pack(key,kind,uf,lat,lon,radius,fetched,startLat,startLon,heading,sourceOk,file,hazards,index);
        }catch(Exception e){return null;}
    }

    private String resolveUf(double lat,double lon) {
        try{
            if(Geocoder.isPresent()){
                Geocoder g=new Geocoder(app,new Locale("pt","BR"));List<Address> list=g.getFromLocation(lat,lon,1);if(list!=null&&!list.isEmpty()){Address a=list.get(0);String code=normalizeUf(a.getAdminArea());if(code.isEmpty())code=normalizeUf(a.getSubAdminArea());if(!code.isEmpty())return code;}
            }
        }catch(Throwable ignored){}
        return guessUfFast(lat,lon);
    }

    private static String normalizeUf(String raw) {
        String v=raw==null?"":raw.trim().toUpperCase(Locale.ROOT);if(isSupportedUf(v))return v;
        v=v.replace('Á','A').replace('À','A').replace('Ã','A').replace('Â','A').replace('É','E').replace('Ê','E').replace('Í','I').replace('Ó','O').replace('Ô','O').replace('Õ','O').replace('Ú','U').replace('Ç','C');
        switch(v){
            case "ACRE":return"AC";case "ALAGOAS":return"AL";case "AMAPA":return"AP";case "AMAZONAS":return"AM";case "BAHIA":return"BA";case "CEARA":return"CE";case "DISTRITO FEDERAL":return"DF";case "ESPIRITO SANTO":return"ES";case "GOIAS":return"GO";case "MARANHAO":return"MA";case "MATO GROSSO":return"MT";case "MATO GROSSO DO SUL":return"MS";case "MINAS GERAIS":return"MG";case "PARA":return"PA";case "PARAIBA":return"PB";case "PARANA":return"PR";case "PERNAMBUCO":return"PE";case "PIAUI":return"PI";case "RIO DE JANEIRO":return"RJ";case "RIO GRANDE DO NORTE":return"RN";case "RIO GRANDE DO SUL":return"RS";case "RONDONIA":return"RO";case "RORAIMA":return"RR";case "SANTA CATARINA":return"SC";case "SAO PAULO":return"SP";case "SERGIPE":return"SE";case "TOCANTINS":return"TO";default:return"";
        }
    }

    /** Bounding-box fallback only. Overlaps are harmless because nearby tiles are geographic. */
    private static String guessUfFast(double lat,double lon) {
        String best="";double bestArea=Double.POSITIVE_INFINITY;
        for(String uf:BRAZIL_UFS){double[] b=stateBounds(uf);if(lat<b[0]||lat>b[2]||lon<b[1]||lon>b[3])continue;double area=(b[2]-b[0])*(b[3]-b[1]);if(area<bestArea){bestArea=area;best=uf;}}
        return best;
    }

    private static boolean isSupportedUf(String uf){if(uf==null)return false;for(String x:BRAZIL_UFS)if(x.equals(uf))return true;return false;}

    private static double[] stateBounds(String uf) {
        switch(uf){
            case"AC":return new double[]{-11.20,-74.05,-7.05,-66.55};case"AL":return new double[]{-10.55,-38.25,-8.75,-35.10};case"AP":return new double[]{-1.30,-54.95,4.50,-49.80};case"AM":return new double[]{-9.90,-73.85,2.30,-56.05};case"BA":return new double[]{-18.40,-46.75,-8.45,-37.25};case"CE":return new double[]{-7.95,-41.50,-2.70,-37.20};case"DF":return new double[]{-16.10,-48.30,-15.45,-47.30};case"ES":return new double[]{-21.35,-41.95,-17.75,-39.55};case"GO":return new double[]{-19.55,-53.30,-12.35,-45.85};case"MA":return new double[]{-10.35,-48.80,-0.95,-41.70};case"MT":return new double[]{-18.10,-61.70,-7.30,-50.15};case"MS":return new double[]{-24.15,-58.25,-17.10,-50.85};case"MG":return new double[]{-23.00,-51.15,-14.10,-39.75};case"PA":return new double[]{-9.90,-58.95,2.65,-45.95};case"PB":return new double[]{-8.35,-38.85,-6.00,-34.75};case"PR":return new double[]{-26.75,-54.70,-22.50,-48.00};case"PE":return new double[]{-9.55,-41.40,-7.10,-34.75};case"PI":return new double[]{-10.95,-45.95,-2.70,-40.30};case"RJ":return new double[]{-23.45,-44.95,-20.65,-40.75};case"RN":return new double[]{-7.00,-38.65,-4.80,-34.90};case"RS":return new double[]{-33.80,-57.70,-27.00,-49.60};case"RO":return new double[]{-13.75,-66.05,-7.90,-59.60};case"RR":return new double[]{0.75,-64.85,5.35,-58.80};case"SC":return new double[]{-29.40,-53.90,-25.90,-48.30};case"SP":return new double[]{-25.45,-53.25,-19.65,-44.00};case"SE":return new double[]{-11.60,-38.30,-9.45,-36.35};case"TO":return new double[]{-13.55,-50.80,-5.05,-45.65};default:return new double[]{-34,-74,6,-34};
        }
    }

    private static double[] stateGeometry(String uf) {
        double[] b=stateBounds(uf);double lat=(b[0]+b[2])/2.0,lon=(b[1]+b[3])/2.0;double radius=Math.max(distanceM(lat,lon,b[0],b[1]),distanceM(lat,lon,b[2],b[3]))+30000;return new double[]{lat,lon,radius};
    }

    private static String readText(File file)throws Exception {
        try(FileInputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buf=new byte[16384];int n;while((n=in.read(buf))>0){out.write(buf,0,n);if(out.size()>80_000_000)throw new IllegalStateException("Pacote grande demais");}return new String(out.toByteArray(),StandardCharsets.UTF_8);}
    }
    private static void writeText(File file,String text)throws Exception {byte[] data=text.getBytes(StandardCharsets.UTF_8);try(FileOutputStream out=new FileOutputStream(file,false)){out.write(data);out.flush();}}

    private void cleanupLocked() {
        cachedHazardCount=-1;long now=System.currentTimeMillis();ArrayList<Pack> stale=new ArrayList<>();for(Pack p:packs){if("state".equals(p.kind))continue;if(now-p.fetchedAt>MAX_AGE_MS)stale.add(p);}for(Pack p:stale){packs.remove(p);p.file.delete();}
        if(packs.size()<=MAX_PACKS)return;ArrayList<Pack> removable=new ArrayList<>();for(Pack p:packs)if(!"state".equals(p.kind))removable.add(p);Collections.sort(removable,Comparator.comparingLong(p->p.fetchedAt));while(packs.size()>MAX_PACKS&&!removable.isEmpty()){Pack p=removable.remove(0);packs.remove(p);p.file.delete();}
    }

    private static int cell(double v){return(int)Math.floor(v/CELL_DEG);}private static long cellKey(int y,int x){return(((long)y)<<32)^(x&0xffffffffL);}
    private static String packKey(double lat,double lon){return String.format(Locale.US,"p_%+.3f_%+.3f",lat,lon).replace('+','p').replace('-','m').replace('.','_');}
    private static String corridorKey(double lat,double lon,float heading){int hb=((int)Math.round(heading/30.0))*30%360;return String.format(Locale.US,"corr_%+.2f_%+.2f_%03d",lat,lon,hb).replace('+','p').replace('-','m').replace('.','_');}
    private static double[] destination(double lat,double lon,double headingDeg,double distanceM){double r=6371000.0,br=Math.toRadians(headingDeg),d=distanceM/r,p1=Math.toRadians(lat),l1=Math.toRadians(lon);double p2=Math.asin(Math.sin(p1)*Math.cos(d)+Math.cos(p1)*Math.sin(d)*Math.cos(br));double l2=l1+Math.atan2(Math.sin(br)*Math.sin(d)*Math.cos(p1),Math.cos(d)-Math.sin(p1)*Math.sin(p2));double lon2=((Math.toDegrees(l2)+540.0)%360.0)-180.0;return new double[]{Math.toDegrees(p2),lon2};}
    private static double angleDiff(double a,double b){double d=Math.abs(a-b)%360.0;return d>180.0?360.0-d:d;}
    static double distanceM(double lat1,double lon1,double lat2,double lon2){double r=6371000.0,p1=Math.toRadians(lat1),p2=Math.toRadians(lat2),dp=Math.toRadians(lat2-lat1),dl=Math.toRadians(lon2-lon1);double a=Math.sin(dp/2)*Math.sin(dp/2)+Math.cos(p1)*Math.cos(p2)*Math.sin(dl/2)*Math.sin(dl/2);return r*2*Math.atan2(Math.sqrt(a),Math.sqrt(Math.max(0.0,1.0-a)));}

    private static final class Pack {
        final String key,kind,uf;final double lat,lon,startLat,startLon;final int radiusM;final long fetchedAt;final float heading;final boolean sourceOk;final File file;final List<RoadHazard> hazards;final Map<Long,List<RoadHazard>> index;
        Pack(String key,String kind,String uf,double lat,double lon,int radiusM,long fetchedAt,double startLat,double startLon,float heading,boolean sourceOk,File file,List<RoadHazard> hazards,Map<Long,List<RoadHazard>> index){this.key=key;this.kind=kind;this.uf=uf;this.lat=lat;this.lon=lon;this.radiusM=radiusM;this.fetchedAt=fetchedAt;this.startLat=startLat;this.startLon=startLon;this.heading=heading;this.sourceOk=sourceOk;this.file=file;this.hazards=hazards;this.index=index;}
    }
}

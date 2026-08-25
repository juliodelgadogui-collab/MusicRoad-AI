package com.estradaplay.app;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class RoadPackStore {
    private static final long TILE_FRESH_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final long CORRIDOR_FRESH_MS = 24L * 60L * 60L * 1000L;
    private static final long STATE_FRESH_MS = 30L * 24L * 60L * 60L * 1000L;
    private static final long MAX_AGE_MS = 120L * 24L * 60L * 60L * 1000L;
    private static final long STATE_MAX_AGE_MS = 180L * 24L * 60L * 60L * 1000L;
    private static final int PACK_RADIUS_M = 22000;
    private static final int RESERVE_DISTANCE_M = 250000;
    private static final int RESERVE_WIDTH_M = 22000;
    private static final double GRID_DEG = 0.16;
    private static final double CELL_DEG = 0.01;
    private static final int MAX_PACKS = 32;

    private final Context app;
    private final File dir;
    private final ArrayList<Pack> packs = new ArrayList<>();

    RoadPackStore(Context context) {
        app = context.getApplicationContext();
        dir = new File(app.getFilesDir(), "road_safety_packs");
        if (!dir.exists()) dir.mkdirs();
        loadDisk();
    }

    synchronized int packCount() { return packs.size(); }

    synchronized int statePackCount() {
        int n=0; for(Pack p:packs) if("state".equals(p.kind)) n++; return n;
    }

    synchronized int hazardCount() {
        LinkedHashMap<String, RoadHazard> unique = new LinkedHashMap<>();
        for (Pack p : packs) for (RoadHazard h : p.hazards) unique.put(h.id, h);
        return unique.size();
    }

    synchronized boolean hasAnyCoverage(double lat, double lon) {
        String uf = guessUfFast(lat, lon);
        for (Pack p : packs) {
            if ("state".equals(p.kind) && !uf.isEmpty() && uf.equals(p.uf)) return true;
            if (distanceM(lat, lon, p.lat, p.lon) <= p.radiusM * 0.98) return true;
        }
        return false;
    }

    synchronized boolean hasFreshCoreCoverage(double lat, double lon) {
        long now = System.currentTimeMillis();
        for (Pack p : packs) {
            if ("state".equals(p.kind)) continue;
            if (now - p.fetchedAt > freshMs(p)) continue;
            if (distanceM(lat, lon, p.lat, p.lon) <= Math.max(10000, p.radiusM * 0.55)) return true;
        }
        return false;
    }

    synchronized boolean needsPreparation(double lat, double lon, float heading) {
        if (!hasFreshCoreCoverage(lat, lon)) return true;
        String uf=guessUfFast(lat,lon);
        if (isSupportedUf(uf) && !hasFreshStateLocked(uf)) return true;
        return Float.isFinite(heading) && !hasFreshCorridorLocked(lat,lon,heading);
    }

    synchronized String status(double lat, double lon) {
        if (packs.isEmpty()) return "Preparando proteção offline…";
        String uf=guessUfFast(lat,lon);
        boolean stateReady=isSupportedUf(uf) && hasStateLocked(uf);
        boolean reserveReady=hasRecentCorridorNearLocked(lat,lon);
        int nearby = nearby(lat, lon, 2400).size();
        StringBuilder s=new StringBuilder("Offline · ");
        if(stateReady)s.append(uf).append(" estadual"); else s.append(packCount()).append(" área(s)");
        if(reserveReady)s.append(" + reserva 250 km");
        s.append(" · ").append(hazardCount()).append(" pontos");
        if(nearby>0)s.append(" · ").append(nearby).append(" próximos");
        return s.toString();
    }

    boolean prepareTravelReserve(ApiClient api, double lat, double lon, float heading) {
        if (api == null || api.cookie() == null || api.cookie().trim().isEmpty()) return false;
        boolean ok = fetchCoverage(api, lat, lon);
        if (Float.isFinite(heading)) ok = fetchCorridor(api, lat, lon, heading) || ok;
        String uf = resolveUf(lat, lon);
        if (isSupportedUf(uf)) ok = fetchStateCoverage(api, uf) || ok;
        return ok;
    }

    boolean fetchCoverage(ApiClient api, double lat, double lon) {
        if (api == null || api.cookie() == null || api.cookie().trim().isEmpty()) return false;
        if (hasFreshCoreCoverage(lat, lon)) return true;
        double centerLat = Math.round(lat / GRID_DEG) * GRID_DEG;
        double centerLon = Math.round(lon / GRID_DEG) * GRID_DEG;
        String key = packKey(centerLat, centerLon);
        synchronized (this) {
            Pack existing = findByKey(key);
            if (existing != null && System.currentTimeMillis() - existing.fetchedAt <= freshMs(existing)) return true;
        }
        try {
            String path = String.format(Locale.US, "api/road_pack.php?lat=%.6f&lon=%.6f&radius=%d", centerLat, centerLon, PACK_RADIUS_M);
            ApiClient.Response response = api.get(path);
            JSONObject json = response.json();
            JSONArray hazards = json.optJSONArray("hazards");
            if (!response.ok() || !json.optBoolean("ok", false) || hazards == null) return false;
            JSONObject stored = new JSONObject();
            stored.put("key", key);
            stored.put("kind", "tile");
            stored.put("center_lat", centerLat);
            stored.put("center_lon", centerLon);
            stored.put("radius_m", json.optInt("radius_m", PACK_RADIUS_M));
            stored.put("fetched_at", System.currentTimeMillis());
            stored.put("source_ok", json.optJSONObject("coverage") == null || json.optJSONObject("coverage").optBoolean("osm_ok", true));
            stored.put("hazards", hazards);
            stored.put("coverage", json.optJSONObject("coverage"));
            return savePack(key, stored);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean fetchCorridor(ApiClient api, double lat, double lon, float heading) {
        synchronized (this) { if (hasFreshCorridorLocked(lat,lon,heading)) return true; }
        try {
            String path=String.format(Locale.US,
                    "api/road_corridor_pack.php?lat=%.6f&lon=%.6f&heading=%.1f&distance=%d&width=%d",
                    lat,lon,heading,RESERVE_DISTANCE_M,RESERVE_WIDTH_M);
            ApiClient.Response response=api.getLong(path);
            JSONObject json=response.json(); JSONArray hazards=json.optJSONArray("hazards");
            if(!response.ok()||!json.optBoolean("ok",false)||hazards==null)return false;
            JSONObject center=json.optJSONObject("center");
            double centerLat=center==null?destination(lat,lon,heading,RESERVE_DISTANCE_M/2.0)[0]:center.optDouble("lat",Double.NaN);
            double centerLon=center==null?destination(lat,lon,heading,RESERVE_DISTANCE_M/2.0)[1]:center.optDouble("lon",Double.NaN);
            if(!Double.isFinite(centerLat)||!Double.isFinite(centerLon)){double[] c=destination(lat,lon,heading,RESERVE_DISTANCE_M/2.0);centerLat=c[0];centerLon=c[1];}
            String key=corridorKey(lat,lon,heading);
            JSONObject stored=new JSONObject();
            stored.put("key",key);stored.put("kind","corridor");
            stored.put("center_lat",centerLat);stored.put("center_lon",centerLon);
            stored.put("radius_m",RESERVE_DISTANCE_M/2+RESERVE_WIDTH_M+18000);
            stored.put("start_lat",lat);stored.put("start_lon",lon);stored.put("heading",heading);
            stored.put("distance_m",json.optInt("distance_m",RESERVE_DISTANCE_M));
            stored.put("width_m",json.optInt("width_m",RESERVE_WIDTH_M));
            stored.put("fetched_at",System.currentTimeMillis());
            stored.put("source_ok",json.optJSONObject("coverage") == null || json.optJSONObject("coverage").optBoolean("osm_ok",true));
            stored.put("hazards",hazards);stored.put("coverage",json.optJSONObject("coverage"));
            return savePack(key,stored);
        }catch(Exception ignored){return false;}
    }

    private boolean fetchStateCoverage(ApiClient api,String uf){
        synchronized(this){if(hasFreshStateLocked(uf))return true;}
        try{
            ApiClient.Response response=api.getLong("api/road_state_pack.php?uf="+uf);
            JSONObject json=response.json();JSONArray hazards=json.optJSONArray("hazards");
            if(!response.ok()||!json.optBoolean("ok",false)||hazards==null)return false;
            double[] g=stateGeometry(uf);String key="state_"+uf.toLowerCase(Locale.ROOT);
            JSONObject stored=new JSONObject();stored.put("key",key);stored.put("kind","state");stored.put("uf",uf);
            stored.put("center_lat",g[0]);stored.put("center_lon",g[1]);stored.put("radius_m",(int)g[2]);
            stored.put("fetched_at",System.currentTimeMillis());
            stored.put("source_ok",json.optJSONObject("coverage") == null || json.optJSONObject("coverage").optBoolean("osm_ok",false));
            stored.put("hazards",hazards);stored.put("coverage",json.optJSONObject("coverage"));
            return savePack(key,stored);
        }catch(Exception ignored){return false;}
    }

    private boolean savePack(String key,JSONObject stored){
        try{
            File target=new File(dir,key+".json");writeText(target,stored.toString());
            Pack p=parsePack(target,stored);if(p==null)return false;
            synchronized(this){Pack old=findByKey(key);if(old!=null)packs.remove(old);packs.add(p);cleanupLocked();}
            return true;
        }catch(Exception e){return false;}
    }

    synchronized List<RoadHazard> nearby(double lat, double lon, double maxMeters) {
        LinkedHashMap<String, RoadHazard> out = new LinkedHashMap<>();
        int baseY = cell(lat), baseX = cell(lon);
        int span = Math.max(1, (int)Math.ceil(maxMeters / 900.0));
        for (Pack p : packs) {
            if (distanceM(lat, lon, p.lat, p.lon) > p.radiusM + maxMeters + 2500) continue;
            for (int y = baseY - span; y <= baseY + span; y++) {
                for (int x = baseX - span; x <= baseX + span; x++) {
                    List<RoadHazard> bucket = p.index.get(cellKey(y, x));
                    if (bucket == null) continue;
                    for (RoadHazard h : bucket) {
                        if (distanceM(lat, lon, h.lat, h.lon) <= maxMeters) out.put(h.id, h);
                    }
                }
            }
        }
        return new ArrayList<>(out.values());
    }

    private synchronized Pack findByKey(String key) {
        for (Pack p : packs) if (p.key.equals(key)) return p;
        return null;
    }

    private boolean hasFreshStateLocked(String uf){
        long now=System.currentTimeMillis();
        for(Pack p:packs)if("state".equals(p.kind)&&uf.equals(p.uf)&&now-p.fetchedAt<=freshMs(p))return true;
        return false;
    }

    private boolean hasStateLocked(String uf){for(Pack p:packs)if("state".equals(p.kind)&&uf.equals(p.uf))return true;return false;}

    private boolean hasFreshCorridorLocked(double lat,double lon,float heading){
        long now=System.currentTimeMillis();
        for(Pack p:packs){
            if(!"corridor".equals(p.kind)||now-p.fetchedAt>freshMs(p))continue;
            if(distanceM(lat,lon,p.startLat,p.startLon)>42000)continue;
            if(angleDiff(heading,p.heading)>32.0)continue;
            return true;
        }
        return false;
    }

    private boolean hasRecentCorridorNearLocked(double lat,double lon){
        long now=System.currentTimeMillis();
        for(Pack p:packs)if("corridor".equals(p.kind)&&now-p.fetchedAt<7L*24L*60L*60L*1000L&&distanceM(lat,lon,p.startLat,p.startLon)<90000)return true;
        return false;
    }

    private long freshMs(Pack p){
        long base="state".equals(p.kind)?STATE_FRESH_MS:("corridor".equals(p.kind)?CORRIDOR_FRESH_MS:TILE_FRESH_MS);
        if(!p.sourceOk)return Math.min(base,12L*60L*60L*1000L);
        return base;
    }

    private void loadDisk() {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return;
        long now = System.currentTimeMillis();
        for (File f : files) {
            try {
                JSONObject json = new JSONObject(readText(f));
                long fetched = json.optLong("fetched_at", f.lastModified());
                String kind=json.optString("kind","tile");
                long maxAge="state".equals(kind)?STATE_MAX_AGE_MS:MAX_AGE_MS;
                if (now - fetched > maxAge) { f.delete(); continue; }
                Pack p = parsePack(f, json);
                if (p != null) packs.add(p);
            } catch (Exception ignored) {}
        }
        synchronized (this) { cleanupLocked(); }
    }

    private Pack parsePack(File file, JSONObject json) {
        try {
            String key = json.optString("key", file.getName().replace(".json", ""));
            String kind=json.optString("kind","tile");String uf=json.optString("uf","");
            double lat = json.optDouble("center_lat", Double.NaN);
            double lon = json.optDouble("center_lon", Double.NaN);
            int radius = json.optInt("radius_m", PACK_RADIUS_M);
            long fetched = json.optLong("fetched_at", file.lastModified());
            double startLat=json.optDouble("start_lat",lat),startLon=json.optDouble("start_lon",lon);
            float heading=(float)json.optDouble("heading",Double.NaN);
            boolean sourceOk=json.optBoolean("source_ok",true);
            JSONArray arr = json.optJSONArray("hazards");
            if (!Double.isFinite(lat) || !Double.isFinite(lon) || arr == null) return null;
            ArrayList<RoadHazard> hazards = new ArrayList<>();
            HashMap<Long, List<RoadHazard>> index = new HashMap<>();
            for (int i = 0; i < arr.length(); i++) {
                RoadHazard h = RoadHazard.fromJson(arr.optJSONObject(i));
                if (h == null) continue;
                hazards.add(h);
                long ck = cellKey(cell(h.lat), cell(h.lon));
                List<RoadHazard> bucket = index.get(ck);
                if (bucket == null) { bucket = new ArrayList<>(); index.put(ck, bucket); }
                bucket.add(h);
            }
            return new Pack(key,kind,uf,lat,lon,radius,fetched,startLat,startLon,heading,sourceOk,file,hazards,index);
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveUf(double lat,double lon){
        String quick=guessUfFast(lat,lon);
        try{
            if(Geocoder.isPresent()){
                Geocoder g=new Geocoder(app,new Locale("pt","BR"));
                List<Address> list=g.getFromLocation(lat,lon,1);
                if(list!=null&&!list.isEmpty()){
                    String admin=(list.get(0).getAdminArea()==null?"":list.get(0).getAdminArea()).toUpperCase(Locale.ROOT);
                    if(admin.equals("SP")||admin.contains("SÃO PAULO")||admin.contains("SAO PAULO"))return "SP";
                    if(admin.equals("RJ")||admin.contains("RIO DE JANEIRO"))return "RJ";
                    if(admin.equals("MG")||admin.contains("MINAS GERAIS"))return "MG";
                    if(admin.equals("ES")||admin.contains("ESPÍRITO SANTO")||admin.contains("ESPIRITO SANTO"))return "ES";
                }
            }
        }catch(Throwable ignored){}
        return quick;
    }

    private static String guessUfFast(double lat,double lon){
        if(lat>=-23.45&&lat<=-20.65&&lon>=-44.95&&lon<=-40.75)return "RJ";
        if(lat>=-21.40&&lat<=-17.75&&lon>=-41.95&&lon<=-39.55)return "ES";
        if(lat>=-25.45&&lat<=-19.65&&lon>=-53.25&&lon<=-44.00)return "SP";
        if(lat>=-23.00&&lat<=-14.10&&lon>=-51.15&&lon<=-39.75)return "MG";
        return "";
    }

    private static boolean isSupportedUf(String uf){return "SP".equals(uf)||"RJ".equals(uf)||"MG".equals(uf)||"ES".equals(uf);}

    private static double[] stateGeometry(String uf){
        if("SP".equals(uf))return new double[]{-22.35,-48.65,470000};
        if("RJ".equals(uf))return new double[]{-22.05,-42.75,270000};
        if("MG".equals(uf))return new double[]{-18.65,-44.45,650000};
        if("ES".equals(uf))return new double[]{-19.65,-40.55,260000};
        return new double[]{-20.0,-45.0,250000};
    }

    private static String readText(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 50_000_000) throw new IllegalStateException("Pacote grande demais");
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void writeText(File file, String text) throws Exception {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(file, false)) { out.write(data); out.flush(); }
    }

    private void cleanupLocked() {
        long now = System.currentTimeMillis();
        ArrayList<Pack> stale = new ArrayList<>();
        for (Pack p : packs) {
            long maxAge="state".equals(p.kind)?STATE_MAX_AGE_MS:MAX_AGE_MS;
            if(now-p.fetchedAt>maxAge)stale.add(p);
        }
        for (Pack p : stale) { packs.remove(p); p.file.delete(); }
        if (packs.size() <= MAX_PACKS) return;
        ArrayList<Pack> removable=new ArrayList<>();for(Pack p:packs)if(!"state".equals(p.kind))removable.add(p);
        Collections.sort(removable, Comparator.comparingLong(p -> p.fetchedAt));
        while(packs.size()>MAX_PACKS&&!removable.isEmpty()){
            Pack p=removable.remove(0);packs.remove(p);p.file.delete();
        }
    }

    private static int cell(double v) { return (int)Math.floor(v / CELL_DEG); }
    private static long cellKey(int y, int x) { return (((long)y) << 32) ^ (x & 0xffffffffL); }
    private static String packKey(double lat, double lon) {
        return String.format(Locale.US, "p_%+.3f_%+.3f", lat, lon).replace('+','p').replace('-','m').replace('.','_');
    }
    private static String corridorKey(double lat,double lon,float heading){
        int hb=((int)Math.round(heading/30.0))*30%360;
        return String.format(Locale.US,"corr_%+.2f_%+.2f_%03d",lat,lon,hb).replace('+','p').replace('-','m').replace('.','_');
    }

    private static double[] destination(double lat,double lon,double headingDeg,double distanceM){
        double r=6371000.0,br=Math.toRadians(headingDeg),d=distanceM/r,p1=Math.toRadians(lat),l1=Math.toRadians(lon);
        double p2=Math.asin(Math.sin(p1)*Math.cos(d)+Math.cos(p1)*Math.sin(d)*Math.cos(br));
        double l2=l1+Math.atan2(Math.sin(br)*Math.sin(d)*Math.cos(p1),Math.cos(d)-Math.sin(p1)*Math.sin(p2));
        double lon2=((Math.toDegrees(l2)+540.0)%360.0)-180.0;
        return new double[]{Math.toDegrees(p2),lon2};
    }

    private static double angleDiff(double a,double b){double d=Math.abs(a-b)%360.0;return d>180.0?360.0-d:d;}

    static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000.0;
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2-lat1), dl = Math.toRadians(lon2-lon1);
        double a = Math.sin(dp/2)*Math.sin(dp/2) + Math.cos(p1)*Math.cos(p2)*Math.sin(dl/2)*Math.sin(dl/2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0.0, 1.0-a)));
    }

    private static final class Pack {
        final String key,kind,uf;
        final double lat, lon,startLat,startLon;
        final int radiusM;
        final long fetchedAt;
        final float heading;
        final boolean sourceOk;
        final File file;
        final List<RoadHazard> hazards;
        final Map<Long, List<RoadHazard>> index;
        Pack(String key,String kind,String uf,double lat,double lon,int radiusM,long fetchedAt,double startLat,double startLon,float heading,boolean sourceOk,File file,List<RoadHazard> hazards,Map<Long,List<RoadHazard>> index) {
            this.key=key;this.kind=kind;this.uf=uf;this.lat=lat;this.lon=lon;this.radiusM=radiusM;this.fetchedAt=fetchedAt;this.startLat=startLat;this.startLon=startLon;this.heading=heading;this.sourceOk=sourceOk;this.file=file;this.hazards=hazards;this.index=index;
        }
    }
}

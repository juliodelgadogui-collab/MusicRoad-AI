package com.estradaplay.comunista;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * Keeps the active calculated route available after process death, reboot or temporary loss of internet.
 * This is deliberately a cache, not a replacement for the online router: a new route still requires a
 * provider unless a previously prepared route matches the same destination and the vehicle is near it.
 */
final class RouteOfflineCache {
    private static final String DIR = "offline_routes";
    private static final String ACTIVE = "active_route_v310.json";
    private static final long MAX_AGE_MS = 45L * 24L * 60L * 60L * 1000L;
    private static final double DEST_TOLERANCE_M = 300.0;
    private static final double ROUTE_RECOVERY_TOLERANCE_M = 3500.0;
    private static final long MAX_FILE_BYTES = 18L * 1024L * 1024L;

    private RouteOfflineCache() {}

    static void save(Context context, double fromLat, double fromLon, double toLat, double toLon,
                     String label, RouteEngine.Route route, boolean prepared) {
        if (context == null || route == null || route.geoJson == null || route.geoJson.trim().isEmpty()) return;
        if (!Double.isFinite(toLat) || !Double.isFinite(toLon)) return;
        try {
            File target=file(context);boolean keepPrepared=prepared;long preparedAt=prepared?System.currentTimeMillis():0L;int routeHash=route.geoJson.hashCode();
            if(!keepPrepared&&validFile(target)){
                try{
                    JSONObject old=new JSONObject(read(target));double a=old.optDouble("to_lat",Double.NaN),b=old.optDouble("to_lon",Double.NaN);boolean sameDest=!stale(old)&&old.optBoolean("prepared",false)&&Double.isFinite(a)&&Double.isFinite(b)&&RouteEngine.distanceM(a,b,toLat,toLon)<=DEST_TOLERANCE_M;
                    int oldHash=old.optInt("route_hash",0);double oldFromLat=old.optDouble("from_lat",Double.NaN),oldFromLon=old.optDouble("from_lon",Double.NaN),oldDistance=old.optDouble("distance_m",0);boolean sameOrigin=Double.isFinite(fromLat)&&Double.isFinite(fromLon)&&Double.isFinite(oldFromLat)&&Double.isFinite(oldFromLon)&&RouteEngine.distanceM(fromLat,fromLon,oldFromLat,oldFromLon)<=3000;boolean similarDistance=oldDistance>0&&Math.abs(oldDistance-route.distanceM)/Math.max(oldDistance,route.distanceM)<.03;boolean materiallySame=(oldHash!=0&&oldHash==routeHash)||(sameOrigin&&similarDistance);
                    if(sameDest&&materiallySame){keepPrepared=true;preparedAt=old.optLong("prepared_at",old.optLong("saved_at",System.currentTimeMillis()));}
                }catch(Throwable ignored){}
            }
            JSONObject root = new JSONObject();
            root.put("version", 310);
            root.put("saved_at", System.currentTimeMillis());
            root.put("prepared", keepPrepared);
            root.put("route_hash",routeHash);
            if(keepPrepared)root.put("prepared_at",preparedAt>0?preparedAt:System.currentTimeMillis());
            if(Double.isFinite(fromLat))root.put("from_lat",fromLat);
            if(Double.isFinite(fromLon))root.put("from_lon",fromLon);
            root.put("to_lat", toLat);
            root.put("to_lon", toLon);
            root.put("label", label == null ? "Destino" : label.trim());
            root.put("distance_m", route.distanceM);
            root.put("duration_s", route.durationS);
            root.put("geojson", new JSONObject(route.geoJson));

            JSONArray steps = new JSONArray();
            for (RouteEngine.Step s : route.steps) {
                JSONObject o = new JSONObject();
                o.put("instruction", s.instruction);
                o.put("road", s.road);
                o.put("type", s.type);
                o.put("modifier", s.modifier);
                o.put("distance_m", s.distanceM);
                o.put("duration_s", s.durationS);
                if(Double.isFinite(s.maneuverLat))o.put("maneuver_lat",s.maneuverLat);
                if(Double.isFinite(s.maneuverLon))o.put("maneuver_lon",s.maneuverLon);
                o.put("along_m", s.alongM);
                steps.put(o);
            }
            root.put("steps", steps);
            writeAtomic(target, root.toString());
        } catch (Throwable ignored) {}
    }

    static void markPrepared(Context context) {
        File f = file(context);
        if (!validFile(f)) return;
        try {
            JSONObject root = new JSONObject(read(f));
            if (stale(root)) return;
            root.put("prepared", true);
            root.put("prepared_at", System.currentTimeMillis());
            writeAtomic(f, root.toString());
        } catch (Throwable ignored) {}
    }

    static RouteEngine.Route load(Context context, double currentLat, double currentLon,
                                  double toLat, double toLon) {
        File f = file(context);
        if (!validFile(f)) return null;
        try {
            JSONObject root = new JSONObject(read(f));
            if (stale(root)) return null;
            double savedToLat = root.optDouble("to_lat", Double.NaN);
            double savedToLon = root.optDouble("to_lon", Double.NaN);
            if (!Double.isFinite(savedToLat) || !Double.isFinite(savedToLon)) return null;
            if (RouteEngine.distanceM(savedToLat, savedToLon, toLat, toLon) > DEST_TOLERANCE_M) return null;

            RouteEngine.Route route = decode(root);
            if (route == null) return null;
            if (Double.isFinite(currentLat) && Double.isFinite(currentLon)) {
                RouteEngine.Match match = route.match(currentLat, currentLon, Double.NaN, -1, 0);
                if (!match.valid || match.lateralM > ROUTE_RECOVERY_TOLERANCE_M) return null;
            }
            return route;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static boolean hasPreparedFor(Context context, double toLat, double toLon) {
        File f = file(context);
        if (!validFile(f)) return false;
        try {
            JSONObject root = new JSONObject(read(f));
            if (stale(root) || !root.optBoolean("prepared", false)) return false;
            double a = root.optDouble("to_lat", Double.NaN), b = root.optDouble("to_lon", Double.NaN);
            return Double.isFinite(a) && Double.isFinite(b) && RouteEngine.distanceM(a, b, toLat, toLon) <= DEST_TOLERANCE_M;
        } catch (Throwable ignored) { return false; }
    }

    static String status(Context context) {
        File f = file(context);
        if (!validFile(f)) return "Nenhuma rota salva offline";
        try {
            JSONObject root = new JSONObject(read(f));
            String label = root.optString("label", "Destino").trim();
            if (label.isEmpty()) label = "Destino";
            if (stale(root)) return "Rota antiga · " + label + " · recalcule antes da próxima viagem";
            double km = root.optDouble("distance_m", 0) / 1000.0;
            boolean prepared = root.optBoolean("prepared", false);
            return (prepared ? "Rota preparada" : "Rota recuperável") + " · " + label + " · " + Math.round(km) + " km";
        } catch (Throwable ignored) { return "Rota offline precisa ser preparada novamente"; }
    }

    private static boolean validFile(File f){return f!=null&&f.isFile()&&f.length()>0&&f.length()<=MAX_FILE_BYTES;}
    private static boolean stale(JSONObject root){long savedAt=root==null?0L:root.optLong("saved_at",0L);return savedAt<=0||System.currentTimeMillis()-savedAt>MAX_AGE_MS;}

    private static RouteEngine.Route decode(JSONObject root) {
        try {
            JSONObject geo = root.optJSONObject("geojson");
            JSONArray features = geo == null ? null : geo.optJSONArray("features");
            JSONObject feature = features == null ? null : features.optJSONObject(0);
            JSONObject geometry = feature == null ? null : feature.optJSONObject("geometry");
            JSONArray coords = geometry == null ? null : geometry.optJSONArray("coordinates");
            if (coords == null || coords.length() < 2) return null;

            int n = coords.length();
            double[] lats = new double[n], lons = new double[n], cumulative = new double[n];
            for (int i = 0; i < n; i++) {
                JSONArray p = coords.optJSONArray(i);
                if (p == null || p.length() < 2) return null;
                lons[i] = p.optDouble(0, Double.NaN);
                lats[i] = p.optDouble(1, Double.NaN);
                if (!Double.isFinite(lats[i]) || !Double.isFinite(lons[i])) return null;
                if (i > 0) cumulative[i] = cumulative[i - 1] + RouteEngine.distanceM(lats[i - 1], lons[i - 1], lats[i], lons[i]);
            }

            double distance = root.optDouble("distance_m", cumulative[n - 1]);
            double scale = cumulative[n - 1] > 1 && distance > 1 ? distance / cumulative[n - 1] : 1.0;
            if (Math.abs(scale - 1.0) > 0.01) for (int i = 0; i < cumulative.length; i++) cumulative[i] *= scale;

            ArrayList<RouteEngine.Step> steps = new ArrayList<>();
            JSONArray a = root.optJSONArray("steps");
            if (a != null) {
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o == null) continue;
                    double along = o.optDouble("along_m", 0);
                    steps.add(new RouteEngine.Step(
                            o.optString("instruction", ""), o.optString("road", ""),
                            o.optString("type", ""), o.optString("modifier", ""),
                            o.optDouble("distance_m", 0), o.optDouble("duration_s", 0),
                            o.has("maneuver_lat")?o.optDouble("maneuver_lat",Double.NaN):Double.NaN,
                            o.has("maneuver_lon")?o.optDouble("maneuver_lon",Double.NaN):Double.NaN, along));
                }
            }
            if (steps.isEmpty()) {
                steps.add(new RouteEngine.Step("Siga na rota", "", "depart", "", 0, 0, lats[0], lons[0], 0));
                steps.add(new RouteEngine.Step("Chegada ao destino", "", "arrive", "", 0, 0, lats[n - 1], lons[n - 1], distance));
            }
            return new RouteEngine.Route(geo.toString(), distance, root.optDouble("duration_s", 0), steps, lats, lons, cumulative);
        } catch (Throwable ignored) { return null; }
    }

    private static File file(Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), DIR);
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, ACTIVE);
    }

    private static void writeAtomic(File target, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) return;
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp, false)) {
            out.write(bytes);
            out.flush();
            try { out.getFD().sync(); } catch (Throwable ignored) {}
        }
        if (target.exists() && !target.delete()) return;
        if (!tmp.renameTo(target)) {
            try (FileOutputStream out = new FileOutputStream(target, false)) { out.write(bytes); }
            tmp.delete();
        }
    }

    private static String read(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int)Math.min(file.length(), MAX_FILE_BYTES)];
            int off = 0, n;
            while (off < bytes.length && (n = in.read(bytes, off, bytes.length - off)) > 0) off += n;
            return new String(bytes, 0, off, StandardCharsets.UTF_8);
        }
    }
}

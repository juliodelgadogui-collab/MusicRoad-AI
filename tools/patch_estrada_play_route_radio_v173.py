from pathlib import Path

ROOT = Path('estrada-play-comunista-app')
JAVA = ROOT / 'app/src/main/java/com/estradaplay/comunista'
GRADLE = ROOT / 'app/build.gradle'

# 1) version
text = GRADLE.read_text(encoding='utf-8')
text = text.replace('versionCode 172', 'versionCode 173').replace("versionName '1.7.2'", "versionName '1.7.3'")
GRADLE.write_text(text, encoding='utf-8')

# 2) robust routing: compare alternatives and reject obviously incoherent short-trip detours
route = r'''package com.estradaplay.comunista;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

final class RouteEngine {
    static final class Route {
        final String geoJson;
        final double distanceM;
        final double durationS;
        final String nextInstruction;

        Route(String geoJson, double distanceM, double durationS, String nextInstruction) {
            this.geoJson = geoJson;
            this.distanceM = distanceM;
            this.durationS = durationS;
            this.nextInstruction = nextInstruction == null ? "" : nextInstruction;
        }

        String summary() {
            String distance = distanceM >= 1000
                    ? String.format(Locale.getDefault(), "%.1f km", distanceM / 1000.0)
                    : Math.max(0, Math.round(distanceM)) + " m";
            long min = Math.max(1, Math.round(durationS / 60.0));
            long h = min / 60;
            long m = min % 60;
            String time = h > 0 ? h + "h " + m + "min" : m + " min";
            return distance + " · " + time;
        }
    }

    private RouteEngine() {}

    static Route fetch(double fromLat, double fromLon, double toLat, double toLon) throws Exception {
        String url = String.format(Locale.US,
                "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson&steps=true&alternatives=true&continue_straight=true&radiuses=500;500",
                fromLon, fromLat, toLon, toLat);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(25000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "EstradaPlayComunista/1.7.3 Android");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            c.disconnect();
            throw new Exception("HTTP " + code);
        }
        String raw;
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) {
                bytes.write(buf, 0, n);
                if (bytes.size() > 8_000_000) throw new Exception("Rota grande demais");
            }
            raw = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }

        JSONObject root = new JSONObject(raw);
        JSONArray routes = root.optJSONArray("routes");
        if (routes == null || routes.length() == 0) throw new Exception("Rota não encontrada");
        JSONObject r = chooseRoute(routes, fromLat, fromLon, toLat, toLon);
        if (r == null) throw new Exception("Rota incoerente para este destino");
        JSONObject geometry = r.optJSONObject("geometry");
        if (geometry == null) throw new Exception("Geometria ausente");

        JSONObject feature = new JSONObject();
        feature.put("type", "Feature");
        feature.put("properties", new JSONObject());
        feature.put("geometry", geometry);
        JSONArray features = new JSONArray();
        features.put(feature);
        JSONObject collection = new JSONObject();
        collection.put("type", "FeatureCollection");
        collection.put("features", features);

        String instruction = firstInstruction(r);
        return new Route(collection.toString(), r.optDouble("distance", 0), r.optDouble("duration", 0), instruction);
    }

    private static JSONObject chooseRoute(JSONArray routes, double fromLat, double fromLon, double toLat, double toLon) {
        ArrayList<JSONObject> valid = new ArrayList<>();
        double fastest = Double.POSITIVE_INFINITY;
        double straight = distanceM(fromLat, fromLon, toLat, toLon);
        for (int i = 0; i < routes.length(); i++) {
            JSONObject r = routes.optJSONObject(i);
            if (r == null) continue;
            double dist = r.optDouble("distance", 0), dur = r.optDouble("duration", 0);
            JSONObject g = r.optJSONObject("geometry");
            if (dist <= 0 || dur <= 0 || g == null) continue;
            JSONArray coords = g.optJSONArray("coordinates");
            if (coords == null || coords.length() < 2) continue;
            JSONArray first = coords.optJSONArray(0), last = coords.optJSONArray(coords.length() - 1);
            if (first == null || last == null || first.length() < 2 || last.length() < 2) continue;
            double startGap = distanceM(fromLat, fromLon, first.optDouble(1), first.optDouble(0));
            double endGap = distanceM(toLat, toLon, last.optDouble(1), last.optDouble(0));
            if (startGap > 1200 || endGap > 1200) continue;
            valid.add(r);
            fastest = Math.min(fastest, dur);
        }
        if (valid.isEmpty()) return null;

        JSONObject best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (JSONObject r : valid) {
            double dist = r.optDouble("distance", 0), dur = r.optDouble("duration", 0);
            // Prefer the shorter sensible route, but do not trade several minutes for a tiny shortcut.
            double score = dist + Math.max(0, dur - fastest) * 8.0;
            if (score < bestScore) { bestScore = score; best = r; }
        }
        if (best == null) return null;

        double chosen = best.optDouble("distance", 0);
        // For nearby destinations, a massive detour usually means a bad snap/route. Better show
        // routing unavailable than draw a clearly nonsensical line through another region.
        if (straight >= 1200 && straight <= 15000) {
            double maxSane = Math.max(straight * 3.2, straight + 12000);
            if (chosen > maxSane) return null;
        }
        return best;
    }

    private static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        if (!Double.isFinite(lat1) || !Double.isFinite(lon1) || !Double.isFinite(lat2) || !Double.isFinite(lon2)) return Double.POSITIVE_INFINITY;
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dLat = p2 - p1, dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371000.0 * 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0, 1 - a)));
    }

    private static String firstInstruction(JSONObject route) {
        JSONArray legs = route.optJSONArray("legs");
        if (legs == null || legs.length() == 0) return "";
        JSONObject leg = legs.optJSONObject(0);
        JSONArray steps = leg == null ? null : leg.optJSONArray("steps");
        if (steps == null) return "";
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step == null) continue;
            JSONObject maneuver = step.optJSONObject("maneuver");
            if (maneuver == null) continue;
            String type = maneuver.optString("type", "");
            if ("depart".equals(type)) continue;
            String modifier = maneuver.optString("modifier", "");
            String road = step.optString("name", "").trim();
            double distance = step.optDouble("distance", 0);
            String action;
            if ("arrive".equals(type)) action = "Chegada ao destino";
            else if ("roundabout".equals(type) || "rotary".equals(type)) action = "Entre na rotatória";
            else if (modifier.contains("right")) action = "Vire à direita";
            else if (modifier.contains("left")) action = "Vire à esquerda";
            else action = "Siga em frente";
            if (!road.isEmpty() && !"arrive".equals(type)) action += " em " + road;
            if (distance > 40 && !"arrive".equals(type)) action += " · " + Math.round(distance) + " m";
            return action;
        }
        return "";
    }
}
'''
(JAVA / 'RouteEngine.java').write_text(route, encoding='utf-8')

# 3) compact built-in road catalog + named local zones. Manual road selection is fallback only.
catalog = r'''package com.estradaplay.comunista;

import android.content.Context;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class KnownRoadCatalog {
    static final String[] PRESET_ROADS = {
            "BR-101", "BR-040", "BR-116", "BR-120", "BR-251", "BR-259", "BR-262", "BR-265", "BR-267", "BR-356", "BR-381", "BR-393", "BR-482", "BR-491",
            "RJ-106", "RJ-116", "RJ-124", "RJ-158", "RJ-186", "RJ-196", "RJ-230",
            "ES-010", "ES-060", "ES-080", "ES-164", "ES-248", "ES-261", "ES-482",
            "MG-010", "MG-050", "MG-135", "MG-167", "MG-179", "MG-184", "MG-188", "MG-290", "MG-353", "MG-458"
    };
    private static final Pattern ROAD = Pattern.compile("(?i)\\b(BR|RJ|MG|ES|SP)[-\\s]?(\\d{1,4})\\b");
    private static final String PREF = "epc_radio_road_fallback_v173", KEY = "road";

    static final class Region {
        final String key, label, uf; final double lat, lon, radiusKm;
        Region(String key, String label, String uf, double lat, double lon, double radiusKm) {
            this.key=key; this.label=label; this.uf=uf; this.lat=lat; this.lon=lon; this.radiusKm=radiusKm;
        }
    }

    private static final Region[] REGIONS = {
            new Region("CAMPOS_RJ", "Campos dos Goytacazes", "RJ", -21.7622, -41.3181, 58),
            new Region("MACAE_RJ", "Macaé", "RJ", -22.3768, -41.7848, 48),
            new Region("RIO_RJ", "Rio / Grande Rio", "RJ", -22.9068, -43.1729, 62),
            new Region("VOLTA_REDONDA_RJ", "Sul Fluminense", "RJ", -22.5202, -44.0996, 58),
            new Region("VITORIA_ES", "Vitória / Grande Vitória", "ES", -20.3155, -40.3128, 55),
            new Region("LINHARES_ES", "Linhares", "ES", -19.3946, -40.0643, 52),
            new Region("SAO_MATEUS_ES", "São Mateus", "ES", -18.7161, -39.8589, 52),
            new Region("CACHOEIRO_ES", "Cachoeiro de Itapemirim", "ES", -20.8480, -41.1120, 52),
            new Region("BELO_HORIZONTE_MG", "Grande Belo Horizonte", "MG", -19.9167, -43.9345, 65),
            new Region("JUIZ_FORA_MG", "Juiz de Fora", "MG", -21.7609, -43.3500, 58),
            new Region("GOV_VALADARES_MG", "Governador Valadares", "MG", -18.8511, -41.9494, 58),
            new Region("TEOFILO_OTONI_MG", "Teófilo Otoni", "MG", -17.8575, -41.5052, 55),
            new Region("UBERLANDIA_MG", "Uberlândia", "MG", -18.9186, -48.2772, 62)
    };

    private KnownRoadCatalog() {}

    static String canonical(String raw) {
        if (raw == null) return "";
        Matcher m = ROAD.matcher(raw.toUpperCase(Locale.ROOT));
        return m.find() ? m.group(1).toUpperCase(Locale.ROOT) + "-" + m.group(2) : "";
    }

    static void select(Context c, String road) {
        String v = canonical(road);
        if (c != null && !v.isEmpty()) c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, v).apply();
    }

    static String selected(Context c) {
        return c == null ? "" : canonical(c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, ""));
    }

    static Region region(double lat, double lon) {
        Region best = null; double bestKm = Double.POSITIVE_INFINITY;
        for (Region r : REGIONS) {
            double km = distanceKm(lat, lon, r.lat, r.lon);
            if (km <= r.radiusKm && km < bestKm) { best = r; bestKm = km; }
        }
        return best;
    }

    static String segmentKey(double lat, double lon) {
        Region r = region(lat, lon);
        if (r != null) return "R-" + r.key;
        int a = (int)Math.round(lat * 4.0), b = (int)Math.round(lon * 4.0); // ~25 km local cell
        return "G-" + a + "-" + b;
    }

    static String segmentLabel(double lat, double lon) {
        Region r = region(lat, lon);
        return r == null ? "trecho local" : "trecho " + r.label;
    }

    static String uf(double lat, double lon, String road) {
        String c = canonical(road);
        if (c.startsWith("RJ-")) return "RJ";
        if (c.startsWith("ES-")) return "ES";
        if (c.startsWith("MG-")) return "MG";
        if (c.startsWith("SP-")) return "SP";
        Region r = region(lat, lon); if (r != null) return r.uf;
        boolean rj = lat>=-23.45&&lat<=-20.65&&lon>=-44.95&&lon<=-40.75;
        boolean es = lat>=-21.40&&lat<=-17.75&&lon>=-41.95&&lon<=-39.55;
        if (rj && es) return "RJ/ES";
        if (es) return "ES";
        if (rj) return "RJ";
        if (lat>=-25.45&&lat<=-19.65&&lon>=-53.25&&lon<=-44.00) return "SP";
        if (lat>=-23.00&&lat<=-14.10&&lon>=-51.15&&lon<=-39.75) return "MG";
        return "BR";
    }

    private static double distanceKm(double a, double b, double c, double d) {
        double p1=Math.toRadians(a), p2=Math.toRadians(c), dl=Math.toRadians(d-b), dp=p2-p1;
        double x=Math.sin(dp/2)*Math.sin(dp/2)+Math.cos(p1)*Math.cos(p2)*Math.sin(dl/2)*Math.sin(dl/2);
        return 6371.0*2.0*Math.atan2(Math.sqrt(x),Math.sqrt(Math.max(0,1-x)));
    }
}
'''
(JAVA / 'KnownRoadCatalog.java').write_text(catalog, encoding='utf-8')

# 4) road identity: room = road + LOCAL segment. Direction/UF never merge distant drivers.
identity = r'''package com.estradaplay.comunista;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

/** Resolves the current named highway using locally cached road geometry, with a manual known-road fallback. */
final class RoadIdentityResolver {
    static final class Identity {
        final String road, uf, direction, segment, roomKey, label;
        Identity(String road, String uf, String direction, String segment, String segmentLabel) {
            this.road=road; this.uf=uf; this.direction=direction; this.segment=segment;
            // LOCAL_RADIO_V173: direction and state are display metadata, not room scope.
            // The room is the road plus a local geographic segment, so BR-101/Campos and BR-101/Vitória never meet.
            roomKey = (road.replace("-","")+"|"+segment).toUpperCase(Locale.ROOT);
            label = road+" · "+segmentLabel+(uf==null||uf.isEmpty()?"":" · "+uf);
        }
    }

    private RoadIdentityResolver() {}

    static Identity resolve(OfflineRoadStore store, double lat, double lon, float heading) {
        if (store == null || !Double.isFinite(lat) || !Double.isFinite(lon)) return null;
        try {
            JSONObject root = new JSONObject(store.combinedGeoJson(lat, lon));
            JSONArray features = root.optJSONArray("features");
            if (features == null) return null;
            double best = Double.MAX_VALUE;
            String bestRoad = "";
            int max = Math.min(features.length(), 12000);
            for (int i=0;i<max;i++) {
                JSONObject f=features.optJSONObject(i); if(f==null)continue;
                JSONObject props=f.optJSONObject("properties"); if(props==null)props=new JSONObject();
                String road=KnownRoadCatalog.canonical(props.optString("ref",""));
                if(road.isEmpty()) road=KnownRoadCatalog.canonical(props.optString("name",""));
                if(road.isEmpty()) continue;
                JSONObject g=f.optJSONObject("geometry"); if(g==null||!"LineString".equalsIgnoreCase(g.optString("type","")))continue;
                JSONArray c=g.optJSONArray("coordinates"); if(c==null||c.length()<2)continue;
                for(int j=1;j<c.length();j++){
                    JSONArray a=c.optJSONArray(j-1),b=c.optJSONArray(j); if(a==null||b==null||a.length()<2||b.length()<2)continue;
                    double d=segmentDistance(lat,lon,a.optDouble(1,Double.NaN),a.optDouble(0,Double.NaN),b.optDouble(1,Double.NaN),b.optDouble(0,Double.NaN));
                    if(d<best){best=d;bestRoad=road;}
                }
            }
            if(bestRoad.isEmpty()||best>110.0)return null;
            return create(bestRoad, lat, lon, heading);
        } catch(Throwable ignored){ return null; }
    }

    static Identity fromKnownRoad(String road, double lat, double lon, float heading) {
        String c = KnownRoadCatalog.canonical(road);
        if (c.isEmpty() || !Double.isFinite(lat) || !Double.isFinite(lon)) return null;
        return create(c, lat, lon, heading);
    }

    private static Identity create(String road, double lat, double lon, float heading) {
        return new Identity(road, KnownRoadCatalog.uf(lat,lon,road), direction(heading),
                KnownRoadCatalog.segmentKey(lat,lon), KnownRoadCatalog.segmentLabel(lat,lon));
    }

    private static String direction(float h){
        if(!Float.isFinite(h))return"GERAL"; float v=((h%360)+360)%360;
        if(v<45||v>=315)return"NORTE"; if(v<135)return"LESTE"; if(v<225)return"SUL"; return"OESTE";
    }
    private static double segmentDistance(double lat,double lon,double lat1,double lon1,double lat2,double lon2){
        if(!Double.isFinite(lat1)||!Double.isFinite(lon1)||!Double.isFinite(lat2)||!Double.isFinite(lon2))return Double.MAX_VALUE;
        double cos=Math.max(.25,Math.cos(Math.toRadians(lat))); double x1=(lon1-lon)*111320*cos,y1=(lat1-lat)*110540,x2=(lon2-lon)*111320*cos,y2=(lat2-lat)*110540;
        double dx=x2-x1,dy=y2-y1,den=dx*dx+dy*dy,t=den<.001?0:-(x1*dx+y1*dy)/den; t=Math.max(0,Math.min(1,t));
        return Math.hypot(x1+t*dx,y1+t*dy);
    }
}
'''
(JAVA / 'RoadIdentityResolver.java').write_text(identity, encoding='utf-8')

# 5) radio service: remembered road is only used when automatic recognition fails.
svc_path = JAVA / 'RoadRadioService.java'
svc = svc_path.read_text(encoding='utf-8')
svc = svc.replace('ACTION_QUERY="com.estradaplay.comunista.radio.QUERY",ACTION_STATE="com.estradaplay.comunista.radio.STATE";',
                  'ACTION_QUERY="com.estradaplay.comunista.radio.QUERY",ACTION_SET_ROAD="com.estradaplay.comunista.radio.SET_ROAD",ACTION_STATE="com.estradaplay.comunista.radio.STATE";')
svc = svc.replace('private ApiClient api;private OfflineRoadStore roads;private RoadIdentityResolver.Identity identity;private boolean wanted,joined,muted,ptt,safetyMuted,registered;',
                  'private ApiClient api;private OfflineRoadStore roads;private RoadIdentityResolver.Identity identity;private String manualRoad="";private boolean wanted,joined,muted,ptt,safetyMuted,registered;')
svc = svc.replace('@Override public void onCreate(){super.onCreate();api=new ApiClient(this);createChannel();',
                  '@Override public void onCreate(){super.onCreate();api=new ApiClient(this);manualRoad=KnownRoadCatalog.selected(this);createChannel();')
old_start = 'else if(ACTION_MUTE.equals(a)){muted=i.getBooleanExtra("muted",!muted);applyRemoteAudio();sendState();}else if(ACTION_ALERT.equals(a))'
new_start = 'else if(ACTION_MUTE.equals(a)){muted=i.getBooleanExtra("muted",!muted);applyRemoteAudio();sendState();}else if(ACTION_SET_ROAD.equals(a)){String r=KnownRoadCatalog.canonical(i.getStringExtra("road"));if(!r.isEmpty()){manualRoad=r;KnownRoadCatalog.select(this,r);identity=null;if(wanted)io.execute(this::resolveAndSyncSafe);setStatus("Rodovia de apoio: "+r+" · confirmando trecho pelo GPS…");}}else if(ACTION_ALERT.equals(a))'
if old_start not in svc: raise SystemExit('RoadRadioService startCommand anchor not found')
svc = svc.replace(old_start, new_start)
old_resolve = 'RoadIdentityResolver.Identity next=RoadIdentityResolver.resolve(roads,lat,lon,heading);if(next==null){setStatus("Ainda não reconheci uma rodovia nomeada neste ponto.");return;}'
new_resolve = 'RoadIdentityResolver.Identity next=RoadIdentityResolver.resolve(roads,lat,lon,heading);if(next==null&&!manualRoad.isEmpty())next=RoadIdentityResolver.fromKnownRoad(manualRoad,lat,lon,heading);if(next==null){setStatus("Não identifiquei a rodovia. Escolha uma rodovia cadastrada como apoio.");return;}'
if old_resolve not in svc: raise SystemExit('RoadRadioService resolver anchor not found')
svc = svc.replace(old_resolve, new_resolve)
svc_path.write_text(svc, encoding='utf-8')

# 6) radio UI: fallback road picker from built-in catalog.
act_path = JAVA / 'RoadRadioActivity.java'
act = act_path.read_text(encoding='utf-8')
act = act.replace('private TextView room,status,people,alerts; private Button join,ptt,mute;',
                  'private TextView room,status,people,alerts; private Button join,ptt,mute,chooseRoad;')
act = act.replace('Sala automática pela rodovia, sentido e trecho aproximado. Áudio WebRTC vai direto entre os aparelhos e não fica gravado no servidor.',
                  'Sala automática pela rodovia e pelo trecho local. Motoristas distantes na mesma BR ficam em salas diferentes. Áudio WebRTC vai direto entre os aparelhos e não fica gravado no servidor.')
anchor = 'p.addView(room,new LinearLayout.LayoutParams(-1,-2));\n        people=t("0 motoristas no trecho",13,GREEN,true);'
insert = 'p.addView(room,new LinearLayout.LayoutParams(-1,-2));\n        chooseRoad=button("ESCOLHER RODOVIA · SE NÃO IDENTIFICAR",Color.rgb(42,22,25));p.addView(chooseRoad,new LinearLayout.LayoutParams(-1,dp(48)));chooseRoad.setOnClickListener(v->showRoadPicker());\n        people=t("0 motoristas no trecho",13,GREEN,true);'
if anchor not in act: raise SystemExit('RoadRadioActivity picker anchor not found')
act = act.replace(anchor, insert)
method_anchor = '    private void enter(){if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)'
method = '''    private void showRoadPicker(){
        final String[] roads=KnownRoadCatalog.PRESET_ROADS;
        new android.app.AlertDialog.Builder(this)
                .setTitle("Rodovia de apoio")
                .setMessage("Use somente se a identificação automática falhar. O GPS ainda separa o rádio por trecho local.")
                .setItems(roads,(d,which)->{if(which<0||which>=roads.length)return;Intent i=new Intent(this,RoadRadioService.class).setAction(RoadRadioService.ACTION_SET_ROAD);i.putExtra("road",roads[which]);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);room.setText(roads[which]+" · CONFIRMANDO TRECHO…");})
                .setNegativeButton("CANCELAR",null).show();
    }

'''
if method_anchor not in act: raise SystemExit('RoadRadioActivity enter anchor not found')
act = act.replace(method_anchor, method + method_anchor)
act_path.write_text(act, encoding='utf-8')

print('Universal 1.7.3 route + local radio patch applied')

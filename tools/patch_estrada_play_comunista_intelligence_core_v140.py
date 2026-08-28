#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path('estrada-play-comunista-app')
if not ROOT.exists():
    raise SystemExit('estrada-play-comunista-app not generated')


def read(rel):
    return (ROOT / rel).read_text(encoding='utf-8')


def write(rel, value):
    p = ROOT / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(value, encoding='utf-8')


def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit('missing anchor: ' + label)
    return text.replace(old, new, 1)


def between(text, start, end, replacement, label):
    a = text.find(start)
    if a < 0:
        raise SystemExit('start anchor missing: ' + label)
    b = text.find(end, a)
    if b < 0:
        raise SystemExit('end anchor missing: ' + label)
    return text[:a] + replacement + text[b:]

# -----------------------------------------------------------------------------
# Version / dependencies / activities
# -----------------------------------------------------------------------------
build = read('app/build.gradle')
build = re.sub(r'versionCode\s+\d+', 'versionCode 140', build, count=1)
build = re.sub(r"versionName\s+'[^']+'", "versionName '1.4.0'", build, count=1)
write('app/build.gradle', build)

manifest = read('app/src/main/AndroidManifest.xml')
activity_block = '''        <activity
            android:name=".DriveToolsActivity"
            android:exported="false"
            android:screenOrientation="${appOrientation}" />

        <activity
            android:name=".TripHistoryActivity"
            android:exported="false"
            android:screenOrientation="${appOrientation}" />

        <activity
            android:name=".HudActivity"
            android:exported="false"
            android:screenOrientation="${appOrientation}" />

        <activity
            android:name=".RoadReportActivity"
            android:exported="false"
            android:screenOrientation="${appOrientation}" />

'''
if '.DriveToolsActivity' not in manifest:
    manifest = replace_once(manifest,
        '        <activity\n            android:name=".VideoLibraryActivity"',
        activity_block + '        <activity\n            android:name=".VideoLibraryActivity"',
        'activities v140')
write('app/src/main/AndroidManifest.xml', manifest)

# -----------------------------------------------------------------------------
# Core offline state packs: RJ + MG + ES are always considered base coverage.
# -----------------------------------------------------------------------------
road_rel = 'app/src/main/java/com/estradaplay/comunista/RoadPackStore.java'
road = read(road_rel)
road = replace_once(road,
'''    synchronized int statePackCount() {
        int n=0; for(Pack p:packs) if("state".equals(p.kind)) n++; return n;
    }
''',
'''    synchronized int statePackCount() {
        int n=0; for(Pack p:packs) if("state".equals(p.kind)) n++; return n;
    }

    // INTELLIGENT_CORE_V140: RJ/MG/ES are the permanent offline core.
    synchronized int coreStatePackCount() {
        int n = 0;
        for (String uf : new String[]{"RJ","MG","ES"}) if (hasStateLocked(uf)) n++;
        return n;
    }

    synchronized boolean coreStatesReady() {
        return coreStatePackCount() == 3;
    }

    synchronized String coreStatesStatus() {
        StringBuilder out = new StringBuilder();
        for (String uf : new String[]{"RJ","MG","ES"}) {
            if (out.length() > 0) out.append(" · ");
            out.append(uf).append(hasStateLocked(uf) ? " ✓" : " …");
        }
        return out.toString();
    }
''', 'road core count')

road = replace_once(road,
'''    synchronized boolean needsPreparation(double lat, double lon, float heading) {
        if (!hasFreshCoreCoverage(lat, lon)) return true;
        String uf=guessUfFast(lat,lon);
        if (isSupportedUf(uf) && !hasFreshStateLocked(uf)) return true;
        return Float.isFinite(heading) && !hasFreshCorridorLocked(lat,lon,heading);
    }
''',
'''    synchronized boolean needsPreparation(double lat, double lon, float heading) {
        if (!hasFreshCoreCoverage(lat, lon)) return true;
        String uf=guessUfFast(lat,lon);
        if (isSupportedUf(uf) && !hasFreshStateLocked(uf)) return true;
        // Keep trying in background until all three requested states exist locally.
        for (String coreUf : new String[]{"RJ","MG","ES"}) if (!hasFreshStateLocked(coreUf)) return true;
        return Float.isFinite(heading) && !hasFreshCorridorLocked(lat,lon,heading);
    }
''', 'road needs core')

road = replace_once(road,
'''        // CAMERA_MONITORAMENTO_V122: additive, public map data only. We intentionally
''',
'''        // INTELLIGENT_CORE_V140: prefetch all three states, even when the car is
        // currently in only one of them. Fresh-state checks prevent repeated downloads.
        for (String coreUf : new String[]{"RJ","MG","ES"}) {
            if (coreUf.equals(uf)) continue;
            boolean stateOk = fetchMusicRoadStateRadars(api, coreUf);
            if (!stateOk) stateOk = fetchStateCoverage(api, coreUf);
            ok = stateOk || ok;
        }

        // CAMERA_MONITORAMENTO_V122: additive, public map data only. We intentionally
''', 'road prefetch core')

road = replace_once(road,
'''        if(stateReady)s.append(uf).append(" estadual"); else s.append(packCount()).append(" área(s)");
''',
'''        if(stateReady)s.append(uf).append(" estadual"); else s.append(packCount()).append(" área(s)");
        s.append(" · núcleo ").append(coreStatePackCount()).append("/3");
''', 'road status core')
write(road_rel, road)

# -----------------------------------------------------------------------------
# Trip history local-only.
# -----------------------------------------------------------------------------
write('app/src/main/java/com/estradaplay/comunista/TripRecorder.java', r'''package com.estradaplay.comunista;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;

import org.json.JSONArray;
import org.json.JSONObject;

/** Small local trip recorder. No route history is uploaded to the server. */
final class TripRecorder {
    private static final String PREFS = "epc_trip_history_v140";
    private static final String KEY_HISTORY = "history";
    private static final String KEY_ACTIVE = "active";
    private static final long IDLE_FINISH_MS = 5L * 60L * 1000L;

    private final SharedPreferences prefs;
    private JSONObject active;
    private Location last;
    private long lastMovingAt;
    private long lastPersistAt;

    TripRecorder(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            String raw = prefs.getString(KEY_ACTIVE, "");
            if (raw != null && !raw.isEmpty()) active = new JSONObject(raw);
        } catch (Throwable ignored) { active = null; }
    }

    synchronized void onLocation(Location loc, double speedKmh) {
        if (loc == null) return;
        long now = System.currentTimeMillis();
        if (speedKmh >= 4.0) {
            if (active == null) start(now, loc);
            lastMovingAt = now;
        } else if (active != null && lastMovingAt > 0 && now - lastMovingAt >= IDLE_FINISH_MS) {
            finish("parada");
            last = new Location(loc);
            return;
        }
        if (active == null) { last = new Location(loc); return; }
        try {
            double add = 0;
            if (last != null) {
                float d = last.distanceTo(loc);
                if (d >= 0 && d <= 1500f) add = d;
            }
            active.put("distance_m", active.optDouble("distance_m", 0) + add);
            active.put("max_speed", Math.max(active.optDouble("max_speed", 0), Math.max(0, speedKmh)));
            active.put("last_lat", loc.getLatitude());
            active.put("last_lon", loc.getLongitude());
            active.put("updated_at", now);
            if (now - lastPersistAt >= 12000L) persistActive();
        } catch (Throwable ignored) {}
        last = new Location(loc);
    }

    synchronized void onHazard(String type) {
        if (active == null) return;
        try {
            if ("RADAR".equals(type)) active.put("radars", active.optInt("radars",0)+1);
            else if ("QUEBRA_MOLAS".equals(type)) active.put("bumps", active.optInt("bumps",0)+1);
            else if ("CAMERA_MONITORAMENTO".equals(type)) active.put("cameras", active.optInt("cameras",0)+1);
            else active.put("other_alerts", active.optInt("other_alerts",0)+1);
            persistActive();
        } catch (Throwable ignored) {}
    }

    synchronized void finish(String reason) {
        if (active == null) return;
        try {
            long now = System.currentTimeMillis();
            active.put("ended_at", now);
            active.put("duration_ms", Math.max(0, now - active.optLong("started_at", now)));
            active.put("reason", reason == null ? "" : reason);
            JSONArray old = history(prefs);
            JSONArray out = new JSONArray();
            out.put(active);
            for (int i=0; i<old.length() && out.length()<30; i++) out.put(old.opt(i));
            prefs.edit().putString(KEY_HISTORY, out.toString()).remove(KEY_ACTIVE).apply();
        } catch (Throwable ignored) {}
        active = null;
        last = null;
        lastMovingAt = 0;
    }

    private void start(long now, Location loc) {
        active = new JSONObject();
        try {
            active.put("started_at", now);
            active.put("updated_at", now);
            active.put("start_lat", loc.getLatitude());
            active.put("start_lon", loc.getLongitude());
            active.put("distance_m", 0);
            active.put("max_speed", 0);
            active.put("radars", 0);
            active.put("bumps", 0);
            active.put("cameras", 0);
            active.put("other_alerts", 0);
        } catch (Throwable ignored) {}
        lastMovingAt = now;
        persistActive();
    }

    private void persistActive() {
        lastPersistAt = System.currentTimeMillis();
        if (active != null) prefs.edit().putString(KEY_ACTIVE, active.toString()).apply();
    }

    static JSONArray history(Context context) {
        return history(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE));
    }

    private static JSONArray history(SharedPreferences prefs) {
        try { return new JSONArray(prefs.getString(KEY_HISTORY, "[]")); }
        catch (Throwable ignored) { return new JSONArray(); }
    }

    static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_HISTORY).apply();
    }
}
''')

# -----------------------------------------------------------------------------
# Road safety: next hazard, urgency, source, thermal telemetry, trip recording.
# -----------------------------------------------------------------------------
safety_rel = 'app/src/main/java/com/estradaplay/comunista/RoadSafetyService.java'
safety = read(safety_rel)
safety = replace_once(safety,
'    private CommunistCopilot copilot;\n',
'    private CommunistCopilot copilot;\n    private TripRecorder tripRecorder;\n', 'trip field')
safety = replace_once(safety,
'        copilot = new CommunistCopilot(this);\n',
'        copilot = new CommunistCopilot(this);\n        tripRecorder = new TripRecorder(this);\n', 'trip init')
safety = replace_once(safety,
'        previous = new Location(loc);\n\n        maybeResolveRoadLimit',
'        previous = new Location(loc);\n        if (tripRecorder != null) tripRecorder.onLocation(loc, speedKmh);\n\n        maybeResolveRoadLimit', 'trip location')

selection_start = '        List<RoadHazard> nearby = packs.nearby(loc.getLatitude(), loc.getLongitude(), 1900);\n'
selection_end = '    private void maybeResolveRoadLimit(double lat, double lon, float heading) {'
new_selection = r'''        List<RoadHazard> nearby = packs.nearby(loc.getLatitude(), loc.getLongitude(), 1900);
        RoadHazard best = null;
        RoadHazard next = null;
        double bestForward = Double.MAX_VALUE;
        double bestDistance = Double.MAX_VALUE;
        double bestScore = Double.MAX_VALUE;
        double nextForward = Double.MAX_VALUE;
        double nextScore = Double.MAX_VALUE;

        if (Float.isFinite(heading) && speedKmh >= 3.0) {
            for (RoadHazard h : nearby) {
                if (!shouldAlert(h)) continue;
                Match m = match(loc.getLatitude(), loc.getLongitude(), heading, speedKmh, h);
                if (!m.valid) continue;
                double score = m.forwardM + hazardPriorityBias(h.type);
                if (score < bestScore) {
                    best = h;
                    bestForward = m.forwardM;
                    bestDistance = m.distanceM;
                    bestScore = score;
                }
            }
            if (best != null) {
                for (RoadHazard h : nearby) {
                    if (h == null || h.id.equals(best.id) || !shouldAlert(h)) continue;
                    Match m = match(loc.getLatitude(), loc.getLongitude(), heading, speedKmh, h);
                    if (!m.valid || m.forwardM < bestForward + 15) continue;
                    double score = m.forwardM + hazardPriorityBias(h.type);
                    if (score < nextScore) { next = h; nextForward = m.forwardM; nextScore = score; }
                }
            }
        }

        if (best != null) {
            rememberAlert(best);
            if (tripRecorder != null) tripRecorder.onHazard(best.type);
            speakHazardVoice(best, bestForward);
            String title = best.label() + " à frente";
            String detail = distanceText(bestForward);
            if (best.speed > 0 && "RADAR".equals(best.type)) {
                detail += " · " + best.speed + " km/h";
                int delta = (int)Math.round(speedKmh - best.speed);
                if (delta >= 2) detail += " · reduza " + delta;
            }
            if (!best.road.isEmpty()) detail += " · " + best.road;
            updateNotification(title, detail, true);
            broadcast(loc, speedKmh, best, bestDistance, detail, next, nextForward);
        } else {
            long now = System.currentTimeMillis();
            if (now - lastStateRefreshAt >= 5000L || lastStateText == null || lastStateText.isEmpty()) {
                lastStateText = packs.hasAnyCoverage(loc.getLatitude(), loc.getLongitude())
                        ? packs.status(loc.getLatitude(), loc.getLongitude()) + " · " + mapRoads.status(loc.getLatitude(), loc.getLongitude())
                        : (fetching.get() ? "Preparando alertas e mapa offline…" : "Aguardando proteção offline desta região");
                lastStateRefreshAt = now;
            }
            String state = lastStateText;
            if (now - lastNotificationAt > 7000L) updateNotification("Proteção na estrada ativa", state, false);
            broadcast(loc, speedKmh, null, 0, state, null, 0);
        }
    }

'''
safety = between(safety, selection_start, selection_end, new_selection, 'safety selection')

old_broadcast_start = '    private void broadcast(Location loc, double speedKmh, RoadHazard h, double distance, String status) {'
old_broadcast_end = '    private void broadcastSynthetic(double lat, double lon, String status) {'
new_broadcast = r'''    private void broadcast(Location loc, double speedKmh, RoadHazard h, double distance, String status,
                           RoadHazard next, double nextDistance) {
        Intent i = baseBroadcast(loc.getLatitude(), loc.getLongitude(), speedKmh, status);
        if (h != null) {
            i.putExtra("hazard_id", h.id);
            i.putExtra("hazard_type", h.type);
            i.putExtra("hazard_label", h.label());
            i.putExtra("road", h.road);
            i.putExtra("source", h.source);
            i.putExtra("distance_m", distance);
            i.putExtra("limit_kmh", h.speed);
            i.putExtra("radar_limit_kmh", h.speed);
            int delta = h.speed > 0 ? Math.max(0, (int)Math.round(speedKmh - h.speed)) : 0;
            i.putExtra("overspeed_delta_kmh", delta);
            int level = ("RADAR".equals(h.type) && delta >= 10) ||
                    ("QUEBRA_MOLAS".equals(h.type) && distance <= 130) ? 2 : 1;
            i.putExtra("alert_level", level);
        }
        if (next != null) {
            i.putExtra("next_hazard_type", next.type);
            i.putExtra("next_hazard_label", next.label());
            i.putExtra("next_distance_m", nextDistance);
            i.putExtra("next_limit_kmh", next.speed);
        }
        sendBroadcast(i);
    }

'''
safety = between(safety, old_broadcast_start, old_broadcast_end, new_broadcast, 'broadcast enhanced')

safety = replace_once(safety,
'''        i.putExtra("state_pack_count", packs.statePackCount());
''',
'''        i.putExtra("state_pack_count", packs.statePackCount());
        i.putExtra("core_state_count", packs.coreStatePackCount());
        i.putExtra("core_states_status", packs.coreStatesStatus());
        i.putExtra("thermal_status", thermalStatus());
''', 'base core extras')

thermal_method = r'''    private int thermalStatus() {
        if (Build.VERSION.SDK_INT < 29) return 0;
        try {
            android.os.PowerManager pm = (android.os.PowerManager)getSystemService(POWER_SERVICE);
            return pm == null ? 0 : pm.getCurrentThermalStatus();
        } catch (Throwable ignored) { return 0; }
    }

'''
safety = replace_once(safety,
'    private void createChannel() {\n', thermal_method + '    private void createChannel() {\n', 'thermal helper')
safety = replace_once(safety,
'''        try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Throwable ignored) {}
''',
'''        try { if (tripRecorder != null) tripRecorder.finish("serviço encerrado"); } catch (Throwable ignored) {}
        try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Throwable ignored) {}
''', 'trip finish')
write(safety_rel, safety)

# -----------------------------------------------------------------------------
# Central alert: urgency + source + next point.
# -----------------------------------------------------------------------------
write('app/src/main/java/com/estradaplay/comunista/SafetyAlertOverlay.java', r'''package com.estradaplay.comunista;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** High-priority central road warning. */
final class SafetyAlertOverlay {
    private static final String TAG = "epc-central-safety-alert";
    private static String lastId = "";
    private static long lastAt;
    private SafetyAlertOverlay() {}

    static void show(Context context, FrameLayout host, Intent intent) {
        if (context == null || host == null || intent == null) return;
        String type = normalize(intent.getStringExtra("hazard_type"));
        if (!("RADAR".equals(type) || "QUEBRA_MOLAS".equals(type) || "CAMERA_MONITORAMENTO".equals(type))) return;
        String id = safe(intent.getStringExtra("hazard_id"));
        if (id.isEmpty()) id = type + ":" + Math.round(intent.getDoubleExtra("distance_m", 0));
        long now = System.currentTimeMillis();
        if (id.equals(lastId) && now-lastAt < 12000L) return;
        lastId=id; lastAt=now;
        View old=host.findViewWithTag(TAG); if(old!=null) host.removeView(old);

        int level=intent.getIntExtra("alert_level",1);
        int accent="QUEBRA_MOLAS".equals(type)?Color.rgb(234,145,34):
                ("CAMERA_MONITORAMENTO".equals(type)?Color.rgb(217,190,93):Color.rgb(208,24,45));
        if(level>=2 && "RADAR".equals(type)) accent=Color.rgb(255,45,45);
        int ink=Color.rgb(249,241,226), muted=Color.rgb(191,171,164), panel=Color.rgb(24,8,12);
        FrameLayout overlay=new FrameLayout(context); overlay.setTag(TAG);
        overlay.setBackgroundColor(Color.argb(level>=2?176:132,0,0,0)); overlay.setClickable(true);
        LinearLayout card=new LinearLayout(context); card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL); card.setPadding(dp(context,24),dp(context,20),dp(context,24),dp(context,18));
        card.setBackground(round(panel,22,accent,level>=2?3:2)); if(android.os.Build.VERSION.SDK_INT>=21)card.setElevation(dp(context,36));

        String over="RADAR".equals(type)?(level>=2?"REDUZA AGORA":"ALERTA DE FISCALIZAÇÃO"):
                ("QUEBRA_MOLAS".equals(type)?"ATENÇÃO NA VIA":"MONITORAMENTO DE TRÁFEGO");
        TextView overline=text(context,over,10,accent,true); overline.setLetterSpacing(.13f); overline.setGravity(Gravity.CENTER); card.addView(overline);
        String titleValue="RADAR".equals(type)?"RADAR À FRENTE":("QUEBRA_MOLAS".equals(type)?"QUEBRA-MOLAS À FRENTE":"CÂMERA À FRENTE");
        TextView title=text(context,titleValue,23,ink,true); title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-1,-2);tp.setMargins(0,dp(context,8),0,dp(context,12));card.addView(title,tp);

        int limit=intent.getIntExtra("radar_limit_kmh",intent.getIntExtra("limit_kmh",0));
        if("RADAR".equals(type)){
            TextView sign=text(context,limit>0?String.valueOf(limit):"?",limit>0?46:42,Color.rgb(18,18,18),true);sign.setGravity(Gravity.CENTER);
            sign.setBackground(round(Color.WHITE,100,accent,7));card.addView(sign,new LinearLayout.LayoutParams(dp(context,104),dp(context,104)));
            int delta=intent.getIntExtra("overspeed_delta_kmh",0);
            String l=limit>0?"KM/H · LIMITE DO RADAR":"LIMITE DO RADAR NÃO INFORMADO";
            if(delta>=2)l+="\nVOCÊ ESTÁ "+delta+" KM/H ACIMA";
            TextView lt=text(context,l,11,delta>=2?accent:ink,true);lt.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(context,9),0,0);card.addView(lt,lp);
        }else{
            TextView action=text(context,"QUEBRA_MOLAS".equals(type)?"REDUZA":"ATENÇÃO",30,accent,true);action.setLetterSpacing(.08f);action.setGravity(Gravity.CENTER);card.addView(action);
        }

        double distance=intent.getDoubleExtra("distance_m",0);String road=safe(intent.getStringExtra("road"));
        StringBuilder detail=new StringBuilder();if(distance>0)detail.append(distanceText(distance));if(!road.isEmpty()){if(detail.length()>0)detail.append(" · ");detail.append(road);}if(detail.length()==0)detail.append("Ponto detectado à frente");
        TextView info=text(context,detail.toString(),13,muted,false);info.setGravity(Gravity.CENTER);LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(-1,-2);ip.setMargins(0,dp(context,12),0,0);card.addView(info,ip);

        String source=safe(intent.getStringExtra("source"));if(!source.isEmpty()){
            TextView src=text(context,"FONTE · "+source.toUpperCase(Locale.ROOT),9,muted,true);src.setGravity(Gravity.CENTER);card.addView(src);
        }
        String next=safe(intent.getStringExtra("next_hazard_label"));double nd=intent.getDoubleExtra("next_distance_m",0);
        if(!next.isEmpty()&&nd>0){TextView n=text(context,"DEPOIS · "+next.toUpperCase(Locale.ROOT)+" · "+distanceText(nd),10,ink,true);n.setGravity(Gravity.CENTER);n.setBackground(round(Color.rgb(42,18,22),8,Color.rgb(82,39,45),1));n.setPadding(dp(context,10),dp(context,7),dp(context,10),dp(context,7));LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,-2);np.setMargins(0,dp(context,10),0,0);card.addView(n,np);}
        TextView close=text(context,"TOQUE PARA FECHAR",9,muted,true);close.setLetterSpacing(.12f);close.setGravity(Gravity.CENTER);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.setMargins(0,dp(context,12),0,0);card.addView(close,cp);
        int screenW=context.getResources().getDisplayMetrics().widthPixels;int width=Math.min(dp(context,430),Math.max(dp(context,280),screenW-dp(context,34)));
        overlay.addView(card,new FrameLayout.LayoutParams(width,-2,Gravity.CENTER));host.addView(overlay,new FrameLayout.LayoutParams(-1,-1));overlay.setAlpha(0);overlay.animate().alpha(1).setDuration(130).start();overlay.setOnClickListener(v->dismiss(host,overlay));overlay.postDelayed(()->dismiss(host,overlay),level>=2?6500L:5200L);
    }
    private static String distanceText(double d){return d>=1000?String.format(Locale.getDefault(),"%.1f km",d/1000.0):Math.max(10,Math.round(d/10.0)*10)+" m";}
    private static void dismiss(FrameLayout host,View overlay){if(host==null||overlay==null||overlay.getParent()==null)return;overlay.animate().alpha(0).setDuration(150).withEndAction(()->{try{if(overlay.getParent()==host)host.removeView(overlay);}catch(Throwable ignored){}}).start();}
    private static String normalize(String raw){String t=safe(raw).toUpperCase(Locale.ROOT).replace('-','_').replace(' ','_');if(t.contains("QUEBRA")||t.contains("LOMBADA")||t.contains("BUMP")||t.contains("HUMP"))return"QUEBRA_MOLAS";if(t.contains("CAMERA")||t.contains("CÂMERA")||t.contains("MONITOR")||t.contains("CCTV")||t.contains("SURVEILLANCE"))return"CAMERA_MONITORAMENTO";if(t.contains("RADAR")||t.contains("SPEED_CAMERA")||t.contains("ENFORCEMENT"))return"RADAR";return t;}
    private static TextView text(Context c,String v,float s,int color,boolean bold){TextView t=new TextView(c);t.setText(v);t.setTextSize(s);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);t.setLineSpacing(0,1.04f);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private static GradientDrawable round(int color,int radius,int stroke,int sw){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dpRaw(radius));if(stroke!=0&&sw>0)d.setStroke(dpRaw(sw),stroke);return d;}
    private static float density=1f;private static int dpRaw(float v){return Math.round(v*density);}private static int dp(Context c,float v){density=c.getResources().getDisplayMetrics().density;return Math.round(v*density);}private static String safe(String s){return s==null?"":s.trim();}
}
''')

# -----------------------------------------------------------------------------
# Shared drive settings.
# -----------------------------------------------------------------------------
write('app/src/main/java/com/estradaplay/comunista/DriveSettings.java', r'''package com.estradaplay.comunista;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.Calendar;
final class DriveSettings {
    private static final String P="epc_drive_settings_v140";
    static SharedPreferences p(Context c){return c.getSharedPreferences(P,Context.MODE_PRIVATE);}
    static boolean autoNight(Context c){return p(c).getBoolean("auto_night",true);}
    static boolean hudMirror(Context c){return p(c).getBoolean("hud_mirror",true);}
    static boolean nightNow(Context c){if(!autoNight(c))return p(c).getBoolean("night_force",false);int h=Calendar.getInstance().get(Calendar.HOUR_OF_DAY);return h>=19||h<6;}
    static void toggle(Context c,String key,boolean value){p(c).edit().putBoolean(key,value).apply();}
}
''')

# -----------------------------------------------------------------------------
# HUD.
# -----------------------------------------------------------------------------
write('app/src/main/java/com/estradaplay/comunista/HudActivity.java', r'''package com.estradaplay.comunista;
import android.content.*;import android.graphics.*;import android.os.*;import android.view.*;import android.widget.*;import androidx.activity.ComponentActivity;
public final class HudActivity extends ComponentActivity{
    private LinearLayout root;private TextView speed,limit,hazard;private boolean registered;
    private final BroadcastReceiver rx=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){speed.setText(Math.round(i.getDoubleExtra("speed_kmh",0))+"");int l=i.getIntExtra("road_limit_kmh",0);limit.setText(l>0?"LIMITE "+l:"LIMITE --");String h=i.getStringExtra("hazard_label");double d=i.getDoubleExtra("distance_m",0);hazard.setText(h==null||h.isEmpty()?"PROTEÇÃO ATIVA":h.toUpperCase()+" · "+Math.round(d)+" m");}};
    @Override protected void onCreate(Bundle b){super.onCreate(b);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);build();}
    private void build(){root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setGravity(Gravity.CENTER);root.setPadding(dp(24),dp(18),dp(24),dp(18));root.setBackgroundColor(Color.BLACK);setContentView(root);speed=t("0",110,Color.rgb(255,40,40),true);speed.setGravity(Gravity.CENTER);limit=t("LIMITE --",24,Color.WHITE,true);limit.setGravity(Gravity.CENTER);hazard=t("PROTEÇÃO ATIVA",18,Color.rgb(210,190,180),true);hazard.setGravity(Gravity.CENTER);root.addView(speed);root.addView(limit);root.addView(hazard);Button close=new Button(this);close.setText("FECHAR HUD");close.setOnClickListener(v->finish());root.addView(close,new LinearLayout.LayoutParams(-1,dp(48)));if(DriveSettings.hudMirror(this))root.setScaleX(-1f);}
    @Override protected void onStart(){super.onStart();IntentFilter f=new IntentFilter(RoadSafetyService.ACTION_STATE);if(Build.VERSION.SDK_INT>=33)registerReceiver(rx,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(rx,f);registered=true;}
    @Override protected void onStop(){if(registered){try{unregisterReceiver(rx);}catch(Throwable ignored){}registered=false;}super.onStop();}
    private TextView t(String v,float s,int c,boolean b){TextView t=new TextView(this);t.setText(v);t.setTextSize(s);t.setTextColor(c);if(b)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
''')

# -----------------------------------------------------------------------------
# Trip history screen.
# -----------------------------------------------------------------------------
write('app/src/main/java/com/estradaplay/comunista/TripHistoryActivity.java', r'''package com.estradaplay.comunista;
import android.graphics.*;import android.graphics.drawable.GradientDrawable;import android.os.*;import android.view.*;import android.widget.*;import androidx.activity.ComponentActivity;import org.json.*;import java.text.*;import java.util.*;
public final class TripHistoryActivity extends ComponentActivity{
    private final int BG=Color.rgb(9,5,7),TEXT=Color.rgb(246,238,224),MUTED=Color.rgb(174,151,146),RED=Color.rgb(190,18,38),BORDER=Color.rgb(76,38,43);
    @Override protected void onCreate(Bundle b){super.onCreate(b);showHistory();}
    private void showHistory(){ScrollView sv=new ScrollView(this);LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(18),dp(18),dp(18),dp(30));page.setBackgroundColor(BG);sv.addView(page);setContentView(sv);page.addView(t("HISTÓRICO DE VIAGENS",26,TEXT,true));page.addView(t("Dados ficam somente neste aparelho.",12,MUTED,false));JSONArray a=TripRecorder.history(this);if(a.length()==0){TextView empty=t("Nenhuma viagem registrada ainda.",15,MUTED,false);page.addView(empty);m(empty,0,30,0,0);}for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(14),dp(13),dp(14),dp(13));c.setBackground(box(Color.rgb(22,10,13),12,BORDER));long start=o.optLong("started_at");double km=o.optDouble("distance_m")/1000.0;long dur=o.optLong("duration_ms");String date=new SimpleDateFormat("dd/MM/yyyy HH:mm",Locale.getDefault()).format(new Date(start));c.addView(t(date,13,RED,true));c.addView(t(String.format(Locale.getDefault(),"%.1f km · %s · máxima %.0f km/h",km,duration(dur),o.optDouble("max_speed")),16,TEXT,true));c.addView(t("Radares "+o.optInt("radars")+" · Quebra-molas "+o.optInt("bumps")+" · Câmeras "+o.optInt("cameras"),11,MUTED,false));page.addView(c);m(c,0,10,0,0);}Button clear=new Button(this);clear.setText("LIMPAR HISTÓRICO");clear.setOnClickListener(v->{TripRecorder.clear(this);showHistory();});page.addView(clear,new LinearLayout.LayoutParams(-1,dp(50)));m(clear,0,18,0,0);}
    private String duration(long ms){long min=Math.max(0,ms/60000);return (min/60)+"h "+(min%60)+"min";}private TextView t(String v,float s,int c,boolean b){TextView t=new TextView(this);t.setText(v);t.setTextSize(s);t.setTextColor(c);if(b)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}private GradientDrawable box(int c,int r,int st){GradientDrawable d=new GradientDrawable();d.setColor(c);d.setCornerRadius(dp(r));d.setStroke(dp(1),st);return d;}private void m(View v,int l,int t,int r,int b){if(v.getLayoutParams() instanceof LinearLayout.LayoutParams){LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)v.getLayoutParams();p.setMargins(dp(l),dp(t),dp(r),dp(b));v.setLayoutParams(p);}}private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
''')

# -----------------------------------------------------------------------------
# Tools/settings screen.
# -----------------------------------------------------------------------------
write('app/src/main/java/com/estradaplay/comunista/DriveToolsActivity.java', r'''package com.estradaplay.comunista;
import android.content.*;import android.graphics.*;import android.os.*;import android.view.*;import android.widget.*;import androidx.activity.ComponentActivity;
public final class DriveToolsActivity extends ComponentActivity{
    private LinearLayout page;private int TEXT=Color.rgb(246,238,224),MUTED=Color.rgb(174,151,146),RED=Color.rgb(190,18,38);
    @Override protected void onCreate(Bundle b){super.onCreate(b);build();}
    private void build(){ScrollView s=new ScrollView(this);page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(20),dp(20),dp(20),dp(30));page.setBackgroundColor(Color.rgb(9,5,7));s.addView(page);setContentView(s);page.addView(t("CENTRAL INTELIGENTE",27,TEXT,true));page.addView(t("Preferências locais de condução",12,MUTED,false));check("MODO NOTURNO AUTOMÁTICO","Escurece instrumentos à noite.","auto_night",DriveSettings.autoNight(this));check("HUD ESPELHADO","Inverte o HUD para refletir no para-brisa.","hud_mirror",DriveSettings.hudMirror(this));button("ABRIR HUD",HudActivity.class);button("HISTÓRICO DE VIAGENS",TripHistoryActivity.class);button("REPORTAR PONTO DA VIA",RoadReportActivity.class);button("CÂMERA / DASHCAM",CameraActivity.class);}
    private void check(String title,String sub,String key,boolean initial){CheckBox c=new CheckBox(this);c.setText(title+"\n"+sub);c.setTextColor(TEXT);c.setTextSize(14);c.setChecked(initial);c.setPadding(0,dp(10),0,dp(10));c.setOnCheckedChangeListener((b,v)->DriveSettings.toggle(this,key,v));page.addView(c,new LinearLayout.LayoutParams(-1,-2));}
    private void button(String label,Class<?> cls){Button b=new Button(this);b.setText(label);b.setTextColor(TEXT);b.setBackgroundColor(Color.rgb(62,14,23));b.setOnClickListener(v->startActivity(new Intent(this,cls)));page.addView(b,new LinearLayout.LayoutParams(-1,dp(54)));}
    private TextView t(String v,float s,int c,boolean b){TextView t=new TextView(this);t.setText(v);t.setTextSize(s);t.setTextColor(c);if(b)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
''')

# -----------------------------------------------------------------------------
# Road reports: local queue + best-effort server send.
# -----------------------------------------------------------------------------
write('app/src/main/java/com/estradaplay/comunista/RoadReportActivity.java', r'''package com.estradaplay.comunista;
import android.content.*;import android.graphics.*;import android.os.*;import android.view.*;import android.widget.*;import androidx.activity.ComponentActivity;import org.json.*;import java.util.*;
public final class RoadReportActivity extends ComponentActivity{
    private static final String P="epc_road_reports_v140";private LinearLayout page;private String selected="";private double lat=Double.NaN,lon=Double.NaN;private BroadcastReceiver rx;private boolean reg;
    @Override protected void onCreate(Bundle b){super.onCreate(b);build();}
    private void build(){page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(20),dp(22),dp(20),dp(24));page.setBackgroundColor(Color.rgb(9,5,7));setContentView(page);TextView h=t("REPORTAR PONTO",27,Color.rgb(246,238,224),true);page.addView(h);page.addView(t("Faça o reporte somente quando for seguro tocar no aparelho. A contribuição fica pendente até o servidor aceitar.",13,Color.rgb(174,151,146),false));for(String[] x:new String[][]{{"RADAR_NOVO","RADAR NOVO"},{"RADAR_REMOVIDO","RADAR REMOVIDO"},{"LIMITE_ERRADO","LIMITE ERRADO"},{"QUEBRA_MOLAS","QUEBRA-MOLAS"},{"CAMERA_MONITORAMENTO","CÂMERA DE MONITORAMENTO"}})addOption(x[0],x[1]);Button send=new Button(this);send.setText("SALVAR REPORTE");send.setOnClickListener(v->save());page.addView(send,new LinearLayout.LayoutParams(-1,dp(58)));}
    private void addOption(String key,String label){Button b=new Button(this);b.setText(label);b.setOnClickListener(v->{selected=key;Toast.makeText(this,"Selecionado: "+label,Toast.LENGTH_SHORT).show();});page.addView(b,new LinearLayout.LayoutParams(-1,dp(50)));}
    @Override protected void onStart(){super.onStart();rx=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){lat=i.getDoubleExtra("lat",Double.NaN);lon=i.getDoubleExtra("lon",Double.NaN);}};IntentFilter f=new IntentFilter(RoadSafetyService.ACTION_STATE);if(Build.VERSION.SDK_INT>=33)registerReceiver(rx,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(rx,f);reg=true;}
    @Override protected void onStop(){if(reg){try{unregisterReceiver(rx);}catch(Throwable ignored){}reg=false;}super.onStop();}
    private void save(){if(selected.isEmpty()){Toast.makeText(this,"Escolha o tipo do reporte.",Toast.LENGTH_SHORT).show();return;}if(!Double.isFinite(lat)||!Double.isFinite(lon)){Toast.makeText(this,"Aguarde uma posição GPS válida.",Toast.LENGTH_SHORT).show();return;}try{JSONObject r=new JSONObject();r.put("type",selected);r.put("lat",lat);r.put("lon",lon);r.put("created_at",System.currentTimeMillis());SharedPreferences p=getSharedPreferences(P,MODE_PRIVATE);JSONArray q=new JSONArray(p.getString("queue","[]"));q.put(r);p.edit().putString("queue",q.toString()).apply();new Thread(()->uploadQueue(p),"epc-report").start();Toast.makeText(this,"Reporte salvo. Obrigado.",Toast.LENGTH_LONG).show();finish();}catch(Throwable e){Toast.makeText(this,"Não foi possível salvar.",Toast.LENGTH_SHORT).show();}}
    private void uploadQueue(SharedPreferences p){try{JSONArray q=new JSONArray(p.getString("queue","[]"));if(q.length()==0)return;ApiClient api=new ApiClient(this);JSONArray keep=new JSONArray();for(int i=0;i<q.length();i++){JSONObject r=q.optJSONObject(i);if(r==null)continue;try{ApiClient.Response x=api.post("api/road_reports.php",r);if(!x.ok()||!x.json().optBoolean("ok",false))keep.put(r);}catch(Throwable e){keep.put(r);}}p.edit().putString("queue",keep.toString()).apply();}catch(Throwable ignored){}}
    private TextView t(String v,float s,int c,boolean b){TextView t=new TextView(this);t.setText(v);t.setTextSize(s);t.setTextColor(c);if(b)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
''')

# -----------------------------------------------------------------------------
# Home module linking the new tools.
# -----------------------------------------------------------------------------
main_rel='app/src/main/java/com/estradaplay/comunista/MainActivity.java'
main=read(main_rel)
anchor='        page.addView(camera); margins(camera, 0, 9, 0, 0);\n'
smart=r'''

        // INTELLIGENT_CORE_V140_HOME
        LinearLayout smart = column();
        smart.setPadding(dp(15), dp(14), dp(15), dp(14));
        smart.setBackground(bg(Color.rgb(26, 10, 15), 3, Color.rgb(118, 40, 49)));
        smart.addView(overline("ESTRADA INTELIGENTE 1.4", Color.rgb(226, 185, 76)));
        smart.addView(text("Proteção, histórico e instrumentos", 17, TEXT, true));
        TextView smartSub = text("RJ + MG + ES offline em segundo plano · HUD · reportes · histórico local", 10, MUTED, false);
        smart.addView(smartSub); margins(smartSub, 0, 3, 0, 9);
        LinearLayout smartActions = row();
        Button toolsButton = compactButton("FERRAMENTAS");
        Button historyButton = compactButton("HISTÓRICO");
        smartActions.addView(toolsButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams shp = new LinearLayout.LayoutParams(0, dp(48), 1); shp.setMargins(dp(8),0,0,0); smartActions.addView(historyButton, shp);
        smart.addView(smartActions);
        toolsButton.setOnClickListener(v -> startActivity(new Intent(this, DriveToolsActivity.class)));
        historyButton.setOnClickListener(v -> startActivity(new Intent(this, TripHistoryActivity.class)));
        page.addView(smart); margins(smart, 0, 9, 0, 0);
'''
main=replace_once(main,anchor,anchor+smart,'home intelligent card')
write(main_rel,main)

# -----------------------------------------------------------------------------
# Server endpoint for reports. It creates its tiny metadata table on first use.
# -----------------------------------------------------------------------------
Path('api/road_reports.php').write_text(r'''<?php
declare(strict_types=1);
require __DIR__.'/bootstrap.php';
require_login();
header('Cache-Control: no-store');

$driver=(string)db()->getAttribute(PDO::ATTR_DRIVER_NAME);
if($driver==='mysql'){
    db()->exec("CREATE TABLE IF NOT EXISTS road_reports (
      id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
      user_id BIGINT NULL, device_token VARCHAR(190) NULL,
      type VARCHAR(50) NOT NULL, latitude DOUBLE NOT NULL, longitude DOUBLE NOT NULL,
      status VARCHAR(30) NOT NULL DEFAULT 'PENDENTE', created_at DATETIME NOT NULL,
      INDEX idx_rr_status(status), INDEX idx_rr_geo(latitude,longitude)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
}else{
    db()->exec("CREATE TABLE IF NOT EXISTS road_reports (id INTEGER PRIMARY KEY AUTOINCREMENT,user_id INTEGER,device_token TEXT,type TEXT NOT NULL,latitude REAL NOT NULL,longitude REAL NOT NULL,status TEXT NOT NULL DEFAULT 'PENDENTE',created_at TEXT NOT NULL)");
}
$body=json_decode((string)file_get_contents('php://input'),true)?:[];
$type=strtoupper(trim((string)($body['type']??'')));
$lat=(float)($body['lat']??0);$lon=(float)($body['lon']??0);
$allowed=['RADAR_NOVO','RADAR_REMOVIDO','LIMITE_ERRADO','QUEBRA_MOLAS','CAMERA_MONITORAMENTO'];
if(!in_array($type,$allowed,true)||$lat<-35||$lat>6||$lon<-75||$lon>-30)json_response(['ok'=>false,'error'=>'Reporte inválido.'],422);
$user=$_SESSION['user_id']??null;$device=(string)($_SESSION['device_token']??'');
$stmt=db()->prepare('INSERT INTO road_reports (user_id,device_token,type,latitude,longitude,status,created_at) VALUES (?,?,?,?,?,\'PENDENTE\',?)');
$stmt->execute([$user,$device!==''?$device:null,$type,$lat,$lon,gmdate('Y-m-d H:i:s')]);
json_response(['ok'=>true,'id'=>(int)db()->lastInsertId(),'status'=>'PENDENTE']);
''', encoding='utf-8')

print('Estrada Play Comunista 1.4.0 intelligent road core patched')

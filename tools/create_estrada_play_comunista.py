#!/usr/bin/env python3
from pathlib import Path
import re
import shutil
import textwrap

BASE = Path('estradaplay-app-v2')
DEST = Path('estrada-play-comunista-app')

if not BASE.exists():
    raise SystemExit('Base estradaplay-app-v2 not found')

if DEST.exists():
    shutil.rmtree(DEST)

shutil.copytree(
    BASE,
    DEST,
    ignore=shutil.ignore_patterns('build', '.gradle', '*.keystore')
)

TEXT_EXT = {'.java', '.xml', '.gradle', '.properties', '.pro', '.md', '.txt', '.json'}
for path in DEST.rglob('*'):
    if not path.is_file() or path.suffix.lower() not in TEXT_EXT:
        continue
    try:
        data = path.read_text(encoding='utf-8')
    except UnicodeDecodeError:
        continue
    data = data.replace('com.estradaplay.app', 'com.estradaplay.comunista')
    data = data.replace('EstradaPlay/2.0', 'EstradaPlayComunista/1.0')
    path.write_text(data, encoding='utf-8')

# Move Java sources to a package-aligned directory.
src_pkg = DEST / 'app/src/main/java/com/estradaplay/app'
dst_pkg = DEST / 'app/src/main/java/com/estradaplay/comunista'
dst_pkg.parent.mkdir(parents=True, exist_ok=True)
if src_pkg.exists():
    shutil.move(str(src_pkg), str(dst_pkg))


def read(rel):
    return (DEST / rel).read_text(encoding='utf-8')


def write(rel, value):
    p = DEST / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(value, encoding='utf-8')


def must_replace(text, old, new, label):
    if old not in text:
        raise SystemExit(f'Patch anchor not found: {label}')
    return text.replace(old, new, 1)

# Independent package/version/project identity.
build = read('app/build.gradle')
build = re.sub(r"versionCode\s+\d+", 'versionCode 100', build, count=1)
build = re.sub(r"versionName\s+'[^']+'", "versionName '1.0.0'", build, count=1)
write('app/build.gradle', build)

settings = read('settings.gradle').replace("rootProject.name = 'EstradaPlay'", "rootProject.name = 'EstradaPlayComunista'")
write('settings.gradle', settings)

manifest = read('app/src/main/AndroidManifest.xml')
manifest = manifest.replace('android:label="EstradaPlay"', 'android:label="Estrada Play Comunista"')
anchor = '''        <activity\n            android:name=".MainActivity"'''
insert = '''        <activity\n            android:name=".DestinationActivity"\n            android:exported="false"\n            android:configChanges="orientation|screenSize|keyboardHidden|uiMode"\n            android:screenOrientation="fullSensor" />\n\n        <activity\n            android:name=".MainActivity"'''
manifest = must_replace(manifest, anchor, insert, 'DestinationActivity manifest')
write('app/src/main/AndroidManifest.xml', manifest)

styles = read('app/src/main/res/values/styles.xml')
styles = styles.replace('#FF6B2C', '#E01E2F')
write('app/src/main/res/values/styles.xml', styles)

# Red/black identity while retaining semantic safety colors.
main_rel = 'app/src/main/java/com/estradaplay/comunista/MainActivity.java'
main = read(main_rel)
main = main.replace('private final int ACCENT = Color.rgb(255, 107, 44);', 'private final int ACCENT = Color.rgb(224, 30, 47);')
main = main.replace('private final int ACCENT_SOFT = Color.rgb(67, 31, 20);', 'private final int ACCENT_SOFT = Color.rgb(72, 12, 20);')
main = main.replace('badge("EP", ACCENT, ACCENT_SOFT)', 'badge("EC", ACCENT, ACCENT_SOFT)')
main = main.replace('overline("ESTRADAPLAY", ACCENT)', 'overline("ESTRADA PLAY COMUNISTA", ACCENT)')
main = main.replace('text("EstradaPlay", 22, TEXT, true)', 'text("Estrada Play Comunista", 22, TEXT, true)')
main = main.replace('text("EstradaPlay", 20, TEXT, true)', 'text("Estrada Play Comunista", 20, TEXT, true)')
main = main.replace('text("EstradaPlay", 27, TEXT, true)', 'text("Estrada Play Comunista", 27, TEXT, true)')
home_old = '''        Button map = button("ABRIR MAPA E PROTEÇÃO", true);\n        page.addView(map, lp(-1, 60));\n        map.setOnClickListener(v -> openCockpit());\n\n        boolean hasDownloaded = library.hasDownloadedHint();'''
home_new = '''        Button map = button("DIRIGIR SEM DESTINO", true);\n        page.addView(map, lp(-1, 60));\n        map.setOnClickListener(v -> {\n            DestinationStore.clear(this);\n            openCockpit();\n        });\n\n        Button destination = button("DEFINIR DESTINO", false);\n        page.addView(destination, lp(-1, 58)); margins(destination, 0, 10, 0, 0);\n        destination.setOnClickListener(v -> startActivity(new Intent(this, DestinationActivity.class)));\n\n        TextView intelligence = text("INTELIGÊNCIA DE BORDO · proteção funciona com ou sem destino", 11, MUTED, true);\n        intelligence.setGravity(Gravity.CENTER);\n        page.addView(intelligence); margins(intelligence, 0, 10, 0, 4);\n\n        boolean hasDownloaded = library.hasDownloadedHint();'''
main = must_replace(main, home_old, home_new, 'Main home destination controls')
write(main_rel, main)

# Launcher always exposes the optional destination choice after setup.
gate_rel = 'app/src/main/java/com/estradaplay/comunista/GateActivity.java'
gate = read(gate_rel)
gate_old = '''        if (hasAccount() && library.hasSetupDone() && hasLocation()) {\n            // 1.6.1 uses the map shell proven in the 1.5 line to avoid the\n            // AutomotiveActivity startup crash reported on real devices.\n            next = new Intent(this, RoadMapActivity.class);\n        } else {\n            // Login/setup/permissions remain inside EstradaPlay.\n            next = new Intent(this, MainActivity.class);\n        }'''
gate_new = '''        // COMUNISTA_HOME_V100: destination is optional, so returning users land on\n        // the choice screen instead of being forced into a route. Passive protection\n        // starts as soon as the driver chooses "Dirigir sem destino".\n        next = new Intent(this, MainActivity.class);'''
gate = must_replace(gate, gate_old, gate_new, 'Gate optional destination')
gate = gate.replace('Color.rgb(5, 7, 10)', 'Color.rgb(8, 5, 7)')
write(gate_rel, gate)

# Local context-aware copilot. Safety events stay deterministic; only wording varies.
write('app/src/main/java/com/estradaplay/comunista/CommunistCopilot.java', textwrap.dedent(r'''\
package com.estradaplay.comunista;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Local context engine for the Estrada Play Comunista voice.
 * It is deliberately not an LLM: radar/limit decisions never depend on generated text.
 * The engine only chooses safe phrasing after the deterministic road engine fires an event.
 */
final class CommunistCopilot {
    private static final String PREFS = "epc_copilot_v1";
    private static final String KEY_LAST_COMRADE = "last_comrade_ms";
    private static final long COMRADE_GAP_MS = 150_000L;

    private final SharedPreferences prefs;
    private final AtomicInteger sequence = new AtomicInteger();

    CommunistCopilot(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    String roadLimit(int limitKmh) {
        int n = next();
        String[] variants = {
                "Novo limite da via: " + limitKmh + " quilômetros por hora.",
                "Atenção ao limite. Agora são " + limitKmh + " quilômetros por hora.",
                "Seguimos com limite de " + limitKmh + " quilômetros por hora."
        };
        return maybeComrade(variants[n % variants.length], false, n);
    }

    String overspeed(int limitKmh) {
        int n = next();
        String[] variants = {
                "Reduza. O limite da via é " + limitKmh + " quilômetros por hora.",
                "Velocidade acima do permitido. Volte para " + limitKmh + " quilômetros por hora.",
                "Velocidade demais não é revolução. Reduza para o limite de " + limitKmh + "."
        };
        // Overspeed must remain short and serious. Personality appears only occasionally.
        return maybeComrade(variants[n % variants.length], true, n);
    }

    String hazard(String type, double forwardM, int limitKmh) {
        int n = next();
        String distance = distanceSpeech(forwardM);
        String text;
        switch (type == null ? "" : type.toUpperCase(Locale.ROOT)) {
            case "SEMAFORO":
                text = n % 2 == 0
                        ? "Atenção. Semáforo à frente, a " + distance + "."
                        : "Semáforo à frente. " + distance + ". Mantenha atenção.";
                break;
            case "QUEBRA_MOLAS":
                text = n % 2 == 0
                        ? "Reduza. Quebra-molas à frente, a " + distance + "."
                        : "Quebra-molas em " + distance + ". Reduza com calma.";
                break;
            case "PEDAGIO":
                text = n % 2 == 0
                        ? "Pedágio à frente, a " + distance + "."
                        : "Prepare-se para o pedágio. Faltam " + distance + ".";
                break;
            case "PASSAGEM_NIVEL":
                text = "Atenção. Passagem de nível em " + distance + ". Reduza.";
                break;
            default:
                if (limitKmh > 0) {
                    text = n % 2 == 0
                            ? "Radar à frente, a " + distance + ". Limite de " + limitKmh + " quilômetros por hora."
                            : "Atenção na via. Radar em " + distance + ". Limite " + limitKmh + ".";
                } else {
                    text = "Radar à frente. " + distance + ".";
                }
                break;
        }
        return maybeComrade(text, true, n);
    }

    String routeStarted(String destination) {
        int n = next();
        String clean = destination == null ? "" : destination.trim();
        String base = clean.isEmpty()
                ? "Rota pronta. Seguimos."
                : "Rota para " + clean + " pronta. Seguimos.";
        return maybeComrade(base, false, n);
    }

    private int next() {
        return Math.abs(sequence.incrementAndGet());
    }

    private String maybeComrade(String text, boolean urgent, int n) {
        long now = System.currentTimeMillis();
        long last = prefs.getLong(KEY_LAST_COMRADE, 0L);
        boolean due = now - last >= COMRADE_GAP_MS;
        // Never force the word into every warning. Even when due, only some events use it.
        boolean use = due && ((urgent && n % 5 == 0) || (!urgent && n % 3 == 0));
        if (!use) return text;
        prefs.edit().putLong(KEY_LAST_COMRADE, now).apply();
        return "Camarada, " + lowerFirst(text);
    }

    private static String lowerFirst(String text) {
        if (text == null || text.isEmpty()) return "";
        if (text.length() == 1) return text.toLowerCase(Locale.ROOT);
        return text.substring(0, 1).toLowerCase(Locale.ROOT) + text.substring(1);
    }

    private static String distanceSpeech(double m) {
        if (!Double.isFinite(m) || m <= 0) return "alguns metros";
        if (m < 120) return Math.max(30, (int)(Math.round(m / 10.0) * 10)) + " metros";
        if (m >= 1000) return String.format(Locale.getDefault(), "%.1f quilômetros", m / 1000.0);
        return Math.max(100, (int)(Math.round(m / 50.0) * 50)) + " metros";
    }
}
'''))

# Destination persistence is local and independent from the normal EstradaPlay app.
write('app/src/main/java/com/estradaplay/comunista/DestinationStore.java', textwrap.dedent(r'''\
package com.estradaplay.comunista;

import android.content.Context;
import android.content.SharedPreferences;

final class DestinationStore {
    private static final String PREFS = "epc_destination_v1";

    static final class Destination {
        final String label;
        final double lat;
        final double lon;

        Destination(String label, double lat, double lon) {
            this.label = label == null ? "Destino" : label.trim();
            this.lat = lat;
            this.lon = lon;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void save(Context c, Destination d) {
        if (d == null || !Double.isFinite(d.lat) || !Double.isFinite(d.lon)) return;
        prefs(c).edit()
                .putBoolean("active", true)
                .putString("label", d.label)
                .putLong("lat_bits", Double.doubleToLongBits(d.lat))
                .putLong("lon_bits", Double.doubleToLongBits(d.lon))
                .apply();
    }

    static Destination read(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean("active", false)) return null;
        double lat = Double.longBitsToDouble(p.getLong("lat_bits", Double.doubleToLongBits(Double.NaN)));
        double lon = Double.longBitsToDouble(p.getLong("lon_bits", Double.doubleToLongBits(Double.NaN)));
        if (!Double.isFinite(lat) || !Double.isFinite(lon)) return null;
        return new Destination(p.getString("label", "Destino"), lat, lon);
    }

    static void clear(Context c) {
        prefs(c).edit().clear().apply();
    }

    static boolean same(Destination a, Destination b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return Math.abs(a.lat - b.lat) < 0.000001 && Math.abs(a.lon - b.lon) < 0.000001 && a.label.equals(b.label);
    }
}
'''))

# Online resolver used only for the address feature in this first test build.
write('app/src/main/java/com/estradaplay/comunista/DestinationResolver.java', textwrap.dedent(r'''\
package com.estradaplay.comunista;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class DestinationResolver {
    private DestinationResolver() {}

    static List<DestinationStore.Destination> search(String query) throws Exception {
        ArrayList<DestinationStore.Destination> out = new ArrayList<>();
        String q = query == null ? "" : query.trim();
        if (q.length() < 3) return out;
        String target = "https://nominatim.openstreetmap.org/search?format=jsonv2&countrycodes=br&limit=5&addressdetails=1&q="
                + URLEncoder.encode(q, "UTF-8");
        HttpURLConnection c = (HttpURLConnection) new URL(target).openConnection();
        c.setConnectTimeout(9000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9");
        c.setRequestProperty("User-Agent", "EstradaPlayComunista/1.0 Android destination-test");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            c.disconnect();
            throw new Exception("HTTP " + code);
        }
        String raw;
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                bytes.write(buf, 0, n);
                if (bytes.size() > 2_000_000) throw new Exception("Resposta grande demais");
            }
            raw = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
        JSONArray arr = new JSONArray(raw);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) continue;
            double lat = parse(item.optString("lat", ""));
            double lon = parse(item.optString("lon", ""));
            String label = item.optString("display_name", "Destino").trim();
            if (Double.isFinite(lat) && Double.isFinite(lon)) out.add(new DestinationStore.Destination(label, lat, lon));
        }
        return out;
    }

    private static double parse(String value) {
        try { return Double.parseDouble(value); } catch (Throwable ignored) { return Double.NaN; }
    }
}
'''))

# Public OSRM is intentionally a test router for v1.0. Production can be moved behind the server later.
write('app/src/main/java/com/estradaplay/comunista/RouteEngine.java', textwrap.dedent(r'''\
package com.estradaplay.comunista;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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
                "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson&steps=true&alternatives=false",
                fromLon, fromLat, toLon, toLat);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(25000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "EstradaPlayComunista/1.0 Android route-test");
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
        JSONObject r = routes.optJSONObject(0);
        if (r == null) throw new Exception("Rota inválida");
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
'''))

write('app/src/main/java/com/estradaplay/comunista/DestinationActivity.java', textwrap.dedent(r'''\
package com.estradaplay.comunista;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DestinationActivity extends ComponentActivity {
    private static final int BG = Color.rgb(7, 7, 9);
    private static final int SURFACE = Color.rgb(18, 14, 17);
    private static final int BORDER = Color.rgb(65, 32, 38);
    private static final int TEXT = Color.rgb(248, 246, 247);
    private static final int MUTED = Color.rgb(161, 145, 149);
    private static final int RED = Color.rgb(224, 30, 47);
    private static final int RED_DARK = Color.rgb(80, 12, 21);

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private EditText query;
    private Button search;
    private ProgressBar progress;
    private TextView status;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        build();
    }

    private void build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(22), dp(26), dp(22), dp(28));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);

        TextView over = label("ESTRADA PLAY COMUNISTA", 11, RED, true);
        over.setLetterSpacing(0.12f);
        page.addView(over);
        TextView title = label("Para onde vamos?", 31, TEXT, true);
        page.addView(title); margin(title, 0, 8, 0, 4);
        TextView body = label("O destino é opcional. Sem endereço, radares, lombadas, limites e proteção continuam funcionando normalmente.", 14, MUTED, false);
        page.addView(body); margin(body, 0, 0, 0, 20);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(panel(20, SURFACE, BORDER));
        page.addView(card, new LinearLayout.LayoutParams(-1, -2));

        query = new EditText(this);
        query.setHint("Rua, número, cidade ou lugar");
        query.setHintTextColor(MUTED);
        query.setTextColor(TEXT);
        query.setTextSize(16);
        query.setSingleLine(true);
        query.setPadding(dp(14), 0, dp(14), 0);
        query.setBackground(panel(15, Color.rgb(27, 20, 23), BORDER));
        card.addView(query, new LinearLayout.LayoutParams(-1, dp(58)));

        search = button("BUSCAR DESTINO", true);
        card.addView(search, new LinearLayout.LayoutParams(-1, dp(58))); margin(search, 0, 12, 0, 0);
        search.setOnClickListener(v -> search());

        progress = new ProgressBar(this);
        progress.setIndeterminateTintList(ColorStateList.valueOf(RED));
        progress.setVisibility(View.GONE);
        card.addView(progress, new LinearLayout.LayoutParams(-1, dp(36))); margin(progress, 0, 10, 0, 0);

        status = label("Busca de endereço disponível quando houver internet.", 12, MUTED, false);
        status.setGravity(Gravity.CENTER);
        card.addView(status); margin(status, 0, 8, 0, 0);

        Button passive = button("DIRIGIR SEM DESTINO", false);
        page.addView(passive, new LinearLayout.LayoutParams(-1, dp(58))); margin(passive, 0, 16, 0, 0);
        passive.setOnClickListener(v -> {
            DestinationStore.clear(this);
            openMap();
        });

        DestinationStore.Destination current = DestinationStore.read(this);
        if (current != null) {
            TextView currentLabel = label("DESTINO ATUAL", 10, RED, true);
            page.addView(currentLabel); margin(currentLabel, 0, 22, 0, 6);
            TextView currentText = label(current.label, 14, TEXT, true);
            currentText.setPadding(dp(14), dp(14), dp(14), dp(14));
            currentText.setBackground(panel(16, RED_DARK, BORDER));
            page.addView(currentText);
        }
    }

    private void search() {
        String q = query.getText().toString().trim();
        if (q.length() < 3) {
            status.setText("Digite pelo menos 3 caracteres.");
            return;
        }
        search.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        status.setText("Procurando no mapa…");
        io.execute(() -> {
            try {
                List<DestinationStore.Destination> results = DestinationResolver.search(q);
                ui.post(() -> showResults(results));
            } catch (Exception e) {
                ui.post(() -> {
                    search.setEnabled(true);
                    progress.setVisibility(View.GONE);
                    status.setText("Não consegui buscar agora. Verifique a internet e tente novamente.");
                });
            }
        });
    }

    private void showResults(List<DestinationStore.Destination> results) {
        search.setEnabled(true);
        progress.setVisibility(View.GONE);
        if (results == null || results.isEmpty()) {
            status.setText("Nenhum endereço encontrado.");
            return;
        }
        String[] labels = new String[results.size()];
        for (int i = 0; i < results.size(); i++) labels[i] = results.get(i).label;
        new AlertDialog.Builder(this)
                .setTitle("Escolha o destino")
                .setItems(labels, (dialog, which) -> {
                    DestinationStore.Destination d = results.get(which);
                    DestinationStore.save(this, d);
                    status.setText("Destino definido. Preparando a rota…");
                    openMap();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void openMap() {
        Intent i = new Intent(this, RoadMapActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
    }

    private Button button(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(TEXT);
        b.setTextSize(12);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setLetterSpacing(0.06f);
        b.setStateListAnimator(null);
        b.setBackground(panel(16, primary ? RED : Color.rgb(31, 22, 25), primary ? 0 : BORDER));
        return b;
    }

    private TextView label(String value, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private GradientDrawable panel(int radiusDp, int color, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) d.setStroke(dp(1), strokeColor);
        return d;
    }

    private void margin(View view, int l, int t, int r, int b) {
        if (!(view.getLayoutParams() instanceof LinearLayout.LayoutParams)) return;
        LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) view.getLayoutParams();
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        view.setLayoutParams(p);
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
'''))

# Integrate contextual voice into the deterministic safety service.
road_rel = 'app/src/main/java/com/estradaplay/comunista/RoadSafetyService.java'
road = read(road_rel)
road = road.replace('private EstradaPlayOfflineVoice offlineVoice;', 'private EstradaPlayOfflineVoice offlineVoice;\n    private CommunistCopilot copilot;')
road = road.replace('offlineVoice = new EstradaPlayOfflineVoice(this);', 'offlineVoice = new EstradaPlayOfflineVoice(this);\n        copilot = new CommunistCopilot(this);')
road = road.replace('tts.setSpeechRate(0.93f);', 'tts.setSpeechRate(0.91f);')
road = road.replace('tts.setPitch(0.88f);', 'tts.setPitch(0.84f);')
old_limit = '''    private boolean speakRoadLimitVoice(int limitKmh) {\n        if (offlineVoice != null) {\n            prepareEmbeddedVoice();\n            if (offlineVoice.playRoadLimit(limitKmh, this::restoreAudioAfterVoice)) return true;\n            restoreAudioAfterVoice();\n        }\n        if (ttsReady) {\n            speak("Limite da via, " + limitKmh + " quilômetros por hora.");\n            return true;\n        }\n        return false;\n    }'''
new_limit = '''    private boolean speakRoadLimitVoice(int limitKmh) {\n        if (ttsReady && copilot != null) {\n            speak(copilot.roadLimit(limitKmh));\n            return true;\n        }\n        if (offlineVoice != null) {\n            prepareEmbeddedVoice();\n            if (offlineVoice.playRoadLimit(limitKmh, this::restoreAudioAfterVoice)) return true;\n            restoreAudioAfterVoice();\n        }\n        return false;\n    }'''
road = must_replace(road, old_limit, new_limit, 'copilot road limit')
old_over = '''    private boolean speakOverspeedVoice(int limitKmh) {\n        if (offlineVoice != null) {\n            prepareEmbeddedVoice();\n            if (offlineVoice.playOverspeed(limitKmh, this::restoreAudioAfterVoice)) return true;\n            restoreAudioAfterVoice();\n        }\n        if (ttsReady) {\n            speak("Atenção. Você passou do limite da via. Limite de " + limitKmh + " quilômetros por hora.");\n            return true;\n        }\n        return false;\n    }'''
new_over = '''    private boolean speakOverspeedVoice(int limitKmh) {\n        if (ttsReady && copilot != null) {\n            speak(copilot.overspeed(limitKmh));\n            return true;\n        }\n        if (offlineVoice != null) {\n            prepareEmbeddedVoice();\n            if (offlineVoice.playOverspeed(limitKmh, this::restoreAudioAfterVoice)) return true;\n            restoreAudioAfterVoice();\n        }\n        return false;\n    }'''
road = must_replace(road, old_over, new_over, 'copilot overspeed')
old_hazard = '''    private void speakHazardVoice(RoadHazard h, double forwardM) {\n        if (offlineVoice != null && h != null) {\n            prepareEmbeddedVoice();\n            if (offlineVoice.playHazard(h.type, forwardM, h.speed, this::restoreAudioAfterVoice)) return;\n            restoreAudioAfterVoice();\n        }\n        speak(voice(h, forwardM));\n    }'''
new_hazard = '''    private void speakHazardVoice(RoadHazard h, double forwardM) {\n        if (ttsReady && copilot != null && h != null) {\n            speak(copilot.hazard(h.type, forwardM, h.speed));\n            return;\n        }\n        if (offlineVoice != null && h != null) {\n            prepareEmbeddedVoice();\n            if (offlineVoice.playHazard(h.type, forwardM, h.speed, this::restoreAudioAfterVoice)) return;\n            restoreAudioAfterVoice();\n        }\n        speak(voice(h, forwardM));\n    }'''
road = must_replace(road, old_hazard, new_hazard, 'copilot hazard')
write(road_rel, road)

# Route line support in MapLibre, including offline map style.
map_rel = 'app/src/main/java/com/estradaplay/comunista/RoadMapView.java'
mapv = read(map_rel)
mapv = mapv.replace('private GeoJsonSource offlineRoadSource;', 'private GeoJsonSource offlineRoadSource;\n    private GeoJsonSource routeSource;')
mapv = mapv.replace('private String offlineRoadGeoJson = EMPTY_GEOJSON;', 'private String offlineRoadGeoJson = EMPTY_GEOJSON;\n    private String routeGeoJson = EMPTY_GEOJSON;')
mapv = mapv.replace('setBackgroundColor(Color.rgb(5, 8, 12));', 'setBackgroundColor(Color.rgb(7, 7, 9));')
mapv = mapv.replace('fallback.setBackgroundColor(Color.rgb(5, 8, 12));', 'fallback.setBackgroundColor(Color.rgb(7, 7, 9));')
open_anchor = '''                currentStyle = style;\n                offlineStyle = false;\n                mapReady = true;'''
open_new = '''                currentStyle = style;\n                offlineRoadSource = null;\n                routeSource = null;\n                offlineStyle = false;\n                mapReady = true;'''
mapv = must_replace(mapv, open_anchor, open_new, 'open map route source reset')
mapv = must_replace(mapv, '                overlay.invalidate();\n            });\n        } catch (Throwable e) {\n            loadOfflineMap();', '                installRoute(style);\n                overlay.invalidate();\n            });\n        } catch (Throwable e) {\n            loadOfflineMap();', 'install route open style')
off_anchor = '''                currentStyle = style;\n                offlineStyle = true;\n                mapReady = true;'''
off_new = '''                currentStyle = style;\n                offlineRoadSource = null;\n                routeSource = null;\n                offlineStyle = true;\n                mapReady = true;'''
mapv = must_replace(mapv, off_anchor, off_new, 'offline route source reset')
mapv = must_replace(mapv, '                installOfflineRoads(style);\n                if (mapView != null)', '                installOfflineRoads(style);\n                installRoute(style);\n                if (mapView != null)', 'install route offline style')
route_methods_anchor = '''    void setFollow(boolean value) {'''
route_methods = '''    void setRouteGeoJson(String geoJson) {\n        routeGeoJson = geoJson == null || geoJson.trim().isEmpty() ? EMPTY_GEOJSON : geoJson;\n        if (currentStyle != null) installRoute(currentStyle);\n    }\n\n    private void installRoute(Style style) {\n        try {\n            GeoJsonSource source = routeSource;\n            if (source == null) {\n                source = new GeoJsonSource("epc-route", routeGeoJson);\n                style.addSource(source);\n                routeSource = source;\n                LineLayer casing = new LineLayer("epc-route-casing", "epc-route")\n                        .withProperties(lineColor("#2b080d"), lineWidth(8.5f), lineOpacity(0.92f));\n                LineLayer route = new LineLayer("epc-route-line", "epc-route")\n                        .withProperties(lineColor("#e01e2f"), lineWidth(5.3f), lineOpacity(0.98f));\n                style.addLayer(casing);\n                style.addLayer(route);\n            } else {\n                source.setGeoJson(routeGeoJson);\n            }\n        } catch (Throwable ignored) {}\n    }\n\n    void setFollow(boolean value) {'''
mapv = must_replace(mapv, route_methods_anchor, route_methods, 'route methods')
write(map_rel, mapv)

# Cockpit: optional destination, test routing, red identity.
rm_rel = 'app/src/main/java/com/estradaplay/comunista/RoadMapActivity.java'
rm = read(rm_rel)
rm = rm.replace('import java.util.concurrent.Executors;', 'import java.util.concurrent.Executors;\nimport java.util.concurrent.atomic.AtomicBoolean;')
rm = rm.replace('private final int ACCENT = Color.rgb(255, 111, 48);', 'private final int ACCENT = Color.rgb(224, 30, 47);')
rm = rm.replace('private final int ACCENT_SOFT = Color.rgb(66, 34, 23);', 'private final int ACCENT_SOFT = Color.rgb(72, 12, 20);')
rm = rm.replace('private final ExecutorService io = Executors.newSingleThreadExecutor();', 'private final ExecutorService io = Executors.newSingleThreadExecutor();\n    private final ExecutorService routeIo = Executors.newSingleThreadExecutor();\n    private final AtomicBoolean routeLoading = new AtomicBoolean(false);')
rm = rm.replace('private TextView gpsText;', 'private TextView gpsText;\n    private TextView destinationText;')
rm = rm.replace('private boolean receiverRegistered;', 'private boolean receiverRegistered;\n    private DestinationStore.Destination destination;\n    private RouteEngine.Route activeRoute;\n    private long lastRouteAt;\n    private double lastRouteLat = Double.NaN;\n    private double lastRouteLon = Double.NaN;')
rm = rm.replace('offlineRoadStore = new OfflineRoadStore(this);', 'offlineRoadStore = new OfflineRoadStore(this);\n        destination = DestinationStore.read(this);', 1)
rm = rm.replace('refreshMapData(lat, lon, count);', 'refreshMapData(lat, lon, count);\n                refreshDestinationRoute(lat, lon);', 1)
rm = rm.replace('brand.addView(label("EstradaPlay", 18, TEXT, true));', 'brand.addView(label("Estrada Play Comunista", 18, TEXT, true));')
rm = rm.replace('TextView logo = label("EP", compact ? 18 : 21, TEXT, true);', 'TextView logo = label("EC", compact ? 18 : 21, TEXT, true);')
rm = rm.replace('TextView drive = label("  ESTRADAPLAY  ·  CONDUÇÃO", compact ? 9 : 10, TEXT, true);', 'TextView drive = label("  ESTRADA PLAY COMUNISTA  ·  CONDUÇÃO", compact ? 9 : 10, TEXT, true);')
rm = rm.replace('TextView title = label("EstradaPlay", compact ? 16 : 20, TEXT, true);', 'TextView title = label("Estrada Play Comunista", compact ? 16 : 20, TEXT, true);')
status_old = '''        TextView statusOver = label("SEGURANÇA", 8, GREEN, true);\n        statusOver.setLetterSpacing(0.13f);\n        statusCard.addView(statusOver);\n        TextView statusTitle = label("Proteção rodoviária", compact ? 15 : 18, TEXT, true);\n        statusCard.addView(statusTitle);\n        TextView statusBody = label("Radar · semáforo · quebra-molas · pedágio · passagem de nível", compact ? 9 : 10, MUTED, false);\n        statusCard.addView(statusBody, new LinearLayout.LayoutParams(-1, 0, 1f));\n        TextView active = label("●  ATIVA EM SEGUNDO PLANO", 8, GREEN, true);\n        statusCard.addView(active);'''
status_new = '''        TextView statusOver = label(destination == null ? "PROTEÇÃO" : "ROTA ATIVA", 8, destination == null ? GREEN : ACCENT, true);\n        statusOver.setLetterSpacing(0.13f);\n        statusCard.addView(statusOver);\n        TextView statusTitle = label(destination == null ? "Proteção rodoviária" : shortDestination(destination.label), compact ? 15 : 18, TEXT, true);\n        statusCard.addView(statusTitle);\n        destinationText = label(destination == null\n                ? "Sem destino · radares, limites e alertas continuam ativos"\n                : "Calculando distância e tempo até o destino…", compact ? 9 : 10, MUTED, false);\n        statusCard.addView(destinationText, new LinearLayout.LayoutParams(-1, 0, 1f));\n        TextView active = label(destination == null ? "●  PROTEÇÃO PASSIVA ATIVA" : "●  NAVEGAÇÃO + PROTEÇÃO", 8, GREEN, true);\n        statusCard.addView(active);'''
rm = must_replace(rm, status_old, status_new, 'cockpit destination status')
map_card_anchor = '''        TextView reserve = label("Reserva automática de até 250 km à frente", 8, MUTED, false);\n        mapCard.addView(reserve);'''
map_card_new = '''        TextView reserve = label("Reserva automática de até 250 km à frente", 8, MUTED, false);\n        mapCard.addView(reserve);\n        Button destinationButton = action(destination == null ? "DEFINIR DESTINO" : "ALTERAR DESTINO", false);\n        mapCard.addView(destinationButton, new LinearLayout.LayoutParams(-1, clamp(Math.round(height * 0.062f), dp(38), dp(48))));\n        destinationButton.setOnClickListener(v -> startActivity(new Intent(this, DestinationActivity.class)));'''
rm = must_replace(rm, map_card_anchor, map_card_new, 'destination button cockpit')
seed_anchor = '''                refreshMapData(lastLat, lastLon, 0);'''
rm = must_replace(rm, seed_anchor, '                refreshMapData(lastLat, lastLon, 0);\n                refreshDestinationRoute(lastLat, lastLon);', 'seed destination route')
methods_anchor = '''    private void updateMapStatus() {'''
methods = r'''    private void refreshDestinationRoute(double lat, double lon) {
        DestinationStore.Destination d = destination;
        if (d == null || !Double.isFinite(lat) || !Double.isFinite(lon)) {
            if (roadMap != null) roadMap.setRouteGeoJson(null);
            return;
        }
        long now = System.currentTimeMillis();
        if (routeLoading.get()) return;
        if (activeRoute != null && Double.isFinite(lastRouteLat) && Double.isFinite(lastRouteLon)) {
            double moved = RoadPackStore.distanceM(lat, lon, lastRouteLat, lastRouteLon);
            if (moved < 700 && now - lastRouteAt < 120_000L) return;
        }
        if (!routeLoading.compareAndSet(false, true)) return;
        if (destinationText != null) destinationText.setText("Calculando rota…");
        routeIo.execute(() -> {
            try {
                RouteEngine.Route route = RouteEngine.fetch(lat, lon, d.lat, d.lon);
                activeRoute = route;
                lastRouteAt = System.currentTimeMillis();
                lastRouteLat = lat;
                lastRouteLon = lon;
                ui.post(() -> {
                    if (roadMap != null) roadMap.setRouteGeoJson(route.geoJson);
                    if (destinationText != null) {
                        String detail = route.summary();
                        if (!route.nextInstruction.isEmpty()) detail += "\n" + route.nextInstruction;
                        destinationText.setText(detail);
                    }
                });
            } catch (Throwable e) {
                ui.post(() -> {
                    if (destinationText != null) destinationText.setText("Rota online indisponível. A proteção da estrada continua ativa.");
                });
            } finally {
                routeLoading.set(false);
            }
        });
    }

    private String shortDestination(String value) {
        String v = value == null ? "Destino" : value.trim();
        int comma = v.indexOf(',');
        if (comma > 0) v = v.substring(0, comma).trim();
        return v.length() > 30 ? v.substring(0, 30) + "…" : v;
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        DestinationStore.Destination fresh = DestinationStore.read(this);
        if (!DestinationStore.same(destination, fresh)) {
            destination = fresh;
            activeRoute = null;
            lastRouteAt = 0L;
            if (roadMap != null) roadMap.setRouteGeoJson(null);
            if (root != null) buildResponsiveUi();
            if (Double.isFinite(lastLat) && Double.isFinite(lastLon)) refreshDestinationRoute(lastLat, lastLon);
        }
    }

    private void updateMapStatus() {'''
rm = must_replace(rm, methods_anchor, methods, 'route activity methods')
rm = rm.replace('try { io.shutdownNow(); } catch (Throwable ignored) {}', 'try { io.shutdownNow(); } catch (Throwable ignored) {}\n        try { routeIo.shutdownNow(); } catch (Throwable ignored) {}')
write(rm_rel, rm)

# A few visible product strings, without renaming Java classes/preferences.
for rel in [
    'app/src/main/java/com/estradaplay/comunista/MainActivity.java',
    'app/src/main/java/com/estradaplay/comunista/RoadMapActivity.java',
    'app/src/main/java/com/estradaplay/comunista/PlayerService.java',
    'app/src/main/java/com/estradaplay/comunista/DownloadService.java',
    'app/src/main/java/com/estradaplay/comunista/RoadSafetyService.java',
]:
    text = read(rel)
    text = text.replace('"EstradaPlay"', '"Estrada Play Comunista"')
    text = text.replace('"ESTRADAPLAY"', '"ESTRADA PLAY COMUNISTA"')
    write(rel, text)

# Documentation inside the new project.
write('README-COMUNISTA.md', textwrap.dedent('''\
# Estrada Play Comunista 1.0.0

Aplicativo Android nativo separado do EstradaPlay normal.

- applicationId: `com.estradaplay.comunista`
- destino opcional: proteção passiva continua sem rota
- busca de endereço para testes via Nominatim / OpenStreetMap
- rota de teste via servidor público OSRM
- MapLibre + linha vermelha de rota
- copiloto local contextual: segurança é determinística e a personalidade só escolhe a frase
- mesma API MusicRoad/EstradaPlay para conta, música e base rodoviária

A busca Nominatim e o roteador público OSRM são adequados para validação inicial, não para escala comercial. Antes de produção, mover geocodificação/roteamento para infraestrutura própria ou provedor contratado.
'''))

print('Estrada Play Comunista 1.0.0 generated at', DEST)

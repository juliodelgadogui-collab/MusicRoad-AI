package com.estradaplay.comunista;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

import org.json.JSONObject;

/**
 * Estrada Play 5.0 visual theme engine.
 *
 * The default theme ships with the APK. Optional themes are imported as JSON and stored locally.
 * No executable code, file paths or remote scripts are accepted from a theme file.
 */
public final class EstradaTheme {
    private static final String PREFS = "estrada_play_theme_v500";
    private static final String KEY_JSON = "theme_json";

    public final String name;
    public final int background;
    public final int surface;
    public final int surfaceAlt;
    public final int glass;
    public final int border;
    public final int primary;
    public final int secondary;
    public final int text;
    public final int muted;
    public final int success;
    public final int warning;
    public final int danger;
    public final int road;
    public final int radiusDp;

    private EstradaTheme(String name, int background, int surface, int surfaceAlt, int glass,
                         int border, int primary, int secondary, int text, int muted,
                         int success, int warning, int danger, int road, int radiusDp) {
        this.name = name;
        this.background = background;
        this.surface = surface;
        this.surfaceAlt = surfaceAlt;
        this.glass = glass;
        this.border = border;
        this.primary = primary;
        this.secondary = secondary;
        this.text = text;
        this.muted = muted;
        this.success = success;
        this.warning = warning;
        this.danger = danger;
        this.road = road;
        this.radiusDp = Math.max(8, Math.min(32, radiusDp));
    }

    public static EstradaTheme get(Context context) {
        if (context != null) {
            try {
                SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                String raw = p.getString(KEY_JSON, "");
                if (raw != null && !raw.trim().isEmpty()) return parse(raw);
            } catch (Throwable ignored) {}
        }
        return defaultTheme();
    }

    public static EstradaTheme defaultTheme() {
        return new EstradaTheme(
                "Midnight Drive",
                Color.rgb(5, 8, 13),
                Color.rgb(11, 16, 24),
                Color.rgb(17, 24, 34),
                Color.argb(232, 8, 13, 20),
                Color.rgb(42, 54, 69),
                Color.rgb(44, 132, 255),
                Color.rgb(88, 207, 255),
                Color.rgb(246, 249, 252),
                Color.rgb(154, 168, 184),
                Color.rgb(52, 211, 153),
                Color.rgb(251, 191, 36),
                Color.rgb(255, 82, 103),
                Color.rgb(95, 170, 255),
                18);
    }

    public static EstradaTheme parse(String json) throws Exception {
        JSONObject j = new JSONObject(json == null ? "{}" : json);
        EstradaTheme d = defaultTheme();
        String name = cleanName(j.optString("name", d.name));
        int radius = j.optInt("radiusDp", d.radiusDp);
        return new EstradaTheme(
                name,
                color(j, "background", d.background),
                color(j, "surface", d.surface),
                color(j, "surfaceAlt", d.surfaceAlt),
                withAlpha(color(j, "glass", stripAlpha(d.glass)), j.optInt("glassAlpha", Color.alpha(d.glass))),
                color(j, "border", d.border),
                color(j, "primary", d.primary),
                color(j, "secondary", d.secondary),
                color(j, "text", d.text),
                color(j, "muted", d.muted),
                color(j, "success", d.success),
                color(j, "warning", d.warning),
                color(j, "danger", d.danger),
                color(j, "road", d.road),
                radius);
    }

    public static String install(Context context, String json) throws Exception {
        if (context == null) throw new IllegalArgumentException("Contexto indisponível");
        if (json == null || json.trim().isEmpty()) throw new IllegalArgumentException("Arquivo vazio");
        if (json.length() > 64 * 1024) throw new IllegalArgumentException("Tema muito grande");
        EstradaTheme parsed = parse(json);
        JSONObject normalized = new JSONObject();
        normalized.put("name", parsed.name);
        normalized.put("background", hex(parsed.background));
        normalized.put("surface", hex(parsed.surface));
        normalized.put("surfaceAlt", hex(parsed.surfaceAlt));
        normalized.put("glass", hex(stripAlpha(parsed.glass)));
        normalized.put("glassAlpha", Color.alpha(parsed.glass));
        normalized.put("border", hex(parsed.border));
        normalized.put("primary", hex(parsed.primary));
        normalized.put("secondary", hex(parsed.secondary));
        normalized.put("text", hex(parsed.text));
        normalized.put("muted", hex(parsed.muted));
        normalized.put("success", hex(parsed.success));
        normalized.put("warning", hex(parsed.warning));
        normalized.put("danger", hex(parsed.danger));
        normalized.put("road", hex(parsed.road));
        normalized.put("radiusDp", parsed.radiusDp);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_JSON, normalized.toString())
                .apply();
        return parsed.name;
    }

    public static void reset(Context context) {
        if (context == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_JSON).apply();
    }

    public static boolean imported(Context context) {
        if (context == null) return false;
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY_JSON);
    }

    public static String exampleJson() {
        try {
            EstradaTheme d = defaultTheme();
            JSONObject j = new JSONObject();
            j.put("name", "Meu tema");
            j.put("background", hex(d.background));
            j.put("surface", "#10151D");
            j.put("surfaceAlt", "#182231");
            j.put("glass", "#0B111A");
            j.put("glassAlpha", 232);
            j.put("border", "#34455B");
            j.put("primary", "#2C84FF");
            j.put("secondary", "#58CFFF");
            j.put("text", "#F6F9FC");
            j.put("muted", "#9AA8B8");
            j.put("success", "#34D399");
            j.put("warning", "#FBBF24");
            j.put("danger", "#FF5267");
            j.put("road", "#5FAAFF");
            j.put("radiusDp", 18);
            return j.toString(2);
        } catch (Throwable ignored) {
            return "{}";
        }
    }

    private static int color(JSONObject j, String key, int fallback) throws Exception {
        if (!j.has(key)) return fallback;
        String raw = j.optString(key, "").trim();
        if (raw.isEmpty()) return fallback;
        if (!raw.matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException("Cor inválida em " + key);
        return Color.parseColor(raw);
    }

    private static int withAlpha(int color, int alpha) {
        int a = Math.max(0, Math.min(255, alpha));
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static int stripAlpha(int color) {
        return Color.rgb(Color.red(color), Color.green(color), Color.blue(color));
    }

    private static String cleanName(String raw) {
        String n = raw == null ? "" : raw.replaceAll("[\\r\\n\\t]", " ").trim();
        if (n.isEmpty()) n = "Tema importado";
        return n.length() > 48 ? n.substring(0, 48) : n;
    }

    private static String hex(int color) {
        return String.format("#%02X%02X%02X", Color.red(color), Color.green(color), Color.blue(color));
    }
}

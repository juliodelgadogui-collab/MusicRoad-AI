\
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

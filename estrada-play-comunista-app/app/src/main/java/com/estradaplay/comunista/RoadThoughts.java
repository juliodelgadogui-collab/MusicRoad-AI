package com.estradaplay.comunista;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;

final class RoadThoughts {
    static final int MODE_OFF = 0;
    static final int MODE_SCREEN = 1;
    static final int MODE_SCREEN_VOICE = 2;
    private static final String PREFS = "epc_road_thoughts_v142";
    private static final String KEY_MODE = "mode";
    private static final String KEY_INTERVAL = "interval_min";
    private static final String KEY_LAST = "last_shown_at";
    private static final String KEY_INDEX = "next_index";

    static final class Entry {
        final String author;
        final String text;
        Entry(String author, String text) { this.author = author; this.text = text; }
    }

    private static final Entry[] ENTRIES = new Entry[]{
        new Entry("Karl Marx", "A realidade muda quando pessoas organizadas agem sobre ela."),
        new Entry("Karl Marx", "Compreender as condições concretas ajuda a escolher melhor o caminho."),
        new Entry("Friedrich Engels", "Conhecimento ganha força quando se transforma em ação consciente."),
        new Entry("Friedrich Engels", "Nenhuma transformação duradoura dispensa entender como a sociedade funciona."),
        new Entry("Rosa Luxemburgo", "Liberdade precisa alcançar também quem pensa e vive de outro modo."),
        new Entry("Rosa Luxemburgo", "Movimento, crítica e participação mantêm uma ideia viva."),
        new Entry("Antonio Gramsci", "Mesmo quando a razão vê dificuldades, a vontade ainda pode construir saída."),
        new Entry("Antonio Gramsci", "Organizar pensamento e ação é uma forma de enfrentar tempos difíceis."),
        new Entry("Che Guevara", "Uma jornada ganha sentido quando existe compromisso com algo maior que o indivíduo."),
        new Entry("Che Guevara", "Coerência aparece quando aquilo que se acredita também orienta a prática."),
        new Entry("Vladimir Lenin", "Analisar a situação concreta evita dirigir apenas por fórmulas prontas."),
        new Entry("Vladimir Lenin", "Organização transforma intenção dispersa em capacidade de agir."),
        new Entry("José Carlos Mariátegui", "Cada realidade precisa criar soluções nascidas de sua própria história."),
        new Entry("José Carlos Mariátegui", "Ideias importadas só ganham vida quando dialogam com o lugar onde chegam."),
        new Entry("Alexandra Kollontai", "Emancipação coletiva também passa por transformar relações do cotidiano."),
        new Entry("Alexandra Kollontai", "Uma sociedade mais livre exige autonomia e dignidade nas relações humanas."),
        new Entry("Clara Zetkin", "Direitos avançam quando participação e organização deixam de ser exceção."),
        new Entry("Clara Zetkin", "Solidariedade se fortalece quando ninguém é tratado como espectador da história."),
        new Entry("Paulo Freire", "Aprender a ler o mundo é também aprender a agir nele com consciência."),
        new Entry("Paulo Freire", "Diálogo verdadeiro começa quando ninguém é reduzido ao silêncio."),
        new Entry("Amílcar Cabral", "Conhecer a própria realidade é condição para transformá-la sem ilusões."),
        new Entry("Amílcar Cabral", "A prática deve ser medida pelos resultados concretos, não apenas pelas palavras."),
        new Entry("Frantz Fanon", "Libertação exige reconstruir também a maneira de enxergar a si e ao outro."),
        new Entry("Thomas Sankara", "Transformação coletiva pede responsabilidade, simplicidade e participação."),
    };

    private RoadThoughts() {}

    static int mode(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MODE, MODE_SCREEN_VOICE);
    }

    static void setMode(Context c, int mode) {
        int safe = Math.max(MODE_OFF, Math.min(MODE_SCREEN_VOICE, mode));
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_MODE, safe).apply();
    }

    static int intervalMinutes(Context c) {
        int v = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_INTERVAL, 25);
        return v == 20 || v == 30 ? v : 25;
    }

    static void setIntervalMinutes(Context c, int minutes) {
        int safe = minutes == 20 || minutes == 30 ? minutes : 25;
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_INTERVAL, safe).apply();
    }

    static long intervalMs(Context c) { return intervalMinutes(c) * 60_000L; }
    static long lastShownAt(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST, 0L); }
    static void markShown(Context c, long when) { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_LAST, when).apply(); }

    static Entry next(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int previous = p.getInt(KEY_INDEX, -1);
        int index;
        if (previous < 0 || previous >= ENTRIES.length) {
            index = Math.abs(Calendar.getInstance().get(Calendar.DAY_OF_YEAR) * 7) % ENTRIES.length;
        } else {
            index = (previous + 5) % ENTRIES.length;
        }
        p.edit().putInt(KEY_INDEX, index).apply();
        return ENTRIES[index];
    }

    static Entry preview(Context c) {
        int index = Math.abs(Calendar.getInstance().get(Calendar.DAY_OF_YEAR) * 7) % ENTRIES.length;
        return ENTRIES[index];
    }

    static String spoken(Entry e) {
        return e == null ? "" : "Pensamento da estrada. Inspirado em " + e.author + ". " + e.text;
    }

    static View homeCard(Context c) {
        if (mode(c) == MODE_OFF) return null;
        Entry e = preview(c);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14));
        box.setBackground(bg(c, Color.rgb(25, 10, 14), 4, Color.rgb(112, 69, 38)));
        TextView over = text(c, "PENSAMENTO DA ESTRADA", 9, Color.rgb(226, 185, 76), true);
        over.setLetterSpacing(0.12f); box.addView(over);
        TextView author = text(c, "INSPIRADO EM " + e.author.toUpperCase(), 12, Color.rgb(236, 205, 151), true);
        box.addView(author);
        TextView body = text(c, e.text, 15, Color.rgb(246, 238, 224), false);
        body.setLineSpacing(0, 1.08f); box.addView(body);
        TextView note = text(c, "PARÁFRASE · TOQUE PARA CONFIGURAR", 8, Color.rgb(174, 151, 146), true);
        note.setGravity(Gravity.RIGHT); box.addView(note);
        box.setClickable(true); box.setFocusable(true);
        return box;
    }

    private static TextView text(Context c, String value, float size, int color, boolean bold) {
        TextView t = new TextView(c); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t;
    }
    private static GradientDrawable bg(Context c, int color, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(c, radius));
        if (stroke != 0) d.setStroke(1, stroke); return d;
    }
    private static int dp(Context c, float v) {
        if (c == null) return Math.round(v);
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}

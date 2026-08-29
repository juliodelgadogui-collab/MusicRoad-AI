#!/usr/bin/env python3
from pathlib import Path

ROOT = Path('estrada-play-comunista-app')
J = ROOT / 'app/src/main/java/com/estradaplay/comunista'


def read(p): return p.read_text(encoding='utf-8')
def write(p, s): p.write_text(s, encoding='utf-8')
def rep(s, old, new, label):
    if old not in s:
        raise SystemExit('missing patch anchor: ' + label)
    return s.replace(old, new, 1)

# Version
p = ROOT / 'app/build.gradle'
s = read(p)
s = rep(s, 'versionCode 141', 'versionCode 142', 'versionCode')
s = rep(s, "versionName '1.4.1'", "versionName '1.4.2'", 'versionName')
write(p, s)

# -----------------------------------------------------------------------------
# Local thought library. All texts are explicitly paraphrases inspired by the
# named historical thinker, never presented as verbatim quotations.
# -----------------------------------------------------------------------------
write(J / 'RoadThoughts.java', r'''package com.estradaplay.comunista;

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
        box.setBackground(bg(Color.rgb(25, 10, 14), 4, Color.rgb(112, 69, 38)));
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
    private static GradientDrawable bg(int color, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(null, radius));
        if (stroke != 0) d.setStroke(1, stroke); return d;
    }
    private static int dp(Context c, float v) {
        if (c == null) return Math.round(v);
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
''')

# Fix corner-radius helper to use density consistently without static context leakage.
p = J / 'RoadThoughts.java'
s = read(p)
s = s.replace('box.setBackground(bg(Color.rgb(25, 10, 14), 4, Color.rgb(112, 69, 38)));',
              'box.setBackground(bg(c, Color.rgb(25, 10, 14), 4, Color.rgb(112, 69, 38)));')
s = s.replace('private static GradientDrawable bg(int color, int radius, int stroke) {\n        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(null, radius));',
              'private static GradientDrawable bg(Context c, int color, int radius, int stroke) {\n        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(c, radius));')
write(p, s)

# -----------------------------------------------------------------------------
# Settings / preview screen.
# -----------------------------------------------------------------------------
write(J / 'ThoughtsActivity.java', r'''package com.estradaplay.comunista;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

public final class ThoughtsActivity extends ComponentActivity {
    private final int BG=Color.rgb(9,5,7), TEXT=Color.rgb(246,238,224), MUTED=Color.rgb(174,151,146);
    private final int RED=Color.rgb(190,18,38), GOLD=Color.rgb(226,185,76), BORDER=Color.rgb(82,39,45), SURFACE=Color.rgb(24,11,15);
    private LinearLayout page;
    private TextView modeState, intervalState, author, body;

    @Override protected void onCreate(Bundle state) { super.onCreate(state); build(); }

    private void build() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(BG);
        page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp(20),dp(22),dp(20),dp(28));
        scroll.addView(page,new ScrollView.LayoutParams(-1,-2)); setContentView(scroll);

        TextView over=text("EPC / COPILOTO",9,GOLD,true); over.setLetterSpacing(.12f); page.addView(over);
        page.addView(text("PENSAMENTOS DA ESTRADA",26,TEXT,true));
        TextView desc=text("Frases curtas em forma de paráfrase, inspiradas em autores socialistas e comunistas. Nunca são tratadas como citação literal e nunca têm prioridade sobre um alerta de segurança.",13,MUTED,false);
        page.addView(desc); margin(desc,0,7,0,18);

        LinearLayout preview=card(); page.addView(preview);
        preview.addView(text("PENSAMENTO ATUAL",9,GOLD,true));
        author=text("",12,GOLD,true); preview.addView(author); margin(author,0,6,0,4);
        body=text("",18,TEXT,false); body.setLineSpacing(0,1.08f); preview.addView(body);
        TextView note=text("PARÁFRASE · INSPIRADO NO AUTOR",9,MUTED,true); preview.addView(note); margin(note,0,10,0,0);
        Button another=button("MOSTRAR OUTRA FRASE",false); preview.addView(another,new LinearLayout.LayoutParams(-1,dp(50))); margin(another,0,14,0,0);
        another.setOnClickListener(v -> renderEntry(RoadThoughts.next(this)));
        renderEntry(RoadThoughts.preview(this));

        TextView modeTitle=text("COMO APARECE",11,GOLD,true); page.addView(modeTitle); margin(modeTitle,0,22,0,6);
        modeState=text("",13,MUTED,false); page.addView(modeState); margin(modeState,0,0,0,8);
        addMode("DESLIGADO",RoadThoughts.MODE_OFF);
        addMode("SÓ NA TELA",RoadThoughts.MODE_SCREEN);
        addMode("TELA + VOZ",RoadThoughts.MODE_SCREEN_VOICE);

        TextView intTitle=text("INTERVALO DURANTE A VIAGEM",11,GOLD,true); page.addView(intTitle); margin(intTitle,0,22,0,6);
        intervalState=text("",13,MUTED,false); page.addView(intervalState); margin(intervalState,0,0,0,8);
        LinearLayout row=new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); page.addView(row);
        addInterval(row,"20 MIN",20); addInterval(row,"25 MIN",25); addInterval(row,"30 MIN",30);

        TextView safety=text("SEGURANÇA PRIMEIRO\nSe aparecer radar, quebra-molas, excesso de velocidade ou outro aviso importante, o pensamento é interrompido imediatamente. A primeira frase de uma viagem só pode aparecer depois de alguns minutos em movimento.",12,MUTED,false);
        safety.setBackground(panel(Color.rgb(34,14,18),BORDER)); safety.setPadding(dp(14),dp(12),dp(14),dp(12)); page.addView(safety); margin(safety,0,22,0,0);

        Button back=button("VOLTAR",true); page.addView(back,new LinearLayout.LayoutParams(-1,dp(56))); margin(back,0,18,0,0); back.setOnClickListener(v->finish());
        refreshStates();
    }

    private void addMode(String label,int mode){Button b=button(label,false);page.addView(b,new LinearLayout.LayoutParams(-1,dp(50)));margin(b,0,6,0,0);b.setOnClickListener(v->{RoadThoughts.setMode(this,mode);refreshStates();});}
    private void addInterval(LinearLayout row,String label,int min){Button b=button(label,false);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(48),1);if(row.getChildCount()>0)lp.setMargins(dp(6),0,0,0);row.addView(b,lp);b.setOnClickListener(v->{RoadThoughts.setIntervalMinutes(this,min);refreshStates();});}
    private void refreshStates(){int m=RoadThoughts.mode(this);modeState.setText(m==0?"Desligado":m==1?"Somente caixa na tela":"Caixa na tela + voz do copiloto");intervalState.setText("A cada aproximadamente "+RoadThoughts.intervalMinutes(this)+" minutos, somente quando não houver alerta de segurança.");}
    private void renderEntry(RoadThoughts.Entry e){author.setText("INSPIRADO EM "+e.author.toUpperCase());body.setText(e.text);}
    private LinearLayout card(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(16),dp(15),dp(16),dp(15));l.setBackground(panel(SURFACE,BORDER));return l;}
    private Button button(String label,boolean primary){Button b=new Button(this);b.setText(label);b.setTextColor(Color.WHITE);b.setTextSize(11);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setGravity(Gravity.CENTER);b.setBackground(panel(primary?RED:Color.rgb(42,20,25),primary?RED:BORDER));return b;}
    private TextView text(String v,float s,int c,boolean bold){TextView t=new TextView(this);t.setText(v);t.setTextSize(s);t.setTextColor(c);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private GradientDrawable panel(int color,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(4));if(stroke!=0)d.setStroke(1,stroke);return d;}
    private void margin(android.view.View v,int l,int t,int r,int b){android.view.ViewGroup.LayoutParams raw=v.getLayoutParams();if(raw instanceof LinearLayout.LayoutParams){LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)raw;p.setMargins(dp(l),dp(t),dp(r),dp(b));v.setLayoutParams(p);}}
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
''')

# -----------------------------------------------------------------------------
# Temporary non-blocking box used while driving.
# -----------------------------------------------------------------------------
write(J / 'RoadThoughtOverlay.java', r'''package com.estradaplay.comunista;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

final class RoadThoughtOverlay {
    private static final String TAG="epc-road-thought-v142";
    private RoadThoughtOverlay(){}

    static void show(Activity activity, FrameLayout host, Intent intent){
        if(activity==null||host==null||intent==null)return;
        String hazard=safe(intent.getStringExtra("hazard_label"));
        View old=host.findViewWithTag(TAG);
        if(!hazard.isEmpty()) { if(old!=null)host.removeView(old); return; }
        String body=safe(intent.getStringExtra("thought_text"));
        String author=safe(intent.getStringExtra("thought_author"));
        if(body.isEmpty()||author.isEmpty())return;
        if(old!=null)host.removeView(old);

        LinearLayout card=new LinearLayout(activity);card.setTag(TAG);card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(activity,15),dp(activity,11),dp(activity,15),dp(activity,11));
        card.setBackground(panel(activity,Color.argb(246,22,9,13),Color.rgb(130,78,38)));
        TextView over=t(activity,"PENSAMENTO DA ESTRADA  ·  PARÁFRASE",8,Color.rgb(226,185,76),true);over.setLetterSpacing(.10f);card.addView(over);
        TextView a=t(activity,"INSPIRADO EM "+author.toUpperCase(),10,Color.rgb(239,205,148),true);card.addView(a);
        TextView b=t(activity,body,14,Color.rgb(246,238,224),false);b.setMaxLines(3);card.addView(b);
        card.setClickable(false);card.setFocusable(false);
        if(Build.VERSION.SDK_INT>=21)card.setElevation(dp(activity,80));
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
        lp.setMargins(dp(activity,18),0,dp(activity,18),dp(activity,74));host.addView(card,lp);card.bringToFront();
        card.setAlpha(0f);card.setTranslationY(dp(activity,12));card.animate().alpha(1f).translationY(0).setDuration(180).start();
        host.postDelayed(()->{View current=host.findViewWithTag(TAG);if(current==card)card.animate().alpha(0f).translationY(dp(activity,10)).setDuration(180).withEndAction(()->{try{host.removeView(card);}catch(Throwable ignored){}}).start();},10500L);
    }
    private static TextView t(Activity a,String v,float s,int c,boolean bold){TextView t=new TextView(a);t.setText(v);t.setTextSize(s);t.setTextColor(c);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private static GradientDrawable panel(Activity a,int color,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(a,4));if(stroke!=0)d.setStroke(1,stroke);return d;}
    private static int dp(Activity a,float v){return Math.round(v*a.getResources().getDisplayMetrics().density);}
    private static String safe(String s){return s==null?"":s.trim();}
}
''')

# Manifest activity.
p = ROOT / 'app/src/main/AndroidManifest.xml'
s = read(p)
s = rep(s,
'''        <activity\n            android:name=".MainActivity"''',
'''        <activity\n            android:name=".ThoughtsActivity"\n            android:exported="false"\n            android:screenOrientation="${appOrientation}" />\n\n        <activity\n            android:name=".MainActivity"''', 'manifest thoughts')
write(p, s)

# Main home: one visible thought card + road overlay.
p = J / 'MainActivity.java'
s = read(p)
s = rep(s, '            SafetyAlertOverlay.show(MainActivity.this, root, intent);',
        '            SafetyAlertOverlay.show(MainActivity.this, root, intent);\n            RoadThoughtOverlay.show(MainActivity.this, root, intent);', 'main thought overlay')
s = rep(s,
'''        page.addView(manifesto);\n\n        LinearLayout stateLine = row();''',
'''        page.addView(manifesto);\n\n        View thoughtCard = RoadThoughts.homeCard(this);\n        if (thoughtCard != null) {\n            page.addView(thoughtCard); margins(thoughtCard, 0, 10, 0, 0);\n            thoughtCard.setOnClickListener(v -> startActivity(new Intent(this, ThoughtsActivity.class)));\n        }\n\n        LinearLayout stateLine = row();''', 'home thought card')
write(p, s)

# Map/cockpit overlay hooks.
for name, owner in [('RoadMapActivity.java','RoadMapActivity.this'),('AutomotiveActivity.java','AutomotiveActivity.this')]:
    p = J / name
    s = read(p)
    s = rep(s, f'            SafetyAlertOverlay.show({owner}, root, intent);',
            f'            SafetyAlertOverlay.show({owner}, root, intent);\n            RoadThoughtOverlay.show({owner}, root, intent);', name+' overlay')
    write(p, s)

# -----------------------------------------------------------------------------
# Road service: 25-minute default cadence, first thought after 4 minutes moving,
# visual event always; optional TTS. Any safety speech interrupts a thought.
# -----------------------------------------------------------------------------
p = J / 'RoadSafetyService.java'
s = read(p)
s = rep(s,
'''    private boolean roadOverspeedWarned;\n    private long lastRoadLimitCheckAt;''',
'''    private boolean roadOverspeedWarned;\n    private long lastRoadLimitCheckAt;\n    // ROAD_THOUGHTS_V142: low-priority cultural layer; safety always wins.\n    private final long thoughtSessionStartedAt = System.currentTimeMillis();\n    private long lastThoughtCheckAt;\n    private long lastSafetyVoiceAt;''', 'thought fields')

s = rep(s,
'''            if (now - lastNotificationAt > 7000L) updateNotification("Proteção na estrada ativa", state, false);\n            broadcast(loc, speedKmh, null, 0, state, null, 0);''',
'''            if (now - lastNotificationAt > 7000L) updateNotification("Proteção na estrada ativa", state, false);\n            maybeEmitRoadThought(loc, speedKmh);\n            broadcast(loc, speedKmh, null, 0, state, null, 0);''', 'thought emit call')

s = rep(s,
'''    private void maybeResolveRoadLimit(double lat, double lon, float heading) {''',
'''    private void maybeEmitRoadThought(Location loc, double speedKmh) {\n        int mode = RoadThoughts.mode(this);\n        if (mode == RoadThoughts.MODE_OFF || loc == null || speedKmh < 5.0) return;\n        long now = System.currentTimeMillis();\n        if (now - lastThoughtCheckAt < 10000L) return;\n        lastThoughtCheckAt = now;\n        if (now - thoughtSessionStartedAt < 4L * 60L * 1000L) return;\n        if (now - lastSafetyVoiceAt < 60_000L) return;\n        long previousThought = RoadThoughts.lastShownAt(this);\n        if (previousThought > 0 && now - previousThought < RoadThoughts.intervalMs(this)) return;\n\n        RoadThoughts.Entry e = RoadThoughts.next(this);\n        if (e == null) return;\n        RoadThoughts.markShown(this, now);\n        Intent thought = baseBroadcast(loc.getLatitude(), loc.getLongitude(), speedKmh, "Proteção ativa");\n        thought.putExtra("thought_author", e.author);\n        thought.putExtra("thought_text", e.text);\n        thought.putExtra("thought_paraphrase", true);\n        sendBroadcast(thought);\n        if (mode == RoadThoughts.MODE_SCREEN_VOICE && ttsReady) speak(RoadThoughts.spoken(e));\n    }\n\n    private void interruptThoughtForSafety() {\n        lastSafetyVoiceAt = System.currentTimeMillis();\n        try { if (tts != null) tts.stop(); } catch (Throwable ignored) {}\n        restoreAudioAfterVoice();\n    }\n\n    private void maybeResolveRoadLimit(double lat, double lon, float heading) {''', 'thought methods')

s = rep(s,
'''    private boolean speakRoadLimitVoice(int limitKmh) {\n        if (offlineVoice != null && offlineVoice.playRoadLimit(limitKmh, this::beginVoiceDucking, this::restoreAudioAfterVoice)) return true;''',
'''    private boolean speakRoadLimitVoice(int limitKmh) {\n        interruptThoughtForSafety();\n        if (offlineVoice != null && offlineVoice.playRoadLimit(limitKmh, this::beginVoiceDucking, this::restoreAudioAfterVoice)) return true;''', 'road limit priority')
s = rep(s,
'''    private boolean speakOverspeedVoice(int limitKmh) {\n        if (offlineVoice != null && offlineVoice.playOverspeed(limitKmh, this::beginVoiceDucking, this::restoreAudioAfterVoice)) return true;''',
'''    private boolean speakOverspeedVoice(int limitKmh) {\n        interruptThoughtForSafety();\n        if (offlineVoice != null && offlineVoice.playOverspeed(limitKmh, this::beginVoiceDucking, this::restoreAudioAfterVoice)) return true;''', 'overspeed priority')
s = rep(s,
'''    private void speakHazardVoice(RoadHazard h, double forwardM) {\n        if (offlineVoice != null && h != null &&''',
'''    private void speakHazardVoice(RoadHazard h, double forwardM) {\n        interruptThoughtForSafety();\n        if (offlineVoice != null && h != null &&''', 'hazard priority')
write(p, s)

print('Estrada Play Comunista 1.4.2 road thoughts patch applied.')

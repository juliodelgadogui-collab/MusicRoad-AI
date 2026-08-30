package com.estradaplay.comunista;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

import java.util.Locale;

public final class VoiceDiagnosticsActivity extends ComponentActivity {
    private final int BG=Color.rgb(8,5,7), TEXT=Color.rgb(246,238,224), MUTED=Color.rgb(174,151,146), RED=Color.rgb(190,18,38), GREEN=Color.rgb(68,213,132);
    private EstradaPlayOfflineVoice offline;
    private TextToSpeech tts;
    private boolean ttsReady;
    private TextView status, mode;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        offline = new EstradaPlayOfflineVoice(this);
        tts = new TextToSpeech(this, r -> {
            ttsReady = r == TextToSpeech.SUCCESS;
            if (ttsReady) {
                try { tts.setLanguage(new Locale("pt","BR")); tts.setSpeechRate(.91f); tts.setPitch(.84f); } catch (Throwable ignored) {}
            }
            updateStatus(ttsReady ? "TTS Android pronto." : "TTS Android indisponível neste aparelho.", ttsReady ? GREEN : RED);
        });
        build();
    }

    private void build() {
        ScrollView sv=new ScrollView(this); LinearLayout page=new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20),dp(20),dp(20),dp(30)); page.setBackgroundColor(BG); sv.addView(page); setContentView(sv);
        page.addView(text("DIAGNÓSTICO DE VOZ",27,TEXT,true));
        page.addView(text("Teste no próprio aparelho antes de dirigir. Alertas reais continuam tendo prioridade sobre música e pensamentos.",12,MUTED,false));
        mode=text("MODO ATUAL · "+VoiceSettings.label(this),13,GREEN,true); page.addView(mode);
        status=text("Preparando os motores de voz…",13,MUTED,false); page.addView(status);

        Button embedded=button("TESTAR VOZ EMBARCADA"); page.addView(embedded); embedded.setOnClickListener(v -> testEmbedded());
        Button android=button("TESTAR TTS DO ANDROID"); page.addView(android); android.setOnClickListener(v -> testAndroid());
        page.addView(text("MODO DOS ALERTAS",12,MUTED,true));
        Button auto=button("AUTOMÁTICO · EMBARCADA + RESERVA TTS"); page.addView(auto); auto.setOnClickListener(v -> choose(VoiceSettings.MODE_AUTO));
        Button onlyEmbedded=button("USAR SOMENTE VOZ EMBARCADA"); page.addView(onlyEmbedded); onlyEmbedded.setOnClickListener(v -> choose(VoiceSettings.MODE_EMBEDDED));
        Button onlyAndroid=button("USAR SOMENTE TTS DO ANDROID"); page.addView(onlyAndroid); onlyAndroid.setOnClickListener(v -> choose(VoiceSettings.MODE_ANDROID));
        TextView note=text("Se a central multimídia tiver incompatibilidade com um dos motores, escolha o outro aqui. O padrão recomendado é AUTOMÁTICO.",11,MUTED,false); page.addView(note);
    }

    private void testEmbedded() {
        if (offline == null) return;
        updateStatus("Iniciando voz embarcada…", MUTED);
        boolean ok=offline.playHazard("RADAR",500,60,
                () -> updateStatus("VOZ EMBARCADA TOCANDO · você deve ouvir: radar, distância e limite.",GREEN),
                () -> updateStatus("Teste da voz embarcada concluído.",GREEN));
        if(!ok) updateStatus("Banco de voz embarcado não pôde ser iniciado.",RED);
    }

    private void testAndroid() {
        if(!ttsReady || tts==null) { updateStatus("TTS Android ainda não está pronto.",RED); return; }
        try {
            int r=tts.speak("Teste de voz do Estrada Play. Radar à frente. Limite sessenta quilômetros por hora.",TextToSpeech.QUEUE_FLUSH,null,"voice-test");
            updateStatus(r==TextToSpeech.ERROR?"O Android recusou a fala de teste.":"TTS Android solicitado. Você deve ouvir a frase agora.",r==TextToSpeech.ERROR?RED:GREEN);
        } catch(Throwable e) { updateStatus("Falha ao iniciar o TTS Android.",RED); }
    }

    private void choose(int m) { VoiceSettings.setMode(this,m); if(mode!=null)mode.setText("MODO ATUAL · "+VoiceSettings.label(this)); updateStatus("Modo salvo. Vale para os próximos alertas.",GREEN); }
    private void updateStatus(String v,int c){ runOnUiThread(()->{ if(status!=null){status.setText(v);status.setTextColor(c);} }); }
    private Button button(String v){Button b=new Button(this);b.setText(v);b.setTextColor(TEXT);b.setBackgroundColor(Color.rgb(61,14,23));b.setAllCaps(false);b.setTextSize(13);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(54));lp.setMargins(0,dp(8),0,0);b.setLayoutParams(lp);return b;}
    private TextView text(String v,float z,int c,boolean bold){TextView t=new TextView(this);t.setText(v);t.setTextSize(z);t.setTextColor(c);t.setPadding(0,dp(6),0,dp(6));if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
    @Override protected void onDestroy(){try{if(offline!=null)offline.release();}catch(Throwable ignored){}try{if(tts!=null){tts.stop();tts.shutdown();}}catch(Throwable ignored){}super.onDestroy();}
}

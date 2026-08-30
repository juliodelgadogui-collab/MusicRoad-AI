#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'estrada-play-comunista-app'
JAVA = APP / 'app/src/main/java/com/estradaplay/comunista'


def read(p): return p.read_text(encoding='utf-8')
def write(p, s): p.write_text(s, encoding='utf-8')
def must_replace(s, old, new, name):
    if old not in s:
        raise SystemExit(f'marker missing: {name}')
    return s.replace(old, new, 1)

# Version
p = APP / 'app/build.gradle'
s = read(p)
s = re.sub(r'versionCode\s+150\b', 'versionCode 151', s, count=1)
s = re.sub(r"versionName\s+'1\.5\.0'", "versionName '1.5.1'", s, count=1)
write(p, s)

# Persistent voice mode selector.
write(JAVA / 'VoiceSettings.java', r'''package com.estradaplay.comunista;

import android.content.Context;
import android.content.SharedPreferences;

final class VoiceSettings {
    static final int MODE_AUTO = 0;
    static final int MODE_EMBEDDED = 1;
    static final int MODE_ANDROID = 2;
    private static final String PREFS = "epc_voice_settings_v151";
    private static final String KEY_MODE = "mode";
    private VoiceSettings() {}
    static int mode(Context c) {
        int m = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MODE, MODE_AUTO);
        return m < MODE_AUTO || m > MODE_ANDROID ? MODE_AUTO : m;
    }
    static void setMode(Context c, int m) {
        int safe = m < MODE_AUTO || m > MODE_ANDROID ? MODE_AUTO : m;
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_MODE, safe).apply();
    }
    static String label(Context c) {
        int m = mode(c);
        if (m == MODE_EMBEDDED) return "VOZ EMBARCADA";
        if (m == MODE_ANDROID) return "TTS DO ANDROID";
        return "AUTOMÁTICO";
    }
}
''')

# Diagnostic screen: lets the actual device/head unit test both engines before driving.
write(JAVA / 'VoiceDiagnosticsActivity.java', r'''package com.estradaplay.comunista;

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
''')

# Harden embedded MediaPlayer sequencing with focus-before-start while ducking only after confirmed start.
p = JAVA / 'EstradaPlayOfflineVoice.java'
s = read(p)
s = must_replace(s,
'''import android.media.AudioAttributes;\nimport android.media.MediaPlayer;\nimport android.os.Handler;\nimport android.os.Looper;''',
'''import android.media.AudioAttributes;\nimport android.media.AudioFocusRequest;\nimport android.media.AudioManager;\nimport android.media.MediaPlayer;\nimport android.os.Build;\nimport android.os.Handler;\nimport android.os.Looper;''','offline imports')
s = must_replace(s,
'''    private boolean startNotified;\n    private int generation;\n\n    EstradaPlayOfflineVoice(Context context) {\n        app = context.getApplicationContext();\n    }''',
'''    private boolean startNotified;\n    private int generation;\n    private final AudioManager audioManager;\n    private AudioFocusRequest focusRequest;\n    private boolean focusHeld;\n\n    EstradaPlayOfflineVoice(Context context) {\n        app = context.getApplicationContext();\n        audioManager = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);\n    }''','offline fields')
s = must_replace(s,
'''        if (id == null) {\n            Runnable done = finished;\n            started = null;\n            finished = null;\n            startNotified = false;\n            if (done != null) done.run();\n            return;\n        }''',
'''        if (id == null) {\n            Runnable done = finished;\n            started = null;\n            finished = null;\n            startNotified = false;\n            abandonLocalFocus();\n            if (done != null) done.run();\n            return;\n        }''','offline finish')
s = must_replace(s,
'''            mp.start();\n            if (!startNotified) {\n                startNotified = true;\n                Runnable begin = started;\n                started = null;\n                if (begin != null) begin.run();\n            }''',
'''            if (!startNotified) requestLocalFocus();\n            mp.setVolume(1f, 1f);\n            mp.start();\n            boolean audibleStart = false;\n            try { audibleStart = mp.isPlaying(); } catch (Throwable ignored) {}\n            if (!audibleStart) { failSequence(token); return; }\n            if (!startNotified) {\n                startNotified = true;\n                Runnable begin = started;\n                started = null;\n                if (begin != null) begin.run();\n            }''','offline start')
s = must_replace(s,
'''        queue.clear();\n        stopPlayerOnly();\n        Runnable done = finished;''',
'''        queue.clear();\n        stopPlayerOnly();\n        abandonLocalFocus();\n        Runnable done = finished;''','offline fail')
s = must_replace(s,
'''        stopInternal();\n        if (done != null) done.run();''',
'''        stopInternal();\n        abandonLocalFocus();\n        if (done != null) done.run();''','offline cancel')
insert = r'''
    private void requestLocalFocus() {
        if (audioManager == null || focusHeld) return;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                if (focusRequest == null) {
                    AudioAttributes attrs = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
                    focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                            .setAudioAttributes(attrs).setAcceptsDelayedFocusGain(false).setWillPauseWhenDucked(false).build();
                }
                focusHeld = audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_FAILED;
            } else {
                focusHeld = audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) != AudioManager.AUDIOFOCUS_REQUEST_FAILED;
            }
        } catch (Throwable ignored) { focusHeld = false; }
    }

    private void abandonLocalFocus() {
        if (audioManager == null || !focusHeld) return;
        try {
            if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) audioManager.abandonAudioFocusRequest(focusRequest);
            else audioManager.abandonAudioFocus(null);
        } catch (Throwable ignored) {}
        focusHeld = false;
    }

'''
s = must_replace(s, '    private static void safeRelease(MediaPlayer p) {', insert + '    private static void safeRelease(MediaPlayer p) {', 'offline focus methods')
write(p, s)

# Session-tokenized audio coordination in RoadSafetyService.
p = JAVA / 'RoadSafetyService.java'
s = read(p)
s = must_replace(s,
'''    private long lastUpcomingAt; private String upcomingCache="";\n\n    private final Runnable restoreAudioFallback = this::restoreAudioAfterVoice;''',
'''    private long lastUpcomingAt; private String upcomingCache="";\n    // VOICE_RELIABILITY_V151: stale callbacks from an older utterance must never alter a newer one.\n    private int voiceSessionCounter;\n    private int activeVoiceToken;\n    private String activeVoiceKind="";\n    private long voiceBusyUntil;\n    private Runnable voiceRestoreWatchdog;''','voice fields')
old_listener = '''                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {\n                        @Override public void onStart(String utteranceId) { main.post(RoadSafetyService.this::beginVoiceDucking); }\n                        @Override public void onDone(String utteranceId) { main.post(RoadSafetyService.this::restoreAudioAfterVoice); }\n                        @Override public void onError(String utteranceId) { main.post(RoadSafetyService.this::restoreAudioAfterVoice); }\n                        @Override public void onStop(String utteranceId, boolean interrupted) { main.post(RoadSafetyService.this::restoreAudioAfterVoice); }\n                    });'''
new_listener = '''                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {\n                        @Override public void onStart(String utteranceId) { int t=voiceTokenFromId(utteranceId); main.post(() -> { if(t==activeVoiceToken){ requestVoiceFocus(); beginVoiceDucking(t); } }); }\n                        @Override public void onDone(String utteranceId) { int t=voiceTokenFromId(utteranceId); main.post(() -> restoreAudioAfterVoice(t)); }\n                        @Override public void onError(String utteranceId) { int t=voiceTokenFromId(utteranceId); main.post(() -> restoreAudioAfterVoice(t)); }\n                        @Override public void onStop(String utteranceId, boolean interrupted) { int t=voiceTokenFromId(utteranceId); main.post(() -> restoreAudioAfterVoice(t)); }\n                    });'''
s = must_replace(s, old_listener, new_listener, 'tts listener')
s = must_replace(s,
'''        if (mode == RoadThoughts.MODE_OFF || loc == null || speedKmh < 5.0) return;''',
'''        if (mode == RoadThoughts.MODE_OFF || loc == null || speedKmh < 5.0 || voiceBusy()) return;''','thought busy gate')
s = must_replace(s,
'''        if (mode == RoadThoughts.MODE_SCREEN_VOICE && ttsReady) speak(RoadThoughts.spoken(e));''',
'''        if (mode == RoadThoughts.MODE_SCREEN_VOICE && ttsReady) speakThought(RoadThoughts.spoken(e));''','thought speak')
s = must_replace(s,
'''    private void interruptThoughtForSafety() {\n        lastSafetyVoiceAt = System.currentTimeMillis();\n        try { if (tts != null) tts.stop(); } catch (Throwable ignored) {}\n        restoreAudioAfterVoice();\n    }''',
'''    private void interruptThoughtForSafety() {\n        lastSafetyVoiceAt = System.currentTimeMillis();\n    }''','thought interrupt')
pattern = re.compile(r'''    private void beginVoiceDucking\(\) \{.*?\n    private String voice\(RoadHazard h, double forward\) \{''', re.S)
replacement = r'''    private boolean voiceBusy() {
        return activeVoiceToken > 0 && System.currentTimeMillis() < voiceBusyUntil;
    }

    private int openVoiceSession(String kind, boolean interrupt) {
        if (!interrupt && voiceBusy()) return 0;
        int token = ++voiceSessionCounter;
        activeVoiceToken = token;
        activeVoiceKind = kind == null ? "" : kind;
        voiceBusyUntil = System.currentTimeMillis() + 15_000L;
        if (interrupt) {
            try { if (offlineVoice != null) offlineVoice.stop(); } catch (Throwable ignored) {}
            try { if (tts != null) tts.stop(); } catch (Throwable ignored) {}
        }
        return token;
    }

    private void beginVoiceDucking(int token) {
        if (token <= 0 || token != activeVoiceToken) return;
        duckOwnPlayer(true);
        voiceBusyUntil = System.currentTimeMillis() + 15_000L;
        if (voiceRestoreWatchdog != null) main.removeCallbacks(voiceRestoreWatchdog);
        voiceRestoreWatchdog = () -> restoreAudioAfterVoice(token);
        main.postDelayed(voiceRestoreWatchdog, 15_000L);
    }

    private void restoreAudioAfterVoice(int token) {
        if (token <= 0 || token != activeVoiceToken) return;
        if (voiceRestoreWatchdog != null) { main.removeCallbacks(voiceRestoreWatchdog); voiceRestoreWatchdog = null; }
        activeVoiceToken = 0;
        activeVoiceKind = "";
        voiceBusyUntil = 0L;
        forceRestoreAudio();
    }

    private void restoreAudioAfterVoice() {
        int token = activeVoiceToken;
        if (token > 0) restoreAudioAfterVoice(token); else forceRestoreAudio();
    }

    private void forceRestoreAudio() {
        duckOwnPlayer(false);
        if (audioManager == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26 && alertFocusRequest != null) audioManager.abandonAudioFocusRequest(alertFocusRequest);
            else audioManager.abandonAudioFocus(null);
        } catch (Throwable ignored) {}
    }

    private int voiceTokenFromId(String id) {
        if (id == null || !id.startsWith("ep-voice-")) return -1;
        try { return Integer.parseInt(id.substring("ep-voice-".length())); }
        catch (Throwable ignored) { return -1; }
    }

    private boolean speakRoadLimitVoice(int limitKmh) {
        if (voiceBusy() && !"thought".equals(activeVoiceKind)) return false;
        interruptThoughtForSafety();
        int token = openVoiceSession("limit", true);
        int mode = VoiceSettings.mode(this);
        if (mode != VoiceSettings.MODE_ANDROID && offlineVoice != null &&
                offlineVoice.playRoadLimit(limitKmh, () -> beginVoiceDucking(token), () -> restoreAudioAfterVoice(token))) return true;
        if (mode != VoiceSettings.MODE_EMBEDDED && ttsReady && copilot != null && speakWithToken(copilot.roadLimit(limitKmh), token)) return true;
        restoreAudioAfterVoice(token);
        return false;
    }

    private boolean speakOverspeedVoice(int limitKmh) {
        if (voiceBusy() && !"thought".equals(activeVoiceKind)) return false;
        interruptThoughtForSafety();
        int token = openVoiceSession("overspeed", true);
        int mode = VoiceSettings.mode(this);
        if (mode != VoiceSettings.MODE_ANDROID && offlineVoice != null &&
                offlineVoice.playOverspeed(limitKmh, () -> beginVoiceDucking(token), () -> restoreAudioAfterVoice(token))) return true;
        if (mode != VoiceSettings.MODE_EMBEDDED && ttsReady && copilot != null && speakWithToken(copilot.overspeed(limitKmh), token)) return true;
        restoreAudioAfterVoice(token);
        return false;
    }

    private void speakHazardVoice(RoadHazard h, double forwardM) {
        interruptThoughtForSafety();
        int token = openVoiceSession("hazard", true);
        int mode = VoiceSettings.mode(this);
        if (mode != VoiceSettings.MODE_ANDROID && offlineVoice != null && h != null &&
                offlineVoice.playHazard(h.type, forwardM, h.speed, () -> beginVoiceDucking(token), () -> restoreAudioAfterVoice(token))) return;
        if (mode != VoiceSettings.MODE_EMBEDDED && ttsReady && copilot != null && h != null && speakWithToken(copilot.hazard(h.type, forwardM, h.speed), token)) return;
        if (mode != VoiceSettings.MODE_EMBEDDED && speakWithToken(voice(h, forwardM), token)) return;
        restoreAudioAfterVoice(token);
    }

    private boolean speakThought(String text) {
        if (!ttsReady || voiceBusy()) return false;
        int token = openVoiceSession("thought", false);
        if (token <= 0) return false;
        if (speakWithToken(text, token)) return true;
        restoreAudioAfterVoice(token);
        return false;
    }

    private boolean speakWithToken(String text, int token) {
        if (!ttsReady || tts == null || token <= 0 || token != activeVoiceToken || text == null || text.trim().isEmpty()) return false;
        try {
            int result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ep-voice-" + token);
            if (result == TextToSpeech.ERROR) return false;
            // No duck here. Android's onStart is the proof that audible playback actually began.
            return true;
        } catch (Throwable ignored) { return false; }
    }

    private String voice(RoadHazard h, double forward) {'''
if not pattern.search(s):
    raise SystemExit('voice method block not found')
s = pattern.sub(replacement, s, count=1)
# Existing restore method lower in file would now duplicate ours: replace it with just focus request or remove exact block.
dup = re.compile(r'''\n    private void restoreAudioAfterVoice\(\) \{\n        main\.removeCallbacks\(restoreAudioFallback\);.*?\n    \}\n''', re.S)
# It may already have been consumed by the big replacement depending on source ordering; only remove if still present.
s = dup.sub('\n', s, count=1)
# Ensure onDestroy invalidates stale callbacks before shutting engines down.
s = must_replace(s,
'''    @Override public void onDestroy() {\n        main.removeCallbacks(staleSpeedWatchdog);\n        restoreAudioAfterVoice();''',
'''    @Override public void onDestroy() {\n        main.removeCallbacks(staleSpeedWatchdog);\n        activeVoiceToken = ++voiceSessionCounter;\n        if (voiceRestoreWatchdog != null) main.removeCallbacks(voiceRestoreWatchdog);\n        forceRestoreAudio();''','service destroy')
write(p, s)

# Add diagnostics entry to tools screen.
p = JAVA / 'DriveToolsActivity.java'
s = read(p)
s = must_replace(s, 'button("COMANDO DE VOZ",VoiceCommandActivity.class);', 'button("COMANDO DE VOZ",VoiceCommandActivity.class);button("DIAGNÓSTICO DE VOZ",VoiceDiagnosticsActivity.class);', 'drive tools voice')
write(p, s)

# Manifest activity.
p = APP / 'app/src/main/AndroidManifest.xml'
s = read(p)
marker = '''        <activity\n            android:name=".VoiceCommandActivity"\n            android:exported="false"\n            android:screenOrientation="${appOrientation}" />'''
add = marker + '''\n\n        <activity\n            android:name=".VoiceDiagnosticsActivity"\n            android:exported="false"\n            android:screenOrientation="${appOrientation}" />'''
s = must_replace(s, marker, add, 'manifest voice diagnostics')
write(p, s)

print('Estrada Play Comunista 1.5.1 voice reliability patch applied')

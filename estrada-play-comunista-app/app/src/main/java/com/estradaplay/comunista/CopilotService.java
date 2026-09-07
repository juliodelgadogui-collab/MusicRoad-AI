package com.estradaplay.comunista;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * COPILOT_BACKGROUND_V1
 * Foreground microphone service with a strictly on-device wake-word gate. No continuous audio is
 * sent to Estrada Play or an AI service. Only after the local device hears "Copiloto" do we start
 * normal speech-to-text for the driver's command.
 */
public final class CopilotService extends Service {
    static final String ACTION_START = "com.estradaplay.comunista.copilot.START";
    static final String ACTION_STOP = "com.estradaplay.comunista.copilot.STOP";
    static final String ACTION_LISTEN_NOW = "com.estradaplay.comunista.copilot.LISTEN_NOW";
    static final String ACTION_QUERY = "com.estradaplay.comunista.copilot.QUERY";
    static final String ACTION_STATE = "com.estradaplay.comunista.copilot.STATE";
    static final String ACTION_OPEN_SCREEN = "com.estradaplay.comunista.copilot.OPEN_SCREEN";

    static final String STATE_OFF = "off";
    static final String STATE_WAITING = "waiting";
    static final String STATE_LISTENING = "listening";
    static final String STATE_PROCESSING = "processing";
    static final String STATE_SPEAKING = "speaking";

    private static final String CHANNEL = "epc_copilot";
    private static final int NOTIFICATION_ID = 7350;
    private static final long WAKE_RETRY_MS = 650L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private SpeechRecognizer wakeRecognizer;
    private SpeechRecognizer commandRecognizer;
    private TextToSpeech tts;
    private boolean ttsReady;
    private boolean wakeListening;
    private boolean commandListening;
    private boolean speaking;
    private boolean safetyAudioBusy;
    private boolean pttBusy;
    private boolean registered;
    private boolean oneShot;
    private int utteranceCounter;
    private Runnable wakeRetry;

    private int lastLimit;
    private String lastHazard = "";
    private String upcoming = "";
    private String core = "";
    private double lastDistance;
    private double lastLat = Double.NaN;
    private double lastLon = Double.NaN;
    private double lastSpeed;
    private float lastHeading = Float.NaN;

    static void requestStart(Context context) {
        Intent intent = new Intent(context, CopilotService.class).setAction(ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (Throwable ignored) {}
    }

    static void requestStop(Context context) {
        Intent intent = new Intent(context, CopilotService.class).setAction(ACTION_STOP);
        try { context.startService(intent); } catch (Throwable ignored) {}
    }

    static void requestListenNow(Context context) {
        Intent intent = new Intent(context, CopilotService.class).setAction(ACTION_LISTEN_NOW);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (Throwable ignored) {}
    }

    static void requestQuery(Context context) {
        Intent intent = new Intent(context, CopilotService.class).setAction(ACTION_QUERY);
        try { context.startService(intent); } catch (Throwable ignored) {}
    }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, notification("Copiloto", "Preparando serviço de voz"));
        initTts();
        registerContextReceivers();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            CopilotSettings.setEnabled(this, false);
            setState(STATE_OFF, "Copiloto desligado");
            stopSelfSafely();
            return START_NOT_STICKY;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setState(STATE_OFF, "Autorize o microfone para usar o Copiloto");
            stopSelfSafely();
            return START_NOT_STICKY;
        }
        if (ACTION_QUERY.equals(action)) {
            publishCurrentState();
            return START_STICKY;
        }
        if (ACTION_LISTEN_NOW.equals(action)) {
            oneShot = !CopilotSettings.enabled(this);
            beginCommandListening();
            return START_STICKY;
        }
        CopilotSettings.setEnabled(this, true);
        oneShot = false;
        enterWaiting("Diga “" + CopilotSettings.wakeWord(this) + "”");
        scheduleWake(180L);
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void initTts() {
        try {
            tts = new TextToSpeech(this, result -> {
                if (result != TextToSpeech.SUCCESS || tts == null) return;
                ttsReady = true;
                try {
                    tts.setLanguage(new Locale("pt", "BR"));
                    tts.setSpeechRate(.94f);
                    tts.setPitch(.92f);
                    if (Build.VERSION.SDK_INT >= 21) {
                        AudioAttributes attrs = new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build();
                        tts.setAudioAttributes(attrs);
                    }
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override public void onStart(String utteranceId) {}
                        @Override public void onDone(String utteranceId) { main.post(CopilotService.this::finishSpeaking); }
                        @Override public void onError(String utteranceId) { main.post(CopilotService.this::finishSpeaking); }
                        @Override public void onStop(String utteranceId, boolean interrupted) { main.post(CopilotService.this::finishSpeaking); }
                    });
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    private void registerContextReceivers() {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                String action = intent.getAction();
                if (RoadSafetyService.ACTION_STATE.equals(action)) {
                    lastLimit = intent.getIntExtra("road_limit_kmh", lastLimit);
                    lastLat = intent.getDoubleExtra("lat", lastLat);
                    lastLon = intent.getDoubleExtra("lon", lastLon);
                    lastSpeed = intent.getDoubleExtra("speed_kmh", lastSpeed);
                    lastHeading = intent.getFloatExtra("heading", lastHeading);
                    String hazard = intent.getStringExtra("hazard_label");
                    if (hazard != null && !hazard.trim().isEmpty()) {
                        lastHazard = hazard.trim();
                        lastDistance = intent.getDoubleExtra("distance_m", lastDistance);
                    }
                    String next = intent.getStringExtra("upcoming_text");
                    if (next != null) upcoming = next;
                    String status = intent.getStringExtra("core_states_status");
                    if (status != null) core = status;
                    return;
                }
                if (RoadSafetyService.ACTION_SAFETY_AUDIO.equals(action)) {
                    boolean active = intent.getBooleanExtra("active", false);
                    if (active != safetyAudioBusy) {
                        safetyAudioBusy = active;
                        if (active) suspendForHigherPriorityAudio();
                        else resumeAfterHigherPriorityAudio();
                    }
                    return;
                }
                if (RoadRadioService.ACTION_STATE.equals(action)) {
                    boolean active = intent.getBooleanExtra("ptt", false)
                            || intent.getBooleanExtra("transmitting", false)
                            || intent.getBooleanExtra("talking", false);
                    if (active != pttBusy) {
                        pttBusy = active;
                        if (active) suspendForHigherPriorityAudio();
                        else resumeAfterHigherPriorityAudio();
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(RoadSafetyService.ACTION_STATE);
        filter.addAction(RoadSafetyService.ACTION_SAFETY_AUDIO);
        filter.addAction(RoadRadioService.ACTION_STATE);
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(receiver, filter);
            contextReceiver = receiver;
            registered = true;
        } catch (Throwable ignored) {}
    }

    private BroadcastReceiver contextReceiver;

    private void suspendForHigherPriorityAudio() {
        cancelRecognition();
        if (speaking && tts != null) {
            try { tts.stop(); } catch (Throwable ignored) {}
        }
        speaking = false;
        enterWaiting(safetyAudioBusy ? "Alerta de estrada em prioridade" : "PTT em prioridade");
    }

    private void resumeAfterHigherPriorityAudio() {
        if (safetyAudioBusy || pttBusy) return;
        duckPlayer(false);
        if (CopilotSettings.enabled(this)) {
            enterWaiting("Diga “" + CopilotSettings.wakeWord(this) + "”");
            scheduleWake(500L);
        } else if (oneShot) {
            stopSelfSafely();
        }
    }

    private void scheduleWake(long delayMs) {
        if (wakeRetry != null) main.removeCallbacks(wakeRetry);
        wakeRetry = () -> startWakeListening();
        main.postDelayed(wakeRetry, Math.max(100L, delayMs));
    }

    private void startWakeListening() {
        if (!CopilotSettings.enabled(this) || wakeListening || commandListening || speaking || safetyAudioBusy || pttBusy) return;
        if (!CopilotSettings.localWakeWordAvailable(this)) {
            enterWaiting("Wake word local indisponível · toque em FALAR AGORA");
            updateNotification("Copiloto ativo", "Wake word local indisponível · use FALAR AGORA");
            return;
        }
        try {
            if (wakeRecognizer == null) {
                if (Build.VERSION.SDK_INT < 31) return;
                wakeRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
                wakeRecognizer.setRecognitionListener(wakeListener);
            }
            wakeListening = true;
            wakeRecognizer.startListening(recognizerIntent(true));
            enterWaiting("Diga “" + CopilotSettings.wakeWord(this) + "”");
        } catch (Throwable ignored) {
            wakeListening = false;
            scheduleWake(1500L);
        }
    }

    private final RecognitionListener wakeListener = new EmptyRecognitionListener() {
        @Override public void onPartialResults(Bundle bundle) {
            inspectWakeResults(bundle, false);
        }
        @Override public void onResults(Bundle bundle) {
            wakeListening = false;
            if (!inspectWakeResults(bundle, true)) scheduleWake(WAKE_RETRY_MS);
        }
        @Override public void onError(int error) {
            wakeListening = false;
            if (!commandListening && CopilotSettings.enabled(CopilotService.this)) {
                long delay = error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 1800L : WAKE_RETRY_MS;
                scheduleWake(delay);
            }
        }
    };

    private boolean inspectWakeResults(Bundle bundle, boolean finalResult) {
        ArrayList<String> results = bundle == null ? null : bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (results == null || results.isEmpty()) return false;
        String wake = normalize(CopilotSettings.wakeWord(this));
        for (String raw : results) {
            String normalized = normalize(raw);
            int index = normalized.indexOf(wake);
            if (index < 0) continue;
            String tail = normalized.substring(Math.min(normalized.length(), index + wake.length())).trim();
            wakeListening = false;
            try { if (wakeRecognizer != null) wakeRecognizer.cancel(); } catch (Throwable ignored) {}
            if (tail.length() >= 3) {
                setState(STATE_PROCESSING, tail);
                main.postDelayed(() -> handleCommand(tail), 80L);
            } else {
                main.postDelayed(this::beginCommandListening, finalResult ? 100L : 180L);
            }
            return true;
        }
        return false;
    }

    private void beginCommandListening() {
        if (commandListening || speaking || safetyAudioBusy || pttBusy) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        try {
            if (wakeRecognizer != null) wakeRecognizer.cancel();
            wakeListening = false;
            if (commandRecognizer == null) {
                if (Build.VERSION.SDK_INT >= 31 && CopilotSettings.localWakeWordAvailable(this))
                    commandRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
                else commandRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
                commandRecognizer.setRecognitionListener(commandListener);
            }
            commandListening = true;
            setState(STATE_LISTENING, "Copiloto ouvindo");
            duckPlayer(true);
            commandRecognizer.startListening(recognizerIntent(false));
        } catch (Throwable ignored) {
            commandListening = false;
            say("Não consegui iniciar o reconhecimento de voz agora.");
        }
    }

    private final RecognitionListener commandListener = new EmptyRecognitionListener() {
        @Override public void onResults(Bundle bundle) {
            commandListening = false;
            ArrayList<String> results = bundle == null ? null : bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            String query = results == null || results.isEmpty() ? "" : normalize(results.get(0));
            if (query.isEmpty()) say("Não entendi. Tente novamente.");
            else {
                setState(STATE_PROCESSING, query);
                handleCommand(query);
            }
        }
        @Override public void onError(int error) {
            commandListening = false;
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                say("Não entendi. Tente novamente.");
            else if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
                say("O microfone está ocupado agora.");
            else say("Não consegui ouvir o comando agora.");
        }
    };

    private Intent recognizerIntent(boolean wakeOnly) {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR");
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, wakeOnly
                ? RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH
                : RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, wakeOnly ? 5 : 3);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, wakeOnly);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        if (!wakeOnly) {
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1100L);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L);
        }
        return intent;
    }

    private void handleCommand(String query) {
        String q = normalize(query);
        if (q.contains("quanto falta") || q.contains("falta para chegar") || q.contains("tempo para chegar")
                || q.contains("hora de chegada") || q.contains("previsao de chegada") || q.equals("chegada")) {
            answerRemainingTrip(); return;
        }
        if (q.contains("velocidade") && !q.contains("limite")) {
            say("Você está a aproximadamente " + Math.round(lastSpeed) + " quilômetros por hora."); return;
        }
        if (q.contains("limite")) {
            say(lastLimit > 0 ? "O limite registrado aqui é " + lastLimit + " quilômetros por hora."
                    : "Ainda não tenho limite confirmado para esta via."); return;
        }
        if (q.contains("o que vem") || q.contains("pela frente") || q.contains("proximo")) {
            say(upcoming.isEmpty() ? "Não tenho perigo próximo dentro da faixa atual."
                    : "À frente: " + upcoming.replace("→", "depois")); return;
        }
        if (q.contains("radar")) {
            say(normalize(lastHazard).contains("radar")
                    ? "Há radar a aproximadamente " + Math.round(lastDistance) + " metros."
                    : "Não há radar dentro da janela de alerta atual."); return;
        }
        if (q.contains("autonomia") || q.contains("combustivel")) {
            VehicleProfileStore.Profile vehicle = VehicleProfileStore.active(this);
            say("Autonomia estimada de " + Math.round(vehicle.autonomyKm()) + " quilômetros, com "
                    + Math.round(vehicle.fuelPercent) + " por cento de combustível informado."); return;
        }
        if (q.contains("offline")) {
            say(core.isEmpty() ? "A proteção offline continua ativa com os dados já salvos neste aparelho."
                    : "Base offline: " + core.replace("✓", "pronto")); return;
        }
        if (q.contains("vai chover") || q.contains("chuva") || q.contains("clima") || q.contains("previsao do tempo")) {
            say(RoadWeatherMonitor.spokenStatus(this)); return;
        }
        if (q.contains("salvar isso") || q.contains("salva isso") || q.contains("salvar acontecimento")) {
            IncidentStore.save(this, lastLat, lastLon, lastSpeed, lastHazard, upcoming, "Salvo por voz");
            say("Acontecimento salvo neste aparelho."); return;
        }
        if ((q.contains("proxima") || q.contains("proximo")) && q.contains("musica")) {
            player(PlayerService.ACTION_NEXT); say("Próxima música."); return;
        }
        if ((q.contains("anterior") || q.contains("volta")) && q.contains("musica")) {
            player(PlayerService.ACTION_PREVIOUS); say("Voltando uma música."); return;
        }
        if ((q.contains("pausa") || q.contains("pausar") || q.contains("continuar") || q.contains("retomar")) && q.contains("musica")) {
            player(PlayerService.ACTION_TOGGLE); say("Controle da música acionado."); return;
        }
        if (q.contains("abrir mapa") || q.contains("navegacao") || q.equals("mapa")) { open("map", "Abrindo o mapa."); return; }
        if (q.contains("central")) { open("central", "Abrindo a Central."); return; }
        if (q.contains("planej")) { open("planner", "Abrindo o planejamento de viagem."); return; }
        if (q.contains("favorit")) { open("favorites", "Abrindo favoritos."); return; }
        if (q.contains("comboio")) { open("convoy", "Abrindo o comboio."); return; }
        if (q.contains("estrada viva") || q.contains("alerta comunit")) { open("estrada_viva", "Abrindo Estrada Viva."); return; }
        if (q.contains("manutencao") || q.contains("revisao")) { open("maintenance", "Abrindo manutenção."); return; }
        if (q.contains("preco do combustivel") || q.contains("combustivel barato")) { open("fuel", "Abrindo preços de combustível."); return; }
        if (q.contains("obd") || q.contains("motor") && q.contains("temperatura")) { open("obd", "Abrindo OBD dois."); return; }
        if (q.contains("sos") || q.contains("socorro") || q.contains("emergencia")) { open("sos", "Abrindo emergência."); return; }
        if (q.contains("radio")) { open("radio", "Abrindo o rádio da rodovia."); return; }
        if (q.contains("camera")) { open("camera", "Abrindo a dashcam."); return; }
        if (q.contains("posto") || q.contains("hospital") || q.contains("oficina") || q.contains("restaurante")) { open("services", "Abrindo serviços próximos."); return; }
        if (q.contains("historico")) { open("history", "Abrindo histórico de viagens."); return; }
        if (q.contains("report")) { open("report", "Abrindo reporte da estrada."); return; }
        if (q.contains("destino") || q.contains("endereco")) { open("destination", "Abrindo destinos."); return; }
        if (q.contains("musica") || q.contains("player")) { open("music", "Abrindo o player."); return; }
        if (q.contains("clima")) { open("weather", "Abrindo o clima."); return; }
        if (q.contains("hud")) { say("A projeção HUD não faz parte desta versão."); return; }
        if ((q.contains("desligar") || q.contains("desativar")) && q.contains("copiloto")) {
            CopilotSettings.setEnabled(this, false);
            say("Copiloto desativado."); return;
        }
        say("Comando não reconhecido ainda.");
    }

    private void answerRemainingTrip() {
        DestinationStore.Destination destination = DestinationStore.read(this);
        if (destination == null) {
            say("Não há uma rota ativa agora.");
            return;
        }
        final double lat = lastLat, lon = lastLon;
        final float heading = lastHeading;
        if (!Double.isFinite(lat) || !Double.isFinite(lon)) {
            say("A rota está ativa, mas ainda estou aguardando uma posição de GPS confiável.");
            return;
        }
        setState(STATE_PROCESSING, "Calculando o restante da viagem");
        io.execute(() -> {
            try {
                RouteEngine.Route route = RouteOfflineCache.load(this, lat, lon, destination.lat, destination.lon);
                if (route == null) route = RouteEngine.fetch(this, lat, lon, destination.lat, destination.lon);
                RouteEngine.Match match = route.match(lat, lon, Float.isFinite(heading) ? heading : Double.NaN, -1, 0);
                double distance = match.valid ? route.remainingDistance(match.alongM) : route.distanceM;
                double duration = match.valid ? route.remainingDuration(match.alongM) : route.durationS;
                final String response = remainingSpeech(distance, duration);
                main.post(() -> say(response));
            } catch (Throwable ignored) {
                main.post(() -> say("Não consegui calcular o restante da rota agora, mas a navegação continua ativa."));
            }
        });
    }

    private String remainingSpeech(double distanceM, double durationS) {
        long km = Math.max(0, Math.round(distanceM / 1000.0));
        long minutes = Math.max(1, Math.round(durationS / 60.0));
        long hours = minutes / 60;
        long rest = minutes % 60;
        String time;
        if (hours > 0 && rest > 0) time = hours + (hours == 1 ? " hora e " : " horas e ") + rest + " minutos";
        else if (hours > 0) time = hours + (hours == 1 ? " hora" : " horas");
        else time = minutes + " minutos";
        long arrivalMs = System.currentTimeMillis() + Math.max(0L, Math.round(durationS * 1000.0));
        String arrival = new SimpleDateFormat("HH:mm", new Locale("pt", "BR")).format(new Date(arrivalMs));
        return "Faltam aproximadamente " + km + " quilômetros e " + time + ". Chegada prevista por volta de " + arrival + ".";
    }

    private void open(String screen, String voice) {
        Intent intent = new Intent(ACTION_OPEN_SCREEN).setPackage(getPackageName());
        intent.putExtra("screen", screen);
        try { sendBroadcast(intent); } catch (Throwable ignored) {}
        say(voice);
    }

    private void player(String action) {
        try { startService(new Intent(this, PlayerService.class).setAction(action)); } catch (Throwable ignored) {}
    }

    private void say(String text) {
        String safe = text == null ? "" : text.trim();
        if (safe.isEmpty()) {
            finishSpeaking();
            return;
        }
        cancelRecognition();
        speaking = true;
        duckPlayer(true);
        setState(STATE_SPEAKING, safe);
        if (!ttsReady || tts == null) {
            main.postDelayed(this::finishSpeaking, 1600L);
            return;
        }
        try {
            int result = tts.speak(safe, TextToSpeech.QUEUE_FLUSH, null, "epc-copilot-" + (++utteranceCounter));
            if (result == TextToSpeech.ERROR) main.postDelayed(this::finishSpeaking, 200L);
        } catch (Throwable ignored) {
            main.postDelayed(this::finishSpeaking, 200L);
        }
    }

    private void finishSpeaking() {
        if (!speaking) return;
        speaking = false;
        if (!safetyAudioBusy && !pttBusy) duckPlayer(false);
        if (!CopilotSettings.enabled(this)) {
            if (oneShot || !CopilotSettings.enabled(this)) stopSelfSafely();
            return;
        }
        enterWaiting("Diga “" + CopilotSettings.wakeWord(this) + "”");
        scheduleWake(450L);
    }

    private void cancelRecognition() {
        wakeListening = false;
        commandListening = false;
        try { if (wakeRecognizer != null) wakeRecognizer.cancel(); } catch (Throwable ignored) {}
        try { if (commandRecognizer != null) commandRecognizer.cancel(); } catch (Throwable ignored) {}
    }

    private void duckPlayer(boolean duck) {
        try {
            Intent intent = new Intent(this, PlayerService.class)
                    .setAction(duck ? PlayerService.ACTION_DUCK : PlayerService.ACTION_UNDUCK);
            startService(intent);
        } catch (Throwable ignored) {}
    }

    private void enterWaiting(String text) {
        setState(STATE_WAITING, text);
        updateNotification("Copiloto ativo", text);
    }

    private String currentState = STATE_OFF;
    private String currentText = "";

    private void setState(String state, String text) {
        currentState = state == null ? STATE_OFF : state;
        currentText = text == null ? "" : text;
        Intent intent = new Intent(ACTION_STATE).setPackage(getPackageName());
        intent.putExtra("state", currentState);
        intent.putExtra("text", currentText);
        intent.putExtra("enabled", CopilotSettings.enabled(this));
        intent.putExtra("wake_word", CopilotSettings.wakeWord(this));
        intent.putExtra("local_wake", CopilotSettings.localWakeWordAvailable(this));
        try { sendBroadcast(intent); } catch (Throwable ignored) {}
        if (STATE_LISTENING.equals(currentState)) updateNotification("Copiloto ouvindo", "Fale o comando agora");
        else if (STATE_PROCESSING.equals(currentState)) updateNotification("Copiloto", "Processando comando");
        else if (STATE_SPEAKING.equals(currentState)) updateNotification("Copiloto respondendo", currentText);
    }

    private void publishCurrentState() {
        setState(currentState, currentText);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Copiloto", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Mantém o Copiloto disponível durante a viagem");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private Notification notification(String title, String text) {
        Intent open = new Intent(this, VoiceCommandActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 7350, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(content);
        return builder.build();
    }

    private void updateNotification(String title, String text) {
        try {
            NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) manager.notify(NOTIFICATION_ID, notification(title, text));
        } catch (Throwable ignored) {}
    }

    private void stopSelfSafely() {
        cancelRecognition();
        duckPlayer(false);
        try { stopForeground(true); } catch (Throwable ignored) {}
        stopSelf();
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String raw = value.toLowerCase(new Locale("pt", "BR")).trim();
        try {
            raw = Normalizer.normalize(raw, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        } catch (Throwable ignored) {}
        return raw.replaceAll("[^a-z0-9 ]+", " ").replaceAll("\\s+", " ").trim();
    }

    @Override public void onDestroy() {
        if (wakeRetry != null) main.removeCallbacks(wakeRetry);
        cancelRecognition();
        try { if (wakeRecognizer != null) wakeRecognizer.destroy(); } catch (Throwable ignored) {}
        try { if (commandRecognizer != null) commandRecognizer.destroy(); } catch (Throwable ignored) {}
        try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Throwable ignored) {}
        try { if (registered && contextReceiver != null) unregisterReceiver(contextReceiver); } catch (Throwable ignored) {}
        registered = false;
        io.shutdownNow();
        super.onDestroy();
    }

    private abstract static class EmptyRecognitionListener implements RecognitionListener {
        @Override public void onReadyForSpeech(Bundle params) {}
        @Override public void onBeginningOfSpeech() {}
        @Override public void onRmsChanged(float rmsdB) {}
        @Override public void onBufferReceived(byte[] buffer) {}
        @Override public void onEndOfSpeech() {}
        @Override public void onError(int error) {}
        @Override public void onResults(Bundle results) {}
        @Override public void onPartialResults(Bundle partialResults) {}
        @Override public void onEvent(int eventType, Bundle params) {}
    }
}

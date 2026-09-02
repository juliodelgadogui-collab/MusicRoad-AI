package com.estradaplay.comunista;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// OFFLINE_VOICE_V204: deterministic EstradaPlay voice bank embedded in the APK.
// Neural synthesis happens only in CI; the head unit plays small local WAV clips.
final class EstradaPlayOfflineVoice {
    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Integer> queue = new ArrayDeque<>();
    private MediaPlayer player;
    private Runnable started;
    private Runnable finished;
    private boolean startNotified;
    private int generation;
    private final AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private boolean focusHeld;
    // VOICE_FULL_ALERT_V192: automotive ROMs can swallow the first short clip while
    // audio focus/ducking is still changing. Prime focus before speech and leave a
    // small gap between clips so the whole sentence is audible, not only distance.
    private static final long FIRST_CLIP_PREROLL_MS = 240L;
    private static final long BETWEEN_CLIPS_MS = 65L;

    EstradaPlayOfflineVoice(Context context) {
        app = context.getApplicationContext();
        audioManager = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
    }

    boolean playRoadLimit(int limitKmh, Runnable onFinished) { return playRoadLimit(limitKmh, null, onFinished); }

    boolean playRoadLimit(int limitKmh, Runnable onStarted, Runnable onFinished) {
        String speed = speedName(limitKmh);
        if (speed == null) return false;
        ArrayList<String> clips = new ArrayList<>();
        clips.add("ep_limite_via");
        clips.add(speed);
        return play(clips, onStarted, onFinished);
    }

    boolean playOverspeed(int limitKmh, Runnable onFinished) { return playOverspeed(limitKmh, null, onFinished); }

    boolean playOverspeed(int limitKmh, Runnable onStarted, Runnable onFinished) {
        String speed = speedName(limitKmh);
        if (speed == null) return false;
        ArrayList<String> clips = new ArrayList<>();
        clips.add("ep_atencao");
        clips.add("ep_acima_limite");
        clips.add("ep_limite_via");
        clips.add(speed);
        return play(clips, onStarted, onFinished);
    }

    boolean playHazard(String type, double forwardM, int radarLimitKmh, Runnable onFinished) {
        return playHazard(type, forwardM, radarLimitKmh, null, onFinished);
    }

    boolean playHazard(String type, double forwardM, int radarLimitKmh, Runnable onStarted, Runnable onFinished) {
        String distance = distanceName(forwardM);
        if (distance == null) return false;
        ArrayList<String> clips = new ArrayList<>();
        String t = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        switch (t) {
            case "SEMAFORO":
                clips.add("ep_atencao");
                clips.add("ep_semaforo_frente");
                clips.add(distance);
                break;
            case "QUEBRA_MOLAS":
                clips.add("ep_reduza");
                clips.add("ep_quebra_molas_frente");
                clips.add(distance);
                break;
            case "PEDAGIO":
                clips.add("ep_pedagio_frente");
                clips.add(distance);
                break;
            case "PASSAGEM_NIVEL":
                clips.add("ep_atencao");
                clips.add("ep_passagem_nivel_frente");
                clips.add(distance);
                clips.add("ep_reduza");
                break;
            case "CAMERA_MONITORAMENTO":
                clips.add("ep_atencao");
                clips.add("ep_camera_monitoramento");
                clips.add(distance);
                break;
            default:
                clips.add("ep_radar_frente");
                clips.add(distance);
                if (radarLimitKmh > 0) {
                    String speed = speedName(radarLimitKmh);
                    if (speed == null) return false;
                    clips.add("ep_limite_radar");
                    clips.add(speed);
                }
                break;
        }
        return play(clips, onStarted, onFinished);
    }

    void stop() {
        main.post(() -> cancelCurrent(true));
    }

    void release() {
        main.post(() -> {
            generation++;
            cancelCurrent(true);
            started = null;
            finished = null;
        });
    }

    private boolean play(List<String> names, Runnable onStarted, Runnable onFinished) {
        if (names == null || names.isEmpty()) return false;
        ArrayList<Integer> ids = new ArrayList<>(names.size());
        for (String name : names) {
            int id = rawId(name);
            if (id == 0) return false;
            ids.add(id);
        }
        main.post(() -> startSequence(ids, onStarted, onFinished));
        return true;
    }

    private int rawId(String name) {
        try {
            return app.getResources().getIdentifier(name, "raw", app.getPackageName());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private void startSequence(List<Integer> ids, Runnable onStarted, Runnable onFinished) {
        generation++;
        int token = generation;
        cancelCurrent(true);
        queue.clear();
        queue.addAll(ids);
        started = onStarted;
        finished = onFinished;
        startNotified = false;
        // Acquire navigation focus and duck the app/player BEFORE the first word.
        // Calling the service callback after playback starts made head units miss
        // phrases such as 'Radar a frente' and only reproduce '500 metros'.
        requestLocalFocus();
        startNotified = true;
        Runnable begin = started;
        started = null;
        if (begin != null) begin.run();
        main.postDelayed(() -> playNext(token), FIRST_CLIP_PREROLL_MS);
    }

    private void playNext(int token) {
        if (token != generation) return;
        Integer id = queue.pollFirst();
        if (id == null) {
            Runnable done = finished;
            started = null;
            finished = null;
            startNotified = false;
            abandonLocalFocus();
            if (done != null) done.run();
            return;
        }
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            MediaPlayer mp = MediaPlayer.create(app, id, attrs, 0);
            if (mp == null) {
                failSequence(token);
                return;
            }
            player = mp;
            mp.setOnCompletionListener(donePlayer -> {
                safeRelease(donePlayer);
                if (player == donePlayer) player = null;
                main.postDelayed(() -> playNext(token), BETWEEN_CLIPS_MS);
            });
            mp.setOnErrorListener((badPlayer, what, extra) -> {
                safeRelease(badPlayer);
                if (player == badPlayer) player = null;
                failSequence(token);
                return true;
            });
            if (!startNotified) requestLocalFocus();
            mp.setVolume(1f, 1f);
            mp.start();
            boolean audibleStart = false;
            try { audibleStart = mp.isPlaying(); } catch (Throwable ignored) {}
            if (!audibleStart) { failSequence(token); return; }
            if (!startNotified) {
                startNotified = true;
                Runnable begin = started;
                started = null;
                if (begin != null) begin.run();
            }
        } catch (Throwable ignored) {
            failSequence(token);
        }
    }

    private void failSequence(int token) {
        if (token != generation) return;
        queue.clear();
        stopPlayerOnly();
        abandonLocalFocus();
        Runnable done = finished;
        started = null;
        finished = null;
        startNotified = false;
        if (done != null) done.run();
    }

    private void cancelCurrent(boolean notifyFinished) {
        Runnable done = notifyFinished ? finished : null;
        started = null;
        finished = null;
        startNotified = false;
        stopInternal();
        abandonLocalFocus();
        if (done != null) done.run();
    }

    private void stopInternal() {
        queue.clear();
        stopPlayerOnly();
    }

    private void stopPlayerOnly() {
        MediaPlayer p = player;
        player = null;
        if (p != null) {
            try { p.stop(); } catch (Throwable ignored) {}
            safeRelease(p);
        }
    }


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

    private static void safeRelease(MediaPlayer p) {
        if (p == null) return;
        try { p.reset(); } catch (Throwable ignored) {}
        try { p.release(); } catch (Throwable ignored) {}
    }

    private static String speedName(int speed) {
        if (speed < 10 || speed > 180 || speed % 5 != 0) return null;
        return String.format(Locale.US, "ep_speed_%03d", speed);
    }

    private static String distanceName(double meters) {
        if (!Double.isFinite(meters)) return null;
        int rounded;
        if (meters < 120.0) {
            rounded = (int)Math.round(meters / 10.0) * 10;
            rounded = Math.max(30, Math.min(100, rounded));
        } else if (meters < 1000.0) {
            rounded = (int)Math.round(meters / 50.0) * 50;
            rounded = Math.max(100, Math.min(950, rounded));
        } else {
            rounded = (int)Math.round(meters / 100.0) * 100;
            rounded = Math.max(1000, Math.min(1500, rounded));
        }
        return String.format(Locale.US, "ep_dist_%04d", rounded);
    }
}

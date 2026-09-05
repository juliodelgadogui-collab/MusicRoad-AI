package com.estradaplay.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
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
    private Runnable finished;
    private int generation;

    EstradaPlayOfflineVoice(Context context) {
        app = context.getApplicationContext();
    }

    boolean playRoadLimit(int limitKmh, Runnable onFinished) {
        String speed = speedName(limitKmh);
        if (speed == null) return false;
        ArrayList<String> clips = new ArrayList<>();
        clips.add("ep_limite_via");
        clips.add(speed);
        return play(clips, onFinished);
    }

    boolean playOverspeed(int limitKmh, Runnable onFinished) {
        String speed = speedName(limitKmh);
        if (speed == null) return false;
        ArrayList<String> clips = new ArrayList<>();
        clips.add("ep_atencao");
        clips.add("ep_acima_limite");
        clips.add("ep_limite_via");
        clips.add(speed);
        return play(clips, onFinished);
    }

    boolean playHazard(String type, double forwardM, int radarLimitKmh, Runnable onFinished) {
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
        return play(clips, onFinished);
    }

    void stop() {
        main.post(this::stopInternal);
    }

    void release() {
        main.post(() -> {
            generation++;
            stopInternal();
            finished = null;
        });
    }

    private boolean play(List<String> names, Runnable onFinished) {
        if (names == null || names.isEmpty()) return false;
        ArrayList<Integer> ids = new ArrayList<>(names.size());
        for (String name : names) {
            int id = rawId(name);
            if (id == 0) return false;
            ids.add(id);
        }
        main.post(() -> startSequence(ids, onFinished));
        return true;
    }

    private int rawId(String name) {
        try {
            return app.getResources().getIdentifier(name, "raw", app.getPackageName());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private void startSequence(List<Integer> ids, Runnable onFinished) {
        generation++;
        int token = generation;
        stopInternal();
        queue.clear();
        queue.addAll(ids);
        finished = onFinished;
        playNext(token);
    }

    private void playNext(int token) {
        if (token != generation) return;
        Integer id = queue.pollFirst();
        if (id == null) {
            Runnable done = finished;
            finished = null;
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
                playNext(token);
            });
            mp.setOnErrorListener((badPlayer, what, extra) -> {
                safeRelease(badPlayer);
                if (player == badPlayer) player = null;
                failSequence(token);
                return true;
            });
            mp.start();
        } catch (Throwable ignored) {
            failSequence(token);
        }
    }

    private void failSequence(int token) {
        if (token != generation) return;
        queue.clear();
        stopPlayerOnly();
        Runnable done = finished;
        finished = null;
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

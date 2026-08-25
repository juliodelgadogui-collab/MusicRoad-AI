package com.estradaplay.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.IBinder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class PlayerService extends Service {
    static final String ACTION_PLAY_TRACK = "com.estradaplay.app.PLAY_TRACK";
    static final String ACTION_TOGGLE = "com.estradaplay.app.PLAYER_TOGGLE";
    static final String ACTION_NEXT = "com.estradaplay.app.PLAYER_NEXT";
    static final String ACTION_PREVIOUS = "com.estradaplay.app.PLAYER_PREVIOUS";
    static final String ACTION_STATE = "com.estradaplay.app.PLAYER_STATE";
    static final String EXTRA_KEY = "track_key";
    static final String EXTRA_FOLDER = "folder";
    private static final String CHANNEL = "estradaplay_player";
    private static final int NOTIFICATION_ID = 4501;

    private final ArrayList<Track> queue = new ArrayList<>();
    private LibraryStore store;
    private MediaPlayer player;
    private int index = -1;
    private boolean prepared;

    @Override public void onCreate() {
        super.onCreate();
        store = new LibraryStore(this);
        createChannel();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_PLAY_TRACK.equals(action)) {
            String key = intent.getStringExtra(EXTRA_KEY);
            String folder = intent.getStringExtra(EXTRA_FOLDER);
            buildQueue(folder);
            index = findIndex(key);
            if (index < 0 && !queue.isEmpty()) index = 0;
            prepareAndPlay();
        } else if (ACTION_TOGGLE.equals(action)) toggle();
        else if (ACTION_NEXT.equals(action)) next();
        else if (ACTION_PREVIOUS.equals(action)) previous();
        return START_NOT_STICKY;
    }

    private void buildQueue(String folder) {
        queue.clear();
        List<Track> all = store.downloadedTracks();
        String f = folder == null ? "" : folder.trim();
        for (Track t : all) {
            if (f.isEmpty() || "__ALL__".equals(f) || f.equals(LibraryStore.folderKey(t))) queue.add(t);
        }
    }

    private int findIndex(String key) {
        if (key == null) return -1;
        for (int i = 0; i < queue.size(); i++) if (key.equals(queue.get(i).key())) return i;
        return -1;
    }

    private Track current() { return index >= 0 && index < queue.size() ? queue.get(index) : null; }

    private void prepareAndPlay() {
        releasePlayer();
        Track t = current();
        if (t == null) { broadcast("", false, "Fila vazia"); stopSelf(); return; }
        File f = t.localPath.isEmpty() ? null : new File(t.localPath);
        if (f == null || !f.isFile() || f.length() <= 0) { next(); return; }
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            player.setDataSource(f.getAbsolutePath());
            player.setOnPreparedListener(mp -> {
                prepared = true;
                requestFocus();
                mp.start();
                startForeground(NOTIFICATION_ID, notification(t, true));
                broadcast(t.title, true, "OFFLINE");
            });
            player.setOnCompletionListener(mp -> next());
            player.setOnErrorListener((mp, what, extra) -> { next(); return true; });
            startForeground(NOTIFICATION_ID, notification(t, false));
            broadcast(t.title, false, "Carregando local");
            player.prepareAsync();
        } catch (Exception e) {
            broadcast(t.title, false, "Arquivo local inválido");
            next();
        }
    }

    private void toggle() {
        if (player == null || !prepared) return;
        try {
            Track t = current();
            if (player.isPlaying()) {
                player.pause();
                if (t != null) { updateNotification(notification(t, false)); broadcast(t.title, false, "Pausado"); }
            } else {
                requestFocus(); player.start();
                if (t != null) { updateNotification(notification(t, true)); broadcast(t.title, true, "OFFLINE"); }
            }
        } catch (Exception ignored) {}
    }

    private void next() {
        if (queue.isEmpty()) return;
        index = (index + 1) % queue.size();
        prepareAndPlay();
    }

    private void previous() {
        if (queue.isEmpty()) return;
        try {
            if (player != null && prepared && player.getCurrentPosition() > 5000) { player.seekTo(0); return; }
        } catch (Exception ignored) {}
        index = (index - 1 + queue.size()) % queue.size();
        prepareAndPlay();
    }

    private void requestFocus() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null) am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
    }

    private void broadcast(String title, boolean playing, String state) {
        Track t = current();
        Intent i = new Intent(ACTION_STATE).setPackage(getPackageName());
        i.putExtra("title", title == null ? "" : title);
        i.putExtra("artist", t == null ? "" : t.artist);
        i.putExtra("playing", playing);
        i.putExtra("state", state == null ? "" : state);
        sendBroadcast(i);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Reprodução", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Música offline do EstradaPlay");
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification notification(Track t, boolean playing) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(t == null ? "EstradaPlay" : t.title)
                .setContentText(t == null ? "Música offline" : t.artist + (playing ? " · Tocando offline" : " · Pausado"))
                .setOngoing(playing)
                .setOnlyAlertOnce(true);
        return b.build();
    }

    private void updateNotification(Notification n) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, n);
    }

    private void releasePlayer() {
        prepared = false;
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
    }

    @Override public void onDestroy() { releasePlayer(); stopForeground(true); super.onDestroy(); }
}

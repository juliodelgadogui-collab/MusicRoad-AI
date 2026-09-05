package com.estradaplay.comunista;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PlayerService extends Service {
    static final String ACTION_PLAY_TRACK = "com.estradaplay.comunista.PLAY_TRACK";
    static final String ACTION_TOGGLE = "com.estradaplay.comunista.PLAYER_TOGGLE";
    static final String ACTION_NEXT = "com.estradaplay.comunista.PLAYER_NEXT";
    static final String ACTION_PREVIOUS = "com.estradaplay.comunista.PLAYER_PREVIOUS";
    static final String ACTION_DUCK = "com.estradaplay.comunista.PLAYER_DUCK";
    static final String ACTION_UNDUCK = "com.estradaplay.comunista.PLAYER_UNDUCK";
    static final String ACTION_STATE = "com.estradaplay.comunista.PLAYER_STATE";
    static final String ACTION_QUERY_STATE = "com.estradaplay.comunista.PLAYER_QUERY_STATE";
    static final String EXTRA_KEY = "track_key";
    static final String EXTRA_FOLDER = "folder";
    private static final String CHANNEL = "estradaplay_player";
    private static final int NOTIFICATION_ID = 4501;

    // PLAYER_SESSION_V242: last local track, folder and position survive service/app recreation.
    // Restoring never starts music by itself; playback resumes only after an explicit user action.
    private static final String PREFS = "epc_player_session_v242";
    private static final String KEY_TRACK = "track_key";
    private static final String KEY_FOLDER = "folder";
    private static final String KEY_POSITION = "position_ms";
    private static final long CHECKPOINT_MS = 5000L;

    // PLAYER_MEDIA_SESSION_V243: Bluetooth/headset/car controls and the Android media
    // notification use the same local queue as the in-app player. No UI layout changes.
    private final ArrayList<Track> queue = new ArrayList<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private SharedPreferences prefs;
    private MediaSession mediaSession;
    private int index = -1;
    private boolean prepared;
    private boolean restoring;
    private boolean playAfterRestore;
    private boolean resumeOnFocus;
    private float alertDuck = 1f;
    private float focusDuck = 1f;
    private String currentFolder = "__ALL__";
    private int pendingSeekMs;

    private final AudioManager.OnAudioFocusChangeListener focusListener = change ->
            main.post(() -> handleAudioFocusChange(change));

    private final Runnable checkpoint = new Runnable() {
        @Override public void run() {
            if (isPlaying()) {
                saveSnapshot();
                updateMediaSession(current(), true);
                main.postDelayed(this, CHECKPOINT_MS);
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        createChannel();
        createMediaSession();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_PLAY_TRACK.equals(action)) {
            String key = intent.getStringExtra(EXTRA_KEY);
            String requestedFolder = normalizeFolder(intent.getStringExtra(EXTRA_FOLDER));
            startForeground(NOTIFICATION_ID, notification(null, false));
            io.execute(() -> {
                ArrayList<Track> loaded = loadQueue(requestedFolder);
                main.post(() -> {
                    saveSnapshot();
                    currentFolder = requestedFolder;
                    queue.clear();
                    queue.addAll(loaded);
                    index = findIndex(key);
                    if (index < 0 && !queue.isEmpty()) index = 0;
                    pendingSeekMs = 0;
                    prepareCurrent(true, 0, false);
                });
            });
        } else if (ACTION_TOGGLE.equals(action)) toggle();
        else if (ACTION_NEXT.equals(action)) next();
        else if (ACTION_PREVIOUS.equals(action)) previous();
        else if (ACTION_DUCK.equals(action)) { alertDuck = 0.22f; applyVolume(); }
        else if (ACTION_UNDUCK.equals(action)) { alertDuck = 1f; applyVolume(); }
        else if (ACTION_QUERY_STATE.equals(action)) {
            if (current() == null) restoreSession(false);
            else broadcastCurrent();
        }
        return START_NOT_STICKY;
    }

    // PLAYER_LOCAL_INDEX_V2315: playback never asks MediaStore to rebuild anything.
    // Queue comes from the saved Estrada Play index + persistent phone MP3 SQLite index.
    private ArrayList<Track> loadQueue(String folder) {
        ArrayList<Track> loaded = new ArrayList<>();
        List<Track> appTracks = FastMusicLibrary.downloadedTracks(this);
        List<Track> all = PhoneMp3Store.hasPermission(this)
                ? PhoneMp3Store.mergeCached(this, appTracks)
                : appTracks;
        String f = normalizeFolder(folder);
        for (Track t : all) {
            if ("__ALL__".equals(f) || f.equals(LibraryStore.folderKey(t))) loaded.add(t);
        }
        return loaded;
    }

    private String normalizeFolder(String folder) {
        String f = folder == null ? "" : folder.trim();
        return f.isEmpty() ? "__ALL__" : f;
    }

    private int findIndex(String key) {
        if (key == null) return -1;
        for (int i = 0; i < queue.size(); i++) if (key.equals(queue.get(i).key())) return i;
        return -1;
    }

    private Track current() { return index >= 0 && index < queue.size() ? queue.get(index) : null; }

    private void restoreSession(boolean autoPlay) {
        if (restoring) {
            if (autoPlay) playAfterRestore = true;
            return;
        }
        if (current() != null) {
            if (!autoPlay) {
                broadcastCurrent();
            } else if (player != null && prepared) {
                resumePlayback();
            } else {
                prepareCurrent(true, pendingSeekMs, false);
            }
            return;
        }
        String key = prefs.getString(KEY_TRACK, "");
        String folder = normalizeFolder(prefs.getString(KEY_FOLDER, "__ALL__"));
        int position = Math.max(0, prefs.getInt(KEY_POSITION, 0));
        if (key == null || key.trim().isEmpty()) {
            broadcast("", false, "PRONTO");
            return;
        }
        restoring = true;
        io.execute(() -> {
            ArrayList<Track> loaded = loadQueue(folder);
            main.post(() -> {
                restoring = false;
                boolean shouldAutoPlay = autoPlay || playAfterRestore;
                playAfterRestore = false;
                queue.clear();
                queue.addAll(loaded);
                currentFolder = folder;
                index = findIndex(key);
                if (index < 0) {
                    clearSnapshot();
                    queue.clear();
                    broadcast("", false, "Última música indisponível");
                    return;
                }
                pendingSeekMs = position;
                prepareCurrent(shouldAutoPlay, position, true);
            });
        });
    }

    private void prepareCurrent(boolean autoPlay, int seekMs, boolean restoringSession) {
        releasePlayerOnly();
        Track t = current();
        if (t == null) { broadcast("", false, "Fila vazia"); if (autoPlay) stopSelf(); return; }
        String source = t.localPath == null ? "" : t.localPath.trim();
        if (!PhoneMp3Store.readable(this, source)) {
            if (source.startsWith("content://")) PhoneMp3Store.invalidate(this);
            if (restoringSession) {
                clearSnapshot();
                queue.clear(); index = -1;
                broadcast("", false, "Última música indisponível");
            } else next();
            return;
        }
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            if (source.startsWith("content://")) player.setDataSource(this, Uri.parse(source));
            else player.setDataSource(new File(source).getAbsolutePath());
            player.setOnPreparedListener(mp -> {
                prepared = true;
                int target = Math.max(0, seekMs);
                try {
                    int duration = mp.getDuration();
                    if (duration > 0 && target >= duration - 1500) target = 0;
                    if (target > 0) mp.seekTo(target);
                } catch (Throwable ignored) {}
                pendingSeekMs = target;
                requestFocus();
                applyVolume();
                if (autoPlay) {
                    mp.start();
                    resumeOnFocus = false;
                    startForeground(NOTIFICATION_ID, notification(t, true));
                    scheduleCheckpoint();
                    broadcast(t.title, true, source.startsWith("content://") ? "NO CELULAR" : "OFFLINE");
                } else {
                    updateNotification(notification(t, false));
                    broadcast(t.title, false, "PAUSADO");
                }
                saveSnapshot();
            });
            player.setOnCompletionListener(mp -> next());
            player.setOnErrorListener((mp, what, extra) -> { next(); return true; });
            if (autoPlay) startForeground(NOTIFICATION_ID, notification(t, false));
            broadcast(t.title, false, restoringSession ? "Restaurando" : "Carregando local");
            player.prepareAsync();
        } catch (Exception e) {
            broadcast(t.title, false, "Arquivo local inválido");
            if (restoringSession) {
                clearSnapshot();
                queue.clear(); index = -1;
            } else next();
        }
    }

    private void toggle() {
        if (isPlaying()) pausePlayback("Pausado");
        else resumePlayback();
    }

    private void resumePlayback() {
        if (player == null || !prepared) {
            restoreSession(true);
            return;
        }
        if (isPlaying()) {
            broadcastCurrent();
            return;
        }
        try {
            Track t = current();
            requestFocus();
            focusDuck = 1f;
            applyVolume();
            player.start();
            resumeOnFocus = false;
            scheduleCheckpoint();
            if (t != null) {
                startForeground(NOTIFICATION_ID, notification(t, true));
                broadcast(t.title, true, "LOCAL");
            }
        } catch (Exception ignored) {}
    }

    private void pausePlayback(String reason) {
        if (player == null || !prepared) return;
        try {
            Track t = current();
            if (player.isPlaying()) player.pause();
            stopCheckpoint();
            saveSnapshot();
            if (t != null) {
                updateNotification(notification(t, false));
                broadcast(t.title, false, reason == null || reason.isEmpty() ? "Pausado" : reason);
            }
        } catch (Exception ignored) {}
    }

    private void next() {
        if (queue.isEmpty()) {
            restoreSession(true);
            return;
        }
        saveSnapshot();
        index = (index + 1) % queue.size();
        pendingSeekMs = 0;
        prepareCurrent(true, 0, false);
    }

    private void previous() {
        if (queue.isEmpty()) {
            restoreSession(true);
            return;
        }
        try {
            if (player != null && prepared && player.getCurrentPosition() > 5000) {
                player.seekTo(0);
                pendingSeekMs = 0;
                saveSnapshot();
                updateMediaSession(current(), isPlaying());
                return;
            }
        } catch (Exception ignored) {}
        saveSnapshot();
        index = (index - 1 + queue.size()) % queue.size();
        pendingSeekMs = 0;
        prepareCurrent(true, 0, false);
    }

    private void seekTo(long positionMs) {
        if (player == null || !prepared) return;
        try {
            int duration = Math.max(0, player.getDuration());
            int target = (int) Math.max(0L, Math.min(positionMs, duration > 0 ? duration : Integer.MAX_VALUE));
            player.seekTo(target);
            pendingSeekMs = target;
            saveSnapshot();
            updateMediaSession(current(), isPlaying());
        } catch (Throwable ignored) {}
    }

    private void scheduleCheckpoint() {
        main.removeCallbacks(checkpoint);
        main.postDelayed(checkpoint, CHECKPOINT_MS);
    }

    private void stopCheckpoint() { main.removeCallbacks(checkpoint); }

    private boolean isPlaying() {
        try { return player != null && prepared && player.isPlaying(); }
        catch (Throwable ignored) { return false; }
    }

    private void saveSnapshot() {
        Track t = current();
        if (t == null || prefs == null) return;
        int position = pendingSeekMs;
        try { if (player != null && prepared) position = Math.max(0, player.getCurrentPosition()); }
        catch (Throwable ignored) {}
        pendingSeekMs = position;
        prefs.edit()
                .putString(KEY_TRACK, t.key())
                .putString(KEY_FOLDER, normalizeFolder(currentFolder))
                .putInt(KEY_POSITION, position)
                .apply();
    }

    private void clearSnapshot() {
        pendingSeekMs = 0;
        if (prefs != null) prefs.edit().remove(KEY_TRACK).remove(KEY_FOLDER).remove(KEY_POSITION).apply();
    }

    private void applyVolume() {
        if (player == null) return;
        float volume = Math.max(0f, Math.min(1f, alertDuck * focusDuck));
        try { player.setVolume(volume, volume); } catch (Throwable ignored) {}
    }

    private void requestFocus() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null) {
            try { am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN); }
            catch (Throwable ignored) {}
        }
    }

    private void abandonFocus() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null) {
            try { am.abandonAudioFocus(focusListener); } catch (Throwable ignored) {}
        }
    }

    private void handleAudioFocusChange(int change) {
        if (change == AudioManager.AUDIOFOCUS_GAIN) {
            focusDuck = 1f;
            applyVolume();
            if (resumeOnFocus) {
                resumeOnFocus = false;
                resumePlayback();
            }
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            focusDuck = 0.25f;
            applyVolume();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            if (isPlaying()) {
                resumeOnFocus = true;
                pausePlayback("Pausado por outro áudio");
            }
        } else if (change == AudioManager.AUDIOFOCUS_LOSS) {
            resumeOnFocus = false;
            focusDuck = 1f;
            if (isPlaying()) pausePlayback("Pausado");
        }
    }

    private void broadcastCurrent() {
        Track t = current();
        boolean playing = isPlaying();
        broadcast(t == null ? "" : t.title, playing, t == null ? "PRONTO" : (playing ? "LOCAL" : "PAUSADO"));
    }

    private void broadcast(String title, boolean playing, String state) {
        Track t = current();
        updateMediaSession(t, playing);
        Intent i = new Intent(ACTION_STATE).setPackage(getPackageName());
        i.putExtra("title", title == null ? "" : title);
        i.putExtra("artist", t == null ? "" : t.artist);
        i.putExtra("playing", playing);
        i.putExtra("state", state == null ? "" : state);
        i.putExtra("track_key", t == null ? "" : t.key());
        sendBroadcast(i);
    }

    private void createMediaSession() {
        try {
            mediaSession = new MediaSession(this, "EstradaPlayPlayer");
            mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            mediaSession.setCallback(new MediaSession.Callback() {
                @Override public void onPlay() { resumePlayback(); }
                @Override public void onPause() { pausePlayback("Pausado"); }
                @Override public void onSkipToNext() { next(); }
                @Override public void onSkipToPrevious() { previous(); }
                @Override public void onSeekTo(long pos) { seekTo(pos); }
                @Override public void onStop() { pausePlayback("Pausado"); }
            }, main);
            mediaSession.setActive(true);
            updateMediaSession(null, false);
        } catch (Throwable ignored) {
            mediaSession = null;
        }
    }

    private void updateMediaSession(Track t, boolean playing) {
        if (mediaSession == null) return;
        try {
            long position = Math.max(0, pendingSeekMs);
            long duration = 0L;
            if (player != null && prepared) {
                try { position = Math.max(0, player.getCurrentPosition()); } catch (Throwable ignored) {}
                try { duration = Math.max(0, player.getDuration()); } catch (Throwable ignored) {}
            }
            long actions = PlaybackState.ACTION_PLAY
                    | PlaybackState.ACTION_PAUSE
                    | PlaybackState.ACTION_PLAY_PAUSE
                    | PlaybackState.ACTION_SKIP_TO_NEXT
                    | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                    | PlaybackState.ACTION_SEEK_TO;
            int sessionState = t == null ? PlaybackState.STATE_NONE : (playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED);
            PlaybackState playbackState = new PlaybackState.Builder()
                    .setActions(actions)
                    .setState(sessionState, position, playing ? 1f : 0f)
                    .build();
            mediaSession.setPlaybackState(playbackState);

            if (t == null) {
                mediaSession.setMetadata(null);
            } else {
                MediaMetadata.Builder meta = new MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, safe(t.title))
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, safe(t.artist))
                        .putString(MediaMetadata.METADATA_KEY_ALBUM, safe(t.album));
                if (duration > 0) meta.putLong(MediaMetadata.METADATA_KEY_DURATION, duration);
                mediaSession.setMetadata(meta.build());
            }
            if (!mediaSession.isActive()) mediaSession.setActive(true);
        } catch (Throwable ignored) {}
    }

    private String safe(String value) { return value == null ? "" : value.trim(); }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Reprodução", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Música local do EstradaPlay e do aparelho");
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification notification(Track t, boolean playing) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(t == null ? "Estrada Play Comunista" : safe(t.title))
                .setContentText(notificationSubtitle(t, playing))
                .setContentIntent(openPlayerIntent())
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_media_previous, "Anterior", serviceAction(1, ACTION_PREVIOUS))
                .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? "Pausar" : "Tocar", serviceAction(2, ACTION_TOGGLE))
                .addAction(android.R.drawable.ic_media_next, "Próxima", serviceAction(3, ACTION_NEXT));
        if (mediaSession != null) {
            try {
                b.setStyle(new Notification.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
            } catch (Throwable ignored) {}
        }
        return b.build();
    }

    private String notificationSubtitle(Track t, boolean playing) {
        if (t == null) return "Música local";
        String artist = safe(t.artist);
        String suffix = playing ? "Tocando no aparelho" : "Pausado";
        return artist.isEmpty() ? suffix : artist + " · " + suffix;
    }

    private PendingIntent serviceAction(int requestCode, String action) {
        Intent i = new Intent(this, PlayerService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent openPlayerIntent() {
        Intent i = new Intent(this, MusicPlayerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 10, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void updateNotification(Notification n) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, n);
    }

    private void releasePlayerOnly() {
        stopCheckpoint();
        prepared = false;
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        saveSnapshot();
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        saveSnapshot();
        io.shutdownNow();
        releasePlayerOnly();
        abandonFocus();
        if (mediaSession != null) {
            try { mediaSession.setActive(false); } catch (Throwable ignored) {}
            try { mediaSession.release(); } catch (Throwable ignored) {}
            mediaSession = null;
        }
        stopForeground(true);
        super.onDestroy();
    }
}

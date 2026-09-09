package com.estradaplay.comunista;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    static final String EXTRA_QUEUE_TOKEN = "queue_token";
    static final String EXTRA_TRACK_JSON = "exact_track_json_v246";
    private static final String CHANNEL = "estradaplay_player";
    private static final int NOTIFICATION_ID = 4501;

    private static final String PREFS = "epc_player_session_v242";
    private static final String KEY_TRACK = "track_key";
    private static final String KEY_FOLDER = "folder";
    private static final String KEY_POSITION = "position_ms";
    private static final String KEY_QUEUE = "queue_json_v245";
    private static final String KEY_QUEUE_SOURCES = "queue_sources_json_v2410";
    private static final String KEY_STAGED_QUEUE = "staged_queue_json_v245";
    private static final String KEY_STAGED_TOKEN = "staged_queue_token_v245";
    private static final String KEY_STAGED_SOURCES = "staged_queue_sources_json_v2410";
    private static final long CHECKPOINT_MS = 5000L;

    // PLAYER_SESSION_V242: last local track, folder and position survive service/app recreation.
    // Restoring never starts music by itself; playback resumes only after an explicit user action.
    // PLAYER_MEDIA_SESSION_V243: Bluetooth/headset/car controls and the Android media
    // notification use the same local queue as the in-app player. No UI layout changes.
    // PLAYER_EXACT_QUEUE_V245: the visible search/folder result becomes the real queue,
    // survives service/app recreation and drops missing/corrupt files without looping forever.
    // PLAYER_EXACT_SELECTED_SOURCE_V246: PLAY_TRACK carries the exact Track shown/tapped
    // in the UI, so a duplicated/stale key can no longer redirect playback to another file.
    // PLAYER_SELECTED_FAILSAFE_V248: an explicit tap never silently falls through to another
    // queue item when the selected source is missing or cannot be decoded.
    // PLAYER_PREPARE_GENERATION_V249: callbacks from an older asynchronous MediaPlayer are
    // ignored after a newer selection/next/previous starts, preventing stale callbacks from
    // stopping or replacing the song the user most recently chose.
    // PLAYER_QUEUE_SOURCE_IDENTITY_V2410: staged and persisted queues remember the exact local
    // source for each key, so next/previous/session restore cannot remap a key to another copy.
    // PLAYER_QUEUE_SOURCE_STRICT_V251: once a queue has persisted source identity, a missing
    // source is dropped instead of falling back to another local file that happens to share its key.
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
    private long playerGeneration;

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

    static String stageQueue(Context context, List<Track> tracks) {
        if (context == null) return "";
        JSONArray a = new JSONArray();
        JSONObject sources = new JSONObject();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        if (tracks != null) {
            for (Track t : tracks) {
                if (t == null) continue;
                String key = t.key();
                if (key == null || key.trim().isEmpty() || !seen.add(key)) continue;
                a.put(key);
                try { sources.put(key, safeSource(t.localPath)); } catch (Throwable ignored) {}
                if (seen.size() >= 10000) break;
            }
        }
        String raw = a.toString();
        String token = Long.toHexString(System.nanoTime()) + "-" + Integer.toHexString(raw.hashCode());
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_STAGED_TOKEN, token)
                .putString(KEY_STAGED_QUEUE, raw)
                .putString(KEY_STAGED_SOURCES, sources.toString())
                .apply();
        return token;
    }

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
            String queueToken = intent.getStringExtra(EXTRA_QUEUE_TOKEN);
            Track exactSelected = exactTrackFromIntent(intent, key);
            StagedQueue requested = readStagedQueue(queueToken);
            startForeground(NOTIFICATION_ID, notification(null, false));
            io.execute(() -> {
                ArrayList<Track> loaded = loadQueue(requestedFolder, requested.keys, requested.sources);
                alignExactSelected(loaded, exactSelected, key, requested.keys);
                main.post(() -> {
                    saveSnapshot();
                    currentFolder = requestedFolder;
                    queue.clear();
                    queue.addAll(loaded);
                    index = findIndex(key, exactSelected == null ? "" : exactSelected.localPath);
                    if (index < 0 && !queue.isEmpty()) index = 0;
                    pendingSeekMs = 0;
                    saveSnapshot();
                    prepareCurrent(true, 0, false, true);
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

    private ArrayList<Track> loadQueue(String folder) { return loadQueue(folder, null, null); }
    private ArrayList<Track> loadQueue(String folder, List<String> preferredKeys) { return loadQueue(folder, preferredKeys, null); }

    private ArrayList<Track> loadQueue(String folder, List<String> preferredKeys, Map<String, String> preferredSources) {
        ArrayList<Track> all = loadLocalTracks();
        if (preferredKeys != null && !preferredKeys.isEmpty()) {
            ArrayList<Track> exact = new ArrayList<>();
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            boolean strictPreferredSources = preferredSources != null && !preferredSources.isEmpty();
            for (String key : preferredKeys) {
                if (key == null || !seen.add(key)) continue;
                String wantedSource = preferredSources == null ? "" : safeSource(preferredSources.get(key));
                Track chosen = null;
                if (!wantedSource.isEmpty()) {
                    for (Track candidate : all) {
                        if (candidate != null && key.equals(candidate.key()) && wantedSource.equals(safeSource(candidate.localPath))) { chosen = candidate; break; }
                    }
                    if (chosen != null) exact.add(chosen);
                    continue;
                }
                for (Track candidate : all) {
                    if (candidate != null && key.equals(candidate.key())) { chosen = candidate; break; }
                }
                if (chosen != null) exact.add(chosen);
            }
            if (!exact.isEmpty() || strictPreferredSources) return exact;
        }
        ArrayList<Track> loaded = new ArrayList<>();
        String f = normalizeFolder(folder);
        for (Track t : all) if ("__ALL__".equals(f) || f.equals(LibraryStore.folderKey(t))) loaded.add(t);
        return loaded;
    }

    private ArrayList<Track> loadLocalTracks() {
        List<Track> appTracks = FastMusicLibrary.downloadedTracks(this);
        List<Track> all = PhoneMp3Store.hasPermission(this) ? PhoneMp3Store.mergeCached(this, appTracks) : appTracks;
        return new ArrayList<>(all);
    }

    private Track exactTrackFromIntent(Intent intent, String requestedKey) {
        if (intent == null) return null;
        String raw = intent.getStringExtra(EXTRA_TRACK_JSON);
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            Track t = Track.fromStored(new JSONObject(raw));
            if (t == null || t.localPath == null || t.localPath.trim().isEmpty()) return null;
            if (requestedKey == null || requestedKey.trim().isEmpty() || requestedKey.equals(t.key())) return t;
        } catch (Throwable ignored) {}
        return null;
    }

    private void alignExactSelected(ArrayList<Track> loaded, Track exact, String requestedKey, List<String> preferredKeys) {
        if (loaded == null || exact == null || exact.localPath == null || exact.localPath.trim().isEmpty()) return;
        String key = requestedKey == null || requestedKey.trim().isEmpty() ? exact.key() : requestedKey;
        String source = exact.localPath.trim();
        for (int i = 0; i < loaded.size(); i++) {
            Track candidate = loaded.get(i);
            if (candidate != null && key.equals(candidate.key()) && source.equals(safeSource(candidate.localPath))) return;
        }
        for (int i = 0; i < loaded.size(); i++) {
            Track candidate = loaded.get(i);
            if (candidate != null && key.equals(candidate.key())) { loaded.set(i, exact); return; }
        }
        int insertAt = 0;
        if (preferredKeys != null && !preferredKeys.isEmpty()) {
            int wanted = preferredKeys.indexOf(key);
            if (wanted > 0) {
                for (int p = 0; p < wanted; p++) {
                    String previous = preferredKeys.get(p);
                    if (previous == null) continue;
                    for (Track candidate : loaded) if (candidate != null && previous.equals(candidate.key())) { insertAt++; break; }
                }
            }
        }
        loaded.add(Math.max(0, Math.min(insertAt, loaded.size())), exact);
    }

    private StagedQueue readStagedQueue(String token) {
        if (prefs == null || token == null || token.trim().isEmpty()) return new StagedQueue();
        String expected = prefs.getString(KEY_STAGED_TOKEN, "");
        if (!token.equals(expected)) return new StagedQueue();
        String rawKeys = prefs.getString(KEY_STAGED_QUEUE, "[]");
        String rawSources = prefs.getString(KEY_STAGED_SOURCES, "{}");
        prefs.edit().remove(KEY_STAGED_TOKEN).remove(KEY_STAGED_QUEUE).remove(KEY_STAGED_SOURCES).apply();
        StagedQueue out = new StagedQueue();
        out.keys.addAll(parseQueueKeys(rawKeys));
        out.sources.putAll(parseQueueSources(rawSources));
        return out;
    }

    private static ArrayList<String> parseQueueKeys(String raw) {
        ArrayList<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        try {
            JSONArray a = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < a.length() && out.size() < 10000; i++) {
                String key = a.optString(i, "").trim();
                if (!key.isEmpty() && seen.add(key)) out.add(key);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static LinkedHashMap<String, String> parseQueueSources(String raw) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        try {
            JSONObject o = new JSONObject(raw == null ? "{}" : raw);
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext() && out.size() < 10000) {
                String key = it.next();
                String source = safeSource(o.optString(key, ""));
                if (key != null && !key.trim().isEmpty() && !source.isEmpty()) out.put(key, source);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private String queueJson() {
        JSONArray a = new JSONArray();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Track t : queue) {
            if (t == null) continue;
            String key = t.key();
            if (key == null || key.trim().isEmpty() || !seen.add(key)) continue;
            a.put(key);
            if (seen.size() >= 10000) break;
        }
        return a.toString();
    }

    private String queueSourcesJson() {
        JSONObject sources = new JSONObject();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Track t : queue) {
            if (t == null) continue;
            String key = t.key();
            String source = safeSource(t.localPath);
            if (key == null || key.trim().isEmpty() || source.isEmpty() || !seen.add(key)) continue;
            try { sources.put(key, source); } catch (Throwable ignored) {}
            if (seen.size() >= 10000) break;
        }
        return sources.toString();
    }

    private String normalizeFolder(String folder) { String f = folder == null ? "" : folder.trim(); return f.isEmpty() ? "__ALL__" : f; }
    private static String safeSource(String source) { return source == null ? "" : source.trim(); }
    private int findIndex(String key) { return findIndex(key, ""); }

    private int findIndex(String key, String exactSource) {
        String source = safeSource(exactSource);
        if (!source.isEmpty()) {
            for (int i = 0; i < queue.size(); i++) {
                Track t = queue.get(i);
                if (t == null) continue;
                if ((key == null || key.equals(t.key())) && source.equals(safeSource(t.localPath))) return i;
            }
        }
        if (key == null) return -1;
        for (int i = 0; i < queue.size(); i++) if (key.equals(queue.get(i).key())) return i;
        return -1;
    }

    private int findRestoreIndex(String key, List<String> oldOrder) {
        int direct = findIndex(key);
        if (direct >= 0) return direct;
        if (oldOrder != null && !oldOrder.isEmpty()) {
            int start = oldOrder.indexOf(key);
            if (start >= 0) {
                for (int n = 1; n <= oldOrder.size(); n++) {
                    String candidate = oldOrder.get((start + n) % oldOrder.size());
                    int found = findIndex(candidate);
                    if (found >= 0) return found;
                }
            }
        }
        return queue.isEmpty() ? -1 : 0;
    }

    private Track current() { return index >= 0 && index < queue.size() ? queue.get(index) : null; }

    private void restoreSession(boolean autoPlay) {
        if (restoring) { if (autoPlay) playAfterRestore = true; return; }
        if (current() != null) {
            if (!autoPlay) broadcastCurrent();
            else if (player != null && prepared) resumePlayback();
            else prepareCurrent(true, pendingSeekMs, false);
            return;
        }
        String key = prefs.getString(KEY_TRACK, "");
        String folder = normalizeFolder(prefs.getString(KEY_FOLDER, "__ALL__"));
        int position = Math.max(0, prefs.getInt(KEY_POSITION, 0));
        ArrayList<String> savedKeys = parseQueueKeys(prefs.getString(KEY_QUEUE, "[]"));
        LinkedHashMap<String, String> savedSources = parseQueueSources(prefs.getString(KEY_QUEUE_SOURCES, "{}"));
        if (key == null || key.trim().isEmpty()) { broadcast("", false, "PRONTO"); return; }
        restoring = true;
        io.execute(() -> {
            ArrayList<Track> loaded = loadQueue(folder, savedKeys, savedSources);
            main.post(() -> {
                restoring = false;
                boolean shouldAutoPlay = autoPlay || playAfterRestore;
                playAfterRestore = false;
                queue.clear(); queue.addAll(loaded); currentFolder = folder;
                String restoredSource = savedSources.get(key);
                index = findIndex(key, restoredSource == null ? "" : restoredSource);
                if (index < 0) index = findRestoreIndex(key, savedKeys);
                if (index < 0) { clearSnapshot(); queue.clear(); broadcast("", false, "Última fila indisponível"); return; }
                pendingSeekMs = key.equals(current().key()) ? position : 0;
                saveSnapshot();
                prepareCurrent(shouldAutoPlay, pendingSeekMs, true);
            });
        });
    }

    private void prepareCurrent(boolean autoPlay, int seekMs, boolean restoringSession) { prepareCurrent(autoPlay, seekMs, restoringSession, false); }

    private void prepareCurrent(boolean autoPlay, int seekMs, boolean restoringSession, boolean explicitSelection) {
        final long generation = ++playerGeneration;
        releasePlayerOnly();
        Track t = current();
        if (t == null) { broadcast("", false, "Fila vazia"); if (autoPlay) stopSelf(); return; }
        String source = t.localPath == null ? "" : t.localPath.trim();
        if (!PhoneMp3Store.readable(this, source)) {
            if (source.startsWith("content://")) PhoneMp3Store.invalidate(this);
            postIfGenerationActive(generation, null, () -> {
                if (explicitSelection) failExplicitSelection(t, "Música selecionada indisponível");
                else skipCurrentUnavailable(autoPlay, restoringSession ? "Última música indisponível" : "Pulando arquivo indisponível");
            });
            return;
        }
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            if (source.startsWith("content://")) player.setDataSource(this, Uri.parse(source)); else player.setDataSource(new File(source).getAbsolutePath());
            player.setOnPreparedListener(mp -> {
                if (!isGenerationActive(generation, mp)) return;
                prepared = true;
                int target = Math.max(0, seekMs);
                try { int duration = mp.getDuration(); if (duration > 0 && target >= duration - 1500) target = 0; if (target > 0) mp.seekTo(target); } catch (Throwable ignored) {}
                pendingSeekMs = target;
                requestFocus(); applyVolume();
                if (autoPlay) {
                    try { mp.start(); } catch (Throwable startError) {
                        postIfGenerationActive(generation, mp, () -> { if (explicitSelection) failExplicitSelection(t, "Não foi possível tocar esta música"); else skipCurrentUnavailable(true, "Pulando arquivo inválido"); });
                        return;
                    }
                    if (!isGenerationActive(generation, mp)) return;
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
            player.setOnCompletionListener(mp -> { if (isGenerationActive(generation, mp)) next(); });
            player.setOnErrorListener((mp, what, extra) -> { postIfGenerationActive(generation, mp, () -> { if (explicitSelection) failExplicitSelection(t, "Não foi possível tocar esta música"); else skipCurrentUnavailable(true, "Pulando arquivo inválido"); }); return true; });
            if (autoPlay) startForeground(NOTIFICATION_ID, notification(t, false));
            broadcast(t.title, false, restoringSession ? "Restaurando" : "Carregando local");
            player.prepareAsync();
        } catch (Exception e) {
            final MediaPlayer failedPlayer = player;
            postIfGenerationActive(generation, failedPlayer, () -> { if (explicitSelection) failExplicitSelection(t, "Não foi possível tocar esta música"); else skipCurrentUnavailable(autoPlay, "Pulando arquivo inválido"); });
        }
    }

    private boolean isGenerationActive(long generation, MediaPlayer expectedPlayer) { return generation == playerGeneration && (expectedPlayer == null || player == expectedPlayer); }
    private void postIfGenerationActive(long generation, MediaPlayer expectedPlayer, Runnable action) { if (action != null) main.post(() -> { if (isGenerationActive(generation, expectedPlayer)) action.run(); }); }

    private void failExplicitSelection(Track selected, String state) {
        playerGeneration++; releasePlayerOnly();
        Track broken = selected == null ? current() : selected;
        if (broken != null && broken.localPath != null && broken.localPath.startsWith("content://")) PhoneMp3Store.invalidate(this);
        if (index >= 0 && index < queue.size()) queue.remove(index);
        index = -1; clearSnapshot();
        try { stopForeground(true); } catch (Throwable ignored) {}
        String title = broken == null ? "" : broken.title;
        broadcast(title, false, state == null || state.trim().isEmpty() ? "Música indisponível" : state);
    }

    private void skipCurrentUnavailable(boolean autoPlay, String emptyState) {
        playerGeneration++; releasePlayerOnly();
        Track broken = current();
        if (broken != null && broken.localPath != null && broken.localPath.startsWith("content://")) PhoneMp3Store.invalidate(this);
        if (index >= 0 && index < queue.size()) queue.remove(index);
        if (queue.isEmpty()) {
            index = -1; clearSnapshot(); updateNotification(notification(null, false));
            broadcast("", false, emptyState == null || emptyState.isEmpty() ? "Fila vazia" : emptyState);
            if (autoPlay) stopSelf(); return;
        }
        if (index < 0 || index >= queue.size()) index = 0;
        pendingSeekMs = 0; saveSnapshot(); main.post(() -> prepareCurrent(autoPlay, 0, false));
    }

    private void toggle() { if (isPlaying()) pausePlayback("Pausado"); else resumePlayback(); }

    private void resumePlayback() {
        if (player == null || !prepared) { restoreSession(true); return; }
        if (isPlaying()) { broadcastCurrent(); return; }
        try {
            Track t = current(); requestFocus(); focusDuck = 1f; applyVolume(); player.start(); resumeOnFocus = false; scheduleCheckpoint();
            if (t != null) { startForeground(NOTIFICATION_ID, notification(t, true)); broadcast(t.title, true, "LOCAL"); }
        } catch (Exception ignored) {}
    }

    private void pausePlayback(String reason) {
        if (player == null || !prepared) return;
        try {
            Track t = current(); if (player.isPlaying()) player.pause(); stopCheckpoint(); saveSnapshot();
            if (t != null) { updateNotification(notification(t, false)); broadcast(t.title, false, reason == null || reason.isEmpty() ? "Pausado" : reason); }
        } catch (Exception ignored) {}
    }

    private void next() { if (queue.isEmpty()) { restoreSession(true); return; } saveSnapshot(); index = (index + 1) % queue.size(); pendingSeekMs = 0; saveSnapshot(); prepareCurrent(true, 0, false); }

    private void previous() {
        if (queue.isEmpty()) { restoreSession(true); return; }
        try { if (player != null && prepared && player.getCurrentPosition() > 5000) { player.seekTo(0); pendingSeekMs = 0; saveSnapshot(); updateMediaSession(current(), isPlaying()); return; } } catch (Exception ignored) {}
        saveSnapshot(); index = (index - 1 + queue.size()) % queue.size(); pendingSeekMs = 0; saveSnapshot(); prepareCurrent(true, 0, false);
    }

    private void seekTo(long positionMs) {
        if (player == null || !prepared) return;
        try { int duration = Math.max(0, player.getDuration()); int target = (int)Math.max(0L, Math.min(positionMs, duration > 0 ? duration : Integer.MAX_VALUE)); player.seekTo(target); pendingSeekMs = target; saveSnapshot(); updateMediaSession(current(), isPlaying()); } catch (Throwable ignored) {}
    }

    private void scheduleCheckpoint() { main.removeCallbacks(checkpoint); main.postDelayed(checkpoint, CHECKPOINT_MS); }
    private void stopCheckpoint() { main.removeCallbacks(checkpoint); }
    private boolean isPlaying() { try { return player != null && prepared && player.isPlaying(); } catch (Throwable ignored) { return false; } }

    private void saveSnapshot() {
        Track t = current(); if (t == null || prefs == null) return;
        int position = pendingSeekMs;
        try { if (player != null && prepared) position = Math.max(0, player.getCurrentPosition()); } catch (Throwable ignored) {}
        pendingSeekMs = position;
        prefs.edit().putString(KEY_TRACK, t.key()).putString(KEY_FOLDER, normalizeFolder(currentFolder)).putInt(KEY_POSITION, position).putString(KEY_QUEUE, queueJson()).putString(KEY_QUEUE_SOURCES, queueSourcesJson()).apply();
    }

    private void clearSnapshot() {
        pendingSeekMs = 0;
        if (prefs != null) prefs.edit().remove(KEY_TRACK).remove(KEY_FOLDER).remove(KEY_POSITION).remove(KEY_QUEUE).remove(KEY_QUEUE_SOURCES).remove(KEY_STAGED_TOKEN).remove(KEY_STAGED_QUEUE).remove(KEY_STAGED_SOURCES).apply();
    }

    private void applyVolume() { if (player != null) { float volume = Math.max(0f, Math.min(1f, alertDuck * focusDuck)); try { player.setVolume(volume, volume); } catch (Throwable ignored) {} } }
    private void requestFocus() { AudioManager am = (AudioManager)getSystemService(AUDIO_SERVICE); if (am != null) try { am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN); } catch (Throwable ignored) {} }
    private void abandonFocus() { AudioManager am = (AudioManager)getSystemService(AUDIO_SERVICE); if (am != null) try { am.abandonAudioFocus(focusListener); } catch (Throwable ignored) {} }

    private void handleAudioFocusChange(int change) {
        if (change == AudioManager.AUDIOFOCUS_GAIN) { focusDuck = 1f; applyVolume(); if (resumeOnFocus) { resumeOnFocus = false; resumePlayback(); } }
        else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) { focusDuck = 0.25f; applyVolume(); }
        else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) { if (isPlaying()) { resumeOnFocus = true; pausePlayback("Pausado por outro áudio"); } }
        else if (change == AudioManager.AUDIOFOCUS_LOSS) { resumeOnFocus = false; focusDuck = 1f; if (isPlaying()) pausePlayback("Pausado"); }
    }

    private void broadcastCurrent() { Track t = current(); boolean playing = isPlaying(); broadcast(t == null ? "" : t.title, playing, t == null ? "PRONTO" : (playing ? "LOCAL" : "PAUSADO")); }

    private void broadcast(String title, boolean playing, String state) {
        Track t = current(); updateMediaSession(t, playing);
        Intent i = new Intent(ACTION_STATE).setPackage(getPackageName());
        i.putExtra("title", title == null ? "" : title); i.putExtra("artist", t == null ? "" : t.artist); i.putExtra("playing", playing); i.putExtra("state", state == null ? "" : state); i.putExtra("track_key", t == null ? "" : t.key()); i.putExtra("queue_size", queue.size()); i.putExtra("queue_index", index); sendBroadcast(i);
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
            mediaSession.setActive(true); updateMediaSession(null, false);
        } catch (Throwable ignored) { mediaSession = null; }
    }

    private void updateMediaSession(Track t, boolean playing) {
        if (mediaSession == null) return;
        try {
            long position = Math.max(0, pendingSeekMs), duration = 0L;
            if (player != null && prepared) { try { position = Math.max(0, player.getCurrentPosition()); } catch (Throwable ignored) {} try { duration = Math.max(0, player.getDuration()); } catch (Throwable ignored) {} }
            long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO;
            int sessionState = t == null ? PlaybackState.STATE_NONE : (playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED);
            mediaSession.setPlaybackState(new PlaybackState.Builder().setActions(actions).setState(sessionState, position, playing ? 1f : 0f).build());
            if (t == null) mediaSession.setMetadata(null);
            else { MediaMetadata.Builder meta = new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, safe(t.title)).putString(MediaMetadata.METADATA_KEY_ARTIST, safe(t.artist)).putString(MediaMetadata.METADATA_KEY_ALBUM, safe(t.album)); if (duration > 0) meta.putLong(MediaMetadata.METADATA_KEY_DURATION, duration); mediaSession.setMetadata(meta.build()); }
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
        b.setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(t == null ? "Estrada Play" : safe(t.title)).setContentText(notificationSubtitle(t, playing)).setContentIntent(openPlayerIntent()).setCategory(Notification.CATEGORY_TRANSPORT).setVisibility(Notification.VISIBILITY_PUBLIC).setOngoing(playing).setOnlyAlertOnce(true).addAction(android.R.drawable.ic_media_previous, "Anterior", serviceAction(1, ACTION_PREVIOUS)).addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, playing ? "Pausar" : "Tocar", serviceAction(2, ACTION_TOGGLE)).addAction(android.R.drawable.ic_media_next, "Próxima", serviceAction(3, ACTION_NEXT));
        if (mediaSession != null) try { b.setStyle(new Notification.MediaStyle().setMediaSession(mediaSession.getSessionToken()).setShowActionsInCompactView(0, 1, 2)); } catch (Throwable ignored) {}
        return b.build();
    }

    private String notificationSubtitle(Track t, boolean playing) { if (t == null) return "Música local"; String artist = safe(t.artist); String suffix = playing ? "Tocando no aparelho" : "Pausado"; return artist.isEmpty() ? suffix : artist + " · " + suffix; }
    private PendingIntent serviceAction(int requestCode, String action) { Intent i = new Intent(this, PlayerService.class).setAction(action); return PendingIntent.getService(this, requestCode, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE); }
    private PendingIntent openPlayerIntent() { Intent i = new Intent(this, MusicPlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP); return PendingIntent.getActivity(this, 10, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE); }

    private void updateNotification(Notification n) { NotificationPermissionCompat.notify(this, NOTIFICATION_ID, n); }

    private void releasePlayerOnly() {
        stopCheckpoint(); prepared = false;
        if (player != null) { try { player.stop(); } catch (Exception ignored) {} try { player.release(); } catch (Exception ignored) {} player = null; }
    }

    private static final class StagedQueue { final ArrayList<Track> unused = null; final ArrayList<String> keys = new ArrayList<>(); final LinkedHashMap<String, String> sources = new LinkedHashMap<>(); }

    @Override public void onTaskRemoved(Intent rootIntent) { saveSnapshot(); stopSelf(); super.onTaskRemoved(rootIntent); }

    @Override public void onDestroy() {
        saveSnapshot(); io.shutdownNow(); playerGeneration++; releasePlayerOnly(); abandonFocus();
        if (mediaSession != null) { try { mediaSession.setActive(false); } catch (Throwable ignored) {} try { mediaSession.release(); } catch (Throwable ignored) {} mediaSession = null; }
        stopForeground(true); super.onDestroy();
    }
}

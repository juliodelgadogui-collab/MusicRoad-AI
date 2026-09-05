package com.estradaplay.comunista;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;

/**
 * MUSIC_LIBRARY_INTEGRITY_V247
 * MUSIC_LIBRARY_SESSION_CLEANUP_V251
 *
 * Fast, local-only repair for stale Estrada Play download entries.
 * It never walks storage and never contacts the server: only paths already
 * recorded in SharedPreferences are checked. Broken entries are removed before
 * the Music screen can expose them as playable songs.
 *
 * This also clears a saved PlayerService session when its current track was one
 * of the removed entries. 2.5.1 includes the exact-source queue maps introduced
 * in 2.4.10, so a stale session cannot leave an orphan source mapping behind.
 */
final class MusicLibraryIntegrityV247 {
    private static final String LIB_PREFS = "estradaplay_library_v1";
    private static final String KEY_DOWNLOADED = "downloaded";
    private static final String FLAGS_PREFS = "estradaplay_library_flags_v211";
    private static final String KEY_DOWNLOADED_HINT = "downloaded_hint";

    private static final String PLAYER_PREFS = "epc_player_session_v242";
    private static final String KEY_TRACK = "track_key";
    private static final String KEY_FOLDER = "folder";
    private static final String KEY_POSITION = "position_ms";
    private static final String KEY_QUEUE = "queue_json_v245";
    private static final String KEY_QUEUE_SOURCES = "queue_sources_json_v2410";
    private static final String KEY_STAGED_QUEUE = "staged_queue_json_v245";
    private static final String KEY_STAGED_TOKEN = "staged_queue_token_v245";
    private static final String KEY_STAGED_SOURCES = "staged_queue_sources_json_v2410";

    private static final String REPAIR_PREFS = "epc_music_integrity_v247";
    private static final String KEY_LAST_REPAIR = "last_repair_at";
    private static final String KEY_LAST_REMOVED = "last_removed";

    private MusicLibraryIntegrityV247() {}

    static int repair(Context context) {
        if (context == null) return 0;
        Context app = context.getApplicationContext();
        SharedPreferences library = app.getSharedPreferences(LIB_PREFS, Context.MODE_PRIVATE);
        String raw = library.getString(KEY_DOWNLOADED, "{}");
        if (raw == null || raw.trim().isEmpty()) raw = "{}";

        JSONObject downloaded;
        try { downloaded = new JSONObject(raw); }
        catch (Throwable ignored) { downloaded = new JSONObject(); }

        ArrayList<String> staleKeys = new ArrayList<>();
        Iterator<String> keys = downloaded.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject stored = downloaded.optJSONObject(key);
            if (stored == null) {
                staleKeys.add(key);
                continue;
            }

            Track track;
            try { track = Track.fromStored(stored); }
            catch (Throwable ignored) { track = null; }
            if (track == null) {
                staleKeys.add(key);
                continue;
            }

            String source = track.localPath == null ? "" : track.localPath.trim();
            if (source.isEmpty()) {
                staleKeys.add(key);
                continue;
            }

            // Content URIs belong to Android/MediaStore. PhoneMp3Store owns their
            // lifecycle and refresh rules; do not open thousands of descriptors here.
            if (source.startsWith("content://")) continue;

            File file = localFile(source);
            if (file == null || !file.isFile() || file.length() <= 0L) staleKeys.add(key);
        }

        if (!staleKeys.isEmpty()) {
            for (String key : staleKeys) downloaded.remove(key);
            library.edit().putString(KEY_DOWNLOADED, downloaded.toString()).apply();
            app.getSharedPreferences(FLAGS_PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_DOWNLOADED_HINT, downloaded.length() > 0).apply();
            clearStalePlayerSession(app, staleKeys);
        }

        app.getSharedPreferences(REPAIR_PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_REPAIR, System.currentTimeMillis())
                .putInt(KEY_LAST_REMOVED, staleKeys.size())
                .apply();
        return staleKeys.size();
    }

    private static void clearStalePlayerSession(Context app, ArrayList<String> staleKeys) {
        if (app == null || staleKeys == null || staleKeys.isEmpty()) return;
        SharedPreferences player = app.getSharedPreferences(PLAYER_PREFS, Context.MODE_PRIVATE);
        String current = player.getString(KEY_TRACK, "");
        if (current == null || current.trim().isEmpty() || !staleKeys.contains(current)) return;
        player.edit()
                .remove(KEY_TRACK)
                .remove(KEY_FOLDER)
                .remove(KEY_POSITION)
                .remove(KEY_QUEUE)
                .remove(KEY_QUEUE_SOURCES)
                .remove(KEY_STAGED_QUEUE)
                .remove(KEY_STAGED_TOKEN)
                .remove(KEY_STAGED_SOURCES)
                .apply();
    }

    private static File localFile(String source) {
        if (source == null) return null;
        String value = source.trim();
        if (value.isEmpty()) return null;
        try {
            if (value.startsWith("file://")) {
                String path = Uri.parse(value).getPath();
                return path == null || path.trim().isEmpty() ? null : new File(path);
            }
            return new File(value);
        } catch (Throwable ignored) {
            return null;
        }
    }
}

package com.estradaplay.comunista;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * MUSIC_FAST_LOCAL_V2315
 * Reads only the already-saved Estrada Play download index. No filesystem walk,
 * no descriptor-open per track and no server. A file is validated only when the
 * user actually presses Play.
 */
final class FastMusicLibrary {
    private static final String PREFS = "estradaplay_library_v1";
    private static final String KEY_DOWNLOADED = "downloaded";

    private FastMusicLibrary() {}

    static List<Track> downloadedTracks(Context context) {
        ArrayList<Track> out = new ArrayList<>();
        if (context == null) return out;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_DOWNLOADED, "{}");
        if (raw == null || raw.trim().isEmpty()) raw = "{}";
        try {
            JSONObject all = new JSONObject(raw);
            Iterator<String> it = all.keys();
            while (it.hasNext()) {
                JSONObject o = all.optJSONObject(it.next());
                if (o == null) continue;
                Track t = Track.fromStored(o);
                if (t != null && t.localPath != null && !t.localPath.trim().isEmpty()) out.add(t);
            }
        } catch (Throwable ignored) {}
        return out;
    }
}

package com.musicroad.ai;

import org.json.JSONException;
import org.json.JSONObject;

public final class MusicTrack {
    public final String id;
    public final String title;
    public final String artist;
    public final String album;
    public final String source;
    public final String origin;
    public final String mimeType;
    public final long durationMs;
    public final long fileSize;
    public final long albumId;

    public MusicTrack(String id, String title, String artist, String album, String source,
                      String origin, String mimeType, long durationMs, long fileSize, long albumId) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.source = source;
        this.origin = origin;
        this.mimeType = mimeType;
        this.durationMs = durationMs;
        this.fileSize = fileSize;
        this.albumId = albumId;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("title", title);
        o.put("artist", artist);
        o.put("album", album);
        o.put("source", source);
        o.put("content_uri", source);
        o.put("origin", origin);
        o.put("mime_type", mimeType);
        o.put("duration", durationMs > 0 ? Math.round(durationMs / 1000.0) : 0);
        o.put("duration_ms", durationMs);
        o.put("file_size", fileSize);
        o.put("album_id", albumId);
        o.put("deviceSource", "mediastore");
        o.put("nativeMedia", true);
        return o;
    }

    public static MusicTrack fromJson(JSONObject o) {
        return new MusicTrack(
                o.optString("id", ""),
                o.optString("title", "Sem título"),
                o.optString("artist", "Artista desconhecido"),
                o.optString("album", ""),
                o.optString("source", o.optString("content_uri", "")),
                o.optString("origin", "Dispositivo"),
                o.optString("mime_type", ""),
                o.optLong("duration_ms", o.optLong("duration", 0) * 1000L),
                o.optLong("file_size", 0),
                o.optLong("album_id", 0)
        );
    }
}

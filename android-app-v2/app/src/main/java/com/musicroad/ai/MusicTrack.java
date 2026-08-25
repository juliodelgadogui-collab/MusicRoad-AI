package com.musicroad.ai;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

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
    public final String genre;
    public final String folder;
    public final String folderPath;

    public MusicTrack(String id, String title, String artist, String album, String source,
                      String origin, String mimeType, long durationMs, long fileSize, long albumId,
                      String genre, String folder, String folderPath) {
        this.id = id == null ? "" : id;
        this.title = title == null || title.trim().isEmpty() ? "Sem título" : title;
        this.artist = artist == null || artist.trim().isEmpty() ? "Artista desconhecido" : artist;
        this.album = album == null ? "" : album;
        this.source = source == null ? "" : source;
        this.origin = origin == null ? "" : origin;
        this.mimeType = mimeType == null ? "" : mimeType;
        this.durationMs = durationMs;
        this.fileSize = fileSize;
        this.albumId = albumId;
        this.genre = genre == null ? "" : genre;
        this.folder = folder == null ? "" : folder;
        this.folderPath = folderPath == null ? "" : folderPath;
    }

    public boolean isDriveTrack() {
        return origin != null && origin.toLowerCase(Locale.ROOT).contains("drive");
    }

    public JSONObject toJson() throws JSONException {
        MusicTrack effective = MusicOfflineStore.preferLocal(this);
        if (effective == null) effective = this;
        boolean downloaded = effective.source.startsWith("file://");
        boolean offlineRequired = isDriveTrack() && !downloaded;
        String playbackSource = offlineRequired ? "" : effective.source;

        JSONObject o = new JSONObject();
        o.put("id", effective.id);
        o.put("title", effective.title);
        o.put("artist", effective.artist);
        o.put("album", effective.album);
        o.put("source", playbackSource);
        o.put("content_uri", playbackSource);
        if ((offlineRequired || !effective.source.equals(this.source)) && (this.source.startsWith("http://") || this.source.startsWith("https://"))) {
            o.put("remote_source", this.source);
        }
        o.put("origin", effective.origin);
        o.put("mime_type", effective.mimeType);
        o.put("duration", effective.durationMs > 0 ? Math.round(effective.durationMs / 1000.0) : 0);
        o.put("duration_ms", effective.durationMs);
        o.put("file_size", effective.fileSize);
        o.put("album_id", effective.albumId);
        o.put("genre", effective.genre);
        o.put("folder", effective.folder);
        o.put("folder_path", effective.folderPath);
        o.put("relative_path", effective.folderPath);
        o.put("downloaded", downloaded);
        o.put("offline_required", offlineRequired);
        o.put("deviceSource", downloaded ? "musicroad-offline" : (offlineRequired ? "drive-metadata-only" : "mediastore"));
        o.put("nativeMedia", true);
        return o;
    }

    public static MusicTrack fromJson(JSONObject o) {
        MusicTrack t = new MusicTrack(
                o.optString("id", ""),
                o.optString("title", "Sem título"),
                o.optString("artist", "Artista desconhecido"),
                o.optString("album", ""),
                o.optString("source", o.optString("content_uri", "")),
                o.optString("origin", "Dispositivo"),
                o.optString("mime_type", ""),
                o.optLong("duration_ms", o.optLong("duration", 0) * 1000L),
                o.optLong("file_size", 0),
                o.optLong("album_id", 0),
                o.optString("genre", ""),
                o.optString("folder", ""),
                o.optString("folder_path", o.optString("relative_path", ""))
        );
        MusicOfflineStore.remember(t);
        MusicTrack local = MusicOfflineStore.preferLocal(t);
        return local == null ? t : local;
    }
}

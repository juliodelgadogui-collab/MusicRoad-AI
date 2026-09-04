package com.estradaplay.patriota;

import org.json.JSONObject;

final class Track {
    final String id;
    final String title;
    final String artist;
    final String album;
    final String folder;
    final String folderPath;
    final String mime;
    final String remoteSource;
    final String localPath;
    final long size;
    final long durationMs;

    Track(String id, String title, String artist, String album, String folder, String folderPath,
          String mime, String remoteSource, String localPath, long size, long durationMs) {
        this.id = clean(id);
        this.title = fallback(clean(title), "Sem título");
        this.artist = fallback(clean(artist), "Artista desconhecido");
        this.album = clean(album);
        this.folder = fallback(clean(folder), "Sem pasta");
        this.folderPath = fallback(clean(folderPath), this.folder);
        this.mime = clean(mime);
        this.remoteSource = clean(remoteSource);
        this.localPath = clean(localPath);
        this.size = Math.max(0, size);
        this.durationMs = Math.max(0, durationMs);
    }

    static Track fromServer(JSONObject o, ApiClient api) {
        String source = text(o, "source");
        if (source.isEmpty()) source = text(o, "content_uri");
        if (source.isEmpty()) source = text(o, "stream_url");
        String folder = text(o, "folder");
        String folderPath = text(o, "folder_path");
        if (folderPath.isEmpty()) folderPath = text(o, "relative_path");
        if (folder.isEmpty()) folder = folderPath;
        if (folder.isEmpty()) folder = text(o, "artist");
        long duration = o.optLong("duration_ms", 0);
        if (duration <= 0) duration = o.optLong("duration", 0) * 1000L;
        return new Track(text(o, "id"), text(o, "title"), text(o, "artist"), text(o, "album"),
                folder, folderPath, text(o, "mime_type"), api.absolute(source), "",
                o.optLong("file_size", 0), duration);
    }

    static Track fromStored(JSONObject o) {
        return new Track(text(o, "id"), text(o, "title"), text(o, "artist"), text(o, "album"),
                text(o, "folder"), text(o, "folder_path"), text(o, "mime"), text(o, "remote"),
                text(o, "local"), o.optLong("size", 0), o.optLong("duration_ms", 0));
    }

    Track withLocal(String path) {
        return new Track(id, title, artist, album, folder, folderPath, mime, remoteSource, path, size, durationMs);
    }

    JSONObject toStored() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id); o.put("title", title); o.put("artist", artist); o.put("album", album);
            o.put("folder", folder); o.put("folder_path", folderPath); o.put("mime", mime);
            o.put("remote", remoteSource); o.put("local", localPath); o.put("size", size); o.put("duration_ms", durationMs);
        } catch (Exception ignored) {}
        return o;
    }

    boolean downloaded() { return !localPath.isEmpty(); }
    String key() { return id.isEmpty() ? folderPath + "|" + title + "|" + artist : id; }

    private static String text(JSONObject o, String key) {
        if (o == null || o.isNull(key)) return "";
        Object v = o.opt(key);
        return v == null || v == JSONObject.NULL ? "" : clean(String.valueOf(v));
    }

    private static String clean(String s) {
        if (s == null) return "";
        String v = s.trim();
        return "null".equalsIgnoreCase(v) ? "" : v;
    }

    private static String fallback(String value, String fallback) { return value.isEmpty() ? fallback : value; }
}

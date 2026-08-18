package com.musicroad.ai;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class NativeMusicRepository {
    private NativeMusicRepository() {}

    public static String permissionName() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    public static boolean hasPermission(Context context) {
        if (Build.VERSION.SDK_INT < 23) return true;
        return context.checkSelfPermission(permissionName()) == PackageManager.PERMISSION_GRANTED;
    }

    public static List<MusicTrack> scan(Context context) {
        List<MusicTrack> result = new ArrayList<>();
        if (!hasPermission(context)) return result;

        ContentResolver resolver = context.getContentResolver();
        Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection = new String[] {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.MIME_TYPE
        };
        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";
        String sort = MediaStore.Audio.Media.ARTIST + " COLLATE NOCASE ASC, " + MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC";

        try (Cursor c = resolver.query(collection, projection, selection, null, sort)) {
            if (c == null) return result;
            int idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
            int artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
            int albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
            int albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
            int durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
            int sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE);
            int displayCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);
            int mimeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE);

            while (c.moveToNext()) {
                long id = c.getLong(idCol);
                String display = safe(c.getString(displayCol), "Música " + id);
                String title = safe(c.getString(titleCol), stripExtension(display));
                String artist = safe(c.getString(artistCol), "Artista desconhecido");
                if ("<unknown>".equalsIgnoreCase(artist)) artist = "Artista desconhecido";
                String album = safe(c.getString(albumCol), "");
                long albumId = c.getLong(albumIdCol);
                long duration = Math.max(0, c.getLong(durationCol));
                long size = Math.max(0, c.getLong(sizeCol));
                String mime = safe(c.getString(mimeCol), mimeFromName(display));
                Uri uri = ContentUris.withAppendedId(collection, id);
                result.add(new MusicTrack(
                        "android-" + id, title, artist, album, uri.toString(),
                        "Dispositivo", mime, duration, size, albumId
                ));
            }
        } catch (SecurityException ignored) {
        }
        return result;
    }

    public static JSONObject scanAsJson(Context context) {
        JSONObject root = new JSONObject();
        JSONArray arr = new JSONArray();
        try {
            boolean permission = hasPermission(context);
            root.put("ok", permission);
            root.put("permission", permission ? "granted" : "required");
            if (permission) {
                List<MusicTrack> tracks = scan(context);
                for (MusicTrack t : tracks) arr.put(t.toJson());
                root.put("count", tracks.size());
            } else {
                root.put("count", 0);
            }
            root.put("tracks", arr);
        } catch (JSONException ignored) {}
        return root;
    }

    private static String safe(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        return value.trim();
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String mimeFromName(String name) {
        String n = name == null ? "" : name.toLowerCase();
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".m4a") || n.endsWith(".mp4")) return "audio/mp4";
        if (n.endsWith(".aac")) return "audio/aac";
        if (n.endsWith(".flac")) return "audio/flac";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".ogg") || n.endsWith(".opus")) return "audio/ogg";
        return "audio/*";
    }
}

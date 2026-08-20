package com.musicroad.ai;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
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
        List<String> projectionList = new ArrayList<>();
        projectionList.add(MediaStore.Audio.Media._ID);
        projectionList.add(MediaStore.Audio.Media.TITLE);
        projectionList.add(MediaStore.Audio.Media.ARTIST);
        projectionList.add(MediaStore.Audio.Media.ALBUM);
        projectionList.add(MediaStore.Audio.Media.ALBUM_ID);
        projectionList.add(MediaStore.Audio.Media.DURATION);
        projectionList.add(MediaStore.Audio.Media.SIZE);
        projectionList.add(MediaStore.Audio.Media.DISPLAY_NAME);
        projectionList.add(MediaStore.Audio.Media.MIME_TYPE);
        if (Build.VERSION.SDK_INT >= 29) projectionList.add(MediaStore.Audio.Media.RELATIVE_PATH);
        else projectionList.add(MediaStore.Audio.Media.DATA);
        String[] projection = projectionList.toArray(new String[0]);

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
            int relativeCol = Build.VERSION.SDK_INT >= 29 ? c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH) : -1;
            int dataCol = Build.VERSION.SDK_INT < 29 ? c.getColumnIndex(MediaStore.Audio.Media.DATA) : -1;

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

                String folderPath = "";
                if (relativeCol >= 0) folderPath = normalizeFolderPath(c.getString(relativeCol));
                else if (dataCol >= 0) {
                    String data = c.getString(dataCol);
                    if (data != null && !data.isEmpty()) {
                        File parent = new File(data).getParentFile();
                        if (parent != null) folderPath = normalizeFolderPath(parent.getPath());
                    }
                }
                String folder = lastFolder(folderPath);
                String genre = readGenre(context, uri);

                result.add(new MusicTrack(
                        "android-" + id, title, artist, album, uri.toString(),
                        "Celular", mime, duration, size, albumId,
                        genre, folder, folderPath
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

    private static String readGenre(Context context, Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            String genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE);
            return genre == null ? "" : genre.trim();
        } catch (Exception ignored) {
            return "";
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
    }

    private static String normalizeFolderPath(String value) {
        if (value == null) return "";
        String normalized = value.replace('\\', '/').trim();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    private static String lastFolder(String path) {
        if (path == null || path.trim().isEmpty()) return "Sem pasta";
        String normalized = normalizeFolderPath(path);
        int slash = normalized.lastIndexOf('/');
        String folder = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        if (folder.isEmpty()) return "Sem pasta";
        return folder;
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


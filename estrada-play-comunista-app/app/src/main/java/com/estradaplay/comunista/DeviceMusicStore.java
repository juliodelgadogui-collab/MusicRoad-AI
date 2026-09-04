package com.estradaplay.comunista;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.File;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * DEVICE_MUSIC_V2312_MP3_FOLDERS
 * Fast local lookup: only MP3 files already indexed by Android inside the
 * Music/ and Download/ folders are queried. No full-storage traversal and no
 * network/server access. Results stay cached until the user explicitly refreshes.
 */
final class DeviceMusicStore {
    private static final Object SCAN_LOCK = new Object();
    private static ArrayList<Track> cached = new ArrayList<>();
    private static boolean cacheReady;

    private DeviceMusicStore() {}

    static boolean hasPermission(Context context) {
        if (context == null) return false;
        if (Build.VERSION.SDK_INT >= 33) {
            return context.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED;
        }
        if (Build.VERSION.SDK_INT >= 23) {
            return context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    static boolean hasCached() {
        synchronized (SCAN_LOCK) { return cacheReady; }
    }

    static void invalidate() {
        synchronized (SCAN_LOCK) {
            cached.clear();
            cacheReady = false;
        }
    }

    static ArrayList<Track> scan(Context context) {
        return scan(context, false);
    }

    static ArrayList<Track> scan(Context context, boolean force) {
        ArrayList<Track> empty = new ArrayList<>();
        if (context == null || !hasPermission(context)) return empty;

        synchronized (SCAN_LOCK) {
            if (!force && cacheReady) return new ArrayList<>(cached);
            ArrayList<Track> out = queryMp3Folders(context);
            cached = new ArrayList<>(out);
            cacheReady = true;
            return out;
        }
    }

    private static ArrayList<Track> queryMp3Folders(Context context) {
        ArrayList<Track> out = new ArrayList<>();
        ContentResolver resolver = context.getContentResolver();
        Uri base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

        ArrayList<String> projection = new ArrayList<>();
        projection.add(MediaStore.Audio.Media._ID);
        projection.add(MediaStore.Audio.Media.TITLE);
        projection.add(MediaStore.Audio.Media.ARTIST);
        projection.add(MediaStore.Audio.Media.DISPLAY_NAME);
        projection.add(MediaStore.Audio.Media.SIZE);
        if (Build.VERSION.SDK_INT >= 29) projection.add(MediaStore.Audio.Media.RELATIVE_PATH);
        else projection.add(MediaStore.Audio.Media.DATA);

        String selection;
        String[] args;
        String sort;
        if (Build.VERSION.SDK_INT >= 29) {
            selection = MediaStore.Audio.Media.SIZE + ">0 AND (" +
                    MediaStore.Audio.Media.MIME_TYPE + "=? OR " +
                    MediaStore.Audio.Media.DISPLAY_NAME + " LIKE ?) AND (" +
                    MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ? OR " +
                    MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ? OR " +
                    MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ?)";
            args = new String[]{"audio/mpeg", "%.mp3", "Music/%", "Download/%", "Downloads/%"};
            sort = MediaStore.Audio.Media.RELATIVE_PATH + " COLLATE NOCASE ASC, " +
                    MediaStore.Audio.Media.DISPLAY_NAME + " COLLATE NOCASE ASC";
        } else {
            selection = MediaStore.Audio.Media.SIZE + ">0 AND (" +
                    MediaStore.Audio.Media.MIME_TYPE + "=? OR " +
                    MediaStore.Audio.Media.DATA + " LIKE ?) AND (" +
                    MediaStore.Audio.Media.DATA + " LIKE ? OR " +
                    MediaStore.Audio.Media.DATA + " LIKE ?)";
            args = new String[]{"audio/mpeg", "%.mp3", "%/Music/%", "%/Download/%"};
            sort = MediaStore.Audio.Media.DATA + " COLLATE NOCASE ASC";
        }

        try (Cursor c = resolver.query(base, projection.toArray(new String[0]), selection, args, sort)) {
            if (c == null) return out;
            int id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int title = c.getColumnIndex(MediaStore.Audio.Media.TITLE);
            int artist = c.getColumnIndex(MediaStore.Audio.Media.ARTIST);
            int name = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME);
            int size = c.getColumnIndex(MediaStore.Audio.Media.SIZE);
            int path = c.getColumnIndex(Build.VERSION.SDK_INT >= 29 ? MediaStore.Audio.Media.RELATIVE_PATH : MediaStore.Audio.Media.DATA);

            while (c.moveToNext() && out.size() < 12000) {
                long mediaId = c.getLong(id);
                String display = value(c, name);
                if (!isMp3(display)) continue;
                String rawTitle = value(c, title);
                if (rawTitle.isEmpty()) rawTitle = stripExtension(display);
                String rawArtist = value(c, artist);
                String rawPath = value(c, path);
                long bytes = size >= 0 ? Math.max(0L, c.getLong(size)) : 0L;
                if (bytes <= 0) continue;

                Uri uri = ContentUris.withAppendedId(base, mediaId);
                String folder = deviceFolder(rawPath);
                out.add(new Track(
                        "device_" + mediaId,
                        rawTitle,
                        rawArtist,
                        "",
                        folder,
                        folder,
                        "audio/mpeg",
                        "",
                        uri.toString(),
                        bytes,
                        0L));
            }
        } catch (Throwable ignored) {}
        return out;
    }

    static ArrayList<Track> merge(Context context, List<Track> appTracks) {
        return merge(context, appTracks, false);
    }

    static ArrayList<Track> merge(Context context, List<Track> appTracks, boolean forceDeviceScan) {
        LinkedHashMap<String, Track> result = new LinkedHashMap<>();
        if (appTracks != null) {
            for (Track t : appTracks) if (t != null && readable(context, t.localPath)) result.put(signature(t), t);
        }
        if (hasPermission(context)) {
            for (Track t : scan(context, forceDeviceScan)) {
                String signature = signature(t);
                if (!result.containsKey(signature)) result.put(signature, t);
            }
        }
        return new ArrayList<>(result.values());
    }

    static boolean readable(Context context, String source) {
        if (source == null || source.trim().isEmpty()) return false;
        String value = source.trim();
        if (value.startsWith("content://")) {
            try (android.content.res.AssetFileDescriptor fd = context.getContentResolver().openAssetFileDescriptor(Uri.parse(value), "r")) {
                return fd != null;
            } catch (Throwable ignored) { return false; }
        }
        File f = new File(value);
        return f.isFile() && f.length() > 0;
    }

    private static boolean isMp3(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".mp3");
    }

    private static String signature(Track t) {
        return token(t == null ? "" : t.title) + "|" + token(t == null ? "" : t.artist);
    }

    private static String deviceFolder(String raw) {
        String path = raw == null ? "" : raw.replace('\\', '/').trim();
        if (Build.VERSION.SDK_INT < 29 && path.contains("/")) path = path.substring(0, path.lastIndexOf('/'));
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (path.isEmpty()) return "Músicas do celular";

        String lower = path.toLowerCase(Locale.ROOT);
        int music = lower.indexOf("music/");
        if (music >= 0) {
            String sub = path.substring(music + 6);
            return sub.isEmpty() ? "Celular / Música" : "Celular / Música / " + sub;
        }
        int download = lower.indexOf("download/");
        if (download >= 0) {
            String sub = path.substring(download + 9);
            return sub.isEmpty() ? "Celular / Download" : "Celular / Download / " + sub;
        }
        if (lower.endsWith("/music") || "music".equals(lower)) return "Celular / Música";
        if (lower.endsWith("/download") || "download".equals(lower)) return "Celular / Download";
        return "Celular / " + path;
    }

    private static String value(Cursor c, int index) {
        if (index < 0 || c.isNull(index)) return "";
        String v = c.getString(index);
        return v == null || "<unknown>".equalsIgnoreCase(v.trim()) ? "" : v.trim();
    }

    private static String stripExtension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot).trim() : name.trim();
    }

    private static String token(String raw) {
        String n = Normalizer.normalize(raw == null ? "" : raw, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }
}

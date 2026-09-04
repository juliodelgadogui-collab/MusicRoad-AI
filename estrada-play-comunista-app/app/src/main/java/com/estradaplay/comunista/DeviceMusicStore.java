package com.estradaplay.comunista;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.MediaStore;

import java.io.File;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * DEVICE_MUSIC_V2311_FAST_SCAN
 * Reads Android MediaStore locally after explicit audio permission. The scan is
 * filtered at query level and cached briefly so reopening Music does not rescan
 * the whole phone every time. No network/server access is involved.
 */
final class DeviceMusicStore {
    private static final long CACHE_TTL_MS = 30000L;
    private static final Object SCAN_LOCK = new Object();
    private static ArrayList<Track> cached = new ArrayList<>();
    private static long cachedAtElapsed;

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

    static void invalidate() {
        synchronized (SCAN_LOCK) {
            cached.clear();
            cachedAtElapsed = 0L;
        }
    }

    static ArrayList<Track> scan(Context context) {
        return scan(context, false);
    }

    static ArrayList<Track> scan(Context context, boolean force) {
        ArrayList<Track> empty = new ArrayList<>();
        if (context == null || !hasPermission(context)) return empty;

        long now = SystemClock.elapsedRealtime();
        synchronized (SCAN_LOCK) {
            if (!force && !cached.isEmpty() && now - cachedAtElapsed <= CACHE_TTL_MS) {
                return new ArrayList<>(cached);
            }

            ArrayList<Track> out = queryMediaStore(context);
            cached = new ArrayList<>(out);
            cachedAtElapsed = SystemClock.elapsedRealtime();
            return out;
        }
    }

    private static ArrayList<Track> queryMediaStore(Context context) {
        ArrayList<Track> out = new ArrayList<>();
        ContentResolver resolver = context.getContentResolver();
        Uri base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

        ArrayList<String> projection = new ArrayList<>();
        projection.add(MediaStore.Audio.Media._ID);
        projection.add(MediaStore.Audio.Media.TITLE);
        projection.add(MediaStore.Audio.Media.ARTIST);
        projection.add(MediaStore.Audio.Media.ALBUM);
        projection.add(MediaStore.Audio.Media.DISPLAY_NAME);
        projection.add(MediaStore.Audio.Media.MIME_TYPE);
        projection.add(MediaStore.Audio.Media.SIZE);
        projection.add(MediaStore.Audio.Media.DURATION);
        projection.add(MediaStore.Audio.Media.IS_MUSIC);
        if (Build.VERSION.SDK_INT >= 29) projection.add(MediaStore.Audio.Media.RELATIVE_PATH);
        else projection.add(MediaStore.Audio.Media.DATA);

        String selection;
        String[] args;
        if (Build.VERSION.SDK_INT >= 29) {
            selection = MediaStore.Audio.Media.SIZE + ">0 AND (" +
                    MediaStore.Audio.Media.IS_MUSIC + "!=0 OR " +
                    MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ? OR " +
                    MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ?)";
            args = new String[]{"Music/%", "%EstradaPlay%"};
        } else {
            selection = MediaStore.Audio.Media.SIZE + ">0 AND (" +
                    MediaStore.Audio.Media.IS_MUSIC + "!=0 OR " +
                    MediaStore.Audio.Media.DATA + " LIKE ?)";
            args = new String[]{"%/Music/%"};
        }

        try (Cursor c = resolver.query(base, projection.toArray(new String[0]), selection, args,
                MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")) {
            if (c == null) return out;
            int id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int title = c.getColumnIndex(MediaStore.Audio.Media.TITLE);
            int artist = c.getColumnIndex(MediaStore.Audio.Media.ARTIST);
            int album = c.getColumnIndex(MediaStore.Audio.Media.ALBUM);
            int name = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME);
            int mime = c.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE);
            int size = c.getColumnIndex(MediaStore.Audio.Media.SIZE);
            int duration = c.getColumnIndex(MediaStore.Audio.Media.DURATION);
            int music = c.getColumnIndex(MediaStore.Audio.Media.IS_MUSIC);
            int path = c.getColumnIndex(Build.VERSION.SDK_INT >= 29 ? MediaStore.Audio.Media.RELATIVE_PATH : MediaStore.Audio.Media.DATA);

            while (c.moveToNext() && out.size() < 12000) {
                long mediaId = c.getLong(id);
                String rawTitle = value(c, title);
                String display = value(c, name);
                if (rawTitle.isEmpty()) rawTitle = stripExtension(display);
                String rawArtist = value(c, artist);
                String rawAlbum = value(c, album);
                String rawMime = value(c, mime);
                String rawPath = value(c, path);
                long bytes = size >= 0 ? Math.max(0L, c.getLong(size)) : 0L;
                long durationMs = duration >= 0 ? Math.max(0L, c.getLong(duration)) : 0L;
                boolean isMusic = music >= 0 && c.getInt(music) != 0;
                String lowerPath = rawPath.toLowerCase(Locale.ROOT).replace('\\', '/');
                boolean inMusicFolder = lowerPath.contains("/music/") || lowerPath.startsWith("music/") || lowerPath.contains("estradaplay");
                if (!isMusic && !inMusicFolder) continue;
                if (bytes <= 0) continue;

                Uri uri = ContentUris.withAppendedId(base, mediaId);
                String folder = deviceFolder(rawPath);
                out.add(new Track(
                        "device_" + mediaId,
                        rawTitle,
                        rawArtist,
                        rawAlbum,
                        folder,
                        folder,
                        rawMime,
                        "",
                        uri.toString(),
                        bytes,
                        durationMs));
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
        if (music >= 0) path = path.substring(music + 6);
        if (path.isEmpty() || "music".equalsIgnoreCase(path)) return "Músicas do celular";
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

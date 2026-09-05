package com.estradaplay.comunista;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.OperationApplicationException;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.OperationCanceledException;
import android.provider.MediaStore;

import java.io.File;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * PHONE_MP3_V2314_PERMISSION_INDEX
 * Fast phone-music lookup using Android's own audio index. Only MP3 entries are
 * queried. No recursive filesystem scan, no server request and no folder walk.
 * A cancellation signal prevents an OEM media provider from leaving the UI in
 * an endless loading state.
 */
final class PhoneMp3Store {
    private static final Object LOCK = new Object();
    private static final long QUERY_TIMEOUT_MS = 4000L;
    private static final int MAX_TRACKS = 8000;
    private static ArrayList<Track> cached = new ArrayList<>();
    private static boolean cacheReady;
    private static boolean lastTimedOut;

    private PhoneMp3Store() {}

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
        synchronized (LOCK) { return cacheReady; }
    }

    static boolean lastTimedOut() {
        synchronized (LOCK) { return lastTimedOut; }
    }

    static void invalidate() {
        synchronized (LOCK) {
            cached.clear();
            cacheReady = false;
            lastTimedOut = false;
        }
    }

    static ArrayList<Track> scan(Context context, boolean force) {
        if (context == null || !hasPermission(context)) return new ArrayList<>();
        synchronized (LOCK) {
            if (!force && cacheReady) return new ArrayList<>(cached);
        }
        ArrayList<Track> result = queryMp3Index(context);
        synchronized (LOCK) {
            cached = new ArrayList<>(result);
            cacheReady = true;
            return new ArrayList<>(cached);
        }
    }

    private static ArrayList<Track> queryMp3Index(Context context) {
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

        String selection = MediaStore.Audio.Media.SIZE + ">0 AND (" +
                MediaStore.Audio.Media.MIME_TYPE + "=? OR " +
                MediaStore.Audio.Media.DISPLAY_NAME + " LIKE ?)";
        String[] args = new String[]{"audio/mpeg", "%.mp3"};
        String sort = MediaStore.Audio.Media.DISPLAY_NAME + " COLLATE NOCASE ASC";

        CancellationSignal signal = new CancellationSignal();
        Handler main = new Handler(Looper.getMainLooper());
        Runnable cancel = () -> {
            try { signal.cancel(); } catch (Throwable ignored) {}
        };
        main.postDelayed(cancel, QUERY_TIMEOUT_MS);
        boolean timedOut = false;
        try (Cursor c = resolver.query(base, projection.toArray(new String[0]), selection, args, sort, signal)) {
            if (c == null) return out;
            int id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int title = c.getColumnIndex(MediaStore.Audio.Media.TITLE);
            int artist = c.getColumnIndex(MediaStore.Audio.Media.ARTIST);
            int name = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME);
            int size = c.getColumnIndex(MediaStore.Audio.Media.SIZE);
            int path = Build.VERSION.SDK_INT >= 29 ? c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH) : -1;

            while (c.moveToNext() && out.size() < MAX_TRACKS) {
                String display = value(c, name);
                if (!isMp3(display)) continue;
                long bytes = 0L;
                try { if (size >= 0 && !c.isNull(size)) bytes = Math.max(0L, c.getLong(size)); } catch (Throwable ignored) {}
                if (bytes <= 0) continue;
                long mediaId = c.getLong(id);
                String rawTitle = value(c, title);
                if (rawTitle.isEmpty()) rawTitle = stripExtension(display);
                String rawArtist = value(c, artist);
                String folder = path >= 0 ? friendlyFolder(value(c, path)) : "Celular";
                Uri uri = ContentUris.withAppendedId(base, mediaId);
                out.add(new Track(
                        "phone_" + mediaId,
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
        } catch (OperationCanceledException canceled) {
            timedOut = true;
        } catch (SecurityException denied) {
            // Permission state changed while querying; caller will show the permission UI again.
        } catch (Throwable ignored) {
        } finally {
            main.removeCallbacks(cancel);
            synchronized (LOCK) { lastTimedOut = timedOut; }
        }
        return out;
    }

    static ArrayList<Track> merge(Context context, List<Track> appTracks, boolean forcePhoneScan) {
        LinkedHashMap<String, Track> result = new LinkedHashMap<>();
        if (appTracks != null) {
            for (Track t : appTracks) if (t != null && readable(context, t.localPath)) result.put(signature(t), t);
        }
        if (hasPermission(context)) {
            for (Track t : scan(context, forcePhoneScan)) {
                String key = signature(t);
                if (!result.containsKey(key)) result.put(key, t);
            }
        }
        return new ArrayList<>(result.values());
    }

    static boolean readable(Context context, String source) {
        if (context == null || source == null || source.trim().isEmpty()) return false;
        String value = source.trim();
        if (value.startsWith("content://")) {
            try (android.content.res.AssetFileDescriptor fd = context.getContentResolver().openAssetFileDescriptor(Uri.parse(value), "r")) {
                return fd != null;
            } catch (Throwable ignored) { return false; }
        }
        File f = new File(value);
        return f.isFile() && f.length() > 0;
    }

    private static String friendlyFolder(String raw) {
        String p = raw == null ? "" : raw.replace('\\', '/').trim();
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p.isEmpty() ? "Celular" : "Celular / " + p;
    }

    private static String value(Cursor c, int index) {
        if (index < 0 || c.isNull(index)) return "";
        String v = c.getString(index);
        if (v == null) return "";
        v = v.trim();
        return "<unknown>".equalsIgnoreCase(v) ? "" : v;
    }

    private static boolean isMp3(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".mp3");
    }

    private static String stripExtension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot).trim() : name.trim();
    }

    private static String signature(Track t) {
        if (t == null) return "";
        return token(t.title) + "|" + token(t.artist);
    }

    private static String token(String raw) {
        String n = Normalizer.normalize(raw == null ? "" : raw, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }
}

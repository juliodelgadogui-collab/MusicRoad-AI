package com.estradaplay.comunista;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PHONE_MP3_V2315_PERSISTENT_INDEX
 * PHONE_MP3_SAFE_REFRESH_V242
 * PHONE_MP3_INCREMENTAL_REFRESH_V244
 * The Music screen never scans storage. It opens a tiny local SQLite index.
 * MediaStore is touched only on first permission, explicit refresh or after
 * Android reports that the audio collection changed. No server is involved.
 *
 * 2.4.2 made full refresh fail-safe. 2.4.4 additionally handles precise
 * MediaStore item notifications one row at a time: adding, editing or removing
 * one MP3 no longer requires rebuilding the whole phone index. Generic provider
 * notifications still fall back to the existing safe background full refresh.
 */
final class PhoneMp3Store {
    private static final Object LOCK = new Object();
    private static final long QUERY_TIMEOUT_MS = 3000L;
    private static final long EMPTY_CONFIRM_TIMEOUT_MS = 900L;
    private static final long RETRY_BACKOFF_MS = 60_000L;
    private static final int MAX_TRACKS = 10000;
    private static final ExecutorService OBSERVER_IO = Executors.newSingleThreadExecutor();
    private static ArrayList<Track> memory = new ArrayList<>();
    private static boolean memoryReady;
    private static boolean lastTimedOut;
    private static boolean observerInstalled;
    private static long retryAfterMs;

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

    static void installObserver(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            if (observerInstalled) return;
            observerInstalled = true;
        }
        try {
            Context app = context.getApplicationContext();
            app.getContentResolver().registerContentObserver(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    true,
                    new ContentObserver(new Handler(Looper.getMainLooper())) {
                        @Override public void onChange(boolean selfChange) { onAudioChanged(app, null); }
                        @Override public void onChange(boolean selfChange, Uri uri) { onAudioChanged(app, uri); }
                    });
        } catch (Throwable ignored) {
            synchronized (LOCK) { observerInstalled = false; }
        }
    }

    private static void onAudioChanged(Context context, Uri uri) {
        Long mediaId = mediaIdFromUri(uri);
        if (mediaId == null) {
            markBroadChange(context);
            return;
        }
        OBSERVER_IO.execute(() -> refreshSingle(context, mediaId));
    }

    private static void markBroadChange(Context context) {
        try { PhoneMp3Index.get(context).markDirty(); } catch (Throwable ignored) {}
        synchronized (LOCK) {
            memoryReady = false;
            retryAfterMs = 0L;
        }
    }

    private static void refreshSingle(Context context, long mediaId) {
        if (context == null || !hasPermission(context)) {
            markBroadChange(context);
            return;
        }
        PhoneMp3Index db;
        try { db = PhoneMp3Index.get(context); }
        catch (Throwable ignored) { markBroadChange(context); return; }

        boolean wasReady = db.isReady();
        boolean wasDirty = db.isDirty();
        SingleResult result = querySingle(context, mediaId);
        if (!result.success) {
            markBroadChange(context);
            return;
        }

        boolean applied = result.track == null ? db.remove(mediaId) : db.upsert(result.track);
        if (!applied) {
            markBroadChange(context);
            return;
        }

        if (wasReady && !wasDirty) db.markFresh();
        else db.markDirty();

        synchronized (LOCK) {
            if (memoryReady) {
                String key = "phone_" + mediaId;
                for (int i = memory.size() - 1; i >= 0; i--) {
                    Track t = memory.get(i);
                    if (t != null && key.equals(t.id)) memory.remove(i);
                }
                if (result.track != null) memory.add(result.track);
                memory.sort((a, b) -> safeText(a == null ? null : a.title).compareToIgnoreCase(safeText(b == null ? null : b.title)));
            }
            retryAfterMs = 0L;
            lastTimedOut = false;
        }
    }

    static boolean needsRefresh(Context context) {
        if (!hasPermission(context)) return false;
        synchronized (LOCK) {
            if (System.currentTimeMillis() < retryAfterMs) return false;
        }
        try {
            PhoneMp3Index db = PhoneMp3Index.get(context);
            return !db.isReady() || db.isDirty();
        } catch (Throwable ignored) { return true; }
    }

    static boolean lastTimedOut() {
        synchronized (LOCK) { return lastTimedOut; }
    }

    static void invalidate(Context context) {
        if (context != null) {
            try { PhoneMp3Index.get(context).markDirty(); } catch (Throwable ignored) {}
        }
        synchronized (LOCK) {
            memoryReady = false;
            lastTimedOut = false;
            retryAfterMs = 0L;
        }
    }

    static ArrayList<Track> cached(Context context) {
        if (context == null) return new ArrayList<>();
        synchronized (LOCK) {
            if (memoryReady) return new ArrayList<>(memory);
        }
        ArrayList<Track> loaded;
        try { loaded = new ArrayList<>(PhoneMp3Index.get(context).load()); }
        catch (Throwable ignored) { loaded = new ArrayList<>(); }
        synchronized (LOCK) {
            memory = new ArrayList<>(loaded);
            memoryReady = true;
        }
        return loaded;
    }

    static ArrayList<Track> refresh(Context context) {
        if (context == null || !hasPermission(context)) return cached(context);
        installObserver(context);

        ArrayList<Track> previous = cached(context);
        QueryResult result = queryMp3Index(context);
        synchronized (LOCK) { lastTimedOut = result.timedOut; }

        if (!result.success) {
            scheduleRetry();
            return previous;
        }

        if (result.tracks.isEmpty() && !previous.isEmpty() && !confirmEmpty(context)) {
            scheduleRetry();
            return previous;
        }

        try {
            PhoneMp3Index.get(context).replace(result.tracks);
        } catch (Throwable ignored) {
            scheduleRetry();
            return previous;
        }

        synchronized (LOCK) {
            memory = new ArrayList<>(result.tracks);
            memoryReady = true;
            retryAfterMs = 0L;
            lastTimedOut = false;
        }
        return new ArrayList<>(result.tracks);
    }

    private static void scheduleRetry() {
        synchronized (LOCK) {
            retryAfterMs = System.currentTimeMillis() + RETRY_BACKOFF_MS;
            memoryReady = true;
        }
    }

    private static QueryResult queryMp3Index(Context context) {
        ArrayList<Track> out = new ArrayList<>();
        ContentResolver resolver = context.getContentResolver();
        Uri base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection = projection();
        String selection = MediaStore.Audio.Media.SIZE + ">0 AND " + MediaStore.Audio.Media.DISPLAY_NAME + " LIKE ?";
        String[] args = new String[]{"%.mp3"};

        CancellationSignal signal = new CancellationSignal();
        Handler main = new Handler(Looper.getMainLooper());
        Runnable cancel = () -> { try { signal.cancel(); } catch (Throwable ignored) {} };
        main.postDelayed(cancel, QUERY_TIMEOUT_MS);
        boolean timedOut = false;
        boolean success = false;
        try (Cursor c = resolver.query(base, projection, selection, args, null, signal)) {
            if (c != null) {
                RowColumns columns = new RowColumns(c);
                while (c.moveToNext() && out.size() < MAX_TRACKS) {
                    Track track = rowTrack(c, columns, base);
                    if (track != null) out.add(track);
                }
                success = true;
            }
        } catch (OperationCanceledException canceled) {
            timedOut = true;
        } catch (SecurityException ignored) {
            success = false;
        } catch (Throwable ignored) {
            success = false;
        } finally {
            main.removeCallbacks(cancel);
        }
        return new QueryResult(out, success, timedOut);
    }

    private static SingleResult querySingle(Context context, long mediaId) {
        ContentResolver resolver = context.getContentResolver();
        Uri base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String selection = MediaStore.Audio.Media._ID + "=? AND " + MediaStore.Audio.Media.SIZE + ">0 AND " + MediaStore.Audio.Media.DISPLAY_NAME + " LIKE ?";
        String[] args = new String[]{Long.toString(mediaId), "%.mp3"};
        try (Cursor c = resolver.query(base, projection(), selection, args, null)) {
            if (c == null) return new SingleResult(null, false);
            if (!c.moveToFirst()) return new SingleResult(null, true);
            return new SingleResult(rowTrack(c, new RowColumns(c), base), true);
        } catch (SecurityException ignored) {
            return new SingleResult(null, false);
        } catch (Throwable ignored) {
            return new SingleResult(null, false);
        }
    }

    private static boolean confirmEmpty(Context context) {
        ContentResolver resolver = context.getContentResolver();
        Uri base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String selection = MediaStore.Audio.Media.SIZE + ">0 AND " + MediaStore.Audio.Media.DISPLAY_NAME + " LIKE ?";
        String[] args = new String[]{"%.mp3"};
        CancellationSignal signal = new CancellationSignal();
        Handler main = new Handler(Looper.getMainLooper());
        Runnable cancel = () -> { try { signal.cancel(); } catch (Throwable ignored) {} };
        main.postDelayed(cancel, EMPTY_CONFIRM_TIMEOUT_MS);
        try (Cursor c = resolver.query(base, new String[]{MediaStore.Audio.Media._ID}, selection, args, null, signal)) {
            return c != null && !c.moveToFirst();
        } catch (Throwable ignored) {
            return false;
        } finally {
            main.removeCallbacks(cancel);
        }
    }

    static ArrayList<Track> mergeCached(Context context, List<Track> appTracks) {
        LinkedHashMap<String, Track> result = new LinkedHashMap<>();
        if (appTracks != null) {
            for (Track t : appTracks) if (t != null) result.put(signature(t), t);
        }
        if (hasPermission(context)) {
            for (Track t : cached(context)) {
                String key = signature(t);
                if (!result.containsKey(key)) result.put(key, t);
            }
        }
        return new ArrayList<>(result.values());
    }

    static ArrayList<Track> mergeFresh(Context context, List<Track> appTracks) {
        refresh(context);
        return mergeCached(context, appTracks);
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

    private static String[] projection() {
        ArrayList<String> p = new ArrayList<>();
        p.add(MediaStore.Audio.Media._ID);
        p.add(MediaStore.Audio.Media.TITLE);
        p.add(MediaStore.Audio.Media.ARTIST);
        p.add(MediaStore.Audio.Media.DISPLAY_NAME);
        p.add(MediaStore.Audio.Media.SIZE);
        if (Build.VERSION.SDK_INT >= 29) p.add(MediaStore.Audio.Media.RELATIVE_PATH);
        return p.toArray(new String[0]);
    }

    private static Track rowTrack(Cursor c, RowColumns columns, Uri base) {
        if (c == null || columns == null) return null;
        String display = value(c, columns.name);
        if (!isMp3(display)) return null;
        long mediaId;
        try { mediaId = c.getLong(columns.id); } catch (Throwable ignored) { return null; }
        long bytes = 0L;
        try { if (columns.size >= 0 && !c.isNull(columns.size)) bytes = Math.max(0L, c.getLong(columns.size)); } catch (Throwable ignored) {}
        String rawTitle = value(c, columns.title);
        if (rawTitle.isEmpty()) rawTitle = stripExtension(display);
        String rawArtist = value(c, columns.artist);
        String folder = columns.path >= 0 ? friendlyFolder(value(c, columns.path)) : "Celular";
        Uri uri = ContentUris.withAppendedId(base, mediaId);
        return new Track("phone_" + mediaId, rawTitle, rawArtist, "", folder, folder,
                "audio/mpeg", "", uri.toString(), bytes, 0L);
    }

    private static Long mediaIdFromUri(Uri uri) {
        if (uri == null) return null;
        String base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.toString();
        String raw = uri.toString();
        if (!raw.startsWith(base + "/")) return null;
        String last = uri.getLastPathSegment();
        if (last == null || last.trim().isEmpty()) return null;
        try { return Long.parseLong(last.trim()); } catch (Throwable ignored) { return null; }
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

    private static String safeText(String value) { return value == null ? "" : value; }

    private static final class RowColumns {
        final int id, title, artist, name, size, path;
        RowColumns(Cursor c) {
            id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            title = c.getColumnIndex(MediaStore.Audio.Media.TITLE);
            artist = c.getColumnIndex(MediaStore.Audio.Media.ARTIST);
            name = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME);
            size = c.getColumnIndex(MediaStore.Audio.Media.SIZE);
            path = Build.VERSION.SDK_INT >= 29 ? c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH) : -1;
        }
    }

    private static final class QueryResult {
        final ArrayList<Track> tracks;
        final boolean success;
        final boolean timedOut;

        QueryResult(ArrayList<Track> tracks, boolean success, boolean timedOut) {
            this.tracks = tracks == null ? new ArrayList<>() : tracks;
            this.success = success;
            this.timedOut = timedOut;
        }
    }

    private static final class SingleResult {
        final Track track;
        final boolean success;
        SingleResult(Track track, boolean success) {
            this.track = track;
            this.success = success;
        }
    }
}

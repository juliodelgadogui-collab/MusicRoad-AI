package com.estradaplay.comunista;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;

/**
 * SHARED_MUSIC_PERSIST_V2310
 * Keeps an additional, user-visible copy of Estrada Play downloads in
 * Music/EstradaPlay through MediaStore. The shared copy survives app uninstall
 * and is intentionally never deleted by app cache cleanup.
 */
final class SharedMusicPublisher {
    private static final String PREF = "epc_shared_music_v2310";
    private static final Object LOCK = new Object();

    private SharedMusicPublisher() {}

    static int publishMissingFromApp(Context context) {
        if (context == null || Build.VERSION.SDK_INT < 29) return 0;
        try {
            LibraryStore store = new LibraryStore(context);
            return publishAll(context, store.downloadedTracks());
        } catch (Throwable ignored) { return 0; }
    }

    static int publishAll(Context context, List<Track> tracks) {
        if (context == null || tracks == null || Build.VERSION.SDK_INT < 29) return 0;
        int published = 0;
        synchronized (LOCK) {
            for (Track track : tracks) {
                if (track == null || track.localPath.startsWith("content://")) continue;
                File source = track.localPath.isEmpty() ? null : new File(track.localPath);
                if (source == null || !source.isFile() || source.length() <= 0) continue;
                if (publishTrackLocked(context, track, source) != null) published++;
            }
        }
        return published;
    }

    static Uri publishTrack(Context context, Track track, File source) {
        if (context == null || track == null || source == null || Build.VERSION.SDK_INT < 29) return null;
        if (!source.isFile() || source.length() <= 0) return null;
        synchronized (LOCK) { return publishTrackLocked(context, track, source); }
    }

    private static Uri publishTrackLocked(Context context, Track track, File source) {
        ContentResolver resolver = context.getContentResolver();
        SharedPreferences prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String key = "uri_" + Integer.toHexString(track.key().hashCode());
        String remembered = prefs.getString(key, "");
        if (remembered != null && !remembered.isEmpty()) {
            Uri existing = Uri.parse(remembered);
            if (uriReadable(resolver, existing)) return existing;
            prefs.edit().remove(key).apply();
        }

        String folder = safeFolder(track.folderPath.isEmpty() ? track.folder : track.folderPath);
        String relative = Environment.DIRECTORY_MUSIC + "/EstradaPlay/" + (folder.isEmpty() ? "" : folder + "/");
        String display = publicName(track, source);
        Uri collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);

        Uri existing = findExisting(resolver, collection, relative, display);
        if (existing != null && uriReadable(resolver, existing)) {
            prefs.edit().putString(key, existing.toString()).apply();
            return existing;
        }

        ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Media.DISPLAY_NAME, display);
        values.put(MediaStore.Audio.Media.TITLE, track.title);
        values.put(MediaStore.Audio.Media.ARTIST, track.artist);
        if (track.album != null && !track.album.trim().isEmpty()) values.put(MediaStore.Audio.Media.ALBUM, track.album.trim());
        values.put(MediaStore.Audio.Media.MIME_TYPE, mime(track, source));
        values.put(MediaStore.Audio.Media.RELATIVE_PATH, relative);
        values.put(MediaStore.Audio.Media.IS_MUSIC, 1);
        values.put(MediaStore.Audio.Media.IS_PENDING, 1);

        Uri created = null;
        try {
            created = resolver.insert(collection, values);
            if (created == null) return null;
            try (FileInputStream in = new FileInputStream(source); OutputStream out = resolver.openOutputStream(created, "w")) {
                if (out == null) throw new IllegalStateException("MediaStore sem saída");
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.flush();
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.Audio.Media.IS_PENDING, 0);
            resolver.update(created, ready, null, null);
            if (!uriReadable(resolver, created)) throw new IllegalStateException("Cópia pública inválida");
            prefs.edit().putString(key, created.toString()).apply();
            return created;
        } catch (Throwable error) {
            if (created != null) {
                try { resolver.delete(created, null, null); } catch (Throwable ignored) {}
            }
            return null;
        }
    }

    private static Uri findExisting(ContentResolver resolver, Uri collection, String relative, String display) {
        try (Cursor c = resolver.query(collection,
                new String[]{MediaStore.Audio.Media._ID, MediaStore.Audio.Media.SIZE},
                MediaStore.Audio.Media.RELATIVE_PATH + "=? AND " + MediaStore.Audio.Media.DISPLAY_NAME + "=?",
                new String[]{relative, display}, null)) {
            if (c != null && c.moveToFirst()) {
                int id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int size = c.getColumnIndex(MediaStore.Audio.Media.SIZE);
                if (size < 0 || c.getLong(size) > 0) return android.content.ContentUris.withAppendedId(collection, c.getLong(id));
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean uriReadable(ContentResolver resolver, Uri uri) {
        try (android.content.res.AssetFileDescriptor fd = resolver.openAssetFileDescriptor(uri, "r")) {
            return fd != null && fd.getLength() != 0;
        } catch (Throwable ignored) { return false; }
    }

    private static String publicName(Track track, File source) {
        String token = track.id == null ? "" : track.id.replaceAll("[^A-Za-z0-9_-]+", "");
        if (token.isEmpty()) token = Integer.toHexString(track.key().hashCode());
        if (token.length() > 24) token = token.substring(0, 24);
        String title = safeName(track.title);
        if (title.isEmpty()) title = "Musica Estrada Play";
        return token + " - " + title + extension(source.getName(), track.mime);
    }

    private static String safeFolder(String raw) {
        String source = raw == null ? "" : raw.replace('\\', '/');
        StringBuilder out = new StringBuilder();
        for (String part : source.split("/+")) {
            String clean = safeName(part);
            if (clean.isEmpty()) continue;
            if (out.length() > 0) out.append('/');
            out.append(clean);
            if (out.length() >= 100) break;
        }
        return out.toString();
    }

    private static String safeName(String raw) {
        if (raw == null) return "";
        String v = raw.replaceAll("[\\\\/:*?\"<>|]+", " ").replaceAll("\\s+", " ").trim();
        if (v.length() > 70) v = v.substring(0, 70).trim();
        return v;
    }

    private static String extension(String name, String mime) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        for (String ext : new String[]{".mp3", ".m4a", ".aac", ".flac", ".wav", ".ogg", ".opus"}) if (n.endsWith(ext)) return ext;
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (m.contains("flac")) return ".flac";
        if (m.contains("wav")) return ".wav";
        if (m.contains("ogg")) return ".ogg";
        if (m.contains("opus")) return ".opus";
        if (m.contains("mp4") || m.contains("m4a")) return ".m4a";
        if (m.contains("aac")) return ".aac";
        return ".mp3";
    }

    private static String mime(Track track, File source) {
        if (track.mime != null && track.mime.startsWith("audio/")) return track.mime;
        String ext = extension(source.getName(), "");
        if (".m4a".equals(ext)) return "audio/mp4";
        if (".aac".equals(ext)) return "audio/aac";
        if (".flac".equals(ext)) return "audio/flac";
        if (".wav".equals(ext)) return "audio/wav";
        if (".ogg".equals(ext)) return "audio/ogg";
        if (".opus".equals(ext)) return "audio/opus";
        return "audio/mpeg";
    }
}

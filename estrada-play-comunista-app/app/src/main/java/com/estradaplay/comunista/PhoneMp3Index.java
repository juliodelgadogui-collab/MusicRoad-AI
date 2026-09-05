package com.estradaplay.comunista;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * PHONE_MP3_LOCAL_DB_V2315
 * PHONE_MP3_INCREMENTAL_DB_V244
 * Tiny local-only database used as the source of truth for phone MP3s.
 * Opening the Music screen reads this DB only; MediaStore is consulted only by
 * an explicit/background refresh and never blocks the UI.
 *
 * 2.4.4 also supports single-row upsert/removal so a precise MediaStore change
 * can update only that song instead of rebuilding the whole phone index.
 */
final class PhoneMp3Index extends SQLiteOpenHelper {
    private static final String DB = "epc_phone_mp3_v2315.db";
    private static final int VERSION = 1;
    private static final String PREFS = "epc_phone_mp3_index_v2315";
    private static final String KEY_READY = "ready";
    private static final String KEY_DIRTY = "dirty";
    private static final String KEY_UPDATED = "updated_at";

    private static volatile PhoneMp3Index instance;
    private final Context app;

    private PhoneMp3Index(Context context) {
        super(context.getApplicationContext(), DB, null, VERSION);
        app = context.getApplicationContext();
    }

    static PhoneMp3Index get(Context context) {
        PhoneMp3Index local = instance;
        if (local == null) {
            synchronized (PhoneMp3Index.class) {
                local = instance;
                if (local == null) instance = local = new PhoneMp3Index(context);
            }
        }
        return local;
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS phone_mp3 (" +
                "media_id INTEGER PRIMARY KEY," +
                "title TEXT NOT NULL," +
                "artist TEXT NOT NULL," +
                "folder TEXT NOT NULL," +
                "uri TEXT NOT NULL," +
                "bytes INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_phone_mp3_title ON phone_mp3(title COLLATE NOCASE)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS phone_mp3");
        onCreate(db);
    }

    List<Track> load() {
        ArrayList<Track> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(
                "phone_mp3",
                new String[]{"media_id","title","artist","folder","uri","bytes"},
                null, null, null, null,
                "title COLLATE NOCASE ASC",
                "10000")) {
            int id = c.getColumnIndexOrThrow("media_id");
            int title = c.getColumnIndexOrThrow("title");
            int artist = c.getColumnIndexOrThrow("artist");
            int folder = c.getColumnIndexOrThrow("folder");
            int uri = c.getColumnIndexOrThrow("uri");
            int bytes = c.getColumnIndexOrThrow("bytes");
            while (c.moveToNext()) {
                long mediaId = c.getLong(id);
                String t = text(c, title);
                String a = text(c, artist);
                String f = text(c, folder);
                String u = text(c, uri);
                long b = Math.max(0L, c.getLong(bytes));
                if (u.isEmpty()) continue;
                out.add(new Track("phone_" + mediaId, t, a, "", f, f,
                        "audio/mpeg", "", u, b, 0L));
            }
        } catch (Throwable ignored) {}
        return out;
    }

    void replace(List<Track> tracks) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("phone_mp3", null, null);
            if (tracks != null) {
                for (Track t : tracks) putTrack(db, t);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        markFresh();
    }

    boolean upsert(Track track) {
        long mediaId = mediaId(track);
        if (mediaId < 0 || track == null || track.localPath == null || !track.localPath.startsWith("content://")) return false;
        SQLiteDatabase db = getWritableDatabase();
        return putTrack(db, track) >= 0;
    }

    boolean remove(long mediaId) {
        if (mediaId < 0) return false;
        try {
            getWritableDatabase().delete("phone_mp3", "media_id=?", new String[]{Long.toString(mediaId)});
            return true;
        } catch (Throwable ignored) { return false; }
    }

    boolean isReady() { return prefs().getBoolean(KEY_READY, false); }
    boolean isDirty() { return prefs().getBoolean(KEY_DIRTY, true); }
    long updatedAt() { return prefs().getLong(KEY_UPDATED, 0L); }
    void markDirty() { prefs().edit().putBoolean(KEY_DIRTY, true).apply(); }
    void markFresh() { prefs().edit().putBoolean(KEY_READY, true).putBoolean(KEY_DIRTY, false).putLong(KEY_UPDATED, System.currentTimeMillis()).apply(); }

    private long putTrack(SQLiteDatabase db, Track t) {
        if (db == null || t == null || t.localPath == null || !t.localPath.startsWith("content://")) return -1L;
        long mediaId = mediaId(t);
        if (mediaId < 0) return -1L;
        ContentValues v = new ContentValues();
        v.put("media_id", mediaId);
        v.put("title", safe(t.title));
        v.put("artist", safe(t.artist));
        v.put("folder", safe(t.folder));
        v.put("uri", t.localPath);
        v.put("bytes", Math.max(0L, t.size));
        return db.insertWithOnConflict("phone_mp3", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private SharedPreferences prefs() { return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    private static long mediaId(Track t) {
        if (t == null || t.id == null || !t.id.startsWith("phone_")) return -1L;
        try { return Long.parseLong(t.id.substring(6)); } catch (Throwable ignored) { return -1L; }
    }

    private static String text(Cursor c, int index) {
        if (index < 0 || c.isNull(index)) return "";
        String v = c.getString(index);
        return v == null ? "" : v.trim();
    }

    private static String safe(String v) { return v == null ? "" : v.trim(); }
}

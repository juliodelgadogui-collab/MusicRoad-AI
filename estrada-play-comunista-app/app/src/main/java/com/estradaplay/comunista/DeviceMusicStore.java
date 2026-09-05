package com.estradaplay.comunista;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * DEVICE_MUSIC_V2313_FOLDER_PICKER
 * The user chooses one folder through Android's Storage Access Framework.
 * Estrada Play then reads only .mp3 files from that folder/subfolders using the
 * persisted tree URI. No MediaStore index, no whole-phone scan and no server.
 */
final class DeviceMusicStore {
    private static final String PREFS = "epc_music_folder_v2313";
    private static final String KEY_TREE = "tree_uri";
    private static final Object SCAN_LOCK = new Object();
    private static final int MAX_DEPTH = 8;
    private static final int MAX_FOLDERS = 800;
    private static final int MAX_TRACKS = 5000;

    private static ArrayList<Track> cached = new ArrayList<>();
    private static String cachedTree = "";
    private static boolean cacheReady;

    private DeviceMusicStore() {}

    static boolean hasSelectedFolder(Context context) {
        return selectedFolderUri(context) != null;
    }

    static Uri selectedFolderUri(Context context) {
        if (context == null) return null;
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TREE, "");
        if (raw == null || raw.trim().isEmpty()) return null;
        try { return Uri.parse(raw.trim()); } catch (Throwable ignored) { return null; }
    }

    static boolean saveSelectedFolder(Context context, Uri treeUri, int resultFlags) {
        if (context == null || treeUri == null) return false;
        try {
            int takeFlags = resultFlags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if ((takeFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) takeFlags |= Intent.FLAG_GRANT_READ_URI_PERMISSION;
            try { context.getContentResolver().takePersistableUriPermission(treeUri, takeFlags); }
            catch (Throwable ignored) {
                try { context.getContentResolver().takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                catch (Throwable ignored2) {}
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TREE, treeUri.toString()).apply();
            invalidate();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void clearSelectedFolder(Context context) {
        Uri uri = selectedFolderUri(context);
        if (context != null) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_TREE).apply();
        if (context != null && uri != null) {
            try { context.getContentResolver().releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Throwable ignored) {}
        }
        invalidate();
    }

    static boolean hasCached() {
        synchronized (SCAN_LOCK) { return cacheReady; }
    }

    static void invalidate() {
        synchronized (SCAN_LOCK) {
            cached.clear();
            cachedTree = "";
            cacheReady = false;
        }
    }

    static ArrayList<Track> scan(Context context) { return scan(context, false); }

    static ArrayList<Track> scan(Context context, boolean force) {
        Uri treeUri = selectedFolderUri(context);
        if (context == null || treeUri == null) return new ArrayList<>();
        String treeKey = treeUri.toString();

        synchronized (SCAN_LOCK) {
            if (!force && cacheReady && treeKey.equals(cachedTree)) return new ArrayList<>(cached);
        }

        ArrayList<Track> out = querySelectedFolder(context, treeUri);
        synchronized (SCAN_LOCK) {
            cached = new ArrayList<>(out);
            cachedTree = treeKey;
            cacheReady = true;
        }
        return out;
    }

    private static ArrayList<Track> querySelectedFolder(Context context, Uri treeUri) {
        ArrayList<Track> out = new ArrayList<>();
        ContentResolver resolver = context.getContentResolver();
        String rootId;
        try { rootId = DocumentsContract.getTreeDocumentId(treeUri); }
        catch (Throwable ignored) { return out; }

        String rootName = queryDisplayName(resolver, DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId));
        if (rootName.isEmpty()) rootName = "Pasta selecionada";

        ArrayDeque<FolderNode> queue = new ArrayDeque<>();
        queue.add(new FolderNode(rootId, rootName, 0));
        int visitedFolders = 0;
        String[] projection = new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
        };

        while (!queue.isEmpty() && out.size() < MAX_TRACKS && visitedFolders < MAX_FOLDERS) {
            FolderNode node = queue.removeFirst();
            visitedFolders++;
            Uri children;
            try { children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, node.documentId); }
            catch (Throwable ignored) { continue; }

            try (Cursor c = resolver.query(children, projection, null, null, null)) {
                if (c == null) continue;
                int idCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int nameCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int mimeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
                int sizeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);

                while (c.moveToNext() && out.size() < MAX_TRACKS) {
                    String documentId = value(c, idCol);
                    String display = value(c, nameCol);
                    String mime = value(c, mimeCol);
                    if (documentId.isEmpty()) continue;

                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        if (node.depth < MAX_DEPTH) {
                            String nextPath = node.path.isEmpty() ? display : node.path + " / " + display;
                            queue.addLast(new FolderNode(documentId, nextPath, node.depth + 1));
                        }
                        continue;
                    }

                    if (!isMp3(display)) continue;
                    long bytes = 0L;
                    try { if (sizeCol >= 0 && !c.isNull(sizeCol)) bytes = Math.max(0L, c.getLong(sizeCol)); }
                    catch (Throwable ignored) {}
                    Uri documentUri;
                    try { documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId); }
                    catch (Throwable ignored) { continue; }

                    String folder = "Celular / " + (node.path.isEmpty() ? rootName : node.path);
                    out.add(new Track(
                            "tree_" + documentId,
                            stripExtension(display),
                            "MP3 do celular",
                            "",
                            folder,
                            folder,
                            "audio/mpeg",
                            "",
                            documentUri.toString(),
                            bytes,
                            0L));
                }
            } catch (SecurityException denied) {
                clearSelectedFolder(context);
                return new ArrayList<>();
            } catch (Throwable ignored) {}
        }

        Collections.sort(out, Comparator
                .comparing((Track t) -> t.folderPath == null ? "" : t.folderPath, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(t -> t.title == null ? "" : t.title, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    static ArrayList<Track> merge(Context context, List<Track> appTracks) { return merge(context, appTracks, false); }

    static ArrayList<Track> merge(Context context, List<Track> appTracks, boolean forceFolderScan) {
        LinkedHashMap<String, Track> result = new LinkedHashMap<>();
        if (appTracks != null) {
            for (Track t : appTracks) if (t != null && readable(context, t.localPath)) result.put(signature(t), t);
        }
        if (hasSelectedFolder(context)) {
            for (Track t : scan(context, forceFolderScan)) {
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

    private static String queryDisplayName(ContentResolver resolver, Uri documentUri) {
        if (resolver == null || documentUri == null) return "";
        String[] projection = new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME};
        try (Cursor c = resolver.query(documentUri, projection, null, null, null)) {
            if (c != null && c.moveToFirst()) return value(c, c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME));
        } catch (Throwable ignored) {}
        return "";
    }

    private static boolean isMp3(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".mp3");
    }

    private static String signature(Track t) {
        if (t == null) return "";
        String title = token(t.title);
        if (t.id != null && t.id.startsWith("tree_")) return title;
        return title + "|" + token(t.artist);
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

    private static final class FolderNode {
        final String documentId;
        final String path;
        final int depth;
        FolderNode(String documentId, String path, int depth) {
            this.documentId = documentId;
            this.path = path == null ? "" : path;
            this.depth = depth;
        }
    }
}

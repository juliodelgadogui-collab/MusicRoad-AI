package com.estradaplay.comunista;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DownloadService extends Service {
    static final String ACTION_START = "com.estradaplay.comunista.DOWNLOAD_START";
    static final String ACTION_CANCEL = "com.estradaplay.comunista.DOWNLOAD_CANCEL";
    static final String ACTION_STATE = "com.estradaplay.comunista.DOWNLOAD_STATE";
    static final String EXTRA_FOLDERS = "folders";
    private static final String CHANNEL = "estradaplay_downloads";
    private static final int NOTIFICATION_ID = 4401;
    private static final int CANCEL_REQUEST_CODE = 4402;
    private static final long PROGRESS_INTERVAL_MS = 350L;
    private static final long SPACE_CHECK_STEP = 8L * 1024L * 1024L;

    // MUSIC_DOWNLOAD_ENGINE_V241: foreground download engine is resumable, cancellable and byte-aware.
    // MUSIC_DOWNLOAD_TIMEOUT_V251: Android 15 dataSync timeout cancels safely and preserves .part for resume.
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile boolean running;
    private volatile boolean cancelRequested;
    private volatile HttpURLConnection activeConnection;
    private LibraryStore store;
    private ApiClient api;

    @Override public void onCreate() {
        super.onCreate();
        store = new LibraryStore(this);
        api = new ApiClient(this);
        createChannel();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_CANCEL.equals(action)) {
            cancelRequested = true;
            running = false;
            HttpURLConnection c = activeConnection;
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) {}
            if (intent != null) publish(false, 0, 0, 0, "Download cancelado");
            if (activeConnection == null) stopSelf();
            return START_NOT_STICKY;
        }
        if (intent == null || !ACTION_START.equals(action) || running) return START_NOT_STICKY;
        Set<String> folders = parseFolders(intent.getStringExtra(EXTRA_FOLDERS));
        if (folders.isEmpty()) { stopSelf(); return START_NOT_STICKY; }
        cancelRequested = false;
        running = true;
        startForeground(NOTIFICATION_ID, notification("Preparando downloads", 0, 0, true));
        io.execute(() -> runDownload(folders));
        return START_NOT_STICKY;
    }

    private void runDownload(Set<String> folders) {
        List<Track> tracks = store.tracksForFolders(folders);
        int total = tracks.size(), done = 0, failed = 0;
        publish(true, total, done, failed, total == 0 ? "Tudo já está offline" : "Iniciando…");
        for (Track track : tracks) {
            if (!running || cancelRequested) break;
            publish(true, total, done, failed, track.title);
            updateNotification(notification("Baixando: " + track.title, done, total, false));
            try {
                download(track, total, done, failed);
                done++;
            } catch (CancelledDownload e) {
                break;
            } catch (Exception e) {
                if (cancelRequested) break;
                failed++;
            }
            publish(true, total, done, failed, track.title);
        }

        boolean cancelled = cancelRequested;
        running = false;
        activeConnection = null;
        if (done > 0 || !store.downloadedTracks().isEmpty()) store.setSetupDone(true);

        if (cancelled) {
            publish(false, total, done, failed, "Download cancelado · o progresso parcial foi preservado");
            updateNotification(notification("Download cancelado · você pode continuar depois", done + failed, Math.max(1, total), false));
        } else {
            publish(false, total, done, failed, failed == 0 ? "Biblioteca offline pronta" : "Concluído com " + failed + " falha(s)");
            updateNotification(notification(failed == 0 ? "Músicas prontas para usar offline" : "Downloads concluídos · " + failed + " falharam", total, total, false));
        }
        stopForeground(false);
        stopSelf();
    }

    private void download(Track track, int batchTotal, int batchDone, int batchFailed) throws Exception {
        if (cancelRequested) throw new CancelledDownload();
        if (track == null || track.remoteSource.isEmpty()) throw new Exception("Fonte ausente");
        File target = store.targetFile(track);
        if (target.isFile() && target.length() > 0) {
            store.saveDownloaded(track, target);
            SharedMusicPublisher.publishTrack(this, track, target);
            return;
        }
        File dir = target.getParentFile();
        if (dir == null || (!dir.exists() && !dir.mkdirs())) throw new Exception("Pasta indisponível");
        File part = new File(dir, target.getName() + ".part");

        String primary = track.remoteSource;
        String alternate = "";
        if (primary.contains("/api/drive_stream.php")) {
            try {
                ApiClient.Response r = api.get(primary + (primary.contains("?") ? "&" : "?") + "resolve=1");
                JSONObject j = r.json();
                if (r.ok() && j.optBoolean("ok", false)) {
                    String direct = j.optString("direct_url", "").trim();
                    alternate = j.optString("alternate_url", "").trim();
                    if (direct.startsWith("https://")) primary = direct;
                }
            } catch (Exception ignored) {}
        }

        Exception first = null;
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (primary != null && !primary.trim().isEmpty()) candidates.add(primary.trim());
        if (alternate != null && !alternate.trim().isEmpty()) candidates.add(alternate.trim());
        if (track.remoteSource != null && !track.remoteSource.trim().isEmpty()) candidates.add(track.remoteSource.trim());

        for (String candidate : candidates) {
            if (cancelRequested) throw new CancelledDownload();
            try {
                fetchToFile(candidate, part, track.title, batchTotal, batchDone, batchFailed);
                first = null;
                break;
            } catch (CancelledDownload e) {
                throw e;
            } catch (Exception e) {
                if (cancelRequested) throw new CancelledDownload();
                if (first == null) first = e;
                // Keep .part: another endpoint or the next app session can resume it with HTTP Range.
            }
        }
        if (cancelRequested) throw new CancelledDownload();
        if (first != null || !part.isFile() || part.length() <= 0) throw first == null ? new Exception("Download vazio") : first;
        if (target.exists() && !target.delete()) throw new Exception("Não consegui substituir arquivo");
        if (!part.renameTo(target)) {
            try (InputStream in = new java.io.FileInputStream(part); FileOutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[65536]; int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancelRequested) throw new CancelledDownload();
                    out.write(buf, 0, n);
                }
                out.flush();
            }
            if (!part.delete()) part.deleteOnExit();
        }
        if (!target.isFile() || target.length() <= 0) throw new Exception("Arquivo incompleto");
        store.saveDownloaded(track, target);
        SharedMusicPublisher.publishTrack(this, track, target);
    }

    private void fetchToFile(String source, File part, String title, int batchTotal, int batchDone, int batchFailed) throws Exception {
        if (cancelRequested) throw new CancelledDownload();
        long existing = part.isFile() ? Math.max(0L, part.length()) : 0L;

        for (int openAttempt = 0; openAttempt < 2; openAttempt++) {
            if (cancelRequested) throw new CancelledDownload();
            HttpURLConnection c = (HttpURLConnection) new URL(source).openConnection();
            activeConnection = c;
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(12000);
            c.setReadTimeout(120000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) EstradaPlay/" + BuildConfig.VERSION_NAME);
            c.setRequestProperty("Accept", "audio/*,application/octet-stream;q=0.9,*/*;q=0.8");
            c.setRequestProperty("Accept-Encoding", "identity");
            if (existing > 0) c.setRequestProperty("Range", "bytes=" + existing + "-");
            if (source.startsWith(api.base())) {
                String cookie = api.cookie();
                if (cookie != null && !cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
                c.setRequestProperty("X-MusicRoad-Native", "1");
                c.setRequestProperty("X-EstradaPlay-Device", DeviceIdentity.token(this));
                c.setRequestProperty("X-EstradaPlay-Device-Label", DeviceIdentity.label());
            }

            try {
                int code = c.getResponseCode();
                if (code == 416 && existing > 0 && openAttempt == 0) {
                    c.disconnect();
                    activeConnection = null;
                    if (part.exists()) part.delete();
                    existing = 0L;
                    continue;
                }
                if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
                String type = c.getContentType() == null ? "" : c.getContentType().toLowerCase(Locale.ROOT);
                if (type.contains("text/html") || type.contains("application/json")) throw new Exception("Drive devolveu página");

                boolean append = existing > 0 && code == HttpURLConnection.HTTP_PARTIAL;
                if (!append) existing = 0L;
                long remainingLength = c.getContentLengthLong();
                long expectedTotal = remainingLength > 0 ? existing + remainingLength : -1L;
                long remainingNeeded = expectedTotal > 0 ? Math.max(0L, expectedTotal - existing) : -1L;
                long free = store.freeBytes();
                if (remainingNeeded > 0 && free > 0 && remainingNeeded > (long) (free * 0.98d)) throw new Exception("Sem espaço no aparelho");

                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part, append)) {
                    byte[] buf = new byte[65536];
                    int n;
                    long downloaded = existing;
                    long lastUi = 0L;
                    long sinceSpaceCheck = 0L;
                    publishByteProgress(title, downloaded, expectedTotal, batchTotal, batchDone, batchFailed, true);
                    while ((n = in.read(buf)) > 0) {
                        if (cancelRequested || !running) throw new CancelledDownload();
                        out.write(buf, 0, n);
                        downloaded += n;
                        sinceSpaceCheck += n;
                        long now = android.os.SystemClock.elapsedRealtime();
                        if (now - lastUi >= PROGRESS_INTERVAL_MS) {
                            lastUi = now;
                            publishByteProgress(title, downloaded, expectedTotal, batchTotal, batchDone, batchFailed, false);
                        }
                        if (sinceSpaceCheck >= SPACE_CHECK_STEP) {
                            sinceSpaceCheck = 0L;
                            if (store.freeBytes() < 2L * 1024L * 1024L) throw new Exception("Sem espaço no aparelho");
                        }
                    }
                    out.flush();
                    publishByteProgress(title, downloaded, expectedTotal, batchTotal, batchDone, batchFailed, true);
                }
                return;
            } catch (CancelledDownload e) {
                throw e;
            } catch (Exception e) {
                if (cancelRequested || !running) throw new CancelledDownload();
                throw e;
            } finally {
                try { c.disconnect(); } catch (Throwable ignored) {}
                if (activeConnection == c) activeConnection = null;
            }
        }
        throw new Exception("Não consegui reiniciar o download parcial");
    }

    private void publishByteProgress(String title, long downloaded, long expectedTotal, int batchTotal, int batchDone, int batchFailed, boolean force) {
        if (cancelRequested) return;
        String safeTitle = title == null || title.trim().isEmpty() ? "Música" : title.trim();
        String detail;
        int percent = -1;
        if (expectedTotal > 0) {
            percent = (int) Math.max(0L, Math.min(100L, (downloaded * 100L) / expectedTotal));
            detail = safeTitle + " · " + percent + "% · " + humanBytes(downloaded) + " / " + humanBytes(expectedTotal);
        } else {
            detail = safeTitle + " · " + humanBytes(downloaded);
        }
        publish(true, batchTotal, batchDone, batchFailed, detail);
        if (percent >= 0) updateNotification(notification("Baixando: " + safeTitle + " · " + percent + "%", percent, 100, false));
        else if (force) updateNotification(notification("Baixando: " + safeTitle + " · " + humanBytes(downloaded), batchDone, batchTotal, batchTotal <= 0));
    }

    private String humanBytes(long value) {
        if (value < 1024L) return value + " B";
        double kb = value / 1024d;
        if (kb < 1024d) return String.format(Locale.getDefault(), "%.1f KB", kb);
        double mb = kb / 1024d;
        if (mb < 1024d) return String.format(Locale.getDefault(), "%.1f MB", mb);
        return String.format(Locale.getDefault(), "%.2f GB", mb / 1024d);
    }

    private Set<String> parseFolders(String raw) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        try {
            JSONArray a = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < a.length(); i++) {
                String s = a.optString(i, "").trim();
                if (!s.isEmpty()) out.add(s);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private void publish(boolean active, int total, int done, int failed, String current) {
        Intent i = new Intent(ACTION_STATE).setPackage(getPackageName());
        i.putExtra("active", active);
        i.putExtra("total", total);
        i.putExtra("done", done);
        i.putExtra("failed", failed);
        i.putExtra("current", current);
        i.putExtra("cancelled", cancelRequested);
        sendBroadcast(i);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Downloads de música", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Download das pastas escolhidas para uso offline");
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification notification(String text, int done, int total, boolean indeterminate) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Estrada Play")
                .setContentText(text)
                .setOngoing(running)
                .setOnlyAlertOnce(true)
                .setProgress(Math.max(0, total), Math.max(0, Math.min(total, done)), indeterminate || total <= 0);
        if (running && !cancelRequested) {
            Intent cancel = new Intent(this, DownloadService.class).setAction(ACTION_CANCEL);
            PendingIntent pi = PendingIntent.getService(this, CANCEL_REQUEST_CODE, cancel, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "CANCELAR", pi);
        }
        return b.build();
    }

    private void updateNotification(Notification n) {
        NotificationPermissionCompat.notify(this, NOTIFICATION_ID, n);
    }

    @Override public void onTimeout(int startId, int fgsType) {
        cancelRequested = true;
        running = false;
        HttpURLConnection c = activeConnection;
        activeConnection = null;
        if (c != null) try { c.disconnect(); } catch (Throwable ignored) {}
        publish(false, 0, 0, 0, "Download pausado pelo Android · progresso parcial preservado");
        try { stopForeground(true); } catch (Throwable ignored) {}
        stopSelf(startId);
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        cancelRequested = true;
        running = false;
        HttpURLConnection c = activeConnection;
        if (c != null) try { c.disconnect(); } catch (Throwable ignored) {}
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        boolean wasRunning = running;
        running = false;
        HttpURLConnection c = activeConnection;
        activeConnection = null;
        if (c != null) try { c.disconnect(); } catch (Throwable ignored) {}
        io.shutdownNow();
        if (wasRunning) stopForeground(true);
        super.onDestroy();
    }

    private static final class CancelledDownload extends Exception {
        CancelledDownload() { super("cancelled"); }
    }
}

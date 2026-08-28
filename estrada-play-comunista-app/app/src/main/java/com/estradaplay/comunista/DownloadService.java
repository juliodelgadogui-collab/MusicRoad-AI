package com.estradaplay.comunista;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
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
    static final String ACTION_STATE = "com.estradaplay.comunista.DOWNLOAD_STATE";
    static final String EXTRA_FOLDERS = "folders";
    private static final String CHANNEL = "estradaplay_downloads";
    private static final int NOTIFICATION_ID = 4401;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile boolean running;
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
        if (intent == null || !ACTION_START.equals(intent.getAction()) || running) return START_NOT_STICKY;
        Set<String> folders = parseFolders(intent.getStringExtra(EXTRA_FOLDERS));
        if (folders.isEmpty()) { stopSelf(); return START_NOT_STICKY; }
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
            if (!running) break;
            publish(true, total, done, failed, track.title);
            updateNotification(notification("Baixando: " + track.title, done, total, false));
            try {
                download(track);
                done++;
            } catch (Exception e) {
                failed++;
            }
            publish(true, total, done, failed, track.title);
        }
        running = false;
        if (done > 0 || !store.downloadedTracks().isEmpty()) store.setSetupDone(true);
        publish(false, total, done, failed, failed == 0 ? "Biblioteca offline pronta" : "Concluído com " + failed + " falha(s)");
        updateNotification(notification(failed == 0 ? "Músicas prontas para usar offline" : "Downloads concluídos · " + failed + " falharam", total, total, false));
        stopForeground(false);
        stopSelf();
    }

    private void download(Track track) throws Exception {
        if (track == null || track.remoteSource.isEmpty()) throw new Exception("Fonte ausente");
        File target = store.targetFile(track);
        if (target.isFile() && target.length() > 0) { store.saveDownloaded(track, target); return; }
        File dir = target.getParentFile();
        if (dir == null || (!dir.exists() && !dir.mkdirs())) throw new Exception("Pasta indisponível");
        File part = new File(dir, target.getName() + ".part");
        if (part.exists()) part.delete();

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
        for (String candidate : new String[]{primary, alternate, track.remoteSource}) {
            if (candidate == null || candidate.trim().isEmpty()) continue;
            try {
                fetchToFile(candidate.trim(), part);
                first = null;
                break;
            } catch (Exception e) {
                if (first == null) first = e;
                if (part.exists()) part.delete();
            }
        }
        if (first != null || !part.isFile() || part.length() <= 0) throw first == null ? new Exception("Download vazio") : first;
        if (target.exists() && !target.delete()) throw new Exception("Não consegui substituir arquivo");
        if (!part.renameTo(target)) {
            try (InputStream in = new java.io.FileInputStream(part); FileOutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[65536]; int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n); out.flush();
            }
            part.delete();
        }
        if (!target.isFile() || target.length() <= 0) throw new Exception("Arquivo incompleto");
        store.saveDownloaded(track, target);
    }

    private void fetchToFile(String source, File part) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(source).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(12000);
        c.setReadTimeout(120000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) EstradaPlay/" + BuildConfig.VERSION_NAME);
        c.setRequestProperty("Accept", "audio/*,application/octet-stream;q=0.9,*/*;q=0.8");
        c.setRequestProperty("Accept-Encoding", "identity");
        if (source.startsWith(api.base())) {
            String cookie = api.cookie();
            if (cookie != null && !cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
            c.setRequestProperty("X-MusicRoad-Native", "1");
            c.setRequestProperty("X-EstradaPlay-Device", DeviceIdentity.token(this));
            c.setRequestProperty("X-EstradaPlay-Device-Label", DeviceIdentity.label());
        }
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) { c.disconnect(); throw new Exception("HTTP " + code); }
        String type = c.getContentType() == null ? "" : c.getContentType().toLowerCase(Locale.ROOT);
        if (type.contains("text/html") || type.contains("application/json")) { c.disconnect(); throw new Exception("Drive devolveu página"); }
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buf = new byte[65536]; int n; long total = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n); total += n;
                if (total > store.freeBytes() + part.length()) throw new Exception("Sem espaço no aparelho");
            }
            out.flush();
        } finally { c.disconnect(); }
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
        i.putExtra("active", active); i.putExtra("total", total); i.putExtra("done", done); i.putExtra("failed", failed); i.putExtra("current", current);
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
                .setContentTitle("Estrada Play Comunista")
                .setContentText(text)
                .setOngoing(running)
                .setOnlyAlertOnce(true)
                .setProgress(Math.max(0, total), Math.max(0, done), indeterminate || total <= 0);
        return b.build();
    }

    private void updateNotification(Notification n) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, n);
    }

    @Override public void onDestroy() { running = false; io.shutdownNow(); super.onDestroy(); }
}

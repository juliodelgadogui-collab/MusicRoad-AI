from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    s = p.read_text(encoding='utf-8')
    if new in s:
        return
    if old not in s:
        raise SystemExit(f'anchor not found in {path}: {old[:120]!r}')
    p.write_text(s.replace(old, new, 1), encoding='utf-8')


# Version
replace_once(
    'estradaplay-app-v2/app/build.gradle',
    "        versionCode 210\n        versionName '2.0.10'",
    "        versionCode 211\n        versionName '2.0.11'",
)

# LibraryStore: keep boot flags in a tiny prefs file so the old large download JSON
# can never stall the main thread while SharedPreferences loads.
path = 'estradaplay-app-v2/app/src/main/java/com/estradaplay/app/LibraryStore.java'
replace_once(
    path,
    '    private static final String PREFS = "estradaplay_library_v1";\n    private static final String KEY_CATALOG = "catalog";',
    '    private static final String PREFS = "estradaplay_library_v1";\n    private static final String FLAGS_PREFS = "estradaplay_library_flags_v211";\n    private static final String KEY_DOWNLOADED_HINT = "downloaded_hint";\n    private static final String KEY_CATALOG = "catalog";',
)
replace_once(
    path,
    '    private final SharedPreferences prefs;\n    private final File catalogFile;',
    '    private final SharedPreferences prefs;\n    private final SharedPreferences flags;\n    private final File catalogFile;',
)
replace_once(
    path,
    '        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);\n        File dir = new File(app.getFilesDir(), "estradaplay_library");',
    '        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);\n        flags = app.getSharedPreferences(FLAGS_PREFS, Context.MODE_PRIVATE);\n        File dir = new File(app.getFilesDir(), "estradaplay_library");',
)
replace_once(
    path,
    '            prefs.edit().putString(KEY_DOWNLOADED, raw).apply();\n            downloadedCache = null; downloadedCacheRaw = null;',
    '            prefs.edit().putString(KEY_DOWNLOADED, raw).apply();\n            flags.edit().putBoolean(KEY_DOWNLOADED_HINT, true).apply();\n            downloadedCache = null; downloadedCacheRaw = null;',
)
replace_once(
    path,
    '        downloadedCacheRaw = raw;\n        downloadedCache = new ArrayList<>(out);\n        return out;\n    }\n\n    boolean hasDownloadedHint() {\n        String raw = prefs.getString(KEY_DOWNLOADED, "{}");\n        return raw != null && raw.length() > 2;\n    }\n\n    boolean hasSetupDone() { return prefs.getBoolean(KEY_SETUP, false); }\n    void setSetupDone(boolean done) { prefs.edit().putBoolean(KEY_SETUP, done).apply(); }',
    '        downloadedCacheRaw = raw;\n        downloadedCache = new ArrayList<>(out);\n        flags.edit().putBoolean(KEY_DOWNLOADED_HINT, !out.isEmpty()).apply();\n        return out;\n    }\n\n    // ANR_BOOT_FLAGS_V211: never read the potentially multi-megabyte download index on UI boot.\n    // Default true preserves existing installs; tapping Music will validate the real index on the IO executor.\n    boolean hasDownloadedHint() { return flags.getBoolean(KEY_DOWNLOADED_HINT, true); }\n\n    boolean hasSetupDone() { return flags.getBoolean(KEY_SETUP, false); }\n    void setSetupDone(boolean done) { flags.edit().putBoolean(KEY_SETUP, done).apply(); }',
)
replace_once(
    path,
    '        prefs.edit().putString(KEY_DOWNLOADED, all.toString()).apply();\n        downloadedCache = null; downloadedCacheRaw = null;\n        return removed;',
    '        prefs.edit().putString(KEY_DOWNLOADED, all.toString()).apply();\n        flags.edit().putBoolean(KEY_DOWNLOADED_HINT, all.length() > 0).apply();\n        downloadedCache = null; downloadedCacheRaw = null;\n        return removed;',
)

# MainActivity: all large catalog parsing and serialization stays on IO.
path = 'estradaplay-app-v2/app/src/main/java/com/estradaplay/app/MainActivity.java'
replace_once(
    path,
    '''private void loadCatalogAndOpenChooser(boolean initial) {
    List<Track> local = library.catalog();
    if (!local.isEmpty()) {
        library.setSetupDone(true);
        showFolderChooser(initial);
        syncCatalogInBackground();
        return;
    }

    showLoading("Carregando sua biblioteca…");
    io.execute(() -> {
        try {''',
    '''// ANR_CATALOG_BOOT_V211: disk/JSON work never runs on Android's main thread.
private void loadCatalogAndOpenChooser(boolean initial) {
    showLoading("Abrindo sua biblioteca…");
    io.execute(() -> {
        List<Track> local = library.catalog();
        if (!local.isEmpty()) {
            library.setSetupDone(true);
            ui.post(() -> {
                showFolderChooser(initial);
                syncCatalogInBackground();
            });
            return;
        }
        ui.post(() -> showLoading("Carregando sua biblioteca…"));
        try {''',
)
replace_once(
    path,
    '''            ArrayList<Track> result = tracks;
            ui.post(() -> {
                library.setSetupDone(true);
                if (!result.isEmpty()) {
                    library.saveCatalog(result);
                    showFolderChooser(initial);''',
    '''            ArrayList<Track> result = tracks;
            if (!result.isEmpty()) library.saveCatalog(result);
            library.setSetupDone(true);
            ui.post(() -> {
                if (!result.isEmpty()) {
                    showFolderChooser(initial);''',
)
replace_once(
    path,
    '''        device.addView(infoRow("Músicas offline", library.downloadedTracks().size() + " arquivos"));
        device.addView(divider());
        RoadPackStore store = new RoadPackStore(this);
        device.addView(infoRow("Proteção da estrada", store.hazardCount() + " pontos salvos"));''',
    '''        device.addView(infoRow("Músicas offline", library.hasDownloadedHint() ? "Biblioteca preparada" : "Nenhuma"));
        device.addView(divider());
        device.addView(infoRow("Proteção da estrada", hasLocationPermission() ? "Ativa neste aparelho" : "GPS desativado"));''',
)

# RoadSafetyService: RoadPackStore parses/indexes large JSON files, so initialization
# must happen on the existing IO executor, after startForeground returns quickly.
path = 'estradaplay-app-v2/app/src/main/java/com/estradaplay/app/RoadSafetyService.java'
replace_once(
    path,
    '    private RoadPackStore packs;\n    private OfflineRoadStore mapRoads;',
    '    private volatile RoadPackStore packs;\n    private volatile OfflineRoadStore mapRoads;\n    private final AtomicBoolean storesLoading = new AtomicBoolean(false);',
)
replace_once(
    path,
    '''        packs = new RoadPackStore(this);
        mapRoads = new OfflineRoadStore(this);
        api = new ApiClient(this);''',
    '''        // ANR_ROAD_INIT_V211: heavy offline JSON parsing is deferred to IO.
        api = new ApiClient(this);''',
)
replace_once(
    path,
    '''        startLocation();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startLocation();
        return START_STICKY;
    }''',
    '''        initializeStoresAsync();
    }

    private void initializeStoresAsync() {
        if (packs != null && mapRoads != null) {
            startLocation();
            return;
        }
        if (!storesLoading.compareAndSet(false, true)) return;
        io.execute(() -> {
            try {
                RoadPackStore loadedPacks = new RoadPackStore(getApplicationContext());
                OfflineRoadStore loadedRoads = new OfflineRoadStore(getApplicationContext());
                packs = loadedPacks;
                mapRoads = loadedRoads;
            } catch (Throwable ignored) {
            } finally {
                storesLoading.set(false);
                main.post(() -> {
                    if (packs != null && mapRoads != null) startLocation();
                    else updateNotification("Proteção na estrada", "Base offline indisponível; tentando novamente", true);
                });
            }
        });
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (packs != null && mapRoads != null) startLocation(); else initializeStoresAsync();
        return START_STICKY;
    }''',
)
replace_once(
    path,
    '''    private void handleLocation(Location loc) {
        if (loc == null) return;''',
    '''    private void handleLocation(Location loc) {
        if (loc == null) return;
        if (packs == null || mapRoads == null) return;''',
)

# PlayerService: the old SharedPreferences download index may be large. Build the
# queue on an IO executor and only mutate MediaPlayer state back on main.
path = 'estradaplay-app-v2/app/src/main/java/com/estradaplay/app/PlayerService.java'
replace_once(
    path,
    'import android.os.Build;\nimport android.os.IBinder;',
    'import android.os.Build;\nimport android.os.Handler;\nimport android.os.IBinder;\nimport android.os.Looper;',
)
replace_once(
    path,
    'import java.util.List;\n',
    'import java.util.List;\nimport java.util.concurrent.ExecutorService;\nimport java.util.concurrent.Executors;\n',
)
replace_once(
    path,
    '    private final ArrayList<Track> queue = new ArrayList<>();\n    private LibraryStore store;',
    '    private final ArrayList<Track> queue = new ArrayList<>();\n    private final ExecutorService io = Executors.newSingleThreadExecutor();\n    private final Handler main = new Handler(Looper.getMainLooper());\n    private LibraryStore store;',
)
replace_once(
    path,
    '''        if (ACTION_PLAY_TRACK.equals(action)) {
            String key = intent.getStringExtra(EXTRA_KEY);
            String folder = intent.getStringExtra(EXTRA_FOLDER);
            buildQueue(folder);
            index = findIndex(key);
            if (index < 0 && !queue.isEmpty()) index = 0;
            prepareAndPlay();
        } else if (ACTION_TOGGLE.equals(action)) toggle();''',
    '''        if (ACTION_PLAY_TRACK.equals(action)) {
            String key = intent.getStringExtra(EXTRA_KEY);
            String folder = intent.getStringExtra(EXTRA_FOLDER);
            startForeground(NOTIFICATION_ID, notification(null, false));
            io.execute(() -> {
                ArrayList<Track> loaded = loadQueue(folder);
                main.post(() -> {
                    queue.clear();
                    queue.addAll(loaded);
                    index = findIndex(key);
                    if (index < 0 && !queue.isEmpty()) index = 0;
                    prepareAndPlay();
                });
            });
        } else if (ACTION_TOGGLE.equals(action)) toggle();''',
)
replace_once(
    path,
    '''    private void buildQueue(String folder) {
        queue.clear();
        List<Track> all = store.downloadedTracks();
        String f = folder == null ? "" : folder.trim();
        for (Track t : all) {
            if (f.isEmpty() || "__ALL__".equals(f) || f.equals(LibraryStore.folderKey(t))) queue.add(t);
        }
    }''',
    '''    private ArrayList<Track> loadQueue(String folder) {
        ArrayList<Track> loaded = new ArrayList<>();
        List<Track> all = store.downloadedTracks();
        String f = folder == null ? "" : folder.trim();
        for (Track t : all) {
            if (f.isEmpty() || "__ALL__".equals(f) || f.equals(LibraryStore.folderKey(t))) loaded.add(t);
        }
        return loaded;
    }''',
)
replace_once(
    path,
    '    @Override public void onDestroy() { releasePlayer(); stopForeground(true); super.onDestroy(); }',
    '    @Override public void onDestroy() { io.shutdownNow(); releasePlayer(); stopForeground(true); super.onDestroy(); }',
)

print('EstradaPlay 2.0.11 ANR root repair applied')

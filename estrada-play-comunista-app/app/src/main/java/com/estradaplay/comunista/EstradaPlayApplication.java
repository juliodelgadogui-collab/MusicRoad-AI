package com.estradaplay.comunista;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Build;

import java.util.List;

/** Process-level bridges for Server 7.0 context and live convoy presence. */
public final class EstradaPlayApplication extends Application {
    private RouteContextV7BackgroundReceiver contextReceiver;
    private ConvoyLiveBridge convoyReceiver;

    @Override public void onCreate() {
        super.onCreate();

        // PTT_STABILITY_V234: RoadRadioService owns an isolated :radio process. Do not duplicate
        // Contexto Vivo, Comboio or the main crash-loop guard there; native audio failure must stay local.
        if (isRadioProcess()) return;

        UiVersionLabelFix.register(this);

        // ANDROID15_SAFE_INSETS_V251: targetSdk 35 is edge-to-edge on Android 15.
        // Install one process-level safe-area bridge for every Activity before optional bridges can defer.
        SystemBarsCompatV251.register(this);

        // MUSIC_LIBRARY_INTEGRITY_V247: repair only already-indexed app downloads before any
        // music UI can expose a missing/zero-byte file as a playable song. No storage scan/server.
        try { MusicLibraryIntegrityV247.repair(this); } catch (Throwable ignored) {}

        boolean deferOptionalBridges = ProcessCrashGuard.install(this);
        if (deferOptionalBridges) return;

        // MUSIC_PERSIST_V2310: copy old private downloads to user-visible Music/EstradaPlay.
        // Runs off the UI thread, is copy-first/non-destructive and is a no-op below Android 10.
        new Thread(() -> {
            try { SharedMusicPublisher.publishMissingFromApp(EstradaPlayApplication.this); }
            catch (Throwable ignored) {}
        }, "epc-shared-music").start();

        // MUSIC_LOCAL_INDEX_V2315: only watch for audio-library changes. This never scans files.
        // The saved SQLite index opens instantly; if it is old, Music refreshes it later in background.
        try {
            PhoneMp3Store.installObserver(this);
            if (PhoneMp3Store.hasPermission(this)) {
                PhoneMp3Index index = PhoneMp3Index.get(this);
                long age = System.currentTimeMillis() - index.updatedAt();
                if (!index.isReady() || age > 6L * 60L * 60L * 1000L) index.markDirty();
            }
        } catch (Throwable ignored) {}

        // COMBOIO_LINK_MAP_V237: deep links + members projected onto the principal RoadMapActivity.
        ConvoyIntegrationV237.install(this);

        IntentFilter roadState = new IntentFilter(RoadSafetyService.ACTION_STATE);

        try {
            contextReceiver = new RouteContextV7BackgroundReceiver();
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(contextReceiver, roadState, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(contextReceiver, roadState);
            }
        } catch (Throwable ignored) {
            try { if (contextReceiver != null) unregisterReceiver(contextReceiver); } catch (Throwable ignored2) {}
            contextReceiver = null;
        }

        try {
            convoyReceiver = new ConvoyLiveBridge();
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(convoyReceiver, roadState, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(convoyReceiver, roadState);
            }
        } catch (Throwable ignored) {
            try { if (convoyReceiver != null) unregisterReceiver(convoyReceiver); } catch (Throwable ignored2) {}
            convoyReceiver = null;
        }
    }

    private boolean isRadioProcess() {
        try {
            String name;
            if (Build.VERSION.SDK_INT >= 28) {
                name = Application.getProcessName();
            } else {
                name = null;
                int pid = android.os.Process.myPid();
                ActivityManager am = (ActivityManager)getSystemService(Context.ACTIVITY_SERVICE);
                List<ActivityManager.RunningAppProcessInfo> processes = am == null ? null : am.getRunningAppProcesses();
                if (processes != null) {
                    for (ActivityManager.RunningAppProcessInfo p : processes) {
                        if (p != null && p.pid == pid) { name = p.processName; break; }
                    }
                }
            }
            return name != null && name.endsWith(":radio");
        } catch (Throwable ignored) {
            return false;
        }
    }
}

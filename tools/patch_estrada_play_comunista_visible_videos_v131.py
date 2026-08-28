#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path('estrada-play-comunista-app')
if not ROOT.exists():
    raise SystemExit('estrada-play-comunista-app not generated')


def read(rel):
    return (ROOT / rel).read_text(encoding='utf-8')


def write(rel, value):
    path = ROOT / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(value, encoding='utf-8')

# Version
build = read('app/build.gradle')
build = re.sub(r'versionCode\s+\d+', 'versionCode 131', build, count=1)
build = re.sub(r"versionName\s+'[^']+'", "versionName '1.3.1'", build, count=1)
write('app/build.gradle', build)

# Manifest: old Android public movies permission + internal video library activity.
manifest = read('app/src/main/AndroidManifest.xml')
if 'android.permission.WRITE_EXTERNAL_STORAGE' not in manifest:
    manifest = manifest.replace(
        '    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />',
        '    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />\n'
        '    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="28" />'
    )
if '.VideoLibraryActivity' not in manifest:
    anchor = '        <activity\n            android:name=".CameraActivity"'
    idx = manifest.find(anchor)
    if idx < 0:
        raise SystemExit('CameraActivity manifest anchor missing')
    block = '''        <activity
            android:name=".VideoLibraryActivity"
            android:exported="false"
            android:screenOrientation="${appOrientation}" />

'''
    manifest = manifest[:idx] + block + manifest[idx:]
write('app/src/main/AndroidManifest.xml', manifest)

camera = r'''package com.estradaplay.comunista;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Optional road camera. Public recordings are stored in Movies/EstradaPlayComunista. */
public final class CameraActivity extends ComponentActivity {
    private static final String PREFS = "epc_camera_v1";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_MIGRATED_PUBLIC = "videos_public_migrated_v131";
    private static final int REQ_CAMERA = 1901;
    private static final int REQ_STORAGE = 1902;

    private final int BG = Color.rgb(7, 4, 6);
    private final int PANEL = Color.rgb(24, 9, 13);
    private final int BORDER = Color.rgb(88, 34, 43);
    private final int TEXT = Color.rgb(247, 239, 226);
    private final int MUTED = Color.rgb(179, 154, 149);
    private final int RED = Color.rgb(196, 20, 41);
    private final int GREEN = Color.rgb(68, 213, 132);

    private FrameLayout root;
    private SharedPreferences prefs;
    private PreviewView previewView;
    private ProcessCameraProvider cameraProvider;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording;
    private Button recordButton;
    private TextView cameraState;
    private TextView speedState;
    private boolean receiverRegistered;

    private final BroadcastReceiver roadReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            double speed = intent.getDoubleExtra("speed_kmh", 0);
            if (speedState != null) speedState.setText(Math.max(0, Math.round(speed)) + " KM/H");
            SafetyAlertOverlay.show(CameraActivity.this, root, intent);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        setContentView(root);
        migrateLegacyVideosAsync();
        if (isEnabled() && hasCameraPermission()) buildCameraUi();
        else buildGateUi();
    }

    @Override protected void onStart() {
        super.onStart();
        registerRoadReceiver();
        if (isEnabled() && hasCameraPermission() && previewView != null) startCamera();
    }

    @Override protected void onStop() {
        stopRecording();
        if (cameraProvider != null) {
            try { cameraProvider.unbindAll(); } catch (Throwable ignored) {}
        }
        unregisterRoadReceiver();
        super.onStop();
    }

    private void buildGateUi() {
        previewView = null;
        root.removeAllViews();
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(dp(24), dp(28), dp(24), dp(24));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        TextView over = text("MÓDULO OPCIONAL", 11, RED, true);
        over.setLetterSpacing(0.14f);
        page.addView(over);
        TextView title = text("CÂMERA DE BORDO", 29, TEXT, true);
        page.addView(title); margins(title, 0, 8, 0, 8);
        TextView body = text(
                "A proteção por GPS, radares e quebra-molas funciona sem câmera. " +
                "Ative este módulo somente se quiser usar a câmera traseira como visão da estrada ou gravar um trecho.",
                14, MUTED, false);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        page.addView(body);

        LinearLayout privacy = new LinearLayout(this);
        privacy.setOrientation(LinearLayout.VERTICAL);
        privacy.setPadding(dp(16), dp(15), dp(16), dp(15));
        privacy.setBackground(box(PANEL, 14, BORDER));
        privacy.addView(text("PRIVACIDADE", 10, GREEN, true));
        privacy.addView(text("• Nenhum vídeo é enviado ao servidor.\n• A permissão só é pedida quando você toca em ATIVAR.\n• Gravações ficam na Galeria em Movies/EstradaPlayComunista.\n• Você pode desativar novamente a qualquer momento.", 12, TEXT, false));
        page.addView(privacy, new LinearLayout.LayoutParams(-1, -2)); margins(privacy, 0, 24, 0, 18);

        Button videos = button("MEUS VÍDEOS", false);
        page.addView(videos, new LinearLayout.LayoutParams(-1, dp(52)));
        videos.setOnClickListener(v -> openVideos());

        View spacer = new View(this);
        page.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));

        Button activate = button("ATIVAR CÂMERA", true);
        page.addView(activate, new LinearLayout.LayoutParams(-1, dp(58)));
        activate.setOnClickListener(v -> activateCamera());
        Button back = button("VOLTAR", false);
        page.addView(back, new LinearLayout.LayoutParams(-1, dp(52))); margins(back, 0, 10, 0, 0);
        back.setOnClickListener(v -> finish());
    }

    private void buildCameraUi() {
        root.removeAllViews();
        previewView = new PreviewView(this);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(previewView, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(14), dp(12), dp(14), dp(12));
        top.setBackgroundColor(Color.argb(210, 12, 5, 8));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        cameraState = text("CÂMERA DE BORDO · ATIVA", 12, GREEN, true);
        info.addView(cameraState);
        info.addView(text("Alertas rodoviários continuam ativos", 10, MUTED, false));
        top.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        speedState = text("0 KM/H", 22, TEXT, true);
        top.addView(speedState);
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        topParams.setMargins(dp(12), dp(12), dp(12), 0);
        root.addView(top, topParams);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(12), dp(12), dp(12), dp(12));
        bottom.setBackground(box(Color.argb(226, 17, 7, 10), 18, BORDER));
        TextView local = text("GALERIA · MOVIES/ESTRADAPLAYCOMUNISTA · SEM UPLOAD", 9, MUTED, true);
        local.setLetterSpacing(0.06f);
        bottom.addView(local);

        Button videos = button("MEUS VÍDEOS", false);
        bottom.addView(videos, new LinearLayout.LayoutParams(-1, dp(46))); margins(videos, 0, 8, 0, 0);
        videos.setOnClickListener(v -> openVideos());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        recordButton = button("GRAVAR", true);
        recordButton.setEnabled(false);
        Button disable = button("DESATIVAR", false);
        Button back = button("VOLTAR", false);
        actions.addView(recordButton, new LinearLayout.LayoutParams(0, dp(54), 1));
        LinearLayout.LayoutParams dp1 = new LinearLayout.LayoutParams(0, dp(54), 1); dp1.setMargins(dp(7), 0, 0, 0); actions.addView(disable, dp1);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(54), 1); bp.setMargins(dp(7), 0, 0, 0); actions.addView(back, bp);
        bottom.addView(actions); margins(actions, 0, 8, 0, 0);
        recordButton.setOnClickListener(v -> toggleRecording());
        disable.setOnClickListener(v -> disableCamera());
        back.setOnClickListener(v -> finish());

        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        bottomParams.setMargins(dp(12), 0, dp(12), dp(14));
        root.addView(bottom, bottomParams);
    }

    private void activateCamera() {
        if (hasCameraPermission()) {
            prefs.edit().putBoolean(KEY_ENABLED, true).apply();
            buildCameraUi();
            startCamera();
            return;
        }
        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) toggleRecording();
            else toast("Sem acesso ao armazenamento, a gravação não pode ser salva na Galeria neste Android.");
            return;
        }
        if (requestCode != REQ_CAMERA) return;
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            prefs.edit().putBoolean(KEY_ENABLED, false).apply();
            toast("Câmera continua desativada.");
            buildGateUi();
            return;
        }
        prefs.edit().putBoolean(KEY_ENABLED, true).apply();
        buildCameraUi();
        startCamera();
    }

    private void disableCamera() {
        stopRecording();
        if (cameraProvider != null) {
            try { cameraProvider.unbindAll(); } catch (Throwable ignored) {}
        }
        prefs.edit().putBoolean(KEY_ENABLED, false).apply();
        toast("Câmera de bordo desativada.");
        buildGateUi();
    }

    private void startCamera() {
        if (!isEnabled() || !hasCameraPermission() || previewView == null) return;
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            toast("Este aparelho não possui câmera disponível.");
            return;
        }
        var future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.HD))
                        .build();
                videoCapture = VideoCapture.withOutput(recorder);
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, videoCapture);
                if (recordButton != null) recordButton.setEnabled(true);
                if (cameraState != null) cameraState.setText("CÂMERA DE BORDO · ATIVA");
            } catch (Throwable e) {
                videoCapture = null;
                if (recordButton != null) recordButton.setEnabled(false);
                if (cameraState != null) cameraState.setText("CÂMERA INDISPONÍVEL");
                toast("Não foi possível abrir a câmera traseira.");
            }
        }, getMainExecutor());
    }

    private void toggleRecording() {
        if (recording != null) {
            stopRecording();
            return;
        }
        if (videoCapture == null) {
            toast("A câmera ainda está preparando.");
            return;
        }
        if (Build.VERSION.SDK_INT <= 28 && !hasLegacyStoragePermission()) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }

        String name = "EPC-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".mp4";
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/EstradaPlayComunista");
        }
        try {
            MediaStoreOutputOptions options = new MediaStoreOutputOptions.Builder(
                    getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                    .setContentValues(values)
                    .build();
            recording = videoCapture.getOutput().prepareRecording(this, options)
                    .start(getMainExecutor(), event -> {
                        if (event instanceof VideoRecordEvent.Start) {
                            if (recordButton != null) recordButton.setText("PARAR");
                            if (cameraState != null) cameraState.setText("● GRAVANDO NA GALERIA");
                        } else if (event instanceof VideoRecordEvent.Finalize) {
                            VideoRecordEvent.Finalize done = (VideoRecordEvent.Finalize) event;
                            recording = null;
                            if (recordButton != null) recordButton.setText("GRAVAR");
                            if (cameraState != null) cameraState.setText("CÂMERA DE BORDO · ATIVA");
                            if (done.hasError()) toast("A gravação foi encerrada com erro.");
                            else toast("Vídeo salvo em Galeria > Movies > EstradaPlayComunista.");
                        }
                    });
        } catch (Throwable e) {
            recording = null;
            toast("Não foi possível iniciar a gravação.");
        }
    }

    private void stopRecording() {
        Recording r = recording;
        recording = null;
        if (r != null) {
            try { r.stop(); } catch (Throwable ignored) {}
        }
        if (recordButton != null) recordButton.setText("GRAVAR");
        if (cameraState != null && isEnabled()) cameraState.setText("CÂMERA DE BORDO · ATIVA");
    }

    private void openVideos() {
        startActivity(new Intent(this, VideoLibraryActivity.class));
    }

    private void migrateLegacyVideosAsync() {
        if (Build.VERSION.SDK_INT < 29 || prefs.getBoolean(KEY_MIGRATED_PUBLIC, false)) return;
        new Thread(() -> {
            int moved = 0;
            try {
                File base = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
                File dir = base == null ? null : new File(base, "EstradaPlayComunista");
                File[] files = dir == null ? null : dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".mp4"));
                if (files != null) {
                    for (File file : files) {
                        if (file == null || !file.isFile() || file.length() <= 0) continue;
                        if (copyLegacyToGallery(file)) {
                            moved++;
                            try { file.delete(); } catch (Throwable ignored) {}
                        }
                    }
                }
                prefs.edit().putBoolean(KEY_MIGRATED_PUBLIC, true).apply();
            } catch (Throwable ignored) {}
            int total = moved;
            if (total > 0) runOnUiThread(() -> toast(total + (total == 1 ? " vídeo antigo recuperado para a Galeria." : " vídeos antigos recuperados para a Galeria.")));
        }, "epc-video-migrate").start();
    }

    private boolean copyLegacyToGallery(File file) {
        Uri uri = null;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, file.getName());
            values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/EstradaPlayComunista");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return false;
            try (FileInputStream in = new FileInputStream(file); OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("no output");
                byte[] buffer = new byte[1024 * 128];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Video.Media.IS_PENDING, 0);
            getContentResolver().update(uri, done, null, null);
            return true;
        } catch (Throwable e) {
            if (uri != null) try { getContentResolver().delete(uri, null, null); } catch (Throwable ignored) {}
            return false;
        }
    }

    private void registerRoadReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter(RoadSafetyService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(roadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(roadReceiver, filter);
        receiverRegistered = true;
    }

    private void unregisterRoadReceiver() {
        if (!receiverRegistered) return;
        try { unregisterReceiver(roadReceiver); } catch (Throwable ignored) {}
        receiverRegistered = false;
    }

    private boolean isEnabled() { return prefs.getBoolean(KEY_ENABLED, false); }
    private boolean hasCameraPermission() { return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED; }
    private boolean hasLegacyStoragePermission() {
        return Build.VERSION.SDK_INT >= 29 || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private Button button(String value, boolean primary) {
        Button b = new Button(this);
        b.setText(value);
        b.setTextSize(12);
        b.setTextColor(TEXT);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setAllCaps(false);
        b.setBackground(box(primary ? RED : Color.rgb(24, 17, 20), 14, primary ? RED : BORDER));
        return b;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.08f);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private GradientDrawable box(int color, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private void margins(View v, int l, int t, int r, int b) {
        if (!(v.getLayoutParams() instanceof LinearLayout.LayoutParams)) return;
        LinearLayout.LayoutParams p = (LinearLayout.LayoutParams)v.getLayoutParams();
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        v.setLayoutParams(p);
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
}
'''
write('app/src/main/java/com/estradaplay/comunista/CameraActivity.java', camera)

library = r'''package com.estradaplay.comunista;

import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public final class VideoLibraryActivity extends ComponentActivity {
    private final int BG = Color.rgb(7, 4, 6);
    private final int PANEL = Color.rgb(24, 9, 13);
    private final int BORDER = Color.rgb(88, 34, 43);
    private final int TEXT = Color.rgb(247, 239, 226);
    private final int MUTED = Color.rgb(179, 154, 149);
    private final int RED = Color.rgb(196, 20, 41);
    private final int GREEN = Color.rgb(68, 213, 132);

    private static final class Item {
        long id;
        String name;
        long date;
        long size;
        Uri uri;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        build();
    }

    @Override protected void onResume() {
        super.onResume();
        build();
    }

    private void build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        setContentView(root);

        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(18), dp(18), dp(28));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        TextView over = text("ARQUIVO LOCAL", 10, RED, true); over.setLetterSpacing(0.14f); page.addView(over);
        page.addView(text("MEUS VÍDEOS", 28, TEXT, true));
        TextView path = text("Galeria / Movies / EstradaPlayComunista", 12, MUTED, false); page.addView(path); margins(path, 0, 4, 0, 14);

        List<Item> items = queryVideos();
        TextView count = text(items.size() + (items.size() == 1 ? " gravação encontrada" : " gravações encontradas"), 12, GREEN, true);
        page.addView(count); margins(count, 0, 0, 0, 10);

        if (items.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(text("Nenhum vídeo público ainda", 17, TEXT, true));
            empty.addView(text("Depois de gravar, o arquivo aparece aqui e também na Galeria do Android.", 12, MUTED, false));
            page.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        } else {
            for (Item item : items) page.addView(videoCard(item), new LinearLayout.LayoutParams(-1, -2));
        }

        Button gallery = button("ABRIR GALERIA DO CELULAR", false);
        page.addView(gallery, new LinearLayout.LayoutParams(-1, dp(52))); margins(gallery, 0, 16, 0, 0);
        gallery.setOnClickListener(v -> openGallery());
        Button back = button("VOLTAR", true);
        page.addView(back, new LinearLayout.LayoutParams(-1, dp(52))); margins(back, 0, 8, 0, 0);
        back.setOnClickListener(v -> finish());
    }

    private LinearLayout videoCard(Item item) {
        LinearLayout card = card();
        TextView name = text(item.name, 15, TEXT, true); card.addView(name);
        String meta = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(item.date * 1000L));
        if (item.size > 0) meta += "  ·  " + Formatter.formatShortFileSize(this, item.size);
        TextView detail = text(meta, 11, MUTED, false); card.addView(detail); margins(detail, 0, 3, 0, 10);
        Button play = button("REPRODUZIR", true); card.addView(play, new LinearLayout.LayoutParams(-1, dp(46)));
        play.setOnClickListener(v -> play(item.uri));
        return card;
    }

    private List<Item> queryVideos() {
        List<Item> out = new ArrayList<>();
        Uri collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] projection = new String[]{MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.SIZE};
        String selection = null;
        String[] args = null;
        if (Build.VERSION.SDK_INT >= 29) {
            selection = MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?";
            args = new String[]{Environment.DIRECTORY_MOVIES + "/EstradaPlayComunista/%"};
        } else {
            projection = new String[]{MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATA};
        }
        try (Cursor c = getContentResolver().query(collection, projection, selection, args, MediaStore.Video.Media.DATE_ADDED + " DESC")) {
            if (c == null) return out;
            int idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
            int dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED);
            int sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
            int dataCol = Build.VERSION.SDK_INT < 29 ? c.getColumnIndex(MediaStore.Video.Media.DATA) : -1;
            while (c.moveToNext()) {
                if (dataCol >= 0) {
                    String data = c.getString(dataCol);
                    if (data == null || !data.replace('\\', '/').contains("/Movies/EstradaPlayComunista/")) continue;
                }
                Item i = new Item();
                i.id = c.getLong(idCol); i.name = c.getString(nameCol); i.date = c.getLong(dateCol); i.size = c.getLong(sizeCol);
                i.uri = ContentUris.withAppendedId(collection, i.id);
                out.add(i);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private void play(Uri uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "video/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Throwable e) { Toast.makeText(this, "Nenhum player de vídeo disponível.", Toast.LENGTH_SHORT).show(); }
    }

    private void openGallery() {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*");
            startActivity(i);
        } catch (Throwable e) {
            Toast.makeText(this, "Abra a Galeria e procure Movies/EstradaPlayComunista.", Toast.LENGTH_LONG).show();
        }
    }

    private LinearLayout card() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(14), dp(13), dp(14), dp(13));
        v.setBackground(box(PANEL, 14, BORDER));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, 0, 0, dp(8)); v.setLayoutParams(p);
        return v;
    }

    private Button button(String value, boolean primary) {
        Button b = new Button(this); b.setText(value); b.setTextSize(12); b.setTextColor(TEXT); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); b.setAllCaps(false);
        b.setBackground(box(primary ? RED : Color.rgb(24, 17, 20), 14, primary ? RED : BORDER)); return b;
    }
    private TextView text(String value, float size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setLineSpacing(0, 1.08f); if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t;
    }
    private GradientDrawable box(int color, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); if (stroke != 0) d.setStroke(dp(1), stroke); return d;
    }
    private void margins(View v, int l, int t, int r, int b) {
        if (!(v.getLayoutParams() instanceof LinearLayout.LayoutParams)) return; LinearLayout.LayoutParams p = (LinearLayout.LayoutParams)v.getLayoutParams(); p.setMargins(dp(l), dp(t), dp(r), dp(b)); v.setLayoutParams(p);
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
'''
write('app/src/main/java/com/estradaplay/comunista/VideoLibraryActivity.java', library)

print('Patched Estrada Play Comunista 1.3.1: public MediaStore videos + in-app video library + legacy migration.')

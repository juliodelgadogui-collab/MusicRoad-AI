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

# Version + CameraX. Camera is optional at install/runtime.
build = read('app/build.gradle')
build = re.sub(r'versionCode\s+\d+', 'versionCode 130', build, count=1)
build = re.sub(r"versionName\s+'[^']+'", "versionName '1.3.0'", build, count=1)
if "androidx.camera:camera-core" not in build:
    build = build.replace(
        "    implementation 'org.maplibre.gl:android-sdk:13.4.1'\n",
        "    implementation 'org.maplibre.gl:android-sdk:13.4.1'\n"
        "    implementation 'androidx.camera:camera-core:1.4.2'\n"
        "    implementation 'androidx.camera:camera-camera2:1.4.2'\n"
        "    implementation 'androidx.camera:camera-lifecycle:1.4.2'\n"
        "    implementation 'androidx.camera:camera-view:1.4.2'\n"
        "    implementation 'androidx.camera:camera-video:1.4.2'\n"
    )
write('app/build.gradle', build)

manifest = read('app/src/main/AndroidManifest.xml')
if 'android.permission.CAMERA' not in manifest:
    manifest = manifest.replace(
        '    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />\n',
        '    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />\n'
        '    <uses-permission android:name="android.permission.CAMERA" />\n'
        '    <uses-feature android:name="android.hardware.camera.any" android:required="false" />\n'
    )
if 'android:name=".CameraActivity"' not in manifest:
    anchor = '''        <activity\n            android:name=".MainActivity"'''
    camera_activity = '''        <activity\n            android:name=".CameraActivity"\n            android:exported="false"\n            android:configChanges="orientation|screenSize|keyboardHidden|uiMode"\n            android:screenOrientation="${appOrientation}" />\n\n'''
    if anchor not in manifest:
        raise SystemExit('MainActivity manifest anchor missing')
    manifest = manifest.replace(anchor, camera_activity + anchor, 1)
write('app/src/main/AndroidManifest.xml', manifest)

# Add camera module to Home. Opening settings does NOT request permission.
main_rel = 'app/src/main/java/com/estradaplay/comunista/MainActivity.java'
main = read(main_rel)
marker = '// OPTIONAL_CAMERA_V130_HOME'
if marker not in main:
    anchor = '        page.addView(copilot); margins(copilot, 0, 14, 0, 0);\n'
    if anchor not in main:
        raise SystemExit('copilot home anchor missing')
    camera_card = r'''

        // OPTIONAL_CAMERA_V130_HOME: camera remains opt-in and asks permission only inside CameraActivity.
        boolean cameraEnabled = getSharedPreferences("epc_camera_v1", MODE_PRIVATE).getBoolean("enabled", false);
        LinearLayout camera = row();
        camera.setGravity(Gravity.CENTER_VERTICAL);
        camera.setPadding(dp(15), dp(13), dp(13), dp(13));
        camera.setBackground(bg(Color.rgb(17, 9, 11), 2, cameraEnabled ? Color.rgb(174, 38, 52) : BORDER));
        LinearLayout cameraInfo = column();
        cameraInfo.addView(overline("CÂMERA DE BORDO", cameraEnabled ? GREEN : ACCENT));
        cameraInfo.addView(text(cameraEnabled ? "Visão da estrada ativada" : "Uso opcional da câmera", 17, TEXT, true));
        cameraInfo.addView(text(cameraEnabled
                ? "A câmera só abre quando você entra no modo de bordo."
                : "Desligada por padrão. Nenhuma permissão é pedida até você ativar.", 10, MUTED, false));
        camera.addView(cameraInfo, new LinearLayout.LayoutParams(0, -2, 1));
        Button cameraButton = compactButton(cameraEnabled ? "ABRIR" : "CONFIGURAR");
        camera.addView(cameraButton, lp(112, 48));
        cameraButton.setOnClickListener(v -> startActivity(new Intent(this, CameraActivity.class)));
        page.addView(camera); margins(camera, 0, 9, 0, 0);
'''
    main = main.replace(anchor, anchor + camera_card, 1)
write(main_rel, main)

camera_java = r'''package com.estradaplay.comunista;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
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
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Optional road camera. No camera access is attempted until the user explicitly
 * enables it. Video is written only to this app's local external-files folder.
 */
public final class CameraActivity extends ComponentActivity {
    private static final String PREFS = "epc_camera_v1";
    private static final String KEY_ENABLED = "enabled";
    private static final int REQ_CAMERA = 1901;

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
        privacy.addView(text("• Nenhum vídeo é enviado ao servidor.\n• A permissão só é pedida quando você toca em ATIVAR.\n• Você pode desativar novamente a qualquer momento.", 12, TEXT, false));
        page.addView(privacy, new LinearLayout.LayoutParams(-1, -2)); margins(privacy, 0, 24, 0, 18);

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
        TextView local = text("GRAVAÇÃO LOCAL · NADA É ENVIADO AO SERVIDOR", 9, MUTED, true);
        local.setLetterSpacing(0.08f);
        bottom.addView(local);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        recordButton = button("GRAVAR", true);
        recordButton.setEnabled(false);
        Button disable = button("DESATIVAR", false);
        Button back = button("VOLTAR", false);
        actions.addView(recordButton, new LinearLayout.LayoutParams(0, dp(54), 1));
        LinearLayout.LayoutParams dp1 = new LinearLayout.LayoutParams(0, dp(54), 1); dp1.setMargins(dp(7), 0, 0, 0); actions.addView(disable, dp1);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(54), 1); bp.setMargins(dp(7), 0, 0, 0); actions.addView(back, bp);
        bottom.addView(actions); margins(actions, 0, 10, 0, 0);
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
        File base = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (base == null) base = getFilesDir();
        File dir = new File(base, "EstradaPlayComunista");
        if (!dir.exists() && !dir.mkdirs()) {
            toast("Não foi possível preparar a pasta de gravação.");
            return;
        }
        String name = "EPC-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".mp4";
        File file = new File(dir, name);
        try {
            FileOutputOptions options = new FileOutputOptions.Builder(file).build();
            recording = videoCapture.getOutput().prepareRecording(this, options)
                    .start(getMainExecutor(), event -> {
                        if (event instanceof VideoRecordEvent.Start) {
                            if (recordButton != null) recordButton.setText("PARAR");
                            if (cameraState != null) cameraState.setText("● GRAVANDO LOCALMENTE");
                        } else if (event instanceof VideoRecordEvent.Finalize) {
                            VideoRecordEvent.Finalize done = (VideoRecordEvent.Finalize) event;
                            recording = null;
                            if (recordButton != null) recordButton.setText("GRAVAR");
                            if (cameraState != null) cameraState.setText("CÂMERA DE BORDO · ATIVA");
                            if (done.hasError()) toast("A gravação foi encerrada com erro.");
                            else toast("Vídeo salvo somente neste aparelho.");
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
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
}
'''
write('app/src/main/java/com/estradaplay/comunista/CameraActivity.java', camera_java)

print('OPTIONAL_CAMERA_V130 applied')

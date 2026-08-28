#!/usr/bin/env python3
from pathlib import Path
import re

APP = Path('estrada-play-comunista-app')
if not APP.exists():
    raise SystemExit('Generate estrada-play-comunista-app before applying design v1.1.0')


def read(rel):
    return (APP / rel).read_text(encoding='utf-8')


def write(rel, content):
    path = APP / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding='utf-8')


def must_replace(text, old, new, label):
    if old not in text:
        raise SystemExit(f'Patch anchor not found: {label}')
    return text.replace(old, new, 1)

# Product version.
build = read('app/build.gradle')
build = re.sub(r'versionCode\s+\d+', 'versionCode 110', build, count=1)
build = re.sub(r"versionName\s+'[^']+'", "versionName '1.1.0'", build, count=1)
write('app/build.gradle', build)

# Launcher identity and dedicated splash theme.
manifest = read('app/src/main/AndroidManifest.xml')
manifest = must_replace(
    manifest,
    '        android:label="Estrada Play Comunista"\n        android:supportsRtl="true"',
    '        android:label="Estrada Play Comunista"\n        android:icon="@mipmap/ic_launcher"\n        android:roundIcon="@mipmap/ic_launcher_round"\n        android:supportsRtl="true"',
    'application launcher icon'
)
manifest = must_replace(
    manifest,
    '            android:launchMode="singleTask"\n            android:screenOrientation="fullSensor">',
    '            android:launchMode="singleTask"\n            android:screenOrientation="fullSensor"\n            android:theme="@style/Theme.EstradaPlay.Splash">',
    'GateActivity splash theme'
)
write('app/src/main/AndroidManifest.xml', manifest)

# Colors and native vector/adaptive icon. No PNG/JPG assets are used.
write('app/src/main/res/values/colors.xml', '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="epc_icon_bg">#B90F22</color>
    <color name="epc_splash_bg">#080507</color>
    <color name="epc_gold">#F1C84B</color>
</resources>
''')

legacy_icon = '''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:fillColor="#B90F22" android:pathData="M0,0h108v108h-108z" />
    <path android:fillColor="#FFFFFF" android:pathData="M54,17 L82,91 L26,91 Z" />
    <path android:fillColor="#B90F22" android:pathData="M54,35 L64,91 L44,91 Z" />
    <path android:fillColor="#F1C84B" android:pathData="M27,18 L30.2,24.4 L37.2,25.4 L32.1,30.3 L33.3,37.2 L27,33.9 L20.7,37.2 L21.9,30.3 L16.8,25.4 L23.8,24.4 Z" />
</vector>
'''
write('app/src/main/res/mipmap-anydpi/ic_launcher.xml', legacy_icon)
write('app/src/main/res/mipmap-anydpi/ic_launcher_round.xml', legacy_icon)

foreground = '''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:fillColor="#FFFFFF" android:pathData="M54,15 L83,94 L25,94 Z" />
    <path android:fillColor="#B90F22" android:pathData="M54,34 L64,94 L44,94 Z" />
    <path android:fillColor="#F1C84B" android:pathData="M28,16 L31.2,22.4 L38.2,23.4 L33.1,28.3 L34.3,35.2 L28,31.9 L21.7,35.2 L22.9,28.3 L17.8,23.4 L24.8,22.4 Z" />
</vector>
'''
write('app/src/main/res/drawable/ic_launcher_foreground.xml', foreground)

adaptive = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/epc_icon_bg" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
'''
write('app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml', adaptive)
write('app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml', adaptive)

adaptive_mono = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/epc_icon_bg" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
'''
write('app/src/main/res/mipmap-anydpi-v33/ic_launcher.xml', adaptive_mono)
write('app/src/main/res/mipmap-anydpi-v33/ic_launcher_round.xml', adaptive_mono)

# Splash style for pre-Android 12 and Android 12+ system splash.
styles = read('app/src/main/res/values/styles.xml')
styles = must_replace(
    styles,
    '    <style name="Theme.EstradaPlay.Dialog"',
    '''    <style name="Theme.EstradaPlay.Splash" parent="@style/Theme.EstradaPlay">
        <item name="android:windowBackground">@color/epc_splash_bg</item>
        <item name="android:statusBarColor">@color/epc_splash_bg</item>
        <item name="android:navigationBarColor">@color/epc_splash_bg</item>
        <item name="android:windowDisablePreview">false</item>
    </style>

    <style name="Theme.EstradaPlay.Dialog"''',
    'base splash style'
)
write('app/src/main/res/values/styles.xml', styles)

write('app/src/main/res/values-v31/styles.xml', '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.EstradaPlay.Splash" parent="@style/Theme.EstradaPlay">
        <item name="android:windowSplashScreenBackground">@color/epc_splash_bg</item>
        <item name="android:windowSplashScreenAnimatedIcon">@drawable/ic_launcher_foreground</item>
        <item name="android:windowSplashScreenAnimationDuration">280</item>
        <item name="android:postSplashScreenTheme">@style/Theme.EstradaPlay</item>
        <item name="android:statusBarColor">@color/epc_splash_bg</item>
        <item name="android:navigationBarColor">@color/epc_splash_bg</item>
    </style>
</resources>
''')

# A reusable brand mark rendered with Canvas. This is UI code, not an image asset.
write('app/src/main/java/com/estradaplay/comunista/BrandMarkView.java', r'''package com.estradaplay.comunista;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

final class BrandMarkView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path road = new Path();
    private final Path lane = new Path();
    private final Path star = new Path();

    BrandMarkView(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float size = Math.min(w, h);
        float left = (w - size) / 2f;
        float top = (h - size) / 2f;
        float r = size * 0.24f;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(185, 15, 34));
        paint.setShadowLayer(size * 0.10f, 0f, size * 0.045f, 0x55000000);
        canvas.drawRoundRect(new RectF(left, top, left + size, top + size), r, r, paint);
        paint.clearShadowLayer();

        road.reset();
        road.moveTo(left + size * 0.50f, top + size * 0.16f);
        road.lineTo(left + size * 0.79f, top + size * 0.86f);
        road.lineTo(left + size * 0.21f, top + size * 0.86f);
        road.close();
        paint.setColor(Color.WHITE);
        canvas.drawPath(road, paint);

        lane.reset();
        lane.moveTo(left + size * 0.50f, top + size * 0.35f);
        lane.lineTo(left + size * 0.60f, top + size * 0.86f);
        lane.lineTo(left + size * 0.40f, top + size * 0.86f);
        lane.close();
        paint.setColor(Color.rgb(185, 15, 34));
        canvas.drawPath(lane, paint);

        float cx = left + size * 0.265f;
        float cy = top + size * 0.245f;
        float outer = size * 0.105f;
        float inner = outer * 0.44f;
        star.reset();
        for (int i = 0; i < 10; i++) {
            double a = -Math.PI / 2 + i * Math.PI / 5;
            float rr = (i % 2 == 0) ? outer : inner;
            float x = cx + (float)Math.cos(a) * rr;
            float y = cy + (float)Math.sin(a) * rr;
            if (i == 0) star.moveTo(x, y); else star.lineTo(x, y);
        }
        star.close();
        paint.setColor(Color.rgb(241, 200, 75));
        canvas.drawPath(star, paint);
    }
}
''')

# Visible branded intro. Android's system splash is followed by this short in-app intro,
# so the product never appears to jump straight into the dashboard.
write('app/src/main/java/com/estradaplay/comunista/GateActivity.java', r'''package com.estradaplay.comunista;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

public final class GateActivity extends ComponentActivity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean launched;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(8, 5, 7));
        getWindow().setNavigationBarColor(Color.rgb(8, 5, 7));
        showBrandIntro();
        ui.postDelayed(this::openApp, 900L);
    }

    private void showBrandIntro() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(8, 5, 7));
        setContentView(root);

        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);
        center.setPadding(dp(30), dp(28), dp(30), dp(28));
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-1, -1);
        root.addView(center, cp);

        BrandMarkView mark = new BrandMarkView(this);
        center.addView(mark, new LinearLayout.LayoutParams(dp(104), dp(104)));

        TextView kicker = label("ESTRADA PLAY", 12, Color.rgb(224, 30, 47), true);
        kicker.setLetterSpacing(0.18f);
        center.addView(kicker, wrap());
        margins(kicker, 0, 24, 0, 3);

        TextView title = label("COMUNISTA", 31, Color.rgb(247, 245, 246), true);
        title.setLetterSpacing(0.05f);
        center.addView(title, wrap());

        TextView subtitle = label("NAVEGAÇÃO  ·  MÚSICA  ·  PROTEÇÃO", 11, Color.rgb(148, 139, 144), true);
        subtitle.setLetterSpacing(0.08f);
        center.addView(subtitle, wrap());
        margins(subtitle, 0, 11, 0, 0);

        View line = new View(this);
        line.setBackgroundColor(Color.rgb(224, 30, 47));
        center.addView(line, new LinearLayout.LayoutParams(dp(54), dp(3)));
        margins(line, 0, 24, 0, 0);
    }

    private void openApp() {
        if (launched || isFinishing()) return;
        launched = true;
        Intent next = new Intent(this, MainActivity.class);
        startActivity(next);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private TextView label(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(-2, -2);
    }

    private void margins(View v, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) v.getLayoutParams();
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        v.setLayoutParams(p);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
''')

# Replace generic EC badges with the actual native brand mark in key onboarding screens.
main_rel = 'app/src/main/java/com/estradaplay/comunista/MainActivity.java'
main = read(main_rel)
main = main.replace('TextView mark = badge("EC", ACCENT, ACCENT_SOFT);', 'BrandMarkView mark = new BrandMarkView(this);')

start = main.find('    private void showHome() {')
end = main.find('    private void showMusic() {', start)
if start < 0 or end < 0:
    raise SystemExit('showHome boundaries not found')
new_home = r'''    private void showHome() {
        clearDownloadViews();
        roadLiveState = null;
        roadLiveDetail = null;
        root.removeAllViews();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout page = column();
        page.setPadding(dp(18), dp(12), dp(18), dp(28));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        page.addView(topBar("Início"));

        LinearLayout hero = featureCard(ACCENT);
        page.addView(hero); margins(hero, 0, 8, 0, 0);

        LinearLayout brandRow = row();
        brandRow.setGravity(Gravity.CENTER_VERTICAL);
        BrandMarkView mark = new BrandMarkView(this);
        brandRow.addView(mark, lp(66, 66));

        LinearLayout identity = column();
        TextView brandOver = overline("ESTRADA PLAY", ACCENT); identity.addView(brandOver);
        TextView brandName = text("Comunista", 27, TEXT, true); identity.addView(brandName); margins(brandName, 0, 2, 0, 1);
        TextView brandSub = text("Copiloto de estrada", 11, MUTED, false); identity.addView(brandSub);
        brandRow.addView(identity, new LinearLayout.LayoutParams(0, -2, 1)); margins(identity, 14, 0, 8, 0);
        TextView status = chip(hasLocationPermission() ? "PROTEÇÃO ATIVA" : "GPS PENDENTE", hasLocationPermission() ? GREEN : ACCENT, hasLocationPermission() ? GREEN_SOFT : ACCENT_SOFT);
        brandRow.addView(status);
        hero.addView(brandRow);

        TextView title = text("Sua estrada em um só lugar", 29, TEXT, true);
        hero.addView(title); margins(title, 0, 22, 0, 5);
        TextView body = text("Navegação opcional, música offline e alertas rodoviários trabalhando juntos sem deixar a tela carregada.", 13, MUTED, false);
        hero.addView(body);

        LinearLayout capabilities = row();
        TextView c1 = chip("MAPA LIVRE", ACCENT, ACCENT_SOFT); capabilities.addView(c1);
        TextView c2 = chip("VOZ LOCAL", BLUE, BLUE_SOFT); capabilities.addView(c2); margins(c2, 7, 0, 0, 0);
        TextView c3 = chip("OFFLINE", GREEN, GREEN_SOFT); capabilities.addView(c3); margins(c3, 7, 0, 0, 0);
        hero.addView(capabilities); margins(capabilities, 0, 16, 0, 0);

        TextView driveLabel = overline("COMEÇAR A VIAGEM", MUTED);
        page.addView(driveLabel); margins(driveLabel, 2, 20, 0, 8);
        LinearLayout drive = card(); page.addView(drive);
        TextView driveTitle = text("Como você quer dirigir?", 20, TEXT, true); drive.addView(driveTitle);
        TextView driveBody = text("Sem destino o app funciona como proteção passiva. Com destino, acrescenta rota e orientação.", 12, MUTED, false); drive.addView(driveBody); margins(driveBody, 0, 4, 0, 14);

        Button map = button("DIRIGIR SEM DESTINO", true);
        drive.addView(map, lp(-1, 58));
        map.setOnClickListener(v -> {
            DestinationStore.clear(this);
            openCockpit();
        });

        Button destination = button("DEFINIR DESTINO", false);
        drive.addView(destination, lp(-1, 56)); margins(destination, 0, 9, 0, 0);
        destination.setOnClickListener(v -> startActivity(new Intent(this, DestinationActivity.class)));

        TextView musicLabel = overline("ENTRETENIMENTO", MUTED);
        page.addView(musicLabel); margins(musicLabel, 2, 20, 0, 8);
        LinearLayout media = card(); page.addView(media);
        LinearLayout mediaTop = row(); mediaTop.setGravity(Gravity.CENTER_VERTICAL);
        TextView note = badge("♪", ACCENT, ACCENT_SOFT); mediaTop.addView(note, lp(46, 46));
        LinearLayout mediaMeta = column();
        mediaMeta.addView(text("Música offline", 18, TEXT, true));
        boolean hasDownloaded = library.hasDownloadedHint();
        mediaMeta.addView(text(hasDownloaded ? "Biblioteca pronta no aparelho" : "Escolha o que levar para a estrada", 11, MUTED, false));
        mediaTop.addView(mediaMeta, new LinearLayout.LayoutParams(0, -2, 1)); margins(mediaMeta, 12, 0, 0, 0);
        media.addView(mediaTop);

        Button music = button(!hasDownloaded ? "ESCOLHER MÚSICAS" : "ABRIR PLAYER", false);
        media.addView(music, lp(-1, 54)); margins(music, 0, 12, 0, 0);
        music.setOnClickListener(v -> {
            if (!hasDownloaded) {
                if (online()) loadCatalogAndOpenChooser(false); else toast("Conecte-se para escolher músicas.");
            } else showMusic();
        });

        LinearLayout tools = row();
        Button libraryButton = compactButton("BIBLIOTECA");
        tools.addView(libraryButton, new LinearLayout.LayoutParams(0, dp(50), 1));
        Button accountButton = compactButton("CONTA");
        tools.addView(accountButton, new LinearLayout.LayoutParams(0, dp(50), 1)); margins(accountButton, 8, 0, 0, 0);
        page.addView(tools); margins(tools, 0, 12, 0, 0);
        libraryButton.setOnClickListener(v -> {
            if (online()) loadCatalogAndOpenChooser(false); else toast("Conecte-se para sincronizar pastas.");
        });
        accountButton.setOnClickListener(v -> showAccount());
    }

'''
main = main[:start] + new_home + main[end:]
write(main_rel, main)

# Update the product note saved in the generated app tree.
write('README-DESIGN-1.1.0.md', '''# Estrada Play Comunista 1.1.0 — identidade nativa

- Splash nativo Android 12+ e apresentação curta compatível com versões anteriores.
- Ícone adaptive/round/monochrome feito somente com VectorDrawable XML.
- Símbolo de marca desenhado em Canvas dentro do app; nenhum PNG/JPG/WebP foi criado.
- Home reorganizada em blocos: marca/status, viagem, entretenimento e utilidades.
- Package permanece `com.estradaplay.comunista`, independente do EstradaPlay normal.
''')

print('Estrada Play Comunista design v1.1.0 applied')

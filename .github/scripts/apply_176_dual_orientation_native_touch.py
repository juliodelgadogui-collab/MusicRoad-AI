from pathlib import Path
import re

repo = Path(__file__).resolve().parents[2]
app = repo / 'estradaplay-app'

# -----------------------------------------------------------------------------
# 1) Stop forcing a compatibility-rotated landscape surface.
#    Several automotive ROMs map TextureView/MapLibre touch correctly while
#    ordinary Android Views keep the wrong transformed coordinates when the
#    Activity is locked to sensorLandscape. Let Android own the real display
#    orientation and support portrait + landscape natively.
# -----------------------------------------------------------------------------
manifest = app / 'app/src/main/AndroidManifest.xml'
s = manifest.read_text(encoding='utf-8')
s = s.replace('android:screenOrientation="sensorLandscape"', 'android:screenOrientation="fullSensor"')
if 'android:resizeableActivity="true"' not in s:
    s = s.replace('android:hardwareAccelerated="true"', 'android:hardwareAccelerated="true"\n        android:resizeableActivity="true"', 1)
if 'sensorLandscape' in s:
    raise SystemExit('1.7.6 manifest still forces landscape')
if s.count('android:screenOrientation="fullSensor"') < 4:
    raise SystemExit('1.7.6 dual orientation was not applied to all activities')
manifest.write_text(s, encoding='utf-8')

# -----------------------------------------------------------------------------
# 2) Road cockpit: Map is one top-level child; control panels are separate
#    top-level siblings added AFTER it. This avoids TextureView/vendor Z-order
#    routing from swallowing taps intended for buttons.
# -----------------------------------------------------------------------------
p = app / 'app/src/main/java/com/estradaplay/app/RoadMapActivity.java'
s = p.read_text(encoding='utf-8')

pattern = re.compile(
    r'    private void buildResponsiveUi\(\) \{.*?\n'
    r'    \}\n\n'
    r'    private LinearLayout buildRail',
    re.S,
)
replacement = r'''    // DUAL_ORIENTATION_TOUCH_V176: native portrait/landscape + top-level controls.
    private void buildResponsiveUi() {
        int[] size = screenSize();
        int width = size[0];
        int height = size[1];
        lastWidth = width;
        lastHeight = height;

        if (roadMap != null) {
            try { roadMap.onPauseMap(); roadMap.onStopMap(); roadMap.onDestroyMap(); } catch (Throwable ignored) {}
        }
        root.removeAllViews();
        touchTargets.clear();
        routedTouchTarget = null;

        if (height > width) buildPortraitUi(width, height);
        else buildLandscapeUi(width, height);
    }

    private void buildLandscapeUi(int width, int height) {
        float ratio = height <= 0 ? 1.8f : (float)width / (float)height;
        boolean compact = ratio < 1.64f || height < dp(420);
        boolean ultrawide = ratio >= 2.12f;

        int outer = clamp(Math.round(height * 0.022f), dp(8), dp(18));
        int gap = clamp(Math.round(height * 0.016f), dp(7), dp(14));
        int railWidth = clamp(Math.round(width * (compact ? 0.092f : 0.078f)), dp(70), dp(112));
        int rightWidth = compact
                ? clamp(Math.round(width * 0.285f), dp(210), dp(330))
                : ultrawide
                ? clamp(Math.round(width * 0.275f), dp(280), dp(430))
                : clamp(Math.round(width * 0.255f), dp(240), dp(370));

        int mapLeft = outer + railWidth + gap;
        int mapRight = width - outer - rightWidth - gap;

        // Map first. It can never cover controls added afterwards.
        FrameLayout mapPane = buildMapPane(height, compact);
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(-1, -1);
        mp.setMargins(mapLeft, outer, width - mapRight, outer);
        root.addView(mapPane, mp);

        LinearLayout rail = buildRail(height, compact);
        rail.setClickable(true);
        rail.setFocusable(false);
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(railWidth, -1, Gravity.LEFT);
        rp.setMargins(outer, outer, 0, outer);
        root.addView(rail, rp);

        LinearLayout right = buildRightPanel(height, compact, ultrawide);
        right.setClickable(true);
        right.setFocusable(false);
        FrameLayout.LayoutParams qp = new FrameLayout.LayoutParams(rightWidth, -1, Gravity.RIGHT);
        qp.setMargins(0, outer, outer, outer);
        root.addView(right, qp);

        if (Build.VERSION.SDK_INT >= 21) {
            rail.setElevation(dp(48));
            right.setElevation(dp(48));
        }
        rail.bringToFront();
        right.bringToFront();
        addTopLevelRecenter(mapLeft, outer, mapRight, height - outer, false);
    }

    private void buildPortraitUi(int width, int height) {
        int outer = clamp(Math.round(width * 0.028f), dp(10), dp(18));
        int gap = clamp(Math.round(height * 0.014f), dp(8), dp(14));
        int bottomH = clamp(Math.round(height * 0.235f), dp(172), dp(250));
        int mapBottom = height - outer - bottomH - gap;

        FrameLayout mapPane = buildMapPane(Math.max(dp(360), mapBottom - outer), true);
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(-1, mapBottom - outer);
        mp.setMargins(outer, outer, outer, 0);
        root.addView(mapPane, mp);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(16), dp(13), dp(16), dp(13));
        controls.setBackground(panel(24, SURFACE, BORDER));
        controls.setClickable(true);
        controls.setFocusable(false);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.addView(label("EstradaPlay", 18, TEXT, true));
        TextView mode = label("SISTEMA AUTOMOTIVO · VERTICAL", 8, GREEN, true);
        mode.setLetterSpacing(0.10f);
        brand.addView(mode);
        head.addView(brand, new LinearLayout.LayoutParams(0, -2, 1f));
        clockText = label("--:--", 20, TEXT, true);
        clockText.setGravity(Gravity.CENTER);
        head.addView(clockText, new LinearLayout.LayoutParams(dp(72), -2));
        controls.addView(head);

        mapStateText = label("Mapa e proteção preparando…", 9, MUTED, false);
        LinearLayout.LayoutParams msp = new LinearLayout.LayoutParams(-1, 0, 1f);
        msp.setMargins(0, dp(8), 0, dp(8));
        controls.addView(mapStateText, msp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        Button music = action("MÚSICA", true);
        Button home = action("INÍCIO", false);
        int actionH = clamp(Math.round(bottomH * 0.32f), dp(50), dp(64));
        actions.addView(music, new LinearLayout.LayoutParams(0, actionH, 1f));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0, actionH, 1f);
        hp.setMargins(dp(10), 0, 0, 0);
        actions.addView(home, hp);
        music.setOnClickListener(v -> openMain());
        home.setOnClickListener(v -> openMain());
        controls.addView(actions);

        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-1, bottomH, Gravity.BOTTOM);
        cp.setMargins(outer, 0, outer, outer);
        root.addView(controls, cp);
        if (Build.VERSION.SDK_INT >= 21) controls.setElevation(dp(48));
        controls.bringToFront();

        addTopLevelRecenter(outer, outer, width - outer, mapBottom, true);
        updateMapStatus();
    }

    private void addTopLevelRecenter(int mapLeft, int mapTop, int mapRight, int mapBottom, boolean portrait) {
        Button recenter = action("CENTRALIZAR", false);
        recenter.setOnClickListener(v -> { if (roadMap != null) roadMap.recenter(); });
        int w = portrait ? dp(112) : dp(116);
        int h = portrait ? dp(48) : dp(52);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h);
        p.leftMargin = Math.max(mapLeft + dp(8), mapRight - w - dp(14));
        p.topMargin = Math.max(mapTop + dp(76), mapTop + ((mapBottom - mapTop - h) / 2));
        root.addView(recenter, p);
        if (Build.VERSION.SDK_INT >= 21) recenter.setElevation(dp(64));
        recenter.bringToFront();
    }

    private LinearLayout buildRail'''
s, n = pattern.subn(replacement, s, count=1)
if n != 1:
    raise SystemExit('1.7.6 responsive layout anchor not found')

# The recenter control inside the MapLibre pane is intentionally removed. The
# top-level recenter above is outside the map child hierarchy and therefore gets
# first-class Android View touch dispatch.
old_recenter = '''        Button recenter = action("CENTRALIZAR", false);
        int recW = compact ? dp(96) : dp(116);
        int recH = clamp(Math.round(height * 0.075f), dp(40), dp(54));
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(recW, recH, Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        rp.setMargins(0, 0, inset, 0);
        pane.addView(recenter, rp);
        recenter.setOnClickListener(v -> { if (roadMap != null) roadMap.recenter(); });

'''
if old_recenter not in s:
    raise SystemExit('1.7.6 in-map recenter anchor not found')
s = s.replace(old_recenter, '', 1)

# Remove all custom per-button ACTION_DOWN routing. Normal Button dispatch is
# more reliable once the Activity is no longer vendor-rotated and controls live
# above the map as top-level siblings.
pattern = re.compile(
    r'    private void registerTouchTarget\(View view\) \{.*?\n'
    r'    \}\n\n'
    r'    private boolean touchInside',
    re.S,
)
replacement = '''    private void registerTouchTarget(View view) {
        if (view == null) return;
        view.setEnabled(true);
        view.setClickable(true);
        view.setLongClickable(false);
        view.setFocusable(true);
        view.setFocusableInTouchMode(false);
        view.setOnTouchListener(null);
        view.setOnGenericMotionListener(null);
        if (Build.VERSION.SDK_INT >= 21) view.setElevation(dp(8));
        touchTargets.add(view);
    }

    private boolean touchInside'''
s, n = pattern.subn(replacement, s, count=1)
if n != 1:
    raise SystemExit('1.7.6 registerTouchTarget anchor not found')

p.write_text(s, encoding='utf-8')

# -----------------------------------------------------------------------------
# 3) MainActivity: apply the same normal interactive window policy to every
#    non-map screen (login, library, music, downloads, home).
# -----------------------------------------------------------------------------
p = app / 'app/src/main/java/com/estradaplay/app/MainActivity.java'
s = p.read_text(encoding='utf-8')
old = '''    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
'''
new = '''    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ensureInteractiveWindow176();
        getWindow().setStatusBarColor(BG);
'''
if old not in s:
    raise SystemExit('1.7.6 MainActivity onCreate anchor not found')
s = s.replace(old, new, 1)

marker = '''    @Override protected void onDestroy() {
'''
method = '''    // NATIVE_TOUCH_ALL_SCREENS_V176
    private void ensureInteractiveWindow176() {
        try {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            View decor = getWindow().getDecorView();
            if (decor != null) {
                decor.setEnabled(true);
                decor.setClickable(false);
                decor.setFocusable(false);
            }
            if (root != null) {
                root.setEnabled(true);
                root.setClickable(false);
                root.setFocusable(false);
            }
        } catch (Throwable ignored) {}
    }

    @Override protected void onResume() {
        super.onResume();
        ensureInteractiveWindow176();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) ensureInteractiveWindow176();
    }

'''
if marker not in s:
    raise SystemExit('1.7.6 MainActivity lifecycle anchor not found')
s = s.replace(marker, method + marker, 1)
p.write_text(s, encoding='utf-8')

# Version bump.
gradle = app / 'app/build.gradle'
s = gradle.read_text(encoding='utf-8')
s = s.replace('versionCode 17', 'versionCode 18')
s = s.replace("versionName '1.7.5'", "versionName '1.7.6'")
if "versionName '1.7.6'" not in s or 'versionCode 18' not in s:
    raise SystemExit('1.7.6 version bump failed')
gradle.write_text(s, encoding='utf-8')

print('EstradaPlay 1.7.6 dual orientation + native controls applied')

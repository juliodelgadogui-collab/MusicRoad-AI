from pathlib import Path

repo = Path(__file__).resolve().parents[2]
app = repo / 'estradaplay-app'

# 1.7.5 is intentionally a new build/version so the corrected cockpit cannot be
# confused with an older 1.7.4 APK already installed or cached on the head unit.
p = app / 'app/src/main/java/com/estradaplay/app/RoadMapActivity.java'
s = p.read_text(encoding='utf-8')

# Keep the Activity window touchable even if a vendor ROM/MapLibre lifecycle
# toggles compatibility flags after onCreate.
onresume_old = '''    @Override protected void onResume() {
        super.onResume();
        if (roadMap != null) roadMap.onResumeMap();
    }
'''
onresume_new = '''    @Override protected void onResume() {
        super.onResume();
        forceTouchableWindow();
        if (roadMap != null) roadMap.onResumeMap();
    }
'''
if onresume_old not in s:
    raise SystemExit('1.7.5 onResume anchor not found')
s = s.replace(onresume_old, onresume_new, 1)

marker = '''    @Override protected void onPause() {
'''
method = '''    // TOUCH_WINDOW_V175: re-assert a normal interactive application window.
    private void forceTouchableWindow() {
        try {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                    android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            View decor = getWindow().getDecorView();
            if (decor != null) {
                decor.setEnabled(true);
                decor.setFocusable(true);
                decor.setFocusableInTouchMode(true);
            }
            if (root != null) {
                root.setEnabled(true);
                root.setClickable(false);
                root.setFocusable(false);
            }
        } catch (Throwable ignored) {}
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) forceTouchableWindow();
    }

'''
if marker not in s:
    raise SystemExit('1.7.5 lifecycle marker not found')
s = s.replace(marker, method + marker, 1)

# Harden every cockpit control itself. 1.7.4 already activates on ACTION_DOWN;
# 1.7.5 additionally keeps controls enabled and raises them after attachment.
old = '''        view.setClickable(true);
        view.setLongClickable(false);
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        if (Build.VERSION.SDK_INT >= 21) view.setElevation(dp(24));
        touchTargets.add(view);
'''
new = '''        view.setEnabled(true);
        view.setClickable(true);
        view.setLongClickable(false);
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        if (Build.VERSION.SDK_INT >= 21) view.setElevation(dp(32));
        view.post(() -> {
            try { view.bringToFront(); } catch (Throwable ignored) {}
        });
        touchTargets.add(view);
'''
if old not in s:
    raise SystemExit('1.7.5 registerTouchTarget anchor not found')
s = s.replace(old, new, 1)

# Re-assert window state after the UI hierarchy is attached.
old = '''        buildResponsiveUi();
        startSafety();
'''
new = '''        buildResponsiveUi();
        forceTouchableWindow();
        startSafety();
'''
if old not in s:
    raise SystemExit('1.7.5 buildResponsiveUi anchor not found')
s = s.replace(old, new, 1)

p.write_text(s, encoding='utf-8')

gradle = app / 'app/build.gradle'
s = gradle.read_text(encoding='utf-8')
s = s.replace('versionCode 16', 'versionCode 17')
s = s.replace("versionName '1.7.4'", "versionName '1.7.5'")
if "versionName '1.7.5'" not in s or 'versionCode 17' not in s:
    raise SystemExit('1.7.5 version bump failed')
gradle.write_text(s, encoding='utf-8')

print('EstradaPlay 1.7.5 touch-window hardening applied')

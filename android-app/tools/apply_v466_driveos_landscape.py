from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/com/musicroad/ai'


def replace_once(path: Path, old: str, new: str, label: str):
    text = path.read_text()
    if old not in text:
        raise SystemExit(f'v4.6.6 DriveOS patch failed: {label} not found in {path.name}')
    path.write_text(text.replace(old, new, 1))


def patch_bridge():
    p = JAVA / 'NativeBridge.java'
    old = '    @JavascriptInterface public int versionCode(){return BuildConfig.VERSION_CODE;}\n'
    new = '''    @JavascriptInterface public int versionCode(){return BuildConfig.VERSION_CODE;}\n    @JavascriptInterface public void setLandscapeMode(boolean enabled){activity.runOnUiThread(()->{try{activity.setRequestedOrientation(enabled?android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE:android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);}catch(Exception ignored){}});}\n'''
    replace_once(p, old, new, 'landscape bridge')


def patch_version():
    p = ROOT / 'app/build.gradle'
    text = p.read_text()
    text = re.sub(r'versionCode\s+\d+', 'versionCode 23', text, count=1)
    text = re.sub(r"versionName\s+'[^']+'", "versionName '4.6.6'", text, count=1)
    p.write_text(text)


patch_bridge()
patch_version()
print('MusicRoad v4.6.6 DriveOS landscape patch applied')

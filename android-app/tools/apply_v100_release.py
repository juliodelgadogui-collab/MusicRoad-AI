from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app'
JAVA = APP / 'src/main/java/com/musicroad/ai'
RES = APP / 'src/main/res'


def replace_once(path: Path, old: str, new: str, label: str):
    text = path.read_text()
    if old not in text:
        raise SystemExit(f'MusicRoad 1.0 patch failed: {label} not found in {path.name}')
    path.write_text(text.replace(old, new, 1))


def patch_version():
    p = ROOT / 'app/build.gradle'
    text = p.read_text()
    text = re.sub(r'versionCode\s+\d+', 'versionCode 24', text, count=1)
    text = re.sub(r"versionName\s+'[^']+'", "versionName '1.0.0'", text, count=1)
    p.write_text(text)


def patch_identity():
    strings = RES / 'values/strings.xml'
    replace_once(strings, '<string name="app_name">MusicRoad AI</string>', '<string name="app_name">MusicRoad</string>', 'app label')

    manifest = APP / 'src/main/AndroidManifest.xml'
    replace_once(manifest, 'android:icon="@drawable/ic_notification"', 'android:icon="@drawable/ic_launcher"', 'launcher icon')
    replace_once(manifest, 'android:roundIcon="@drawable/ic_notification"', 'android:roundIcon="@drawable/ic_launcher_round"', 'round launcher icon')

    main = JAVA / 'MainActivity.java'
    replace_once(main, 'getWindow().setStatusBarColor(Color.rgb(8,17,31));', 'getWindow().setStatusBarColor(Color.rgb(7,11,16));', 'status bar color')
    replace_once(main, 'getWindow().setNavigationBarColor(Color.rgb(5,12,22));', 'getWindow().setNavigationBarColor(Color.rgb(5,8,12));', 'nav bar color')
    replace_once(main, 's.setUserAgentString(s.getUserAgentString()+" MusicRoadAndroid/4.2");', 's.setUserAgentString(s.getUserAgentString()+" MusicRoadAndroid/"+BuildConfig.VERSION_NAME);', 'webview user agent')
    replace_once(main, 'r.addRequestHeader("User-Agent","MusicRoadAndroid/4.2");', 'r.addRequestHeader("User-Agent","MusicRoadAndroid/"+BuildConfig.VERSION_NAME);', 'update user agent')


def write_icons():
    RES.joinpath('drawable').mkdir(parents=True, exist_ok=True)
    icon = '''<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="96" android:viewportHeight="96">
    <path android:fillColor="#0C1219" android:pathData="M8,0 L88,0 Q96,0 96,8 L96,88 Q96,96 88,96 L8,96 Q0,96 0,88 L0,8 Q0,0 8,0 Z"/>
    <path android:fillColor="#FF7A1A" android:pathData="M23,65 L34,65 L51,32 L40,32 Z"/>
    <path android:fillColor="#FF7A1A" android:pathData="M56,32 L67,32 L77,65 L66,65 Z"/>
    <path android:fillColor="#FF7A1A" android:pathData="M44,42 L53,42 L53,70 L44,70 Z"/>
    <path android:fillColor="#FFF1E1" android:pathData="M47,45 L50,45 L50,51 L47,51 Z M47,55 L50,55 L50,61 L47,61 Z M47,65 L50,65 L50,69 L47,69 Z"/>
</vector>'''
    RES.joinpath('drawable/ic_launcher.xml').write_text(icon)
    RES.joinpath('drawable/ic_launcher_round.xml').write_text(icon)


patch_version()
patch_identity()
write_icons()
print('MusicRoad 1.0 public release patch applied (versionCode 24)')

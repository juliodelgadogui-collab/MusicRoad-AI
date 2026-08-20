from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/com/musicroad/ai'


def replace_once(path: Path, old: str, new: str, label: str):
    text = path.read_text()
    if old not in text:
        raise SystemExit(f'v4.6.5 trial device patch failed: {label} not found in {path.name}')
    path.write_text(text.replace(old, new, 1))


def patch_bridge():
    p = JAVA / 'NativeBridge.java'
    replace_once(
        p,
        'import android.os.Environment;',
        'import android.os.Environment;\nimport android.provider.Settings;',
        'settings import'
    )
    replace_once(
        p,
        'import java.util.Set;',
        'import java.util.Set;\nimport java.security.MessageDigest;',
        'digest import'
    )
    old = '    @JavascriptInterface public String platform(){return "android";}\n    @JavascriptInterface public String version(){return BuildConfig.VERSION_NAME;}'
    new = '''    private static String sha256(String value){try{MessageDigest d=MessageDigest.getInstance("SHA-256");byte[] b=d.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte x:b)out.append(String.format(Locale.ROOT,"%02x",x&0xff));return out.toString();}catch(Exception e){return Integer.toHexString(value.hashCode());}}\n    @JavascriptInterface public String getTrialDeviceToken(){try{String id=Settings.Secure.getString(activity.getContentResolver(),Settings.Secure.ANDROID_ID);if(id==null||id.trim().isEmpty())id=activity.getSharedPreferences("musicroad_trial_identity",Context.MODE_PRIVATE).getString("fallback","");if(id==null||id.isEmpty()){id=java.util.UUID.randomUUID().toString();activity.getSharedPreferences("musicroad_trial_identity",Context.MODE_PRIVATE).edit().putString("fallback",id).apply();}return "android:"+sha256(activity.getPackageName()+"|"+id);}catch(Exception e){return "";}}\n    @JavascriptInterface public String platform(){return "android";}\n    @JavascriptInterface public String version(){return BuildConfig.VERSION_NAME;}'''
    replace_once(p, old, new, 'trial device bridge')


def patch_version():
    p = ROOT / 'app/build.gradle'
    text = p.read_text()
    text = re.sub(r'versionCode\s+\d+', 'versionCode 22', text, count=1)
    text = re.sub(r"versionName\s+'[^']+'", "versionName '4.6.5'", text, count=1)
    p.write_text(text)


patch_bridge()
patch_version()
print('MusicRoad v4.6.5 trial-device identity patch applied')

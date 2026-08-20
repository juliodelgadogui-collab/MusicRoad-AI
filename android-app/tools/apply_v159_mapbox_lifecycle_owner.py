from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app'
JAVA = APP / 'src/main/java/com/musicroad/ai'

main = JAVA / 'MainActivity.java'
s = main.read_text()

# Mapbox Maps SDK 11 observes the hosting Activity lifecycle through
# ViewTreeLifecycleOwner. A plain android.app.Activity does not provide it.
s = s.replace('import android.app.Activity;\n', '')
if 'import androidx.activity.ComponentActivity;' not in s:
    marker = 'import android.widget.Toast;\n'
    if marker not in s:
        raise SystemExit('v1.5.9: import insertion point not found')
    s = s.replace(marker, marker + '\nimport androidx.activity.ComponentActivity;\n')

s, n = re.subn(r'public class MainActivity extends Activity \{',
               'public class MainActivity extends ComponentActivity {', s, count=1)
if n != 1:
    raise SystemExit('v1.5.9: MainActivity superclass replacement failed')
main.write_text(s)

build = APP / 'build.gradle'
b = build.read_text()

# Keep the Mapbox dependency produced by the previous patch and add an explicit
# Activity implementation that is a LifecycleOwner and installs the view-tree owners.
if "androidx.activity:activity:1.10.1" not in b:
    if 'dependencies {' not in b:
        b += "\n\ndependencies {\n    implementation 'androidx.activity:activity:1.10.1'\n}\n"
    else:
        b = b.replace('dependencies {', "dependencies {\n    implementation 'androidx.activity:activity:1.10.1'", 1)

b = re.sub(r'versionCode\s+\d+', 'versionCode 35', b, count=1)
b = re.sub(r"versionName\s+'[^']+'", "versionName '1.5.9'", b, count=1)
build.write_text(b)

print('MusicRoad 1.5.9 Mapbox LifecycleOwner fix applied')

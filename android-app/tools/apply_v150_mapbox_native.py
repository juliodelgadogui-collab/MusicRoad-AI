from pathlib import Path
import re, shutil

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app'
JAVA = APP / 'src/main/java/com/musicroad/ai'
SRC = ROOT / 'mapbox-v150/NativeMapView.java'
DST = JAVA / 'NativeMapView.java'

if not SRC.exists():
    raise SystemExit('MusicRoad 1.5 Mapbox source missing')
shutil.copyfile(SRC, DST)

settings = ROOT / 'settings.gradle'
s = settings.read_text()
repo = "maven { url = uri('https://api.mapbox.com/downloads/v2/releases/maven') }"
if 'api.mapbox.com/downloads/v2/releases/maven' not in s:
    s = s.replace('mavenCentral() }', 'mavenCentral(); ' + repo + ' }')
settings.write_text(s)

build = APP / 'build.gradle'
b = build.read_text()
b = re.sub(r'versionCode\s+\d+', 'versionCode 30', b, count=1)
b = re.sub(r"versionName\s+'[^']+'", "versionName '1.5.0'", b, count=1)
if 'com.mapbox.maps:android-ndk27:11.28.3' not in b:
    b += "\n\ndependencies {\n    implementation 'com.mapbox.maps:android-ndk27:11.28.3'\n}\n"
build.write_text(b)

main = JAVA / 'MainActivity.java'
m = main.read_text()
m = m.replace('online()?"MAPA ONLINE":"OFFLINE"', 'online()?"MAPBOX":"OFFLINE"')
m = m.replace('Button route=btn(currentRouteCoords.length()>1?"RECALCULAR":"IR",true);panel.addView(route,new LinearLayout.LayoutParams(dp(116),dp(58)));margins(route,10,0,0,0);Button stop=btn("■",false);panel.addView(stop,new LinearLayout.LayoutParams(dp(58),dp(58)));margins(stop,8,0,0,0);',
              'Button route=btn(currentRouteCoords.length()>1?"↻":"IR",true);panel.addView(route,new LinearLayout.LayoutParams(dp(88),dp(58)));margins(route,7,0,0,0);Button stop=btn("■",false);panel.addView(stop,new LinearLayout.LayoutParams(dp(54),dp(58)));margins(stop,6,0,0,0);')
m = m.replace('b.setText("RECALCULAR")', 'b.setText("↻")')
main.write_text(m)

print('MusicRoad 1.5.0 Mapbox native map applied')

from pathlib import Path
import re

ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/'app/src/main/java/com/musicroad/ai'

p=JAVA/'MainActivity.java'
s=p.read_text()
old='shell.addView(main,new LinearLayout.LayoutParams(0,-1,1));'
new='shell.addView(main,isLandscape()?new LinearLayout.LayoutParams(0,-1,1):new LinearLayout.LayoutParams(-1,0,1));'
if old not in s:
    raise SystemExit('MusicRoad 1.3.1 fix failed: main shell layout anchor not found')
s=s.replace(old,new,1)
p.write_text(s)

b=ROOT/'app/build.gradle'
t=b.read_text()
t=re.sub(r'versionCode\s+\d+','versionCode 28',t,count=1)
t=re.sub(r"versionName\s+'[^']+'","versionName '1.3.1'",t,count=1)
b.write_text(t)
print('MusicRoad 1.3.1 login black-screen portrait layout fix applied')

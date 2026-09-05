from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
p=ROOT/'app/src/main/java/com/musicroad/ai/DeviceIdentity.java'
s=p.read_text()
old='return "android:"+sha256(c.getPackageName()+"|"+signingDigest(c)+"|"+id);'
new='return "android:"+sha256(c.getPackageName()+"|"+id);'
if old not in s:
    raise SystemExit('compatibility patch failed')
p.write_text(s.replace(old,new,1))
print('compatibility patch applied')

from pathlib import Path

p = Path(__file__).with_name('apply_v140_native_full_design.py')
s = p.read_text()
old = 'ns,n=re.subn(pattern,new,s,count=1,flags=re.S)'
new = 'ns,n=re.subn(pattern,lambda m:new,s,count=1,flags=re.S)'
if old not in s and new not in s:
    raise SystemExit('MusicRoad 1.4 generator helper not found')
if old in s:
    s = s.replace(old, new, 1)
p.write_text(s)
print('MusicRoad 1.4 generator escaping fixed')

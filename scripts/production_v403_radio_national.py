from pathlib import Path
p=Path('estrada-play-comunista-app/app/src/main/java/com/estradaplay/comunista/KnownRoadCatalog.java')
t=p.read_text(encoding='utf-8')
old='private static final Pattern ROAD = Pattern.compile("(?i)\\\\b(BR|RJ|MG|ES|SP)[-\\\\s]?(\\\\d{1,4})\\\\b");'
new='private static final Pattern ROAD = Pattern.compile("(?i)\\\\b(BR|AC|AL|AP|AM|BA|CE|DF|ES|GO|MA|MT|MS|MG|PA|PB|PR|PE|PI|RJ|RN|RS|RO|RR|SC|SP|SE|TO)[-\\\\s]?(\\\\d{1,4})\\\\b");'
if old not in t: raise SystemExit('road regex missing')
t=t.replace(old,new,1)
old2='''        if (c.startsWith("RJ-")) return "RJ";
        if (c.startsWith("ES-")) return "ES";
        if (c.startsWith("MG-")) return "MG";
        if (c.startsWith("SP-")) return "SP";
'''
new2='''        if (c.matches("^(AC|AL|AP|AM|BA|CE|DF|ES|GO|MA|MT|MS|MG|PA|PB|PR|PE|PI|RJ|RN|RS|RO|RR|SC|SP|SE|TO)-\\\\d{1,4}$")) return c.substring(0,2);
'''
if old2 not in t: raise SystemExit('uf legacy block missing')
t=t.replace(old2,new2,1)
p.write_text(t,encoding='utf-8')
print('national radio road catalog patched')

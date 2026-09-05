from pathlib import Path

p = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/estradaplay/app/RoadPackStore.java'
s = p.read_text(encoding='utf-8')

replacements = {
'''            JSONObject json = response.json();
            JSONArray hazards = json.optJSONArray("hazards");
            if (!response.ok() || !json.optBoolean("ok", false) || hazards == null) return false;
            JSONObject stored = new JSONObject();''':
'''            JSONObject json = response.json();
            JSONArray hazards = json.optJSONArray("hazards");
            JSONObject coverage = json.optJSONObject("coverage");
            boolean sourceOk = coverage == null || coverage.optBoolean("osm_ok", true);
            if (!response.ok() || !json.optBoolean("ok", false) || hazards == null) return false;
            if (hazards.length() == 0 && !sourceOk) return false;
            JSONObject stored = new JSONObject();''',
'''            stored.put("source_ok", json.optJSONObject("coverage") == null || json.optJSONObject("coverage").optBoolean("osm_ok", true));
            stored.put("hazards", hazards);
            stored.put("coverage", json.optJSONObject("coverage"));''':
'''            stored.put("source_ok", sourceOk);
            stored.put("hazards", hazards);
            stored.put("coverage", coverage);''',
'''            JSONObject json=response.json(); JSONArray hazards=json.optJSONArray("hazards");
            if(!response.ok()||!json.optBoolean("ok",false)||hazards==null)return false;
            JSONObject center=json.optJSONObject("center");''':
'''            JSONObject json=response.json(); JSONArray hazards=json.optJSONArray("hazards");
            JSONObject coverage=json.optJSONObject("coverage");
            boolean sourceOk=coverage==null||coverage.optBoolean("osm_ok",true);
            if(!response.ok()||!json.optBoolean("ok",false)||hazards==null)return false;
            if(hazards.length()==0&&!sourceOk)return false;
            JSONObject center=json.optJSONObject("center");''',
'''            stored.put("source_ok",json.optJSONObject("coverage") == null || json.optJSONObject("coverage").optBoolean("osm_ok",true));
            stored.put("hazards",hazards);stored.put("coverage",json.optJSONObject("coverage"));''':
'''            stored.put("source_ok",sourceOk);
            stored.put("hazards",hazards);stored.put("coverage",coverage);''',
'''            JSONObject json=response.json();JSONArray hazards=json.optJSONArray("hazards");
            if(!response.ok()||!json.optBoolean("ok",false)||hazards==null)return false;''':
'''            JSONObject json=response.json();JSONArray hazards=json.optJSONArray("hazards");
            if(!response.ok()||!json.optBoolean("ok",false)||hazards==null||hazards.length()==0)return false;''',
'''        for(Pack p:packs)if("state".equals(p.kind)&&uf.equals(p.uf)&&now-p.fetchedAt<=freshMs(p))return true;''':
'''        for(Pack p:packs)if("state".equals(p.kind)&&uf.equals(p.uf)&&!p.hazards.isEmpty()&&now-p.fetchedAt<=freshMs(p))return true;''',
'''    private boolean hasStateLocked(String uf){for(Pack p:packs)if("state".equals(p.kind)&&uf.equals(p.uf))return true;return false;}''':
'''    private boolean hasStateLocked(String uf){for(Pack p:packs)if("state".equals(p.kind)&&uf.equals(p.uf)&&!p.hazards.isEmpty())return true;return false;}''',
'''                Pack p = parsePack(f, json);
                if (p != null) packs.add(p);''':
'''                Pack p = parsePack(f, json);
                if (p != null && "state".equals(p.kind) && p.hazards.isEmpty()) { f.delete(); continue; }
                if (p != null) packs.add(p);''',
'''    private static String guessUfFast(double lat,double lon){
        if(lat>=-23.45&&lat<=-20.65&&lon>=-44.95&&lon<=-40.75)return "RJ";
        if(lat>=-21.40&&lat<=-17.75&&lon>=-41.95&&lon<=-39.55)return "ES";
        if(lat>=-25.45&&lat<=-19.65&&lon>=-53.25&&lon<=-44.00)return "SP";
        if(lat>=-23.00&&lat<=-14.10&&lon>=-51.15&&lon<=-39.75)return "MG";
        return "";
    }''':
'''    private static String guessUfFast(double lat,double lon){
        if(looksLikeEs(lat,lon))return "ES";
        if(lat>=-23.45&&lat<=-20.65&&lon>=-44.95&&lon<=-40.75)return "RJ";
        if(lat>=-25.45&&lat<=-19.65&&lon>=-53.25&&lon<=-44.00)return "SP";
        if(lat>=-23.00&&lat<=-14.10&&lon>=-51.15&&lon<=-39.75)return "MG";
        return "";
    }

    private static boolean looksLikeEs(double lat,double lon){
        if(lat < -21.35 || lat > -17.75 || lon < -41.95 || lon > -39.55)return false;
        if(lat > -19.0 && lon < -40.98)return false;
        if(lat > -20.0 && lat <= -19.0 && lon < -41.32)return false;
        if(lat > -21.0 && lat <= -20.0 && lon < -41.90)return false;
        if(lat <= -21.0){
            if(lon > -40.96)return lat >= -21.33;
            if(lon >= -41.75){double border=-21.30 - 0.25*(lon+40.96);return lat >= border;}
            return lat >= -20.92;
        }
        return true;
    }'''
}

for old, new in replacements.items():
    if old not in s:
        raise SystemExit('EstradaPlay 1.5.2 anchor not found: ' + old.splitlines()[0])
    s = s.replace(old, new, 1)

p.write_text(s, encoding='utf-8')
print('EstradaPlay 1.5.2 alert cache and ES detection fix applied')

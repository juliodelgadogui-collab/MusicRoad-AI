from pathlib import Path

ROOT=Path('.')
APP=ROOT/'estrada-play-comunista-app/app/src/main/java/com/estradaplay/comunista'

def patch(path, old, new, label):
    t=path.read_text(encoding='utf-8')
    if old not in t: raise SystemExit(f'missing {label} in {path}')
    path.write_text(t.replace(old,new,1),encoding='utf-8')

# Cockpit bridge cleanup: route values no longer duplicate their labels; speed limit drives the gauge.
p=APP/'RoadCockpitUiV400.java'
patch(p,'        TextView limit = limit(a);','        TextView limit = limit(a, speedometer);','limit bridge call')
patch(p,'''    private static TextView metricValue(Activity a, String value) {
        TextView t = text(a, value, 15, WHITE, false);
        t.setSingleLine(true);
        return t;
    }
''','''    private static TextView metricValue(Activity a, String value) {
        TextView t = new MetricBridgeTextView(a);
        t.setTextSize(15); t.setTextColor(WHITE); t.setGravity(Gravity.CENTER_VERTICAL); t.setSingleLine(true);
        t.setText(value);
        return t;
    }
''','metric bridge')
patch(p,'''    private static TextView limit(Activity a) {
        TextView t = text(a, "—", 23, Color.rgb(34, 34, 34), true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(panel(a, Color.WHITE, 100, RED, 5));
        return t;
    }
''','''    private static TextView limit(Activity a, ReferenceSpeedometerView gauge) {
        LimitBridgeTextView t = new LimitBridgeTextView(a, gauge);
        t.setText("—"); t.setTextSize(23); t.setTextColor(Color.rgb(34,34,34));
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); t.setGravity(Gravity.CENTER);
        t.setBackground(panel(a, Color.WHITE, 100, RED, 5));
        return t;
    }
''','limit bridge method')
patch(p,'''    private static final class GpsBridgeTextView extends TextView {
        private final ReferenceSpeedometerView gauge;
        GpsBridgeTextView(Activity a, ReferenceSpeedometerView gauge) { super(a); this.gauge = gauge; }
        @Override public void setText(CharSequence text, BufferType type) {
            super.setText(text, type);
            boolean ok = text != null && text.toString().toUpperCase().contains("ATIVO");
            if (gauge != null) gauge.setGpsAvailable(ok);
            setTextColor(ok ? GREEN : Color.rgb(242, 181, 65));
        }
    }
''','''    private static final class LimitBridgeTextView extends TextView {
        private final ReferenceSpeedometerView gauge;
        LimitBridgeTextView(Activity a, ReferenceSpeedometerView gauge){super(a);this.gauge=gauge;}
        @Override public void setText(CharSequence value, BufferType type){
            int limit=0;try{limit=Integer.parseInt(value==null?"":value.toString().trim());}catch(Throwable ignored){}
            if(gauge!=null)gauge.setLimit(limit);
            super.setText(limit>0?String.valueOf(limit):"—",type);
        }
    }

    private static final class MetricBridgeTextView extends TextView {
        MetricBridgeTextView(Activity a){super(a);}
        @Override public void setText(CharSequence value, BufferType type){
            String s=value==null?"":value.toString();
            int cut=s.indexOf('\\n'); if(cut>=0)s=s.substring(0,cut).trim();
            super.setText(s,type);
        }
    }

    private static final class GpsBridgeTextView extends TextView {
        private final ReferenceSpeedometerView gauge;
        GpsBridgeTextView(Activity a, ReferenceSpeedometerView gauge) { super(a); this.gauge = gauge; }
        @Override public void setText(CharSequence text, BufferType type) {
            boolean ok = text != null && text.toString().toUpperCase().contains("ATIVO");
            if (gauge != null) gauge.setGpsAvailable(ok);
            super.setText(ok ? "●  GPS ATIVO" : "●  GPS BUSCANDO", type);
            setTextColor(ok ? GREEN : Color.rgb(242, 181, 65));
        }
    }
''','bridge classes')

# Real safety coverage state is sent with every road-state broadcast.
p=APP/'RoadSafetyService.java'
patch(p,'        i.putExtra("protection_available", !protectionGpsUnavailable); i.putExtra("gps_fix_age_ms", fixAge);','''        boolean coverageAny=false, coverageFresh=false;
        try { if(packs!=null){coverageAny=packs.hasAnyCoverage(lat,lon);coverageFresh=packs.hasFreshCoreCoverage(lat,lon);} } catch(Throwable ignored){}
        i.putExtra("coverage_available", coverageAny); i.putExtra("coverage_fresh", coverageFresh);
        i.putExtra("protection_available", !protectionGpsUnavailable); i.putExtra("gps_fix_age_ms", fixAge);''','coverage broadcast')

# Cockpit shows road limit, GPS health and regional protection separately.
p=APP/'RoadMapActivity.java'
patch(p,'            int limit = intent.getIntExtra("limit_kmh", 0);','''            int limit = intent.getIntExtra("limit_kmh", 0);
            int roadLimit = intent.getIntExtra("road_limit_kmh", 0);
            boolean gpsAvailable = intent.getBooleanExtra("protection_available", true);
            boolean coverageAvailable = intent.getBooleanExtra("coverage_available", false);''','road state vars')
patch(p,'            universalSpeed=speed; universalLimit=intent.getIntExtra("road_limit_kmh",0); currentLatForSave=lat; currentLonForSave=lon;','            universalSpeed=speed; universalLimit=roadLimit; currentLatForSave=lat; currentLonForSave=lon;','road limit reuse')
patch(p,'            if (navLimitText != null) navLimitText.setText(limit > 0 ? String.valueOf(limit) : "—");','            if (navLimitText != null) navLimitText.setText(roadLimit > 0 ? String.valueOf(roadLimit) : (limit > 0 ? String.valueOf(limit) : "—"));','visible road limit')
patch(p,'            if (gpsText != null) gpsText.setText(Double.isFinite(lat) ? "GPS ATIVO" : "GPS BUSCANDO");','            if (gpsText != null) gpsText.setText(Double.isFinite(lat) && gpsAvailable ? "GPS ATIVO" : "GPS BUSCANDO");','gps state')
patch(p,'            if (protectionText != null) protectionText.setText(hasHazard ? "ATENÇÃO À FRENTE" : "PROTEÇÃO ATIVA");','''            if (protectionText != null) protectionText.setText(hasHazard ? "ATENÇÃO À FRENTE" : (!gpsAvailable ? "GPS INDISPONÍVEL" : (coverageAvailable ? "PROTEÇÃO ATIVA" : "PROTEÇÃO PREPARANDO")));''','coverage label')

# PTT exposes TURN readiness to the driver instead of silently pretending relay is guaranteed.
p=APP/'RoadRadioService.java'
patch(p,'    private boolean wanted,joined,muted,ptt,safetyMuted,registered,rtcFailed;','    private boolean wanted,joined,muted,ptt,safetyMuted,registered,rtcFailed,turnReady;','turn field')
patch(p,'            self=j.optString("self",DeviceIdentity.token(this));\n            loadIce(j.optJSONArray("ice_servers"));','            self=j.optString("self",DeviceIdentity.token(this));\n            turnReady=j.optBoolean("turn_ready",false);\n            loadIce(j.optJSONArray("ice_servers"));','turn join')
patch(p,'            status="Rádio conectado · "+participants+" no trecho";','            status="Rádio conectado · "+participants+" no trecho"+(turnReady?"":" · relay TURN não configurado");','turn status')

print('v401 polish applied')

from pathlib import Path

APP=Path('estrada-play-comunista-app/app/src/main/java/com/estradaplay/comunista')

def patch(path, old, new, label):
    t=path.read_text(encoding='utf-8')
    if old not in t: raise SystemExit(f'missing {label} in {path}')
    path.write_text(t.replace(old,new,1),encoding='utf-8')

p=APP/'EstradaPlayApplication.java'
patch(p,'        try { ProductionTelemetryV400.install(this); } catch (Throwable ignored) {}\n','        try { ProductionTelemetryV400.install(this); } catch (Throwable ignored) {}\n        try { MapStyleConfigV400.refreshAsync(this); } catch (Throwable ignored) {}\n','map config refresh')

p=APP/'RoadMapView.java'
patch(p,'map.setStyle(new Style.Builder().fromUri(OPEN_STYLE), style -> {','map.setStyle(new Style.Builder().fromUri(MapStyleConfigV400.styleUri(getContext())), style -> {','server selected map style')

print('v402 map config applied')

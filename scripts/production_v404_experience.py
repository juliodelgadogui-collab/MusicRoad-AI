from pathlib import Path

APP=Path('estrada-play-comunista-app/app/src/main/java/com/estradaplay/comunista')

def patch(path, old, new, label):
    t=path.read_text(encoding='utf-8')
    if old not in t: raise SystemExit(f'missing {label} in {path}')
    path.write_text(t.replace(old,new,1),encoding='utf-8')

p=APP/'RoadCockpitUiV400.java'
patch(p,'''            buildPortrait(a, root, width, height, speedometer, gpsBridge, protection, instruction, weather,
                    limit, eta, remaining, duration, playerTitle, playerArtist, playerToggle);''','''            buildPortrait(a, root, width, height, map, speedometer, gpsBridge, protection, instruction, weather,
                    limit, eta, remaining, duration, playerTitle, playerArtist, playerToggle);''','portrait map arg call')
patch(p,'''    private static void buildPortrait(RoadMapActivity a, FrameLayout root, int w, int h,
                                      ReferenceSpeedometerView speedometer, TextView gps, TextView protection,''','''    private static void buildPortrait(RoadMapActivity a, FrameLayout root, int w, int h, RoadMapView map,
                                      ReferenceSpeedometerView speedometer, TextView gps, TextView protection,''','portrait map arg signature')
patch(p,'        mapB.setOnClickListener(v -> { if (speedometer.getParent() != null) { /* already on map */ } });','        mapB.setOnClickListener(v -> { if (map != null) map.recenter(); });','portrait recenter')
patch(p,'        TextView avg = text(a, "0 km/h", 15, WHITE, false);','        TextView avg = new TripAverageTextView(a); avg.setTextSize(15); avg.setTextColor(WHITE); avg.setGravity(Gravity.CENTER_VERTICAL);','real average')
anchor='''    private static final class SpeedBridgeTextView extends TextView {
'''
insert='''    private static final class TripAverageTextView extends TextView {
        private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        private final Runnable tick = new Runnable(){@Override public void run(){
            try{setText(new DriveSessionStore(getContext()).snapshot().averageLabel());}catch(Throwable ignored){setText("0 km/h");}
            handler.postDelayed(this,5000L);
        }};
        TripAverageTextView(Activity a){super(a);}
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();handler.removeCallbacks(tick);handler.post(tick);}
        @Override protected void onDetachedFromWindow(){handler.removeCallbacks(tick);super.onDetachedFromWindow();}
    }

    private static final class SpeedBridgeTextView extends TextView {
'''
if anchor not in p.read_text(encoding='utf-8'): raise SystemExit('average class anchor missing')
t=p.read_text(encoding='utf-8').replace(anchor,insert,1);p.write_text(t,encoding='utf-8')
print('v404 experience applied')

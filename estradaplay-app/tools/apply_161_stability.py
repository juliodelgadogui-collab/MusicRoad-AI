from pathlib import Path

root = Path(__file__).resolve().parents[1]

# --- MainActivity: stable map startup + in-app GPS authorization ---
p = root / 'app/src/main/java/com/estradaplay/app/MainActivity.java'
s = p.read_text(encoding='utf-8')

s = s.replace(
    '    private static final int REQ_NOTIFICATIONS = 701;\n',
    '    private static final int REQ_NOTIFICATIONS = 701;\n    private static final int REQ_LOCATION = 702;\n',
    1,
)

old = '''    private void openCockpit() {
        Intent i = new Intent(this, AutomotiveActivity.class);
        startActivity(i);
        finish();
    }
'''
new = '''    private void openCockpit() {
        if (!hasLocationPermission()) {
            showLocationPermissionGate();
            return;
        }
        Intent i = new Intent(this, RoadMapActivity.class);
        startActivity(i);
        finish();
    }

    private void showLocationPermissionGate() {
        root.removeAllViews();
        LinearLayout page = column();
        page.setGravity(Gravity.CENTER);
        page.setPadding(dp(34), dp(24), dp(34), dp(24));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        TextView mark = badge("EP", ACCENT, ACCENT_SOFT);
        page.addView(mark, lp(62, 62));
        TextView over = overline("CONFIGURAÇÃO DO VEÍCULO", ACCENT);
        over.setGravity(Gravity.CENTER);
        page.addView(over); margins(over, 0, 18, 0, 4);
        TextView title = text("Ativar proteção da estrada", 26, TEXT, true);
        title.setGravity(Gravity.CENTER);
        page.addView(title);
        TextView body = text("O EstradaPlay usa sua localização para identificar a estrada, o sentido do veículo e os alertas que estão à frente. A autorização só é solicitada depois do seu toque.", 13, MUTED, false);
        body.setGravity(Gravity.CENTER);
        page.addView(body); margins(body, 0, 8, 0, 18);

        Button allow = button("ATIVAR LOCALIZAÇÃO", true);
        page.addView(allow, lp(-1, 58));
        allow.setOnClickListener(v -> requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION));

        Button music = compactButton("CONTINUAR NA MÚSICA SEM GPS");
        page.addView(music, lp(-1, 50)); margins(music, 0, 8, 0, 0);
        music.setOnClickListener(v -> showMusic());
    }
'''
if old not in s:
    raise SystemExit('1.6.1 openCockpit anchor not found')
s = s.replace(old, new, 1)

# Remove every remaining route to the crash-prone experimental cockpit.
s = s.replace('new Intent(this, AutomotiveActivity.class)', 'new Intent(this, RoadMapActivity.class)')

anchor = '''    private void clearDownloadViews() { downloadTitle = null; downloadState = null; downloadProgress = null; }

    @Override protected void onDestroy() {
'''
insert = '''    private void clearDownloadViews() { downloadTitle = null; downloadState = null; downloadProgress = null; }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION) {
            if (hasLocationPermission()) {
                startRoadSafetyIfAllowed();
                showHome();
            } else {
                showLocationPermissionGate();
            }
        }
    }

    @Override protected void onDestroy() {
'''
if anchor not in s:
    raise SystemExit('1.6.1 permission result anchor not found')
s = s.replace(anchor, insert, 1)
p.write_text(s, encoding='utf-8')

# --- RoadPackStore: one-time ES cache reset + resilient forced refresh ---
p = root / 'app/src/main/java/com/estradaplay/app/RoadPackStore.java'
s = p.read_text(encoding='utf-8')

constructor = '''        dir = new File(app.getFilesDir(), "road_safety_packs");
        if (!dir.exists()) dir.mkdirs();
        loadDisk();
'''
constructor_new = '''        dir = new File(app.getFilesDir(), "road_safety_packs");
        if (!dir.exists()) dir.mkdirs();
        migrateEsStateV13();
        loadDisk();
'''
if constructor not in s:
    raise SystemExit('1.6.1 RoadPackStore constructor anchor not found')
s = s.replace(constructor, constructor_new, 1)

insert_anchor = '''    synchronized int packCount() { return packs.size(); }
'''
migration = '''    private void migrateEsStateV13() {
        try {
            android.content.SharedPreferences m = app.getSharedPreferences("estradaplay_migrations", Context.MODE_PRIVATE);
            if (m.getBoolean("es_state_v13_reset", false)) return;
            File legacy = new File(dir, "state_es.json");
            if (legacy.isFile()) legacy.delete();
            m.edit().putBoolean("es_state_v13_reset", true).apply();
        } catch (Throwable ignored) {}
    }

    synchronized int packCount() { return packs.size(); }
'''
if insert_anchor not in s:
    raise SystemExit('1.6.1 RoadPackStore migration anchor not found')
s = s.replace(insert_anchor, migration, 1)

state_call = '''            ApiClient.Response response=api.getLong("api/road_state_pack.php?uf="+uf);
'''
state_call_new = '''            String statePath="api/road_state_pack.php?uf="+uf+("ES".equals(uf)?"&refresh=1":"");
            ApiClient.Response response=api.getLong(statePath);
'''
if state_call not in s:
    raise SystemExit('1.6.1 RoadPackStore state refresh anchor not found')
s = s.replace(state_call, state_call_new, 1)
p.write_text(s, encoding='utf-8')

print('EstradaPlay 1.6.1 stability + ES cache reset/forced refresh applied')

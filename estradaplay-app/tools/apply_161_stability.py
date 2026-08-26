from pathlib import Path

p = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/estradaplay/app/MainActivity.java'
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
print('EstradaPlay 1.6.1 crash-safe launcher and in-app GPS gate applied')

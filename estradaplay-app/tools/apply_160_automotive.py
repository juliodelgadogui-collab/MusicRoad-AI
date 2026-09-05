from pathlib import Path

p = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/estradaplay/app/MainActivity.java'
s = p.read_text(encoding='utf-8')

# Runtime notification permission must not pop automatically. The premium
# cockpit presents an in-app explanation and only then invokes Android's
# permission dialog when the user taps the corresponding action.
s = s.replace(
    '        requestNotifications();\n        boot();',
    '        // Permissions are requested from inside the automotive cockpit.\n        boot();',
    1,
)

boot_start = s.find('    private void boot() {')
boot_end = s.find('    private void attemptDeviceLogin() {', boot_start)
if boot_start < 0 or boot_end < 0:
    raise SystemExit('EstradaPlay 1.6 boot anchors not found')

new_boot = '''    private void boot() {
        if (hasAccount()) {
            startRoadSafetyIfAllowed();
            if (!library.hasSetupDone()) {
                if (online()) loadCatalogAndOpenChooser(true); else showOfflineSetupBlocked();
            } else openConfiguredTarget();
            return;
        }
        if (online()) attemptDeviceLogin();
        else showAuth(false, "Conecte-se para entrar pela primeira vez. Depois, música e alertas preparados continuam disponíveis offline.");
    }

    private void openConfiguredTarget() {
        String target = getIntent() == null ? "" : getIntent().getStringExtra("open");
        target = target == null ? "" : target.trim().toLowerCase(Locale.ROOT);
        if ("music".equals(target)) showMusic();
        else if ("library".equals(target)) {
            if (online()) loadCatalogAndOpenChooser(false); else showMusic();
        }
        else if ("account".equals(target)) showAccount();
        else showHome();
    }

    private void openCockpit() {
        Intent i = new Intent(this, AutomotiveActivity.class);
        startActivity(i);
        finish();
    }

'''
s = s[:boot_start] + new_boot + s[boot_end:]

home_start = s.find('    private void showHome() {')
home_end = s.find('    private void showMusic() {', home_start)
if home_start < 0 or home_end < 0:
    raise SystemExit('EstradaPlay 1.6 home anchors not found')

new_home = '''    private void showHome() {
        clearDownloadViews();
        roadLiveState = null;
        roadLiveDetail = null;
        openCockpit();
    }

'''
s = s[:home_start] + new_home + s[home_end:]

# Any secondary-screen navigation to the road should return to the same
# automotive cockpit instead of opening the legacy phone-style road page.
s = s.replace('new Intent(this, RoadMapActivity.class)', 'new Intent(this, AutomotiveActivity.class)')

p.write_text(s, encoding='utf-8')
print('EstradaPlay 1.6 automotive routing and in-app permission flow applied')

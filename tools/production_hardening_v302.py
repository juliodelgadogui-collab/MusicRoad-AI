from pathlib import Path
import re

ROOT = Path('estrada-play-comunista-app')
APP = ROOT / 'app'
JAVA = APP / 'src/main/java/com/estradaplay/comunista'


def split_args(raw: str):
    out, buf = [], []
    par = bra = brk = 0
    quote = None
    esc = False
    for ch in raw:
        if quote:
            buf.append(ch)
            if esc:
                esc = False
            elif ch == '\\':
                esc = True
            elif ch == quote:
                quote = None
            continue
        if ch in ('"', "'"):
            quote = ch
            buf.append(ch)
        elif ch == '(':
            par += 1; buf.append(ch)
        elif ch == ')':
            par -= 1; buf.append(ch)
        elif ch == '{':
            bra += 1; buf.append(ch)
        elif ch == '}':
            bra -= 1; buf.append(ch)
        elif ch == '[':
            brk += 1; buf.append(ch)
        elif ch == ']':
            brk -= 1; buf.append(ch)
        elif ch == ',' and par == 0 and bra == 0 and brk == 0:
            out.append(''.join(buf).strip()); buf = []
        else:
            buf.append(ch)
    out.append(''.join(buf).strip())
    return out


def matching_paren(text: str, open_pos: int):
    depth = 0
    quote = None
    esc = False
    for i in range(open_pos, len(text)):
        ch = text[i]
        if quote:
            if esc:
                esc = False
            elif ch == '\\':
                esc = True
            elif ch == quote:
                quote = None
            continue
        if ch in ('"', "'"):
            quote = ch
        elif ch == '(':
            depth += 1
        elif ch == ')':
            depth -= 1
            if depth == 0:
                return i
    return -1


CALL = re.compile(r'(?<![A-Za-z0-9_])(?:(?P<qual>[A-Za-z_][A-Za-z0-9_]*)\s*\.\s*)?registerReceiver\s*\(')


def harden_receivers(text: str):
    pos = 0
    changes = 0
    while True:
        m = CALL.search(text, pos)
        if not m:
            break
        open_pos = text.find('(', m.start(), m.end() + 1)
        close_pos = matching_paren(text, open_pos)
        if close_pos < 0:
            break
        args = split_args(text[open_pos + 1:close_pos])
        allowed = len(args) == 2 or (len(args) == 3 and 'RECEIVER_' in args[2])
        if not allowed:
            pos = close_pos + 1
            continue
        context = m.group('qual') or 'this'
        repl = f'InternalBroadcasts.register({context}, {args[0]}, {args[1]})'
        text = text[:m.start()] + repl + text[close_pos + 1:]
        pos = m.start() + len(repl)
        changes += 1
    return text, changes


helper = '''package com.estradaplay.comunista;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.core.content.ContextCompat;

/** Registers app-internal broadcasts consistently on Android 7 through Android 16+. */
final class InternalBroadcasts {
    private InternalBroadcasts() {}

    static Intent register(Context context, BroadcastReceiver receiver, IntentFilter filter) {
        return ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
        );
    }
}
'''
(JAVA / 'InternalBroadcasts.java').write_text(helper, encoding='utf-8')

receiver_changes = 0
for path in JAVA.glob('*.java'):
    if path.name == 'InternalBroadcasts.java':
        continue
    text = path.read_text(encoding='utf-8')
    updated, n = harden_receivers(text)
    if n:
        path.write_text(updated, encoding='utf-8')
        receiver_changes += n

# Explicit permission proof for Lint and runtime safety before reading last-known location.
for filename in ('AutomotiveActivity.java', 'RoadMapActivity.java'):
    path = JAVA / filename
    text = path.read_text(encoding='utf-8')
    if filename == 'AutomotiveActivity.java':
        old = 'if (lm == null || !hasLocation()) return;'
        new = ('if (lm == null || (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED '
               '&& checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)) return;')
    else:
        old = 'if(lm==null||!hasLocation())return;'
        new = ('if(lm==null||(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED'
               '&&checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED))return;')
    if old not in text:
        raise SystemExit(f'expected location guard not found in {filename}')
    path.write_text(text.replace(old, new, 1), encoding='utf-8')

# API 24-27 compatible main executor for CameraX callbacks.
camera = JAVA / 'CameraActivity.java'
text = camera.read_text(encoding='utf-8')
text = text.replace('getMainExecutor()', 'androidx.core.content.ContextCompat.getMainExecutor(this)')
camera.write_text(text, encoding='utf-8')

# Permission flow: location -> notifications -> cockpit. Notification denial never blocks navigation.
main = JAVA / 'MainActivity.java'
text = main.read_text(encoding='utf-8')
text = text.replace(
    'private boolean downloadInitialFlow;',
    'private boolean downloadInitialFlow;\n    private boolean pendingOpenCockpitAfterPermission;',
    1
)
old_open = '''    private void openCockpit() {
        if (!hasLocationPermission()) {
            showLocationPermissionGate();
            return;
        }
        Intent i = new Intent(this, RoadMapActivity.class);
        startActivity(i);
        finish();
    }
'''
new_open = '''    private void openCockpit() {
        if (!hasLocationPermission()) {
            showLocationPermissionGate();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingOpenCockpitAfterPermission = true;
            requestNotifications();
            return;
        }
        launchCockpitNow();
    }

    private void launchCockpitNow() {
        Intent i = new Intent(this, RoadMapActivity.class);
        startActivity(i);
        finish();
    }
'''
if old_open not in text:
    raise SystemExit('openCockpit block not found')
text = text.replace(old_open, new_open, 1)
old_perm = '''    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
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
'''
new_perm = '''    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION) {
            if (hasLocationPermission()) {
                startRoadSafetyIfAllowed();
                openCockpit();
            } else {
                pendingOpenCockpitAfterPermission = false;
                showLocationPermissionGate();
            }
            return;
        }
        if (requestCode == REQ_NOTIFICATIONS) {
            if (pendingOpenCockpitAfterPermission) {
                pendingOpenCockpitAfterPermission = false;
                launchCockpitNow();
            }
        }
    }
'''
if old_perm not in text:
    raise SystemExit('permission result block not found')
text = text.replace(old_perm, new_perm, 1)
main.write_text(text, encoding='utf-8')

# Keep API-27-only navigation-bar attribute out of API 24-26 resources.
styles = APP / 'src/main/res/values/styles.xml'
base = styles.read_text(encoding='utf-8')
base = base.replace('        <item name="android:windowLightNavigationBar">false</item>\n', '')
styles.write_text(base, encoding='utf-8')
v27 = APP / 'src/main/res/values-v27'
v27.mkdir(parents=True, exist_ok=True)
(v27 / 'styles.xml').write_text('''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.EstradaPlay" parent="@android:style/Theme.Material.NoActionBar">
        <item name="android:fontFamily">sans</item>
        <item name="android:windowNoTitle">true</item>
        <item name="android:windowActionModeOverlay">true</item>
        <item name="android:windowBackground">#06080C</item>
        <item name="android:statusBarColor">#06080C</item>
        <item name="android:navigationBarColor">#06080C</item>
        <item name="android:windowLightStatusBar">false</item>
        <item name="android:windowLightNavigationBar">false</item>
        <item name="android:colorAccent">#E01E2F</item>
        <item name="android:alertDialogTheme">@style/Theme.EstradaPlay.Dialog</item>
    </style>
</resources>
''', encoding='utf-8')

# Explicit AndroidX Core dependency + installable candidate build without .teste suffix.
gradle = APP / 'build.gradle'
g = gradle.read_text(encoding='utf-8')
if "implementation 'androidx.core:core:" not in g:
    g = g.replace("    implementation 'androidx.activity:activity:1.10.1'\n",
                  "    implementation 'androidx.activity:activity:1.10.1'\n    implementation 'androidx.core:core:1.15.0'\n", 1)
if '        candidate {' not in g:
    marker = '''        release {
            debuggable false
'''
    candidate = '''        candidate {
            // Production candidate: real package/name, no .teste suffix. Signed only for field validation.
            debuggable false
            minifyEnabled false
            shrinkResources false
            signingConfig signingConfigs.debug
            manifestPlaceholders = [appLabel: 'Estrada Play Comunista']
        }
        release {
            debuggable false
'''
    if marker not in g:
        raise SystemExit('release buildType marker not found')
    g = g.replace(marker, candidate, 1)
gradle.write_text(g, encoding='utf-8')

# Keep routine workflow manual-only after this one-shot stabilization.
workflow = Path('.github/workflows/build-estrada-play-comunista.yml')
w = workflow.read_text(encoding='utf-8')
if '  push:' in w:
    # Defensive only; normal workflow should already be manual-only.
    start = w.index('  push:')
    end = w.index('\n\npermissions:', start)
    w = w[:start] + w[end + 2:]
    workflow.write_text(w, encoding='utf-8')

print(f'Internal receiver registrations hardened: {receiver_changes}')
if receiver_changes < 25:
    raise SystemExit(f'expected at least 25 receiver registrations, got {receiver_changes}')
print('Production hardening source changes applied.')

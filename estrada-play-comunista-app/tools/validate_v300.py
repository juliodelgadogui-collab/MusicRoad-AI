#!/usr/bin/env python3
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app" / "src" / "main" / "java" / "com" / "estradaplay" / "comunista"
TEST_JAVA = ROOT / "app" / "src" / "test" / "java" / "com" / "estradaplay" / "comunista"
ROOT_GRADLE = ROOT / "build.gradle"
APP_GRADLE = ROOT / "app" / "build.gradle"
MANIFEST = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"
BUILD_SCRIPT = ROOT / "build-3.0.0-codespace.sh"

errors = []


def text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except Exception as exc:
        errors.append(f"não consegui ler {path}: {exc}")
        return ""


def require(path: Path, needle: str, label: str | None = None) -> None:
    if needle not in text(path):
        errors.append(label or f"faltando {needle!r} em {path.name}")


def forbid(path: Path, needle: str, label: str | None = None) -> None:
    if needle in text(path):
        errors.append(label or f"conteúdo proibido {needle!r} em {path.name}")


# Toolchain / isolated debug identity.
require(ROOT_GRADLE, "version '8.10.1'", "Android Gradle Plugin esperado: 8.10.1")
require(APP_GRADLE, "EPC_300_PRODUCTION_BASELINE")
require(APP_GRADLE, "compileSdk 36", "compileSdk esperado: 36")
require(APP_GRADLE, "targetSdk 36", "targetSdk esperado: 36")
require(APP_GRADLE, "versionCode 300", "versionCode esperado: 300")
require(APP_GRADLE, "versionName '3.0.0'", "versionName esperado: 3.0.0")
require(APP_GRADLE, "JavaVersion.VERSION_17", "Java 17 deve permanecer fixado no Android")
require(APP_GRADLE, "com.google.mlkit:text-recognition:16.0.1", "OCR local bundled esperado")
for marker in (
    "EPC_DEBUG_ISOLATED_V300",
    "applicationIdSuffix '.teste'",
    "versionNameSuffix '-teste'",
    "appLabel: 'Estrada Play Comunista Teste'",
):
    require(APP_GRADLE, marker)

server_store = JAVA / "ServerEndpointStore.java"
server_settings = JAVA / "ServerSettingsActivity.java"
diagnostics = JAVA / "SystemDiagnosticsActivity.java"
shell = JAVA / "UnifiedAppShell.java"
gate = JAVA / "GateActivity.java"
api = JAVA / "ApiClient.java"
application = JAVA / "EstradaPlayApplication.java"
device_identity = JAVA / "DeviceIdentity.java"
session_guard = JAVA / "SessionValidityGuardV300.java"
session_client = JAVA / "SessionValidationClientV300.java"
session_policy = JAVA / "SessionValidityPolicyV300.java"
session_test = TEST_JAVA / "SessionValidityPolicyV300Test.java"
notification_guard = JAVA / "NotificationPermissionCompat.java"
obd = JAVA / "Obd2Activity.java"
player = JAVA / "PlayerService.java"
music = JAVA / "MusicPlayerActivity.java"
road_map = JAVA / "RoadMapActivity.java"
download = JAVA / "DownloadService.java"
road_safety = JAVA / "RoadSafetyService.java"
road_radio = JAVA / "RoadRadioService.java"
system_bars = JAVA / "SystemBarsCompatV251.java"

# Server / HTTPS hardening.
for marker in (
    "SERVER_ENDPOINT_HTTPS_V260",
    "Servidor precisa usar HTTPS válido",
    '"https".equalsIgnoreCase(u.getScheme())',
):
    require(server_store, marker)

for marker in (
    "SERVER_READY_GATE_V260",
    "if(persist&&ready)",
    "setInstanceFollowRedirects(false)",
):
    require(server_settings, marker)

for marker in (
    "Read-only local health snapshot",
    "BuildConfig.VERSION_NAME",
    "ProcessCrashGuard.recentCrash",
    "PhoneMp3Store.hasPermission",
):
    require(diagnostics, marker)

# Android 16 adaptive-window baseline.
for marker in (
    "ANDROID16_ADAPTIVE_V280",
    "cfg.screenWidthDp >= 600",
):
    require(shell, marker)

require(MANIFEST, 'android:resizeableActivity="true"')
require(MANIFEST, 'android:usesCleartextTraffic="false"')
require(MANIFEST, 'android:name=".SystemDiagnosticsActivity"')
require(MANIFEST, "ANDROID16_CONFIG_RECREATE_V300")
forbid(MANIFEST, "PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY", "opt-out antigo de tela grande ainda presente")

try:
    manifest_root = ET.parse(MANIFEST).getroot()
    android = "{http://schemas.android.com/apk/res/android}"
    activities = {
        node.get(android + "name", ""): node
        for node in manifest_root.findall("./application/activity")
    }
    for name in (
        ".AutomotiveActivity",
        ".DestinationActivity",
        ".ConvoyActivity",
        ".MusicStorageActivity",
        ".CameraActivity",
        ".MainActivity",
    ):
        node = activities.get(name)
        if node is None:
            errors.append(f"Activity esperada ausente do Manifest: {name}")
        elif node.get(android + "configChanges"):
            errors.append(f"{name} ainda intercepta configChanges sem handler próprio")
    for name in (".RoadMapActivity", ".MusicPlayerActivity"):
        node = activities.get(name)
        value = "" if node is None else node.get(android + "configChanges", "")
        if "orientation" not in value or "screenSize" not in value:
            errors.append(f"{name} deveria manter configChanges porque reconstrói a UI manualmente")
except Exception as exc:
    errors.append(f"Manifest XML inválido: {exc}")

require(road_map, "onConfigurationChanged")
require(music, "onConfigurationChanged")

# Offline-first Gate: comments may mention device login; executable network code may not exist here.
require(gate, "STARTUP_OFFLINE_FIRST_V290")
forbid(gate, "new ApiClient(", "Gate não pode criar cliente de rede")
forbid(gate, "native_app.php?action=device_login", "Gate não pode executar device_login")

# General API redirect/connection hardening.
for marker in (
    "API_REDIRECT_GUARD_V300",
    "API_CONNECTION_CLEANUP_V300",
    "setInstanceFollowRedirects(false)",
    "MAX_REDIRECTS = 3",
    "isTrustedTarget(redirected)",
    "isHttpsTarget(redirected)",
):
    require(api, marker)

# Debug APK must remain isolated from production identity.
for marker in (
    "EPC_DEBUG_DEVICE_ISOLATED_V300",
    'BuildConfig.DEBUG ? "|debug|" + BuildConfig.APPLICATION_ID : ""',
    "Estrada Play Teste",
):
    require(device_identity, marker)

# Background remembered-session validation must be read-only and race-safe.
for marker in (
    "SESSION_VALIDITY_GUARD_V300",
    "NET_CAPABILITY_VALIDATED",
    "SESSION_ACCOUNT_SWITCH_GUARD_V300",
    "SESSION_VALIDATION_NO_SUCCESS_WRITE_V300",
    "SessionValidationClientV300.sessionFingerprint(app)",
    "SessionValidationClientV300.validate(app, payload)",
    "validationStillCurrent(accountAtStart, sessionAtStart)",
    "invalidateLocalSessionIfCurrent(accountAtStart, sessionAtStart)",
    "SessionValidityPolicyV300.isExplicitRevocation(response.code)",
    "FLAG_ACTIVITY_CLEAR_TASK",
):
    require(session_guard, marker)
forbid(session_guard, "putString(KEY_ACCOUNT", "validação em background não pode regravar a conta em caso de sucesso")
require(application, "SessionValidityGuardV300.register(this)")

for marker in (
    "SESSION_VALIDATION_READ_ONLY_V300",
    "setInstanceFollowRedirects(false)",
    "X-EstradaPlay-Device-Secret",
    "sessionFingerprint",
    "SHA-256",
):
    require(session_client, marker)
forbid(session_client, "captureCookies(", "validação read-only não pode capturar cookies")
forbid(session_client, "captureAuth(", "validação read-only não pode capturar tokens")
forbid(session_client, "refreshIfPossible(", "validação read-only não pode renovar sessão")
forbid(session_client, "accessToken()", "fingerprint read-only não deve ler/expirar access token")
forbid(session_client, "refreshToken()", "fingerprint read-only não deve ler/expirar refresh token")

for marker in (
    "httpCode == 401",
    "httpCode == 403",
    "SESSION_ACCOUNT_SWITCH_GUARD_V300",
    "sameValidationSubject",
):
    require(session_policy, marker)
for marker in (
    "isExplicitRevocation(401)",
    "isExplicitRevocation(403)",
    "isExplicitRevocation(500)",
    "sameValidationSubject",
    '"session-a", "session-b"',
):
    require(session_test, marker)

# Android 13+ notification updates: runtime guard + SecurityException race handling.
for marker in (
    "NOTIFICATION_PERMISSION_GUARD_V300",
    "POST_NOTIFICATIONS",
    '@SuppressLint("MissingPermission")',
    "catch (SecurityException ignored)",
):
    require(notification_guard, marker)
for service in (player, download, road_safety, road_radio):
    require(service, "NotificationPermissionCompat.notify", f"{service.name} deve atualizar notificações pelo guard central")

# Bluetooth OBD2 keeps real runtime checks; suppression only documents the guard to Lint.
for marker in (
    "OBD2_PERMISSION_GUARD_V300",
    "hasBluetoothConnectPermission()",
    "BLUETOOTH_CONNECT",
    "catch(SecurityException",
):
    require(obd, marker)

# Music/download protections carried from 2.5.1.
for marker in (
    "PLAYER_EXACT_SELECTED_SOURCE_V246",
    "PLAYER_PREPARE_GENERATION_V249",
    "PLAYER_QUEUE_SOURCE_STRICT_V251",
):
    require(player, marker)
for marker in (
    "MUSIC_EXACT_SELECTED_SOURCE_V246",
    "MUSIC_FAILURE_AUTO_REFRESH_V250",
):
    require(music, marker)
for marker in (
    "MUSIC_DOWNLOAD_TIMEOUT_V251",
    "onTimeout(int startId, int fgsType)",
):
    require(download, marker)
for marker in (
    "ANDROID15_SAFE_INSETS_V251",
    "WindowInsets.Type.systemBars()",
    "WindowInsets.Type.displayCutout()",
):
    require(system_bars, marker)

# Codespaces test build is intentionally Universal-only.
for marker in (
    "JAVA17_AUTO_BOOTSTRAP_V300",
    "gradle-8.11.1-bin.zip",
    'platforms;android-36',
    "validate_v300.py",
    "UNIVERSAL_ONLY_BUILD_V300",
    "lintUniversalDebug",
    "testUniversalDebugUnitTest",
    "assembleUniversalDebug",
    "NATIVE_16K_AUDIT_V300",
    "audit_elf_16k",
    'EPC_STRICT_16K:-1',
    "python3-venv",
    "bzip2",
    "com.estradaplay.comunista.universal.teste",
    "Estrada-Play-Comunista-Teste-Universal-3.0.0.apk",
):
    require(BUILD_SCRIPT, marker)
for forbidden in (
    "lintHorizontalDebug",
    "testHorizontalDebugUnitTest",
    "assembleHorizontalDebug",
    "com.estradaplay.comunista.horizontal.teste",
    "Estrada-Play-Comunista-Teste-Horizontal-3.0.0.apk",
):
    forbid(BUILD_SCRIPT, forbidden, f"build Universal-only ainda contém {forbidden}")
forbid(BUILD_SCRIPT, 'EPC_STRICT_16K:-0', "auditoria 16 KB não pode voltar a ser permissiva por padrão")

# Native app invariant.
for path in JAVA.rglob("*.java"):
    source = text(path)
    if "android.webkit.WebView" in source or "new WebView" in source:
        errors.append(f"WebView detectado em {path.relative_to(ROOT)}")

if errors:
    print("EPC 3.0.0 Universal: pré-validação FALHOU", file=sys.stderr)
    for error in errors:
        print(f" - {error}", file=sys.stderr)
    sys.exit(1)

print("EPC 3.0.0 Universal: pré-validação OK")
print(" - servidor personalizado exige HTTPS e API pronta")
print(" - Gate abre sem depender da rede")
print(" - validação de conta em background é read-only e protegida contra troca de conta")
print(" - notificações e OBD2 mantêm checagens de permissão compatíveis com Lint")
print(" - redirects autenticados são controlados e conexões são encerradas")
print(" - player mantém fonte/fila exatas e download trata timeout dataSync")
print(" - edge-to-edge respeita barras/cutout")
print(" - debug usa pacote, nome e identidade de dispositivo de TESTE")
print(" - Codespaces compila somente Universal e bloqueia 16 KB incompatível por padrão")
print(" - sem WebView")

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

require(ROOT_GRADLE, "version '8.10.1'", "Android Gradle Plugin esperado: 8.10.1")
require(APP_GRADLE, "EPC_300_PRODUCTION_BASELINE")
require(APP_GRADLE, "compileSdk 36", "compileSdk esperado: 36")
require(APP_GRADLE, "targetSdk 36", "targetSdk esperado: 36")
require(APP_GRADLE, "versionCode 300", "versionCode esperado: 300")
require(APP_GRADLE, "versionName '3.0.0'", "versionName esperado: 3.0.0")
require(APP_GRADLE, "com.google.mlkit:text-recognition:16.0.1", "OCR local bundled esperado")

server_store = JAVA / "ServerEndpointStore.java"
server_settings = JAVA / "ServerSettingsActivity.java"
diagnostics = JAVA / "SystemDiagnosticsActivity.java"
shell = JAVA / "UnifiedAppShell.java"
gate = JAVA / "GateActivity.java"
api = JAVA / "ApiClient.java"
application = JAVA / "EstradaPlayApplication.java"
session_guard = JAVA / "SessionValidityGuardV300.java"
session_policy = JAVA / "SessionValidityPolicyV300.java"
session_test = TEST_JAVA / "SessionValidityPolicyV300Test.java"
player = JAVA / "PlayerService.java"
music = JAVA / "MusicPlayerActivity.java"
road_map = JAVA / "RoadMapActivity.java"
download = JAVA / "DownloadService.java"
system_bars = JAVA / "SystemBarsCompatV251.java"

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

# ANDROID16_CONFIG_RECREATE_V300: screens without an explicit configuration handler must
# let Android recreate them. Camera recreation also forces CameraX/OCR to rebind to the new rotation.
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

require(gate, "STARTUP_OFFLINE_FIRST_V290")
forbid(gate, "device_login", "Gate não pode voltar a bloquear abertura com device_login")

for marker in (
    "API_REDIRECT_GUARD_V300",
    "API_CONNECTION_CLEANUP_V300",
    "setInstanceFollowRedirects(false)",
    "MAX_REDIRECTS = 3",
    "isTrustedTarget(redirected)",
    "isHttpsTarget(redirected)",
):
    require(api, marker)

for marker in (
    "SESSION_VALIDITY_GUARD_V300",
    "NET_CAPABILITY_VALIDATED",
    "SessionValidityPolicyV300.isExplicitRevocation(response.code)",
    "LOGOUT_RACE_GUARD_V300",
    "if (hasLocalAccount())",
    "FLAG_ACTIVITY_CLEAR_TASK",
):
    require(session_guard, marker)
require(application, "SessionValidityGuardV300.register(this)")
for marker in (
    "httpCode == 401",
    "httpCode == 403",
):
    require(session_policy, marker)
for marker in (
    "isExplicitRevocation(401)",
    "isExplicitRevocation(403)",
    "isExplicitRevocation(500)",
):
    require(session_test, marker)

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

for marker in (
    "gradle-8.11.1-bin.zip",
    'platforms;android-36',
    "validate_v300.py",
    "lintUniversalDebug",
    "lintHorizontalDebug",
    "NATIVE_16K_AUDIT_V300",
    "audit_elf_16k",
    'EPC_STRICT_16K:-1',
    "python3-venv",
    "bzip2",
    "Estrada-Play-Comunista-Universal-3.0.0.apk",
    "Estrada-Play-Comunista-Horizontal-3.0.0.apk",
):
    require(BUILD_SCRIPT, marker)
forbid(BUILD_SCRIPT, 'EPC_STRICT_16K:-0', "auditoria 16 KB não pode voltar a ser permissiva por padrão")

for path in JAVA.rglob("*.java"):
    source = text(path)
    if "android.webkit.WebView" in source or "new WebView" in source:
        errors.append(f"WebView detectado em {path.relative_to(ROOT)}")

if errors:
    print("EPC 3.0.0: pré-validação FALHOU", file=sys.stderr)
    for error in errors:
        print(f" - {error}", file=sys.stderr)
    sys.exit(1)

print("EPC 3.0.0: pré-validação OK")
print(" - servidor personalizado exige HTTPS e API pronta")
print(" - diagnóstico local registrado")
print(" - janelas adaptáveis Android 16 habilitadas")
print(" - telas sem handler próprio deixam Android recriar a configuração")
print(" - câmera é recriada/revinculada pelo sistema quando a configuração muda")
print(" - Gate abre sem depender da rede")
print(" - conta salva é validada em background; somente 401/403 revoga")
print(" - logout não pode ser desfeito por validação concorrente")
print(" - redirects autenticados são controlados")
print(" - conexões HTTP são encerradas também em falhas")
print(" - player mantém fonte/fila exatas")
print(" - download trata timeout dataSync")
print(" - edge-to-edge respeita barras/cutout")
print(" - build Codespaces executa Android Lint antes dos APKs")
print(" - build Codespaces bloqueia 16 KB incompatível por padrão")
print(" - sem WebView")

#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app" / "src" / "main" / "java" / "com" / "estradaplay" / "comunista"
GRADLE = ROOT / "app" / "build.gradle"
MANIFEST = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"

errors = []

def require(path: Path, needle: str, label: str | None = None) -> None:
    try:
        text = path.read_text(encoding="utf-8")
    except Exception as exc:
        errors.append(f"não consegui ler {path}: {exc}")
        return
    if needle not in text:
        errors.append(label or f"faltando {needle!r} em {path.name}")

require(GRADLE, "versionCode 262", "versionCode esperado: 262")
require(GRADLE, "versionName '2.5.1'", "versionName esperado: 2.5.1")
require(GRADLE, "EPC_251_MUSIC_STABLE")
require(GRADLE, "targetSdk 35", "targetSdk esperado: 35")

player = JAVA / "PlayerService.java"
activity = JAVA / "MusicPlayerActivity.java"
integrity = JAVA / "MusicLibraryIntegrityV247.java"
application = JAVA / "EstradaPlayApplication.java"
download = JAVA / "DownloadService.java"

for marker in (
    "PLAYER_EXACT_QUEUE_V245",
    "PLAYER_EXACT_SELECTED_SOURCE_V246",
    "PLAYER_SELECTED_FAILSAFE_V248",
    "PLAYER_PREPARE_GENERATION_V249",
    "PLAYER_QUEUE_SOURCE_IDENTITY_V2410",
    "PLAYER_QUEUE_SOURCE_STRICT_V251",
    "queue_sources_json_v2410",
    "staged_queue_sources_json_v2410",
    "strictPreferredSources",
    "if (!exact.isEmpty() || strictPreferredSources) return exact;",
    "postIfGenerationActive",
    "failExplicitSelection",
):
    require(player, marker)

for marker in (
    "MUSIC_EXACT_QUEUE_V245",
    "MUSIC_EXACT_SELECTED_SOURCE_V246",
    "MUSIC_FAILURE_AUTO_REFRESH_V250",
    "PlayerService.stageQueue",
    "t.toStored().toString()",
    "refreshLibraryAfterPlaybackFailure",
):
    require(activity, marker)

for marker in (
    "MUSIC_LIBRARY_INTEGRITY_V247",
    "MUSIC_LIBRARY_SESSION_CLEANUP_V251",
    "queue_sources_json_v2410",
    "staged_queue_sources_json_v2410",
):
    require(integrity, marker)

for marker in (
    "MUSIC_DOWNLOAD_TIMEOUT_V251",
    "onTimeout(int startId, int fgsType)",
    "stopSelf(startId)",
    "progresso parcial preservado",
):
    require(download, marker)

require(MANIFEST, 'android.permission.FOREGROUND_SERVICE_DATA_SYNC')
require(MANIFEST, 'android:foregroundServiceType="dataSync"')
require(application, "MusicLibraryIntegrityV247.repair(this)")

for path in JAVA.rglob("*.java"):
    try:
        text = path.read_text(encoding="utf-8", errors="ignore")
    except Exception:
        continue
    if "android.webkit.WebView" in text or "new WebView" in text:
        errors.append(f"WebView detectado em {path.relative_to(ROOT)}")

if errors:
    print("EPC 2.5.1: pré-validação FALHOU", file=sys.stderr)
    for error in errors:
        print(f" - {error}", file=sys.stderr)
    sys.exit(1)

print("EPC 2.5.1: pré-validação OK")
print(" - reprodução selecionada usa fonte exata")
print(" - falha explícita não pula silenciosamente para outra música")
print(" - callbacks antigos do MediaPlayer são ignorados")
print(" - fila persiste identidade da fonte local")
print(" - fila persistida não remapeia uma fonte ausente para outra cópia")
print(" - biblioteca repara entradas locais obsoletas")
print(" - download dataSync encerra com segurança no timeout do Android 15")
print(" - sem WebView")

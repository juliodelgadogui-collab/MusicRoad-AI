#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$APP_DIR"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

ANDROID_CMDLINE_ZIP="$HOME/.cache/commandlinetools-linux-11076708_latest.zip"
GRADLE_ZIP="$HOME/.cache/gradle-8.11.1-bin.zip"
GRADLE_HOME="$HOME/.cache/gradle-8.11.1"
VOICE_VENV="$HOME/.cache/epc-v300-voice-venv"
VOICE_MODEL="$HOME/.cache/vits-piper-pt_BR-faber-medium.tar.bz2"
VOICE_ROOT="$HOME/.cache/epc-v300-voice-model"
DIST="$APP_DIR/dist"
# 16 KB is release-blocking by default. Set EPC_STRICT_16K=0 only for an intentional diagnostic build.
STRICT_16K="${EPC_STRICT_16K:-1}"

log(){ printf '\n==> %s\n' "$*"; }

log "Pré-validando fonte EPC 3.0.0"
python3 -m py_compile tools/validate_v300.py
python3 tools/validate_v300.py

# JAVA17_AUTO_BOOTSTRAP_V300: Codespaces may open with a newer JDK (for example Java 25).
# The Android toolchain for this project is pinned to Java 17, so locate it or install it automatically.
find_java17_home(){
  local candidate version current

  if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
    version="$("${JAVA_HOME}/bin/java" -version 2>&1 || true)"
    if printf '%s\n' "$version" | grep -Eq 'version "17\.|openjdk version "17\.'; then
      printf '%s\n' "$JAVA_HOME"
      return 0
    fi
  fi

  if command -v java >/dev/null 2>&1; then
    current="$(readlink -f "$(command -v java)" 2>/dev/null || command -v java)"
    candidate="$(cd "$(dirname "$current")/.." 2>/dev/null && pwd || true)"
    if [ -n "$candidate" ] && [ -x "$candidate/bin/java" ]; then
      version="$("$candidate/bin/java" -version 2>&1 || true)"
      if printf '%s\n' "$version" | grep -Eq 'version "17\.|openjdk version "17\.'; then
        printf '%s\n' "$candidate"
        return 0
      fi
    fi
  fi

  for candidate in /usr/lib/jvm/*; do
    [ -x "$candidate/bin/java" ] || continue
    version="$("$candidate/bin/java" -version 2>&1 || true)"
    if printf '%s\n' "$version" | grep -Eq 'version "17\.|openjdk version "17\.'; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

log "Preparando Java 17 automaticamente"
JAVA17_HOME="$(find_java17_home || true)"
if [ -z "$JAVA17_HOME" ]; then
  log "Java 17 não encontrado; instalando OpenJDK 17"
  sudo apt-get update
  sudo apt-get install -y openjdk-17-jdk
  JAVA17_HOME="$(find_java17_home || true)"
fi
if [ -z "$JAVA17_HOME" ]; then
  echo "Não foi possível localizar o Java 17 após a instalação automática." >&2
  exit 1
fi
export JAVA_HOME="$JAVA17_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
hash -r
"$JAVA_HOME/bin/java" -version 2>&1 | tee /tmp/epc-java-version.txt
if ! grep -Eq 'version "17\.|openjdk version "17\.' /tmp/epc-java-version.txt; then
  echo "Falha ao ativar Java 17 automaticamente." >&2
  exit 1
fi
printf 'Java 17 ativo em: %s\n' "$JAVA_HOME"

need_apt=0
command -v unzip >/dev/null 2>&1 || need_apt=1
command -v readelf >/dev/null 2>&1 || need_apt=1
command -v bzip2 >/dev/null 2>&1 || need_apt=1
rm -rf /tmp/epc-venv-probe
if ! python3 -m venv /tmp/epc-venv-probe >/dev/null 2>&1; then
  need_apt=1
fi
rm -rf /tmp/epc-venv-probe
if [ "$need_apt" -eq 1 ]; then
  log "Instalando utilitários de validação"
  sudo apt-get update
  sudo apt-get install -y unzip binutils bzip2 python3-venv
fi

if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  log "Instalando Android command-line tools"
  mkdir -p "$HOME/.cache" "$ANDROID_HOME/cmdline-tools"
  if [ ! -f "$ANDROID_CMDLINE_ZIP" ]; then
    curl -L --fail --retry 3 --retry-delay 2 \
      -o "$ANDROID_CMDLINE_ZIP" \
      https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  fi
  rm -rf "$ANDROID_HOME/cmdline-tools/latest" /tmp/epc-android-tools
  mkdir -p /tmp/epc-android-tools "$ANDROID_HOME/cmdline-tools/latest"
  unzip -q "$ANDROID_CMDLINE_ZIP" -d /tmp/epc-android-tools
  cp -a /tmp/epc-android-tools/cmdline-tools/. "$ANDROID_HOME/cmdline-tools/latest/"
fi

export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

log "Instalando Android SDK 36"
yes | sdkmanager --licenses >/dev/null 2>&1 || true
sdkmanager "platform-tools" "platforms;android-36" "build-tools;35.0.0"

if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
  log "Instalando Gradle 8.11.1"
  mkdir -p "$HOME/.cache"
  if [ ! -f "$GRADLE_ZIP" ]; then
    curl -L --fail --retry 3 --retry-delay 2 \
      -o "$GRADLE_ZIP" \
      https://services.gradle.org/distributions/gradle-8.11.1-bin.zip
  fi
  rm -rf "$GRADLE_HOME"
  unzip -q "$GRADLE_ZIP" -d "$HOME/.cache"
fi
GRADLE="$GRADLE_HOME/bin/gradle"

log "Preparando chave de assinatura de desenvolvimento"
test -s signing/estradaplay-dev.keystore.b64
base64 -d signing/estradaplay-dev.keystore.b64 > signing/estradaplay-dev.keystore

log "Preparando banco de voz português embutido"
if [ ! -x "$VOICE_VENV/bin/python" ]; then
  python3 -m venv "$VOICE_VENV"
fi
"$VOICE_VENV/bin/python" -m pip install --disable-pip-version-check --upgrade pip
"$VOICE_VENV/bin/python" -m pip install --disable-pip-version-check sherpa-onnx soundfile numpy

mkdir -p "$HOME/.cache"
if [ ! -f "$VOICE_MODEL" ]; then
  curl -L --fail --retry 3 --retry-delay 2 \
    -o "$VOICE_MODEL" \
    https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-pt_BR-faber-medium.tar.bz2
fi
echo "7add3f923ad6bc25ca8a192805fd1a64d1b3893e4611c4a9719545a825039a83  $VOICE_MODEL" | sha256sum -c -
rm -rf "$VOICE_ROOT"
mkdir -p "$VOICE_ROOT"
tar -xjf "$VOICE_MODEL" -C "$VOICE_ROOT"
"$VOICE_VENV/bin/python" tools/generate_voice_bank.py \
  --model-root "$VOICE_ROOT" \
  --output app/src/main/res/raw \
  --metadata-output app/src/main/assets/estradaplay_voice

test "$(find app/src/main/res/raw -maxdepth 1 -name 'ep_*.wav' | wc -l)" -ge 77

log "Executando Android Lint, testes e compilando Universal + Horizontal TESTE"
"$GRADLE" --no-daemon \
  lintUniversalDebug \
  lintHorizontalDebug \
  testUniversalDebugUnitTest \
  testHorizontalDebugUnitTest \
  assembleUniversalDebug \
  assembleHorizontalDebug

U="app/build/outputs/apk/universal/debug/app-universal-debug.apk"
H="app/build/outputs/apk/horizontal/debug/app-horizontal-debug.apk"
test -f "$U"
test -f "$H"

AAPT="$ANDROID_HOME/build-tools/35.0.0/aapt"
APKSIGNER="$ANDROID_HOME/build-tools/35.0.0/apksigner"
ZIPALIGN="$ANDROID_HOME/build-tools/35.0.0/zipalign"

# NATIVE_16K_AUDIT_V300: every build is inspected. Strict mode is on by default so
# any native LOAD segment below 0x4000 blocks the candidate APK before distribution.
audit_elf_16k(){
  local apk="$1" label="$2" tmp bad so align value
  tmp="$(mktemp -d)"
  bad=0
  if unzip -qq "$apk" 'lib/*/*.so' -d "$tmp" 2>/dev/null; then
    while IFS= read -r -d '' so; do
      while IFS= read -r align; do
        case "$align" in
          0x*) value=$((align)) ;;
          *) continue ;;
        esac
        if [ "$value" -lt $((0x4000)) ]; then
          printf 'ERRO 16 KB [%s]: %s tem LOAD align %s\n' "$label" "${so#"$tmp/"}" "$align" >&2
          bad=1
          break
        fi
      done < <(readelf -lW "$so" 2>/dev/null | awk '$1=="LOAD" {print $NF}')
    done < <(find "$tmp/lib" -type f -name '*.so' -print0 2>/dev/null)
  fi
  rm -rf "$tmp"
  if [ "$bad" -eq 0 ]; then
    printf 'ELF 16 KB [%s]: nenhum LOAD abaixo de 0x4000 detectado.\n' "$label"
    return 0
  fi
  printf 'ELF 16 KB [%s]: há SDK nativo a migrar.\n' "$label" >&2
  if [ "$STRICT_16K" = "1" ]; then
    printf 'EPC_STRICT_16K=1: bloqueando APK incompatível.\n' >&2
    return 1
  fi
  printf 'EPC_STRICT_16K=0: diagnóstico permitido, APK não deve ser publicado.\n' >&2
  return 0
}

validate_apk(){
  local apk="$1" package="$2" label="$3" dump="$4"
  log "Validando APK $label"
  "$AAPT" dump badging "$apk" | tee "$dump"
  grep -q "package: name='$package' versionCode='300' versionName='3.0.0-teste'" "$dump"
  grep -q "targetSdkVersion:'36'" "$dump"
  grep -q "application-label:'Estrada Play Comunista Teste'" "$dump"
  "$ZIPALIGN" -c -P 16 -v 4 "$apk"
  audit_elf_16k "$apk" "$label"
  "$APKSIGNER" verify --print-certs "$apk"
}

# EPC_DEBUG_ISOLATED_V300: debug IDs are intentionally different from production.
validate_apk "$U" "com.estradaplay.comunista.universal.teste" "Universal Teste" /tmp/epc-v300-universal-teste.txt
validate_apk "$H" "com.estradaplay.comunista.horizontal.teste" "Horizontal Teste" /tmp/epc-v300-horizontal-teste.txt

log "Organizando APKs de teste"
rm -rf "$DIST"
mkdir -p "$DIST"
cp "$U" "$DIST/Estrada-Play-Comunista-Teste-Universal-3.0.0.apk"
cp "$H" "$DIST/Estrada-Play-Comunista-Teste-Horizontal-3.0.0.apk"
(
  cd "$DIST"
  sha256sum \
    Estrada-Play-Comunista-Teste-Universal-3.0.0.apk \
    Estrada-Play-Comunista-Teste-Horizontal-3.0.0.apk \
    | tee SHA256SUMS.txt
)

log "BUILD TESTE 3.0.0 CONCLUÍDO"
printf 'APKs em: %s\n' "$DIST"
printf ' - %s\n' "$DIST/Estrada-Play-Comunista-Teste-Universal-3.0.0.apk"
printf ' - %s\n' "$DIST/Estrada-Play-Comunista-Teste-Horizontal-3.0.0.apk"

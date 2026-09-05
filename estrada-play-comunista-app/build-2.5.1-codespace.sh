#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$APP_DIR"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

ANDROID_CMDLINE_ZIP="$HOME/.cache/commandlinetools-linux-11076708_latest.zip"
GRADLE_ZIP="$HOME/.cache/gradle-8.9-bin.zip"
GRADLE_HOME="$HOME/.cache/gradle-8.9"
VOICE_VENV="$HOME/.cache/epc-v251-voice-venv"
VOICE_MODEL="$HOME/.cache/vits-piper-pt_BR-faber-medium.tar.bz2"
VOICE_ROOT="$HOME/.cache/epc-v251-voice-model"
DIST="$APP_DIR/dist"

log(){ printf '\n==> %s\n' "$*"; }

log "Pré-validando fonte EPC 2.5.1"
python3 tools/validate_music_251.py

log "Conferindo Java 17"
java -version 2>&1 | tee /tmp/epc-java-version.txt
if ! grep -Eq 'version "17\.|openjdk version "17\.' /tmp/epc-java-version.txt; then
  echo "Java 17 não encontrado. No Codespace, instale/ative o JDK 17 antes de continuar." >&2
  exit 1
fi

if ! command -v unzip >/dev/null 2>&1; then
  log "Instalando unzip"
  sudo apt-get update
  sudo apt-get install -y unzip
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

log "Instalando Android SDK 35"
yes | sdkmanager --licenses >/dev/null 2>&1 || true
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"

if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
  log "Instalando Gradle 8.9"
  mkdir -p "$HOME/.cache"
  if [ ! -f "$GRADLE_ZIP" ]; then
    curl -L --fail --retry 3 --retry-delay 2 \
      -o "$GRADLE_ZIP" \
      https://services.gradle.org/distributions/gradle-8.9-bin.zip
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

log "Executando testes e compilando Universal + Horizontal"
"$GRADLE" --no-daemon \
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

log "Validando APK Universal"
"$AAPT" dump badging "$U" | tee /tmp/epc-v251-universal.txt
grep -q "package: name='com.estradaplay.comunista.universal' versionCode='262' versionName='2.5.1'" /tmp/epc-v251-universal.txt
"$APKSIGNER" verify --print-certs "$U"

log "Validando APK Horizontal"
"$AAPT" dump badging "$H" | tee /tmp/epc-v251-horizontal.txt
grep -q "package: name='com.estradaplay.comunista.horizontal' versionCode='262' versionName='2.5.1'" /tmp/epc-v251-horizontal.txt
"$APKSIGNER" verify --print-certs "$H"

log "Organizando APKs"
rm -rf "$DIST"
mkdir -p "$DIST"
cp "$U" "$DIST/Estrada-Play-Comunista-Universal-2.5.1.apk"
cp "$H" "$DIST/Estrada-Play-Comunista-Horizontal-2.5.1.apk"
(
  cd "$DIST"
  sha256sum \
    Estrada-Play-Comunista-Universal-2.5.1.apk \
    Estrada-Play-Comunista-Horizontal-2.5.1.apk \
    | tee SHA256SUMS.txt
)

log "BUILD 2.5.1 CONCLUÍDO"
printf 'APKs em: %s\n' "$DIST"
printf ' - %s\n' "$DIST/Estrada-Play-Comunista-Universal-2.5.1.apk"
printf ' - %s\n' "$DIST/Estrada-Play-Comunista-Horizontal-2.5.1.apk"

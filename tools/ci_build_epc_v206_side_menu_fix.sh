#!/usr/bin/env bash
set -euo pipefail
# EPC_V206_SIDE_MENU_FIX: horizontal left rail only.
APP=estrada-play-comunista-app
OUT=Estrada-Play-Comunista-Universal-2.0.6-Menu-Lateral-Corrigido.apk
J="$APP/app/src/main/java/com/estradaplay/comunista"

base64 -d "$APP/signing/estradaplay-dev.keystore.b64" > "$APP/signing/estradaplay-dev.keystore"

python3 -m pip install --disable-pip-version-check sherpa-onnx soundfile numpy
MODEL=/tmp/vits-piper-pt_BR-faber-medium.tar.bz2
curl -L --fail --retry 3 --retry-delay 2 -o "$MODEL" https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-pt_BR-faber-medium.tar.bz2
echo "7add3f923ad6bc25ca8a192805fd1a64d1b3893e4611c4a9719545a825039a83  $MODEL" | sha256sum -c -
rm -rf /tmp/epc206-voice && mkdir -p /tmp/epc206-voice
tar -xjf "$MODEL" -C /tmp/epc206-voice
python3 "$APP/tools/generate_voice_bank.py" --model-root /tmp/epc206-voice --output "$APP/app/src/main/res/raw" --metadata-output "$APP/app/src/main/assets/estradaplay_voice"
test "$(find "$APP/app/src/main/res/raw" -maxdepth 1 -name 'ep_*.wav' | wc -l)" -ge 77

grep -q 'versionCode 206' "$APP/app/build.gradle"
grep -q "versionName '2.0.6'" "$APP/app/build.gradle"
grep -q 'SIDE_MENU_FIX_V206' "$J/RoadMapActivity.java"
grep -q 'int navH = clamp' "$J/RoadMapActivity.java"
grep -q 'setMinimumHeight(0)' "$J/RoadMapActivity.java"
grep -q 'HORIZONTAL_COCKPIT_V205' "$J/RoadMapActivity.java"
grep -q 'MUSIC_LIBRARY_NAV_V204' "$J/MusicPlayerActivity.java"
grep -q 'getNoBackupFilesDir' "$J/LibraryStore.java"
grep -q 'VOICE_FULL_ALERT_V192' "$J/EstradaPlayOfflineVoice.java"
if grep -R -q 'android.webkit.WebView\|new WebView' "$APP/app/src/main/java"; then echo 'WebView detected'; exit 1; fi

(cd "$APP" && gradle --no-daemon clean assembleUniversalDebug)
A="$APP/app/build/outputs/apk/universal/debug/app-universal-debug.apk"
test -f "$A"
"$ANDROID_HOME/build-tools/35.0.0/aapt" dump badging "$A" | tee /tmp/epc206-badging.txt
grep -q "package: name='com.estradaplay.comunista.universal' versionCode='206' versionName='2.0.6'" /tmp/epc206-badging.txt
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs "$A" >/tmp/epc206-cert.txt
cp "$A" "$OUT"
sha256sum "$OUT" | tee /tmp/epc206-sha.txt

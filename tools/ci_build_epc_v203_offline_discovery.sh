#!/usr/bin/env bash
set -euo pipefail
# EPC_V203_OFFLINE_DISCOVERY: physical storage discovery + uninstall-safe future downloads.
APP=estrada-play-comunista-app
OUT=Estrada-Play-Comunista-Universal-2.0.3-Recuperacao-Offline.apk
J="$APP/app/src/main/java/com/estradaplay/comunista"

base64 -d "$APP/signing/estradaplay-dev.keystore.b64" > "$APP/signing/estradaplay-dev.keystore"

python3 -m pip install --disable-pip-version-check sherpa-onnx soundfile numpy
MODEL=/tmp/vits-piper-pt_BR-faber-medium.tar.bz2
curl -L --fail --retry 3 --retry-delay 2 -o "$MODEL" https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-pt_BR-faber-medium.tar.bz2
echo "7add3f923ad6bc25ca8a192805fd1a64d1b3893e4611c4a9719545a825039a83  $MODEL" | sha256sum -c -
rm -rf /tmp/epc203-voice && mkdir -p /tmp/epc203-voice
tar -xjf "$MODEL" -C /tmp/epc203-voice
python3 "$APP/tools/generate_voice_bank.py" --model-root /tmp/epc203-voice --output "$APP/app/src/main/res/raw" --metadata-output "$APP/app/src/main/assets/estradaplay_voice"
test "$(find "$APP/app/src/main/res/raw" -maxdepth 1 -name 'ep_*.wav' | wc -l)" -ge 77

grep -q 'versionCode 203' "$APP/app/build.gradle"
grep -q "versionName '2.0.3'" "$APP/app/build.gradle"
grep -q 'OFFLINE_DISCOVERY_V203' "$J/LibraryStore.java"
grep -q 'getNoBackupFilesDir' "$J/LibraryStore.java"
grep -q 'syntheticLocalTrack' "$J/LibraryStore.java"
grep -q 'PUBLIC_DISCOVERY_V203' "$J/MusicStorageActivity.java"
grep -q 'matchLegacy(item,catalog)' "$J/MusicStorageActivity.java"
grep -q 'LIBRARY_REPAIR_ENTRY_V203' "$J/MainActivity.java"
grep -q 'PROCURAR / LIMPAR MÚSICAS DO CELULAR' "$J/MainActivity.java"
grep -q 'PROCURAR MÚSICAS NO CELULAR' "$J/MusicPlayerActivity.java"
grep -q 'MusicStorageActivity' "$APP/app/src/main/AndroidManifest.xml"
grep -q 'READ_MEDIA_AUDIO' "$APP/app/src/main/AndroidManifest.xml"
grep -q 'VOICE_FULL_ALERT_V192' "$J/EstradaPlayOfflineVoice.java"
if grep -R -q 'android.webkit.WebView\|new WebView' "$APP/app/src/main/java"; then echo 'WebView detected'; exit 1; fi

(cd "$APP" && gradle --no-daemon clean assembleUniversalDebug)
A="$APP/app/build/outputs/apk/universal/debug/app-universal-debug.apk"
test -f "$A"
"$ANDROID_HOME/build-tools/35.0.0/aapt" dump badging "$A" | tee /tmp/epc203-badging.txt
grep -q "package: name='com.estradaplay.comunista.universal' versionCode='203' versionName='2.0.3'" /tmp/epc203-badging.txt
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs "$A" >/tmp/epc203-cert.txt
cp "$A" "$OUT"
sha256sum "$OUT" | tee /tmp/epc203-sha.txt

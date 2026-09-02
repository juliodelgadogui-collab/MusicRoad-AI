#!/usr/bin/env bash
set -euo pipefail
# EPC_V201_BUILD_TRIGGER: compile and validate the automotive polish source.
APP=estrada-play-comunista-app
OUT=Estrada-Play-Comunista-Universal-2.0.1-Acabamento-Automotivo.apk
base64 -d "$APP/signing/estradaplay-dev.keystore.b64" > "$APP/signing/estradaplay-dev.keystore"
python3 -m pip install --disable-pip-version-check sherpa-onnx soundfile numpy
MODEL=/tmp/vits-piper-pt_BR-faber-medium.tar.bz2
curl -L --fail --retry 3 --retry-delay 2 -o "$MODEL" https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-pt_BR-faber-medium.tar.bz2
echo "7add3f923ad6bc25ca8a192805fd1a64d1b3893e4611c4a9719545a825039a83  $MODEL" | sha256sum -c -
rm -rf /tmp/epc201-voice && mkdir -p /tmp/epc201-voice
tar -xjf "$MODEL" -C /tmp/epc201-voice
python3 "$APP/tools/generate_voice_bank.py" --model-root /tmp/epc201-voice --output "$APP/app/src/main/res/raw" --metadata-output "$APP/app/src/main/assets/estradaplay_voice"
test "$(find "$APP/app/src/main/res/raw" -maxdepth 1 -name 'ep_*.wav' | wc -l)" -ge 77
J="$APP/app/src/main/java/com/estradaplay/comunista"
grep -q 'NAV_POLISH_V201' "$J/RouteEngine.java"
grep -q 'ALERT_CARD_V201' "$J/SafetyAlertOverlay.java"
grep -q 'class EpcMotion' "$J/EpcMotion.java"
grep -q 'navDistanceText' "$J/RoadMapActivity.java"
grep -q 'mapPlayerToggle' "$J/RoadMapActivity.java"
grep -q 'VOICE_FULL_ALERT_V192' "$J/EstradaPlayOfflineVoice.java"
if grep -R -q 'android.webkit.WebView\|new WebView' "$APP/app/src/main/java"; then echo 'WebView detected'; exit 1; fi
(cd "$APP" && gradle --no-daemon clean assembleUniversalDebug)
A="$APP/app/build/outputs/apk/universal/debug/app-universal-debug.apk"
test -f "$A"
"$ANDROID_HOME/build-tools/35.0.0/aapt" dump badging "$A" | tee /tmp/epc201-badging.txt
grep -q "package: name='com.estradaplay.comunista.universal' versionCode='201' versionName='2.0.1'" /tmp/epc201-badging.txt
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs "$A" >/tmp/epc201-cert.txt
cp "$A" "$OUT"
sha256sum "$OUT" | tee /tmp/epc201-sha.txt

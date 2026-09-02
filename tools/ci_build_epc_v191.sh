#!/usr/bin/env bash
set -euo pipefail

APP=estrada-play-comunista-app
OUT=Estrada-Play-Comunista-Universal-1.9.1-Clima-da-Rota.apk

base64 -d "$APP/signing/estradaplay-dev.keystore.b64" > "$APP/signing/estradaplay-dev.keystore"
python3 -m pip install --disable-pip-version-check sherpa-onnx soundfile numpy
MODEL=/tmp/vits-piper-pt_BR-faber-medium.tar.bz2
curl -L --fail --retry 3 --retry-delay 2 -o "$MODEL" https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-pt_BR-faber-medium.tar.bz2
echo "7add3f923ad6bc25ca8a192805fd1a64d1b3893e4611c4a9719545a825039a83  $MODEL" | sha256sum -c -
rm -rf /tmp/epc191-voice && mkdir -p /tmp/epc191-voice
tar -xjf "$MODEL" -C /tmp/epc191-voice
python3 "$APP/tools/generate_voice_bank.py" --model-root /tmp/epc191-voice --output "$APP/app/src/main/res/raw" --metadata-output "$APP/app/src/main/assets/estradaplay_voice"
test "$(find "$APP/app/src/main/res/raw" -maxdepth 1 -name 'ep_*.wav' | wc -l)" -ge 77

(cd "$APP" && gradle --no-daemon clean assembleUniversalDebug)
A="$APP/app/build/outputs/apk/universal/debug/app-universal-debug.apk"
test -f "$A"
"$ANDROID_HOME/build-tools/35.0.0/aapt" dump badging "$A" | tee /tmp/epc191-badging.txt
grep -q "package: name='com.estradaplay.comunista.universal' versionCode='191' versionName='1.9.1'" /tmp/epc191-badging.txt
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs "$A" >/tmp/epc191-cert.txt
cp "$A" "$OUT"
sha256sum "$OUT" | tee /tmp/epc191-sha.txt

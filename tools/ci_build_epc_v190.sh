#!/usr/bin/env bash
set -euo pipefail

APP=estrada-play-comunista-app
OUT=Estrada-Play-Comunista-Universal-1.9.0-Central-Inteligente.apk
SERVER=Estrada-Play-Comunista-1.9.0-SERVIDOR.zip

base64 -d "$APP/signing/estradaplay-dev.keystore.b64" > "$APP/signing/estradaplay-dev.keystore"
python3 -m pip install --disable-pip-version-check sherpa-onnx soundfile numpy
MODEL=/tmp/vits-piper-pt_BR-faber-medium.tar.bz2
curl -L --fail --retry 3 --retry-delay 2 -o "$MODEL" https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-pt_BR-faber-medium.tar.bz2
echo "7add3f923ad6bc25ca8a192805fd1a64d1b3893e4611c4a9719545a825039a83  $MODEL" | sha256sum -c -
rm -rf /tmp/epc190-voice && mkdir -p /tmp/epc190-voice
tar -xjf "$MODEL" -C /tmp/epc190-voice
python3 "$APP/tools/generate_voice_bank.py" --model-root /tmp/epc190-voice --output "$APP/app/src/main/res/raw" --metadata-output "$APP/app/src/main/assets/estradaplay_voice"
test "$(find "$APP/app/src/main/res/raw" -maxdepth 1 -name 'ep_*.wav' | wc -l)" -ge 77

(cd "$APP" && gradle --no-daemon clean assembleUniversalDebug)
A="$APP/app/build/outputs/apk/universal/debug/app-universal-debug.apk"
test -f "$A"
"$ANDROID_HOME/build-tools/35.0.0/aapt" dump badging "$A" | tee /tmp/epc190-badging.txt
grep -q "package: name='com.estradaplay.comunista.universal' versionCode='190' versionName='1.9.0'" /tmp/epc190-badging.txt
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs "$A" >/tmp/epc190-cert.txt
cp "$A" "$OUT"
sha256sum "$OUT" | tee /tmp/epc190-sha.txt

rm -rf /tmp/epc190-server && mkdir -p /tmp/epc190-server/api
cp api/road_live.php api/convoy.php api/fuel_live.php /tmp/epc190-server/api/
printf '%s\n' \
  'Estrada Play Comunista Universal 1.9.0 - Central Inteligente' \
  'Copie a pasta api/ para a pasta api/ do servidor atual.' \
  'road_live.php = Estrada Viva' \
  'convoy.php = Comboio Virtual' \
  'fuel_live.php = precos comunitarios de combustivel' \
  'Manutencao, SOS, HUD noturno e OBD2 sao locais.' \
  'OBD2 e somente leitura e requer ELM327 Bluetooth Classic pareado.' > /tmp/epc190-server/LEIA-PRIMEIRO.txt
(cd /tmp/epc190-server && zip -qr "$GITHUB_WORKSPACE/$SERVER" .)

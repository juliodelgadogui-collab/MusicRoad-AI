#!/usr/bin/env bash
set -euo pipefail

APP=estrada-play-comunista-app
J="$APP/app/src/main/java/com/estradaplay/comunista"
APK_OUT=Estrada-Play-Comunista-Universal-2.0.9-Contexto-Inteligente.apk
SERVER_OUT=Estrada-Play-Comunista-2.0.9-SERVIDOR-CONTEXTO-7.zip

base64 -d "$APP/signing/estradaplay-dev.keystore.b64" > "$APP/signing/estradaplay-dev.keystore"

# Keep the validated embedded Portuguese voice bank.
python3 -m pip install --disable-pip-version-check sherpa-onnx soundfile numpy
MODEL=/tmp/vits-piper-pt_BR-faber-medium.tar.bz2
curl -L --fail --retry 3 --retry-delay 2 -o "$MODEL" https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-pt_BR-faber-medium.tar.bz2
echo "7add3f923ad6bc25ca8a192805fd1a64d1b3893e4611c4a9719545a825039a83  $MODEL" | sha256sum -c -
rm -rf /tmp/epc209-voice && mkdir -p /tmp/epc209-voice
tar -xjf "$MODEL" -C /tmp/epc209-voice
python3 "$APP/tools/generate_voice_bank.py" --model-root /tmp/epc209-voice --output "$APP/app/src/main/res/raw" --metadata-output "$APP/app/src/main/assets/estradaplay_voice"
test "$(find "$APP/app/src/main/res/raw" -maxdepth 1 -name 'ep_*.wav' | wc -l)" -ge 77

# 2.0.9 unified-context source contract.
grep -q 'versionCode 209' "$APP/app/build.gradle"
grep -q "versionName '2.0.9'" "$APP/app/build.gradle"
grep -q 'CONTEXTO_INTELIGENTE_V209' "$J/RouteContextV7Client.java"
grep -q 'CONTEXTO_INTELIGENTE_V209' "$J/RoadMapActivity.java"
grep -q 'api/route_context.php' "$J/RouteContextV7Client.java"
grep -q 'RouteContextV7Client.fetch' "$J/RoadMapActivity.java"
grep -q 'DriveSettings.setRainAutoDetected' "$J/RoadMapActivity.java"
grep -q 'TRÂNSITO COLABORATIVO' "$J/DriveToolsActivity.java"
grep -q 'getBoolean("context_traffic_opt_in",false)' "$J/DriveSettings.java"
grep -q 'body.put("traffic_opt_in", trafficOptIn)' "$J/RouteContextV7Client.java"
grep -q 'body.put("include", new JSONArray().put("hazards").put("live").put("traffic").put("weather").put("fuel"))' "$J/RouteContextV7Client.java"

# Server 7.0 unified endpoint and privacy contract.
grep -q 'ep7_build_context' api/route_context.php
grep -q "device_id_stored'=>false" api/server_context_v7.php
grep -q "traffic_retention_h'=>2" api/server_context_v7.php
grep -q 'ep7_store_traffic_sample' api/server_context_v7.php
grep -q "\['hazards','live','traffic','weather','fuel'\]" api/server_context_v7.php

# Security 2.0.7 and native app constraints must remain present.
grep -q 'SECURITY_V207' "$J/SecureDeviceCredential.java"
grep -q 'native_device_credentials' api/native_auth.php
if grep -R -q 'android.webkit.WebView\|new WebView' "$APP/app/src/main/java"; then
  echo 'WebView detected'; exit 1
fi

for f in api/route_context.php api/server_context_v7.php api/context_v7_core.php api/context_v7_live.php api/context_v7_weather.php api/native_auth.php; do
  php -l "$f"
done

(cd "$APP" && gradle --no-daemon clean assembleUniversalDebug)
A="$APP/app/build/outputs/apk/universal/debug/app-universal-debug.apk"
test -f "$A"
"$ANDROID_HOME/build-tools/35.0.0/aapt" dump badging "$A" | tee /tmp/epc209-badging.txt
grep -q "package: name='com.estradaplay.comunista.universal' versionCode='209' versionName='2.0.9'" /tmp/epc209-badging.txt
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs "$A" | tee /tmp/epc209-cert.txt
cp "$A" "$APK_OUT"

rm -rf /tmp/epc209-server && mkdir -p /tmp/epc209-server/api
cp api/route_context.php api/server_context_v7.php api/context_v7_core.php api/context_v7_live.php api/context_v7_weather.php /tmp/epc209-server/api/
cat > /tmp/epc209-server/LEIA-PRIMEIRO.txt <<'TXT'
ESTRADA PLAY COMUNISTA 2.0.9 - CONTEXTO INTELIGENTE / SERVER 7.0

Atualizacao incremental segura do servidor. Usa o MESMO banco de dados e a mesma config atual.
Nao inclui config.php, banco, midia ou APK dentro deste ZIP.

Arquivos de contexto:
- api/route_context.php
- api/server_context_v7.php
- api/context_v7_core.php
- api/context_v7_live.php
- api/context_v7_weather.php

O cockpit 2.0.9 passa a consultar uma unica API autenticada para receber:
- clima/chuva ao longo da rota;
- transito colaborativo;
- acidentes e eventos da Estrada Viva;
- perigos de estrada;
- combustivel recente ao longo da rota.

PRIVACIDADE DO TRANSITO COLABORATIVO
- desativado por padrao no APK;
- so envia amostra quando o usuario ativa explicitamente TRÂNSITO COLABORATIVO;
- o Server 7.0 grava somente hash derivado do aparelho, nao o ID bruto;
- retencao da amostra: 2 horas;
- sem opt-in, o usuario continua podendo RECEBER o contexto de transito, mas nao contribui com amostras.

INSTALACAO
1. Faca backup da configuracao e do banco atuais.
2. Envie estes arquivos para a pasta api/ existente, substituindo as versoes correspondentes.
3. Nao apague config/config.php, config.php ou o banco atual.
4. O endpoint usa a autenticacao nativa segura da 2.0.7.
5. Se o Server 7.0 ficar indisponivel, a protecao local/offline do APK continua funcionando.
TXT
(cd /tmp/epc209-server && zip -qr "$GITHUB_WORKSPACE/$SERVER_OUT" .)

sha256sum "$APK_OUT" "$SERVER_OUT" | tee /tmp/epc209-sha.txt

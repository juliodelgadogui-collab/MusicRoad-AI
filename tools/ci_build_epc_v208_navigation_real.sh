#!/usr/bin/env bash
set -euo pipefail

APP=estrada-play-comunista-app
J="$APP/app/src/main/java/com/estradaplay/comunista"
APK_OUT=Estrada-Play-Comunista-Universal-2.0.8-Navegacao-Real.apk
SERVER_OUT=Estrada-Play-Comunista-2.0.8-SERVIDOR-NAVEGACAO.zip

base64 -d "$APP/signing/estradaplay-dev.keystore.b64" > "$APP/signing/estradaplay-dev.keystore"

# Keep the validated embedded Portuguese voice bank.
python3 -m pip install --disable-pip-version-check sherpa-onnx soundfile numpy
MODEL=/tmp/vits-piper-pt_BR-faber-medium.tar.bz2
curl -L --fail --retry 3 --retry-delay 2 -o "$MODEL" https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-pt_BR-faber-medium.tar.bz2
echo "7add3f923ad6bc25ca8a192805fd1a64d1b3893e4611c4a9719545a825039a83  $MODEL" | sha256sum -c -
rm -rf /tmp/epc208-voice && mkdir -p /tmp/epc208-voice
tar -xjf "$MODEL" -C /tmp/epc208-voice
python3 "$APP/tools/generate_voice_bank.py" --model-root /tmp/epc208-voice --output "$APP/app/src/main/res/raw" --metadata-output "$APP/app/src/main/assets/estradaplay_voice"
test "$(find "$APP/app/src/main/res/raw" -maxdepth 1 -name 'ep_*.wav' | wc -l)" -ge 77

# Source validation.
grep -q 'versionCode 208' "$APP/app/build.gradle"
grep -q "versionName '2.0.8'" "$APP/app/build.gradle"
grep -q 'NAVIGATION_REAL_V208' "$J/RouteEngine.java"
grep -q 'NAVIGATION_REAL_ACTIVITY_V208' "$J/RoadMapActivity.java"
grep -q 'upcomingStep' "$J/RouteEngine.java"
grep -q 'Match match' "$J/RouteEngine.java"
grep -q 'route.match(lat, lon' "$J/RoadMapActivity.java"
grep -q 'offRouteSamples >= 3' "$J/RoadMapActivity.java"
grep -q 'Recalculando rota' "$J/RoadMapActivity.java"
grep -q 'api/navigation_route.php' "$J/RouteEngine.java"
grep -q "map_matching'=>'device-polyline'" api/navigation_route.php
grep -q "off_route_recalculation'=>true" api/navigation_route.php
# Security 2.0.7 must remain present.
grep -q 'SECURITY_V207' "$J/SecureDeviceCredential.java"
grep -q 'native_device_credentials' api/native_auth.php
if grep -R -q 'android.webkit.WebView\|new WebView' "$APP/app/src/main/java"; then
  echo 'WebView detected'; exit 1
fi

php -l api/navigation_route.php
php -l api/native_auth.php
php -l api/native_app.php

(cd "$APP" && gradle --no-daemon clean assembleUniversalDebug)
A="$APP/app/build/outputs/apk/universal/debug/app-universal-debug.apk"
test -f "$A"
"$ANDROID_HOME/build-tools/35.0.0/aapt" dump badging "$A" | tee /tmp/epc208-badging.txt
grep -q "package: name='com.estradaplay.comunista.universal' versionCode='208' versionName='2.0.8'" /tmp/epc208-badging.txt
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs "$A" | tee /tmp/epc208-cert.txt
cp "$A" "$APK_OUT"

rm -rf /tmp/epc208-server && mkdir -p /tmp/epc208-server/api
cp api/navigation_route.php /tmp/epc208-server/api/
cat > /tmp/epc208-server/LEIA-PRIMEIRO.txt <<'TXT'
ESTRADA PLAY COMUNISTA 2.0.8 - NAVEGACAO REAL

Atualizacao incremental do servidor.

Arquivo novo:
- api/navigation_route.php

Instalacao:
1. Mantenha os arquivos e o banco atuais do servidor.
2. Envie navigation_route.php para a pasta api/ existente.
3. Nao apague config.php nem o banco.
4. O endpoint usa a autenticacao segura introduzida na 2.0.7.
5. Quando Mapbox estiver configurado, a rota usa Mapbox Directions v5 pelo servidor.
6. Se Mapbox estiver temporariamente indisponivel, existe fallback operacional para OSRM.

No APK 2.0.8 o progresso e calculado sobre a geometria real da rota, com map matching local,
sequencia completa de manobras, deteccao de saida da rota e recalculo automatico.
O APK ainda possui fallback direto de rota durante a transicao de servidores 2.0.7 -> 2.0.8.
TXT
(cd /tmp/epc208-server && zip -qr "$GITHUB_WORKSPACE/$SERVER_OUT" .)

sha256sum "$APK_OUT" "$SERVER_OUT" | tee /tmp/epc208-sha.txt

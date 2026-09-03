#!/usr/bin/env bash
set -euo pipefail

APP=estrada-play-comunista-app
J="$APP/app/src/main/java/com/estradaplay/comunista"
T="$APP/app/src/test/java/com/estradaplay/comunista"
APK_OUT=Estrada-Play-Comunista-Universal-2.3.0-Comboio-Avancado.apk
SERVER_OUT=Estrada-Play-Comunista-2.3.0-SERVIDOR-COMBOIO.zip

base64 -d "$APP/signing/estradaplay-dev.keystore.b64" > "$APP/signing/estradaplay-dev.keystore"

grep -q 'versionCode 230' "$APP/app/build.gradle"
grep -q "versionName '2.3.0'" "$APP/app/build.gradle"
grep -q 'COMBOIO_AVANCADO_V230' "$J/ConvoyActivity.java"
grep -q 'COMBOIO_AVANCADO_V230' "$J/ConvoyStore.java"
grep -q 'COMBOIO_AVANCADO_V230' "$J/ConvoyLiveBridge.java"
grep -q 'COMBOIO_AVANCADO_V230' "$J/ConvoySyncPolicy.java"
grep -q 'ConvoyLiveBridge' "$J/EstradaPlayApplication.java"
grep -q 'set_route' api/convoy.php
grep -q "action==='kick'" api/convoy.php
grep -q 'self_is_leader' api/convoy.php
grep -q 'presence_ttl_s' api/convoy.php
php -l api/convoy.php

# Consolidated safety/context architecture must remain intact.
grep -q 'BASE_CONSOLIDADA_V210' "$J/RoadSafetyService.java"
grep -q 'CONTEXTO_INTELIGENTE_V209' "$J/RouteContextV7Client.java"
grep -q 'CONTEXTO_VIVO_V220' "$J/RouteContextV7BackgroundReceiver.java"
grep -q 'android:name=".EstradaPlayApplication"' "$APP/app/src/main/AndroidManifest.xml"
SERVICE_BYTES=$(wc -c < "$J/RoadSafetyService.java")
if [ "$SERVICE_BYTES" -ge 47000 ]; then echo 'RoadSafetyService consolidation regressed'; exit 1; fi

# JVM tests remain mandatory and now cover convoy sync/separation policy.
test -f "$T/RoadHazardMatcherTest.java"
test -f "$T/RoadHazardSelectorTest.java"
test -f "$T/RoadAlertCooldownTest.java"
test -f "$T/RoadLimitPolicyTest.java"
test -f "$T/RoadSafetyFormatTest.java"
test -f "$T/RouteContextV7BackgroundPolicyTest.java"
test -f "$T/ConvoySyncPolicyTest.java"
(cd "$APP" && gradle --no-daemon testUniversalDebugUnitTest)

# Preserve prior milestone contracts and privacy/security behavior.
grep -q 'SECURITY_V207' "$J/SecureDeviceCredential.java"
grep -q 'NAVIGATION_REAL_V208' "$J/RouteEngine.java"
grep -q 'NAVIGATION_REAL_ACTIVITY_V208' "$J/RoadMapActivity.java"
grep -q 'getBoolean("context_traffic_opt_in",false)' "$J/DriveSettings.java"
if grep -R -q 'android.webkit.WebView\|new WebView' "$APP/app/src/main/java"; then echo 'WebView detected'; exit 1; fi

# Only 2.3.0 may own the EPC Android workflow namespace.
LEGACY_EPC_WORKFLOWS="$(find .github/workflows -maxdepth 1 -type f \
  \( -name 'build-estrada-play-comunista*.yml' -o -name 'build-estrada-play-portable*.yml' -o -name 'build-estrada-play-universal-v*.yml' \) \
  ! -name 'build-estrada-play-universal-v230-comboio-avancado.yml' -print)"
if [ -n "$LEGACY_EPC_WORKFLOWS" ]; then echo 'Obsolete EPC Android workflow detected:'; printf '%s\n' "$LEGACY_EPC_WORKFLOWS"; exit 1; fi

# Preserve validated embedded Portuguese voice bank.
python3 -m pip install --disable-pip-version-check sherpa-onnx soundfile numpy
MODEL=/tmp/vits-piper-pt_BR-faber-medium.tar.bz2
curl -L --fail --retry 3 --retry-delay 2 -o "$MODEL" https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-pt_BR-faber-medium.tar.bz2
echo "7add3f923ad6bc25ca8a192805fd1a64d1b3893e4611c4a9719545a825039a83  $MODEL" | sha256sum -c -
rm -rf /tmp/epc230-voice && mkdir -p /tmp/epc230-voice
tar -xjf "$MODEL" -C /tmp/epc230-voice
python3 "$APP/tools/generate_voice_bank.py" --model-root /tmp/epc230-voice --output "$APP/app/src/main/res/raw" --metadata-output "$APP/app/src/main/assets/estradaplay_voice"
test "$(find "$APP/app/src/main/res/raw" -maxdepth 1 -name 'ep_*.wav' | wc -l)" -ge 77

(cd "$APP" && gradle --no-daemon assembleUniversalDebug)
A="$APP/app/build/outputs/apk/universal/debug/app-universal-debug.apk"
test -f "$A"
"$ANDROID_HOME/build-tools/35.0.0/aapt" dump badging "$A" | tee /tmp/epc230-badging.txt
grep -q "package: name='com.estradaplay.comunista.universal' versionCode='230' versionName='2.3.0'" /tmp/epc230-badging.txt
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs "$A" | tee /tmp/epc230-cert.txt
cp "$A" "$APK_OUT"
sha256sum "$APK_OUT" | tee /tmp/epc230-apk-sha.txt

rm -rf /tmp/epc230-server && mkdir -p /tmp/epc230-server/api
cp api/convoy.php /tmp/epc230-server/api/convoy.php
cat > /tmp/epc230-server/LEIA-PRIMEIRO.txt <<'TXT'
ESTRADA PLAY COMUNISTA 2.3.0 - ATUALIZACAO DO SERVIDOR / COMBOIO AVANCADO

1. Faça backup do servidor e do banco atual.
2. Envie a pasta api/ sobre a instalação atual, substituindo somente api/convoy.php.
3. O endpoint usa a mesma conexão db() e as tabelas atuais estrada_convoys / estrada_convoy_members.
4. Na primeira chamada ele adiciona, se necessário, campos de líder/destino/rota e cria estrada_convoy_blocks.
5. Não apaga usuários, músicas, radares, Estrada Viva, contexto Server 7.0 ou outros dados.
6. Requer a autenticação nativa segura já usada pelo app 2.2.0/2.1.0.

Funções novas: líder, rota compartilhada, distância ao líder, desvio da rota, ETA, presença com TTL de 90 s, remoção/bloqueio e troca automática de líder quando o líder sai.
TXT
(cd /tmp/epc230-server && zip -qr "$GITHUB_WORKSPACE/$SERVER_OUT" .)
sha256sum "$SERVER_OUT" | tee /tmp/epc230-server-sha.txt

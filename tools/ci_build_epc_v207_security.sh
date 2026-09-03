#!/usr/bin/env bash
set -euo pipefail

APP=estrada-play-comunista-app
J="$APP/app/src/main/java/com/estradaplay/comunista"
APK_OUT=Estrada-Play-Comunista-Universal-2.0.7-Seguranca-Estabilidade.apk
SERVER_OUT=Estrada-Play-Comunista-2.0.7-SERVIDOR-SEGURANCA.zip

base64 -d "$APP/signing/estradaplay-dev.keystore.b64" > "$APP/signing/estradaplay-dev.keystore"

# Keep the deterministic embedded Portuguese voice bank used by the current validated line.
python3 -m pip install --disable-pip-version-check sherpa-onnx soundfile numpy
MODEL=/tmp/vits-piper-pt_BR-faber-medium.tar.bz2
curl -L --fail --retry 3 --retry-delay 2 -o "$MODEL" https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-pt_BR-faber-medium.tar.bz2
echo "7add3f923ad6bc25ca8a192805fd1a64d1b3893e4611c4a9719545a825039a83  $MODEL" | sha256sum -c -
rm -rf /tmp/epc207-voice && mkdir -p /tmp/epc207-voice
tar -xjf "$MODEL" -C /tmp/epc207-voice
python3 "$APP/tools/generate_voice_bank.py" --model-root /tmp/epc207-voice --output "$APP/app/src/main/res/raw" --metadata-output "$APP/app/src/main/assets/estradaplay_voice"
test "$(find "$APP/app/src/main/res/raw" -maxdepth 1 -name 'ep_*.wav' | wc -l)" -ge 77

# Version and architecture validation.
grep -q 'versionCode 207' "$APP/app/build.gradle"
grep -q "versionName '2.0.7'" "$APP/app/build.gradle"
grep -q 'SECURITY_V207' "$J/SecureDeviceCredential.java"
grep -q 'AndroidKeyStore' "$J/SecureDeviceCredential.java"
grep -q 'X-EstradaPlay-Device-Secret' "$J/ApiClient.java"
grep -q 'Authorization.*Bearer' "$J/ApiClient.java"
grep -q 'refreshIfPossible' "$J/ApiClient.java"
grep -q 'isTrustedTarget' "$J/ApiClient.java"
grep -q 'native_device_credentials' api/native_auth.php
grep -q 'NATIVE_ACCESS_TTL = 1800' api/native_auth.php
grep -q 'NATIVE_REFRESH_TTL = 2592000' api/native_auth.php
grep -q "action === 'refresh'" api/native_app.php
grep -q 'native_revoke_device' admin_devices.php
if grep -A25 "if (\$action === 'device_login')" api/native_app.php | grep -q 'native_user_for_device'; then
  echo 'SECURITY FAILURE: device_login still accepts token-only lookup'; exit 1
fi
if grep -R -q 'android.webkit.WebView\|new WebView' "$APP/app/src/main/java"; then
  echo 'WebView detected'; exit 1
fi

php -l api/native_auth.php
php -l api/native_app.php
php -l admin_devices.php

(cd "$APP" && gradle --no-daemon clean assembleUniversalDebug)
A="$APP/app/build/outputs/apk/universal/debug/app-universal-debug.apk"
test -f "$A"
"$ANDROID_HOME/build-tools/35.0.0/aapt" dump badging "$A" | tee /tmp/epc207-badging.txt
grep -q "package: name='com.estradaplay.comunista.universal' versionCode='207' versionName='2.0.7'" /tmp/epc207-badging.txt
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs "$A" | tee /tmp/epc207-cert.txt
cp "$A" "$APK_OUT"

rm -rf /tmp/epc207-server && mkdir -p /tmp/epc207-server/api
cp api/native_auth.php /tmp/epc207-server/api/
cp api/native_app.php /tmp/epc207-server/api/
cp admin_devices.php /tmp/epc207-server/
cat > /tmp/epc207-server/LEIA-PRIMEIRO.txt <<'TXT'
ESTRADA PLAY COMUNISTA 2.0.7 - SEGURANCA E ESTABILIDADE

Arquivos do servidor:
- api/native_auth.php
- api/native_app.php
- admin_devices.php

Instalacao:
1. Faça backup dos arquivos atuais e do banco.
2. Envie os dois PHP da pasta api/ para a pasta api/ existente do servidor.
3. Envie admin_devices.php para a raiz do painel.
4. Acesse o app 2.0.7 e faça um login normal em um aparelho de teste.
5. Abra admin_devices.php como administrador e confirme o estado SEGURO.

A tabela native_device_credentials e criada de forma idempotente pelo backend para MySQL/MariaDB ou SQLite.
O ID do aparelho nao autentica mais sozinho. O servidor armazena hashes do segredo e dos tokens.
Access token: 30 minutos. Refresh token: 30 dias e rotacionado a cada renovacao.
Revogar um aparelho invalida access/refresh; uma nova autenticacao por senha pode autoriza-lo novamente.

IMPORTANTE: este pacote nao altera nem apaga biblioteca, radares, mapas offline, Estrada Viva ou comboios.
TXT
(cd /tmp/epc207-server && zip -qr "$GITHUB_WORKSPACE/$SERVER_OUT" .)

sha256sum "$APK_OUT" "$SERVER_OUT" | tee /tmp/epc207-sha.txt

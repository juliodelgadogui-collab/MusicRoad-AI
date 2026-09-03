#!/usr/bin/env bash
set -euo pipefail

APP=estrada-play-comunista-app
J="$APP/app/src/main/java/com/estradaplay/comunista"
T="$APP/app/src/test/java/com/estradaplay/comunista"
APK_OUT=Estrada-Play-Comunista-Universal-2.3.3-Sessao-Servidor-Corrigida.apk

base64 -d "$APP/signing/estradaplay-dev.keystore.b64" > "$APP/signing/estradaplay-dev.keystore"

grep -q 'versionCode 233' "$APP/app/build.gradle"
grep -q "versionName '2.3.3'" "$APP/app/build.gradle"

# SESSION_HOST_COMPAT_V233: shared PHP hosting may strip Authorization/custom headers.
grep -q 'SESSION_HOST_COMPAT_V233' "$J/ApiClient.java"
grep -q 'c.setRequestProperty("Cookie", cookie.trim())' "$J/ApiClient.java"
grep -q 'data.put("device_secret", credential.secret())' "$J/ApiClient.java"
grep -q 'recoverDeviceSessionIfPossible' "$J/ApiClient.java"
grep -q 'native_app.php?action=device_login' "$J/ApiClient.java"
grep -q 'refreshIfPossible' "$J/ApiClient.java"

# Persistent secret/reinstall safety from 2.3.2 remains required.
grep -q 'SESSION_PERSIST_V232' "$J/SecureDeviceCredential.java"
grep -q 'device_secret_recovery_v232' "$J/SecureDeviceCredential.java"
grep -q 'SESSION_PERSIST_V232' "$J/GateActivity.java"
grep -q 'android:allowBackup="false"' "$APP/app/src/main/AndroidManifest.xml"

# Driver-visible version must be the real APK version, even after dynamic redraws.
grep -q 'VERSION_VISIBLE_V233' "$J/UiVersionLabelFix.java"
grep -q 'value.startsWith("EPC 2.")' "$J/UiVersionLabelFix.java"
grep -q 'BuildConfig.VERSION_NAME' "$J/UiVersionLabelFix.java"
grep -q 'UiVersionLabelFix.register' "$J/EstradaPlayApplication.java"

# Preserve stability, Comboio and consolidated milestones.
grep -q 'STABILITY_V232' "$J/ProcessCrashGuard.java"
grep -q 'COMBOIO_AVANCADO_V230' "$J/ConvoyActivity.java"
grep -q 'COMBOIO_AVANCADO_V230' "$J/ConvoyStore.java"
grep -q 'COMBOIO_AVANCADO_V230' "$J/ConvoyLiveBridge.java"
grep -q 'BASE_CONSOLIDADA_V210' "$J/RoadSafetyService.java"
grep -q 'CONTEXTO_INTELIGENTE_V209' "$J/RouteContextV7Client.java"
grep -q 'CONTEXTO_VIVO_V220' "$J/RouteContextV7BackgroundReceiver.java"
grep -q 'SECURITY_V207' "$J/SecureDeviceCredential.java"
grep -q 'NAVIGATION_REAL_V208' "$J/RouteEngine.java"
grep -q 'set_route' api/convoy.php
grep -q "action==='kick'" api/convoy.php
php -l api/convoy.php
php -l api/native_app.php
php -l api/native_auth.php

SERVICE_BYTES=$(wc -c < "$J/RoadSafetyService.java")
if [ "$SERVICE_BYTES" -ge 47000 ]; then echo 'RoadSafetyService consolidation regressed'; exit 1; fi
if grep -R -q 'android.webkit.WebView\|new WebView' "$APP/app/src/main/java"; then echo 'WebView detected'; exit 1; fi

# All JVM tests remain mandatory.
test -f "$T/RoadHazardMatcherTest.java"
test -f "$T/RoadHazardSelectorTest.java"
test -f "$T/RoadAlertCooldownTest.java"
test -f "$T/RoadLimitPolicyTest.java"
test -f "$T/RoadSafetyFormatTest.java"
test -f "$T/RouteContextV7BackgroundPolicyTest.java"
test -f "$T/ConvoySyncPolicyTest.java"
test -f "$T/ReinstallSessionPolicyTest.java"
(cd "$APP" && gradle --no-daemon testUniversalDebugUnitTest)

# Only the current 2.3.3 Android workflow may own this EPC namespace.
LEGACY_EPC_WORKFLOWS="$(find .github/workflows -maxdepth 1 -type f \
  \( -name 'build-estrada-play-comunista*.yml' -o -name 'build-estrada-play-portable*.yml' -o -name 'build-estrada-play-universal-v*.yml' \) \
  ! -name 'build-estrada-play-universal-v233-auth-host-compat.yml' -print)"
if [ -n "$LEGACY_EPC_WORKFLOWS" ]; then echo 'Obsolete EPC Android workflow detected:'; printf '%s\n' "$LEGACY_EPC_WORKFLOWS"; exit 1; fi

# Preserve validated embedded Portuguese voice bank.
python3 -m pip install --disable-pip-version-check sherpa-onnx soundfile numpy
MODEL=/tmp/vits-piper-pt_BR-faber-medium.tar.bz2
curl -L --fail --retry 3 --retry-delay 2 -o "$MODEL" https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-pt_BR-faber-medium.tar.bz2
echo "7add3f923ad6bc25ca8a192805fd1a64d1b3893e4611c4a9719545a825039a83  $MODEL" | sha256sum -c -
rm -rf /tmp/epc233-voice && mkdir -p /tmp/epc233-voice
tar -xjf "$MODEL" -C /tmp/epc233-voice
python3 "$APP/tools/generate_voice_bank.py" --model-root /tmp/epc233-voice --output "$APP/app/src/main/res/raw" --metadata-output "$APP/app/src/main/assets/estradaplay_voice"
test "$(find "$APP/app/src/main/res/raw" -maxdepth 1 -name 'ep_*.wav' | wc -l)" -ge 77

(cd "$APP" && gradle --no-daemon assembleUniversalDebug)
A="$APP/app/build/outputs/apk/universal/debug/app-universal-debug.apk"
test -f "$A"
"$ANDROID_HOME/build-tools/35.0.0/aapt" dump badging "$A" | tee /tmp/epc233-badging.txt
grep -q "package: name='com.estradaplay.comunista.universal' versionCode='233' versionName='2.3.3'" /tmp/epc233-badging.txt
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs "$A" | tee /tmp/epc233-cert.txt
cp "$A" "$APK_OUT"
sha256sum "$APK_OUT" | tee /tmp/epc233-apk-sha.txt

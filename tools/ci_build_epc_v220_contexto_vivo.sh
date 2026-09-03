#!/usr/bin/env bash
set -euo pipefail

APP=estrada-play-comunista-app
J="$APP/app/src/main/java/com/estradaplay/comunista"
T="$APP/app/src/test/java/com/estradaplay/comunista"
APK_OUT=Estrada-Play-Comunista-Universal-2.2.0-Contexto-Vivo.apk

base64 -d "$APP/signing/estradaplay-dev.keystore.b64" > "$APP/signing/estradaplay-dev.keystore"

# 2.2.0 stays on the consolidated direct-source architecture.
grep -q 'versionCode 220' "$APP/app/build.gradle"
grep -q "versionName '2.2.0'" "$APP/app/build.gradle"
grep -q "testImplementation 'junit:junit:4.13.2'" "$APP/app/build.gradle"
grep -q 'BASE_CONSOLIDADA_V210' "$J/RoadSafetyService.java"
grep -q 'CONTEXTO_INTELIGENTE_V209' "$J/RouteContextV7Client.java"
grep -q 'CONTEXTO_VIVO_V220' "$J/EstradaPlayApplication.java"
grep -q 'CONTEXTO_VIVO_V220' "$J/RouteContextV7BackgroundReceiver.java"
grep -q 'CONTEXTO_VIVO_V220' "$J/RouteContextV7BackgroundPolicy.java"
grep -q 'CONTEXTO_VIVO_V220' "$J/RouteContextV7Cache.java"
grep -q 'CONTEXTO_VIVO_V220' "$J/HudActivity.java"
grep -q 'android:name=".EstradaPlayApplication"' "$APP/app/src/main/AndroidManifest.xml"
grep -q 'RouteContextV7Client.fetch' "$J/RouteContextV7BackgroundReceiver.java"
grep -q 'RouteContextV7Cache.save' "$J/RouteContextV7BackgroundReceiver.java"
grep -q 'DriveSettings.offlineTestMode' "$J/RouteContextV7BackgroundReceiver.java"
grep -q 'RouteContextV7Cache.load' "$J/HudActivity.java"

# Safety remains local-first: background online context is additive and may not alter RoadSafetyService hot-path geometry.
grep -q 'RoadHazardSelector.select' "$J/RoadSafetyService.java"
grep -q 'RoadLimitPolicy' "$J/RoadSafetyService.java"
grep -q 'RoadSafetyFormat.distanceText' "$J/RoadSafetyService.java"
SERVICE_BYTES=$(wc -c < "$J/RoadSafetyService.java")
echo "RoadSafetyService bytes: $SERVICE_BYTES"
if [ "$SERVICE_BYTES" -ge 47000 ]; then
  echo 'RoadSafetyService consolidation target regressed'; exit 1
fi
if grep -q 'private Match match\|new HashMap<>\|hazardPriorityBias(' "$J/RoadSafetyService.java"; then
  echo 'RoadSafetyService contains extracted legacy policy'; exit 1
fi

# Automated JVM tests remain a release gate.
test -f "$T/RoadHazardMatcherTest.java"
test -f "$T/RoadHazardSelectorTest.java"
test -f "$T/RoadAlertCooldownTest.java"
test -f "$T/RoadLimitPolicyTest.java"
test -f "$T/RoadSafetyFormatTest.java"
test -f "$T/RouteContextV7BackgroundPolicyTest.java"
(cd "$APP" && gradle --no-daemon testUniversalDebugUnitTest)

# Preserve security/navigation/context contracts from previous milestones.
grep -q 'SECURITY_V207' "$J/SecureDeviceCredential.java"
grep -q 'NAVIGATION_REAL_V208' "$J/RouteEngine.java"
grep -q 'NAVIGATION_REAL_ACTIVITY_V208' "$J/RoadMapActivity.java"
grep -q 'CONTEXTO_INTELIGENTE_V209' "$J/RoadMapActivity.java"
grep -q 'getBoolean("context_traffic_opt_in",false)' "$J/DriveSettings.java"
if grep -R -q 'android.webkit.WebView\|new WebView' "$APP/app/src/main/java"; then
  echo 'WebView detected'; exit 1
fi

# The historical EPC generator chain must not return.
FORBIDDEN_EPC_GENERATORS=(
  tools/patch_estrada_play_v208_navigation_real.py
  tools/fix_epc_v208_navigation_strings.py
  tools/ci_build_epc_v208_navigation_real.sh
  tools/patch_estrada_play_v209_context_inteligente.py
  tools/ci_build_epc_v209_context_inteligente.sh
  tools/migrate_epc_v210_base_consolidada.py
  .github/workflows/build-estrada-play-universal-v208-navigation-real.yml
  .github/workflows/build-estrada-play-universal-v209-contexto-inteligente.yml
  .github/workflows/build-estrada-play-universal-v210-base-consolidada.yml
)
for legacy in "${FORBIDDEN_EPC_GENERATORS[@]}"; do
  if [ -e "$legacy" ]; then
    echo "Legacy EPC build path returned: $legacy"
    exit 1
  fi
done

# Only the current 2.2.0 Android workflow may remain in the EPC Android namespace.
LEGACY_EPC_WORKFLOWS="$(find .github/workflows -maxdepth 1 -type f \
  \( -name 'build-estrada-play-comunista*.yml' \
     -o -name 'build-estrada-play-portable*.yml' \
     -o -name 'build-estrada-play-universal-v*.yml' \) \
  ! -name 'build-estrada-play-universal-v220-contexto-vivo.yml' -print)"
if [ -n "$LEGACY_EPC_WORKFLOWS" ]; then
  echo 'Obsolete EPC Android workflow detected:'
  printf '%s\n' "$LEGACY_EPC_WORKFLOWS"
  exit 1
fi

# Preserve the validated embedded Portuguese voice bank in the APK.
python3 -m pip install --disable-pip-version-check sherpa-onnx soundfile numpy
MODEL=/tmp/vits-piper-pt_BR-faber-medium.tar.bz2
curl -L --fail --retry 3 --retry-delay 2 -o "$MODEL" https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-pt_BR-faber-medium.tar.bz2
echo "7add3f923ad6bc25ca8a192805fd1a64d1b3893e4611c4a9719545a825039a83  $MODEL" | sha256sum -c -
rm -rf /tmp/epc220-voice && mkdir -p /tmp/epc220-voice
tar -xjf "$MODEL" -C /tmp/epc220-voice
python3 "$APP/tools/generate_voice_bank.py" --model-root /tmp/epc220-voice --output "$APP/app/src/main/res/raw" --metadata-output "$APP/app/src/main/assets/estradaplay_voice"
test "$(find "$APP/app/src/main/res/raw" -maxdepth 1 -name 'ep_*.wav' | wc -l)" -ge 77

(cd "$APP" && gradle --no-daemon assembleUniversalDebug)
A="$APP/app/build/outputs/apk/universal/debug/app-universal-debug.apk"
test -f "$A"
"$ANDROID_HOME/build-tools/35.0.0/aapt" dump badging "$A" | tee /tmp/epc220-badging.txt
grep -q "package: name='com.estradaplay.comunista.universal' versionCode='220' versionName='2.2.0'" /tmp/epc220-badging.txt
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs "$A" | tee /tmp/epc220-cert.txt
cp "$A" "$APK_OUT"
sha256sum "$APK_OUT" | tee /tmp/epc220-sha.txt

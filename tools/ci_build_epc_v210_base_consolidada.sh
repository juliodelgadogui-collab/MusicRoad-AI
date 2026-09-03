#!/usr/bin/env bash
set -euo pipefail

APP=estrada-play-comunista-app
J="$APP/app/src/main/java/com/estradaplay/comunista"
T="$APP/app/src/test/java/com/estradaplay/comunista"
APK_OUT=Estrada-Play-Comunista-Universal-2.1.0-Base-Consolidada.apk

base64 -d "$APP/signing/estradaplay-dev.keystore.b64" > "$APP/signing/estradaplay-dev.keystore"

# Direct-source architecture contract: no generated patch is required to compile 2.1.0.
grep -q 'versionCode 210' "$APP/app/build.gradle"
grep -q "versionName '2.1.0'" "$APP/app/build.gradle"
grep -q "testImplementation 'junit:junit:4.13.2'" "$APP/app/build.gradle"
grep -q 'BASE_CONSOLIDADA_V210' "$J/RoadSafetyService.java"
grep -q 'RoadHazardSelector.select' "$J/RoadSafetyService.java"
grep -q 'RoadLimitPolicy' "$J/RoadSafetyService.java"
grep -q 'RoadSafetyFormat.distanceText' "$J/RoadSafetyService.java"
grep -q 'BASE_CONSOLIDADA_V210' "$J/RoadHazardMatcher.java"
grep -q 'BASE_CONSOLIDADA_V210' "$J/RoadAlertCooldown.java"
grep -q 'BASE_CONSOLIDADA_V210' "$J/RoadHazardSelector.java"
grep -q 'BASE_CONSOLIDADA_V210' "$J/RoadLimitPolicy.java"
grep -q 'BASE_CONSOLIDADA_V210' "$J/RoadSafetyFormat.java"

# Prevent the coordinator from silently growing back into the old geometry/state monolith.
if grep -q 'private Match match\|new HashMap<>\|hazardPriorityBias(' "$J/RoadSafetyService.java"; then
  echo 'RoadSafetyService still contains extracted legacy policy'; exit 1
fi
SERVICE_BYTES=$(wc -c < "$J/RoadSafetyService.java")
echo "RoadSafetyService bytes: $SERVICE_BYTES"
if [ "$SERVICE_BYTES" -ge 47000 ]; then
  echo 'RoadSafetyService consolidation target not reached'; exit 1
fi

# Automated JVM tests are a release gate from 2.1.0 onward.
test -f "$T/RoadHazardMatcherTest.java"
test -f "$T/RoadHazardSelectorTest.java"
test -f "$T/RoadAlertCooldownTest.java"
test -f "$T/RoadLimitPolicyTest.java"
test -f "$T/RoadSafetyFormatTest.java"
(cd "$APP" && gradle --no-daemon testUniversalDebugUnitTest)

# Preserve security/navigation/context contracts from the previous milestones.
grep -q 'SECURITY_V207' "$J/SecureDeviceCredential.java"
grep -q 'NAVIGATION_REAL_V208' "$J/RouteEngine.java"
grep -q 'NAVIGATION_REAL_ACTIVITY_V208' "$J/RoadMapActivity.java"
grep -q 'CONTEXTO_INTELIGENTE_V209' "$J/RouteContextV7Client.java"
grep -q 'CONTEXTO_INTELIGENTE_V209' "$J/RoadMapActivity.java"
grep -q 'getBoolean("context_traffic_opt_in",false)' "$J/DriveSettings.java"
if grep -R -q 'android.webkit.WebView\|new WebView' "$APP/app/src/main/java"; then
  echo 'WebView detected'; exit 1
fi

# Preserve the validated embedded Portuguese voice bank in the APK.
python3 -m pip install --disable-pip-version-check sherpa-onnx soundfile numpy
MODEL=/tmp/vits-piper-pt_BR-faber-medium.tar.bz2
curl -L --fail --retry 3 --retry-delay 2 -o "$MODEL" https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-pt_BR-faber-medium.tar.bz2
echo "7add3f923ad6bc25ca8a192805fd1a64d1b3893e4611c4a9719545a825039a83  $MODEL" | sha256sum -c -
rm -rf /tmp/epc210-voice && mkdir -p /tmp/epc210-voice
tar -xjf "$MODEL" -C /tmp/epc210-voice
python3 "$APP/tools/generate_voice_bank.py" --model-root /tmp/epc210-voice --output "$APP/app/src/main/res/raw" --metadata-output "$APP/app/src/main/assets/estradaplay_voice"
test "$(find "$APP/app/src/main/res/raw" -maxdepth 1 -name 'ep_*.wav' | wc -l)" -ge 77

(cd "$APP" && gradle --no-daemon assembleUniversalDebug)
A="$APP/app/build/outputs/apk/universal/debug/app-universal-debug.apk"
test -f "$A"
"$ANDROID_HOME/build-tools/35.0.0/aapt" dump badging "$A" | tee /tmp/epc210-badging.txt
grep -q "package: name='com.estradaplay.comunista.universal' versionCode='210' versionName='2.1.0'" /tmp/epc210-badging.txt
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs "$A" | tee /tmp/epc210-cert.txt
cp "$A" "$APK_OUT"
sha256sum "$APK_OUT" | tee /tmp/epc210-sha.txt

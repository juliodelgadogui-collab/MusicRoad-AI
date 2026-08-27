#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "estradaplay-app-v2"
SERVICE = APP / "app/src/main/java/com/estradaplay/app/RoadSafetyService.java"
GRADLE = APP / "app/build.gradle"
WORKFLOW = ROOT / ".github/workflows/build-estradaplay-v2.yml"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"anchor missing: {label}")
    if text.count(old) != 1:
        raise SystemExit(f"anchor not unique: {label} ({text.count(old)})")
    return text.replace(old, new, 1)


# Version 2.0.4
g = GRADLE.read_text(encoding="utf-8")
g = replace_once(g, "versionCode 203", "versionCode 204", "versionCode")
g = replace_once(g, "versionName '2.0.3'", "versionName '2.0.4'", "versionName")
GRADLE.write_text(g, encoding="utf-8")

# Activate the embedded voice bank in the definitive service source.
s = SERVICE.read_text(encoding="utf-8")
s = replace_once(
    s,
    "    private TextToSpeech tts;\n    private boolean ttsReady;",
    "    private TextToSpeech tts;\n    // OFFLINE_VOICE_V204: primary deterministic voice; Android TTS is fallback only.\n    private EstradaPlayOfflineVoice offlineVoice;\n    private boolean ttsReady;",
    "offline voice field",
)
s = replace_once(
    s,
    "        api = new ApiClient(this);\n        locationManager = (LocationManager)getSystemService(LOCATION_SERVICE);",
    "        api = new ApiClient(this);\n        offlineVoice = new EstradaPlayOfflineVoice(this);\n        locationManager = (LocationManager)getSystemService(LOCATION_SERVICE);",
    "offline voice init",
)
s = replace_once(
    s,
    "            speak(voice(best, bestForward));",
    "            speakHazardVoice(best, bestForward);",
    "hazard voice",
)
s = replace_once(
    s,
    "        if (limitKmh != announcedRoadLimitKmh && ttsReady) {\n            announcedRoadLimitKmh = limitKmh;\n            speak(\"Limite da via, \" + limitKmh + \" quilômetros por hora.\");\n        }",
    "        if (limitKmh != announcedRoadLimitKmh && speakRoadLimitVoice(limitKmh)) {\n            announcedRoadLimitKmh = limitKmh;\n        }",
    "road limit voice",
)
s = replace_once(
    s,
    "        if (!roadOverspeedWarned && speedKmh >= limit + 2.0 && ttsReady) {\n            roadOverspeedWarned = true;\n            speak(\"Atenção. Você passou do limite da via. Limite de \" + limit + \" quilômetros por hora.\");\n        }",
    "        if (!roadOverspeedWarned && speedKmh >= limit + 2.0 && speakOverspeedVoice(limit)) {\n            roadOverspeedWarned = true;\n        }",
    "overspeed voice",
)

anchor = "    private void speak(String text) {\n"
helpers = r'''    private void prepareEmbeddedVoice() {
        try { if (tts != null) tts.stop(); } catch (Throwable ignored) {}
        duckOwnPlayer(true);
        requestVoiceFocus();
        main.removeCallbacks(restoreAudioFallback);
        main.postDelayed(restoreAudioFallback, 8000L);
    }

    private boolean speakRoadLimitVoice(int limitKmh) {
        if (offlineVoice != null) {
            prepareEmbeddedVoice();
            if (offlineVoice.playRoadLimit(limitKmh, this::restoreAudioAfterVoice)) return true;
            restoreAudioAfterVoice();
        }
        if (ttsReady) {
            speak("Limite da via, " + limitKmh + " quilômetros por hora.");
            return true;
        }
        return false;
    }

    private boolean speakOverspeedVoice(int limitKmh) {
        if (offlineVoice != null) {
            prepareEmbeddedVoice();
            if (offlineVoice.playOverspeed(limitKmh, this::restoreAudioAfterVoice)) return true;
            restoreAudioAfterVoice();
        }
        if (ttsReady) {
            speak("Atenção. Você passou do limite da via. Limite de " + limitKmh + " quilômetros por hora.");
            return true;
        }
        return false;
    }

    private void speakHazardVoice(RoadHazard h, double forwardM) {
        if (offlineVoice != null && h != null) {
            prepareEmbeddedVoice();
            if (offlineVoice.playHazard(h.type, forwardM, h.speed, this::restoreAudioAfterVoice)) return;
            restoreAudioAfterVoice();
        }
        speak(voice(h, forwardM));
    }

'''
if helpers.strip() in s:
    raise SystemExit("helpers already installed")
s = replace_once(s, anchor, helpers + anchor, "speak helper insertion")
s = replace_once(
    s,
    "    private void speak(String text) {\n        if (!ttsReady || tts == null || text == null || text.trim().isEmpty()) return;\n        duckOwnPlayer(true);",
    "    private void speak(String text) {\n        if (!ttsReady || tts == null || text == null || text.trim().isEmpty()) return;\n        if (offlineVoice != null) offlineVoice.stop();\n        duckOwnPlayer(true);",
    "tts fallback stop embedded",
)
s = replace_once(
    s,
    "        try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Throwable ignored) {}\n        io.shutdownNow();",
    "        try { if (offlineVoice != null) offlineVoice.release(); } catch (Throwable ignored) {}\n        try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Throwable ignored) {}\n        io.shutdownNow();",
    "release embedded voice",
)
SERVICE.write_text(s, encoding="utf-8")

# Make the direct build synthesize the same pt-BR voice bank on every build.
w = WORKFLOW.read_text(encoding="utf-8")
voice_step = r'''      - name: Generate embedded EstradaPlay voice
        shell: bash
        run: |
          set -euo pipefail
          python3 -m pip install --disable-pip-version-check sherpa-onnx soundfile numpy
          MODEL=/tmp/vits-piper-pt_BR-faber-medium.tar.bz2
          curl -L --fail --retry 3 --retry-delay 2 \
            -o "$MODEL" \
            https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-pt_BR-faber-medium.tar.bz2
          echo "439e3a5f55a43ed70f918492acf5729dd35dde2d61a6c7956f04a8eee1f2c51f  $MODEL" | sha256sum -c -
          rm -rf /tmp/estradaplay-voice
          mkdir -p /tmp/estradaplay-voice
          tar -xjf "$MODEL" -C /tmp/estradaplay-voice
          python3 estradaplay-app-v2/tools/generate_voice_bank.py \
            --model-root /tmp/estradaplay-voice \
            --output estradaplay-app-v2/app/src/main/res/raw \
            --metadata-output estradaplay-app-v2/app/src/main/assets/estradaplay_voice
          test "$(find estradaplay-app-v2/app/src/main/res/raw -maxdepth 1 -name 'ep_*.wav' | wc -l)" -ge 70

'''
w = replace_once(
    w,
    "      - name: Verify source architecture\n",
    voice_step + "      - name: Verify source architecture\n",
    "voice generation workflow step",
)
w = replace_once(
    w,
    "          grep -q 'OSM_DIRECT_MAP_V200' estradaplay-app-v2/app/src/main/java/com/estradaplay/app/OfflineRoadStore.java\n",
    "          grep -q 'OSM_DIRECT_MAP_V200' estradaplay-app-v2/app/src/main/java/com/estradaplay/app/OfflineRoadStore.java\n          grep -q 'ANR_GUARD_V201' estradaplay-app-v2/app/src/main/java/com/estradaplay/app/RoadSafetyService.java\n          grep -q 'ROAD_LIMIT_V202' estradaplay-app-v2/app/src/main/java/com/estradaplay/app/RoadSafetyService.java\n          grep -q 'OFFLINE_VOICE_V204' estradaplay-app-v2/app/src/main/java/com/estradaplay/app/EstradaPlayOfflineVoice.java\n          grep -q 'OFFLINE_VOICE_V204' estradaplay-app-v2/app/src/main/java/com/estradaplay/app/RoadSafetyService.java\n          grep -q 'OFFLINE_VOICE_V204' estradaplay-app-v2/tools/generate_voice_bank.py\n          grep -q \"versionName '2.0.4'\" estradaplay-app-v2/app/build.gradle\n          test -s estradaplay-app-v2/app/src/main/res/raw/ep_radar_frente.wav\n          test -s estradaplay-app-v2/app/src/main/res/raw/ep_limite_via.wav\n          test -s estradaplay-app-v2/app/src/main/res/raw/ep_speed_060.wav\n",
    "2.0.4 verification",
)
w = replace_once(
    w,
    "          if [ \"$VERSION\" = \"2.0.1\" ]; then\n            NOTES=\"Hotfix do ANR visto no Android: remove leitura/parse de GeoJSON grande do caminho principal do GPS, reduz trabalho repetitivo do serviço e limita o mapa OSM de emergência para centrais mais fracas. Mantém toque, velocidade, radares, música e suporte horizontal/vertical da 2.0.\"\n          fi\n",
    "          if [ \"$VERSION\" = \"2.0.1\" ]; then\n            NOTES=\"Hotfix do ANR visto no Android: remove leitura/parse de GeoJSON grande do caminho principal do GPS, reduz trabalho repetitivo do serviço e limita o mapa OSM de emergência para centrais mais fracas. Mantém toque, velocidade, radares, música e suporte horizontal/vertical da 2.0.\"\n          fi\n          if [ \"$VERSION\" = \"2.0.4\" ]; then\n            NOTES=\"Versão de teste com voz EstradaPlay embarcada e offline: o banco pt-BR é sintetizado no build e os alertas reproduzem os mesmos áudios em qualquer central. Android TTS fica apenas como fallback para valores não previstos. Mantém limite da via, limite do radar, aviso único de excesso, correção de ANR, toque e horizontal/vertical.\"\n          fi\n",
    "2.0.4 release notes",
)
WORKFLOW.write_text(w, encoding="utf-8")

print("EstradaPlay 2.0.4 offline voice activated")

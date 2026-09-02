#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "estrada-play-comunista-app"
JAVA = APP / "app/src/main/java/com/estradaplay/comunista/EstradaPlayOfflineVoice.java"
GRADLE = APP / "app/build.gradle"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f"Marcador nao encontrado para {label}")
    return text.replace(old, new, 1)


g = GRADLE.read_text(encoding="utf-8")
g = replace_once(g, "versionCode 191", "versionCode 192", "versionCode")
g = replace_once(g, "versionName '1.9.1'", "versionName '1.9.2'", "versionName")
GRADLE.write_text(g, encoding="utf-8")

j = JAVA.read_text(encoding="utf-8")
j = replace_once(
    j,
    "    private boolean focusHeld;\n",
    "    private boolean focusHeld;\n"
    "    // VOICE_FULL_ALERT_V192: automotive ROMs can swallow the first short clip while\n"
    "    // audio focus/ducking is still changing. Prime focus before speech and leave a\n"
    "    // small gap between clips so the whole sentence is audible, not only distance.\n"
    "    private static final long FIRST_CLIP_PREROLL_MS = 240L;\n"
    "    private static final long BETWEEN_CLIPS_MS = 65L;\n",
    "voice timing constants",
)

j = replace_once(
    j,
    "        started = onStarted;\n"
    "        finished = onFinished;\n"
    "        startNotified = false;\n"
    "        playNext(token);\n",
    "        started = onStarted;\n"
    "        finished = onFinished;\n"
    "        startNotified = false;\n"
    "        // Acquire navigation focus and duck the app/player BEFORE the first word.\n"
    "        // Calling the service callback after playback starts made head units miss\n"
    "        // phrases such as 'Radar a frente' and only reproduce '500 metros'.\n"
    "        requestLocalFocus();\n"
    "        startNotified = true;\n"
    "        Runnable begin = started;\n"
    "        started = null;\n"
    "        if (begin != null) begin.run();\n"
    "        main.postDelayed(() -> playNext(token), FIRST_CLIP_PREROLL_MS);\n",
    "sequence preroll",
)

j = replace_once(
    j,
    "                playNext(token);\n",
    "                main.postDelayed(() -> playNext(token), BETWEEN_CLIPS_MS);\n",
    "inter clip gap",
)

j = replace_once(
    j,
    "            if (!startNotified) requestLocalFocus();\n"
    "            mp.setVolume(1f, 1f);\n"
    "            mp.start();\n"
    "            boolean audibleStart = false;\n"
    "            try { audibleStart = mp.isPlaying(); } catch (Throwable ignored) {}\n"
    "            if (!audibleStart) { failSequence(token); return; }\n"
    "            if (!startNotified) {\n"
    "                startNotified = true;\n"
    "                Runnable begin = started;\n"
    "                started = null;\n"
    "                if (begin != null) begin.run();\n"
    "            }\n",
    "            mp.setVolume(1f, 1f);\n"
    "            mp.start();\n"
    "            boolean audibleStart = false;\n"
    "            try { audibleStart = mp.isPlaying(); } catch (Throwable ignored) {}\n"
    "            if (!audibleStart) { failSequence(token); return; }\n",
    "remove late focus callback",
)

if "VOICE_FULL_ALERT_V192" not in j or "FIRST_CLIP_PREROLL_MS" not in j:
    raise SystemExit("Patch de voz 1.9.2 incompleto")
JAVA.write_text(j, encoding="utf-8")
print("VOICE_FULL_ALERT_V192 aplicado")

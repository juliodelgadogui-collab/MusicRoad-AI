from pathlib import Path

root = Path('estradaplay-app-v2')
gradle = root / 'app/build.gradle'
service = root / 'app/src/main/java/com/estradaplay/app/RoadSafetyService.java'

g = gradle.read_text(encoding='utf-8')
g = g.replace("versionCode 202", "versionCode 203")
g = g.replace("versionName '2.0.2'", "versionName '2.0.3'")
gradle.write_text(g, encoding='utf-8')

s = service.read_text(encoding='utf-8')

# Add Voice import if needed.
if 'import android.speech.tts.Voice;' not in s:
    s = s.replace('import android.speech.tts.UtteranceProgressListener;\n', 'import android.speech.tts.UtteranceProgressListener;\nimport android.speech.tts.Voice;\n')
if 'import java.util.ArrayList;' not in s:
    s = s.replace('import java.util.HashMap;\n', 'import java.util.ArrayList;\nimport java.util.Comparator;\nimport java.util.HashMap;\n')

# Replace the old neutral TTS profile with an EstradaPlay automotive profile.
old = '''                    tts.setLanguage(new Locale("pt", "BR"));\n                    tts.setSpeechRate(1.0f);\n                    tts.setPitch(1.0f);'''
new = '''                    tts.setLanguage(new Locale("pt", "BR"));\n                    // ESTRADAPLAY_VOICE_V203: branded automotive profile.\n                    // Slightly lower pitch + calmer pace makes road warnings firm and distinct.\n                    tts.setSpeechRate(0.93f);\n                    tts.setPitch(0.88f);\n                    selectEstradaPlayVoice();'''
if old not in s:
    raise SystemExit('TTS profile anchor not found')
s = s.replace(old, new, 1)

# Insert deterministic pt-BR voice selection before speak().
anchor = '    private void speak(String text) {\n'
method = '''    private void selectEstradaPlayVoice() {\n        if (tts == null || Build.VERSION.SDK_INT < 21) return;\n        try {\n            java.util.Set<Voice> voices = tts.getVoices();\n            if (voices == null || voices.isEmpty()) return;\n            ArrayList<Voice> pt = new ArrayList<>();\n            for (Voice v : voices) {\n                if (v == null || v.getLocale() == null) continue;\n                String lang = v.getLocale().getLanguage();\n                String country = v.getLocale().getCountry();\n                if (!"pt".equalsIgnoreCase(lang)) continue;\n                if (!country.isEmpty() && !"BR".equalsIgnoreCase(country)) continue;\n                pt.add(v);\n            }\n            if (pt.isEmpty()) return;\n            pt.sort(Comparator\n                    .comparing((Voice v) -> v.isNetworkConnectionRequired())\n                    .thenComparing((Voice v) -> -v.getQuality())\n                    .thenComparing(Voice::getName));\n            tts.setVoice(pt.get(0));\n        } catch (Throwable ignored) {}\n    }\n\n'''
if method not in s:
    if anchor not in s:
        raise SystemExit('speak anchor not found')
    s = s.replace(anchor, method + anchor, 1)

# Shorter, punchier wording with natural pauses.
s = s.replace('return "Atenção. Semáforo à frente, a " + distance + ".";', 'return "Atenção. Semáforo à frente. " + distance + ".";')
s = s.replace('return "Reduza. Quebra-molas à frente, a " + distance + ".";', 'return "Reduza. Quebra-molas à frente. " + distance + ".";')
s = s.replace('return "Pedágio à frente, a " + distance + ". Prepare-se para a praça de pedágio.";', 'return "Pedágio à frente. " + distance + ".";')
s = s.replace('return "Atenção. Passagem de nível à frente, a " + distance + ". Reduza a velocidade e observe a sinalização.";', 'return "Atenção. Passagem de nível à frente. " + distance + ". Reduza.";')
s = s.replace('if (h.speed > 0) return "Radar à frente, a " + distance + ". Limite de " + h.speed + " quilômetros por hora.";', 'if (h.speed > 0) return "Radar à frente. " + distance + ". Limite do radar, " + h.speed + " quilômetros por hora.";')
s = s.replace('return "Radar à frente, a " + distance + ".";', 'return "Radar à frente. " + distance + ".";')

service.write_text(s, encoding='utf-8')
print('EstradaPlay 2.0.3 branded voice applied')

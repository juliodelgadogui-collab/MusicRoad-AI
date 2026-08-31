#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "estrada-play-comunista-app"
JAVA = APP / "app/src/main/java/com/estradaplay/comunista"


def read(path):
    return path.read_text(encoding="utf-8")


def write(path, text):
    path.write_text(text, encoding="utf-8")


def replace_once(text, old, new, label):
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f"Trecho não encontrado: {label}")
    return text.replace(old, new, 1)


# ---------------------------------------------------------------------------
# 1.5.3 UNIVERSAL: one APK, real portrait + landscape layouts.
# Keep the previous vertical/horizontal flavors available for regression tests.
# ---------------------------------------------------------------------------
gradle = APP / "app/build.gradle"
s = read(gradle)
s = re.sub(r"versionCode\s+152\b", "versionCode 153", s, count=1)
s = re.sub(r"versionName\s+'1\.5\.2'", "versionName '1.5.3'", s, count=1)

if "universal {" not in s:
    marker = "    productFlavors {\n"
    universal = '''    productFlavors {\n        universal {\n            dimension 'orientation'\n            applicationIdSuffix '.universal'\n            manifestPlaceholders = [\n                appLabel: 'Estrada Play Comunista',\n                appOrientation: 'fullSensor'\n            ]\n            buildConfigField 'String', 'FIXED_LAYOUT', '\"universal\"'\n        }\n'''
    if marker not in s:
        raise SystemExit("productFlavors não encontrado")
    s = s.replace(marker, universal, 1)
write(gradle, s)


# Adaptive cockpit -----------------------------------------------------------
road_map = JAVA / "RoadMapActivity.java"
s = read(road_map)
s = replace_once(
    s,
    '        if ("vertical".equals(BuildConfig.FIXED_LAYOUT)) {\n            buildPortraitUi(width, height);\n        } else {\n            buildLandscapeUi(width, height);\n        }\n',
    '        if (usePortraitLayout()) {\n            buildPortraitUi(width, height);\n        } else {\n            buildLandscapeUi(width, height);\n        }\n',
    "RoadMapActivity layout selection",
)

if "UNIVERSAL_ORIENTATION_V153" not in s:
    marker = "    private int[] screenSize() {\n"
    helper = '''    // UNIVERSAL_ORIENTATION_V153: fixed flavors retain their old behavior,\n    // while the Universal flavor follows the real screen shape on every rotation.\n    private boolean usePortraitLayout() {\n        String mode = BuildConfig.FIXED_LAYOUT == null ? "" : BuildConfig.FIXED_LAYOUT;\n        if ("vertical".equals(mode)) return true;\n        if ("horizontal".equals(mode)) return false;\n        int[] size = screenSize();\n        return size[1] >= size[0];\n    }\n\n'''
    if marker not in s:
        raise SystemExit("RoadMapActivity screenSize não encontrado")
    s = s.replace(marker, helper + marker, 1)
write(road_map, s)


# The old AutomotiveActivity is landscape-only. In Universal, always use the
# newer responsive RoadMapActivity if a legacy path tries to open it.
automotive = JAVA / "AutomotiveActivity.java"
s = read(automotive)
if "UNIVERSAL_ORIENTATION_V153_REDIRECT" not in s:
    old = '''    @Override protected void onCreate(Bundle state) {\n        super.onCreate(state);\n        enterImmersive();\n'''
    new = '''    @Override protected void onCreate(Bundle state) {\n        super.onCreate(state);\n        // UNIVERSAL_ORIENTATION_V153_REDIRECT: legacy automotive shell was\n        // landscape-only. Universal always uses the responsive cockpit.\n        if ("universal".equals(BuildConfig.FIXED_LAYOUT)) {\n            startActivity(new Intent(this, RoadMapActivity.class));\n            finish();\n            return;\n        }\n        enterImmersive();\n'''
    s = replace_once(s, old, new, "AutomotiveActivity universal redirect")
write(automotive, s)


# A small marker used by CI and diagnostics. No new background work is added.
marker_file = APP / "UNIVERSAL-1.5.3.md"
write(marker_file, '''# Estrada Play Comunista 1.5.3 — Universal\n\n- Um único APK para portrait + landscape.\n- Flavor: `universal`.\n- Package: `com.estradaplay.comunista.universal`.\n- Orientation: `fullSensor`.\n- O cockpit `RoadMapActivity` escolhe `buildPortraitUi` ou `buildLandscapeUi` pela dimensão atual da tela.\n- Ao girar, `onConfigurationChanged` reconstrói somente a interface responsiva; serviços de estrada e voz continuam preservados.\n- A versão mantém as correções de estabilidade 1.5.2: serviços não sticky, encerramento com a tarefa, voz embarcada única por padrão, contagem de perigos em cache e I/O pesado fora da UI.\n- Vertical e Horizontal antigos permanecem disponíveis como builds separados; a Universal usa package próprio para coexistir durante testes.\n''')

print("Estrada Play Comunista 1.5.3 Universal patch aplicado.")

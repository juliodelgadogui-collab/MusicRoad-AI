from pathlib import Path

ROOT = Path('estrada-play-comunista-app')
APP = ROOT / 'app'


def read(path):
    return Path(path).read_text(encoding='utf-8')


def write(path, content):
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content, encoding='utf-8')


def must_replace(text, old, new, label):
    if old not in text:
        raise SystemExit(f'orientation split: missing target: {label}')
    return text.replace(old, new, 1)

# 1) Two installable products from the same source tree.
gradle_path = APP / 'build.gradle'
gradle = read(gradle_path)
gradle = must_replace(gradle, "versionCode 120\n        versionName '1.2.0'", "versionCode 121\n        versionName '1.2.1'", 'version 1.2.1')
gradle = must_replace(
    gradle,
    "    buildFeatures { buildConfig true }",
    """    buildFeatures { buildConfig true }

    flavorDimensions += 'orientation'
    productFlavors {
        vertical {
            dimension 'orientation'
            applicationIdSuffix '.vertical'
            manifestPlaceholders = [
                appLabel: 'Estrada Play Comunista Vertical',
                appOrientation: 'sensorPortrait'
            ]
            buildConfigField 'String', 'FIXED_LAYOUT', '\"vertical\"'
        }
        horizontal {
            dimension 'orientation'
            applicationIdSuffix '.horizontal'
            manifestPlaceholders = [
                appLabel: 'Estrada Play Comunista Horizontal',
                appOrientation: 'sensorLandscape'
            ]
            buildConfigField 'String', 'FIXED_LAYOUT', '\"horizontal\"'
        }
    }""",
    'product flavors'
)
write(gradle_path, gradle)

# 2) Lock every screen to the selected family. resizeableActivity=false avoids
# multi-window/desktop resizing from silently changing the cockpit geometry.
manifest_path = APP / 'src/main/AndroidManifest.xml'
manifest = read(manifest_path)
manifest = manifest.replace('android:resizeableActivity="true"', 'android:resizeableActivity="false"')
manifest = manifest.replace('android:label="Estrada Play Comunista"', 'android:label="${appLabel}"')
count = manifest.count('android:screenOrientation="fullSensor"')
if count < 5:
    raise SystemExit(f'orientation split: expected >=5 fullSensor activities, found {count}')
manifest = manifest.replace('android:screenOrientation="fullSensor"', 'android:screenOrientation="${appOrientation}"')
write(manifest_path, manifest)

# 3) The map cockpit itself no longer chooses layout by current width/height.
# Each APK calls exactly one renderer, so stale dimensions after an OEM resize
# can never switch from landscape UI to portrait UI (or vice versa).
road_path = APP / 'src/main/java/com/estradaplay/comunista/RoadMapActivity.java'
road = read(road_path)
old = """        if (height > width) buildPortraitUi(width, height);
        else buildLandscapeUi(width, height);"""
new = """        if (\"vertical\".equals(BuildConfig.FIXED_LAYOUT)) {
            buildPortraitUi(width, height);
        } else {
            buildLandscapeUi(width, height);
        }"""
road = must_replace(road, old, new, 'fixed RoadMapActivity renderer')
write(road_path, road)

# 4) A tiny runtime identity helper. It is used by validation and makes the
# selected family explicit for future UI tuning without reading orientation.
identity_path = APP / 'src/main/java/com/estradaplay/comunista/OrientationEdition.java'
write(identity_path, '''package com.estradaplay.comunista;\n\nfinal class OrientationEdition {\n    private OrientationEdition() {}\n\n    static boolean vertical() {\n        return "vertical".equals(BuildConfig.FIXED_LAYOUT);\n    }\n\n    static boolean horizontal() {\n        return "horizontal".equals(BuildConfig.FIXED_LAYOUT);\n    }\n}\n''')

print('Estrada Play Comunista 1.2.1: vertical + horizontal fixed variants ready')

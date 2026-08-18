from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/com/musicroad/ai'


def replace_once(path: Path, old: str, new: str, label: str):
    text = path.read_text()
    if old not in text:
        raise SystemExit(f'v4.6.2 radar voice patch failed: {label} not found in {path.name}')
    path.write_text(text.replace(old, new, 1))


def patch_navigation():
    p = JAVA / 'NavigationService.java'

    replace_once(
        p,
        '        String voice(int meters){String base="Atenção. "+label()+" em "+meters+" metros.";if(isSpeedEnforcement()&&speed>0)base+=" Limite de "+speed+" quilômetros por hora.";return base;}',
        '        String voice(int meters){if(isSpeedEnforcement()&&speed>0)return label()+" de "+speed+" quilômetros por hora em "+meters+" metros.";return "Atenção. "+label()+" em "+meters+" metros.";}',
        'speed enforcement distance voice'
    )

    replace_once(
        p,
        '        String frontVoice(){String base="Atenção. "+label()+" na sua frente.";if(isSpeedEnforcement()&&speed>0)base+=" Limite de "+speed+" quilômetros por hora.";return base;}',
        '        String frontVoice(){if(isSpeedEnforcement()&&speed>0)return label()+" de "+speed+" quilômetros por hora na sua frente.";return "Atenção. "+label()+" na sua frente.";}',
        'speed enforcement front voice'
    )


def patch_version():
    p = ROOT / 'app/build.gradle'
    text = p.read_text()
    text = re.sub(r'versionCode\s+\d+', 'versionCode 19', text, count=1)
    text = re.sub(r"versionName\s+'[^']+'", "versionName '4.6.2'", text, count=1)
    p.write_text(text)


patch_navigation()
patch_version()
print('MusicRoad v4.6.2 radar speed voice patch applied')

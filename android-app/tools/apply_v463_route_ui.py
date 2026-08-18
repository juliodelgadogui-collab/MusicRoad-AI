from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/com/musicroad/ai'


def replace_once(path: Path, old: str, new: str, label: str):
    text = path.read_text()
    if old not in text:
        raise SystemExit(f'v4.6.3 route UI patch failed: {label} not found in {path.name}')
    path.write_text(text.replace(old, new, 1))


def patch_navigation():
    p = JAVA / 'NavigationService.java'
    replace_once(
        p,
        '            double along=h.routeM-progress;if(along<-55||along>5000)continue;if(h.routeDistanceM>360)continue;',
        '            double along=h.routeM-progress;if(along<-12||along>5000)continue;if(along<10&&warnedNow.contains(h.key))continue;if(h.routeDistanceM>360)continue;',
        'skip passed hazards'
    )
    replace_once(
        p,
        '            status=label+" · "+formatDistance(Math.max(0,next.routeM-progress));',
        '            double shownDistance=Math.max(0,next.routeM-progress);status=label+" · "+(shownDistance<=25?"agora":formatDistance(shownDistance));',
        'avoid zero metre status'
    )


def patch_version():
    p = ROOT / 'app/build.gradle'
    text = p.read_text()
    text = re.sub(r'versionCode\s+\d+', 'versionCode 20', text, count=1)
    text = re.sub(r"versionName\s+'[^']+'", "versionName '4.6.3'", text, count=1)
    p.write_text(text)


patch_navigation()
patch_version()
print('MusicRoad v4.6.3 passed-hazard patch applied')

from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/com/musicroad/ai'


def replace_once(path: Path, old: str, new: str, label: str):
    text = path.read_text()
    if old not in text:
        raise SystemExit(f'v4.6.4 bump sequence patch failed: {label} not found in {path.name}')
    path.write_text(text.replace(old, new, 1))


def patch_navigation():
    p = JAVA / 'NavigationService.java'

    # Only nearby bumps on the same route segment should form one sequence.
    replace_once(
        p,
        '    private static final double BUMP_SEQUENCE_GAP_M=170.0;',
        '    private static final double BUMP_SEQUENCE_GAP_M=110.0;',
        'sequence gap'
    )

    # Ensure the sequence is evaluated in route order even if the API payload is not ordered.
    replace_once(
        p,
        '        for(Hazard h:hazards)if("bump".equals(h.kind())&&h.routeDistanceM<=360)bumps.add(h);\n        int center=-1;',
        '        for(Hazard h:hazards)if("bump".equals(h.kind())&&h.routeDistanceM<=360)bumps.add(h);\n        bumps.sort((a,b)->Double.compare(a.routeM,b.routeM));\n        int center=-1;',
        'sort bumps by route progress'
    )

    # In a sequence, do not announce a fake fixed "50 m" for every bump.
    # The live notification already shows the real remaining distance and the front alert says "agora".
    fixed_50_block = '''        if(milestoneEnabled(50)&&distance<=50&&distance>22){
            String nearKey=h.key+"@bump50";long now=System.currentTimeMillis();
            if(!warnedBumpNear.contains(nearKey)&&now-lastHazardVoiceAt>=1200){
                warnedBumpNear.add(nearKey);showTransient("QUEBRA-MOLA "+(idx+1)+" DE "+total,"50 m · sequência de "+total,4000);
                speak("Quebra-mola "+(idx+1)+" de "+total+" em 50 metros.");lastHazardVoiceAt=now;
            }
        }
'''
    replace_once(p, fixed_50_block, '', 'remove fixed 50m sequence alert')

    # The v4.6.3 logic keeps the current hazard until it is very close. For bumps already
    # announced as "now", release it immediately after crossing its route position so the
    # next bump in a 20-50 m sequence can become active without delay.
    replace_once(
        p,
        '            double along=h.routeM-progress;if(along<-12||along>5000)continue;if(along<10&&warnedNow.contains(h.key))continue;if(h.routeDistanceM>360)continue;',
        '            double along=h.routeM-progress;if(along<-12||along>5000)continue;if("bump".equals(h.kind())&&along<=0&&warnedNow.contains(h.key))continue;if(!"bump".equals(h.kind())&&along<10&&warnedNow.contains(h.key))continue;if(h.routeDistanceM>360)continue;',
        'release passed bump immediately'
    )


def patch_version():
    p = ROOT / 'app/build.gradle'
    text = p.read_text()
    text = re.sub(r'versionCode\s+\d+', 'versionCode 21', text, count=1)
    text = re.sub(r"versionName\s+'[^']+'", "versionName '4.6.4'", text, count=1)
    p.write_text(text)


patch_navigation()
patch_version()
print('MusicRoad v4.6.4 native bump-sequence patch applied')

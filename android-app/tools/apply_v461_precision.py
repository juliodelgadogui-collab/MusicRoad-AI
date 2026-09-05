from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/com/musicroad/ai'


def replace_once(path: Path, old: str, new: str, label: str):
    text = path.read_text()
    if old not in text:
        raise SystemExit(f'v4.6.1 precision patch failed: {label} not found in {path.name}')
    path.write_text(text.replace(old, new, 1))


def patch_navigation():
    p = JAVA / 'NavigationService.java'

    replace_once(
        p,
        '    private long lastHazardVoiceAt=0;\n    private boolean suspendedOffRoute=false;',
        '    private long lastHazardVoiceAt=0;\n    private double lastAlertLeadM=0;\n    private boolean suspendedOffRoute=false;',
        'lead field'
    )

    replace_once(
        p,
        '        double progress=lastProgressM;\n        int currentLimit=currentSpeedLimit(progress);',
        '        double progress=lastProgressM;\n        lastAlertLeadM=predictiveLeadMeters(loc);\n        int currentLimit=currentSpeedLimit(progress);',
        'lead update'
    )

    replace_once(
        p,
        '    private int milestoneFor(double distance){if(distance<=50)return 50;if(distance<=100)return 100;if(distance<=200)return 200;if(distance<=300)return 300;return 0;}',
        '''    private double predictiveLeadMeters(Location loc){\n        if(loc==null)return 0;\n        double speed=loc.hasSpeed()?Math.max(0.0,loc.getSpeed()):0.0;\n        double accuracy=loc.hasAccuracy()?Math.max(0.0,loc.getAccuracy()):0.0;\n        double ageSec=Math.max(0.0,Math.min(3.0,(System.currentTimeMillis()-loc.getTime())/1000.0));\n        if(speed<1.2)return Math.min(8.0,accuracy*0.18);\n        double lead=speed*(1.8+ageSec)+Math.min(16.0,accuracy*0.45);\n        return Math.max(10.0,Math.min(55.0,lead));\n    }\n    private double predictedHazardDistance(Hazard h,double progress){return Math.max(0,h.routeM-(progress+lastAlertLeadM));}\n    private double rawHazardDistance(Hazard h,double progress){return Math.max(0,h.routeM-progress);}\n    private double frontThresholdM(){return 22.0+Math.min(14.0,lastAlertLeadM*0.38);}\n\n    private int milestoneFor(double distance){if(distance<=50)return 50;if(distance<=100)return 100;if(distance<=200)return 200;if(distance<=300)return 300;return 0;}''',
        'prediction helpers'
    )

    replace_once(
        p,
        '    private void handleHazard(Hazard h,double progress){\n        if(!kindEnabled(h))return;double distance=Math.max(0,h.routeM-progress);String key=h.key;int milestone=milestoneFor(distance);',
        '    private void handleHazard(Hazard h,double progress){\n        if(!kindEnabled(h))return;double rawDistance=rawHazardDistance(h,progress);double distance=predictedHazardDistance(h,progress);String key=h.key;int milestone=milestoneFor(distance);',
        'hazard predicted distance'
    )

    replace_once(
        p,
        '        if(pref("front",true)&&distance<=22&&!warnedNow.contains(key)){',
        '        if(pref("front",true)&&rawDistance<=frontThresholdM()&&!warnedNow.contains(key)){',
        'hazard front threshold'
    )

    replace_once(
        p,
        '        double distance=Math.max(0,h.routeM-progress);int idx=bumpIndex(seq,h),total=seq.size(),remaining=Math.max(1,total-idx);String seqKey=bumpSequenceKey(seq);int milestone=milestoneFor(distance);',
        '        double rawDistance=rawHazardDistance(h,progress);double distance=predictedHazardDistance(h,progress);int idx=bumpIndex(seq,h),total=seq.size(),remaining=Math.max(1,total-idx);String seqKey=bumpSequenceKey(seq);int milestone=milestoneFor(distance);',
        'bump predicted distance'
    )

    replace_once(
        p,
        '        if(pref("front",true)&&distance<=22&&!warnedNow.contains(h.key)){',
        '        if(pref("front",true)&&rawDistance<=frontThresholdM()&&!warnedNow.contains(h.key)){',
        'bump front threshold'
    )


def patch_version():
    p = ROOT / 'app/build.gradle'
    text = p.read_text()
    text = re.sub(r'versionCode\s+\d+', 'versionCode 18', text, count=1)
    text = re.sub(r"versionName\s+'[^']+'", "versionName '4.6.1'", text, count=1)
    p.write_text(text)


patch_navigation()
patch_version()
print('MusicRoad v4.6.1 GPS predictive alert patch applied')

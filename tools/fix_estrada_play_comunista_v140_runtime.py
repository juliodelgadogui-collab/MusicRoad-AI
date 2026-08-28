#!/usr/bin/env python3
from pathlib import Path
ROOT=Path('estrada-play-comunista-app')

def read(r): return (ROOT/r).read_text(encoding='utf-8')
def write(r,v): (ROOT/r).write_text(v,encoding='utf-8')
def rep(s,a,b,label):
    if a not in s: raise SystemExit('missing '+label)
    return s.replace(a,b,1)

# Overpass QL does not need escaped quotes for these simple equality filters.
near='app/src/main/java/com/estradaplay/comunista/NearbyServicesActivity.java'
s=read(near)
s=s.replace('selector="[\\\\\\\"amenity\\\\\\\"=\\\\\\\"fuel\\\\\\\"]"','selector="[amenity=fuel]"')
s=s.replace('selector="[\\\\\\\"amenity\\\\\\\"=\\\\\\\"hospital\\\\\\\"]"','selector="[amenity=hospital]"')
s=s.replace('selector="[\\\\\\\"amenity\\\\\\\"=\\\\\\\"restaurant\\\\\\\"]"','selector="[amenity=restaurant]"')
s=s.replace('selector="[\\\\\\\"shop\\\\\\\"=\\\\\\\"car_repair\\\\\\\"]"','selector="[shop=car_repair]"')
# Also handle the exact generated spelling if Python escaping above differs.
s=s.replace('selector="[\\\\\"amenity\\\\\"=\\\\\"fuel\\\\\"]"','selector="[amenity=fuel]"')
s=s.replace('selector="[\\\\\"amenity\\\\\"=\\\\\"hospital\\\\\"]"','selector="[amenity=hospital]"')
s=s.replace('selector="[\\\\\"amenity\\\\\"=\\\\\"restaurant\\\\\"]"','selector="[amenity=restaurant]"')
s=s.replace('selector="[\\\\\"shop\\\\\"=\\\\\"car_repair\\\\\"]"','selector="[shop=car_repair]"')
# The compact generator had one extra closing brace between the inner
# try-with-resources and the outer catch. Keep the outer try open until sort.
s=s.replace('out.add(new Item(name,la,lo,distance(lat,lon,la,lo)));}}}out.sort',
            'out.add(new Item(name,la,lo,distance(lat,lon,la,lo)));}}out.sort')
write(near,s)

cam='app/src/main/java/com/estradaplay/comunista/CameraActivity.java'
s=read(cam)
if 'import androidx.camera.core.ExperimentalGetImage;' not in s:
    s=s.replace('import androidx.camera.core.CameraSelector;','import androidx.camera.core.CameraSelector;\nimport androidx.camera.core.ExperimentalGetImage;')
# Add explicit opt-in for ImageProxy#getImage.
s=s.replace('    private void analyzeFrame(ImageProxy proxy){','    @ExperimentalGetImage\n    private void analyzeFrame(ImageProxy proxy){')
old='''if(smartSigns()&&thermalStatus<3){ImageAnalysis analysis=new ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();analysis.setAnalyzer(analysisExecutor,this::analyzeFrame);cameraProvider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,preview,videoCapture,analysis);}else cameraProvider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,preview,videoCapture);'''
new='''if(smartSigns()&&thermalStatus<3){ImageAnalysis analysis=new ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();analysis.setAnalyzer(analysisExecutor,this::analyzeFrame);try{cameraProvider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,preview,videoCapture,analysis);}catch(Throwable incompatible){analysis.clearAnalyzer();cameraProvider.unbindAll();cameraProvider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,preview,videoCapture);if(visionState!=null)visionState.setText("VISÃO BETA · INDISPONÍVEL NESTE APARELHO");}}else cameraProvider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,preview,videoCapture);'''
s=rep(s,old,new,'camera use-case fallback')
write(cam,s)

# Ensure runtime fixes actually took effect.
check=read(near)
if '[amenity=fuel]' not in check: raise SystemExit('nearby filter fix did not apply')
if '}}}out.sort' in check: raise SystemExit('nearby Java brace fix did not apply')
if '}}out.sort' not in check: raise SystemExit('nearby Java method structure not recognized')
check=read(cam)
if '@ExperimentalGetImage' not in check: raise SystemExit('camera experimental annotation missing')
if 'VISÃO BETA · INDISPONÍVEL NESTE APARELHO' not in check: raise SystemExit('camera fallback fix did not apply')
print('Estrada Play Comunista 1.4.0 runtime hardening applied')

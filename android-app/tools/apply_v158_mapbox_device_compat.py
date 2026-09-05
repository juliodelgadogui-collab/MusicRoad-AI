from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app'
JAVA = APP / 'src/main/java/com/musicroad/ai'

# Prefer the default Mapbox Android artifact (NDK 23) for broad device compatibility.
build = APP / 'build.gradle'
b = build.read_text()
b = b.replace("com.mapbox.maps:android-ndk27:11.28.3", "com.mapbox.maps:android:11.28.3")
b = re.sub(r'versionCode\s+\d+', 'versionCode 34', b, count=1)
b = re.sub(r"versionName\s+'[^']+'", "versionName '1.5.8'", b, count=1)
build.write_text(b)

map_file = JAVA / 'NativeMapView.java'
s = map_file.read_text()

# MapInitOptions lets us force TextureView for OEM/GPU compatibility.
if 'import com.mapbox.maps.MapInitOptions;' not in s:
    s = s.replace('import com.mapbox.maps.MapView;', 'import com.mapbox.maps.MapView;\nimport com.mapbox.maps.MapInitOptions;')

old = '''            MapboxOptions.INSTANCE.setAccessToken(token);\n            MapView mv=new MapView(getContext());\n            mv.setAlpha(0f);'''
new = '''            MapboxOptions.INSTANCE.setAccessToken(token);\n            MapInitOptions initOptions=new MapInitOptions(getContext());\n            initOptions.setTextureView(true);\n            MapView mv=new MapView(getContext(),initOptions);\n            mv.setAlpha(0f);'''
if old not in s:
    raise SystemExit('v1.5.8: MapView init block not found')
s = s.replace(old, new, 1)

old_err = '''        } catch(Throwable e) {\n            if(mapView!=null){removeView(mapView);mapView=null;mapboxMap=null;}\n            message="Mapbox: erro de inicialização";\n            fallback.setVisibility(View.VISIBLE);\n            fallback.invalidate();\n        }'''
new_err = '''        } catch(Throwable e) {\n            if(mapView!=null){removeView(mapView);mapView=null;mapboxMap=null;}\n            String type=e.getClass().getSimpleName();\n            String detail=e.getMessage();\n            if(detail==null||detail.trim().isEmpty())detail=type;\n            detail=detail.replace('\\n',' ').replace('\\r',' ');\n            if(detail.length()>72)detail=detail.substring(0,72);\n            message="Mapbox init: "+detail;\n            fallback.setVisibility(View.VISIBLE);\n            fallback.invalidate();\n        }'''
if old_err not in s:
    raise SystemExit('v1.5.8: init error block not found')
s = s.replace(old_err, new_err, 1)

map_file.write_text(s)
print('MusicRoad 1.5.8 Mapbox device compatibility applied')

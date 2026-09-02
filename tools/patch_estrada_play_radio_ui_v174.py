from pathlib import Path
import re

ROOT = Path('estrada-play-comunista-app')
JAVA = ROOT / 'app/src/main/java/com/estradaplay/comunista'

# Version: safe whether the branch is still 1.7.3 or was partially prepared.
p = ROOT / 'app/build.gradle'
s = p.read_text(encoding='utf-8')
s = re.sub(r'versionCode\s+173\b', 'versionCode 174', s)
s = s.replace("versionName '1.7.3'", "versionName '1.7.4'")
if "versionCode 174" not in s or "versionName '1.7.4'" not in s:
    raise SystemExit('version bump failed')
p.write_text(s, encoding='utf-8')

# Portrait shell: all five sectors visible at once. The direct source may already
# carry PORTRAIT_NAV_FIT_V174, so never try to patch it twice.
p = JAVA / 'UnifiedAppShell.java'
s = p.read_text(encoding='utf-8')
s = s.replace('import android.widget.HorizontalScrollView;\n', '')
s = s.replace('root.addView(head,new LinearLayout.LayoutParams(-1,dp(a,62)));',
              'root.addView(head,new LinearLayout.LayoutParams(-1,dp(a,56)));')
s = s.replace('head.addView(mark,new LinearLayout.LayoutParams(dp(a,48),dp(a,42)));',
              'head.addView(mark,new LinearLayout.LayoutParams(dp(a,44),dp(a,38)));')
s = s.replace('words.addView(text(a,title(active),16,TEXT,true));',
              'words.addView(text(a,title(active),15,TEXT,true));')

if 'PORTRAIT_NAV_FIT_V174' not in s:
    start = s.find('        HorizontalScrollView hsv=')
    end_marker = 'root.addView(hsv,new LinearLayout.LayoutParams(-1,dp(a,52)));'
    end = s.find(end_marker, start)
    if start < 0 or end < 0:
        raise SystemExit('portrait navigation source not found')
    end += len(end_marker)
    new_nav = '''        // PORTRAIT_NAV_FIT_V174: all five sectors remain visible at once.\n        LinearLayout row=new LinearLayout(a);row.setOrientation(LinearLayout.HORIZONTAL);row.setPadding(dp(a,5),dp(a,4),dp(a,5),dp(a,4));row.setBackgroundColor(BG);\n        addNavChipFit(a,row,"ESTRADA","road",active,RoadMapActivity.class);\n        addNavChipFit(a,row,"MÚSICA","music",active,MusicPlayerActivity.class);\n        addNavChipFit(a,row,"RÁDIO","radio",active,RoadRadioActivity.class);\n        addNavChipFit(a,row,"VIAGEM","trip",active,TripPlannerActivity.class);\n        addNavChipFit(a,row,"CENTRAL","central",active,DriveToolsActivity.class);\n        root.addView(row,new LinearLayout.LayoutParams(-1,dp(a,44)));'''
    s = s[:start] + new_nav + s[end:]
    anchor = '''    private static View navChip(Activity a,String label,String key,String active,Class<?> cls){\n        TextView v=navText(a,label,key.equals(active));v.setMinWidth(dp(a,92));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(a,40));p.setMargins(0,0,dp(a,7),0);v.setLayoutParams(p);v.setOnClickListener(x->go(a,key,active,cls));return v;\n    }'''
    helper = anchor + '''\n    private static void addNavChipFit(Activity a,LinearLayout row,String label,String key,String active,Class<?> cls){\n        TextView v=navText(a,label,key.equals(active));v.setTextSize(7.6f);v.setMinWidth(0);v.setSingleLine(true);\n        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(a,36),1f);p.setMargins(dp(a,2),0,dp(a,2),0);row.addView(v,p);\n        v.setOnClickListener(x->go(a,key,active,cls));\n    }'''
    if anchor not in s:
        raise SystemExit('nav helper anchor not found')
    s = s.replace(anchor, helper, 1)
else:
    s = s.replace('row.setPadding(dp(a,7),dp(a,6),dp(a,7),dp(a,6));',
                  'row.setPadding(dp(a,5),dp(a,4),dp(a,5),dp(a,4));')
    s = s.replace('v.setTextSize(8.2f);', 'v.setTextSize(7.6f);')
    s = s.replace('new LinearLayout.LayoutParams(0,dp(a,40),1f)',
                  'new LinearLayout.LayoutParams(0,dp(a,36),1f)')
    s = s.replace('root.addView(row,new LinearLayout.LayoutParams(-1,dp(a,52)));',
                  'root.addView(row,new LinearLayout.LayoutParams(-1,dp(a,44)));')
p.write_text(s, encoding='utf-8')

# Radio page: keep the custom picker already in source, compact the screen and
# make the manual road choice persist explicitly.
p = JAVA / 'RoadRadioActivity.java'
s = p.read_text(encoding='utf-8')
s = s.replace('p.setPadding(dp(18),dp(18),dp(18),dp(30));', 'p.setPadding(dp(14),dp(12),dp(14),dp(26));')
s = s.replace(
    'TextView k=t("ESTRADA PLAY · COMUNICAÇÃO",10,GOLD,true);k.setLetterSpacing(.13f);p.addView(k);p.addView(t("RÁDIO DA RODOVIA",29,TEXT,true));p.addView(t("Sala automática pela rodovia e pelo trecho local. Motoristas distantes na mesma BR ficam em salas diferentes. Áudio WebRTC vai direto entre os aparelhos e não fica gravado no servidor.",12,MUTED,false));',
    'TextView k=t("COMUNICAÇÃO DO TRECHO",9,GOLD,true);k.setLetterSpacing(.13f);p.addView(k);p.addView(t("Sala automática pela rodovia e pelo trecho local. Motoristas distantes na mesma BR ficam em salas diferentes. O áudio vai direto entre os aparelhos e não fica gravado no servidor.",11,MUTED,false));'
)
s = s.replace('chooseRoad=button("ESCOLHER RODOVIA · SE NÃO IDENTIFICAR",Color.rgb(42,22,25));p.addView(chooseRoad,new LinearLayout.LayoutParams(-1,dp(48)));',
              'chooseRoad=button("ESCOLHER RODOVIA MANUALMENTE",Color.rgb(42,22,25));p.addView(chooseRoad,new LinearLayout.LayoutParams(-1,dp(46)));')
s = s.replace('join=button("ENTRAR NO RÁDIO",RED);p.addView(join,new LinearLayout.LayoutParams(-1,dp(58)));',
              'join=button("ENTRAR NO RÁDIO",RED);p.addView(join,new LinearLayout.LayoutParams(-1,dp(54)));')
s = s.replace('ptt=button("SEGURE PARA FALAR",Color.rgb(78,18,28));p.addView(ptt,new LinearLayout.LayoutParams(-1,dp(92)));',
              'ptt=button("SEGURE PARA FALAR",Color.rgb(78,18,28));p.addView(ptt,new LinearLayout.LayoutParams(-1,dp(78)));')

if 'ROAD_PICKER_PANEL_V174' in s:
    target = 'option.setOnClickListener(v->{\n                Intent i=new Intent(this,RoadRadioService.class).setAction(RoadRadioService.ACTION_SET_ROAD);i.putExtra("road",road);'
    repl = 'option.setOnClickListener(v->{\n                KnownRoadCatalog.select(this,road);\n                Intent i=new Intent(this,RoadRadioService.class).setAction(RoadRadioService.ACTION_SET_ROAD);i.putExtra("road",road);'
    if target in s and 'KnownRoadCatalog.select(this,road);' not in s:
        s = s.replace(target, repl, 1)
else:
    raise SystemExit('custom road picker source missing')

if 'ROAD_PICKER_PANEL_V174' not in s or 'KnownRoadCatalog.select(this,road);' not in s:
    raise SystemExit('custom road picker validation failed')
p.write_text(s, encoding='utf-8')

print('Universal 1.7.4 UI fixes ready')

from pathlib import Path

ROOT = Path('estrada-play-comunista-app')
JAVA = ROOT / 'app/src/main/java/com/estradaplay/comunista'

# Version
p = ROOT / 'app/build.gradle'
s = p.read_text(encoding='utf-8')
s = s.replace("versionCode 172", "versionCode 173")
s = s.replace("versionName '1.7.2'", "versionName '1.7.3'")
p.write_text(s, encoding='utf-8')

# Portrait shell: all five sectors fit on screen; no horizontal scrolling.
p = JAVA / 'UnifiedAppShell.java'
s = p.read_text(encoding='utf-8')
s = s.replace('root.addView(head,new LinearLayout.LayoutParams(-1,dp(a,62)));',
              'root.addView(head,new LinearLayout.LayoutParams(-1,dp(a,54)));')
s = s.replace('head.addView(mark,new LinearLayout.LayoutParams(dp(a,48),dp(a,42)));',
              'head.addView(mark,new LinearLayout.LayoutParams(dp(a,44),dp(a,38)));')
s = s.replace('words.addView(text(a,title(active),16,TEXT,true));',
              'words.addView(text(a,title(active),15,TEXT,true));')
old = '''        HorizontalScrollView hsv=new HorizontalScrollView(a);hsv.setHorizontalScrollBarEnabled(false);hsv.setOverScrollMode(View.OVER_SCROLL_NEVER);hsv.setBackgroundColor(BG);\n        LinearLayout row=new LinearLayout(a);row.setOrientation(LinearLayout.HORIZONTAL);row.setPadding(dp(a,10),dp(a,6),dp(a,10),dp(a,6));\n        row.addView(navChip(a,"ESTRADA","road",active,RoadMapActivity.class));\n        row.addView(navChip(a,"MÚSICA","music",active,MusicPlayerActivity.class));\n        row.addView(navChip(a,"RÁDIO","radio",active,RoadRadioActivity.class));\n        row.addView(navChip(a,"VIAGEM","trip",active,TripPlannerActivity.class));\n        row.addView(navChip(a,"CENTRAL","central",active,DriveToolsActivity.class));\n        hsv.addView(row,new HorizontalScrollView.LayoutParams(-2,-1));root.addView(hsv,new LinearLayout.LayoutParams(-1,dp(a,52)));'''
new = '''        LinearLayout row=new LinearLayout(a);row.setOrientation(LinearLayout.HORIZONTAL);row.setPadding(dp(a,6),dp(a,4),dp(a,6),dp(a,4));\n        addPortraitNav(a,row,"ESTRADA","road",active,RoadMapActivity.class);\n        addPortraitNav(a,row,"MÚSICA","music",active,MusicPlayerActivity.class);\n        addPortraitNav(a,row,"RÁDIO","radio",active,RoadRadioActivity.class);\n        addPortraitNav(a,row,"VIAGEM","trip",active,TripPlannerActivity.class);\n        addPortraitNav(a,row,"CENTRAL","central",active,DriveToolsActivity.class);\n        root.addView(row,new LinearLayout.LayoutParams(-1,dp(a,44)));'''
if old not in s:
    raise SystemExit('portrait nav block not found')
s = s.replace(old, new)
needle = '''    private static View navChip(Activity a,String label,String key,String active,Class<?> cls){\n        TextView v=navText(a,label,key.equals(active));v.setMinWidth(dp(a,92));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(a,40));p.setMargins(0,0,dp(a,7),0);v.setLayoutParams(p);v.setOnClickListener(x->go(a,key,active,cls));return v;\n    }'''
replacement = '''    private static View navChip(Activity a,String label,String key,String active,Class<?> cls){\n        TextView v=navText(a,label,key.equals(active));v.setTextSize(7.8f);v.setOnClickListener(x->go(a,key,active,cls));return v;\n    }\n    private static void addPortraitNav(Activity a,LinearLayout row,String label,String key,String active,Class<?> cls){\n        View v=navChip(a,label,key,active,cls);\n        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(a,36),1f);p.setMargins(dp(a,2),0,dp(a,2),0);\n        row.addView(v,p);\n    }'''
if needle not in s:
    raise SystemExit('navChip block not found')
s = s.replace(needle, replacement)
p.write_text(s, encoding='utf-8')

# Radio: remove duplicated page heading and make fallback road list actually visible.
p = JAVA / 'RoadRadioActivity.java'
s = p.read_text(encoding='utf-8')
s = s.replace('p.setPadding(dp(18),dp(18),dp(18),dp(30));', 'p.setPadding(dp(14),dp(12),dp(14),dp(26));')
old_head = '''        TextView k=t("ESTRADA PLAY · COMUNICAÇÃO",10,GOLD,true);k.setLetterSpacing(.13f);p.addView(k);p.addView(t("RÁDIO DA RODOVIA",29,TEXT,true));p.addView(t("Sala automática pela rodovia e pelo trecho local. Motoristas distantes na mesma BR ficam em salas diferentes. Áudio WebRTC vai direto entre os aparelhos e não fica gravado no servidor.",12,MUTED,false));'''
new_head = '''        TextView k=t("COMUNICAÇÃO DO TRECHO",9,GOLD,true);k.setLetterSpacing(.13f);p.addView(k);p.addView(t("A sala acompanha a rodovia e o trecho local. O áudio vai direto entre os aparelhos e não fica gravado no servidor.",11,MUTED,false));'''
if old_head not in s:
    raise SystemExit('radio heading block not found')
s = s.replace(old_head, new_head)
s = s.replace('chooseRoad=button("ESCOLHER RODOVIA · SE NÃO IDENTIFICAR",Color.rgb(42,22,25));p.addView(chooseRoad,new LinearLayout.LayoutParams(-1,dp(48)));',
              'chooseRoad=button("ESCOLHER RODOVIA MANUALMENTE",Color.rgb(42,22,25));p.addView(chooseRoad,new LinearLayout.LayoutParams(-1,dp(44)));')
s = s.replace('join=button("ENTRAR NO RÁDIO",RED);p.addView(join,new LinearLayout.LayoutParams(-1,dp(58)));',
              'join=button("ENTRAR NO RÁDIO",RED);p.addView(join,new LinearLayout.LayoutParams(-1,dp(54)));')
s = s.replace('ptt=button("SEGURE PARA FALAR",Color.rgb(78,18,28));p.addView(ptt,new LinearLayout.LayoutParams(-1,dp(92)));',
              'ptt=button("SEGURE PARA FALAR",Color.rgb(78,18,28));p.addView(ptt,new LinearLayout.LayoutParams(-1,dp(78)));')
old_picker = '''        new android.app.AlertDialog.Builder(this)\n                .setTitle("Rodovia de apoio")\n                .setMessage("Use somente se a identificação automática falhar. O GPS ainda separa o rádio por trecho local.")\n                .setItems(roads,(d,which)->{if(which<0||which>=roads.length)return;Intent i=new Intent(this,RoadRadioService.class).setAction(RoadRadioService.ACTION_SET_ROAD);i.putExtra("road",roads[which]);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);room.setText(roads[which]+" · CONFIRMANDO TRECHO…");})\n                .setNegativeButton("CANCELAR",null).show();'''
new_picker = '''        if(roads==null||roads.length==0){Toast.makeText(this,"Catálogo de rodovias indisponível.",Toast.LENGTH_SHORT).show();return;}\n        new android.app.AlertDialog.Builder(this)\n                .setTitle("Escolher rodovia")\n                .setItems(roads,(d,which)->{\n                    if(which<0||which>=roads.length)return;\n                    String selected=roads[which];\n                    KnownRoadCatalog.select(this,selected);\n                    Intent i=new Intent(this,RoadRadioService.class).setAction(RoadRadioService.ACTION_SET_ROAD);\n                    i.putExtra("road",selected);\n                    if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);\n                    room.setText(selected+" · CONFIRMANDO TRECHO…");\n                })\n                .setNegativeButton("CANCELAR",null).show();'''
if old_picker not in s:
    raise SystemExit('road picker block not found')
s = s.replace(old_picker, new_picker)
p.write_text(s, encoding='utf-8')

print('Universal 1.7.3 portrait/radio fixes applied')

from pathlib import Path
import re

ROOT = Path('estrada-play-comunista-app')
JAVA = ROOT / 'app/src/main/java/com/estradaplay/comunista'

# Version
p = ROOT / 'app/build.gradle'
s = p.read_text(encoding='utf-8')
s = re.sub(r'versionCode\s+173\b', 'versionCode 174', s)
s = s.replace("versionName '1.7.3'", "versionName '1.7.4'")
if "versionCode 174" not in s or "versionName '1.7.4'" not in s:
    raise SystemExit('version bump failed')
p.write_text(s, encoding='utf-8')

# Portrait shell: five sectors always visible, no horizontal scrolling.
p = JAVA / 'UnifiedAppShell.java'
s = p.read_text(encoding='utf-8')
s = s.replace('import android.widget.HorizontalScrollView;\n', '')
s = s.replace('root.addView(head,new LinearLayout.LayoutParams(-1,dp(a,62)));',
              'root.addView(head,new LinearLayout.LayoutParams(-1,dp(a,56)));')
s = s.replace('head.addView(mark,new LinearLayout.LayoutParams(dp(a,48),dp(a,42)));',
              'head.addView(mark,new LinearLayout.LayoutParams(dp(a,44),dp(a,38)));')
s = s.replace('words.addView(text(a,title(active),16,TEXT,true));',
              'words.addView(text(a,title(active),15,TEXT,true));')

pattern = re.compile(
    r'        HorizontalScrollView hsv=.*?root\.addView\(hsv,new LinearLayout\.LayoutParams\(-1,dp\(a,52\)\)\);',
    re.S,
)
new_nav = '''        LinearLayout row=new LinearLayout(a);row.setOrientation(LinearLayout.HORIZONTAL);row.setPadding(dp(a,4),dp(a,4),dp(a,4),dp(a,4));
        addPortraitNav(a,row,"ESTRADA","road",active,RoadMapActivity.class);
        addPortraitNav(a,row,"MÚSICA","music",active,MusicPlayerActivity.class);
        addPortraitNav(a,row,"RÁDIO","radio",active,RoadRadioActivity.class);
        addPortraitNav(a,row,"VIAGEM","trip",active,TripPlannerActivity.class);
        addPortraitNav(a,row,"CENTRAL","central",active,DriveToolsActivity.class);
        root.addView(row,new LinearLayout.LayoutParams(-1,dp(a,44)));'''
s, n = pattern.subn(new_nav, s, count=1)
if n != 1:
    raise SystemExit(f'portrait nav replacement failed: {n}')

anchor = '''    private static View navChip(Activity a,String label,String key,String active,Class<?> cls){
        TextView v=navText(a,label,key.equals(active));v.setMinWidth(dp(a,92));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(a,40));p.setMargins(0,0,dp(a,7),0);v.setLayoutParams(p);v.setOnClickListener(x->go(a,key,active,cls));return v;
    }'''
helper = anchor + '''
    private static void addPortraitNav(Activity a,LinearLayout row,String label,String key,String active,Class<?> cls){
        TextView v=navText(a,label,key.equals(active));
        v.setTextSize(7.4f);
        v.setSingleLine(true);
        v.setOnClickListener(x->go(a,key,active,cls));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(a,36),1f);
        p.setMargins(dp(a,2),0,dp(a,2),0);
        row.addView(v,p);
    }'''
if anchor not in s:
    raise SystemExit('navChip anchor not found')
s = s.replace(anchor, helper, 1)
p.write_text(s, encoding='utf-8')

# Radio page: remove duplicate giant title and use a native custom in-app road picker.
p = JAVA / 'RoadRadioActivity.java'
s = p.read_text(encoding='utf-8')
s = s.replace('p.setPadding(dp(18),dp(18),dp(18),dp(30));', 'p.setPadding(dp(14),dp(12),dp(14),dp(26));')
s = s.replace(
    'TextView k=t("ESTRADA PLAY · COMUNICAÇÃO",10,GOLD,true);k.setLetterSpacing(.13f);p.addView(k);p.addView(t("RÁDIO DA RODOVIA",29,TEXT,true));p.addView(t("Sala automática pela rodovia e pelo trecho local. Motoristas distantes na mesma BR ficam em salas diferentes. Áudio WebRTC vai direto entre os aparelhos e não fica gravado no servidor.",12,MUTED,false));',
    'TextView k=t("COMUNICAÇÃO DO TRECHO",9,GOLD,true);k.setLetterSpacing(.13f);p.addView(k);p.addView(t("Sala automática pela rodovia e pelo trecho local. Motoristas distantes na mesma BR ficam em salas diferentes. O áudio vai direto entre os aparelhos e não fica gravado no servidor.",11,MUTED,false));'
)
s = s.replace('chooseRoad=button("ESCOLHER RODOVIA · SE NÃO IDENTIFICAR",Color.rgb(42,22,25));p.addView(chooseRoad,new LinearLayout.LayoutParams(-1,dp(48)));',
              'chooseRoad=button("ESCOLHER RODOVIA MANUALMENTE",Color.rgb(42,22,25));p.addView(chooseRoad,new LinearLayout.LayoutParams(-1,dp(46)));')

picker_pattern = re.compile(r'    private void showRoadPicker\(\)\{.*?\n    \}\n\n    private void enter\(\)', re.S)
new_picker = '''    private void showRoadPicker(){
        final String[] roads=KnownRoadCatalog.PRESET_ROADS;
        if(roads==null||roads.length==0){Toast.makeText(this,"Catálogo de rodovias indisponível.",Toast.LENGTH_SHORT).show();return;}

        final android.app.Dialog dialog=new android.app.Dialog(this);
        LinearLayout shell=new LinearLayout(this);shell.setOrientation(LinearLayout.VERTICAL);shell.setPadding(dp(16),dp(14),dp(16),dp(12));shell.setBackground(box(Color.rgb(18,9,12),16,Color.rgb(104,48,56)));
        TextView title=t("ESCOLHER RODOVIA",18,TEXT,true);shell.addView(title);
        TextView hint=t("Use somente quando a identificação automática falhar. O GPS continua separando o rádio por trecho local.",11,MUTED,false);shell.addView(hint);

        ScrollView scroll=new ScrollView(this);LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);scroll.addView(list);
        String current=KnownRoadCatalog.selected(this);
        for(String road:roads){
            final String selected=road;
            TextView item=t(road,14,TEXT,true);item.setGravity(Gravity.CENTER_VERTICAL);item.setPadding(dp(14),0,dp(14),0);item.setClickable(true);item.setFocusable(true);
            boolean active=road.equals(current);item.setBackground(box(active?Color.rgb(79,10,23):Color.rgb(31,15,19),10,active?RED:Color.rgb(76,38,43)));
            LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(-1,dp(46));ip.setMargins(0,dp(3),0,dp(3));list.addView(item,ip);
            item.setOnClickListener(v->{
                KnownRoadCatalog.select(this,selected);
                Intent i=new Intent(this,RoadRadioService.class).setAction(RoadRadioService.ACTION_SET_ROAD);i.putExtra("road",selected);
                if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);
                room.setText(selected+" · CONFIRMANDO TRECHO…");
                dialog.dismiss();
            });
        }
        int maxList=Math.min(dp(330),Math.round(getResources().getDisplayMetrics().heightPixels*0.48f));
        shell.addView(scroll,new LinearLayout.LayoutParams(-1,maxList));
        Button cancel=button("CANCELAR",Color.rgb(42,22,25));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,dp(44));cp.setMargins(0,dp(8),0,0);shell.addView(cancel,cp);cancel.setOnClickListener(v->dialog.dismiss());

        dialog.setContentView(shell);dialog.setCanceledOnTouchOutside(true);dialog.show();
        android.view.Window w=dialog.getWindow();if(w!=null){w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));int width=Math.min(dp(520),Math.round(getResources().getDisplayMetrics().widthPixels*0.92f));w.setLayout(width,ViewGroup.LayoutParams.WRAP_CONTENT);}
    }

    private void enter()'''
s, n = picker_pattern.subn(new_picker, s, count=1)
if n != 1:
    raise SystemExit(f'road picker replacement failed: {n}')
p.write_text(s, encoding='utf-8')

print('Universal 1.7.4 portrait navigation and radio picker fixes applied')

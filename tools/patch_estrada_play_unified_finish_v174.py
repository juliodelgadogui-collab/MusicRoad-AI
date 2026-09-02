from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "estrada-play-comunista-app"
JAVA = APP / "app/src/main/java/com/estradaplay/comunista"


def replace_once(path: Path, old: str, new: str):
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"expected fragment not found in {path}: {old[:120]!r}")
    text = text.replace(old, new, 1)
    path.write_text(text, encoding="utf-8")


# Version bump only; no feature addition.
gradle = APP / "app/build.gradle"
replace_once(gradle, "versionCode 173", "versionCode 174")
replace_once(gradle, "versionName '1.7.3'", "versionName '1.7.4'")

shell = JAVA / "UnifiedAppShell.java"
old_nav = '''        HorizontalScrollView hsv=new HorizontalScrollView(a);hsv.setHorizontalScrollBarEnabled(false);hsv.setOverScrollMode(View.OVER_SCROLL_NEVER);hsv.setBackgroundColor(BG);
        LinearLayout row=new LinearLayout(a);row.setOrientation(LinearLayout.HORIZONTAL);row.setPadding(dp(a,10),dp(a,6),dp(a,10),dp(a,6));
        row.addView(navChip(a,"ESTRADA","road",active,RoadMapActivity.class));
        row.addView(navChip(a,"MÚSICA","music",active,MusicPlayerActivity.class));
        row.addView(navChip(a,"RÁDIO","radio",active,RoadRadioActivity.class));
        row.addView(navChip(a,"VIAGEM","trip",active,TripPlannerActivity.class));
        row.addView(navChip(a,"CENTRAL","central",active,DriveToolsActivity.class));
        hsv.addView(row,new HorizontalScrollView.LayoutParams(-2,-1));root.addView(hsv,new LinearLayout.LayoutParams(-1,dp(a,52)));
'''
new_nav = '''        // PORTRAIT_NAV_FIT_V174: all five sectors remain visible at once.
        // A scrollable rail made CENTRAL disappear off-screen and looked like another app.
        LinearLayout row=new LinearLayout(a);row.setOrientation(LinearLayout.HORIZONTAL);row.setPadding(dp(a,7),dp(a,6),dp(a,7),dp(a,6));row.setBackgroundColor(BG);
        addNavChipFit(a,row,"ESTRADA","road",active,RoadMapActivity.class);
        addNavChipFit(a,row,"MÚSICA","music",active,MusicPlayerActivity.class);
        addNavChipFit(a,row,"RÁDIO","radio",active,RoadRadioActivity.class);
        addNavChipFit(a,row,"VIAGEM","trip",active,TripPlannerActivity.class);
        addNavChipFit(a,row,"CENTRAL","central",active,DriveToolsActivity.class);
        root.addView(row,new LinearLayout.LayoutParams(-1,dp(a,52)));
'''
replace_once(shell, old_nav, new_nav)

old_methods = '''    private static View navChip(Activity a,String label,String key,String active,Class<?> cls){
        TextView v=navText(a,label,key.equals(active));v.setMinWidth(dp(a,92));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(a,40));p.setMargins(0,0,dp(a,7),0);v.setLayoutParams(p);v.setOnClickListener(x->go(a,key,active,cls));return v;
    }
'''
new_methods = '''    private static View navChip(Activity a,String label,String key,String active,Class<?> cls){
        TextView v=navText(a,label,key.equals(active));v.setMinWidth(dp(a,92));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(a,40));p.setMargins(0,0,dp(a,7),0);v.setLayoutParams(p);v.setOnClickListener(x->go(a,key,active,cls));return v;
    }
    private static void addNavChipFit(Activity a,LinearLayout row,String label,String key,String active,Class<?> cls){
        TextView v=navText(a,label,key.equals(active));v.setTextSize(8.2f);v.setMinWidth(0);v.setSingleLine(true);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(a,40),1f);p.setMargins(dp(a,2),0,dp(a,2),0);row.addView(v,p);
        v.setOnClickListener(x->go(a,key,active,cls));
    }
'''
replace_once(shell, old_methods, new_methods)
replace_once(shell, 'if("radio".equals(s))return "Rádio da Rodovia";', 'if("radio".equals(s))return "Rádio";')
replace_once(shell, 'return "Central de Bordo";', 'return "Central";')

radio = JAVA / "RoadRadioActivity.java"
old_picker = '''    private void showRoadPicker(){
        final String[] roads=KnownRoadCatalog.PRESET_ROADS;
        new android.app.AlertDialog.Builder(this)
                .setTitle("Rodovia de apoio")
                .setMessage("Use somente se a identificação automática falhar. O GPS ainda separa o rádio por trecho local.")
                .setItems(roads,(d,which)->{if(which<0||which>=roads.length)return;Intent i=new Intent(this,RoadRadioService.class).setAction(RoadRadioService.ACTION_SET_ROAD);i.putExtra("road",roads[which]);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);room.setText(roads[which]+" · CONFIRMANDO TRECHO…");})
                .setNegativeButton("CANCELAR",null).show();
    }
'''
new_picker = '''    // ROAD_PICKER_PANEL_V174: custom Estrada Play panel. AlertDialog message+items hid
    // the actual road list on some Android builds, leaving only CANCELAR visible.
    private void showRoadPicker(){
        final String[] roads=KnownRoadCatalog.PRESET_ROADS;
        final android.app.Dialog dialog=new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(18),dp(16),dp(18),dp(16));panel.setBackground(box(Color.rgb(18,9,12),18,Color.rgb(92,43,50)));
        TextView over=t("ESTRADA PLAY · RÁDIO",10,GOLD,true);over.setLetterSpacing(.12f);panel.addView(over);
        panel.addView(t("RODOVIA DE APOIO",22,TEXT,true));
        panel.addView(t("Use somente se a identificação automática falhar. O GPS continua separando o rádio por trecho local.",12,MUTED,false));

        TextView hint=t("SELECIONE A RODOVIA",10,GOLD,true);hint.setPadding(0,dp(12),0,dp(7));panel.addView(hint);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);list.setPadding(0,0,0,dp(6));scroll.addView(list,new ScrollView.LayoutParams(-1,-2));
        for(String road:roads){
            Button option=button(road,Color.rgb(45,18,23));option.setTextSize(14);option.setAllCaps(false);
            LinearLayout.LayoutParams op=new LinearLayout.LayoutParams(-1,dp(48));op.setMargins(0,0,0,dp(6));list.addView(option,op);
            option.setOnClickListener(v->{
                Intent i=new Intent(this,RoadRadioService.class).setAction(RoadRadioService.ACTION_SET_ROAD);i.putExtra("road",road);
                if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);
                room.setText(road+" · CONFIRMANDO TRECHO…");dialog.dismiss();
            });
        }
        panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1f));
        Button cancel=button("CANCELAR",Color.rgb(31,18,21));cancel.setAllCaps(false);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,dp(48));cp.setMargins(0,dp(6),0,0);panel.addView(cancel,cp);cancel.setOnClickListener(v->dialog.dismiss());

        dialog.setContentView(panel);dialog.setCanceledOnTouchOutside(true);dialog.show();
        android.view.Window w=dialog.getWindow();if(w!=null){
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            int sw=getResources().getDisplayMetrics().widthPixels,sh=getResources().getDisplayMetrics().heightPixels;
            w.setLayout(Math.min(sw-dp(24),dp(520)),Math.min((int)(sh*.78f),dp(620)));
            w.setGravity(Gravity.CENTER);
        }
    }
'''
replace_once(radio, old_picker, new_picker)

print("Universal 1.7.4 UI finish patch applied")

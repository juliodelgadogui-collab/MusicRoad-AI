from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "estrada-play-comunista-app"
JAVA = APP / "app/src/main/java/com/estradaplay/comunista"


def replace_once(path: Path, old: str, new: str):
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"expected fragment not found in {path}: {old[:140]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


gradle = APP / "app/build.gradle"
replace_once(gradle, "versionCode 175", "versionCode 176")
replace_once(gradle, "versionName '1.7.5'", "versionName '1.7.6'")

radio = JAVA / "RoadRadioActivity.java"
old = '''    // ROAD_PICKER_PANEL_V174: custom Estrada Play panel. AlertDialog message+items hid
    // the actual road list on some Android builds, leaving only CANCELAR visible.
    private void showRoadPicker(){
        final String[] roads=KnownRoadCatalog.PRESET_ROADS;
        if(roads==null||roads.length==0){Toast.makeText(this,"Catálogo de rodovias indisponível.",Toast.LENGTH_SHORT).show();return;}
        new android.app.AlertDialog.Builder(this)
                .setTitle("Escolher rodovia de apoio")
                .setSingleChoiceItems(roads,-1,(d,which)->{
                    if(which<0||which>=roads.length)return;
                    d.dismiss();
                    applyRoadSupport(roads[which]);
                })
                .setNeutralButton("DIGITAR",(d,w)->showRoadInput())
                .setNegativeButton("CANCELAR",null)
                .show();
    }

    private void showRoadInput(){
        LinearLayout wrap=new LinearLayout(this);wrap.setOrientation(LinearLayout.VERTICAL);wrap.setPadding(dp(22),dp(8),dp(22),0);
        TextView help=t("Informe no formato BR-101, RJ-116, MG-050 ou ES-060.",11,MUTED,false);wrap.addView(help);
        EditText input=new EditText(this);input.setSingleLine(true);input.setHint("Ex.: BR-101");input.setTextColor(TEXT);input.setHintTextColor(MUTED);input.setTextSize(17);input.setPadding(dp(12),0,dp(12),0);input.setBackground(box(Color.rgb(25,10,14),10,Color.rgb(94,43,50)));wrap.addView(input,new LinearLayout.LayoutParams(-1,dp(52)));
        new android.app.AlertDialog.Builder(this)
                .setTitle("Rodovia de apoio")
                .setView(wrap)
                .setPositiveButton("USAR",(d,w)->applyRoadSupport(String.valueOf(input.getText())))
                .setNegativeButton("CANCELAR",null)
                .show();
    }
'''
new = '''    // UNIFIED_ROAD_PICKER_V176: road selection stays inside the Estrada Play visual system.
    // No stock AlertDialog: the driver sees the real catalog in a scrollable branded panel.
    private void showRoadPicker(){
        final String[] roads=KnownRoadCatalog.PRESET_ROADS;
        if(roads==null||roads.length==0){Toast.makeText(this,"Catálogo de rodovias indisponível.",Toast.LENGTH_SHORT).show();return;}
        final android.app.Dialog dialog=baseRoadDialog();
        LinearLayout panel=roadDialogPanel();
        panel.addView(t("ESTRADA PLAY · RÁDIO",9,GOLD,true));
        panel.addView(t("RODOVIA DE APOIO",23,TEXT,true));
        panel.addView(t("Use somente se a identificação automática falhar. O GPS continua separando o rádio por trecho local.",11,MUTED,false));
        TextView choose=t("ESCOLHA UMA RODOVIA",10,GOLD,true);choose.setPadding(0,dp(12),0,dp(7));panel.addView(choose);

        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);list.setPadding(0,0,0,dp(4));scroll.addView(list,new ScrollView.LayoutParams(-1,-2));
        for(String road:roads){
            Button option=button(road,Color.rgb(38,16,21));option.setAllCaps(false);option.setTextSize(14);option.setGravity(Gravity.CENTER_VERTICAL|Gravity.LEFT);option.setPadding(dp(16),0,dp(16),0);
            LinearLayout.LayoutParams op=new LinearLayout.LayoutParams(-1,dp(48));op.setMargins(0,0,0,dp(6));list.addView(option,op);
            option.setOnClickListener(v->{dialog.dismiss();applyRoadSupport(road);});
        }
        panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1f));

        LinearLayout actions=row();
        Button type=button("DIGITAR RODOVIA",Color.rgb(61,18,26));type.setAllCaps(false);
        Button cancel=button("CANCELAR",Color.rgb(30,17,20));cancel.setAllCaps(false);
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(0,dp(48),1f);ap.setMargins(dp(3),dp(6),dp(3),0);actions.addView(type,ap);actions.addView(cancel,new LinearLayout.LayoutParams(ap));panel.addView(actions);
        type.setOnClickListener(v->{dialog.dismiss();showRoadInput();});cancel.setOnClickListener(v->dialog.dismiss());
        showRoadDialog(dialog,panel);
    }

    private void showRoadInput(){
        final android.app.Dialog dialog=baseRoadDialog();
        LinearLayout panel=roadDialogPanel();
        panel.addView(t("ESTRADA PLAY · RÁDIO",9,GOLD,true));
        panel.addView(t("DIGITAR RODOVIA",23,TEXT,true));
        panel.addView(t("Informe no formato BR-101, RJ-116, MG-050 ou ES-060.",11,MUTED,false));
        EditText input=new EditText(this);input.setSingleLine(true);input.setHint("Ex.: BR-101");input.setTextColor(TEXT);input.setHintTextColor(MUTED);input.setTextSize(17);input.setPadding(dp(14),0,dp(14),0);input.setBackground(box(Color.rgb(25,10,14),10,Color.rgb(94,43,50)));
        LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(-1,dp(54));ip.setMargins(0,dp(12),0,dp(10));panel.addView(input,ip);
        LinearLayout actions=row();Button use=button("USAR RODOVIA",RED);use.setAllCaps(false);Button cancel=button("CANCELAR",Color.rgb(30,17,20));cancel.setAllCaps(false);
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(0,dp(50),1f);bp.setMargins(dp(3),0,dp(3),0);actions.addView(use,bp);actions.addView(cancel,new LinearLayout.LayoutParams(bp));panel.addView(actions);
        use.setOnClickListener(v->{String selected=KnownRoadCatalog.canonical(String.valueOf(input.getText()));if(selected.isEmpty()){input.setError("Use, por exemplo, BR-101");return;}dialog.dismiss();applyRoadSupport(selected);});
        cancel.setOnClickListener(v->dialog.dismiss());
        showRoadDialog(dialog,panel);
        input.requestFocus();
    }

    private android.app.Dialog baseRoadDialog(){android.app.Dialog d=new android.app.Dialog(this);d.requestWindowFeature(Window.FEATURE_NO_TITLE);d.setCanceledOnTouchOutside(true);return d;}
    private LinearLayout roadDialogPanel(){LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);p.setPadding(dp(18),dp(16),dp(18),dp(16));p.setBackground(box(Color.rgb(18,9,12),18,Color.rgb(92,43,50)));return p;}
    private void showRoadDialog(android.app.Dialog dialog,View panel){
        dialog.setContentView(panel);dialog.show();Window w=dialog.getWindow();if(w==null)return;w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        int sw=getResources().getDisplayMetrics().widthPixels,sh=getResources().getDisplayMetrics().heightPixels;int width=Math.min(sw-dp(24),dp(520));int height=Math.min((int)(sh*.78f),dp(620));w.setLayout(width,height);w.setGravity(Gravity.CENTER);
    }
'''
replace_once(radio, old, new)

print("Universal 1.7.6 radio panel patch applied")

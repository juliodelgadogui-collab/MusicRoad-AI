from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "estrada-play-comunista-app"
JAVA = APP / "app/src/main/java/com/estradaplay/comunista"


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old[:100]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# Version 1.7.7
build = APP / "app/build.gradle"
replace_once(build, "versionCode 176", "versionCode 177")
replace_once(build, "versionName '1.7.6'", "versionName '1.7.7'")

# Expand the visible fallback catalog to all four test states and allow clearing a manual choice.
catalog = JAVA / "KnownRoadCatalog.java"
replace_once(
    catalog,
    '            "MG-010", "MG-050", "MG-135", "MG-167", "MG-179", "MG-184", "MG-188", "MG-290", "MG-353", "MG-458"\n',
    '            "MG-010", "MG-050", "MG-135", "MG-167", "MG-179", "MG-184", "MG-188", "MG-290", "MG-353", "MG-458",\n'
    '            "SP-055", "SP-070", "SP-075", "SP-125", "SP-150", "SP-160", "SP-270", "SP-280", "SP-300", "SP-310", "SP-330", "SP-348", "SP-425"\n'
)
replace_once(
    catalog,
    '    static String selected(Context c) {\n        return c == null ? "" : canonical(c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, ""));\n    }\n',
    '    static String selected(Context c) {\n        return c == null ? "" : canonical(c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, ""));\n    }\n\n'
    '    // ROAD_SELECTION_V177: explicit manual choice can also be returned to automatic mode.\n'
    '    static void clear(Context c) {\n        if (c != null) c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY).apply();\n    }\n'
)

# Make manual selection authoritative in the radio service and expose it in state broadcasts.
service = JAVA / "RoadRadioService.java"
replace_once(
    service,
    'else if(ACTION_SET_ROAD.equals(a)){String r=KnownRoadCatalog.canonical(i.getStringExtra("road"));if(!r.isEmpty()){manualRoad=r;KnownRoadCatalog.select(this,r);identity=null;if(wanted)io.execute(this::resolveAndSyncSafe);setStatus("Rodovia de apoio: "+r+" · confirmando trecho pelo GPS…");}}',
    'else if(ACTION_SET_ROAD.equals(a)){String r=KnownRoadCatalog.canonical(i.getStringExtra("road"));if(r.isEmpty()){manualRoad="";KnownRoadCatalog.clear(this);identity=null;if(wanted)io.execute(this::resolveAndSyncSafe);setStatus("Identificação automática de rodovia ativada.");}else{manualRoad=r;KnownRoadCatalog.select(this,r);identity=null;if(wanted)io.execute(this::resolveAndSyncSafe);setStatus("Rodovia escolhida: "+r+" · confirmando trecho pelo GPS…");}}'
)
replace_once(
    service,
    '    private void resolveAndSync() throws Exception {if(!wanted)return;if(!ensureSession()){setStatus("Entre novamente no Estrada Play para usar o rádio.");return;}long now=System.currentTimeMillis();if(identity==null||now-lastResolveAt>30000L){lastResolveAt=now;RoadIdentityResolver.Identity next=RoadIdentityResolver.resolve(roads,lat,lon,heading);if(next==null&&!autoRoadHint.isEmpty())next=RoadIdentityResolver.fromKnownRoad(autoRoadHint,lat,lon,heading);if(next==null&&!manualRoad.isEmpty())next=RoadIdentityResolver.fromKnownRoad(manualRoad,lat,lon,heading);if(next==null){setStatus("Não identifiquei a rodovia. Defina uma rodovia de apoio.");return;}if(identity==null||!identity.roomKey.equals(next.roomKey)){leaveNetworkOnly();identity=next;join();return;}identity=next;}if(!joined){join();return;}heartbeat();}\n',
    '    // MANUAL_ROAD_OVERRIDE_V177: an explicit driver choice wins until AUTOMATIC mode is selected again.\n'
    '    private void resolveAndSync() throws Exception {if(!wanted)return;if(!ensureSession()){setStatus("Entre novamente no Estrada Play para usar o rádio.");return;}long now=System.currentTimeMillis();if(identity==null||now-lastResolveAt>30000L){lastResolveAt=now;RoadIdentityResolver.Identity next=null;if(!manualRoad.isEmpty())next=RoadIdentityResolver.fromKnownRoad(manualRoad,lat,lon,heading);if(next==null)next=RoadIdentityResolver.resolve(roads,lat,lon,heading);if(next==null&&!autoRoadHint.isEmpty())next=RoadIdentityResolver.fromKnownRoad(autoRoadHint,lat,lon,heading);if(next==null){setStatus("Não identifiquei a rodovia. Defina uma rodovia de apoio.");return;}if(identity==null||!identity.roomKey.equals(next.roomKey)){leaveNetworkOnly();identity=next;join();return;}identity=next;}if(!joined){join();return;}heartbeat();}\n'
)
replace_once(
    service,
    'i.putExtra("room_label",identity==null?"":identity.label);i.putExtra("status",status+(safetyMuted?" · alerta de segurança em prioridade":""));',
    'i.putExtra("room_label",identity==null?"":identity.label);i.putExtra("selected_road",manualRoad);i.putExtra("road_mode",manualRoad.isEmpty()?"auto":"manual");i.putExtra("status",status+(safetyMuted?" · alerta de segurança em prioridade":""));'
)

# Keep the chosen road visible in the UI instead of immediately returning to IDENTIFICANDO.
activity = JAVA / "RoadRadioActivity.java"
replace_once(
    activity,
    '        chooseRoad=button("DEFINIR RODOVIA DE APOIO",Color.rgb(42,22,25));p.addView(chooseRoad,new LinearLayout.LayoutParams(-1,dp(48)));chooseRoad.setOnClickListener(v->showRoadPicker());\n',
    '        String selectedRoad=KnownRoadCatalog.selected(this);\n'
    '        chooseRoad=button(selectedRoad.isEmpty()?"DEFINIR RODOVIA DE APOIO":"ALTERAR RODOVIA · "+selectedRoad,Color.rgb(42,22,25));p.addView(chooseRoad,new LinearLayout.LayoutParams(-1,dp(48)));chooseRoad.setOnClickListener(v->showRoadPicker());\n'
    '        if(!selectedRoad.isEmpty())room.setText(selectedRoad+" · RODOVIA DE APOIO");\n'
)
replace_once(
    activity,
    '        TextView choose=t("ESCOLHA UMA RODOVIA",10,GOLD,true);choose.setPadding(0,dp(12),0,dp(7));panel.addView(choose);\n\n        ScrollView scroll=new ScrollView(this);',
    '        String current=KnownRoadCatalog.selected(this);\n'
    '        Button automatic=button(current.isEmpty()?"✓ IDENTIFICAÇÃO AUTOMÁTICA":"USAR IDENTIFICAÇÃO AUTOMÁTICA",Color.rgb(31,25,20));automatic.setAllCaps(false);\n'
    '        LinearLayout.LayoutParams autoLp=new LinearLayout.LayoutParams(-1,dp(48));autoLp.setMargins(0,dp(10),0,dp(8));panel.addView(automatic,autoLp);automatic.setOnClickListener(v->{dialog.dismiss();clearRoadSupport();});\n'
    '        TextView choose=t("ESCOLHA UMA RODOVIA",10,GOLD,true);choose.setPadding(0,dp(6),0,dp(7));panel.addView(choose);\n\n        ScrollView scroll=new ScrollView(this);'
)
replace_once(
    activity,
    '            Button option=button(road,Color.rgb(38,16,21));option.setAllCaps(false);',
    '            Button option=button(road.equals(current)?"✓ "+road:road,Color.rgb(38,16,21));option.setAllCaps(false);'
)
replace_once(
    activity,
    '    private void applyRoadSupport(String raw){\n',
    '    // ROAD_SELECTION_V177: manual choice remains visible and active until the driver returns to automatic mode.\n'
    '    private void clearRoadSupport(){\n'
    '        KnownRoadCatalog.clear(this);\n'
    '        room.setText("IDENTIFICANDO RODOVIA…");\n'
    '        chooseRoad.setText("DEFINIR RODOVIA DE APOIO");\n'
    '        status.setText("Identificação automática de rodovia ativada.");\n'
    '        Intent i=new Intent(this,RoadRadioService.class).setAction(RoadRadioService.ACTION_SET_ROAD);i.putExtra("road","");\n'
    '        if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);\n'
    '        Toast.makeText(this,"Identificação automática ativada.",Toast.LENGTH_SHORT).show();\n'
    '    }\n\n'
    '    private void applyRoadSupport(String raw){\n'
)
replace_once(
    activity,
    '        room.setText(selected+" · CONFIRMANDO TRECHO…");\n        status.setText("Usando rodovia de apoio enquanto o GPS confirma o trecho.");\n',
    '        room.setText(selected+" · RODOVIA DE APOIO");\n'
    '        chooseRoad.setText("ALTERAR RODOVIA · "+selected);\n'
    '        status.setText("Rodovia escolhida: "+selected+". O GPS só define o trecho local.");\n'
    '        Toast.makeText(this,selected+" selecionada.",Toast.LENGTH_SHORT).show();\n'
)
replace_once(
    activity,
    'String label=i.getStringExtra("room_label");String st=i.getStringExtra("status");boolean missing=label==null||label.isEmpty();if(missing&&st!=null&&st.toLowerCase(Locale.ROOT).contains("não identifiquei"))room.setText("RODOVIA NÃO IDENTIFICADA");else room.setText(missing?"IDENTIFICANDO RODOVIA…":label.toUpperCase(Locale.ROOT));int n=i.getIntExtra("participants",0);',
    'String label=i.getStringExtra("room_label");String st=i.getStringExtra("status");String selected=KnownRoadCatalog.canonical(i.getStringExtra("selected_road"));if(selected.isEmpty())selected=KnownRoadCatalog.selected(RoadRadioActivity.this);boolean missing=label==null||label.isEmpty();if(missing&&!selected.isEmpty())room.setText(selected+" · RODOVIA DE APOIO");else if(missing&&st!=null&&st.toLowerCase(Locale.ROOT).contains("não identifiquei"))room.setText("RODOVIA NÃO IDENTIFICADA");else room.setText(missing?"IDENTIFICANDO RODOVIA…":label.toUpperCase(Locale.ROOT));chooseRoad.setText(selected.isEmpty()?"DEFINIR RODOVIA DE APOIO":"ALTERAR RODOVIA · "+selected);int n=i.getIntExtra("participants",0);'
)

print("Estrada Play Comunista Universal 1.7.7 road selection patch applied")

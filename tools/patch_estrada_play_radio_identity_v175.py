from pathlib import Path
import re

ROOT = Path('estrada-play-comunista-app')
JAVA = ROOT / 'app/src/main/java/com/estradaplay/comunista'

# Build number: keep this fix above the current 1.7.4 source.
p = ROOT / 'app/build.gradle'
s = p.read_text(encoding='utf-8')
s = re.sub(r'(?m)^\s*versionCode\s+\d+\s*$', '        versionCode 175', s, count=1)
s = re.sub(r"(?m)^\s*versionName\s+'[^']+'\s*$", "        versionName '1.7.5'", s, count=1)
p.write_text(s, encoding='utf-8')

# Radio screen: remove duplicate page title, make road fallback functional and explicit.
p = JAVA / 'RoadRadioActivity.java'
s = p.read_text(encoding='utf-8')

s = s.replace(
    'TextView k=t("ESTRADA PLAY · COMUNICAÇÃO",10,GOLD,true);k.setLetterSpacing(.13f);p.addView(k);p.addView(t("RÁDIO DA RODOVIA",29,TEXT,true));p.addView(t("Sala automática pela rodovia e pelo trecho local. Motoristas distantes na mesma BR ficam em salas diferentes. Áudio WebRTC vai direto entre os aparelhos e não fica gravado no servidor.",12,MUTED,false));',
    'TextView k=t("COMUNICAÇÃO DO TRECHO",9,GOLD,true);k.setLetterSpacing(.13f);p.addView(k);p.addView(t("Sala automática pela rodovia e pelo trecho local. O áudio vai direto entre os aparelhos e não fica gravado no servidor.",11,MUTED,false));'
)
s = s.replace('chooseRoad=button("ESCOLHER RODOVIA · SE NÃO IDENTIFICAR",Color.rgb(42,22,25));',
              'chooseRoad=button("DEFINIR RODOVIA DE APOIO",Color.rgb(42,22,25));')

picker_re = re.compile(r'    private void showRoadPicker\(\)\{.*?\n    \}\n\n    private void enter\(\)', re.S)
new_picker = r'''    private void showRoadPicker(){
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

    private void applyRoadSupport(String raw){
        String selected=KnownRoadCatalog.canonical(raw);
        if(selected.isEmpty()){Toast.makeText(this,"Rodovia inválida. Use, por exemplo, BR-101.",Toast.LENGTH_SHORT).show();return;}
        KnownRoadCatalog.select(this,selected);
        Intent i=new Intent(this,RoadRadioService.class).setAction(RoadRadioService.ACTION_SET_ROAD);
        i.putExtra("road",selected);
        if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);
        room.setText(selected+" · CONFIRMANDO TRECHO…");
        status.setText("Usando rodovia de apoio enquanto o GPS confirma o trecho.");
    }

    private void enter()'''
if not picker_re.search(s):
    raise SystemExit('RoadRadioActivity showRoadPicker block not found')
s = picker_re.sub(new_picker, s, count=1)

old_rx = 'String label=i.getStringExtra("room_label");room.setText(label==null||label.isEmpty()?"IDENTIFICANDO RODOVIA…":label.toUpperCase(Locale.ROOT));int n=i.getIntExtra("participants",0);people.setText(n+" motorista"+(n==1?"":"s")+" no trecho");String st=i.getStringExtra("status");status.setText(st==null?"":st);'
new_rx = 'String label=i.getStringExtra("room_label");String st=i.getStringExtra("status");boolean missing=label==null||label.isEmpty();if(missing&&st!=null&&st.toLowerCase(Locale.ROOT).contains("não identifiquei"))room.setText("RODOVIA NÃO IDENTIFICADA");else room.setText(missing?"IDENTIFICANDO RODOVIA…":label.toUpperCase(Locale.ROOT));int n=i.getIntExtra("participants",0);people.setText(n+" motorista"+(n==1?"":"s")+" no trecho");status.setText(st==null?"":st);'
if old_rx not in s:
    raise SystemExit('RoadRadioActivity state receiver block not found')
s = s.replace(old_rx, new_rx, 1)
p.write_text(s, encoding='utf-8')

# Radio service: reuse the road identity already discovered by the safety/road service.
p = JAVA / 'RoadRadioService.java'
s = p.read_text(encoding='utf-8')

s = s.replace('private ApiClient api;private OfflineRoadStore roads;private RoadIdentityResolver.Identity identity;private String manualRoad="";private boolean wanted,joined,muted,ptt,safetyMuted,registered;',
              'private ApiClient api;private OfflineRoadStore roads;private RoadIdentityResolver.Identity identity;private String manualRoad="",autoRoadHint="";private boolean wanted,joined,muted,ptt,safetyMuted,registered;', 1)

old_road_rx = 'double a=i.getDoubleExtra("lat",Double.NaN),b=i.getDoubleExtra("lon",Double.NaN);float h=i.getFloatExtra("heading",Float.NaN);if(Double.isFinite(a)&&Double.isFinite(b)){lat=a;lon=b;}if(Float.isFinite(h))heading=h;if(wanted&&System.currentTimeMillis()-lastResolveAt>30000L)io.execute(RoadRadioService.this::resolveAndSyncSafe);'
new_road_rx = 'double a=i.getDoubleExtra("lat",Double.NaN),b=i.getDoubleExtra("lon",Double.NaN);float h=i.getFloatExtra("heading",Float.NaN);String roadHint=KnownRoadCatalog.canonical(i.getStringExtra("road"));if(!roadHint.isEmpty())autoRoadHint=roadHint;if(Double.isFinite(a)&&Double.isFinite(b)){lat=a;lon=b;}if(Float.isFinite(h))heading=h;if(wanted&&System.currentTimeMillis()-lastResolveAt>30000L)io.execute(RoadRadioService.this::resolveAndSyncSafe);'
if old_road_rx not in s:
    raise SystemExit('RoadRadioService road receiver block not found')
s = s.replace(old_road_rx, new_road_rx, 1)

old_resolve = 'RoadIdentityResolver.Identity next=RoadIdentityResolver.resolve(roads,lat,lon,heading);if(next==null&&!manualRoad.isEmpty())next=RoadIdentityResolver.fromKnownRoad(manualRoad,lat,lon,heading);if(next==null){setStatus("Não identifiquei a rodovia. Escolha uma rodovia cadastrada como apoio.");return;}'
new_resolve = 'RoadIdentityResolver.Identity next=RoadIdentityResolver.resolve(roads,lat,lon,heading);if(next==null&&!autoRoadHint.isEmpty())next=RoadIdentityResolver.fromKnownRoad(autoRoadHint,lat,lon,heading);if(next==null&&!manualRoad.isEmpty())next=RoadIdentityResolver.fromKnownRoad(manualRoad,lat,lon,heading);if(next==null){setStatus("Não identifiquei a rodovia. Defina uma rodovia de apoio.");return;}'
if old_resolve not in s:
    raise SystemExit('RoadRadioService resolve fallback block not found')
s = s.replace(old_resolve, new_resolve, 1)
p.write_text(s, encoding='utf-8')

print('Universal 1.7.5 radio identity fix applied')

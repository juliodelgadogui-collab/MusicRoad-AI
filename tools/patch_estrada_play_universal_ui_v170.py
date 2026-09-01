#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'estrada-play-comunista-app'
JAVA = APP / 'app/src/main/java/com/estradaplay/comunista'


def read(p): return p.read_text(encoding='utf-8')
def write(p, s): p.parent.mkdir(parents=True, exist_ok=True); p.write_text(s, encoding='utf-8')
def once(s, old, new, label):
    if new in s: return s
    if old not in s: raise SystemExit('Trecho nao encontrado: ' + label)
    return s.replace(old, new, 1)

# ---------------------------------------------------------------------------
# Version
# ---------------------------------------------------------------------------
p = APP / 'app/build.gradle'
s = read(p)
s = re.sub(r'versionCode\s+162\b', 'versionCode 170', s, count=1)
s = re.sub(r"versionName\s+'1\.6\.2'", "versionName '1.7.0'", s, count=1)
write(p, s)

# ---------------------------------------------------------------------------
# Manifest: dedicated music player
# ---------------------------------------------------------------------------
p = APP / 'app/src/main/AndroidManifest.xml'
s = read(p)
if '.MusicPlayerActivity' not in s:
    marker = '        <activity android:name=".ServerSettingsActivity" android:exported="false" android:screenOrientation="${appOrientation}" />\n'
    s = once(s, marker, marker + '        <activity android:name=".MusicPlayerActivity" android:exported="false" android:configChanges="orientation|screenSize|keyboardHidden|uiMode" android:screenOrientation="${appOrientation}" />\n', 'manifest music player')
write(p, s)

# ---------------------------------------------------------------------------
# Road cockpit: remove the 1.6 floating slab and open the real player directly.
# ---------------------------------------------------------------------------
p = JAVA / 'RoadMapActivity.java'
s = read(p)
s = s.replace('        installUniversalDriveWidgets(width,height);\n', '')
s = s.replace('music.setOnClickListener(v -> openMain("music"));', 'music.setOnClickListener(v -> startActivity(new Intent(this, MusicPlayerActivity.class)));')
# Make the right-side audio card shorter and more useful.
s = s.replace('TextView mediaTitle = label("Sua música continua com você", compact ? 14 : 17, TEXT, true);', 'TextView mediaTitle = label("Player de bordo", compact ? 14 : 17, TEXT, true);')
s = s.replace('TextView mediaBody = label("Os alertas reduzem o áudio e falam por cima sem encerrar a reprodução.", compact ? 9 : 10, MUTED, false);', 'TextView mediaBody = label("Controle a música sem sair do cockpit. Alertas de segurança continuam com prioridade.", compact ? 9 : 10, MUTED, false);')
write(p, s)

# ---------------------------------------------------------------------------
# Main screen: music always opens as a player, never auto-redirects to download.
# ---------------------------------------------------------------------------
p = JAVA / 'MainActivity.java'
s = read(p)
s = s.replace('if ("music".equals(target)) showMusic();', 'if ("music".equals(target)) { startActivity(new Intent(this, MusicPlayerActivity.class)); finish(); }')
old_listener = '''        Button music = compactButton(hasDownloaded ? "ABRIR" : "PREPARAR"); media.addView(music, lp(96, 48));\n        music.setOnClickListener(v -> {\n            if (!hasDownloaded) { if (online()) loadCatalogAndOpenChooser(false); else toast("Conecte-se para escolher músicas."); }\n            else showMusic();\n        });\n'''
new_listener = '''        Button music = compactButton("ABRIR PLAYER"); media.addView(music, lp(112, 48));\n        music.setOnClickListener(v -> startActivity(new Intent(this, MusicPlayerActivity.class)));\n'''
s = once(s, old_listener, new_listener, 'home music player')
s = s.replace('close.setOnClickListener(v -> showMusic());', 'close.setOnClickListener(v -> startActivity(new Intent(this, MusicPlayerActivity.class)));')
write(p, s)

# ---------------------------------------------------------------------------
# Player service can report current state when the dedicated player opens.
# ---------------------------------------------------------------------------
p = JAVA / 'PlayerService.java'
s = read(p)
if 'ACTION_QUERY_STATE' not in s:
    s = once(s,
        '    static final String ACTION_STATE = "com.estradaplay.comunista.PLAYER_STATE";\n',
        '    static final String ACTION_STATE = "com.estradaplay.comunista.PLAYER_STATE";\n    static final String ACTION_QUERY_STATE = "com.estradaplay.comunista.PLAYER_QUERY_STATE";\n',
        'player query constant')
    s = once(s,
        '        else if (ACTION_UNDUCK.equals(action)) { alertDuck = 1f; applyVolume(); }\n',
        '        else if (ACTION_UNDUCK.equals(action)) { alertDuck = 1f; applyVolume(); }\n        else if (ACTION_QUERY_STATE.equals(action)) broadcastCurrent();\n',
        'player query branch')
    s = once(s,
        '    private void broadcast(String title, boolean playing, String state) {\n',
        '''    private void broadcastCurrent() {\n        Track t = current();\n        boolean playing = false;\n        try { playing = player != null && prepared && player.isPlaying(); } catch (Throwable ignored) {}\n        broadcast(t == null ? "" : t.title, playing, t == null ? "PRONTO" : (playing ? "OFFLINE" : "PAUSADO"));\n    }\n\n    private void broadcast(String title, boolean playing, String state) {\n''',
        'player broadcast current')
    s = once(s,
        '        i.putExtra("state", state == null ? "" : state);\n',
        '        i.putExtra("state", state == null ? "" : state);\n        i.putExtra("track_key", t == null ? "" : t.key());\n',
        'player state key')
write(p, s)

# ---------------------------------------------------------------------------
# Dedicated responsive automotive music player.
# ---------------------------------------------------------------------------
write(JAVA / 'MusicPlayerActivity.java', r'''package com.estradaplay.comunista;

import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.activity.ComponentActivity;
import java.util.*;
import java.util.concurrent.*;

/** Dedicated music surface. Opening Music never redirects to the download manager. */
public final class MusicPlayerActivity extends ComponentActivity {
    private final int BG=Color.rgb(8,5,7),SURFACE=Color.rgb(18,9,12),SURFACE2=Color.rgb(29,14,18),BORDER=Color.rgb(76,38,44);
    private final int TEXT=Color.rgb(246,238,224),MUTED=Color.rgb(174,151,146),RED=Color.rgb(190,18,38),GREEN=Color.rgb(72,212,134),GOLD=Color.rgb(226,185,76);
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private LinearLayout root, listBox; private TextView title,artist,state,count; private LibraryStore library; private boolean registered;

    private final BroadcastReceiver playerState=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
        String t=i.getStringExtra("title"),a=i.getStringExtra("artist"),s=i.getStringExtra("state");
        boolean playing=i.getBooleanExtra("playing",false);
        if(title!=null)title.setText(t==null||t.trim().isEmpty()?"Escolha uma música":t.trim());
        if(artist!=null)artist.setText(a==null||a.trim().isEmpty()?"Biblioteca offline":a.trim());
        if(state!=null){state.setText(playing?"● TOCANDO":(s==null||s.isEmpty()?"PRONTO":s.toUpperCase(Locale.ROOT)));state.setTextColor(playing?GREEN:MUTED);}
    }};

    @Override protected void onCreate(Bundle b){super.onCreate(b);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);library=new LibraryStore(this);build();loadTracks();register();queryPlayer();}
    @Override public void onConfigurationChanged(android.content.res.Configuration c){super.onConfigurationChanged(c);build();loadTracks();queryPlayer();}
    @Override protected void onDestroy(){if(registered)try{unregisterReceiver(playerState);}catch(Throwable ignored){}io.shutdownNow();super.onDestroy();}

    private void build(){
        FrameLayout frame=new FrameLayout(this);frame.setBackgroundColor(BG);setContentView(frame);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);frame.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(18),dp(14),dp(18),dp(24));scroll.addView(root,new ScrollView.LayoutParams(-1,-2));
        LinearLayout head=row();head.setGravity(Gravity.CENTER_VERTICAL);Button back=small("‹ MAPA");head.addView(back,new LinearLayout.LayoutParams(dp(92),dp(46)));back.setOnClickListener(v->finish());
        LinearLayout brand=col();brand.addView(over("ÁUDIO DE BORDO",RED));brand.addView(text("Música",27,TEXT,true));head.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        Button manage=small("BIBLIOTECA");head.addView(manage,new LinearLayout.LayoutParams(dp(112),dp(46)));manage.setOnClickListener(v->openLibrary());root.addView(head);

        boolean landscape=getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout body=landscape?row():col();LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,-2);bp.setMargins(0,dp(14),0,0);root.addView(body,bp);
        LinearLayout player=playerCard();LinearLayout.LayoutParams pp=landscape?new LinearLayout.LayoutParams(0,dp(350),.42f):new LinearLayout.LayoutParams(-1,-2);body.addView(player,pp);
        listBox=col();listBox.setPadding(dp(14),dp(14),dp(14),dp(14));listBox.setBackground(panel(SURFACE,18,BORDER));
        LinearLayout.LayoutParams lp=landscape?new LinearLayout.LayoutParams(0,dp(350),.58f):new LinearLayout.LayoutParams(-1,-2);if(landscape)lp.setMargins(dp(12),0,0,0);else lp.setMargins(0,dp(12),0,0);body.addView(listBox,lp);
        LinearLayout lh=row();lh.setGravity(Gravity.CENTER_VERTICAL);LinearLayout lt=col();lt.addView(over("NO APARELHO",MUTED));count=text("Carregando…",17,TEXT,true);lt.addView(count);lh.addView(lt,new LinearLayout.LayoutParams(0,-2,1));listBox.addView(lh);
        TextView wait=text("Lendo sua biblioteca offline sem bloquear a tela…",12,MUTED,false);LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(-1,-2);wp.setMargins(0,dp(14),0,0);listBox.addView(wait,wp);
    }

    private LinearLayout playerCard(){
        LinearLayout p=col();p.setPadding(dp(20),dp(18),dp(20),dp(18));p.setBackground(panel(SURFACE2,20,RED));
        LinearLayout top=row();top.setGravity(Gravity.CENTER_VERTICAL);TextView mark=text("♪",30,GOLD,true);mark.setGravity(Gravity.CENTER);mark.setBackground(panel(Color.rgb(50,19,25),16,Color.rgb(113,47,55)));top.addView(mark,new LinearLayout.LayoutParams(dp(64),dp(64)));
        LinearLayout meta=col();state=over("PRONTO",MUTED);meta.addView(state);title=text("Escolha uma música",22,TEXT,true);title.setMaxLines(2);meta.addView(title);artist=text("Biblioteca offline",12,MUTED,false);meta.addView(artist);top.addView(meta,new LinearLayout.LayoutParams(0,-2,1));margins(meta,dp(14),0,0,0);p.addView(top);
        View spacer=new View(this);p.addView(spacer,new LinearLayout.LayoutParams(1,0,1));
        LinearLayout controls=row();controls.setGravity(Gravity.CENTER);Button prev=control("‹‹",false),play=control("▶ Ⅱ",true),next=control("››",false);controls.addView(prev,new LinearLayout.LayoutParams(dp(64),dp(56)));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(96),dp(56));cp.setMargins(dp(10),0,dp(10),0);controls.addView(play,cp);controls.addView(next,new LinearLayout.LayoutParams(dp(64),dp(56)));p.addView(controls);
        prev.setOnClickListener(v->command(PlayerService.ACTION_PREVIOUS,null,null));play.setOnClickListener(v->command(PlayerService.ACTION_TOGGLE,null,null));next.setOnClickListener(v->command(PlayerService.ACTION_NEXT,null,null));
        TextView note=text("Alertas de segurança podem reduzir temporariamente a música, sem fechar o player.",10,MUTED,false);LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,-2);np.setMargins(0,dp(13),0,0);p.addView(note,np);return p;
    }

    private void loadTracks(){io.execute(()->{List<Track> tracks=library.downloadedTracks();runOnUiThread(()->renderTracks(tracks));});}
    private void renderTracks(List<Track> tracks){if(listBox==null)return;while(listBox.getChildCount()>1)listBox.removeViewAt(1);int n=tracks==null?0:tracks.size();if(count!=null)count.setText(n+" "+(n==1?"música offline":"músicas offline"));
        if(n==0){LinearLayout empty=col();empty.setPadding(0,dp(20),0,0);empty.addView(text("Nenhuma música baixada",19,TEXT,true));empty.addView(text("O player continua disponível. Adicione músicas quando quiser; ele não vai mais te mandar para downloads sozinho.",12,MUTED,false));Button add=primary("ADICIONAR MÚSICAS");LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,dp(54));ap.setMargins(0,dp(18),0,0);empty.addView(add,ap);add.setOnClickListener(v->openLibrary());listBox.addView(empty);return;}
        ScrollView sv=new ScrollView(this);LinearLayout rows=col();sv.addView(rows,new ScrollView.LayoutParams(-1,-2));LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,dp(270));sp.setMargins(0,dp(10),0,0);listBox.addView(sv,sp);int limit=Math.min(n,120);for(int i=0;i<limit;i++){Track t=tracks.get(i);rows.addView(trackRow(t,i));if(i<limit-1){View d=new View(this);d.setBackgroundColor(BORDER);rows.addView(d,new LinearLayout.LayoutParams(-1,dp(1)));}}if(n>limit)rows.addView(text("Mostrando 120 de "+n+" faixas para manter a tela leve.",10,MUTED,false));}
    private View trackRow(Track t,int pos){LinearLayout r=row();r.setGravity(Gravity.CENTER_VERTICAL);r.setPadding(dp(8),dp(8),dp(6),dp(8));TextView idx=text(String.format(Locale.ROOT,"%02d",pos+1),10,GOLD,true);idx.setGravity(Gravity.CENTER);r.addView(idx,new LinearLayout.LayoutParams(dp(38),dp(44)));LinearLayout m=col();TextView tt=text(t.title,14,TEXT,true);tt.setMaxLines(1);m.addView(tt);TextView aa=text(t.artist,10,MUTED,false);aa.setMaxLines(1);m.addView(aa);r.addView(m,new LinearLayout.LayoutParams(0,-2,1));Button go=control("▶",true);r.addView(go,new LinearLayout.LayoutParams(dp(48),dp(44)));go.setOnClickListener(v->command(PlayerService.ACTION_PLAY_TRACK,t.key(),"__ALL__"));r.setOnClickListener(v->command(PlayerService.ACTION_PLAY_TRACK,t.key(),"__ALL__"));return r;}

    private void register(){if(registered)return;IntentFilter f=new IntentFilter(PlayerService.ACTION_STATE);if(Build.VERSION.SDK_INT>=33)registerReceiver(playerState,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(playerState,f);registered=true;}
    private void queryPlayer(){try{startService(new Intent(this,PlayerService.class).setAction(PlayerService.ACTION_QUERY_STATE));}catch(Throwable ignored){}}
    private void command(String action,String key,String folder){try{Intent i=new Intent(this,PlayerService.class).setAction(action);if(key!=null)i.putExtra(PlayerService.EXTRA_KEY,key);if(folder!=null)i.putExtra(PlayerService.EXTRA_FOLDER,folder);if(Build.VERSION.SDK_INT>=26&&PlayerService.ACTION_PLAY_TRACK.equals(action))startForegroundService(i);else startService(i);}catch(Throwable e){Toast.makeText(this,"Não consegui iniciar o player agora.",Toast.LENGTH_SHORT).show();}}
    private void openLibrary(){Intent i=new Intent(this,MainActivity.class);i.putExtra("open","library");startActivity(i);}

    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}private LinearLayout col(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private TextView text(String v,float s,int c,boolean b){TextView t=new TextView(this);t.setText(v);t.setTextSize(s);t.setTextColor(c);t.setGravity(Gravity.CENTER_VERTICAL);t.setLineSpacing(0,1.06f);if(b)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}private TextView over(String v,int c){TextView t=text(v,9,c,true);t.setLetterSpacing(.12f);return t;}
    private Button small(String v){Button b=control(v,false);b.setTextSize(9);return b;}private Button primary(String v){return control(v,true);}private Button control(String v,boolean pri){Button b=new Button(this);b.setText(v);b.setAllCaps(false);b.setTextColor(TEXT);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setTextSize(12);b.setStateListAnimator(null);b.setBackground(panel(pri?RED:Color.rgb(35,18,22),14,pri?0:BORDER));return b;}
    private GradientDrawable panel(int color,int radius,int stroke){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));if(stroke!=0)g.setStroke(dp(1),stroke);return g;}private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}private void margins(View v,int l,int t,int r,int b){ViewGroup.MarginLayoutParams p=(ViewGroup.MarginLayoutParams)v.getLayoutParams();p.setMargins(l,t,r,b);v.setLayoutParams(p);}
}
''')

# ---------------------------------------------------------------------------
# Central de bordo: grouped cards + compact quick settings.
# ---------------------------------------------------------------------------
write(JAVA / 'DriveToolsActivity.java', r'''package com.estradaplay.comunista;

import android.content.*;import android.graphics.*;import android.graphics.drawable.GradientDrawable;import android.os.*;import android.view.*;import android.widget.*;import androidx.activity.ComponentActivity;import java.util.*;

public final class DriveToolsActivity extends ComponentActivity {
    private final int BG=Color.rgb(8,5,7),SURFACE=Color.rgb(18,9,12),SURFACE2=Color.rgb(28,14,18),BORDER=Color.rgb(76,38,44),TEXT=Color.rgb(246,238,224),MUTED=Color.rgb(174,151,146),RED=Color.rgb(190,18,38),GREEN=Color.rgb(72,212,134),GOLD=Color.rgb(226,185,76);
    private LinearLayout page;
    @Override protected void onCreate(Bundle b){super.onCreate(b);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);build();}
    private void build(){ScrollView sv=new ScrollView(this);sv.setFillViewport(true);sv.setOverScrollMode(View.OVER_SCROLL_NEVER);page=col();page.setPadding(dp(18),dp(16),dp(18),dp(28));page.setBackgroundColor(BG);sv.addView(page);setContentView(sv);
        LinearLayout head=row();head.setGravity(Gravity.CENTER_VERTICAL);Button back=btn("‹ VOLTAR",false);head.addView(back,new LinearLayout.LayoutParams(dp(94),dp(46)));back.setOnClickListener(v->finish());LinearLayout title=col();title.addView(over("CENTRAL DE BORDO",RED));title.addView(text("Controles da viagem",27,TEXT,true));title.addView(text("Só o que você precisa, organizado por função.",11,MUTED,false));head.addView(title,new LinearLayout.LayoutParams(0,-2,1));page.addView(head);
        addStatusStrip();section("VIAGEM");grid(new Tool[]{new Tool("PLANEJAR VIAGEM","Destino, distância e custo",TripPlannerActivity.class,GOLD),new Tool("HISTÓRICO","Viagens e replay",TripHistoryActivity.class,GREEN),new Tool("VEÍCULO","Consumo e abastecimento",VehicleCostActivity.class,RED),new Tool("FAVORITOS","Postos e paradas salvas",RoadFavoritesActivity.class,GOLD)});
        section("ESTRADA");grid(new Tool[]{new Tool("OFFLINE","RJ · MG · ES e teste local",OfflineCenterActivity.class,GREEN),new Tool("RADARES","Confirmações e base coletiva",CollectiveRoadActivity.class,RED),new Tool("SERVIÇOS","Postos, oficinas e hospitais",NearbyServicesActivity.class,GOLD),new Tool("REPORTAR","Registrar ocorrência da via",RoadReportActivity.class,RED)});
        section("BORDO");grid(new Tool[]{new Tool("RÁDIO PTT","Canal da rodovia",RoadRadioActivity.class,GREEN),new Tool("HUD","Projeção no para-brisa",HudActivity.class,GOLD),new Tool("DASHCAM","Câmera e salvar momento",CameraActivity.class,RED),new Tool("COPILOTO","Comandos de voz",VoiceCommandActivity.class,GREEN)});
        section("AJUSTES RÁPIDOS");toggle("MODO NOTURNO","Escurecer instrumentos automaticamente","auto_night",DriveSettings.autoNight(this));toggle("CHUVA AUTOMÁTICA","Aumentar antecedência quando houver precipitação","rain_auto",DriveSettings.autoRain(this));toggle("PROTEGER VÍDEO EM IMPACTO","Preservar trecho da dashcam quando estiver aberta","protect_impact_video",DriveSettings.protectImpactVideo(this));toggle("BASE COLETIVA","Compartilhar somente eventos leves da estrada","collective_enabled",DriveSettings.collectiveEnabled(this));
        LinearLayout maintenance=row();Button voice=btn("DIAGNÓSTICO DE VOZ",false);Button server=btn("SERVIDOR",false);maintenance.addView(voice,new LinearLayout.LayoutParams(0,dp(50),1));LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(0,dp(50),1);sp.setMargins(dp(8),0,0,0);maintenance.addView(server,sp);voice.setOnClickListener(v->open(VoiceDiagnosticsActivity.class));server.setOnClickListener(v->open(ServerSettingsActivity.class));LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-1,-2);mp.setMargins(0,dp(14),0,0);page.addView(maintenance,mp);
    }
    private void addStatusStrip(){LinearLayout strip=row();strip.setGravity(Gravity.CENTER_VERTICAL);strip.setPadding(dp(12),dp(10),dp(12),dp(10));strip.setBackground(panel(SURFACE2,14,BORDER));TextView a=over("● PROTEÇÃO",GREEN);strip.addView(a,new LinearLayout.LayoutParams(0,-2,1));TextView b=over(DriveSettings.offlineTestMode(this)?"TESTE OFFLINE":"BASE LOCAL",DriveSettings.offlineTestMode(this)?GOLD:MUTED);b.setGravity(Gravity.RIGHT);strip.addView(b);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(14),0,0);page.addView(strip,p);}
    private void section(String name){TextView s=over(name,MUTED);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(20),0,dp(8));page.addView(s,p);}
    private void grid(Tool[] tools){int cols=getResources().getDisplayMetrics().widthPixels/getResources().getDisplayMetrics().density>=700?3:2;for(int i=0;i<tools.length;i+=cols){LinearLayout r=row();for(int c=0;c<cols;c++){int k=i+c;if(k>=tools.length){r.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));continue;}View card=tool(tools[k]);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(112),1);if(c>0)p.setMargins(dp(8),0,0,0);r.addView(card,p);}LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);if(i>0)rp.setMargins(0,dp(8),0,0);page.addView(r,rp);}}
    private View tool(Tool t){LinearLayout c=col();c.setPadding(dp(14),dp(13),dp(14),dp(12));c.setBackground(panel(SURFACE,16,BORDER));TextView mark=text("●",12,t.accent,true);c.addView(mark);TextView title=text(t.title,15,TEXT,true);c.addView(title);TextView sub=text(t.sub,10,MUTED,false);sub.setMaxLines(2);c.addView(sub,new LinearLayout.LayoutParams(-1,0,1));TextView go=over("ABRIR  ›",t.accent);c.addView(go);c.setClickable(true);c.setOnClickListener(v->open(t.cls));return c;}
    private void toggle(String title,String sub,String key,boolean initial){LinearLayout r=row();r.setGravity(Gravity.CENTER_VERTICAL);r.setPadding(dp(14),dp(11),dp(10),dp(11));r.setBackground(panel(SURFACE,14,BORDER));LinearLayout m=col();m.addView(text(title,14,TEXT,true));m.addView(text(sub,10,MUTED,false));r.addView(m,new LinearLayout.LayoutParams(0,-2,1));Button b=btn(initial?"ATIVO":"DESLIGADO",initial);r.addView(b,new LinearLayout.LayoutParams(dp(104),dp(44)));final boolean[] on={initial};b.setOnClickListener(v->{on[0]=!on[0];DriveSettings.toggle(this,key,on[0]);style(b,on[0]);b.setText(on[0]?"ATIVO":"DESLIGADO");});LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,0,0,dp(8));page.addView(r,p);}
    private void open(Class<?> c){try{startActivity(new Intent(this,c));}catch(Throwable e){Toast.makeText(this,"Essa função não conseguiu abrir. Vou manter a Central funcionando.",Toast.LENGTH_LONG).show();}}
    private Button btn(String v,boolean primary){Button b=new Button(this);b.setAllCaps(false);b.setText(v);b.setTextSize(10);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setStateListAnimator(null);style(b,primary);return b;}private void style(Button b,boolean primary){b.setTextColor(primary?Color.WHITE:TEXT);b.setBackground(panel(primary?RED:SURFACE2,13,primary?0:BORDER));}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}private LinearLayout col(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}private TextView text(String v,float s,int c,boolean bold){TextView t=new TextView(this);t.setText(v);t.setTextSize(s);t.setTextColor(c);t.setGravity(Gravity.CENTER_VERTICAL);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}private TextView over(String v,int c){TextView t=text(v,9,c,true);t.setLetterSpacing(.12f);return t;}private GradientDrawable panel(int c,int r,int stroke){GradientDrawable g=new GradientDrawable();g.setColor(c);g.setCornerRadius(dp(r));if(stroke!=0)g.setStroke(dp(1),stroke);return g;}private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}private static final class Tool{final String title,sub;final Class<?>cls;final int accent;Tool(String t,String s,Class<?>c,int a){title=t;sub=s;cls=c;accent=a;}}
}
''')

# ---------------------------------------------------------------------------
# Trip planner: proper automotive screen; results replace a single result area.
# ---------------------------------------------------------------------------
write(JAVA / 'TripPlannerActivity.java', r'''package com.estradaplay.comunista;

import android.Manifest;import android.content.*;import android.content.pm.PackageManager;import android.graphics.*;import android.graphics.drawable.GradientDrawable;import android.location.*;import android.os.*;import android.view.*;import android.widget.*;import androidx.activity.ComponentActivity;import org.json.*;import java.util.*;import java.util.concurrent.*;

public final class TripPlannerActivity extends ComponentActivity{
    private final int BG=Color.rgb(8,5,7),SURFACE=Color.rgb(18,9,12),SURFACE2=Color.rgb(28,14,18),BORDER=Color.rgb(76,38,44),TEXT=Color.rgb(246,238,224),MUTED=Color.rgb(174,151,146),RED=Color.rgb(190,18,38),GREEN=Color.rgb(72,212,134),GOLD=Color.rgb(226,185,76);private LinearLayout page,result;private final ExecutorService io=Executors.newSingleThreadExecutor();private DestinationStore.Destination destination;
    @Override protected void onCreate(Bundle b){super.onCreate(b);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);build();}@Override protected void onResume(){super.onResume();DestinationStore.Destination fresh=DestinationStore.read(this);if(!DestinationStore.same(destination,fresh)){build();}}@Override protected void onDestroy(){io.shutdownNow();super.onDestroy();}
    private void build(){destination=DestinationStore.read(this);ScrollView sv=new ScrollView(this);sv.setFillViewport(true);sv.setOverScrollMode(View.OVER_SCROLL_NEVER);page=col();page.setPadding(dp(18),dp(16),dp(18),dp(28));page.setBackgroundColor(BG);sv.addView(page);setContentView(sv);
        LinearLayout head=row();head.setGravity(Gravity.CENTER_VERTICAL);Button back=btn("‹ VOLTAR",false);head.addView(back,new LinearLayout.LayoutParams(dp(94),dp(46)));back.setOnClickListener(v->finish());LinearLayout title=col();title.addView(over("VIAGEM",RED));title.addView(text("Planejador",28,TEXT,true));head.addView(title,new LinearLayout.LayoutParams(0,-2,1));page.addView(head);
        LinearLayout dest=card();dest.addView(over(destination==null?"SEM DESTINO":"DESTINO",destination==null?MUTED:GREEN));TextView dt=text(destination==null?"Para onde você vai?":destination.label,21,TEXT,true);dt.setMaxLines(2);dest.addView(dt);dest.addView(text(destination==null?"Defina um destino para calcular distância, tempo, combustível e perigos conhecidos.":"Pronto para calcular o plano com sua posição atual.",11,MUTED,false));Button choose=btn(destination==null?"DEFINIR DESTINO":"MUDAR DESTINO",true);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,dp(54));cp.setMargins(0,dp(14),0,0);dest.addView(choose,cp);choose.setOnClickListener(v->startActivity(new Intent(this,DestinationActivity.class)));LinearLayout.LayoutParams dpv=new LinearLayout.LayoutParams(-1,-2);dpv.setMargins(0,dp(14),0,0);page.addView(dest,dpv);
        LinearLayout actions=row();Button road=btn("SÓ ESTRADA",false);actions.addView(road,new LinearLayout.LayoutParams(0,dp(54),1));road.setOnClickListener(v->{DriveSettings.toggle(this,"only_road_mode",true);DestinationStore.clear(this);startActivity(new Intent(this,RoadMapActivity.class));finish();});if(destination!=null){Button calc=btn("CALCULAR PLANO",true);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(54),1);p.setMargins(dp(8),0,0,0);actions.addView(calc,p);calc.setOnClickListener(v->calculate(destination));}LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,-2);ap.setMargins(0,dp(10),0,0);page.addView(actions,ap);
        result=col();LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.setMargins(0,dp(16),0,0);page.addView(result,rp);renderHint();
    }
    private void renderHint(){result.removeAllViews();LinearLayout h=card();h.addView(over("ANTES DE SAIR",GOLD));h.addView(text("O plano usa sua rota, seu perfil de veículo e a base de segurança já disponível no aparelho.",13,TEXT,true));h.addView(text("Preço de pedágio só aparece quando houver uma fonte confiável; o app não inventa valores.",10,MUTED,false));result.addView(h);}
    private void calculate(DestinationStore.Destination d){if(DriveSettings.offlineTestMode(this)){toast("O cálculo de rota precisa de internet. Os alertas continuam offline.");return;}Location l=last();if(l==null){toast("Ainda não tenho uma posição GPS válida.");return;}result.removeAllViews();LinearLayout loading=card();loading.addView(over("CALCULANDO",RED));loading.addView(text("Montando seu plano de viagem…",18,TEXT,true));loading.addView(text("A análise acontece fora da tela para não travar o app.",10,MUTED,false));result.addView(loading);io.execute(()->{try{RouteEngine.Route r=RouteEngine.fetch(l.getLatitude(),l.getLongitude(),d.lat,d.lon);RoadPackStore store=new RoadPackStore(getApplicationContext());RoadQualityStore quality=new RoadQualityStore(getApplicationContext());Plan p=analyze(r,store,quality);runOnUiThread(()->showPlan(r,p,d));}catch(Throwable e){runOnUiThread(()->{result.removeAllViews();LinearLayout er=card();er.addView(over("ROTA INDISPONÍVEL",RED));er.addView(text("Não consegui calcular agora. Sua proteção de estrada continua funcionando.",13,TEXT,true));result.addView(er);});}});}
    private Plan analyze(RouteEngine.Route r,RoadPackStore store,RoadQualityStore quality){Plan p=new Plan();try{JSONObject root=new JSONObject(r.geoJson);JSONArray f=root.optJSONArray("features");JSONObject g=f==null?null:f.optJSONObject(0);JSONObject geo=g==null?null:g.optJSONObject("geometry");JSONArray coords=geo==null?null:geo.optJSONArray("coordinates");HashSet<String> ids=new HashSet<>();double score=0;int scoreN=0;if(coords!=null){int step=Math.max(1,coords.length()/55);for(int i=0;i<coords.length();i+=step){JSONArray c=coords.optJSONArray(i);if(c==null)continue;double lon=c.optDouble(0),lat=c.optDouble(1);for(RoadHazard h:store.nearby(lat,lon,500)){if(!ids.add(h.id))continue;if("RADAR".equals(h.type))p.radars++;else if("QUEBRA_MOLAS".equals(h.type))p.bumps++;else if("PEDAGIO".equals(h.type))p.tolls++;}score+=quality.scoreNear(lat,lon);scoreN++;}}p.quality=scoreN==0?100:(int)Math.round(score/scoreN);}catch(Throwable ignored){}return p;}
    private void showPlan(RouteEngine.Route r,Plan p,DestinationStore.Destination d){VehicleProfileStore.Profile v=VehicleProfileStore.active(this);double km=r.distanceM/1000.0,liters=km/Math.max(1f,v.kmL),cost=liters*v.fuelPrice;result.removeAllViews();LinearLayout ready=card();ready.addView(over("PLANO PRONTO",GREEN));ready.addView(text(d.label,19,TEXT,true));ready.addView(text(r.summary(),12,MUTED,false));LinearLayout metrics=row();metrics.addView(metric("COMBUSTÍVEL",String.format(Locale.getDefault(),"%.1f L",liters),GOLD),new LinearLayout.LayoutParams(0,dp(82),1));LinearLayout.LayoutParams c=new LinearLayout.LayoutParams(0,dp(82),1);c.setMargins(dp(8),0,0,0);metrics.addView(metric("CUSTO",v.fuelPrice>0?String.format(Locale.getDefault(),"R$ %.2f",cost):"—",GREEN),c);LinearLayout.LayoutParams q=new LinearLayout.LayoutParams(0,dp(82),1);q.setMargins(dp(8),0,0,0);metrics.addView(metric("VIA",p.quality+"/100",RED),q);LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-1,-2);mp.setMargins(0,dp(14),0,0);ready.addView(metrics,mp);ready.addView(text("Radares conhecidos na rota: "+p.radars+"   ·   Quebra-molas: "+p.bumps+"   ·   Pedágios detectados: "+p.tolls,11,TEXT,true));ready.addView(text("Valores de pedágio: indisponíveis sem fonte confiável.",10,MUTED,false));Button go=btn("INICIAR VIAGEM",true);LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(-1,dp(58));gp.setMargins(0,dp(16),0,0);ready.addView(go,gp);go.setOnClickListener(x->{DriveSettings.toggle(this,"only_road_mode",false);startActivity(new Intent(this,RoadMapActivity.class));finish();});result.addView(ready);}
    private View metric(String k,String v,int accent){LinearLayout m=col();m.setGravity(Gravity.CENTER);m.setBackground(panel(SURFACE2,14,BORDER));TextView vv=text(v,17,accent,true);vv.setGravity(Gravity.CENTER);m.addView(vv);TextView kk=over(k,MUTED);kk.setGravity(Gravity.CENTER);m.addView(kk);return m;}
    private Location last(){if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED)return null;try{LocationManager m=(LocationManager)getSystemService(LOCATION_SERVICE);Location best=null;for(String p:new String[]{LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER}){Location x=m.getLastKnownLocation(p);if(x!=null&&(best==null||x.getTime()>best.getTime()))best=x;}return best;}catch(Throwable e){return null;}}
    private LinearLayout card(){LinearLayout c=col();c.setPadding(dp(16),dp(15),dp(16),dp(15));c.setBackground(panel(SURFACE,17,BORDER));return c;}private Button btn(String v,boolean pri){Button b=new Button(this);b.setAllCaps(false);b.setText(v);b.setTextColor(TEXT);b.setTextSize(10);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setStateListAnimator(null);b.setBackground(panel(pri?RED:SURFACE2,14,pri?0:BORDER));return b;}private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}private LinearLayout col(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}private TextView text(String v,float s,int c,boolean b){TextView t=new TextView(this);t.setText(v);t.setTextSize(s);t.setTextColor(c);t.setGravity(Gravity.CENTER_VERTICAL);if(b)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}private TextView over(String v,int c){TextView t=text(v,9,c,true);t.setLetterSpacing(.12f);return t;}private GradientDrawable panel(int c,int r,int stroke){GradientDrawable g=new GradientDrawable();g.setColor(c);g.setCornerRadius(dp(r));if(stroke!=0)g.setStroke(dp(1),stroke);return g;}private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}private static final class Plan{int radars,bumps,tolls,quality=100;}
}
''')

print('Universal 1.7.0 automotive UI patch applied')

#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "estrada-play-comunista-app"
JAVA = APP / "app/src/main/java/com/estradaplay/comunista"


def replace_method(src: str, signature: str, replacement: str) -> str:
    start = src.find(signature)
    if start < 0:
        raise SystemExit(f"signature not found: {signature}")
    brace = src.find("{", start)
    if brace < 0:
        raise SystemExit(f"opening brace not found: {signature}")
    depth = 0
    i = brace
    in_string = False
    in_char = False
    esc = False
    line_comment = False
    block_comment = False
    while i < len(src):
        c = src[i]
        n = src[i + 1] if i + 1 < len(src) else ""
        if line_comment:
            if c == "\n": line_comment = False
        elif block_comment:
            if c == "*" and n == "/": block_comment = False; i += 1
        elif in_string:
            if esc: esc = False
            elif c == "\\": esc = True
            elif c == '"': in_string = False
        elif in_char:
            if esc: esc = False
            elif c == "\\": esc = True
            elif c == "'": in_char = False
        else:
            if c == "/" and n == "/": line_comment = True; i += 1
            elif c == "/" and n == "*": block_comment = True; i += 1
            elif c == '"': in_string = True
            elif c == "'": in_char = True
            elif c == "{": depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    return src[:start] + replacement.rstrip() + src[i + 1:]
        i += 1
    raise SystemExit(f"closing brace not found: {signature}")


HOME = r'''private void showHome() {
        clearDownloadViews();
        roadLiveState = null;
        roadLiveDetail = null;
        nowTitle = null;
        nowArtist = null;
        nowState = null;
        root.removeAllViews();

        // AUTOMOTIVE_RED_GOLD_V200_HOME: visual hierarchy follows an in-dash system,
        // not a generic Android dashboard. Decorative atmosphere is drawn natively.
        EpcBackdropView backdrop = new EpcBackdropView(this);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout page = column();
        page.setPadding(dp(18), dp(12), dp(18), dp(28));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout system = row();
        system.setGravity(Gravity.CENTER_VERTICAL);
        Button menu = compactButton("☰");
        menu.setTextSize(20);
        system.addView(menu, lp(54, 48));
        menu.setOnClickListener(v -> startActivity(new Intent(this, DriveToolsActivity.class)));
        TextView clock = text(new java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(new java.util.Date()), 14, TEXT, true);
        clock.setGravity(Gravity.CENTER);
        system.addView(clock, new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView active = chip(hasLocationPermission() ? "● SISTEMA ATIVO" : "○ GPS PENDENTE",
                hasLocationPermission() ? GREEN : ACCENT,
                hasLocationPermission() ? GREEN_SOFT : ACCENT_SOFT);
        system.addView(active);
        page.addView(system);

        LinearLayout brand = column();
        brand.setGravity(Gravity.CENTER_HORIZONTAL);
        BrandMarkView mark = new BrandMarkView(this);
        brand.addView(mark, lp(68, 68));
        TextView epc = text("EPC", 38, Color.rgb(247, 235, 211), true);
        epc.setGravity(Gravity.CENTER); epc.setLetterSpacing(.10f); brand.addView(epc);
        TextView full = overline("ESTRADA PLAY COMUNISTA", ACCENT); full.setGravity(Gravity.CENTER); brand.addView(full);
        page.addView(brand); margins(brand, 0, 12, 0, 0);

        LinearLayout hero = column();
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView heroTitle = text("CENTRAL\nde VIAGEM", 38, Color.rgb(250, 241, 224), true);
        heroTitle.setGravity(Gravity.CENTER); heroTitle.setLineSpacing(0, .90f); hero.addView(heroTitle);
        TextView heroSub = overline("ROTA  ·  SOM  ·  PROTEÇÃO  ·  COPILOTO", Color.rgb(226,185,76));
        heroSub.setGravity(Gravity.CENTER); hero.addView(heroSub);
        page.addView(hero); margins(hero, 0, 18, 0, 16);

        LinearLayout selector = row();
        selector.setBackground(bg(Color.argb(185, 13, 7, 9), 22, Color.rgb(92, 39, 44)));
        selector.setPadding(dp(2), dp(2), dp(2), dp(2));

        LinearLayout free = column();
        free.setPadding(dp(16), dp(18), dp(16), dp(16));
        free.setGravity(Gravity.CENTER_HORIZONTAL);
        free.setBackground(bg(Color.rgb(112, 12, 27), 20, ACCENT));
        TextView roadGlyph = text("╱╲", 25, Color.rgb(241, 213, 164), true); roadGlyph.setGravity(Gravity.CENTER); free.addView(roadGlyph);
        TextView freeTitle = text("RODAR\nLIVRE", 22, Color.WHITE, true); freeTitle.setGravity(Gravity.CENTER); free.addView(freeTitle); margins(freeTitle,0,8,0,3);
        TextView freeSub = text("Explore sem destino", 10, Color.rgb(220,185,184), false); freeSub.setGravity(Gravity.CENTER); free.addView(freeSub);
        View fsp = new View(this); free.addView(fsp, new LinearLayout.LayoutParams(1,0,1));
        Button freeGo = button("→", true); freeGo.setTextSize(20); free.addView(freeGo, lp(58,48));
        freeGo.setOnClickListener(v -> { DestinationStore.clear(this); openCockpit(); });
        selector.addView(free, new LinearLayout.LayoutParams(0, dp(212), 1));

        LinearLayout route = column();
        route.setPadding(dp(16), dp(18), dp(16), dp(16));
        route.setGravity(Gravity.CENTER_HORIZONTAL);
        route.setBackground(bg(Color.argb(230, 14, 12, 13), 20, Color.rgb(91, 67, 58)));
        TextView target = text("◎", 31, Color.rgb(226,185,76), true); target.setGravity(Gravity.CENTER); route.addView(target);
        TextView routeTitle = text("DEFINIR\nDESTINO", 21, TEXT, true); routeTitle.setGravity(Gravity.CENTER); route.addView(routeTitle); margins(routeTitle,0,7,0,3);
        TextView routeSub = text("Informe um endereço", 10, MUTED, false); routeSub.setGravity(Gravity.CENTER); route.addView(routeSub);
        View rsp = new View(this); route.addView(rsp, new LinearLayout.LayoutParams(1,0,1));
        Button routeGo = button("→", false); routeGo.setTextSize(20); route.addView(routeGo, lp(58,48));
        routeGo.setOnClickListener(v -> startActivity(new Intent(this, DestinationActivity.class)));
        LinearLayout.LayoutParams routeLp = new LinearLayout.LayoutParams(0, dp(212), 1); routeLp.setMargins(dp(3),0,0,0); selector.addView(route, routeLp);
        page.addView(selector);

        LinearLayout protection = row();
        protection.setGravity(Gravity.CENTER_VERTICAL);
        protection.setPadding(dp(14), dp(12), dp(14), dp(12));
        protection.setBackground(bg(Color.argb(226, 14, 11, 12), 15, Color.rgb(54, 91, 65)));
        TextView shield = text("◈", 25, GREEN, true); shield.setGravity(Gravity.CENTER); protection.addView(shield, lp(44,44));
        LinearLayout protectText = column();
        roadLiveState = overline(hasLocationPermission() ? "PROTEÇÃO RODOVIÁRIA ATIVA" : "LOCALIZAÇÃO PENDENTE", hasLocationPermission()?GREEN:ACCENT);
        roadLiveDetail = text(RoadWeatherMonitor.compactStatus(this), 10, MUTED, false);
        protectText.addView(roadLiveState); protectText.addView(roadLiveDetail);
        protection.addView(protectText, new LinearLayout.LayoutParams(0,-2,1)); margins(protectText,10,0,4,0);
        TextView goProtect = text("›", 26, TEXT, true); goProtect.setGravity(Gravity.CENTER); protection.addView(goProtect, lp(38,44));
        protection.setClickable(true); protection.setOnClickListener(v -> openCockpit());
        page.addView(protection); margins(protection,0,12,0,0);

        LinearLayout media = row();
        media.setGravity(Gravity.CENTER_VERTICAL);
        media.setPadding(dp(13), dp(12), dp(13), dp(12));
        media.setBackground(bg(Color.argb(232, 17, 11, 13), 15, BORDER));
        TextView cover = text("★", 24, Color.rgb(226,185,76), true); cover.setGravity(Gravity.CENTER); cover.setBackground(bg(Color.rgb(78,12,24),10,Color.rgb(130,31,45))); media.addView(cover, lp(54,54));
        LinearLayout song = column();
        nowState = overline("MÚSICA OFFLINE", ACCENT); song.addView(nowState);
        nowTitle = text("Biblioteca local", 16, TEXT, true); nowTitle.setMaxLines(1); song.addView(nowTitle);
        nowArtist = text("Toque para abrir o player", 10, MUTED, false); nowArtist.setMaxLines(1); song.addView(nowArtist);
        media.addView(song, new LinearLayout.LayoutParams(0,-2,1)); margins(song,10,0,8,0);
        Button player = button("▶", true); player.setTextSize(19); media.addView(player, lp(54,54));
        player.setOnClickListener(v -> startActivity(new Intent(this, MusicPlayerActivity.class)));
        media.setClickable(true); media.setOnClickListener(v -> startActivity(new Intent(this, MusicPlayerActivity.class)));
        page.addView(media); margins(media,0,9,0,0);

        LinearLayout quick = row();
        Button climate = compactButton("CLIMA  ·  " + shortWeather());
        Button copilot = compactButton("COPILOTO  ·  FALAR");
        quick.addView(climate, new LinearLayout.LayoutParams(0, dp(52), 1));
        LinearLayout.LayoutParams qcp = new LinearLayout.LayoutParams(0, dp(52), 1); qcp.setMargins(dp(8),0,0,0); quick.addView(copilot, qcp);
        climate.setOnClickListener(v -> startActivity(new Intent(this, WeatherActivity.class)));
        copilot.setOnClickListener(v -> startActivity(new Intent(this, VoiceCommandActivity.class)));
        page.addView(quick); margins(quick,0,9,0,0);

        TextView foot = overline("EPC 2.0  ·  CENTRAL AUTOMOTIVA", Color.rgb(118,84,77));
        foot.setGravity(Gravity.CENTER); page.addView(foot); margins(foot,0,18,0,0);
    }

    private String shortWeather() {
        String s = RoadWeatherMonitor.compactStatus(this);
        if (s == null || s.trim().isEmpty()) return "AGUARDANDO";
        s = s.replace('\n',' ').trim();
        return s.length() > 26 ? s.substring(0,26) + "…" : s;
    }'''

CENTRAL = r'''package com.estradaplay.comunista;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;

public final class DriveToolsActivity extends ComponentActivity {
    // AUTOMOTIVE_RED_GOLD_V200_CENTRAL
    private final int BG=Color.rgb(6,4,5),SURFACE=Color.rgb(17,10,12),SURFACE2=Color.rgb(27,14,17),BORDER=Color.rgb(73,35,40),TEXT=Color.rgb(247,239,224),MUTED=Color.rgb(170,145,140),RED=Color.rgb(184,20,38),GREEN=Color.rgb(69,205,126),GOLD=Color.rgb(226,185,76);
    private LinearLayout page;

    @Override protected void onCreate(Bundle b){super.onCreate(b);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);build();}

    private void build(){
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(BG);root.addView(new EpcBackdropView(this),new FrameLayout.LayoutParams(-1,-1));
        ScrollView sv=new ScrollView(this);sv.setFillViewport(true);sv.setOverScrollMode(View.OVER_SCROLL_NEVER);sv.setBackgroundColor(Color.TRANSPARENT);
        page=col();page.setPadding(dp(18),dp(14),dp(18),dp(30));sv.addView(page);root.addView(sv,new FrameLayout.LayoutParams(-1,-1));setContentView(root);

        LinearLayout chrome=row();chrome.setGravity(Gravity.CENTER_VERTICAL);chrome.setPadding(dp(12),dp(9),dp(12),dp(9));chrome.setBackground(panel(Color.argb(225,30,7,13),14,Color.rgb(113,31,42)));
        TextView star=text("★",25,GOLD,true);star.setGravity(Gravity.CENTER);chrome.addView(star,new LinearLayout.LayoutParams(dp(42),dp(42)));
        LinearLayout brand=col();brand.addView(text("EPC",24,TEXT,true));brand.addView(over("ESTRADA PLAY COMUNISTA",RED));chrome.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        TextView live=over("● SISTEMA ATIVO",GREEN);live.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);chrome.addView(live);page.addView(chrome);

        LinearLayout head=row();head.setGravity(Gravity.CENTER_VERTICAL);Button back=btn("‹ VOLTAR",false);head.addView(back,new LinearLayout.LayoutParams(dp(96),dp(48)));back.setOnClickListener(v->finish());
        LinearLayout title=col();title.addView(over("CENTRAL",RED));title.addView(text("Controles da viagem",28,TEXT,true));title.addView(text("Tudo do carro em um único painel.",11,MUTED,false));head.addView(title,new LinearLayout.LayoutParams(0,-2,1));page.addView(head);margins(head,0,18,0,10);

        addStatusStrip();
        section("VIAGEM");grid(new Tool[]{
                new Tool("◎","PLANEJAR VIAGEM","Destino, distância e custo",TripPlannerActivity.class,GOLD),
                new Tool("↺","HISTÓRICO","Viagens e replay",TripHistoryActivity.class,RED),
                new Tool("★","FAVORITOS","Postos e paradas salvas",RoadFavoritesActivity.class,GOLD),
                new Tool("☂","CLIMA","Chuva aqui e ao longo da rota",WeatherActivity.class,GREEN)});
        section("VEÍCULO");grid(new Tool[]{
                new Tool("▣","VEÍCULO","Consumo e abastecimento",VehicleCostActivity.class,RED),
                new Tool("⚙","MANUTENÇÃO","Diário e próximos serviços",MaintenanceActivity.class,GOLD),
                new Tool("$","COMBUSTÍVEL","Preços informados na estrada",FuelCommunityActivity.class,GREEN),
                new Tool("⌁","OBD2","ELM327 · somente leitura",Obd2Activity.class,GREEN)});
        section("ESTRADA");grid(new Tool[]{
                new Tool("!","SOS","Localização e emergência",EmergencyActivity.class,RED),
                new Tool("↓","OFFLINE","Mapa e proteção local",OfflineCenterActivity.class,GREEN),
                new Tool("✦","ESTRADA VIVA","Alertas comunitários",EstradaVivaActivity.class,RED),
                new Tool("◎","RADARES","Base coletiva",CollectiveRoadActivity.class,GOLD),
                new Tool("+","SERVIÇOS","Postos, oficinas, hospitais",NearbyServicesActivity.class,GREEN),
                new Tool("⚑","REPORTAR","Registrar ocorrência",RoadReportActivity.class,RED)});
        section("BORDO");grid(new Tool[]{
                new Tool(")))","RÁDIO PTT","Canal da rodovia",RoadRadioActivity.class,RED),
                new Tool("◆","COMBOIO","Mapa ao vivo entre carros",ConvoyActivity.class,GREEN),
                new Tool("▭","HUD","Projeção no para-brisa",HudActivity.class,GOLD),
                new Tool("●","DASHCAM","Câmera e salvar momento",CameraActivity.class,RED),
                new Tool("≋","COPILOTO","Comandos de voz",VoiceCommandActivity.class,GREEN),
                new Tool("★","PENSAMENTOS","Camada cultural opcional",ThoughtsActivity.class,GOLD)});

        section("SISTEMA");
        toggle("MODO NOTURNO","Escurecer instrumentos automaticamente","auto_night",DriveSettings.autoNight(this));
        toggle("CHUVA AUTOMÁTICA","Antecipa alertas e observa a rota","rain_auto",DriveSettings.autoRain(this));
        toggle("PROTEGER VÍDEO EM IMPACTO","Preserva o trecho da dashcam","protect_impact_video",DriveSettings.protectImpactVideo(this));
        toggle("BASE COLETIVA","Compartilha eventos leves da estrada","collective_enabled",DriveSettings.collectiveEnabled(this));

        LinearLayout maintenance=row();Button voice=btn("DIAGNÓSTICO DE VOZ",false);Button server=btn("SERVIDOR",false);maintenance.addView(voice,new LinearLayout.LayoutParams(0,dp(50),1));LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(0,dp(50),1);sp.setMargins(dp(8),0,0,0);maintenance.addView(server,sp);voice.setOnClickListener(v->open(VoiceDiagnosticsActivity.class));server.setOnClickListener(v->open(ServerSettingsActivity.class));page.addView(maintenance);margins(maintenance,0,10,0,0);
    }

    private void addStatusStrip(){LinearLayout strip=row();strip.setGravity(Gravity.CENTER_VERTICAL);strip.setPadding(dp(13),dp(11),dp(13),dp(11));strip.setBackground(panel(Color.argb(225,16,10,12),14,BORDER));TextView a=over("◈ PROTEÇÃO ATIVA",GREEN);strip.addView(a,new LinearLayout.LayoutParams(0,-2,1));TextView b=over(RoadWeatherMonitor.compactStatus(this),GOLD);b.setGravity(Gravity.RIGHT);b.setMaxLines(1);strip.addView(b,new LinearLayout.LayoutParams(0,-2,1));page.addView(strip);}
    private void section(String name){TextView s=over(name,MUTED);page.addView(s);margins(s,2,19,0,8);}

    private void grid(Tool[] tools){int cols=getResources().getDisplayMetrics().widthPixels/getResources().getDisplayMetrics().density>=760?3:2;for(int i=0;i<tools.length;i+=cols){LinearLayout r=row();for(int c=0;c<cols;c++){int k=i+c;if(k>=tools.length){r.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));continue;}View card=tool(tools[k]);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(104),1);if(c>0)p.setMargins(dp(8),0,0,0);r.addView(card,p);}page.addView(r);if(i+cols<tools.length)margins(r,0,0,0,8);}}

    private View tool(Tool t){LinearLayout c=row();c.setGravity(Gravity.CENTER_VERTICAL);c.setPadding(dp(12),dp(11),dp(11),dp(11));c.setBackground(panel(Color.argb(230,17,10,12),15,BORDER));TextView icon=text(t.icon,17,t.accent,true);icon.setGravity(Gravity.CENTER);icon.setBackground(panel(Color.argb(140,80,12,24),100,Color.argb(130,184,20,38)));c.addView(icon,new LinearLayout.LayoutParams(dp(44),dp(44)));LinearLayout meta=col();meta.addView(text(t.title,14,TEXT,true));TextView sub=text(t.sub,9,MUTED,false);sub.setMaxLines(2);meta.addView(sub);meta.addView(over("ABRIR  ›",t.accent));c.addView(meta,new LinearLayout.LayoutParams(0,-2,1));margins(meta,10,0,0,0);c.setClickable(true);c.setOnClickListener(v->open(t.cls));return c;}

    private void toggle(String title,String sub,String key,boolean initial){LinearLayout r=row();r.setGravity(Gravity.CENTER_VERTICAL);r.setPadding(dp(14),dp(11),dp(10),dp(11));r.setBackground(panel(Color.argb(225,17,10,12),14,BORDER));LinearLayout m=col();m.addView(text(title,13,TEXT,true));m.addView(text(sub,9,MUTED,false));r.addView(m,new LinearLayout.LayoutParams(0,-2,1));Button b=btn(initial?"ATIVO":"DESLIGADO",initial);r.addView(b,new LinearLayout.LayoutParams(dp(104),dp(42)));final boolean[] on={initial};b.setOnClickListener(v->{on[0]=!on[0];DriveSettings.toggle(this,key,on[0]);style(b,on[0]);b.setText(on[0]?"ATIVO":"DESLIGADO");});page.addView(r);margins(r,0,0,0,8);}
    private void open(Class<?> c){try{startActivity(new Intent(this,c));}catch(Throwable e){Toast.makeText(this,"Essa função não conseguiu abrir.",Toast.LENGTH_LONG).show();}}
    private Button btn(String v,boolean primary){Button b=new Button(this);b.setAllCaps(false);b.setText(v);b.setTextSize(9);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setStateListAnimator(null);style(b,primary);return b;}
    private void style(Button b,boolean primary){b.setTextColor(primary?Color.WHITE:TEXT);b.setBackground(panel(primary?RED:Color.argb(220,28,14,17),12,primary?0:BORDER));}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}
    private LinearLayout col(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private TextView text(String v,float s,int c,boolean bold){TextView t=new TextView(this);t.setText(v);t.setTextSize(s);t.setTextColor(c);t.setGravity(Gravity.CENTER_VERTICAL);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private TextView over(String v,int c){TextView t=text(v,8,c,true);t.setLetterSpacing(.12f);return t;}
    private GradientDrawable panel(int c,int r,int stroke){GradientDrawable g=new GradientDrawable();g.setColor(c);g.setCornerRadius(dp(r));if(stroke!=0)g.setStroke(dp(1),stroke);return g;}
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private void margins(View v,int l,int t,int r,int b){if(v.getLayoutParams() instanceof LinearLayout.LayoutParams){LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)v.getLayoutParams();p.setMargins(dp(l),dp(t),dp(r),dp(b));v.setLayoutParams(p);}}
    private static final class Tool{final String icon,title,sub;final Class<?>cls;final int accent;Tool(String i,String t,String s,Class<?>c,int a){icon=i;title=t;sub=s;cls=c;accent=a;}}
}
'''

PORTRAIT = r'''private void buildPortraitUi(int width, int height) {
        int outer = clamp(Math.round(width * 0.018f), dp(7), dp(12));
        FrameLayout mapPane = buildMapPane(height - outer * 2, true);
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(-1, -1);
        mp.setMargins(outer, outer, outer, outer);
        root.addView(mapPane, mp);

        // AUTOMOTIVE_RED_GOLD_V200_NAV: compact vertical rail, inspired by a real head unit.
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setGravity(Gravity.CENTER_HORIZONTAL);
        rail.setPadding(dp(6), dp(7), dp(6), dp(7));
        rail.setBackground(panel(18, Color.argb(238, 10, 7, 8), Color.rgb(83, 45, 43)));
        TextView radioMark=label(")))",13,ACCENT,true);radioMark.setGravity(Gravity.CENTER);rail.addView(radioMark,new LinearLayout.LayoutParams(-1,dp(32)));
        Button ptt = action("PTT", false);
        Button hud = action("HUD", false);
        Button dash = action("DASH", false);
        Button central = action("CENTRAL", false);
        for(Button b:new Button[]{ptt,hud,dash,central}){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(76),dp(48));p.setMargins(0,dp(5),0,0);rail.addView(b,p);}
        ptt.setOnClickListener(v -> startActivity(new Intent(this, RoadRadioActivity.class)));
        hud.setOnClickListener(v -> startActivity(new Intent(this, HudActivity.class)));
        dash.setOnClickListener(v -> startActivity(new Intent(this, CameraActivity.class)));
        central.setOnClickListener(v -> startActivity(new Intent(this, DriveToolsActivity.class)));
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(dp(88), -2, Gravity.LEFT | Gravity.CENTER_VERTICAL);
        rp.setMargins(outer + dp(9), dp(76), 0, 0); root.addView(rail, rp);
        if(Build.VERSION.SDK_INT>=21)rail.setElevation(dp(72));

        Button recenter=action("◎",false);recenter.setTextSize(18);recenter.setOnClickListener(v->{if(roadMap!=null)roadMap.recenter();});
        FrameLayout.LayoutParams rc=new FrameLayout.LayoutParams(dp(54),dp(54),Gravity.RIGHT|Gravity.CENTER_VERTICAL);rc.setMargins(0,dp(112),outer+dp(12),0);root.addView(recenter,rc);if(Build.VERSION.SDK_INT>=21)recenter.setElevation(dp(74));
    }'''

RAIL = r'''private LinearLayout buildRail(int height, boolean compact) {
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = clamp(Math.round(height * 0.018f), dp(7), dp(13));
        rail.setPadding(pad, pad, pad, pad);
        rail.setBackground(panel(22, Color.rgb(12,7,9), Color.rgb(75,35,40)));

        LinearLayout emblem=new LinearLayout(this);emblem.setOrientation(LinearLayout.VERTICAL);emblem.setGravity(Gravity.CENTER);emblem.setBackground(panel(16,Color.rgb(101,10,24),Color.rgb(163,28,43)));
        TextView star=label("★",compact?18:22,Color.rgb(226,185,76),true);star.setGravity(Gravity.CENTER);emblem.addView(star);
        TextView brand=label("EPC",compact?13:16,TEXT,true);brand.setGravity(Gravity.CENTER);emblem.addView(brand);
        rail.addView(emblem,new LinearLayout.LayoutParams(-1,clamp(Math.round(height*.125f),dp(60),dp(78))));
        TextView drive=label("SISTEMA ATIVO",7,GREEN,true);drive.setGravity(Gravity.CENTER);drive.setLetterSpacing(.08f);rail.addView(drive,new LinearLayout.LayoutParams(-1,dp(28)));

        Button estrada = nav("ESTRADA", true, height);
        Button music = nav("MÚSICA", false, height);
        Button radio = nav("RÁDIO", false, height);
        Button trip = nav("VIAGEM", false, height);
        Button central = nav("CENTRAL", false, height);
        for (Button b : new Button[]{estrada,music,radio,trip,central}) {LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, 0, 1f); p.setMargins(0, dp(3), 0, dp(3)); rail.addView(b, p);}
        estrada.setOnClickListener(v -> { if (roadMap != null) roadMap.recenter(); });
        music.setOnClickListener(v -> startActivity(new Intent(this, MusicPlayerActivity.class)));
        radio.setOnClickListener(v -> startActivity(new Intent(this, RoadRadioActivity.class)));
        trip.setOnClickListener(v -> startActivity(new Intent(this, TripPlannerActivity.class)));
        central.setOnClickListener(v -> startActivity(new Intent(this, DriveToolsActivity.class)));
        TextView version = label("v" + BuildConfig.VERSION_NAME, 8, Color.rgb(126,91,87), true); version.setGravity(Gravity.CENTER); rail.addView(version, new LinearLayout.LayoutParams(-1, dp(28)));
        return rail;
    }'''

MAP_PANE = r'''private FrameLayout buildMapPane(int height, boolean compact) {
        FrameLayout pane = new FrameLayout(this);
        pane.setBackground(panel(26, Color.rgb(8,5,7), Color.rgb(73,35,40)));
        pane.setClipToPadding(true);
        roadMap = new RoadMapView(this);
        pane.addView(roadMap, new FrameLayout.LayoutParams(-1, -1));

        int inset = clamp(Math.round(height * 0.022f), dp(9), dp(16));
        int guideH = clamp(Math.round(height * 0.135f), dp(78), dp(104));

        LinearLayout guidance=new LinearLayout(this);guidance.setOrientation(LinearLayout.HORIZONTAL);guidance.setGravity(Gravity.CENTER_VERTICAL);guidance.setPadding(dp(13),dp(9),dp(13),dp(9));guidance.setBackground(panel(18,Color.argb(242,12,8,9),Color.rgb(86,47,43)));
        TextView turn=label(destination==null?"★":"↰",compact?28:34,destination==null?Color.rgb(226,185,76):Color.rgb(255,91,91),true);turn.setGravity(Gravity.CENTER);guidance.addView(turn,new LinearLayout.LayoutParams(dp(58),-1));
        LinearLayout gText=new LinearLayout(this);gText.setOrientation(LinearLayout.VERTICAL);gText.setGravity(Gravity.CENTER_VERTICAL);
        TextView over=label(destination==null?"RODAGEM LIVRE":"PRÓXIMA ORIENTAÇÃO",7,destination==null?GREEN:ACCENT,true);over.setLetterSpacing(.12f);gText.addView(over);
        navInstructionText=label(destination==null?"Proteção ativa · siga a estrada":"Calculando rota…",compact?15:19,TEXT,true);navInstructionText.setMaxLines(2);gText.addView(navInstructionText,new LinearLayout.LayoutParams(-1,0,1));
        navWeatherText=label(RoadWeatherMonitor.compactStatus(this),8,Color.rgb(226,185,76),true);navWeatherText.setMaxLines(1);gText.addView(navWeatherText);
        guidance.addView(gText,new LinearLayout.LayoutParams(0,-1,1));
        gpsText=label("GPS",8,GREEN,true);gpsText.setGravity(Gravity.CENTER);gpsText.setBackground(panel(100,Color.argb(190,18,54,39),0));guidance.addView(gpsText,new LinearLayout.LayoutParams(dp(54),dp(32)));
        FrameLayout.LayoutParams gp=new FrameLayout.LayoutParams(-1,guideH,Gravity.TOP);gp.setMargins(inset,inset,inset,0);pane.addView(guidance,gp);

        LinearLayout speed=new LinearLayout(this);speed.setOrientation(LinearLayout.VERTICAL);speed.setGravity(Gravity.CENTER);speed.setBackground(panel(100,Color.argb(243,12,8,9),Color.rgb(113,57,44)));
        speedText=label("0",compact?27:34,TEXT,true);speedText.setGravity(Gravity.CENTER);speed.addView(speedText);
        TextView kmh=label("km/h",8,MUTED,true);kmh.setGravity(Gravity.CENTER);speed.addView(kmh);
        navLimitText=label("—",compact?13:16,Color.rgb(226,185,76),true);navLimitText.setGravity(Gravity.CENTER);navLimitText.setBackground(panel(100,Color.rgb(248,238,220),0));navLimitText.setTextColor(Color.rgb(112,15,28));speed.addView(navLimitText,new LinearLayout.LayoutParams(dp(40),dp(28)));
        FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(dp(92),dp(116),Gravity.RIGHT|Gravity.TOP);sp.setMargins(0,inset+guideH+dp(12),inset,0);pane.addView(speed,sp);

        LinearLayout alert=new LinearLayout(this);alert.setOrientation(LinearLayout.VERTICAL);alert.setGravity(Gravity.CENTER_VERTICAL);alert.setPadding(dp(12),dp(10),dp(12),dp(10));alert.setBackground(panel(17,Color.argb(238,17,9,11),Color.rgb(92,40,43)));
        protectionText=label("PROTEÇÃO ATIVA",7,GREEN,true);protectionText.setLetterSpacing(.11f);alert.addView(protectionText);
        hazardTitle=label("Estrada livre à frente",compact?13:16,TEXT,true);hazardTitle.setMaxLines(2);alert.addView(hazardTitle);
        hazardDetail=label("Monitorando sua direção e a estrada",8,MUTED,false);hazardDetail.setMaxLines(2);alert.addView(hazardDetail);
        FrameLayout.LayoutParams ap=new FrameLayout.LayoutParams(compact?dp(180):dp(230),-2,Gravity.RIGHT|Gravity.CENTER_VERTICAL);ap.setMargins(0,dp(30),inset,0);pane.addView(alert,ap);

        LinearLayout footer=new LinearLayout(this);footer.setOrientation(LinearLayout.VERTICAL);footer.setPadding(dp(12),dp(8),dp(12),dp(8));footer.setBackground(panel(16,Color.argb(235,11,8,9),Color.rgb(72,38,38)));
        TextView footerOver=label(destination==null?"EPC · PROTEÇÃO DA ESTRADA":"ROTA ATIVA",7,Color.rgb(226,185,76),true);footerOver.setLetterSpacing(.10f);footer.addView(footerOver);
        mapStateText=label("Preparando cobertura desta região…",8,TEXT,true);mapStateText.setMaxLines(2);footer.addView(mapStateText);
        FrameLayout.LayoutParams fp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);fp.setMargins(inset,0,inset,inset);pane.addView(footer,fp);
        return pane;
    }'''

RIGHT = r'''private LinearLayout buildRightPanel(int height, boolean compact, boolean ultrawide) {
        LinearLayout right=new LinearLayout(this);right.setOrientation(LinearLayout.VERTICAL);int pad=clamp(Math.round(height*.022f),dp(10),dp(17));right.setPadding(pad,pad,pad,pad);right.setBackground(panel(25,Color.rgb(12,7,9),Color.rgb(72,35,40)));
        LinearLayout head=new LinearLayout(this);head.setOrientation(LinearLayout.HORIZONTAL);head.setGravity(Gravity.CENTER_VERTICAL);TextView star=label("★",20,Color.rgb(226,185,76),true);head.addView(star,new LinearLayout.LayoutParams(dp(34),dp(38)));LinearLayout titles=new LinearLayout(this);titles.setOrientation(LinearLayout.VERTICAL);titles.addView(label("EPC",compact?18:22,TEXT,true));TextView ss=label("CENTRAL AUTOMOTIVA",7,ACCENT,true);ss.setLetterSpacing(.12f);titles.addView(ss);head.addView(titles,new LinearLayout.LayoutParams(0,-2,1));clockText=label("--:--",compact?17:20,TEXT,true);head.addView(clockText);right.addView(head);
        int gap=clamp(Math.round(height*.016f),dp(7),dp(11));

        LinearLayout route=card();LinearLayout.LayoutParams rlp=new LinearLayout.LayoutParams(-1,0,.34f);rlp.setMargins(0,gap,0,0);right.addView(route,rlp);TextView ro=label(destination==null?"RODAGEM LIVRE":"ROTA ATIVA",8,destination==null?GREEN:ACCENT,true);ro.setLetterSpacing(.11f);route.addView(ro);route.addView(label(destination==null?"Estrada sob proteção":shortDestination(destination.label),compact?15:18,TEXT,true));destinationText=label(destination==null?"Radares, limites, clima e alertas continuam ativos.":"Calculando percurso…",compact?9:10,MUTED,false);route.addView(destinationText,new LinearLayout.LayoutParams(-1,0,1));TextView weather=label(RoadWeatherMonitor.compactStatus(this),9,Color.rgb(226,185,76),true);route.addView(weather);

        LinearLayout quick=card();LinearLayout.LayoutParams qlp=new LinearLayout.LayoutParams(-1,0,.31f);qlp.setMargins(0,gap,0,0);right.addView(quick,qlp);TextView qo=label("ATALHOS",8,ACCENT,true);qo.setLetterSpacing(.11f);quick.addView(qo);LinearLayout q1=new LinearLayout(this);q1.setOrientation(LinearLayout.HORIZONTAL);Button radio=action("RÁDIO PTT",false);Button central=action("CENTRAL",false);q1.addView(radio,new LinearLayout.LayoutParams(0,dp(44),1));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,dp(44),1);cp.setMargins(dp(7),0,0,0);q1.addView(central,cp);quick.addView(q1);LinearLayout q2=new LinearLayout(this);q2.setOrientation(LinearLayout.HORIZONTAL);Button hud=action("HUD",false);Button dash=action("DASHCAM",false);q2.addView(hud,new LinearLayout.LayoutParams(0,dp(44),1));LinearLayout.LayoutParams dp2=new LinearLayout.LayoutParams(0,dp(44),1);dp2.setMargins(dp(7),0,0,0);q2.addView(dash,dp2);quick.addView(q2);radio.setOnClickListener(v->startActivity(new Intent(this,RoadRadioActivity.class)));central.setOnClickListener(v->startActivity(new Intent(this,DriveToolsActivity.class)));hud.setOnClickListener(v->startActivity(new Intent(this,HudActivity.class)));dash.setOnClickListener(v->startActivity(new Intent(this,CameraActivity.class)));

        LinearLayout media=card();LinearLayout.LayoutParams mlp=new LinearLayout.LayoutParams(-1,0,.35f);mlp.setMargins(0,gap,0,0);right.addView(media,mlp);TextView mo=label("MÚSICA OFFLINE",8,ACCENT,true);mo.setLetterSpacing(.11f);media.addView(mo);media.addView(label("Player de bordo",compact?15:18,TEXT,true));media.addView(label("A música continua local. Alertas de segurança têm prioridade sobre o áudio.",compact?9:10,MUTED,false),new LinearLayout.LayoutParams(-1,0,1));Button music=action("ABRIR MÚSICA",true);media.addView(music,new LinearLayout.LayoutParams(-1,clamp(Math.round(height*.072f),dp(42),dp(54))));music.setOnClickListener(v->startActivity(new Intent(this,MusicPlayerActivity.class)));
        return right;
    }'''


def patch_main():
    p = JAVA / "MainActivity.java"
    s = p.read_text(encoding="utf-8")
    s = replace_method(s, "private void showHome() {", HOME)
    p.write_text(s, encoding="utf-8")


def patch_central():
    (JAVA / "DriveToolsActivity.java").write_text(CENTRAL, encoding="utf-8")


def patch_map():
    p = JAVA / "RoadMapActivity.java"
    s = p.read_text(encoding="utf-8")
    if "private TextView navInstructionText" not in s:
        s = s.replace("    private TextView destinationText;\n", "    private TextView destinationText;\n    private TextView navInstructionText, navLimitText, navWeatherText;\n")
    s = s.replace("    private final int BG = Color.rgb(8, 5, 7);", "    private final int BG = Color.rgb(6, 4, 5);")
    s = s.replace("    private final int SURFACE = Color.rgb(18, 9, 12);", "    private final int SURFACE = Color.rgb(14, 8, 10);")
    s = s.replace("    private final int SURFACE_2 = Color.rgb(28, 14, 18);", "    private final int SURFACE_2 = Color.rgb(25, 13, 16);")
    s = s.replace("    private final int BORDER = Color.rgb(79, 39, 45);", "    private final int BORDER = Color.rgb(73, 35, 40);")
    s = s.replace("    private final int ACCENT = Color.rgb(190, 18, 38);", "    private final int ACCENT = Color.rgb(184, 20, 38);")
    s = s.replace("    private final int ACCENT_SOFT = Color.rgb(79, 10, 23);", "    private final int ACCENT_SOFT = Color.rgb(78, 10, 23);")
    s = replace_method(s, "private void buildPortraitUi(int width, int height) {", PORTRAIT)
    s = replace_method(s, "private LinearLayout buildRail(int height, boolean compact) {", RAIL)
    s = replace_method(s, "private FrameLayout buildMapPane(int height, boolean compact) {", MAP_PANE)
    s = replace_method(s, "private LinearLayout buildRightPanel(int height, boolean compact, boolean ultrawide) {", RIGHT)

    needle = "            if (speedText != null) speedText.setText(String.valueOf(Math.max(0, Math.round(speed))));\n"
    if needle in s and "navLimitText" not in s[s.find(needle):s.find(needle)+350]:
        s = s.replace(needle, needle + "            if (navLimitText != null) navLimitText.setText(limit > 0 ? String.valueOf(limit) : \"—\");\n            if (navWeatherText != null) navWeatherText.setText(RoadWeatherMonitor.compactStatus(this));\n", 1)

    old = '''                    if (destinationText != null) {
                        String detail = route.summary();
                        if (!route.nextInstruction.isEmpty()) detail += "\\n" + route.nextInstruction;
                        destinationText.setText(detail);
                    }'''
    new = '''                    if (navInstructionText != null) {
                        String nav = route.nextInstruction.isEmpty() ? "Siga na rota" : route.nextInstruction;
                        navInstructionText.setText(nav + "\\n" + route.summary());
                    }
                    if (destinationText != null) {
                        String detail = route.summary();
                        if (!route.nextInstruction.isEmpty()) detail += "\\n" + route.nextInstruction;
                        destinationText.setText(detail);
                    }'''
    if old in s: s = s.replace(old, new, 1)
    s = s.replace('if (destinationText != null) destinationText.setText("Rota online indisponível. A proteção da estrada continua ativa.");', 'if (navInstructionText != null) navInstructionText.setText("Rota online indisponível · proteção ativa");\n                    if (destinationText != null) destinationText.setText("Rota online indisponível. A proteção da estrada continua ativa.");', 1)
    p.write_text(s, encoding="utf-8")


def patch_gradle():
    p = APP / "app/build.gradle"
    s = p.read_text(encoding="utf-8")
    s = re.sub(r"versionCode\s+\d+", "versionCode 200", s, count=1)
    s = re.sub(r"versionName\s+'[^']+'", "versionName '2.0.0'", s, count=1)
    p.write_text(s, encoding="utf-8")


if __name__ == "__main__":
    patch_main()
    patch_central()
    patch_map()
    patch_gradle()
    print("AUTOMOTIVE_RED_GOLD_V200 applied")

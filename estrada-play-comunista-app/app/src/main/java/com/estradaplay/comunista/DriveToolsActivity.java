package com.estradaplay.comunista;

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
        // SYSTEM_DIAGNOSTICS_V270: local health snapshot is reachable without leaving the cockpit family.
        grid(new Tool[]{new Tool("✓","DIAGNÓSTICO","Versão, permissões e prontidão local",SystemDiagnosticsActivity.class,GREEN)});
        toggle("MODO NOTURNO","Escurecer instrumentos automaticamente","auto_night",DriveSettings.autoNight(this));
        toggle("CHUVA AUTOMÁTICA","Antecipa alertas e observa a rota","rain_auto",DriveSettings.autoRain(this));
        toggle("PROTEGER VÍDEO EM IMPACTO","Preserva o trecho da dashcam","protect_impact_video",DriveSettings.protectImpactVideo(this));
        toggle("BASE COLETIVA","Compartilha eventos leves da estrada","collective_enabled",DriveSettings.collectiveEnabled(this));
        toggle("TRÂNSITO COLABORATIVO","Opt-in: envia velocidade/posição com ID em hash e retenção de 2h","context_traffic_opt_in",DriveSettings.contextTrafficOptIn(this));

        LinearLayout maintenance=row();Button voice=btn("DIAGNÓSTICO DE VOZ",false);Button server=btn("SERVIDOR",false);maintenance.addView(voice,new LinearLayout.LayoutParams(0,dp(50),1));LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(0,dp(50),1);sp.setMargins(dp(8),0,0,0);maintenance.addView(server,sp);voice.setOnClickListener(v->open(VoiceDiagnosticsActivity.class));server.setOnClickListener(v->open(ServerSettingsActivity.class));page.addView(maintenance);margins(maintenance,0,10,0,0);
        page.postDelayed(() -> EpcMotion.stagger(page), 55L);
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

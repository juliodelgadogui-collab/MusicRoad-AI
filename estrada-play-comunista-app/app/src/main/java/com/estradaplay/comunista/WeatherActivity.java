package com.estradaplay.comunista;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** CLIMA_ROTA_UI_V303 */
public final class WeatherActivity extends ComponentActivity {
    private final int BG=Color.rgb(8,5,7),SURFACE=Color.rgb(18,9,12),SURFACE2=Color.rgb(28,14,18),BORDER=Color.rgb(76,38,44);
    private final int TEXT=Color.rgb(246,238,224),MUTED=Color.rgb(174,151,146),RED=Color.rgb(190,18,38),GREEN=Color.rgb(72,212,134),GOLD=Color.rgb(226,185,76);
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private LinearLayout page;

    @Override protected void onCreate(Bundle b){super.onCreate(b);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);build();}
    @Override protected void onResume(){super.onResume();if(page!=null)build();}
    @Override protected void onDestroy(){io.shutdownNow();super.onDestroy();}

    private void build(){
        ScrollView sv=new ScrollView(this);sv.setFillViewport(true);sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        page=col();page.setPadding(dp(18),dp(16),dp(18),dp(28));page.setBackgroundColor(BG);sv.addView(page);setContentView(UnifiedAppShell.wrap(this,"central",sv));
        LinearLayout head=row();head.setGravity(Gravity.CENTER_VERTICAL);Button back=button("‹ CENTRAL",false);head.addView(back,new LinearLayout.LayoutParams(dp(100),dp(46)));back.setOnClickListener(v->finish());LinearLayout title=col();title.addView(over("CLIMA DA ROTA",GOLD));title.addView(text("Previsão da estrada",27,TEXT,true));title.addView(text("Condição atual, próximas horas e o tempo no caminho.",11,MUTED,false));head.addView(title,new LinearLayout.LayoutParams(0,-2,1));page.addView(head);

        RoadWeatherMonitor.Snapshot s=RoadWeatherMonitor.snapshot(this);
        int heroColor=s.currentWet?RED:(s.routeRisk()||s.nextRainMinutes>=0||s.gustKmh>=55?GOLD:GREEN);
        LinearLayout hero=card();hero.addView(over(s.headline(),heroColor));hero.addView(text(RoadWeatherMonitor.compactStatus(this),21,TEXT,true));
        long age=RoadWeatherMonitor.ageMinutes(this);hero.addView(text(age<0?"Ainda sem atualização do provedor.":"Atualizado há "+age+" min",10,MUTED,false));
        add(page,hero,0,14,0,12,-1,-2);

        page.addView(section("AGORA","Leitura simples da condição na sua posição GPS."));
        LinearLayout local=card();
        local.addView(metric("CONDIÇÃO",s.available?s.condition:"SEM DADOS",s.currentWet?RED:GREEN));
        String temp="—";if(Double.isFinite(s.currentTempC)){temp=Math.round(s.currentTempC)+" °C";if(Double.isFinite(s.feelsLikeC))temp+=" · sensação "+Math.round(s.feelsLikeC)+" °C";}
        local.addView(metric("TEMPERATURA",temp,TEXT));
        local.addView(metric("CHANCE DE CHUVA AGORA",s.available?(s.currentChance+"%"):"—",s.currentChance>=40?GOLD:GREEN));
        String wind="—";if(Double.isFinite(s.windKmh)){wind=Math.round(s.windKmh)+" km/h";if(Double.isFinite(s.gustKmh)&&s.gustKmh>s.windKmh+5)wind+=" · rajadas "+Math.round(s.gustKmh)+" km/h";}
        local.addView(metric("VENTO",wind,s.gustKmh>=55?GOLD:TEXT));
        add(page,local,0,8,0,16,-1,-2);

        page.addView(section("PRÓXIMAS 6 HORAS","Resumo para saber o que muda sem precisar interpretar muitos números."));
        LinearLayout next=card();
        next.addView(metric("TENDÊNCIA",s.available?s.nextHoursSummary():"SEM DADOS",s.next6hMaxChance>=40?GOLD:GREEN));
        next.addView(metric("PRÓXIMA CHUVA",s.nextRainMinutes>=0?("~"+s.nextRainMinutes+" min · "+s.nextRainChance+"%"):"não prevista nas próximas horas",s.nextRainMinutes>=0?GOLD:GREEN));
        add(page,next,0,8,0,16,-1,-2);

        page.addView(section("AO LONGO DA ROTA","O Estrada Play cruza vários pontos do percurso com o horário estimado de chegada."));
        LinearLayout route=card();
        if(s.routeRisk()){
            route.addView(over("CHUVA NO CAMINHO",RED));
            route.addView(text(String.format(Locale.getDefault(),"Aproximadamente %.0f km à frente",s.routeRainKm),20,TEXT,true));
            route.addView(text("Chegada estimada ao trecho em ~"+s.routeRainMinutes+" min · chance "+s.routeRainChance+"%",12,GOLD,true));
            if(!s.routeLabel.isEmpty())route.addView(text("Destino: "+s.routeLabel,10,MUTED,false));
        }else if(!s.routeLabel.isEmpty()){
            route.addView(over("ROTA MONITORADA",GREEN));route.addView(text("Sem chuva relevante detectada nos pontos analisados do caminho.",15,TEXT,true));route.addView(text("Destino: "+s.routeLabel,10,MUTED,false));
        }else{
            route.addView(over("SEM ROTA ATIVA",MUTED));route.addView(text("Calcule uma viagem para verificar o clima ao longo do caminho.",14,TEXT,true));
        }
        add(page,route,0,8,0,16,-1,-2);

        Button refresh=button("ATUALIZAR CLIMA AGORA",true);add(page,refresh,0,0,0,8,-1,dp(56));refresh.setOnClickListener(v->refresh(refresh));
        Button toggle=button(DriveSettings.autoRain(this)?"CLIMA AUTOMÁTICO · ATIVO":"CLIMA AUTOMÁTICO · DESLIGADO",false);add(page,toggle,0,0,0,16,-1,dp(50));toggle.setOnClickListener(v->{boolean n=!DriveSettings.autoRain(this);DriveSettings.toggle(this,"rain_auto",n);toast(n?"Clima automático ativado":"Clima automático desativado");build();});

        LinearLayout explain=card();explain.addView(over("COMO FUNCIONA",MUTED));explain.addView(text("• condição atual: temperatura, sensação, chuva, vento e código meteorológico\n• próximas horas: faixa de temperatura e maior chance de chuva\n• rota: até seis pontos do percurso comparados com o horário previsto de chegada\n• sem resposta do provedor, o app mantém a última informação válida e indica quando ficou antiga",11,TEXT,false));add(page,explain,0,0,0,0,-1,-2);
        if(!s.error.isEmpty()){TextView e=text("Última falha de atualização: "+s.error,9,MUTED,false);add(page,e,2,8,0,0,-1,-2);}
    }

    private void refresh(Button b){if(!DriveSettings.autoRain(this)){toast("Ative Clima Automático primeiro.");return;}Location l=last();if(l==null){toast("Ainda não tenho uma posição GPS válida.");return;}b.setEnabled(false);b.setText("ATUALIZANDO…");io.execute(()->{RoadWeatherMonitor.refresh(getApplicationContext(),l.getLatitude(),l.getLongitude());runOnUiThread(()->{b.setEnabled(true);toast("Clima atualizado");build();});});}

    private Location last(){if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED)return null;try{LocationManager m=(LocationManager)getSystemService(Context.LOCATION_SERVICE);Location best=null;for(String p:new String[]{LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER}){Location x=m.getLastKnownLocation(p);if(x!=null&&(best==null||x.getTime()>best.getTime()))best=x;}return best;}catch(Throwable e){return null;}}
    private View section(String title,String sub){LinearLayout x=col();x.addView(over(title,MUTED));x.addView(text(sub,10,MUTED,false));return x;}
    private View metric(String label,String value,int accent){LinearLayout x=col();x.setPadding(dp(12),dp(10),dp(12),dp(10));x.setBackground(panel(SURFACE2,13,BORDER));x.addView(over(label,MUTED));x.addView(text(value,16,accent,true));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,0,0,dp(8));x.setLayoutParams(p);return x;}
    private LinearLayout card(){LinearLayout c=col();c.setPadding(dp(16),dp(15),dp(16),dp(15));c.setBackground(panel(SURFACE,17,BORDER));return c;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}private LinearLayout col(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private TextView text(String v,float s,int c,boolean b){TextView t=new TextView(this);t.setText(v);t.setTextSize(s);t.setTextColor(c);t.setGravity(Gravity.CENTER_VERTICAL);t.setLineSpacing(0,1.07f);if(b)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}private TextView over(String v,int c){TextView t=text(v,9,c,true);t.setLetterSpacing(.12f);return t;}
    private Button button(String v,boolean primary){Button b=new Button(this);b.setText(v);b.setAllCaps(false);b.setTextColor(TEXT);b.setTextSize(10);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setStateListAnimator(null);b.setBackground(panel(primary?RED:SURFACE2,14,primary?0:BORDER));return b;}
    private GradientDrawable panel(int c,int r,int stroke){GradientDrawable g=new GradientDrawable();g.setColor(c);g.setCornerRadius(dp(r));if(stroke!=0)g.setStroke(dp(1),stroke);return g;}
    private void add(LinearLayout p,View v,int l,int t,int r,int b,int w,int h){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(w,h);lp.setMargins(dp(l),dp(t),dp(r),dp(b));p.addView(v,lp);}private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
}

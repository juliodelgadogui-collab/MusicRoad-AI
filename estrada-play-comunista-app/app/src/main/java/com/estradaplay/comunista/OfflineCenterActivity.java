package com.estradaplay.comunista;

import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class OfflineCenterActivity extends ComponentActivity {
    private final int BG=Color.rgb(9,5,7),TEXT=Color.rgb(246,238,224),MUTED=Color.rgb(174,151,146),RED=Color.rgb(190,18,38),GREEN=Color.rgb(69,212,131),GOLD=Color.rgb(226,185,76);
    private LinearLayout page;
    private final ExecutorService io=Executors.newSingleThreadExecutor();

    @Override protected void onCreate(Bundle b){super.onCreate(b);load();}
    @Override protected void onDestroy(){io.shutdownNow();super.onDestroy();}

    private void load(){
        ScrollView sv=new ScrollView(this);page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(18),dp(18),dp(18),dp(30));page.setBackgroundColor(BG);sv.addView(page);setContentView(UnifiedAppShell.wrap(this,"central",sv));
        page.addView(t("MODO OFFLINE",27,TEXT,true));
        page.addView(t("Verificando os dados essenciais que ficam disponíveis no aparelho…",13,MUTED,false));
        io.execute(()->{
            RoadPackStore safety=new RoadPackStore(getApplicationContext());
            OfflineRoadStore roads=new OfflineRoadStore(getApplicationContext());
            boolean ready=safety.hazardCount()>0||roads.packCount()>0;
            String route=RouteOfflineCache.status(getApplicationContext());
            runOnUiThread(()->render(ready,route));
        });
    }

    private void render(boolean ready,String routeStatus){
        page.removeAllViews();
        page.addView(t("MODO OFFLINE",27,TEXT,true));
        page.addView(t("O Estrada Play mantém automaticamente os dados necessários para continuar ajudando quando o sinal cair.",12,MUTED,false));

        LinearLayout state=box();
        state.addView(t(DriveSettings.offlineTestMode(this)?"TESTE OFFLINE ATIVO":(ready?"PROTEÇÃO PRONTA":"PREPARANDO PROTEÇÃO"),11,DriveSettings.offlineTestMode(this)||ready?GREEN:GOLD,true));
        state.addView(t(ready?"Dados essenciais disponíveis neste aparelho.":"Conecte-se por alguns instantes para completar a preparação.",16,TEXT,true));
        page.addView(state,new LinearLayout.LayoutParams(-1,-2));

        if(routeStatus!=null&&!routeStatus.startsWith("Nenhuma")){
            LinearLayout route=box();
            route.addView(t("VIAGEM PREPARADA",11,GOLD,true));
            route.addView(t("Sua última rota pode ser retomada se a conexão oscilar.",14,TEXT,true));
            page.addView(route,new LinearLayout.LayoutParams(-1,-2));
        }

        Button test=new Button(this);test.setText(DriveSettings.offlineTestMode(this)?"ENCERRAR TESTE OFFLINE":"TESTAR SEM INTERNET");test.setTextColor(TEXT);test.setBackgroundColor(DriveSettings.offlineTestMode(this)?Color.rgb(55,85,65):RED);page.addView(test,new LinearLayout.LayoutParams(-1,dp(58)));
        test.setOnClickListener(v->{boolean on=!DriveSettings.offlineTestMode(this);DriveSettings.toggle(this,"offline_test_mode",on);Toast.makeText(this,on?"Teste offline ativado.":"Conexão normal restaurada.",Toast.LENGTH_LONG).show();load();});

        Button update=new Button(this);update.setText("ATUALIZAR DADOS OFFLINE");page.addView(update,new LinearLayout.LayoutParams(-1,dp(54)));
        update.setOnClickListener(v->{if(DriveSettings.offlineTestMode(this)){Toast.makeText(this,"Encerre o teste offline para atualizar.",Toast.LENGTH_LONG).show();return;}Intent i=new Intent(this,RoadSafetyService.class).setAction(RoadSafetyService.ACTION_PREFETCH_CORE);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);Toast.makeText(this,"Atualização iniciada.",Toast.LENGTH_LONG).show();});

        Button planner=new Button(this);planner.setText("PREPARAR VIAGEM OFFLINE");planner.setOnClickListener(v->startActivity(new Intent(this,TripPlannerActivity.class)));page.addView(planner,new LinearLayout.LayoutParams(-1,dp(54)));
        Button reload=new Button(this);reload.setText("VERIFICAR NOVAMENTE");reload.setOnClickListener(v->load());page.addView(reload,new LinearLayout.LayoutParams(-1,dp(50)));
    }

    private LinearLayout box(){LinearLayout b=new LinearLayout(this);b.setOrientation(LinearLayout.VERTICAL);b.setPadding(dp(14),dp(12),dp(14),dp(12));b.setBackgroundColor(Color.rgb(20,11,14));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(12),0,dp(10));b.setLayoutParams(p);return b;}
    private TextView t(String v,float s,int c,boolean b){TextView t=new TextView(this);t.setText(v);t.setTextSize(s);t.setTextColor(c);if(b)t.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);return t;}
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
}

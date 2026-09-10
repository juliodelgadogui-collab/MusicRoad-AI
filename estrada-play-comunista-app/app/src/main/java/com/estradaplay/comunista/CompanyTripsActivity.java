package com.estradaplay.comunista;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Recent company journeys recorded from the shared road GPS stream. */
public final class CompanyTripsActivity extends ComponentActivity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private EstradaTheme theme;
    private LinearLayout list;
    private TextView status;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!CompanyAccount.shouldUseCompanyCentral(this)) { finish(); return; }
        build(); load();
    }

    private void build() {
        theme=EstradaTheme.get(this); getWindow().setStatusBarColor(theme.background); getWindow().setNavigationBarColor(theme.background);
        ScrollView scroll=new ScrollView(this); scroll.setBackgroundColor(theme.background);
        LinearLayout page=PremiumUi.col(this); page.setPadding(dp(18),dp(18),dp(18),dp(30)); scroll.addView(page,new ScrollView.LayoutParams(-1,-2)); setContentView(scroll);
        Button back=PremiumUi.button(this,"‹ EMPRESA",false);back.setOnClickListener(v->finish());page.addView(back,new LinearLayout.LayoutParams(dp(110),dp(46)));
        TextView over=PremiumUi.overline(this,"VIAGENS",theme.secondary);page.addView(over);margins(over,0,22,0,4);
        page.addView(PremiumUi.text(this,"Histórico da frota",28,theme.text,true));
        TextView sub=PremiumUi.text(this,"Jornadas registradas enquanto os motoristas usam a Estrada, com veículo, duração e quilometragem.",12,theme.muted,false);page.addView(sub);margins(sub,0,7,0,14);
        status=PremiumUi.text(this,"Carregando viagens…",11,theme.muted,false);page.addView(status);margins(status,0,0,0,10);
        list=PremiumUi.col(this);page.addView(list,new LinearLayout.LayoutParams(-1,-2));
    }

    private void load(){io.execute(()->{try{JSONObject j=new CompanyApi(this).journeys();JSONArray rows=j.optJSONArray("journeys");if(rows==null)rows=new JSONArray();JSONArray finalRows=rows;runOnUiThread(()->render(finalRows));}catch(Throwable e){runOnUiThread(()->status.setText("Não consegui carregar as viagens agora."));}});}

    private void render(JSONArray rows){list.removeAllViews();status.setText(rows.length()+" viagem(ns) recente(s)");for(int i=0;i<rows.length();i++){JSONObject r=rows.optJSONObject(i);if(r==null)continue;LinearLayout card=PremiumUi.col(this);card.setPadding(dp(14),dp(14),dp(14),dp(14));card.setBackground(PremiumUi.panel(this,theme.surfaceAlt,theme.border,theme.radiusDp));String driver=r.optString("driver_name","Motorista");String vehicle=r.optString("vehicle_label","Veículo");card.addView(PremiumUi.text(this,driver,15,theme.text,true));card.addView(PremiumUi.text(this,vehicle,11,theme.secondary,true));TextView km=PremiumUi.text(this,String.format(Locale.getDefault(),"%.1f km",r.optDouble("distance_km",0.0)),18,theme.text,true);card.addView(km);margins(km,0,8,0,2);String started=r.optString("started_at","");String ended=r.optString("ended_at","");String state=r.optString("status","");card.addView(PremiumUi.text(this,started+(ended.isEmpty()?" · em andamento":" → "+ended)+(state.isEmpty()?"":" · "+state),10,theme.muted,false));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,0,0,dp(8));list.addView(card,p);}}

    private int dp(float v){return PremiumUi.dp(this,v);}private void margins(View v,int l,int t,int r,int b){if(!(v.getLayoutParams() instanceof LinearLayout.LayoutParams))return;LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)v.getLayoutParams();p.setMargins(dp(l),dp(t),dp(r),dp(b));v.setLayoutParams(p);}    
    @Override protected void onDestroy(){io.shutdownNow();super.onDestroy();}
}

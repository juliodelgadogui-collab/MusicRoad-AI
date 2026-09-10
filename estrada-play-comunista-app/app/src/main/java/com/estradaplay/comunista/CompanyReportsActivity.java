package com.estradaplay.comunista;

import android.os.Bundle;
import android.view.Gravity;
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

/** Daily/weekly/monthly company mileage reports grouped by driver and vehicle. */
public final class CompanyReportsActivity extends ComponentActivity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private EstradaTheme theme;
    private TextView totalKm, driverCount, vehicleCount, periodLabel, status;
    private LinearLayout totals, rows;
    private Button today, week, month;
    private int days = 7;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!CompanyAccount.shouldUseCompanyCentral(this)) { finish(); return; }
        build();
        load(7);
    }

    private void build() {
        theme=EstradaTheme.get(this);getWindow().setStatusBarColor(theme.background);getWindow().setNavigationBarColor(theme.background);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(theme.background);
        LinearLayout page=PremiumUi.col(this);page.setPadding(dp(18),dp(18),dp(18),dp(30));scroll.addView(page,new ScrollView.LayoutParams(-1,-2));setContentView(scroll);

        Button back=PremiumUi.button(this,"‹ EMPRESA",false);back.setOnClickListener(v->finish());page.addView(back,new LinearLayout.LayoutParams(dp(110),dp(46)));
        TextView over=PremiumUi.overline(this,"RELATÓRIOS",theme.secondary);page.addView(over);margins(over,0,22,0,4);
        page.addView(PremiumUi.text(this,"Quilometragem da frota",28,theme.text,true));
        TextView sub=PremiumUi.text(this,"Compare quanto cada motorista e veículo rodou por dia, semana ou mês.",12,theme.muted,false);page.addView(sub);margins(sub,0,7,0,14);

        LinearLayout filters=PremiumUi.row(this);
        today=filter("HOJE",1);week=filter("7 DIAS",7);month=filter("30 DIAS",30);
        filters.addView(today,new LinearLayout.LayoutParams(0,dp(46),1f));
        LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(0,dp(46),1f);wp.setMargins(dp(7),0,0,0);filters.addView(week,wp);
        LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(0,dp(46),1f);mp.setMargins(dp(7),0,0,0);filters.addView(month,mp);
        page.addView(filters,new LinearLayout.LayoutParams(-1,-2));

        periodLabel=PremiumUi.text(this,"",10,theme.muted,false);periodLabel.setGravity(Gravity.CENTER);page.addView(periodLabel);margins(periodLabel,0,8,0,10);

        LinearLayout summary=PremiumUi.row(this);
        totalKm=stat(summary,"KM","—");driverCount=stat(summary,"MOTORISTAS","—");vehicleCount=stat(summary,"VEÍCULOS","—");
        page.addView(summary,new LinearLayout.LayoutParams(-1,-2));

        TextView driversTitle=PremiumUi.overline(this,"TOTAL POR MOTORISTA",theme.muted);page.addView(driversTitle);margins(driversTitle,0,20,0,7);
        totals=PremiumUi.col(this);page.addView(totals,new LinearLayout.LayoutParams(-1,-2));

        TextView dailyTitle=PremiumUi.overline(this,"DETALHE DIÁRIO",theme.muted);page.addView(dailyTitle);margins(dailyTitle,0,20,0,7);
        rows=PremiumUi.col(this);page.addView(rows,new LinearLayout.LayoutParams(-1,-2));

        status=PremiumUi.text(this,"Carregando relatório…",10,theme.muted,false);status.setGravity(Gravity.CENTER);page.addView(status);margins(status,0,12,0,0);
        updateFilters();
    }

    private Button filter(String label,int value){Button b=PremiumUi.button(this,label,false);b.setOnClickListener(v->load(value));return b;}

    private TextView stat(LinearLayout parent,String label,String initial){
        LinearLayout card=PremiumUi.col(this);card.setGravity(Gravity.CENTER);card.setPadding(dp(8),dp(11),dp(8),dp(11));card.setBackground(PremiumUi.panel(this,theme.surfaceAlt,theme.border,theme.radiusDp));
        TextView value=PremiumUi.text(this,initial,19,theme.text,true);value.setGravity(Gravity.CENTER);card.addView(value);
        TextView small=PremiumUi.overline(this,label,theme.muted);small.setGravity(Gravity.CENTER);card.addView(small);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(82),1f);if(parent.getChildCount()>0)p.setMargins(dp(6),0,0,0);parent.addView(card,p);return value;
    }

    private void load(int value){
        days=value;updateFilters();status.setText("Carregando relatório…");
        io.execute(()->{try{JSONObject j=new CompanyApi(this).report(value);runOnUiThread(()->render(j));}catch(Throwable e){String m=e.getMessage()==null?"Não consegui carregar o relatório.":e.getMessage();runOnUiThread(()->status.setText(m));}});
    }

    private void render(JSONObject j){
        JSONObject period=j.optJSONObject("period");JSONObject summary=j.optJSONObject("summary");if(summary==null)summary=new JSONObject();
        totalKm.setText(String.format(Locale.getDefault(),"%.1f",summary.optDouble("distance_km",0.0)));
        driverCount.setText(String.valueOf(summary.optInt("drivers",0)));vehicleCount.setText(String.valueOf(summary.optInt("vehicles",0)));
        if(period!=null)periodLabel.setText(period.optString("from","")+" → "+period.optString("to",""));

        totals.removeAllViews();JSONArray driverTotals=j.optJSONArray("driver_totals");if(driverTotals==null)driverTotals=new JSONArray();
        for(int i=0;i<driverTotals.length();i++){JSONObject d=driverTotals.optJSONObject(i);if(d==null)continue;LinearLayout row=PremiumUi.row(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(13),dp(11),dp(13),dp(11));row.setBackground(PremiumUi.panel(this,theme.surfaceAlt,theme.border,theme.radiusDp));row.addView(PremiumUi.text(this,d.optString("name","Motorista"),13,theme.text,true),new LinearLayout.LayoutParams(0,-2,1f));TextView km=PremiumUi.text(this,String.format(Locale.getDefault(),"%.1f km",d.optDouble("distance_km",0.0)),13,theme.secondary,true);km.setGravity(Gravity.RIGHT);row.addView(km,new LinearLayout.LayoutParams(dp(100),-2));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,0,0,dp(7));totals.addView(row,p);}
        if(driverTotals.length()==0)totals.addView(PremiumUi.text(this,"Nenhuma quilometragem registrada neste período.",11,theme.muted,false));

        rows.removeAllViews();JSONArray data=j.optJSONArray("rows");if(data==null)data=new JSONArray();String lastDay="";
        for(int i=0;i<data.length();i++){JSONObject r=data.optJSONObject(i);if(r==null)continue;String day=r.optString("day","");if(!day.equals(lastDay)){TextView dayTitle=PremiumUi.overline(this,day,theme.secondary);rows.addView(dayTitle);margins(dayTitle,0,i==0?0:9,0,5);lastDay=day;}
            LinearLayout card=PremiumUi.col(this);card.setPadding(dp(13),dp(11),dp(13),dp(11));card.setBackground(PremiumUi.panel(this,theme.surfaceAlt,theme.border,theme.radiusDp));LinearLayout top=PremiumUi.row(this);top.addView(PremiumUi.text(this,r.optString("driver_name","Motorista"),13,theme.text,true),new LinearLayout.LayoutParams(0,-2,1f));TextView km=PremiumUi.text(this,String.format(Locale.getDefault(),"%.1f km",r.optDouble("distance_km",0.0)),12,theme.secondary,true);km.setGravity(Gravity.RIGHT);top.addView(km,new LinearLayout.LayoutParams(dp(95),-2));card.addView(top);card.addView(PremiumUi.text(this,r.optString("vehicle_label","Veículo"),10,theme.muted,false));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,0,0,dp(6));rows.addView(card,p);}
        status.setText(data.length()+" registro(s) no período.");
    }

    private void updateFilters(){
        if(today==null)return;today.setBackground(PremiumUi.panel(this,days==1?theme.primary:theme.surfaceAlt,days==1?theme.primary:theme.border,theme.radiusDp));week.setBackground(PremiumUi.panel(this,days==7?theme.primary:theme.surfaceAlt,days==7?theme.primary:theme.border,theme.radiusDp));month.setBackground(PremiumUi.panel(this,days==30?theme.primary:theme.surfaceAlt,days==30?theme.primary:theme.border,theme.radiusDp));
    }

    private int dp(float v){return PremiumUi.dp(this,v);}private void margins(View v,int l,int t,int r,int b){if(!(v.getLayoutParams() instanceof LinearLayout.LayoutParams))return;LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)v.getLayoutParams();p.setMargins(dp(l),dp(t),dp(r),dp(b));v.setLayoutParams(p);}    
    @Override protected void onDestroy(){io.shutdownNow();super.onDestroy();}
}

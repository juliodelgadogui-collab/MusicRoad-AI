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

/** Operational comparison of vehicles and drivers for the selected period. */
public final class CompanyComparisonActivity extends ComponentActivity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private EstradaTheme theme;
    private LinearLayout vehiclesList,driversList;
    private TextView status,period;
    private Button seven,thirty;
    private int days=30;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        if(!CompanyAccount.shouldUseCompanyCentral(this)){finish();return;}
        build();load(30);
    }

    private void build(){
        theme=EstradaTheme.get(this);getWindow().setStatusBarColor(theme.background);getWindow().setNavigationBarColor(theme.background);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(theme.background);LinearLayout page=PremiumUi.col(this);page.setPadding(dp(18),dp(18),dp(18),dp(30));scroll.addView(page,new ScrollView.LayoutParams(-1,-2));setContentView(scroll);
        Button back=PremiumUi.button(this,"‹ EMPRESA",false);back.setOnClickListener(v->finish());page.addView(back,new LinearLayout.LayoutParams(dp(110),dp(46)));
        TextView over=PremiumUi.overline(this,"COMPARATIVO",theme.secondary);page.addView(over);margins(over,0,22,0,4);
        page.addView(PremiumUi.text(this,"Desempenho da frota",28,theme.text,true));
        TextView sub=PremiumUi.text(this,"Compare km rodado, combustível, custo por km e alertas sem criar pontuação de motorista.",12,theme.muted,false);page.addView(sub);margins(sub,0,7,0,14);
        LinearLayout filters=PremiumUi.row(this);seven=PremiumUi.button(this,"7 DIAS",false);thirty=PremiumUi.button(this,"30 DIAS",false);seven.setOnClickListener(v->load(7));thirty.setOnClickListener(v->load(30));filters.addView(seven,new LinearLayout.LayoutParams(0,dp(46),1f));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(46),1f);p.setMargins(dp(7),0,0,0);filters.addView(thirty,p);page.addView(filters);
        period=PremiumUi.text(this,"",10,theme.muted,false);period.setGravity(Gravity.CENTER);page.addView(period);margins(period,0,8,0,14);
        TextView vt=PremiumUi.overline(this,"VEÍCULOS",theme.muted);page.addView(vt);vehiclesList=PremiumUi.col(this);page.addView(vehiclesList);margins(vehiclesList,0,7,0,12);
        TextView dt=PremiumUi.overline(this,"MOTORISTAS",theme.muted);page.addView(dt);driversList=PremiumUi.col(this);page.addView(driversList);margins(driversList,0,7,0,0);
        status=PremiumUi.text(this,"Carregando comparação…",10,theme.muted,false);status.setGravity(Gravity.CENTER);page.addView(status);margins(status,0,12,0,0);
    }

    private void load(int value){days=value;updateFilter();status.setText("Atualizando comparação…");io.execute(()->{try{JSONObject j=new CompanyApi(this).compareFleet(value);runOnUiThread(()->render(j));}catch(Throwable e){String m=e.getMessage()==null?"Não consegui carregar a comparação.":e.getMessage();runOnUiThread(()->status.setText(m));}});}

    private void render(JSONObject root){
        JSONObject p=root.optJSONObject("period");period.setText(p==null?"":p.optString("from","")+" → "+p.optString("to",""));
        JSONArray vehicles=root.optJSONArray("vehicles");if(vehicles==null)vehicles=new JSONArray();vehiclesList.removeAllViews();
        for(int i=0;i<vehicles.length();i++){JSONObject v=vehicles.optJSONObject(i);if(v==null)continue;LinearLayout c=panel();LinearLayout top=PremiumUi.row(this);top.addView(PremiumUi.text(this,(i+1)+". "+v.optString("label","Veículo"),14,theme.text,true),new LinearLayout.LayoutParams(0,-2,1f));TextView km=PremiumUi.text(this,String.format(Locale.getDefault(),"%.0f km",v.optDouble("km",0)),13,theme.secondary,true);km.setGravity(Gravity.RIGHT);top.addView(km,new LinearLayout.LayoutParams(dp(95),-2));c.addView(top);c.addView(PremiumUi.text(this,String.format(Locale.getDefault(),"R$ %.2f combustível · R$ %.3f/km",v.optDouble("fuel_value",0),v.optDouble("cost_per_km",0)),10,theme.muted,false));int incidents=v.optInt("open_incidents",0),high=v.optInt("high_incidents",0),overdue=v.optInt("overdue_maintenance",0);if(high>0)c.addView(PremiumUi.text(this,high+" ocorrência(s) urgente(s)",10,theme.danger,true));else if(incidents>0)c.addView(PremiumUi.text(this,incidents+" ocorrência(s) aberta(s)",10,theme.warning,true));if(overdue>0)c.addView(PremiumUi.text(this,overdue+" manutenção(ões) vencida(s)",10,theme.danger,true));vehiclesList.addView(c,marginLp());}
        if(vehicles.length()==0)vehiclesList.addView(PremiumUi.text(this,"Nenhum veículo cadastrado.",11,theme.muted,false));

        JSONArray drivers=root.optJSONArray("drivers");if(drivers==null)drivers=new JSONArray();driversList.removeAllViews();
        for(int i=0;i<drivers.length();i++){JSONObject d=drivers.optJSONObject(i);if(d==null)continue;LinearLayout c=panel();LinearLayout top=PremiumUi.row(this);top.addView(PremiumUi.text(this,(i+1)+". "+d.optString("name","Motorista"),13,theme.text,true),new LinearLayout.LayoutParams(0,-2,1f));TextView km=PremiumUi.text(this,String.format(Locale.getDefault(),"%.0f km",d.optDouble("km",0)),12,theme.secondary,true);km.setGravity(Gravity.RIGHT);top.addView(km,new LinearLayout.LayoutParams(dp(90),-2));c.addView(top);String username=d.optString("username","");if(!username.isEmpty())c.addView(PremiumUi.text(this,"@"+username,9,theme.muted,false));c.addView(PremiumUi.text(this,String.format(Locale.getDefault(),"R$ %.2f em abastecimentos registrados · %d ocorrência(s)",d.optDouble("fuel_value",0),d.optInt("incidents",0)),10,theme.muted,false));driversList.addView(c,marginLp());}
        if(drivers.length()==0)driversList.addView(PremiumUi.text(this,"Nenhum motorista vinculado.",11,theme.muted,false));
        status.setText(vehicles.length()+" veículo(s) · "+drivers.length()+" motorista(s)");
    }

    private void updateFilter(){if(seven==null)return;seven.setBackground(PremiumUi.panel(this,days==7?theme.primary:theme.surfaceAlt,days==7?theme.primary:theme.border,theme.radiusDp));thirty.setBackground(PremiumUi.panel(this,days==30?theme.primary:theme.surfaceAlt,days==30?theme.primary:theme.border,theme.radiusDp));}
    private LinearLayout panel(){LinearLayout c=PremiumUi.col(this);c.setPadding(dp(13),dp(11),dp(13),dp(11));c.setBackground(PremiumUi.panel(this,theme.surfaceAlt,theme.border,theme.radiusDp));return c;}
    private LinearLayout.LayoutParams marginLp(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,0,0,dp(7));return p;}
    private int dp(float v){return PremiumUi.dp(this,v);}private void margins(View v,int l,int t,int r,int b){if(!(v.getLayoutParams() instanceof LinearLayout.LayoutParams))return;LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)v.getLayoutParams();p.setMargins(dp(l),dp(t),dp(r),dp(b));v.setLayoutParams(p);}
    @Override protected void onDestroy(){io.shutdownNow();super.onDestroy();}
}

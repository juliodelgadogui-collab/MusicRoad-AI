package com.estradaplay.comunista;

import android.content.Intent;
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

/** One operational view for a company vehicle: driver, km, costs, maintenance, incidents and journeys. */
public final class CompanyVehicleDetailActivity extends ComponentActivity {
    public static final String EXTRA_VEHICLE_ID = "vehicle_id";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private EstradaTheme theme;
    private LinearLayout page, content;
    private TextView status;
    private int vehicleId;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!CompanyAccount.shouldUseCompanyCentral(this)) { finish(); return; }
        vehicleId = getIntent().getIntExtra(EXTRA_VEHICLE_ID, 0);
        if (vehicleId <= 0) { finish(); return; }
        build();
        load();
    }

    private void build() {
        theme = EstradaTheme.get(this);
        getWindow().setStatusBarColor(theme.background);
        getWindow().setNavigationBarColor(theme.background);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(theme.background);
        page = PremiumUi.col(this); page.setPadding(dp(18),dp(18),dp(18),dp(30)); scroll.addView(page,new ScrollView.LayoutParams(-1,-2)); setContentView(scroll);
        Button back = PremiumUi.button(this,"‹ FROTA",false); back.setOnClickListener(v->finish()); page.addView(back,new LinearLayout.LayoutParams(dp(110),dp(46)));
        TextView over = PremiumUi.overline(this,"VEÍCULO",theme.secondary); page.addView(over); margins(over,0,22,0,4);
        status = PremiumUi.text(this,"Carregando veículo…",11,theme.muted,false); page.addView(status); margins(status,0,5,0,12);
        content = PremiumUi.col(this); page.addView(content,new LinearLayout.LayoutParams(-1,-2));
    }

    private void load() {
        status.setText("Atualizando veículo…");
        io.execute(() -> {
            try {
                JSONObject j = new CompanyApi(this).vehicleDetail(vehicleId);
                runOnUiThread(() -> render(j));
            } catch (Throwable e) {
                String m = e.getMessage()==null ? "Não consegui carregar o veículo." : e.getMessage();
                runOnUiThread(() -> status.setText(m));
            }
        });
    }

    private void render(JSONObject root) {
        content.removeAllViews();
        JSONObject vehicle = root.optJSONObject("vehicle"); if (vehicle == null) vehicle = new JSONObject();
        JSONObject driver = root.optJSONObject("driver");
        JSONObject presence = root.optJSONObject("presence");
        JSONObject summary = root.optJSONObject("summary"); if (summary == null) summary = new JSONObject();
        JSONObject fuel = summary.optJSONObject("fuel_30d"); if (fuel == null) fuel = new JSONObject();
        JSONObject maintenance = summary.optJSONObject("maintenance"); if (maintenance == null) maintenance = new JSONObject();
        JSONObject incidents = summary.optJSONObject("incidents"); if (incidents == null) incidents = new JSONObject();

        String label = vehicle.optString("label","Veículo");
        content.addView(PremiumUi.text(this,label,29,theme.text,true));
        String model = vehicle.optString("model","").trim(); int year = vehicle.optInt("year",0);
        if (!model.isEmpty() || year > 0) content.addView(PremiumUi.text(this,model+(year>0?" · "+year:""),11,theme.muted,false));

        LinearLayout live = panel();
        live.addView(PremiumUi.overline(this,"OPERAÇÃO AGORA",theme.muted));
        String driverName = driver == null ? "Sem motorista responsável" : driver.optString("name","Motorista");
        TextView driverText = PremiumUi.text(this,driverName,17,theme.text,true); live.addView(driverText); margins(driverText,0,6,0,0);
        boolean online = presence != null && presence.optBoolean("online",false);
        String liveText;
        int liveColor;
        if (online) { liveText = "NA ESTRADA · "+Math.round(presence.optDouble("speed_kmh",0))+" km/h"; liveColor = theme.success; }
        else if (presence != null) { liveText = "FORA DE JORNADA · última posição "+presence.optString("last_seen_at",""); liveColor = theme.muted; }
        else { liveText = "SEM POSIÇÃO RECENTE"; liveColor = theme.muted; }
        live.addView(PremiumUi.text(this,liveText,10,liveColor,true));
        content.addView(live,marginLp(12));

        LinearLayout stats = PremiumUi.row(this);
        stat(stats,"KM HOJE",String.format(Locale.getDefault(),"%.0f",summary.optDouble("km_today",0)));
        stat(stats,"KM 30 DIAS",String.format(Locale.getDefault(),"%.0f",summary.optDouble("km_30d",0)));
        stat(stats,"KM ESTIMADO",String.format(Locale.getDefault(),"%.0f",vehicle.optDouble("estimated_km",0)));
        content.addView(stats,marginLp(12));

        LinearLayout cost = panel();
        cost.addView(PremiumUi.overline(this,"CUSTOS · 30 DIAS",theme.muted));
        cost.addView(PremiumUi.text(this,String.format(Locale.getDefault(),"R$ %.2f",fuel.optDouble("total_value",0)),22,theme.text,true));
        cost.addView(PremiumUi.text(this,String.format(Locale.getDefault(),"%.1f L · R$ %.3f/km",fuel.optDouble("liters",0),fuel.optDouble("cost_per_km",0)),11,theme.secondary,true));
        content.addView(cost,marginLp(8));

        LinearLayout alerts = panel();
        alerts.addView(PremiumUi.overline(this,"ATENÇÃO",theme.muted));
        int overdue = maintenance.optInt("overdue",0), soon = maintenance.optInt("soon",0), open = incidents.optInt("open",0), high = incidents.optInt("high",0);
        String maintenanceText = overdue>0 ? overdue+" manutenção(ões) vencida(s)" : (soon>0 ? soon+" manutenção(ões) próxima(s)" : "Manutenção sem pendência crítica");
        int maintenanceColor = overdue>0 ? theme.danger : (soon>0 ? theme.warning : theme.success);
        alerts.addView(PremiumUi.text(this,maintenanceText,12,maintenanceColor,true));
        String incidentText = high>0 ? high+" ocorrência(s) urgente(s)" : (open>0 ? open+" ocorrência(s) aberta(s)" : "Nenhuma ocorrência aberta");
        int incidentColor = high>0 ? theme.danger : (open>0 ? theme.warning : theme.success);
        alerts.addView(PremiumUi.text(this,incidentText,12,incidentColor,true));
        content.addView(alerts,marginLp(12));

        JSONArray journeys = root.optJSONArray("journeys"); if (journeys == null) journeys = new JSONArray();
        sectionTitle("ÚLTIMAS JORNADAS");
        for (int i=0;i<journeys.length();i++) {
            JSONObject j=journeys.optJSONObject(i); if(j==null)continue;
            LinearLayout row=panel();
            String km=String.format(Locale.getDefault(),"%.1f km",j.optDouble("distance_km",0));
            long sec=j.optLong("duration_s",0); String duration=formatDuration(sec);
            row.addView(PremiumUi.text(this,km+(duration.isEmpty()?"":" · "+duration),14,theme.text,true));
            row.addView(PremiumUi.text(this,j.optString("driver_name","Motorista"),10,theme.secondary,true));
            row.addView(PremiumUi.text(this,j.optString("started_at","")+(j.optString("ended_at","").isEmpty()?" · em andamento":" → "+j.optString("ended_at","")),9,theme.muted,false));
            content.addView(row,marginLp(6));
        }
        if(journeys.length()==0)content.addView(PremiumUi.text(this,"Nenhuma jornada registrada para este veículo.",11,theme.muted,false));

        JSONArray maintenanceRows=root.optJSONArray("maintenance_tasks"); if(maintenanceRows==null)maintenanceRows=new JSONArray();
        sectionTitle("MANUTENÇÃO");
        for(int i=0;i<maintenanceRows.length();i++){
            JSONObject m=maintenanceRows.optJSONObject(i);if(m==null)continue;String st=m.optString("status","EM DIA");int c="VENCIDA".equals(st)?theme.danger:("PRÓXIMA".equals(st)?theme.warning:theme.success);
            LinearLayout row=panel();row.addView(PremiumUi.text(this,m.optString("title","Manutenção"),13,theme.text,true));row.addView(PremiumUi.text(this,st,9,c,true));content.addView(row,marginLp(6));
        }
        if(maintenanceRows.length()==0)content.addView(PremiumUi.text(this,"Nenhuma manutenção cadastrada.",11,theme.muted,false));

        JSONArray incidentRows=root.optJSONArray("incidents"); if(incidentRows==null)incidentRows=new JSONArray();
        sectionTitle("OCORRÊNCIAS RECENTES");
        for(int i=0;i<incidentRows.length();i++){
            JSONObject it=incidentRows.optJSONObject(i);if(it==null)continue;boolean isOpen="open".equals(it.optString("status",""));String sev=it.optString("severity","");int c=isOpen&&"high".equals(sev)?theme.danger:(isOpen?theme.warning:theme.muted);
            LinearLayout row=panel();row.addView(PremiumUi.text(this,it.optString("title","Ocorrência"),13,theme.text,true));row.addView(PremiumUi.text(this,isOpen?"ABERTA":"RESOLVIDA",9,c,true));row.addView(PremiumUi.text(this,it.optString("created_at",""),9,theme.muted,false));content.addView(row,marginLp(6));
        }
        if(incidentRows.length()==0)content.addView(PremiumUi.text(this,"Nenhuma ocorrência registrada.",11,theme.muted,false));

        LinearLayout actions=PremiumUi.row(this);
        Button fuelButton=PremiumUi.button(this,"CUSTOS",false);fuelButton.setOnClickListener(v->startActivity(new Intent(this,CompanyFuelActivity.class)));actions.addView(fuelButton,new LinearLayout.LayoutParams(0,dp(50),1f));
        Button maintenanceButton=PremiumUi.button(this,"MANUTENÇÃO",false);maintenanceButton.setOnClickListener(v->startActivity(new Intent(this,CompanyMaintenanceActivity.class)));LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(0,dp(50),1f);mp.setMargins(dp(7),0,0,0);actions.addView(maintenanceButton,mp);
        content.addView(actions,marginLp(6));
        Button incidentsButton=PremiumUi.button(this,"OCORRÊNCIAS",false);incidentsButton.setOnClickListener(v->startActivity(new Intent(this,CompanyIncidentsActivity.class)));content.addView(incidentsButton,new LinearLayout.LayoutParams(-1,dp(50)));

        status.setText(online?"Dados atualizados · veículo em jornada":"Dados atualizados");
    }

    private LinearLayout panel(){LinearLayout p=PremiumUi.col(this);p.setPadding(dp(14),dp(13),dp(14),dp(13));p.setBackground(PremiumUi.panel(this,theme.surfaceAlt,theme.border,theme.radiusDp));return p;}
    private void stat(LinearLayout parent,String label,String value){LinearLayout c=PremiumUi.col(this);c.setGravity(Gravity.CENTER);c.setPadding(dp(6),dp(10),dp(6),dp(10));c.setBackground(PremiumUi.panel(this,theme.surfaceAlt,theme.border,theme.radiusDp));TextView v=PremiumUi.text(this,value,17,theme.text,true);v.setGravity(Gravity.CENTER);c.addView(v);TextView l=PremiumUi.overline(this,label,theme.muted);l.setGravity(Gravity.CENTER);c.addView(l);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(80),1f);if(parent.getChildCount()>0)p.setMargins(dp(6),0,0,0);parent.addView(c,p);}
    private void sectionTitle(String text){TextView t=PremiumUi.overline(this,text,theme.muted);content.addView(t);margins(t,0,17,0,7);}
    private String formatDuration(long s){if(s<=0)return "";long h=s/3600,m=(s%3600)/60;return h>0?h+"h"+String.format(Locale.getDefault(),"%02d",m):m+"min";}
    private LinearLayout.LayoutParams marginLp(int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,0,0,dp(bottom));return p;}
    private int dp(float v){return PremiumUi.dp(this,v);}private void margins(View v,int l,int t,int r,int b){if(!(v.getLayoutParams() instanceof LinearLayout.LayoutParams))return;LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)v.getLayoutParams();p.setMargins(dp(l),dp(t),dp(r),dp(b));v.setLayoutParams(p);}
    @Override protected void onDestroy(){io.shutdownNow();super.onDestroy();}
}

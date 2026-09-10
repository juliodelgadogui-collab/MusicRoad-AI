package com.estradaplay.comunista;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Fuel entries for fleet managers and the currently assigned company driver. */
public final class CompanyFuelActivity extends ComponentActivity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private EstradaTheme theme;
    private LinearLayout list;
    private TextView totalValue,litersValue,costKmValue,status;
    private JSONArray vehicles=new JSONArray();
    private JSONObject driverVehicle;
    private boolean manager;
    private int days=30;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        manager=CompanyAccount.shouldUseCompanyCentral(this);
        if(!manager&&!CompanyAccount.hasCompany(this)){finish();return;}
        build();load();
    }

    private void build(){
        theme=EstradaTheme.get(this);getWindow().setStatusBarColor(theme.background);getWindow().setNavigationBarColor(theme.background);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(theme.background);LinearLayout page=PremiumUi.col(this);page.setPadding(dp(18),dp(18),dp(18),dp(30));scroll.addView(page,new ScrollView.LayoutParams(-1,-2));setContentView(scroll);
        Button back=PremiumUi.button(this,"‹ VOLTAR",false);back.setOnClickListener(v->finish());page.addView(back,new LinearLayout.LayoutParams(dp(110),dp(46)));
        TextView over=PremiumUi.overline(this,manager?"CUSTOS DA FROTA":"ABASTECIMENTO",theme.secondary);page.addView(over);margins(over,0,22,0,4);
        page.addView(PremiumUi.text(this,manager?"Combustível e custo por km":"Registrar abastecimento",28,theme.text,true));
        TextView sub=PremiumUi.text(this,manager?"Acompanhe litros, gasto e custo aproximado por quilômetro registrado.":"O abastecimento fica ligado ao veículo que a empresa atribuiu a você.",12,theme.muted,false);page.addView(sub);margins(sub,0,7,0,14);

        if(manager){LinearLayout filters=PremiumUi.row(this);filters.addView(filter("HOJE",1),new LinearLayout.LayoutParams(0,dp(44),1));LinearLayout.LayoutParams p7=new LinearLayout.LayoutParams(0,dp(44),1);p7.setMargins(dp(7),0,0,0);filters.addView(filter("7 DIAS",7),p7);LinearLayout.LayoutParams p30=new LinearLayout.LayoutParams(0,dp(44),1);p30.setMargins(dp(7),0,0,0);filters.addView(filter("30 DIAS",30),p30);page.addView(filters);}

        LinearLayout summary=PremiumUi.row(this);totalValue=stat(summary,"GASTO","—");litersValue=stat(summary,"LITROS","—");costKmValue=stat(summary,"R$/KM","—");page.addView(summary,lp(-1,-2,0,12,0,0));
        Button add=PremiumUi.button(this,"REGISTRAR ABASTECIMENTO",true);add.setOnClickListener(v->startFuelEntry());page.addView(add,lp(-1,54,0,14,0,0));
        status=PremiumUi.text(this,"Carregando abastecimentos…",10,theme.muted,false);status.setGravity(Gravity.CENTER);page.addView(status);margins(status,0,10,0,10);
        list=PremiumUi.col(this);page.addView(list,new LinearLayout.LayoutParams(-1,-2));
    }

    private Button filter(String label,int value){Button b=PremiumUi.button(this,label,false);b.setOnClickListener(v->{days=value;load();});return b;}

    private TextView stat(LinearLayout parent,String label,String initial){LinearLayout c=PremiumUi.col(this);c.setGravity(Gravity.CENTER);c.setPadding(dp(8),dp(10),dp(8),dp(10));c.setBackground(PremiumUi.panel(this,theme.surfaceAlt,theme.border,theme.radiusDp));TextView v=PremiumUi.text(this,initial,18,theme.text,true);v.setGravity(Gravity.CENTER);c.addView(v);TextView l=PremiumUi.overline(this,label,theme.muted);l.setGravity(Gravity.CENTER);c.addView(l);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(80),1);if(parent.getChildCount()>0)p.setMargins(dp(6),0,0,0);parent.addView(c,p);return v;}

    private void load(){
        io.execute(()->{try{
            CompanyApi api=new CompanyApi(this);
            if(manager){JSONObject vr=api.vehicles();JSONArray vs=vr.optJSONArray("vehicles");vehicles=vs==null?new JSONArray():vs;}
            else{JSONObject ds=api.driverStatus();JSONObject company=ds.optJSONObject("company");driverVehicle=company==null?null:company.optJSONObject("active_vehicle");if(company!=null)CompanyAccount.saveCompany(this,company);}
            JSONObject response=api.fuel(days);runOnUiThread(()->render(response));
        }catch(Throwable e){String m=e.getMessage()==null?"Não consegui carregar os abastecimentos.":e.getMessage();runOnUiThread(()->status.setText(m));}});
    }

    private void render(JSONObject response){JSONObject s=response.optJSONObject("summary");if(s==null)s=new JSONObject();totalValue.setText(String.format(Locale.getDefault(),"R$ %.0f",s.optDouble("total_value",0)));litersValue.setText(String.format(Locale.getDefault(),"%.1f",s.optDouble("liters",0)));costKmValue.setText(String.format(Locale.getDefault(),"%.2f",s.optDouble("cost_per_km",0)));JSONArray entries=response.optJSONArray("entries");if(entries==null)entries=new JSONArray();list.removeAllViews();for(int i=0;i<entries.length();i++){JSONObject e=entries.optJSONObject(i);if(e==null)continue;LinearLayout c=PremiumUi.col(this);c.setPadding(dp(13),dp(12),dp(13),dp(12));c.setBackground(PremiumUi.panel(this,theme.surfaceAlt,theme.border,theme.radiusDp));LinearLayout top=PremiumUi.row(this);top.addView(PremiumUi.text(this,e.optString("vehicle_label","Veículo"),13,theme.text,true),new LinearLayout.LayoutParams(0,-2,1));TextView total=PremiumUi.text(this,String.format(Locale.getDefault(),"R$ %.2f",e.optDouble("total_value",0)),13,theme.secondary,true);total.setGravity(Gravity.RIGHT);top.addView(total,new LinearLayout.LayoutParams(dp(100),-2));c.addView(top);String meta=String.format(Locale.getDefault(),"%.1f L · R$ %.3f/L",e.optDouble("liters",0),e.optDouble("price_per_liter",0));c.addView(PremiumUi.text(this,meta,10,theme.muted,false));if(manager)c.addView(PremiumUi.text(this,e.optString("driver_name","Motorista"),10,theme.muted,false));String station=e.optString("station","").trim();if(!station.isEmpty())c.addView(PremiumUi.text(this,station,10,theme.muted,false));c.addView(PremiumUi.text(this,e.optString("filled_at",""),9,theme.muted,false));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,0,0,dp(7));list.addView(c,p);}status.setText(entries.length()+" abastecimento(s) no período");if(entries.length()==0)list.addView(PremiumUi.text(this,"Nenhum abastecimento registrado neste período.",11,theme.muted,false));}

    private void startFuelEntry(){if(manager){if(vehicles.length()==0){Toast.makeText(this,"Cadastre um veículo primeiro.",Toast.LENGTH_LONG).show();return;}String[] labels=new String[vehicles.length()];for(int i=0;i<vehicles.length();i++){JSONObject v=vehicles.optJSONObject(i);labels[i]=v==null?"Veículo":vehicleLabel(v);}new AlertDialog.Builder(this).setTitle("Veículo abastecido").setItems(labels,(d,w)->{JSONObject v=vehicles.optJSONObject(w);if(v!=null)fuelForm(v);}).setNegativeButton("Cancelar",null).show();}else{if(driverVehicle==null){Toast.makeText(this,"A empresa ainda não atribuiu um veículo a você.",Toast.LENGTH_LONG).show();return;}fuelForm(driverVehicle);}}

    private void fuelForm(JSONObject vehicle){LinearLayout form=PremiumUi.col(this);form.setPadding(dp(18),dp(8),dp(18),0);TextView selected=PremiumUi.text(this,vehicleLabel(vehicle),12,theme.secondary,true);form.addView(selected);margins(selected,0,0,0,8);EditText liters=field("Litros",true),total=field("Valor total (R$)",true),odo=field("Hodômetro opcional",true),station=field("Posto opcional",false),notes=field("Observação opcional",false);form.addView(liters);form.addView(total);form.addView(odo);form.addView(station);form.addView(notes);new AlertDialog.Builder(this).setTitle("Novo abastecimento").setView(form).setNegativeButton("Cancelar",null).setPositiveButton("Salvar",(d,w)->saveFuel(vehicle.optInt("id",0),parse(liters),parse(total),parse(odo),station.getText().toString(),notes.getText().toString())).show();}

    private void saveFuel(int vehicleId,double liters,double total,double odo,String station,String notes){if(liters<=0||total<=0){Toast.makeText(this,"Informe litros e valor total.",Toast.LENGTH_LONG).show();return;}status.setText("Salvando abastecimento…");io.execute(()->{try{new CompanyApi(this).addFuel(vehicleId,liters,total,odo,station,notes);runOnUiThread(this::load);}catch(Throwable e){String m=e.getMessage()==null?"Não consegui salvar o abastecimento.":e.getMessage();runOnUiThread(()->status.setText(m));}});}

    private EditText field(String hint,boolean number){EditText e=new EditText(this);e.setSingleLine(true);e.setHint(hint);e.setTextColor(theme.text);e.setHintTextColor(theme.muted);e.setPadding(dp(12),0,dp(12),0);e.setBackground(PremiumUi.panel(this,theme.surfaceAlt,theme.border,theme.radiusDp));if(number)e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(52));p.setMargins(0,0,0,dp(7));e.setLayoutParams(p);return e;}
    private double parse(EditText e){try{return Double.parseDouble(e.getText().toString().trim().replace(',','.'));}catch(Throwable x){return 0;}}
    private String vehicleLabel(JSONObject v){String n=v.optString("nickname","").trim(),p=v.optString("plate","").trim(),m=v.optString("model","").trim();if(!n.isEmpty())return n+(p.isEmpty()?"":" · "+p);if(!p.isEmpty())return p;return m.isEmpty()?"Veículo":m;}
    private int dp(float v){return PremiumUi.dp(this,v);}private LinearLayout.LayoutParams lp(int w,int h,int l,int t,int r,int b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h));p.setMargins(dp(l),dp(t),dp(r),dp(b));return p;}private void margins(View v,int l,int t,int r,int b){if(!(v.getLayoutParams() instanceof LinearLayout.LayoutParams))return;LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)v.getLayoutParams();p.setMargins(dp(l),dp(t),dp(r),dp(b));v.setLayoutParams(p);}
    @Override protected void onDestroy(){io.shutdownNow();super.onDestroy();}
}

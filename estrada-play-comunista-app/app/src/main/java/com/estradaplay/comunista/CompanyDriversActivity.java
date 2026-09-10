package com.estradaplay.comunista;

import android.app.AlertDialog;
import android.os.Bundle;
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

/** Company driver roster and vehicle assignment. */
public final class CompanyDriversActivity extends ComponentActivity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private EstradaTheme theme;
    private LinearLayout list;
    private TextView status;
    private JSONArray vehicles = new JSONArray();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!CompanyAccount.shouldUseCompanyCentral(this)) { finish(); return; }
        build();
        load();
    }

    private void build() {
        theme = EstradaTheme.get(this);
        getWindow().setStatusBarColor(theme.background);
        getWindow().setNavigationBarColor(theme.background);
        ScrollView scroll = new ScrollView(this); scroll.setBackgroundColor(theme.background);
        LinearLayout page = PremiumUi.col(this); page.setPadding(dp(18),dp(18),dp(18),dp(30));
        scroll.addView(page,new ScrollView.LayoutParams(-1,-2)); setContentView(scroll);

        Button back=PremiumUi.button(this,"‹ EMPRESA",false); back.setOnClickListener(v->finish());
        page.addView(back,new LinearLayout.LayoutParams(dp(110),dp(46)));
        TextView over=PremiumUi.overline(this,"MOTORISTAS",theme.secondary); page.addView(over); margins(over,0,22,0,4);
        page.addView(PremiumUi.text(this,"Equipe da frota",28,theme.text,true));
        TextView sub=PremiumUi.text(this,"Vincule uma conta Estrada Play já existente e defina qual veículo ela está dirigindo.",12,theme.muted,false); page.addView(sub); margins(sub,0,7,0,16);
        Button add=PremiumUi.button(this,"ADICIONAR MOTORISTA",true); add.setOnClickListener(v->addDriver()); page.addView(add,new LinearLayout.LayoutParams(-1,dp(54)));
        status=PremiumUi.text(this,"Carregando equipe…",11,theme.muted,false); status.setGravity(Gravity.CENTER); page.addView(status); margins(status,0,10,0,10);
        list=PremiumUi.col(this); page.addView(list,new LinearLayout.LayoutParams(-1,-2));
    }

    private void load() {
        io.execute(() -> {
            try {
                CompanyApi api = new CompanyApi(this);
                JSONObject vehicleResponse = api.vehicles();
                JSONObject driverResponse = api.drivers();
                JSONArray vs = vehicleResponse.optJSONArray("vehicles"); if (vs == null) vs = new JSONArray();
                JSONArray ds = driverResponse.optJSONArray("drivers"); if (ds == null) ds = new JSONArray();
                JSONArray finalVs=vs, finalDs=ds;
                runOnUiThread(() -> { vehicles=finalVs; render(finalDs); });
            } catch (Throwable e) {
                runOnUiThread(() -> status.setText("Não consegui carregar a equipe agora."));
            }
        });
    }

    private void render(JSONArray rows) {
        list.removeAllViews(); status.setText(rows.length()+" motorista(s) vinculado(s)");
        for(int i=0;i<rows.length();i++) {
            JSONObject d=rows.optJSONObject(i); if(d==null) continue;
            LinearLayout card=PremiumUi.col(this); card.setPadding(dp(14),dp(14),dp(14),dp(14)); card.setBackground(PremiumUi.panel(this,theme.surfaceAlt,theme.border,theme.radiusDp));
            String name=d.optString("name","Motorista"); String username=d.optString("username","");
            card.addView(PremiumUi.text(this,name,16,theme.text,true));
            if(!username.isEmpty()) card.addView(PremiumUi.text(this,"@"+username,10,theme.muted,false));
            JSONObject v=d.optJSONObject("vehicle");
            String vehicle=v==null?"Sem veículo atribuído":vehicleLabel(v);
            TextView assignment=PremiumUi.text(this,"Veículo · "+vehicle,11,v==null?theme.danger:theme.success,true); card.addView(assignment); margins(assignment,0,8,0,0);
            TextView km=PremiumUi.text(this,String.format(Locale.getDefault(),"Hoje · %.1f km",d.optDouble("km_today",0.0)),11,theme.muted,false); card.addView(km); margins(km,0,4,0,0);
            String presence=d.optString("presence","");
            if(!presence.isEmpty()) { TextView p=PremiumUi.text(this,presence,10,theme.muted,false); card.addView(p); margins(p,0,3,0,0); }
            Button assign=PremiumUi.button(this,v==null?"VINCULAR VEÍCULO":"TROCAR VEÍCULO",false); assign.setOnClickListener(x->chooseVehicle(d)); card.addView(assign,new LinearLayout.LayoutParams(-1,dp(44))); margins(assign,0,10,0,0);
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2); cp.setMargins(0,0,0,dp(8)); list.addView(card,cp);
        }
    }

    private void addDriver() {
        EditText input=new EditText(this); input.setSingleLine(true); input.setHint("Usuário ou e-mail"); input.setTextColor(theme.text); input.setHintTextColor(theme.muted); input.setPadding(dp(12),0,dp(12),0);
        input.setBackground(PremiumUi.panel(this,theme.surfaceAlt,theme.border,theme.radiusDp));
        LinearLayout box=PremiumUi.col(this); box.setPadding(dp(18),dp(8),dp(18),0); box.addView(input,new LinearLayout.LayoutParams(-1,dp(52)));
        new AlertDialog.Builder(this).setTitle("Adicionar motorista").setMessage("A pessoa precisa ter uma conta Estrada Play.").setView(box).setNegativeButton("Cancelar",null)
                .setPositiveButton("Adicionar",(d,w)->linkDriver(input.getText().toString())).show();
    }

    private void linkDriver(String identifier) {
        String value=identifier==null?"":identifier.trim(); if(value.length()<3){Toast.makeText(this,"Informe usuário ou e-mail.",Toast.LENGTH_LONG).show();return;}
        status.setText("Vinculando motorista…"); io.execute(() -> {
            try { new CompanyApi(this).addDriver(value); runOnUiThread(this::load); }
            catch(Throwable e){runOnUiThread(()->status.setText(e.getMessage()==null?"Não consegui vincular o motorista.":e.getMessage()));}
        });
    }

    private void chooseVehicle(JSONObject driver) {
        if(vehicles.length()==0){Toast.makeText(this,"Cadastre um veículo primeiro.",Toast.LENGTH_LONG).show();return;}
        String[] labels=new String[vehicles.length()]; for(int i=0;i<vehicles.length();i++){JSONObject v=vehicles.optJSONObject(i);labels[i]=v==null?"Veículo":vehicleLabel(v);}
        new AlertDialog.Builder(this).setTitle("Veículo de "+driver.optString("name","motorista")).setItems(labels,(dialog,which)->{
            JSONObject v=vehicles.optJSONObject(which); if(v!=null)assign(driver.optInt("user_id",0),v.optInt("id",0));
        }).setNegativeButton("Cancelar",null).show();
    }

    private void assign(int userId,int vehicleId) {
        if(userId<=0||vehicleId<=0)return; status.setText("Atualizando responsável…"); io.execute(() -> {
            try { new CompanyApi(this).assignVehicle(userId,vehicleId); runOnUiThread(this::load); }
            catch(Throwable e){runOnUiThread(()->status.setText(e.getMessage()==null?"Não consegui vincular o veículo.":e.getMessage()));}
        });
    }

    private String vehicleLabel(JSONObject v){String nick=v.optString("nickname","").trim();String plate=v.optString("plate","").trim();return nick.isEmpty()?plate:(nick+(plate.isEmpty()?"":" · "+plate));}
    private int dp(float v){return PremiumUi.dp(this,v);}    
    private void margins(View v,int l,int t,int r,int b){if(!(v.getLayoutParams() instanceof LinearLayout.LayoutParams))return;LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)v.getLayoutParams();p.setMargins(dp(l),dp(t),dp(r),dp(b));v.setLayoutParams(p);}    
    @Override protected void onDestroy(){io.shutdownNow();super.onDestroy();}
}

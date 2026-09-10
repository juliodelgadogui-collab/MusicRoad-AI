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

/** Company vehicle registry. */
public final class CompanyFleetActivity extends ComponentActivity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private EstradaTheme theme;
    private LinearLayout list;
    private TextView status;

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
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(theme.background);
        LinearLayout page = PremiumUi.col(this);
        page.setPadding(dp(18), dp(18), dp(18), dp(30));
        scroll.addView(page, new ScrollView.LayoutParams(-1,-2));
        setContentView(scroll);

        Button back = PremiumUi.button(this, "‹ EMPRESA", false);
        back.setOnClickListener(v -> finish());
        page.addView(back, new LinearLayout.LayoutParams(dp(110), dp(46)));
        TextView over = PremiumUi.overline(this, "FROTA", theme.secondary);
        page.addView(over); margins(over,0,22,0,4);
        page.addView(PremiumUi.text(this, "Veículos da empresa", 28, theme.text, true));
        TextView sub = PremiumUi.text(this, "Cadastre placa, modelo, identificação interna e hodômetro de referência.", 12, theme.muted, false);
        page.addView(sub); margins(sub,0,7,0,16);

        Button add = PremiumUi.button(this, "CADASTRAR VEÍCULO", true);
        add.setOnClickListener(v -> editVehicle(null));
        page.addView(add, new LinearLayout.LayoutParams(-1, dp(54)));

        status = PremiumUi.text(this, "Carregando frota…", 11, theme.muted, false);
        status.setGravity(Gravity.CENTER);
        page.addView(status); margins(status,0,10,0,10);
        list = PremiumUi.col(this);
        page.addView(list, new LinearLayout.LayoutParams(-1,-2));
    }

    private void load() {
        io.execute(() -> {
            try {
                JSONObject j = new CompanyApi(this).vehicles();
                JSONArray rows = j.optJSONArray("vehicles");
                if (rows == null) rows = new JSONArray();
                JSONArray finalRows = rows;
                runOnUiThread(() -> render(finalRows));
            } catch (Throwable e) {
                runOnUiThread(() -> status.setText("Não consegui carregar a frota agora."));
            }
        });
    }

    private void render(JSONArray rows) {
        list.removeAllViews();
        status.setText(rows.length() + " veículo(s) cadastrado(s)");
        for (int i=0;i<rows.length();i++) {
            JSONObject v = rows.optJSONObject(i); if (v == null) continue;
            LinearLayout card = PremiumUi.col(this);
            card.setPadding(dp(14),dp(14),dp(14),dp(14));
            card.setBackground(PremiumUi.panel(this, theme.surfaceAlt, theme.border, theme.radiusDp));
            String plate = v.optString("plate", "SEM PLACA");
            String nick = v.optString("nickname", "").trim();
            card.addView(PremiumUi.text(this, nick.isEmpty()?plate:(nick+" · "+plate), 16, theme.text, true));
            String model = v.optString("model", "").trim();
            int year = v.optInt("year",0);
            if (!model.isEmpty() || year>0) card.addView(PremiumUi.text(this, model + (year>0?" · "+year:""), 11, theme.muted, false));
            double odo = v.optDouble("odometer_km",0.0);
            TextView km = PremiumUi.text(this, String.format(Locale.getDefault(), "Hodômetro referência · %.0f km", odo), 10, theme.muted, false);
            card.addView(km); margins(km,0,5,0,0);
            Button edit = PremiumUi.button(this, "EDITAR", false);
            edit.setOnClickListener(x -> editVehicle(v));
            card.addView(edit, new LinearLayout.LayoutParams(-1, dp(44))); margins(edit,0,10,0,0);
            list.addView(card, marginLp());
        }
    }

    private void editVehicle(JSONObject existing) {
        LinearLayout form = PremiumUi.col(this); form.setPadding(dp(18),dp(8),dp(18),0);
        EditText plate = field("Placa");
        EditText nickname = field("Nome interno (ex.: Caminhão 07)");
        EditText model = field("Modelo");
        EditText year = field("Ano"); year.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText odo = field("Hodômetro atual"); odo.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (existing != null) {
            plate.setText(existing.optString("plate","")); nickname.setText(existing.optString("nickname","")); model.setText(existing.optString("model",""));
            if (existing.optInt("year",0)>0) year.setText(String.valueOf(existing.optInt("year")));
            odo.setText(String.format(Locale.US,"%.0f",existing.optDouble("odometer_km",0)));
        }
        form.addView(plate); form.addView(nickname); form.addView(model); form.addView(year); form.addView(odo);
        new AlertDialog.Builder(this).setTitle(existing==null?"Novo veículo":"Editar veículo").setView(form)
                .setNegativeButton("Cancelar",null)
                .setPositiveButton("Salvar",(d,w)->saveVehicle(existing,plate,nickname,model,year,odo)).show();
    }

    private void saveVehicle(JSONObject existing, EditText plate, EditText nickname, EditText model, EditText year, EditText odo) {
        String p = plate.getText().toString().trim().toUpperCase(Locale.ROOT);
        if (p.length()<5) { Toast.makeText(this,"Informe uma placa válida.",Toast.LENGTH_LONG).show(); return; }
        int y = parseInt(year.getText().toString()); double o = parseDouble(odo.getText().toString()); int id = existing==null?0:existing.optInt("id",0);
        status.setText("Salvando veículo…");
        io.execute(() -> {
            try { new CompanyApi(this).saveVehicle(id,p,nickname.getText().toString(),model.getText().toString(),y,o); runOnUiThread(this::load); }
            catch (Throwable e) { runOnUiThread(() -> status.setText(e.getMessage()==null?"Não consegui salvar o veículo.":e.getMessage())); }
        });
    }

    private EditText field(String hint) {
        EditText e = new EditText(this); e.setHint(hint); e.setSingleLine(true); e.setTextColor(theme.text); e.setHintTextColor(theme.muted);
        e.setBackground(PremiumUi.panel(this,theme.surfaceAlt,theme.border,theme.radiusDp)); e.setPadding(dp(12),0,dp(12),0);
        e.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(52))); return e;
    }
    private int parseInt(String s){try{return Integer.parseInt(s.trim());}catch(Throwable e){return 0;}}
    private double parseDouble(String s){try{return Double.parseDouble(s.trim().replace(',','.'));}catch(Throwable e){return 0;}}
    private LinearLayout.LayoutParams marginLp(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,0,0,dp(8));return p;}
    private int dp(float v){return PremiumUi.dp(this,v);}    
    private void margins(View v,int l,int t,int r,int b){if(!(v.getLayoutParams() instanceof LinearLayout.LayoutParams))return;LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)v.getLayoutParams();p.setMargins(dp(l),dp(t),dp(r),dp(b));v.setLayoutParams(p);}    
    @Override protected void onDestroy(){io.shutdownNow();super.onDestroy();}
}

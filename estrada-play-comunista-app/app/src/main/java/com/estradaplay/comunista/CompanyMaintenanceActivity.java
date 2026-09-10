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

/** Fleet maintenance tied to company vehicles and tracked mileage. */
public final class CompanyMaintenanceActivity extends ComponentActivity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private EstradaTheme theme;
    private LinearLayout list;
    private TextView status, overdueValue, soonValue, okValue;
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
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(theme.background);
        LinearLayout page = PremiumUi.col(this);
        page.setPadding(dp(18),dp(18),dp(18),dp(30));
        scroll.addView(page,new ScrollView.LayoutParams(-1,-2));
        setContentView(scroll);

        Button back = PremiumUi.button(this,"‹ EMPRESA",false);
        back.setOnClickListener(v -> finish());
        page.addView(back,new LinearLayout.LayoutParams(dp(110),dp(46)));
        TextView over = PremiumUi.overline(this,"MANUTENÇÃO DA FROTA",theme.secondary);
        page.addView(over); margins(over,0,22,0,4);
        page.addView(PremiumUi.text(this,"Serviços por veículo",28,theme.text,true));
        TextView sub = PremiumUi.text(this,
                "O Estrada Play combina o hodômetro de referência com os quilômetros registrados pela frota para antecipar cada serviço.",
                12,theme.muted,false);
        page.addView(sub); margins(sub,0,7,0,14);

        LinearLayout summary = PremiumUi.row(this);
        overdueValue = stat(summary,"VENCIDAS","—",theme.danger);
        soonValue = stat(summary,"PRÓXIMAS","—",theme.warning);
        okValue = stat(summary,"EM DIA","—",theme.success);
        page.addView(summary,new LinearLayout.LayoutParams(-1,-2));

        Button add = PremiumUi.button(this,"CADASTRAR MANUTENÇÃO",true);
        add.setOnClickListener(v -> chooseVehicle());
        page.addView(add,lp(-1,54,0,14,0,0));

        status = PremiumUi.text(this,"Carregando manutenção da frota…",11,theme.muted,false);
        status.setGravity(Gravity.CENTER);
        page.addView(status); margins(status,0,10,0,10);
        list = PremiumUi.col(this);
        page.addView(list,new LinearLayout.LayoutParams(-1,-2));
    }

    private TextView stat(LinearLayout parent,String label,String initial,int accent) {
        LinearLayout card=PremiumUi.col(this);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(8),dp(11),dp(8),dp(11));
        card.setBackground(PremiumUi.panel(this,theme.surfaceAlt,theme.border,theme.radiusDp));
        TextView value=PremiumUi.text(this,initial,20,accent,true); value.setGravity(Gravity.CENTER); card.addView(value);
        TextView small=PremiumUi.overline(this,label,theme.muted); small.setGravity(Gravity.CENTER); card.addView(small);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(82),1f); if(parent.getChildCount()>0)p.setMargins(dp(6),0,0,0); parent.addView(card,p); return value;
    }

    private void load() {
        io.execute(() -> {
            try {
                CompanyApi api = new CompanyApi(this);
                JSONObject vehicleResponse = api.vehicles();
                JSONObject maintenanceResponse = api.maintenance();
                JSONArray vs = vehicleResponse.optJSONArray("vehicles"); if (vs == null) vs = new JSONArray();
                JSONArray tasks = maintenanceResponse.optJSONArray("tasks"); if (tasks == null) tasks = new JSONArray();
                JSONObject summary = maintenanceResponse.optJSONObject("summary"); if (summary == null) summary = new JSONObject();
                JSONArray finalVs=vs, finalTasks=tasks; JSONObject finalSummary=summary;
                runOnUiThread(() -> { vehicles=finalVs; render(finalTasks,finalSummary); });
            } catch (Throwable e) {
                String m=e.getMessage()==null?"Não consegui carregar a manutenção da frota.":e.getMessage();
                runOnUiThread(() -> status.setText(m));
            }
        });
    }

    private void render(JSONArray tasks,JSONObject summary) {
        overdueValue.setText(String.valueOf(summary.optInt("overdue",0)));
        soonValue.setText(String.valueOf(summary.optInt("soon",0)));
        okValue.setText(String.valueOf(summary.optInt("ok",0)));
        status.setText(tasks.length()+" manutenção(ões) ativa(s)");
        list.removeAllViews();
        for(int i=0;i<tasks.length();i++) {
            JSONObject task=tasks.optJSONObject(i); if(task==null)continue;
            String state=task.optString("status","EM DIA");
            int accent="VENCIDA".equals(state)?theme.danger:("PRÓXIMA".equals(state)?theme.warning:theme.success);
            LinearLayout card=PremiumUi.col(this); card.setPadding(dp(14),dp(14),dp(14),dp(14)); card.setBackground(PremiumUi.panel(this,theme.surfaceAlt,accent,theme.radiusDp));
            LinearLayout top=PremiumUi.row(this); top.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(PremiumUi.text(this,task.optString("title","Manutenção"),15,theme.text,true),new LinearLayout.LayoutParams(0,-2,1f));
            TextView badge=PremiumUi.text(this,state,9,accent,true); badge.setGravity(Gravity.RIGHT); top.addView(badge,new LinearLayout.LayoutParams(dp(86),-2)); card.addView(top);
            card.addView(PremiumUi.text(this,task.optString("vehicle_label","Veículo"),11,theme.secondary,true));
            double current=task.optDouble("current_km",0.0);
            TextView currentText=PremiumUi.text(this,String.format(Locale.getDefault(),"Estimado atual · %.0f km",current),10,theme.muted,false); card.addView(currentText); margins(currentText,0,6,0,0);

            double next=task.optDouble("next_due_km",Double.NaN);
            double remaining=task.optDouble("km_remaining",Double.NaN);
            if(Double.isFinite(next)) {
                String text=String.format(Locale.getDefault(),"Próxima por km · %.0f km",next);
                if(Double.isFinite(remaining)) text += remaining<=0 ? " · vencida" : String.format(Locale.getDefault()," · faltam %.0f km",remaining);
                card.addView(PremiumUi.text(this,text,10,remaining<=0?theme.danger:theme.muted,remaining<=1000));
            }
            String dueDate=task.optString("next_due_date","").trim();
            if(!dueDate.isEmpty()&&!"null".equalsIgnoreCase(dueDate)) {
                int days=task.optInt("days_remaining",Integer.MAX_VALUE);
                String text="Próxima por data · "+dueDate;
                if(days!=Integer.MAX_VALUE) text += days<0?" · vencida":(days==0?" · hoje":" · "+days+" dia(s)");
                card.addView(PremiumUi.text(this,text,10,days<0?theme.danger:theme.muted,days<=30));
            }
            String notes=task.optString("notes","").trim(); if(!notes.isEmpty()){TextView n=PremiumUi.text(this,notes,10,theme.muted,false);card.addView(n);margins(n,0,6,0,0);}
            Button complete=PremiumUi.button(this,"REGISTRAR SERVIÇO FEITO",false); complete.setOnClickListener(v -> confirmComplete(task)); card.addView(complete,new LinearLayout.LayoutParams(-1,dp(46))); margins(complete,0,11,0,0);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,0,0,dp(8));list.addView(card,p);
        }
        if(tasks.length()==0)list.addView(PremiumUi.text(this,"Nenhuma manutenção cadastrada. Crie a primeira revisão da frota.",11,theme.muted,false));
    }

    private void chooseVehicle() {
        if(vehicles.length()==0){Toast.makeText(this,"Cadastre um veículo primeiro.",Toast.LENGTH_LONG).show();return;}
        String[] labels=new String[vehicles.length()];
        for(int i=0;i<vehicles.length();i++){JSONObject v=vehicles.optJSONObject(i);labels[i]=v==null?"Veículo":vehicleLabel(v);}
        new AlertDialog.Builder(this).setTitle("Escolha o veículo").setItems(labels,(dialog,which)->{
            JSONObject v=vehicles.optJSONObject(which); if(v!=null)editTask(v);
        }).setNegativeButton("Cancelar",null).show();
    }

    private void editTask(JSONObject vehicle) {
        LinearLayout form=PremiumUi.col(this);form.setPadding(dp(18),dp(8),dp(18),0);
        TextView selected=PremiumUi.text(this,vehicleLabel(vehicle),12,theme.secondary,true);form.addView(selected);margins(selected,0,0,0,8);
        EditText title=field("Serviço (ex.: troca de óleo)",false);
        EditText km=field("Intervalo em km (ex.: 20000)",true);
        EditText days=field("Intervalo em dias (ex.: 180)",true);
        EditText notes=field("Observação opcional",false);
        form.addView(title);form.addView(km);form.addView(days);form.addView(notes);
        new AlertDialog.Builder(this).setTitle("Nova manutenção").setView(form).setNegativeButton("Cancelar",null)
                .setPositiveButton("Salvar",(d,w)->saveTask(vehicle.optInt("id",0),title.getText().toString(),parseDouble(km.getText().toString()),parseInt(days.getText().toString()),notes.getText().toString())).show();
    }

    private void saveTask(int vehicleId,String title,double intervalKm,int intervalDays,String notes) {
        if(vehicleId<=0||title.trim().isEmpty()){Toast.makeText(this,"Informe o serviço.",Toast.LENGTH_LONG).show();return;}
        if(intervalKm<=0&&intervalDays<=0){Toast.makeText(this,"Informe intervalo em km ou dias.",Toast.LENGTH_LONG).show();return;}
        status.setText("Salvando manutenção…");
        io.execute(()->{try{new CompanyApi(this).saveMaintenanceTask(0,vehicleId,title,intervalKm,intervalDays,notes);runOnUiThread(this::load);}catch(Throwable e){String m=e.getMessage()==null?"Não consegui salvar a manutenção.":e.getMessage();runOnUiThread(()->status.setText(m));}});
    }

    private void confirmComplete(JSONObject task) {
        new AlertDialog.Builder(this).setTitle("Registrar serviço concluído?")
                .setMessage(task.optString("title","Manutenção")+" · "+task.optString("vehicle_label","Veículo")+"\n\nO próximo vencimento será recalculado a partir da quilometragem estimada atual.")
                .setNegativeButton("Cancelar",null).setPositiveButton("Concluir",(d,w)->complete(task.optInt("id",0))).show();
    }

    private void complete(int id) {
        if(id<=0)return;status.setText("Registrando serviço…");
        io.execute(()->{try{new CompanyApi(this).completeMaintenanceTask(id);runOnUiThread(this::load);}catch(Throwable e){String m=e.getMessage()==null?"Não consegui registrar o serviço.":e.getMessage();runOnUiThread(()->status.setText(m));}});
    }

    private EditText field(String hint,boolean number) {
        EditText e=new EditText(this);e.setSingleLine(true);e.setHint(hint);e.setTextColor(theme.text);e.setHintTextColor(theme.muted);e.setPadding(dp(12),0,dp(12),0);e.setBackground(PremiumUi.panel(this,theme.surfaceAlt,theme.border,theme.radiusDp));
        if(number)e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(52));p.setMargins(0,0,0,dp(7));e.setLayoutParams(p);return e;
    }
    private String vehicleLabel(JSONObject v){String nick=v.optString("nickname","").trim();String plate=v.optString("plate","").trim();String model=v.optString("model","").trim();if(!nick.isEmpty())return nick+(plate.isEmpty()?"":" · "+plate);if(!plate.isEmpty())return plate;return model.isEmpty()?"Veículo":model;}
    private int parseInt(String s){try{return Integer.parseInt(s.trim());}catch(Throwable e){return 0;}}
    private double parseDouble(String s){try{return Double.parseDouble(s.trim().replace(',','.'));}catch(Throwable e){return 0.0;}}
    private int dp(float v){return PremiumUi.dp(this,v);}private LinearLayout.LayoutParams lp(int w,int h,int l,int t,int r,int b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h));p.setMargins(dp(l),dp(t),dp(r),dp(b));return p;}private void margins(View v,int l,int t,int r,int b){if(!(v.getLayoutParams() instanceof LinearLayout.LayoutParams))return;LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)v.getLayoutParams();p.setMargins(dp(l),dp(t),dp(r),dp(b));v.setLayoutParams(p);}
    @Override protected void onDestroy(){io.shutdownNow();super.onDestroy();}
}

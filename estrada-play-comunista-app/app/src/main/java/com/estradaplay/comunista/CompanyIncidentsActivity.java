package com.estradaplay.comunista;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Company incident inbox. Drivers report; managers receive and resolve. */
public final class CompanyIncidentsActivity extends ComponentActivity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private EstradaTheme theme;
    private LinearLayout list;
    private TextView status;
    private boolean manager;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        if(!CompanyAccount.hasCompany(this)){finish();return;}
        manager=CompanyAccount.shouldUseCompanyCentral(this);
        build();load();
    }

    private void build(){
        theme=EstradaTheme.get(this);getWindow().setStatusBarColor(theme.background);getWindow().setNavigationBarColor(theme.background);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(theme.background);
        LinearLayout page=PremiumUi.col(this);page.setPadding(dp(18),dp(18),dp(18),dp(30));scroll.addView(page,new ScrollView.LayoutParams(-1,-2));setContentView(scroll);
        Button back=PremiumUi.button(this,manager?"‹ EMPRESA":"‹ JORNADA",false);back.setOnClickListener(v->finish());page.addView(back,new LinearLayout.LayoutParams(dp(118),dp(46)));
        TextView over=PremiumUi.overline(this,"OCORRÊNCIAS",theme.secondary);page.addView(over);margins(over,0,22,0,4);
        page.addView(PremiumUi.text(this,manager?"Ocorrências da frota":"Reportar à empresa",28,theme.text,true));
        TextView sub=PremiumUi.text(this,manager?"Acompanhe panes, pneus, acidentes, atrasos, carga e problemas de estrada informados pelos motoristas.":"Registre uma ocorrência do veículo. A última posição recente da jornada é anexada automaticamente quando disponível.",12,theme.muted,false);page.addView(sub);margins(sub,0,7,0,14);
        if(!manager){Button report=PremiumUi.button(this,"NOVA OCORRÊNCIA",true);report.setOnClickListener(v->report());page.addView(report,new LinearLayout.LayoutParams(-1,dp(54)));margins(report,0,0,0,10);}
        status=PremiumUi.text(this,"Carregando ocorrências…",11,theme.muted,false);status.setGravity(Gravity.CENTER);page.addView(status);margins(status,0,4,0,10);
        list=PremiumUi.col(this);page.addView(list,new LinearLayout.LayoutParams(-1,-2));
    }

    private void load(){
        io.execute(()->{try{JSONObject j=new CompanyApi(this).incidents();runOnUiThread(()->render(j));}catch(Throwable e){String m=e.getMessage()==null?"Não consegui carregar as ocorrências.":e.getMessage();runOnUiThread(()->status.setText(m));}});
    }

    private void render(JSONObject j){
        JSONObject summary=j.optJSONObject("summary");int open=summary==null?0:summary.optInt("open",0);int high=summary==null?0:summary.optInt("high",0);
        status.setText(open+" aberta(s)"+(high>0?" · "+high+" urgente(s)":""));status.setTextColor(high>0?theme.danger:(open>0?theme.warning:theme.muted));
        list.removeAllViews();JSONArray rows=j.optJSONArray("incidents");if(rows==null)rows=new JSONArray();
        for(int i=0;i<rows.length();i++){
            JSONObject r=rows.optJSONObject(i);if(r==null)continue;boolean isOpen="open".equalsIgnoreCase(r.optString("status",""));String severity=r.optString("severity","medium");
            int accent="high".equals(severity)?theme.danger:("low".equals(severity)?theme.success:theme.warning);
            LinearLayout card=PremiumUi.col(this);card.setPadding(dp(14),dp(13),dp(14),dp(13));card.setBackground(PremiumUi.panel(this,theme.surfaceAlt,isOpen?accent:theme.border,theme.radiusDp));
            LinearLayout top=PremiumUi.row(this);top.setGravity(Gravity.CENTER_VERTICAL);top.addView(PremiumUi.text(this,r.optString("title","Ocorrência"),15,theme.text,true),new LinearLayout.LayoutParams(0,-2,1f));
            TextView state=PremiumUi.text(this,isOpen?severityLabel(severity):"RESOLVIDA",9,isOpen?accent:theme.muted,true);state.setGravity(Gravity.RIGHT);top.addView(state,new LinearLayout.LayoutParams(dp(95),-2));card.addView(top);
            String driver=r.optString("driver_name","Motorista");String vehicle=r.optString("vehicle_label","");card.addView(PremiumUi.text(this,driver+(vehicle.isEmpty()?"":" · "+vehicle),10,theme.secondary,true));
            String notes=r.optString("notes","").trim();if(!notes.isEmpty()){TextView n=PremiumUi.text(this,notes,11,theme.text,false);card.addView(n);margins(n,0,7,0,0);}
            String when=r.optString("created_at","");TextView date=PremiumUi.text(this,when,9,theme.muted,false);card.addView(date);margins(date,0,7,0,0);
            if(manager&&isOpen){Button done=PremiumUi.button(this,"MARCAR COMO RESOLVIDA",false);done.setOnClickListener(v->confirmResolve(r));card.addView(done,new LinearLayout.LayoutParams(-1,dp(44)));margins(done,0,10,0,0);}
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.setMargins(0,0,0,dp(8));list.addView(card,cp);
        }
        if(rows.length()==0)list.addView(PremiumUi.text(this,"Nenhuma ocorrência registrada.",11,theme.muted,false));
    }

    private void report(){
        LinearLayout form=PremiumUi.col(this);form.setPadding(dp(18),dp(8),dp(18),0);
        Spinner type=new Spinner(this);String[] typeLabels={"Pane mecânica","Pneu / roda","Acidente","Atraso","Carga","Problema na estrada","Outra"};String[] typeValues={"breakdown","tire","accident","delay","load","road","other"};type.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,typeLabels));form.addView(type,new LinearLayout.LayoutParams(-1,dp(52)));
        Spinner severity=new Spinner(this);String[] severityLabels={"Normal","Baixa","Urgente"};String[] severityValues={"medium","low","high"};severity.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,severityLabels));form.addView(severity,new LinearLayout.LayoutParams(-1,dp(52)));
        EditText notes=new EditText(this);notes.setHint("Detalhes da ocorrência");notes.setMinLines(3);notes.setMaxLines(6);notes.setTextColor(theme.text);notes.setHintTextColor(theme.muted);notes.setPadding(dp(12),dp(10),dp(12),dp(10));notes.setBackground(PremiumUi.panel(this,theme.surfaceAlt,theme.border,theme.radiusDp));form.addView(notes,new LinearLayout.LayoutParams(-1,dp(110)));
        new AlertDialog.Builder(this).setTitle("Nova ocorrência").setView(form).setNegativeButton("Cancelar",null).setPositiveButton("Enviar",(d,w)->send(typeValues[type.getSelectedItemPosition()],severityValues[severity.getSelectedItemPosition()],notes.getText().toString())).show();
    }

    private void send(String type,String severity,String notes){
        status.setText("Enviando ocorrência…");io.execute(()->{try{new CompanyApi(this).addIncident(type,severity,notes);runOnUiThread(this::load);}catch(Throwable e){String m=e.getMessage()==null?"Não consegui registrar a ocorrência.":e.getMessage();runOnUiThread(()->status.setText(m));}});
    }

    private void confirmResolve(JSONObject incident){
        int id=incident.optInt("id",0);if(id<=0)return;
        new AlertDialog.Builder(this).setTitle("Encerrar ocorrência").setMessage("Marcar esta ocorrência como resolvida?").setNegativeButton("Cancelar",null).setPositiveButton("Resolver",(d,w)->resolve(id)).show();
    }

    private void resolve(int id){
        status.setText("Atualizando ocorrência…");io.execute(()->{try{new CompanyApi(this).resolveIncident(id);runOnUiThread(this::load);}catch(Throwable e){String m=e.getMessage()==null?"Não consegui encerrar a ocorrência.":e.getMessage();runOnUiThread(()->status.setText(m));}});
    }

    private String severityLabel(String value){if("high".equals(value))return"URGENTE";if("low".equals(value))return"BAIXA";return"NORMAL";}
    private int dp(float v){return PremiumUi.dp(this,v);}private void margins(View v,int l,int t,int r,int b){if(!(v.getLayoutParams() instanceof LinearLayout.LayoutParams))return;LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)v.getLayoutParams();p.setMargins(dp(l),dp(t),dp(r),dp(b));v.setLayoutParams(p);}    
    @Override protected void onDestroy(){io.shutdownNow();super.onDestroy();}
}

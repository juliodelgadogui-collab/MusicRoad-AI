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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Company-level control room for the live Estrada Play convoy. */
public final class CompanyConvoyActivity extends ComponentActivity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private EstradaTheme theme;
    private TextView operation, status;
    private LinearLayout roster;
    private Button create, open, close;
    private JSONObject activeConvoy;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!CompanyAccount.shouldUseCompanyCentral(this)) { finish(); return; }
        build(); refresh();
    }

    private void build() {
        theme=EstradaTheme.get(this);getWindow().setStatusBarColor(theme.background);getWindow().setNavigationBarColor(theme.background);
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(theme.background);LinearLayout page=PremiumUi.col(this);page.setPadding(dp(18),dp(18),dp(18),dp(30));scroll.addView(page,new ScrollView.LayoutParams(-1,-2));setContentView(scroll);
        Button back=PremiumUi.button(this,"‹ EMPRESA",false);back.setOnClickListener(v->finish());page.addView(back,new LinearLayout.LayoutParams(dp(110),dp(46)));
        TextView over=PremiumUi.overline(this,"COMBOIO DA EMPRESA",theme.secondary);page.addView(over);margins(over,0,23,0,4);
        page.addView(PremiumUi.text(this,"Operação em grupo",28,theme.text,true));
        TextView sub=PremiumUi.text(this,"Crie o comboio uma vez. Os motoristas vinculados à empresa passam a enxergar o código ativo no módulo de trabalho.",12,theme.muted,false);page.addView(sub);margins(sub,0,7,0,15);

        LinearLayout op=PremiumUi.col(this);op.setPadding(dp(16),dp(16),dp(16),dp(16));op.setBackground(PremiumUi.panel(this,theme.glass,theme.border,theme.radiusDp+2));op.addView(PremiumUi.overline(this,"OPERAÇÃO ATIVA",theme.muted));
        operation=PremiumUi.text(this,"Nenhum comboio publicado.",18,theme.text,true);op.addView(operation);margins(operation,0,7,0,0);
        create=PremiumUi.button(this,"CRIAR COMBOIO DA EMPRESA",true);create.setOnClickListener(v->createCompanyConvoy());op.addView(create,new LinearLayout.LayoutParams(-1,dp(54)));margins(create,0,12,0,0);
        open=PremiumUi.button(this,"ABRIR COMBOIO AO VIVO",false);open.setVisibility(View.GONE);open.setOnClickListener(v->startActivity(new Intent(this,ConvoyActivity.class)));op.addView(open,new LinearLayout.LayoutParams(-1,dp(50)));margins(open,0,8,0,0);
        close=PremiumUi.button(this,"ENCERRAR OPERAÇÃO",false);close.setVisibility(View.GONE);close.setOnClickListener(v->closeOperation());op.addView(close,new LinearLayout.LayoutParams(-1,dp(48)));margins(close,0,8,0,0);
        page.addView(op,new LinearLayout.LayoutParams(-1,-2));

        TextView team=PremiumUi.overline(this,"FROTA DO COMBOIO",theme.muted);page.addView(team);margins(team,0,20,0,7);
        roster=PremiumUi.col(this);page.addView(roster,new LinearLayout.LayoutParams(-1,-2));
        status=PremiumUi.text(this,"Atualizando operação…",10,theme.muted,false);status.setGravity(Gravity.CENTER);page.addView(status);margins(status,0,10,0,0);
    }

    private void refresh(){io.execute(()->{try{CompanyApi api=new CompanyApi(this);JSONObject c=api.convoyStatus();JSONObject active=c.optJSONObject("active_convoy");JSONObject d=api.drivers();JSONArray drivers=d.optJSONArray("drivers");if(drivers==null)drivers=new JSONArray();JSONObject finalActive=active;JSONArray finalDrivers=drivers;runOnUiThread(()->render(finalActive,finalDrivers));}catch(Throwable e){runOnUiThread(()->status.setText("Não consegui atualizar o Comboio Empresa agora."));}});}

    private void render(JSONObject active,JSONArray drivers){activeConvoy=active;boolean has=active!=null&&!active.optString("code","").isEmpty();if(has){operation.setText(active.optString("title","Comboio da empresa")+" · "+active.optString("code",""));create.setText("CRIAR NOVO COMBOIO");open.setVisibility(View.VISIBLE);close.setVisibility(View.VISIBLE);}else{operation.setText("Nenhum comboio publicado.");create.setText("CRIAR COMBOIO DA EMPRESA");open.setVisibility(View.GONE);close.setVisibility(View.GONE);}roster.removeAllViews();for(int i=0;i<drivers.length();i++){JSONObject d=drivers.optJSONObject(i);if(d==null)continue;LinearLayout row=PremiumUi.row(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(13),dp(11),dp(13),dp(11));row.setBackground(PremiumUi.panel(this,theme.surfaceAlt,theme.border,theme.radiusDp));LinearLayout words=PremiumUi.col(this);words.addView(PremiumUi.text(this,d.optString("name","Motorista"),13,theme.text,true));JSONObject v=d.optJSONObject("vehicle");String vehicle=v==null?"Sem veículo":vehicleLabel(v);words.addView(PremiumUi.text(this,vehicle,10,theme.muted,false));row.addView(words,new LinearLayout.LayoutParams(0,-2,1f));String presence=d.optString("presence","SEM JORNADA");TextView p=PremiumUi.text(this,presence,9,"NA ESTRADA".equals(presence)?theme.success:theme.muted,true);p.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);row.addView(p,new LinearLayout.LayoutParams(dp(105),dp(40)));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,dp(7));roster.addView(row,lp);}status.setText(drivers.length()+" motorista(s) vinculados à empresa.");}

    private void createCompanyConvoy(){create.setEnabled(false);status.setText("Criando comboio…");io.execute(()->{try{ConvoyStore store=new ConvoyStore(this);JSONObject created=store.create(null);if(!created.optBoolean("ok",false))throw new Exception(created.optString("error","Não consegui criar o comboio."));String code=ConvoyStore.normalize(created.optString("code",store.code()));if(code.isEmpty())throw new Exception("O servidor não retornou o código do comboio.");new CompanyApi(this).publishConvoy(code,"Comboio · "+CompanyAccount.companyName(this));runOnUiThread(()->{create.setEnabled(true);refresh();});}catch(Throwable e){String m=e.getMessage()==null?"Não consegui criar o comboio.":e.getMessage();runOnUiThread(()->{create.setEnabled(true);status.setText(m);});}});}

    private void closeOperation(){close.setEnabled(false);status.setText("Encerrando operação…");io.execute(()->{try{new CompanyApi(this).closeConvoy();try{new ConvoyStore(this).leave();}catch(Throwable ignored){}runOnUiThread(()->{close.setEnabled(true);refresh();});}catch(Throwable e){String m=e.getMessage()==null?"Não consegui encerrar a operação.":e.getMessage();runOnUiThread(()->{close.setEnabled(true);status.setText(m);});}});}

    private String vehicleLabel(JSONObject v){String nick=v.optString("nickname","").trim();String plate=v.optString("plate","").trim();return nick.isEmpty()?plate:nick+(plate.isEmpty()?"":" · "+plate);}
    private int dp(float v){return PremiumUi.dp(this,v);}private void margins(View v,int l,int t,int r,int b){if(!(v.getLayoutParams() instanceof LinearLayout.LayoutParams))return;LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)v.getLayoutParams();p.setMargins(dp(l),dp(t),dp(r),dp(b));v.setLayoutParams(p);}    
    @Override protected void onDestroy(){io.shutdownNow();super.onDestroy();}
}

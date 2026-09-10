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

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Work context shown to a driver linked to a company while keeping the normal personal central. */
public final class CompanyDriverActivity extends ComponentActivity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private EstradaTheme theme;
    private TextView vehicle, km, convoy, status;
    private Button joinConvoy;
    private JSONObject activeConvoy;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (CompanyAccount.shouldUseCompanyCentral(this)) {
            startActivity(new Intent(this, CompanyHomeActivity.class)); finish(); return;
        }
        if (!CompanyAccount.hasCompany(this)) {
            startActivity(new Intent(this, CompanySetupActivity.class)); finish(); return;
        }
        build(); refresh();
    }

    private void build() {
        theme=EstradaTheme.get(this); getWindow().setStatusBarColor(theme.background); getWindow().setNavigationBarColor(theme.background);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(theme.background);
        LinearLayout page=PremiumUi.col(this);page.setPadding(dp(20),dp(20),dp(20),dp(30));scroll.addView(page,new ScrollView.LayoutParams(-1,-2));setContentView(scroll);
        Button back=PremiumUi.button(this,"‹ VOLTAR",false);back.setOnClickListener(v->finish());page.addView(back,new LinearLayout.LayoutParams(dp(105),dp(46)));
        TextView over=PremiumUi.overline(this,"JORNADA DA EMPRESA",theme.secondary);page.addView(over);margins(over,0,25,0,5);
        page.addView(PremiumUi.text(this,CompanyAccount.companyName(this),28,theme.text,true));
        TextView intro=PremiumUi.text(this,"Seu vínculo de trabalho usa a mesma tela Estrada. A quilometragem é registrada apenas enquanto o modo de estrada estiver em uso.",12,theme.muted,false);page.addView(intro);margins(intro,0,7,0,16);

        LinearLayout card=PremiumUi.col(this);card.setPadding(dp(16),dp(16),dp(16),dp(16));card.setBackground(PremiumUi.panel(this,theme.glass,theme.border,theme.radiusDp+2));
        card.addView(PremiumUi.overline(this,"VEÍCULO RESPONSÁVEL",theme.muted));
        vehicle=PremiumUi.text(this,"Carregando…",18,theme.text,true);card.addView(vehicle);margins(vehicle,0,7,0,0);
        km=PremiumUi.text(this,"Hoje · — km",13,theme.secondary,true);card.addView(km);margins(km,0,6,0,0);
        page.addView(card,new LinearLayout.LayoutParams(-1,-2));

        LinearLayout convoyCard=PremiumUi.col(this);convoyCard.setPadding(dp(16),dp(16),dp(16),dp(16));convoyCard.setBackground(PremiumUi.panel(this,theme.surfaceAlt,theme.border,theme.radiusDp));
        convoyCard.addView(PremiumUi.overline(this,"COMBOIO DA EMPRESA",theme.muted));
        convoy=PremiumUi.text(this,"Nenhum comboio ativo.",14,theme.text,true);convoyCard.addView(convoy);margins(convoy,0,7,0,0);
        joinConvoy=PremiumUi.button(this,"ENTRAR NO COMBOIO",true);joinConvoy.setVisibility(View.GONE);joinConvoy.setOnClickListener(v->joinCompanyConvoy());convoyCard.addView(joinConvoy,new LinearLayout.LayoutParams(-1,dp(52)));margins(joinConvoy,0,12,0,0);
        page.addView(convoyCard,lp(-1,-2,0,10,0,0));

        Button road=PremiumUi.button(this,"ABRIR ESTRADA",true);road.setOnClickListener(v->startActivity(new Intent(this,RoadEntryActivity.class)));page.addView(road,lp(-1,54,0,14,0,0));
        Button personal=PremiumUi.button(this,"VOLTAR À CENTRAL PESSOAL",false);personal.setOnClickListener(v->{CompanyBootstrapProvider.markRouted();startActivity(new Intent(this,PremiumHomeActivity.class));finish();});page.addView(personal,lp(-1,50,0,8,0,0));
        status=PremiumUi.text(this,"Atualizando vínculo…",10,theme.muted,false);status.setGravity(Gravity.CENTER);page.addView(status);margins(status,0,10,0,0);
    }

    private void refresh(){io.execute(()->{try{JSONObject response=new CompanyApi(this).driverStatus();JSONObject company=response.optJSONObject("company");if(company!=null)CompanyAccount.saveCompany(this,company);double today=response.optDouble("km_today",0.0);runOnUiThread(()->apply(company,today));}catch(Throwable e){runOnUiThread(()->status.setText("Sem conexão com a empresa agora. A Central pessoal continua disponível."));}});}

    private void apply(JSONObject company,double today){
        JSONObject v=company==null?null:company.optJSONObject("active_vehicle");
        if(v==null){vehicle.setText("Nenhum veículo atribuído");vehicle.setTextColor(theme.warning);}else{String nick=v.optString("nickname","").trim();String plate=v.optString("plate","").trim();String model=v.optString("model","").trim();String label=nick.isEmpty()?plate:nick+(plate.isEmpty()?"":" · "+plate);if(label.isEmpty())label=model.isEmpty()?"Veículo atribuído":model;vehicle.setText(label);vehicle.setTextColor(theme.text);}
        km.setText(String.format(Locale.getDefault(),"Hoje · %.1f km",today));
        activeConvoy=company==null?null:company.optJSONObject("active_convoy");
        if(activeConvoy==null){convoy.setText("Nenhum comboio ativo.");joinConvoy.setVisibility(View.GONE);}else{String code=activeConvoy.optString("code","");String title=activeConvoy.optString("title","Comboio da empresa");convoy.setText(title+" · "+code);joinConvoy.setVisibility(code.isEmpty()?View.GONE:View.VISIBLE);}
        status.setText(v==null?"A empresa ainda precisa atribuir um veículo a você.":"Vínculo atualizado.");
    }

    private void joinCompanyConvoy(){
        String code=activeConvoy==null?"":activeConvoy.optString("code","").trim();if(code.isEmpty())return;
        joinConvoy.setEnabled(false);joinConvoy.setText("ENTRANDO…");status.setText("Conectando ao comboio da empresa…");
        io.execute(()->{try{JSONObject j=new ConvoyStore(this).join(code,null);if(!j.optBoolean("ok",false))throw new Exception(j.optString("error","Não consegui entrar no comboio."));runOnUiThread(()->{startActivity(new Intent(this,ConvoyActivity.class));joinConvoy.setEnabled(true);joinConvoy.setText("ENTRAR NO COMBOIO");});}catch(Throwable e){String m=e.getMessage()==null?"Não consegui entrar no comboio.":e.getMessage();runOnUiThread(()->{status.setText(m);joinConvoy.setEnabled(true);joinConvoy.setText("ENTRAR NO COMBOIO");});}});
    }

    private int dp(float v){return PremiumUi.dp(this,v);}private LinearLayout.LayoutParams lp(int w,int h,int l,int t,int r,int b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h));p.setMargins(dp(l),dp(t),dp(r),dp(b));return p;}private void margins(View v,int l,int t,int r,int b){if(!(v.getLayoutParams() instanceof LinearLayout.LayoutParams))return;LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)v.getLayoutParams();p.setMargins(dp(l),dp(t),dp(r),dp(b));v.setLayoutParams(p);}    
    @Override protected void onDestroy(){io.shutdownNow();super.onDestroy();}
}

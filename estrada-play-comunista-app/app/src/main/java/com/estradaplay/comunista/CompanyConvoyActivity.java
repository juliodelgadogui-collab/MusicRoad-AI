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

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Company-level control room for the live Estrada Play convoy. */
public final class CompanyConvoyActivity extends ComponentActivity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Set<Integer> selectedDrivers = new HashSet<>();
    private EstradaTheme theme;
    private TextView operation, status;
    private LinearLayout roster;
    private Button create, open, close, saveRoster;
    private JSONObject activeConvoy;
    private JSONArray drivers = new JSONArray();
    private int leaderUserId;
    private boolean rosterConfigured;

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
        TextView sub=PremiumUi.text(this,"Escolha quem participa, defina o líder e publique uma única operação para a frota.",12,theme.muted,false);page.addView(sub);margins(sub,0,7,0,15);

        LinearLayout op=PremiumUi.col(this);op.setPadding(dp(16),dp(16),dp(16),dp(16));op.setBackground(PremiumUi.panel(this,theme.glass,theme.border,theme.radiusDp+2));op.addView(PremiumUi.overline(this,"OPERAÇÃO ATIVA",theme.muted));
        operation=PremiumUi.text(this,"Nenhum comboio publicado.",18,theme.text,true);op.addView(operation);margins(operation,0,7,0,0);
        create=PremiumUi.button(this,"CRIAR COMBOIO DA EMPRESA",true);create.setOnClickListener(v->createCompanyConvoy());op.addView(create,new LinearLayout.LayoutParams(-1,dp(54)));margins(create,0,12,0,0);
        open=PremiumUi.button(this,"ABRIR COMBOIO AO VIVO",false);open.setVisibility(View.GONE);open.setOnClickListener(v->startActivity(new Intent(this,ConvoyActivity.class)));op.addView(open,new LinearLayout.LayoutParams(-1,dp(50)));margins(open,0,8,0,0);
        close=PremiumUi.button(this,"ENCERRAR OPERAÇÃO",false);close.setVisibility(View.GONE);close.setOnClickListener(v->closeOperation());op.addView(close,new LinearLayout.LayoutParams(-1,dp(48)));margins(close,0,8,0,0);
        page.addView(op,new LinearLayout.LayoutParams(-1,-2));

        TextView team=PremiumUi.overline(this,"EQUIPE DO COMBOIO",theme.muted);page.addView(team);margins(team,0,20,0,7);
        TextView hint=PremiumUi.text(this,"Motoristas sem veículo atribuído não podem ser incluídos. O líder precisa fazer parte da operação.",10,theme.muted,false);page.addView(hint);margins(hint,0,0,0,8);
        roster=PremiumUi.col(this);page.addView(roster,new LinearLayout.LayoutParams(-1,-2));
        saveRoster=PremiumUi.button(this,"SALVAR EQUIPE DO COMBOIO",true);saveRoster.setVisibility(View.GONE);saveRoster.setOnClickListener(v->saveRoster());page.addView(saveRoster,new LinearLayout.LayoutParams(-1,dp(52)));margins(saveRoster,0,8,0,0);
        status=PremiumUi.text(this,"Atualizando operação…",10,theme.muted,false);status.setGravity(Gravity.CENTER);page.addView(status);margins(status,0,10,0,0);
    }

    private void refresh(){
        io.execute(()->{
            try{
                CompanyApi api=new CompanyApi(this);
                JSONObject c=api.convoyStatus();
                JSONObject active=c.optJSONObject("active_convoy");
                JSONObject d=api.drivers();
                JSONArray all=d.optJSONArray("drivers");if(all==null)all=new JSONArray();
                JSONObject rosterState=null;
                if(active!=null&&!active.optString("code","").isEmpty()){
                    try{rosterState=api.convoyRoster();}catch(Throwable ignored){}
                }
                JSONObject finalActive=active;JSONArray finalDrivers=all;JSONObject finalRoster=rosterState;
                runOnUiThread(()->render(finalActive,finalDrivers,finalRoster));
            }catch(Throwable e){runOnUiThread(()->status.setText("Não consegui atualizar o Comboio Empresa agora."));}
        });
    }

    private void render(JSONObject active,JSONArray allDrivers,JSONObject rosterState){
        activeConvoy=active;drivers=allDrivers==null?new JSONArray():allDrivers;
        boolean has=active!=null&&!active.optString("code","").isEmpty();
        if(has){operation.setText(active.optString("title","Comboio da empresa")+" · "+active.optString("code",""));create.setText("CRIAR NOVO COMBOIO");open.setVisibility(View.VISIBLE);close.setVisibility(View.VISIBLE);saveRoster.setVisibility(View.VISIBLE);}
        else{operation.setText("Nenhum comboio publicado.");create.setText("CRIAR COMBOIO DA EMPRESA");open.setVisibility(View.GONE);close.setVisibility(View.GONE);saveRoster.setVisibility(View.GONE);}

        selectedDrivers.clear();leaderUserId=0;rosterConfigured=false;
        JSONArray participants=rosterState==null?null:rosterState.optJSONArray("participants");
        if(participants!=null&&participants.length()>0){
            rosterConfigured=true;
            leaderUserId=rosterState.optInt("leader_user_id",0);
            for(int i=0;i<participants.length();i++){JSONObject p=participants.optJSONObject(i);if(p!=null&&p.optInt("user_id",0)>0)selectedDrivers.add(p.optInt("user_id"));}
        }else if(has){
            // First publication defaults to every driver that already has a vehicle.
            for(int i=0;i<drivers.length();i++){JSONObject d=drivers.optJSONObject(i);if(d!=null&&d.optJSONObject("vehicle")!=null){int id=d.optInt("user_id",0);if(id>0){selectedDrivers.add(id);if(leaderUserId==0)leaderUserId=id;}}}
        }
        renderRoster();
        if(!has)status.setText(drivers.length()+" motorista(s) vinculados · crie uma operação para escolher a equipe.");
        else if(!rosterConfigured)status.setText("Equipe sugerida. Revise o líder e toque em SALVAR EQUIPE.");
        else status.setText(selectedDrivers.size()+" motorista(s) selecionados para este comboio.");
    }

    private void renderRoster(){
        roster.removeAllViews();
        for(int i=0;i<drivers.length();i++){
            JSONObject d=drivers.optJSONObject(i);if(d==null)continue;
            int userId=d.optInt("user_id",0);JSONObject vehicle=d.optJSONObject("vehicle");boolean canJoin=vehicle!=null;boolean selected=selectedDrivers.contains(userId);boolean leader=leaderUserId==userId;
            LinearLayout card=PremiumUi.col(this);card.setPadding(dp(13),dp(11),dp(13),dp(11));card.setBackground(PremiumUi.panel(this,theme.surfaceAlt,leader?theme.secondary:theme.border,theme.radiusDp));
            LinearLayout top=PremiumUi.row(this);top.setGravity(Gravity.CENTER_VERTICAL);LinearLayout words=PremiumUi.col(this);words.addView(PremiumUi.text(this,(leader?"★ ":"")+d.optString("name","Motorista"),13,theme.text,true));String vehicleText=vehicle==null?"Sem veículo atribuído":vehicleLabel(vehicle);words.addView(PremiumUi.text(this,vehicleText,10,vehicle==null?theme.danger:theme.muted,false));top.addView(words,new LinearLayout.LayoutParams(0,-2,1f));TextView km=PremiumUi.text(this,String.format(java.util.Locale.getDefault(),"%.1f km",d.optDouble("km_today",0.0)),10,theme.muted,true);km.setGravity(Gravity.RIGHT);top.addView(km,new LinearLayout.LayoutParams(dp(75),-2));card.addView(top);
            LinearLayout actions=PremiumUi.row(this);Button include=PremiumUi.button(this,!canJoin?"SEM VEÍCULO":(selected?"INCLUÍDO":"INCLUIR"),selected&&canJoin);include.setEnabled(canJoin);actions.addView(include,new LinearLayout.LayoutParams(0,dp(44),1f));
            Button lead=PremiumUi.button(this,leader?"★ LÍDER":"DEFINIR LÍDER",leader);LinearLayout.LayoutParams llp=new LinearLayout.LayoutParams(0,dp(44),1f);llp.setMargins(dp(7),0,0,0);actions.addView(lead,llp);lead.setEnabled(selected&&canJoin);card.addView(actions);margins(actions,0,9,0,0);
            include.setOnClickListener(v->{if(selectedDrivers.contains(userId)){selectedDrivers.remove(userId);if(leaderUserId==userId)leaderUserId=0;}else selectedDrivers.add(userId);if(leaderUserId==0&&!selectedDrivers.isEmpty())leaderUserId=userId;rosterConfigured=false;renderRoster();status.setText("Alterações pendentes · salve a equipe do comboio.");});
            lead.setOnClickListener(v->{if(selectedDrivers.contains(userId)){leaderUserId=userId;rosterConfigured=false;renderRoster();status.setText("Novo líder selecionado · salve a equipe.");}});
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.setMargins(0,0,0,dp(7));roster.addView(card,cp);
        }
        if(drivers.length()==0)roster.addView(PremiumUi.text(this,"Cadastre e vincule motoristas antes de criar um Comboio Empresa.",11,theme.muted,false));
    }

    private void saveRoster(){
        if(activeConvoy==null){status.setText("Crie um comboio primeiro.");return;}
        if(!selectedDrivers.isEmpty()&&!selectedDrivers.contains(leaderUserId)){status.setText("Defina um líder que esteja incluído na operação.");return;}
        JSONArray ids=new JSONArray();for(Integer id:selectedDrivers)ids.put(id);
        saveRoster.setEnabled(false);status.setText("Salvando equipe do comboio…");
        io.execute(()->{try{new CompanyApi(this).saveConvoyRoster(ids,leaderUserId);runOnUiThread(()->{saveRoster.setEnabled(true);refresh();});}catch(Throwable e){String m=e.getMessage()==null?"Não consegui salvar a equipe.":e.getMessage();runOnUiThread(()->{saveRoster.setEnabled(true);status.setText(m);});}});
    }

    private void createCompanyConvoy(){
        create.setEnabled(false);status.setText("Criando comboio…");
        io.execute(()->{
            try{
                ConvoyStore store=new ConvoyStore(this);JSONObject created=store.create(null);if(!created.optBoolean("ok",false))throw new Exception(created.optString("error","Não consegui criar o comboio."));String code=ConvoyStore.normalize(created.optString("code",store.code()));if(code.isEmpty())throw new Exception("O servidor não retornou o código do comboio.");
                CompanyApi api=new CompanyApi(this);api.publishConvoy(code,"Comboio · "+CompanyAccount.companyName(this));
                JSONArray ids=new JSONArray();int leader=0;for(int i=0;i<drivers.length();i++){JSONObject d=drivers.optJSONObject(i);if(d==null||d.optJSONObject("vehicle")==null)continue;int id=d.optInt("user_id",0);if(id>0){ids.put(id);if(leader==0)leader=id;}}
                if(ids.length()>0)api.saveConvoyRoster(ids,leader);
                runOnUiThread(()->{create.setEnabled(true);refresh();});
            }catch(Throwable e){String m=e.getMessage()==null?"Não consegui criar o comboio.":e.getMessage();runOnUiThread(()->{create.setEnabled(true);status.setText(m);});}
        });
    }

    private void closeOperation(){close.setEnabled(false);status.setText("Encerrando operação…");io.execute(()->{try{new CompanyApi(this).closeConvoy();try{new ConvoyStore(this).leave();}catch(Throwable ignored){}runOnUiThread(()->{close.setEnabled(true);refresh();});}catch(Throwable e){String m=e.getMessage()==null?"Não consegui encerrar a operação.":e.getMessage();runOnUiThread(()->{close.setEnabled(true);status.setText(m);});}});}

    private String vehicleLabel(JSONObject v){String nick=v.optString("nickname","").trim();String plate=v.optString("plate","").trim();return nick.isEmpty()?plate:nick+(plate.isEmpty()?"":" · "+plate);}
    private int dp(float v){return PremiumUi.dp(this,v);}private void margins(View v,int l,int t,int r,int b){if(!(v.getLayoutParams() instanceof LinearLayout.LayoutParams))return;LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)v.getLayoutParams();p.setMargins(dp(l),dp(t),dp(r),dp(b));v.setLayoutParams(p);}    
    @Override protected void onDestroy(){io.shutdownNow();super.onDestroy();}
}

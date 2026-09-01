package com.estradaplay.comunista;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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

import java.util.ArrayList;
import java.util.Locale;

public final class VehicleCostActivity extends ComponentActivity {
    private final int BG=Color.rgb(8,5,7),SURFACE=Color.rgb(18,9,12),SURFACE2=Color.rgb(29,14,18),BORDER=Color.rgb(76,38,44);
    private final int TEXT=Color.rgb(246,238,224),MUTED=Color.rgb(174,151,146),RED=Color.rgb(190,18,38),GREEN=Color.rgb(72,212,134),GOLD=Color.rgb(226,185,76);
    private LinearLayout page;

    @Override protected void onCreate(Bundle b){super.onCreate(b);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);build();}

    private void build(){
        ScrollView sv=new ScrollView(this);sv.setFillViewport(true);sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        page=col();page.setPadding(dp(18),dp(14),dp(18),dp(28));page.setBackgroundColor(BG);sv.addView(page,new ScrollView.LayoutParams(-1,-2));setContentView(UnifiedAppShell.wrap(this,"trip",sv));
        VehicleProfileStore.Profile active=VehicleProfileStore.active(this);
        ArrayList<VehicleProfileStore.Profile> profiles=VehicleProfileStore.list(this);

        LinearLayout head=row();head.setGravity(Gravity.CENTER_VERTICAL);Button back=secondary("‹ CENTRAL");head.addView(back,new LinearLayout.LayoutParams(dp(100),dp(46)));back.setOnClickListener(v->finish());
        LinearLayout titleBox=col();titleBox.addView(over("GARAGEM",RED));titleBox.addView(text("Veículo e combustível",27,TEXT,true));head.addView(titleBox,new LinearLayout.LayoutParams(0,-2,1));page.addView(head);
        TextView intro=text("Configure o carro uma vez. O EstradaPlay usa estes dados para autonomia e custo de viagem.",12,MUTED,false);add(page,intro,0,8,0,14,-1,-2);

        LinearLayout profileCard=card();profileCard.addView(over("PERFIL ATIVO",GOLD));
        LinearLayout profileLine=row();profileLine.setGravity(Gravity.CENTER_VERTICAL);LinearLayout who=col();who.addView(text(active.name,22,TEXT,true));who.addView(text(active.type,11,MUTED,false));profileLine.addView(who,new LinearLayout.LayoutParams(0,-2,1));
        if(profiles.size()>1){Button next=secondary("TROCAR");profileLine.addView(next,new LinearLayout.LayoutParams(dp(92),dp(46)));next.setOnClickListener(v->{int at=0;for(int i=0;i<profiles.size();i++)if(profiles.get(i).id.equals(active.id)){at=i;break;}VehicleProfileStore.setActive(this,profiles.get((at+1)%profiles.size()).id);build();});}
        profileCard.addView(profileLine);add(page,profileCard,0,0,0,12,-1,-2);

        LinearLayout stats=row();stats.addView(metric("AUTONOMIA",String.format(Locale.getDefault(),"%.0f km",active.autonomyKm()),GREEN),new LinearLayout.LayoutParams(0,dp(82),1));
        double real=FuelingStore.actualKml(this,active.id);LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(0,dp(82),1);mp.setMargins(dp(8),0,0,0);stats.addView(metric("CONSUMO REAL",real>0?String.format(Locale.getDefault(),"%.1f km/l",real):"—",GOLD),mp);add(page,stats,0,0,0,16,-1,-2);

        page.addView(section("DADOS DO VEÍCULO","Usados somente neste aparelho."));
        LinearLayout form=card();
        EditText name=field(form,"NOME","Ex.: Escort",active.name,false);
        EditText type=field(form,"TIPO","Carro, moto ou caminhão",active.type,false);
        EditText km=field(form,"CONSUMO MÉDIO","km/l",fmt(active.kmL),true);
        EditText price=field(form,"PREÇO DO COMBUSTÍVEL","R$ por litro",fmt2(active.fuelPrice),true);
        EditText tank=field(form,"CAPACIDADE DO TANQUE","litros",fmt(active.tankL),true);
        EditText pct=field(form,"COMBUSTÍVEL AGORA","0 a 100%",fmt(active.fuelPercent),true);
        Button save=primary("SALVAR VEÍCULO");add(form,save,0,14,0,0,-1,dp(54));
        save.setOnClickListener(v->{try{VehicleProfileStore.save(this,new VehicleProfileStore.Profile(active.id,name.getText().toString(),type.getText().toString(),number(km),number(price),number(tank),number(pct)));toast("Veículo salvo");build();}catch(Throwable e){toast("Confira os valores informados.");}});
        Button newProfile=secondary("+ ADICIONAR OUTRO VEÍCULO");add(form,newProfile,0,8,0,0,-1,dp(50));newProfile.setOnClickListener(v->{VehicleProfileStore.Profile n=new VehicleProfileStore.Profile("v"+System.currentTimeMillis(),"Novo veículo","Carro",10,0,50,50);VehicleProfileStore.save(this,n);build();});
        add(page,form,0,8,0,18,-1,-2);

        page.addView(section("ABASTECIMENTO","Registre quando parar. Com dois odômetros válidos, calculamos o consumo real."));
        LinearLayout fuelCard=card();
        EditText liters=field(fuelCard,"LITROS ABASTECIDOS","Ex.: 38,5","",true);
        EditText total=field(fuelCard,"VALOR TOTAL","R$","",true);
        EditText odo=field(fuelCard,"ODÔMETRO","km · opcional","",true);
        Button fuel=primary("REGISTRAR ABASTECIMENTO");add(fuelCard,fuel,0,14,0,0,-1,dp(56));
        fuel.setOnClickListener(v->{try{float l=number(liters),tot=number(total),od=number(odo);if(l<=0){toast("Informe quantos litros foram abastecidos.");return;}FuelingStore.add(this,active.id,l,tot,od);if(tot>0){float unit=tot/l;VehicleProfileStore.save(this,new VehicleProfileStore.Profile(active.id,active.name,active.type,active.kmL,unit,active.tankL,100));}toast("Abastecimento registrado");build();}catch(Throwable e){toast("Confira os valores informados.");}});
        add(page,fuelCard,0,8,0,0,-1,-2);
    }

    private View section(String title,String sub){LinearLayout x=col();x.addView(over(title,MUTED));x.addView(text(sub,11,MUTED,false));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,0,0,8);x.setLayoutParams(p);return x;}
    private EditText field(LinearLayout parent,String label,String hint,String value,boolean number){TextView l=over(label,MUTED);add(parent,l,2,number?12:0,0,5,-1,-2);EditText e=new EditText(this);e.setText(value);e.setHint(hint);e.setSingleLine(true);e.setTextColor(TEXT);e.setHintTextColor(Color.rgb(121,100,98));e.setTextSize(15);e.setPadding(dp(14),0,dp(14),0);e.setBackground(panel(SURFACE2,13,BORDER));if(number)e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);add(parent,e,0,0,0,0,-1,dp(52));return e;}
    private View metric(String label,String value,int accent){LinearLayout box=col();box.setGravity(Gravity.CENTER);box.setBackground(panel(SURFACE2,16,BORDER));TextView v=text(value,20,accent,true);v.setGravity(Gravity.CENTER);box.addView(v);TextView l=over(label,MUTED);l.setGravity(Gravity.CENTER);box.addView(l);return box;}

    private LinearLayout card(){LinearLayout c=col();c.setPadding(dp(16),dp(15),dp(16),dp(15));c.setBackground(panel(SURFACE,18,BORDER));return c;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}private LinearLayout col(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private TextView text(String v,float s,int c,boolean b){TextView t=new TextView(this);t.setText(v);t.setTextSize(s);t.setTextColor(c);t.setGravity(Gravity.CENTER_VERTICAL);t.setLineSpacing(0,1.06f);if(b)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}private TextView over(String v,int c){TextView t=text(v,9,c,true);t.setLetterSpacing(.12f);return t;}
    private Button primary(String v){return button(v,true);}private Button secondary(String v){return button(v,false);}private Button button(String v,boolean primary){Button b=new Button(this);b.setText(v);b.setAllCaps(false);b.setTextColor(TEXT);b.setTextSize(10);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setLetterSpacing(.05f);b.setStateListAnimator(null);b.setBackground(panel(primary?RED:SURFACE2,14,primary?0:BORDER));return b;}
    private GradientDrawable panel(int color,int radius,int stroke){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));if(stroke!=0)g.setStroke(dp(1),stroke);return g;}
    private void add(LinearLayout p,View v,int l,int t,int r,int b,int w,int h){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(w,h);lp.setMargins(dp(l),dp(t),dp(r),dp(b));p.addView(v,lp);}private float number(EditText e){String s=e.getText().toString().trim().replace(',','.');return s.isEmpty()?0:Float.parseFloat(s);}private String fmt(float v){return String.format(Locale.US,"%.1f",v);}private String fmt2(float v){return String.format(Locale.US,"%.2f",v);}private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
}

package com.estradaplay.comunista;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

public final class ThoughtsActivity extends ComponentActivity {
    private final int BG=Color.rgb(9,5,7), TEXT=Color.rgb(246,238,224), MUTED=Color.rgb(174,151,146);
    private final int RED=Color.rgb(190,18,38), GOLD=Color.rgb(226,185,76), BORDER=Color.rgb(82,39,45), SURFACE=Color.rgb(24,11,15);
    private LinearLayout page;
    private TextView modeState, intervalState, author, body;

    @Override protected void onCreate(Bundle state) { super.onCreate(state); build(); }

    private void build() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(BG);
        page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp(20),dp(22),dp(20),dp(28));
        scroll.addView(page,new ScrollView.LayoutParams(-1,-2)); setContentView(scroll);

        TextView over=text("EPC / COPILOTO",9,GOLD,true); over.setLetterSpacing(.12f); page.addView(over);
        page.addView(text("PENSAMENTOS DA ESTRADA",26,TEXT,true));
        TextView desc=text("Frases curtas em forma de paráfrase, inspiradas em autores socialistas e comunistas. Nunca são tratadas como citação literal e nunca têm prioridade sobre um alerta de segurança.",13,MUTED,false);
        page.addView(desc); margin(desc,0,7,0,18);

        LinearLayout preview=card(); page.addView(preview);
        preview.addView(text("PENSAMENTO ATUAL",9,GOLD,true));
        author=text("",12,GOLD,true); preview.addView(author); margin(author,0,6,0,4);
        body=text("",18,TEXT,false); body.setLineSpacing(0,1.08f); preview.addView(body);
        TextView note=text("PARÁFRASE · INSPIRADO NO AUTOR",9,MUTED,true); preview.addView(note); margin(note,0,10,0,0);
        Button another=button("MOSTRAR OUTRA FRASE",false); preview.addView(another,new LinearLayout.LayoutParams(-1,dp(50))); margin(another,0,14,0,0);
        another.setOnClickListener(v -> renderEntry(RoadThoughts.next(this)));
        renderEntry(RoadThoughts.preview(this));

        TextView modeTitle=text("COMO APARECE",11,GOLD,true); page.addView(modeTitle); margin(modeTitle,0,22,0,6);
        modeState=text("",13,MUTED,false); page.addView(modeState); margin(modeState,0,0,0,8);
        addMode("DESLIGADO",RoadThoughts.MODE_OFF);
        addMode("SÓ NA TELA",RoadThoughts.MODE_SCREEN);
        addMode("TELA + VOZ",RoadThoughts.MODE_SCREEN_VOICE);

        TextView intTitle=text("INTERVALO DURANTE A VIAGEM",11,GOLD,true); page.addView(intTitle); margin(intTitle,0,22,0,6);
        intervalState=text("",13,MUTED,false); page.addView(intervalState); margin(intervalState,0,0,0,8);
        LinearLayout row=new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); page.addView(row);
        addInterval(row,"20 MIN",20); addInterval(row,"25 MIN",25); addInterval(row,"30 MIN",30);

        TextView safety=text("SEGURANÇA PRIMEIRO\nSe aparecer radar, quebra-molas, excesso de velocidade ou outro aviso importante, o pensamento é interrompido imediatamente. A primeira frase de uma viagem só pode aparecer depois de alguns minutos em movimento.",12,MUTED,false);
        safety.setBackground(panel(Color.rgb(34,14,18),BORDER)); safety.setPadding(dp(14),dp(12),dp(14),dp(12)); page.addView(safety); margin(safety,0,22,0,0);

        Button back=button("VOLTAR",true); page.addView(back,new LinearLayout.LayoutParams(-1,dp(56))); margin(back,0,18,0,0); back.setOnClickListener(v->finish());
        refreshStates();
    }

    private void addMode(String label,int mode){Button b=button(label,false);page.addView(b,new LinearLayout.LayoutParams(-1,dp(50)));margin(b,0,6,0,0);b.setOnClickListener(v->{RoadThoughts.setMode(this,mode);refreshStates();});}
    private void addInterval(LinearLayout row,String label,int min){Button b=button(label,false);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(48),1);if(row.getChildCount()>0)lp.setMargins(dp(6),0,0,0);row.addView(b,lp);b.setOnClickListener(v->{RoadThoughts.setIntervalMinutes(this,min);refreshStates();});}
    private void refreshStates(){int m=RoadThoughts.mode(this);modeState.setText(m==0?"Desligado":m==1?"Somente caixa na tela":"Caixa na tela + voz do copiloto");intervalState.setText("A cada aproximadamente "+RoadThoughts.intervalMinutes(this)+" minutos, somente quando não houver alerta de segurança.");}
    private void renderEntry(RoadThoughts.Entry e){author.setText("INSPIRADO EM "+e.author.toUpperCase());body.setText(e.text);}
    private LinearLayout card(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(16),dp(15),dp(16),dp(15));l.setBackground(panel(SURFACE,BORDER));return l;}
    private Button button(String label,boolean primary){Button b=new Button(this);b.setText(label);b.setTextColor(Color.WHITE);b.setTextSize(11);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setGravity(Gravity.CENTER);b.setBackground(panel(primary?RED:Color.rgb(42,20,25),primary?RED:BORDER));return b;}
    private TextView text(String v,float s,int c,boolean bold){TextView t=new TextView(this);t.setText(v);t.setTextSize(s);t.setTextColor(c);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private GradientDrawable panel(int color,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(4));if(stroke!=0)d.setStroke(1,stroke);return d;}
    private void margin(android.view.View v,int l,int t,int r,int b){android.view.ViewGroup.LayoutParams raw=v.getLayoutParams();if(raw instanceof LinearLayout.LayoutParams){LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)raw;p.setMargins(dp(l),dp(t),dp(r),dp(b));v.setLayoutParams(p);}}
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
}

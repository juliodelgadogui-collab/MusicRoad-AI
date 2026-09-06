package com.estradaplay.comunista;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

public final class RoadReportActivity extends ComponentActivity {
    private static final String P="epc_road_reports_v140";
    private final int BG=Color.rgb(8,5,7),SURFACE=Color.rgb(18,9,12),SURFACE2=Color.rgb(29,14,18),BORDER=Color.rgb(76,38,44);
    private final int TEXT=Color.rgb(246,238,224),MUTED=Color.rgb(174,151,146),RED=Color.rgb(190,18,38),GREEN=Color.rgb(72,212,134),GOLD=Color.rgb(226,185,76);
    private final ArrayList<OptionView> options=new ArrayList<>();
    private String selected="";
    private double lat=Double.NaN,lon=Double.NaN;
    private BroadcastReceiver rx; private boolean reg;
    private TextView gpsState, selectedState;

    @Override protected void onCreate(Bundle b){super.onCreate(b);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);build();}

    private void build(){
        ScrollView sv=new ScrollView(this);sv.setFillViewport(true);sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout page=col();page.setPadding(dp(18),dp(14),dp(18),dp(26));page.setBackgroundColor(BG);sv.addView(page,new ScrollView.LayoutParams(-1,-2));setContentView(UnifiedAppShell.wrap(this,"central",sv));

        LinearLayout head=row();head.setGravity(Gravity.CENTER_VERTICAL);Button back=button("‹ CENTRAL",false);head.addView(back,new LinearLayout.LayoutParams(dp(100),dp(46)));back.setOnClickListener(v->finish());
        LinearLayout titles=col();titles.addView(over("CORREÇÃO DA BASE",RED));titles.addView(text("Corrigir ponto da estrada",27,TEXT,true));head.addView(titles,new LinearLayout.LayoutParams(0,-2,1));page.addView(head);
        TextView note=text("Use esta tela para corrigir dados permanentes da base: radar, limite, quebra-molas ou câmera. Para acidente, buraco, animal, trânsito ou fiscalização temporária, use Estrada Viva.",12,MUTED,false);add(page,note,0,8,0,10,-1,-2);
        Button live=button("ABRIR ESTRADA VIVA · ALERTA TEMPORÁRIO",false);add(page,live,0,0,0,14,-1,dp(50));live.setOnClickListener(v->startActivity(new Intent(this,EstradaVivaActivity.class)));

        LinearLayout status=row();status.setGravity(Gravity.CENTER_VERTICAL);status.setPadding(dp(14),dp(12),dp(14),dp(12));status.setBackground(panel(SURFACE,16,BORDER));
        LinearLayout gpsBox=col();gpsBox.addView(over("LOCALIZAÇÃO",MUTED));gpsState=text("Aguardando GPS…",14,GOLD,true);gpsBox.addView(gpsState);status.addView(gpsBox,new LinearLayout.LayoutParams(0,-2,1));
        selectedState=chip("NENHUM TIPO",MUTED,SURFACE2);status.addView(selectedState);add(page,status,0,0,0,18,-1,-2);

        page.addView(over("O QUE PRECISA SER CORRIGIDO?",MUTED));
        LinearLayout grid=col();add(page,grid,0,8,0,18,-1,-2);
        addPair(grid,new String[]{"RADAR_NOVO","RADAR NOVO","Novo ponto de fiscalização"},new String[]{"RADAR_REMOVIDO","RADAR REMOVIDO","Ponto que não existe mais"});
        addPair(grid,new String[]{"LIMITE_ERRADO","LIMITE INCORRETO","Velocidade exibida está errada"},new String[]{"QUEBRA_MOLAS","QUEBRA-MOLAS","Lombada ou redutor físico"});
        addSingle(grid,new String[]{"CAMERA_MONITORAMENTO","CÂMERA DE MONITORAMENTO","Fiscalização sem limite de velocidade"});

        LinearLayout confirm=col();confirm.setPadding(dp(16),dp(15),dp(16),dp(15));confirm.setBackground(panel(SURFACE,18,BORDER));
        confirm.addView(over("CONFIRMAÇÃO",GOLD));confirm.addView(text("O EstradaPlay salva GPS e horário do momento da correção. Não envia áudio ou vídeo.",12,MUTED,false));
        Button send=button("SALVAR CORREÇÃO",true);add(confirm,send,0,14,0,0,-1,dp(56));send.setOnClickListener(v->save());
        page.addView(confirm);
    }

    private void addPair(LinearLayout parent,String[] a,String[] b){LinearLayout r=row();OptionView oa=option(a[0],a[1],a[2]);OptionView ob=option(b[0],b[1],b[2]);r.addView(oa.view,new LinearLayout.LayoutParams(0,dp(112),1));LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(0,dp(112),1);bp.setMargins(dp(8),0,0,0);r.addView(ob.view,bp);parent.addView(r);if(parent.getChildCount()>0){LinearLayout.LayoutParams rp=(LinearLayout.LayoutParams)r.getLayoutParams();rp.setMargins(0,parent.getChildCount()==1?0:dp(8),0,0);r.setLayoutParams(rp);}}
    private void addSingle(LinearLayout parent,String[] a){OptionView o=option(a[0],a[1],a[2]);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(96));p.setMargins(0,dp(8),0,0);parent.addView(o.view,p);}
    private OptionView option(String key,String title,String subtitle){LinearLayout box=col();box.setPadding(dp(14),dp(12),dp(14),dp(12));box.setGravity(Gravity.CENTER_VERTICAL);TextView t=over(title,TEXT);TextView s=text(subtitle,10,MUTED,false);box.addView(t);box.addView(s);OptionView ov=new OptionView(key,box,t,s);options.add(ov);box.setOnClickListener(v->select(ov));applyOptionStyle(ov,false);return ov;}
    private void select(OptionView chosen){selected=chosen.key;for(OptionView o:options)applyOptionStyle(o,o==chosen);selectedState.setText("SELECIONADO");selectedState.setTextColor(GREEN);selectedState.setBackground(panel(Color.rgb(17,52,38),100,0));}
    private void applyOptionStyle(OptionView o,boolean active){o.view.setBackground(panel(active?Color.rgb(71,10,21):SURFACE2,16,active?RED:BORDER));o.title.setTextColor(active?Color.WHITE:TEXT);o.subtitle.setTextColor(active?Color.rgb(230,194,194):MUTED);}

    @Override protected void onStart(){super.onStart();rx=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){lat=i.getDoubleExtra("lat",Double.NaN);lon=i.getDoubleExtra("lon",Double.NaN);if(gpsState!=null){boolean ok=Double.isFinite(lat)&&Double.isFinite(lon);gpsState.setText(ok?"GPS pronto":"Aguardando GPS…");gpsState.setTextColor(ok?GREEN:GOLD);}}};IntentFilter f=new IntentFilter(RoadSafetyService.ACTION_STATE);if(Build.VERSION.SDK_INT>=33)registerReceiver(rx,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(rx,f);reg=true;}
    @Override protected void onStop(){if(reg){try{unregisterReceiver(rx);}catch(Throwable ignored){}reg=false;}super.onStop();}

    private void save(){if(selected.isEmpty()){toast("Escolha o tipo da correção.");return;}if(!Double.isFinite(lat)||!Double.isFinite(lon)){toast("Aguarde uma posição GPS válida.");return;}try{JSONObject r=new JSONObject();r.put("type",selected);r.put("lat",lat);r.put("lon",lon);r.put("created_at",System.currentTimeMillis());SharedPreferences p=getSharedPreferences(P,MODE_PRIVATE);JSONArray q=new JSONArray(p.getString("queue","[]"));q.put(r);p.edit().putString("queue",q.toString()).apply();new Thread(()->uploadQueue(p),"epc-report").start();Toast.makeText(this,"Correção salva. Ela será enviada quando o servidor aceitar.",Toast.LENGTH_LONG).show();finish();}catch(Throwable e){toast("Não foi possível salvar.");}}
    private void uploadQueue(SharedPreferences p){try{JSONArray q=new JSONArray(p.getString("queue","[]"));if(q.length()==0)return;ApiClient api=new ApiClient(this);JSONArray keep=new JSONArray();for(int i=0;i<q.length();i++){JSONObject r=q.optJSONObject(i);if(r==null)continue;try{ApiClient.Response x=api.post("api/road_reports.php",r);if(!x.ok()||!x.json().optBoolean("ok",false))keep.put(r);}catch(Throwable e){keep.put(r);}}p.edit().putString("queue",keep.toString()).apply();}catch(Throwable ignored){}}

    private static final class OptionView{final String key;final LinearLayout view;final TextView title,subtitle;OptionView(String k,LinearLayout v,TextView t,TextView s){key=k;view=v;title=t;subtitle=s;}}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}private LinearLayout col(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private TextView text(String v,float s,int c,boolean b){TextView t=new TextView(this);t.setText(v);t.setTextSize(s);t.setTextColor(c);t.setGravity(Gravity.CENTER_VERTICAL);t.setLineSpacing(0,1.06f);if(b)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}private TextView over(String v,int c){TextView t=text(v,9,c,true);t.setLetterSpacing(.11f);return t;}private TextView chip(String v,int c,int fill){TextView t=over(v,c);t.setGravity(Gravity.CENTER);t.setPadding(dp(10),0,dp(10),0);t.setBackground(panel(fill,100,0));t.setMinHeight(dp(32));return t;}
    private Button button(String v,boolean primary){Button b=new Button(this);b.setText(v);b.setAllCaps(false);b.setTextColor(TEXT);b.setTextSize(10);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setLetterSpacing(.05f);b.setStateListAnimator(null);b.setBackground(panel(primary?RED:SURFACE2,14,primary?0:BORDER));return b;}
    private GradientDrawable panel(int color,int radius,int stroke){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));if(stroke!=0)g.setStroke(dp(1),stroke);return g;}private void add(LinearLayout p,View v,int l,int t,int r,int b,int w,int h){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(w,h);lp.setMargins(dp(l),dp(t),dp(r),dp(b));p.addView(v,lp);}private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
}

package com.estradaplay.comunista;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.activity.ComponentActivity;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.*;

/** Driver-facing push-to-talk radio. Microphone is hot only while PTT is held. */
public final class RoadRadioActivity extends ComponentActivity {
    private static final int REQ_MIC=6101;
    private final int BG=Color.rgb(8,5,7),TEXT=Color.rgb(246,238,224),MUTED=Color.rgb(174,151,146),RED=Color.rgb(190,18,38),GOLD=Color.rgb(226,185,76),GREEN=Color.rgb(72,212,134);
    private TextView room,status,people,alerts; private Button join,ptt,mute; private boolean joined,muted,registered;
    private BroadcastReceiver rx;

    @Override protected void onCreate(Bundle b){super.onCreate(b);build();}
    private void build(){
        ScrollView sv=new ScrollView(this); LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);p.setPadding(dp(18),dp(18),dp(18),dp(30));p.setBackgroundColor(BG);sv.addView(p);setContentView(UnifiedAppShell.wrap(this,"radio",sv));
        TextView k=t("ESTRADA PLAY · COMUNICAÇÃO",10,GOLD,true);k.setLetterSpacing(.13f);p.addView(k);p.addView(t("RÁDIO DA RODOVIA",29,TEXT,true));p.addView(t("Sala automática pela rodovia, sentido e trecho aproximado. Áudio WebRTC vai direto entre os aparelhos e não fica gravado no servidor.",12,MUTED,false));
        room=t("IDENTIFICANDO RODOVIA…",18,TEXT,true);room.setPadding(dp(14),dp(14),dp(14),dp(14));room.setBackground(box(Color.rgb(25,10,14),12,RED));p.addView(room,new LinearLayout.LayoutParams(-1,-2));
        people=t("0 motoristas no trecho",13,GREEN,true);p.addView(people);status=t("Rádio desligado",12,MUTED,false);p.addView(status);
        join=button("ENTRAR NO RÁDIO",RED);p.addView(join,new LinearLayout.LayoutParams(-1,dp(58)));join.setOnClickListener(v->{if(joined)send(RoadRadioService.ACTION_LEAVE);else enter();});
        ptt=button("SEGURE PARA FALAR",Color.rgb(78,18,28));p.addView(ptt,new LinearLayout.LayoutParams(-1,dp(92)));ptt.setEnabled(false);ptt.setOnTouchListener((v,e)->{if(!joined)return false;int a=e.getActionMasked();if(a==MotionEvent.ACTION_DOWN){send(RoadRadioService.ACTION_PTT_ON);ptt.setText("FALANDO… SOLTE PARA OUVIR");return true;}if(a==MotionEvent.ACTION_UP||a==MotionEvent.ACTION_CANCEL){send(RoadRadioService.ACTION_PTT_OFF);ptt.setText("SEGURE PARA FALAR");return true;}return true;});
        mute=button("SILENCIAR RÁDIO",Color.rgb(42,22,25));p.addView(mute,new LinearLayout.LayoutParams(-1,dp(52)));mute.setOnClickListener(v->{muted=!muted;Intent i=new Intent(this,RoadRadioService.class).setAction(RoadRadioService.ACTION_MUTE);i.putExtra("muted",muted);startService(i);renderMute();});
        TextView q=t("ALERTA RÁPIDO DO TRECHO",11,GOLD,true);q.setPadding(0,dp(18),0,dp(6));p.addView(q);
        LinearLayout r1=row();quick(r1,"ACIDENTE","ACIDENTE");quick(r1,"OBJETO","OBJETO");p.addView(r1);LinearLayout r2=row();quick(r2,"OBRA","OBRA");quick(r2,"TRÂNSITO","TRANSITO");p.addView(r2);LinearLayout r3=row();quick(r3,"CHUVA FORTE","CHUVA");p.addView(r3);
        alerts=t("Nenhum alerta recente recebido nesta sala.",12,MUTED,false);alerts.setPadding(dp(12),dp(12),dp(12),dp(12));alerts.setBackground(box(Color.rgb(18,9,12),10,Color.rgb(76,38,43)));p.addView(alerts,new LinearLayout.LayoutParams(-1,-2));
        p.addView(t("SEGURANÇA · alertas de radar, limite e quebra-molas silenciam o rádio automaticamente. Fechar o app encerra o rádio. O microfone não permanece aberto fora do PTT.",10,MUTED,false));
    }
    private void enter(){if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQ_MIC);return;}try{Intent s=new Intent(this,RoadSafetyService.class);if(Build.VERSION.SDK_INT>=26)startForegroundService(s);else startService(s);}catch(Throwable ignored){}Intent i=new Intent(this,RoadRadioService.class).setAction(RoadRadioService.ACTION_JOIN);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);status.setText("Identificando a estrada e entrando na sala…");}
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==REQ_MIC&&g.length>0&&g[0]==PackageManager.PERMISSION_GRANTED)enter();}
    private void quick(LinearLayout row,String label,String type){Button b=button(label,Color.rgb(45,18,23));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(50),1);lp.setMargins(dp(3),dp(3),dp(3),dp(3));row.addView(b,lp);b.setOnClickListener(v->{if(!joined){Toast.makeText(this,"Entre no rádio primeiro.",Toast.LENGTH_SHORT).show();return;}Intent i=new Intent(this,RoadRadioService.class).setAction(RoadRadioService.ACTION_ALERT);i.putExtra("alert_type",type);startService(i);Toast.makeText(this,"Alerta enviado ao trecho.",Toast.LENGTH_SHORT).show();});}
    private void send(String a){Intent i=new Intent(this,RoadRadioService.class).setAction(a);startService(i);}
    @Override protected void onStart(){super.onStart();rx=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){joined=i.getBooleanExtra("joined",false);muted=i.getBooleanExtra("muted",false);String label=i.getStringExtra("room_label");room.setText(label==null||label.isEmpty()?"IDENTIFICANDO RODOVIA…":label.toUpperCase(Locale.ROOT));int n=i.getIntExtra("participants",0);people.setText(n+" motorista"+(n==1?"":"s")+" no trecho");String st=i.getStringExtra("status");status.setText(st==null?"":st);join.setText(joined?"SAIR DO RÁDIO":"ENTRAR NO RÁDIO");ptt.setEnabled(joined&&!muted);String raw=i.getStringExtra("alerts_json");renderAlerts(raw);renderMute();}};IntentFilter f=new IntentFilter(RoadRadioService.ACTION_STATE);if(Build.VERSION.SDK_INT>=33)registerReceiver(rx,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(rx,f);registered=true;}
    @Override protected void onStop(){if(registered)try{unregisterReceiver(rx);}catch(Throwable ignored){}registered=false;super.onStop();}
    private void renderMute(){mute.setText(muted?"ATIVAR ÁUDIO DO RÁDIO":"SILENCIAR RÁDIO");ptt.setEnabled(joined&&!muted);}
    private void renderAlerts(String raw){if(raw==null||raw.isEmpty())return;try{JSONArray a=new JSONArray(raw);if(a.length()==0){alerts.setText("Nenhum alerta recente recebido nesta sala.");return;}StringBuilder x=new StringBuilder("ALERTAS RECENTES\n");for(int i=0;i<Math.min(6,a.length());i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;if(x.length()>17)x.append('\n');x.append(o.optString("type","ALERTA").replace('_',' '));long at=o.optLong("at",0);if(at>0)x.append(" · ").append(new SimpleDateFormat("HH:mm",Locale.getDefault()).format(new Date(at*1000L)));}alerts.setText(x.toString());}catch(Throwable ignored){}}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}private Button button(String v,int c){Button b=new Button(this);b.setText(v);b.setTextColor(TEXT);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setBackground(box(c,10,Color.rgb(94,43,50)));return b;}private TextView t(String v,float s,int c,boolean b){TextView t=new TextView(this);t.setText(v);t.setTextSize(s);t.setTextColor(c);if(b)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);t.setPadding(0,dp(5),0,dp(5));return t;}private GradientDrawable box(int c,int r,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(c);d.setCornerRadius(dp(r));d.setStroke(dp(1),stroke);return d;}private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
}

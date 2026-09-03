package com.estradaplay.comunista;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** COMBOIO_AVANCADO_V230: leader route, live presence, separation/off-route warnings and kick/block. */
public final class ConvoyActivity extends ComponentActivity {
    private final int BG=Color.rgb(8,5,7),SURFACE=Color.rgb(18,9,12),SURFACE2=Color.rgb(28,14,18),BORDER=Color.rgb(76,38,44),TEXT=Color.rgb(246,238,224),MUTED=Color.rgb(174,151,146),RED=Color.rgb(190,18,38),GREEN=Color.rgb(72,212,134),GOLD=Color.rgb(226,185,76),BLUE=Color.rgb(79,165,255),ORANGE=Color.rgb(245,145,55);
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final AtomicBoolean syncing=new AtomicBoolean(false);
    private ConvoyStore store; private RoadMapView map; private LinearLayout members,routeActions; private TextView status,routeTitle,routeDetail; private Location current; private JSONObject lastState=new JSONObject(); private boolean liveRegistered;
    private final Runnable tick=new Runnable(){@Override public void run(){refreshState();ui.postDelayed(this,10_000L);}};

    private final BroadcastReceiver liveReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){String raw=i.getStringExtra("state_json");if(raw==null||raw.trim().isEmpty())return;try{JSONObject j=new JSONObject(raw);if(j.optBoolean("ok",false))renderState(j,store.members(j),last());else if(status!=null)status.setText(i.getStringExtra("error"));}catch(Exception ignored){}}};

    @Override protected void onCreate(Bundle b){super.onCreate(b);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);store=new ConvoyStore(this);build();}
    @Override protected void onStart(){super.onStart();if(map!=null)map.onStartMap();registerLive();}
    @Override protected void onResume(){super.onResume();if(map!=null)map.onResumeMap();ui.removeCallbacks(tick);ui.post(tick);}
    @Override protected void onPause(){ui.removeCallbacks(tick);if(map!=null)map.onPauseMap();super.onPause();}
    @Override protected void onStop(){unregisterLive();if(map!=null)map.onStopMap();super.onStop();}
    @Override public void onLowMemory(){super.onLowMemory();if(map!=null)map.onLowMemoryMap();}
    @Override protected void onDestroy(){ui.removeCallbacks(tick);unregisterLive();if(map!=null)map.onDestroyMap();io.shutdownNow();super.onDestroy();}

    private void registerLive(){if(liveRegistered)return;try{IntentFilter f=new IntentFilter(ConvoyLiveBridge.ACTION_STATE);if(Build.VERSION.SDK_INT>=33)registerReceiver(liveReceiver,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(liveReceiver,f);liveRegistered=true;}catch(Throwable ignored){}}
    private void unregisterLive(){if(!liveRegistered)return;try{unregisterReceiver(liveReceiver);}catch(Throwable ignored){}liveRegistered=false;}

    private void build(){if(map!=null)try{map.onPauseMap();map.onStopMap();map.onDestroyMap();}catch(Throwable ignored){}map=null;if(store.code().isEmpty())buildJoin();else buildActive();}

    private void buildJoin(){
        ScrollView sv=new ScrollView(this);LinearLayout p=col();p.setPadding(dp(18),dp(16),dp(18),dp(30));p.setBackgroundColor(BG);sv.addView(p);setContentView(UnifiedAppShell.wrap(this,"central",sv));
        LinearLayout head=row();Button back=btn("‹ VOLTAR",false);head.addView(back,new LinearLayout.LayoutParams(dp(94),dp(46)));back.setOnClickListener(v->finish());LinearLayout tt=col();tt.addView(over("COMBOIO AVANÇADO · 2.3",GREEN));tt.addView(text("Viaje junto em carros separados",25,TEXT,true));tt.addView(text("Posição ao vivo, líder, rota compartilhada e alertas de separação.",11,MUTED,false));head.addView(tt,new LinearLayout.LayoutParams(0,-2,1));p.addView(head);
        LinearLayout create=card();create.addView(over("NOVO COMBOIO",GOLD));create.addView(text("Quem cria vira o líder e pode compartilhar a rota do grupo.",13,TEXT,true));Button make=btn("CRIAR COMBOIO",true);LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-1,dp(56));mp.setMargins(0,dp(12),0,0);create.addView(make,mp);make.setOnClickListener(v->createConvoy(make));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.setMargins(0,dp(18),0,0);p.addView(create,cp);
        LinearLayout join=card();join.addView(over("ENTRAR COM CÓDIGO",BLUE));EditText code=new EditText(this);code.setSingleLine(true);code.setHint("Ex.: 7K4M2Q");code.setTextColor(TEXT);code.setHintTextColor(MUTED);code.setTextSize(20);code.setAllCaps(true);code.setGravity(Gravity.CENTER);code.setBackground(panel(SURFACE2,13,BORDER));LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(-1,dp(58));ip.setMargins(0,dp(10),0,dp(10));join.addView(code,ip);Button enter=btn("ENTRAR NO COMBOIO",false);join.addView(enter,new LinearLayout.LayoutParams(-1,dp(54)));enter.setOnClickListener(v->joinConvoy(code.getText().toString(),enter));LinearLayout.LayoutParams jp=new LinearLayout.LayoutParams(-1,-2);jp.setMargins(0,dp(10),0,0);p.addView(join,jp);
        p.addView(text("Sua posição é compartilhada somente enquanto você participa do comboio e a proteção GPS do Estrada Play está ativa. Participantes sem atualização desaparecem do mapa após 90 segundos.",10,MUTED,false));
    }

    private void buildActive(){
        boolean landscape=getResources().getDisplayMetrics().widthPixels>getResources().getDisplayMetrics().heightPixels;LinearLayout root=col();root.setBackgroundColor(BG);if(landscape)root.setOrientation(LinearLayout.HORIZONTAL);
        map=new RoadMapView(this);ScrollView scroll=new ScrollView(this);LinearLayout panel=col();panel.setPadding(dp(16),dp(14),dp(16),dp(22));panel.setBackgroundColor(BG);scroll.addView(panel);
        if(landscape){root.addView(map,new LinearLayout.LayoutParams(0,-1,1.55f));root.addView(scroll,new LinearLayout.LayoutParams(0,-1,1f));}else{int h=getResources().getDisplayMetrics().heightPixels;root.addView(map,new LinearLayout.LayoutParams(-1,Math.max(dp(260),(int)(h*.39f))));root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1f));}
        setContentView(UnifiedAppShell.wrap(this,"central",root));
        panel.addView(over("COMBOIO ATIVO",GREEN));TextView code=text(store.code(),30,TEXT,true);code.setLetterSpacing(.12f);panel.addView(code);status=text("Conectando ao grupo…",11,MUTED,false);panel.addView(status);
        LinearLayout actions=row();Button share=btn("COMPARTILHAR",false),refresh=btn("ATUALIZAR",false);actions.addView(share,new LinearLayout.LayoutParams(0,dp(48),1));LinearLayout.LayoutParams ar=new LinearLayout.LayoutParams(0,dp(48),1);ar.setMargins(dp(8),0,0,0);actions.addView(refresh,ar);share.setOnClickListener(v->shareCode());refresh.setOnClickListener(v->refreshState());LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,-2);ap.setMargins(0,dp(12),0,0);panel.addView(actions,ap);

        LinearLayout route=card();routeTitle=text("ROTA DO COMBOIO",13,GOLD,true);routeDetail=text("Aguardando estado do líder…",10,MUTED,false);route.addView(routeTitle);route.addView(routeDetail);routeActions=row();LinearLayout.LayoutParams rap=new LinearLayout.LayoutParams(-1,-2);rap.setMargins(0,dp(8),0,0);route.addView(routeActions,rap);LinearLayout.LayoutParams rcp=new LinearLayout.LayoutParams(-1,-2);rcp.setMargins(0,dp(12),0,0);panel.addView(route,rcp);

        TextView mtitle=over("MOTORISTAS",GOLD);LinearLayout.LayoutParams mtp=new LinearLayout.LayoutParams(-1,-2);mtp.setMargins(0,dp(18),0,dp(6));panel.addView(mtitle,mtp);members=col();panel.addView(members);
        Button leave=btn("SAIR DO COMBOIO",true);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(52));lp.setMargins(0,dp(18),0,0);panel.addView(leave,lp);leave.setOnClickListener(v->leaveConvoy(leave));
        current=last();if(current!=null)map.setUserLocation(current.getLatitude(),current.getLongitude(),current.hasBearing()?current.getBearing():0);JSONObject cached=store.lastSnapshot();if(cached.optBoolean("ok",false))renderState(cached,store.members(cached),current);refreshState();
    }

    private void createConvoy(Button b){b.setEnabled(false);b.setText("CRIANDO…");io.execute(()->{try{JSONObject j=store.create(last());runOnUiThread(()->{if(j.optBoolean("ok",false))build();else{b.setEnabled(true);b.setText("CRIAR COMBOIO");toast(j.optString("error","Não consegui criar o comboio."));}});}catch(Throwable e){runOnUiThread(()->{b.setEnabled(true);b.setText("CRIAR COMBOIO");toast("Sem conexão para criar o comboio agora.");});}});}
    private void joinConvoy(String raw,Button b){String c=ConvoyStore.normalize(raw);if(c.length()<4){toast("Digite o código do comboio.");return;}b.setEnabled(false);b.setText("ENTRANDO…");io.execute(()->{try{JSONObject j=store.join(c,last());runOnUiThread(()->{if(j.optBoolean("ok",false))build();else{b.setEnabled(true);b.setText("ENTRAR NO COMBOIO");toast(j.optString("error","Comboio não encontrado."));}});}catch(Throwable e){runOnUiThread(()->{b.setEnabled(true);b.setText("ENTRAR NO COMBOIO");toast("Sem conexão para entrar no comboio.");});}});}
    private void leaveConvoy(Button b){b.setEnabled(false);io.execute(()->{try{store.leave();}catch(Throwable ignored){store.clear();}runOnUiThread(this::build);});}

    private void refreshState(){if(store.code().isEmpty()||!syncing.compareAndSet(false,true))return;io.execute(()->{try{JSONObject j=store.state();ArrayList<ConvoyStore.Member> ms=store.members(j);runOnUiThread(()->{if(store.code().isEmpty()){build();return;}renderState(j,ms,last());});}catch(Throwable e){runOnUiThread(()->{if(status!=null)status.setText("Sem conexão · posição ao vivo tenta reconectar automaticamente");});}finally{syncing.set(false);}});}

    private void renderState(JSONObject j,ArrayList<ConvoyStore.Member> ms,Location l){
        if(j==null||!j.optBoolean("ok",false)){if(status!=null)status.setText(j==null?"Comboio indisponível":j.optString("error","Não consegui atualizar o comboio."));return;}lastState=j;store.saveSnapshot(j);current=l;
        if(map!=null){if(l!=null)map.setUserLocation(l.getLatitude(),l.getLongitude(),l.hasBearing()?l.getBearing():0);map.setConvoyMembers(ms);map.setRouteGeoJson(routeGeoJson(j));}
        boolean leader=store.selfIsLeader(j);int warning=0;for(ConvoyStore.Member m:ms)if((!m.self&&(!m.online()||m.offRoute||"DISTANTE".equals(m.separationStatus))))warning++;
        if(status!=null)status.setText(ms.size()+" motorista"+(ms.size()==1?"":"s")+" · "+(leader?"VOCÊ É O LÍDER":"LÍDER NO GRUPO")+(warning>0?" · "+warning+" atenção":" · grupo conectado"));
        renderRoute(j,leader);
        if(members==null)return;members.removeAllViews();if(ms.isEmpty()){members.addView(text("Aguardando os outros motoristas…",11,MUTED,false));return;}
        for(ConvoyStore.Member m:ms)renderMember(m,leader);
    }

    private void renderRoute(JSONObject j,boolean leader){
        ConvoyStore.SharedDestination d=store.destination(j);if(routeActions==null)return;routeActions.removeAllViews();
        if(d==null){routeTitle.setText("ROTA DO COMBOIO");routeDetail.setText(leader?"Compartilhe o destino atual do navegador com todos os carros.":"O líder ainda não compartilhou um destino.");}
        else{routeTitle.setText("ROTA · "+d.label);routeDetail.setText(leader?"Destino compartilhado com o grupo · desvios acima de 180 m geram atenção.":"Destino definido pelo líder · toque abaixo para usar no seu navegador.");}
        if(leader){Button set=btn(d==null?"COMPARTILHAR DESTINO":"ATUALIZAR ROTA",true);routeActions.addView(set,new LinearLayout.LayoutParams(0,dp(46),1));set.setOnClickListener(v->shareLeaderRoute(set));if(d!=null){Button clear=btn("LIMPAR",false);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(86),dp(46));cp.setMargins(dp(7),0,0,0);routeActions.addView(clear,cp);clear.setOnClickListener(v->clearLeaderRoute(clear));}}
        else if(d!=null){Button use=btn("USAR ROTA DO LÍDER",true);routeActions.addView(use,new LinearLayout.LayoutParams(-1,dp(46)));use.setOnClickListener(v->{DestinationStore.save(this,new DestinationStore.Destination(d.label,d.lat,d.lon));toast("Rota do líder definida no navegador.");startActivity(new Intent(this,RoadMapActivity.class));});}
    }

    private void renderMember(ConvoyStore.Member m,boolean selfLeader){
        LinearLayout c=card();LinearLayout top=row();String title=(m.leader?"★ ":"")+m.nickname+(m.self?" · VOCÊ":"");TextView name=text(title,14,m.self?GREEN:(m.leader?GOLD:TEXT),true);top.addView(name,new LinearLayout.LayoutParams(0,-2,1));TextView sp=text(Math.round(m.speedKmh)+" km/h",11,GOLD,true);sp.setGravity(Gravity.RIGHT);top.addView(sp);c.addView(top);
        int presenceColor=m.online()?GREEN:("ATRASADO".equals(m.presence)?ORANGE:RED);StringBuilder meta=new StringBuilder(m.presence.replace('_',' '));if(!m.self&&Double.isFinite(m.distanceToLeaderM))meta.append(" · ").append(distance(m.distanceToLeaderM)).append(" do líder");if(m.offRoute&&Double.isFinite(m.routeDeviationM))meta.append(" · FORA DA ROTA ").append(distance(m.routeDeviationM));else if(!m.self&&"DISTANTE".equals(m.separationStatus))meta.append(" · FICANDO PARA TRÁS");c.addView(text(meta.toString(),10,presenceColor,m.offRoute||"DISTANTE".equals(m.separationStatus)));
        if(Double.isFinite(m.distanceToDestinationM)){String eta=m.etaS>0?" · ETA "+duration(m.etaS):"";c.addView(text("Destino · "+distance(m.distanceToDestinationM)+eta,10,MUTED,false));}
        if(selfLeader&&!m.self){Button kick=btn("REMOVER / BLOQUEAR",false);LinearLayout.LayoutParams kp=new LinearLayout.LayoutParams(-1,dp(42));kp.setMargins(0,dp(8),0,0);c.addView(kick,kp);kick.setOnClickListener(v->confirmKick(m,kick));}
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.setMargins(0,0,0,dp(7));members.addView(c,cp);
    }

    private void shareLeaderRoute(Button b){DestinationStore.Destination d=DestinationStore.read(this);if(d==null){toast("Escolha um destino no navegador primeiro.");startActivity(new Intent(this,DestinationActivity.class));return;}b.setEnabled(false);b.setText("CALCULANDO…");Location l=last();io.execute(()->{RouteEngine.Route r=null;try{if(l!=null)r=RouteEngine.fetch(this,l.getLatitude(),l.getLongitude(),d.lat,d.lon);}catch(Throwable ignored){}try{JSONObject j=store.setRoute(d,r);ArrayList<ConvoyStore.Member> ms=store.members(j);runOnUiThread(()->{b.setEnabled(true);renderState(j,ms,last());if(j.optBoolean("ok",false))toast("Rota compartilhada com o comboio.");else toast(j.optString("error","Não consegui compartilhar a rota."));});}catch(Throwable e){runOnUiThread(()->{b.setEnabled(true);b.setText("ATUALIZAR ROTA");toast("Sem conexão para compartilhar a rota.");});}});}
    private void clearLeaderRoute(Button b){b.setEnabled(false);io.execute(()->{try{JSONObject j=store.clearRoute();runOnUiThread(()->renderState(j,store.members(j),last()));}catch(Throwable e){runOnUiThread(()->{b.setEnabled(true);toast("Não consegui limpar a rota.");});}});}
    private void confirmKick(ConvoyStore.Member m,Button b){new AlertDialog.Builder(this).setTitle("Remover do comboio?").setMessage(m.nickname+" será removido e este aparelho ficará bloqueado para este código.").setNegativeButton("CANCELAR",null).setPositiveButton("REMOVER",(d,w)->kick(m,b)).show();}
    private void kick(ConvoyStore.Member m,Button b){b.setEnabled(false);io.execute(()->{try{JSONObject j=store.kick(m.memberId);runOnUiThread(()->{renderState(j,store.members(j),last());if(j.optBoolean("ok",false))toast("Participante removido.");else toast(j.optString("error","Não consegui remover."));});}catch(Throwable e){runOnUiThread(()->{b.setEnabled(true);toast("Sem conexão para remover o participante.");});}});}

    private String routeGeoJson(JSONObject j){try{JSONArray a=j.optJSONArray("route_points");if(a==null||a.length()<2)return null;JSONArray coords=new JSONArray();for(int i=0;i<a.length();i++){JSONObject p=a.optJSONObject(i);if(p==null)continue;JSONArray c=new JSONArray();c.put(p.optDouble("lon"));c.put(p.optDouble("lat"));coords.put(c);}JSONObject geometry=new JSONObject().put("type","LineString").put("coordinates",coords);JSONObject feature=new JSONObject().put("type","Feature").put("properties",new JSONObject()).put("geometry",geometry);return new JSONObject().put("type","FeatureCollection").put("features",new JSONArray().put(feature)).toString();}catch(Exception e){return null;}}
    private void shareCode(){ConvoyStore.SharedDestination d=store.destination(lastState);String msg="Entre no meu Comboio Estrada Play com o código: "+store.code()+(d==null?"":"\nDestino do grupo: "+d.label);Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");i.putExtra(Intent.EXTRA_TEXT,msg);try{startActivity(Intent.createChooser(i,"Compartilhar comboio"));}catch(Throwable ignored){}}
    private Location last(){if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED)return null;try{LocationManager m=(LocationManager)getSystemService(LOCATION_SERVICE);Location best=null;for(String p:new String[]{LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER}){Location x=m.getLastKnownLocation(p);if(x!=null&&(best==null||x.getTime()>best.getTime()))best=x;}return best;}catch(Throwable e){return null;}}
    private String distance(double m){if(!Double.isFinite(m))return"--";return m>=1000?String.format(Locale.getDefault(),"%.1f km",m/1000.0):Math.round(m)+" m";}
    private String duration(long sec){long min=Math.max(1,Math.round(sec/60.0)),h=min/60,m=min%60;return h>0?h+"h "+m+"m":min+" min";}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private LinearLayout card(){LinearLayout c=col();c.setPadding(dp(14),dp(12),dp(14),dp(12));c.setBackground(panel(SURFACE,14,BORDER));return c;}private Button btn(String v,boolean primary){Button b=new Button(this);b.setAllCaps(false);b.setText(v);b.setTextColor(TEXT);b.setTextSize(10);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setStateListAnimator(null);b.setBackground(panel(primary?RED:SURFACE2,13,primary?0:BORDER));return b;}private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}private LinearLayout col(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}private TextView text(String v,float s,int c,boolean bold){TextView t=new TextView(this);t.setText(v);t.setTextSize(s);t.setTextColor(c);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}private TextView over(String v,int c){TextView t=text(v,9,c,true);t.setLetterSpacing(.12f);return t;}private GradientDrawable panel(int c,int r,int stroke){GradientDrawable g=new GradientDrawable();g.setColor(c);g.setCornerRadius(dp(r));if(stroke!=0)g.setStroke(dp(1),stroke);return g;}private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
}

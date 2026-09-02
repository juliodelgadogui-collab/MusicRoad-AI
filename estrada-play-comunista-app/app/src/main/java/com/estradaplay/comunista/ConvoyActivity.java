package com.estradaplay.comunista;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.location.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.activity.ComponentActivity;
import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Live convoy map: create/join by short code, publish position and see group distance. */
public final class ConvoyActivity extends ComponentActivity {
    private final int BG=Color.rgb(8,5,7),SURFACE=Color.rgb(18,9,12),SURFACE2=Color.rgb(28,14,18),BORDER=Color.rgb(76,38,44),TEXT=Color.rgb(246,238,224),MUTED=Color.rgb(174,151,146),RED=Color.rgb(190,18,38),GREEN=Color.rgb(72,212,134),GOLD=Color.rgb(226,185,76),BLUE=Color.rgb(79,165,255);
    private final Handler ui=new Handler(Looper.getMainLooper());private final ExecutorService io=Executors.newSingleThreadExecutor();private final AtomicBoolean syncing=new AtomicBoolean(false);
    private ConvoyStore store;private RoadMapView map;private LinearLayout members;private TextView status;private Location current;
    private final Runnable tick=new Runnable(){@Override public void run(){syncNow();ui.postDelayed(this,5000L);}};

    @Override protected void onCreate(Bundle b){super.onCreate(b);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);store=new ConvoyStore(this);build();}
    @Override protected void onStart(){super.onStart();if(map!=null)map.onStartMap();}
    @Override protected void onResume(){super.onResume();if(map!=null)map.onResumeMap();ui.removeCallbacks(tick);ui.post(tick);}
    @Override protected void onPause(){ui.removeCallbacks(tick);if(map!=null)map.onPauseMap();super.onPause();}
    @Override protected void onStop(){if(map!=null)map.onStopMap();super.onStop();}
    @Override public void onLowMemory(){super.onLowMemory();if(map!=null)map.onLowMemoryMap();}
    @Override protected void onDestroy(){ui.removeCallbacks(tick);if(map!=null)map.onDestroyMap();io.shutdownNow();super.onDestroy();}

    private void build(){if(map!=null)try{map.onPauseMap();map.onStopMap();map.onDestroyMap();}catch(Throwable ignored){}map=null;if(store.code().isEmpty())buildJoin();else buildActive();}

    private void buildJoin(){
        ScrollView sv=new ScrollView(this);LinearLayout p=col();p.setPadding(dp(18),dp(16),dp(18),dp(30));p.setBackgroundColor(BG);sv.addView(p);setContentView(UnifiedAppShell.wrap(this,"central",sv));
        LinearLayout head=row();Button back=btn("‹ VOLTAR",false);head.addView(back,new LinearLayout.LayoutParams(dp(94),dp(46)));back.setOnClickListener(v->finish());LinearLayout tt=col();tt.addView(over("COMBOIO VIRTUAL · 1.8",GREEN));tt.addView(text("Viaje junto, mesmo em carros separados",25,TEXT,true));tt.addView(text("O mapa mostra quem está no grupo e a distância entre vocês.",11,MUTED,false));head.addView(tt,new LinearLayout.LayoutParams(0,-2,1));p.addView(head);
        LinearLayout create=card();create.addView(over("NOVO COMBOIO",GOLD));create.addView(text("Crie um código e envie para os outros motoristas.",13,TEXT,true));Button make=btn("CRIAR COMBOIO",true);LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-1,dp(56));mp.setMargins(0,dp(12),0,0);create.addView(make,mp);make.setOnClickListener(v->createConvoy(make));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.setMargins(0,dp(18),0,0);p.addView(create,cp);
        LinearLayout join=card();join.addView(over("ENTRAR COM CÓDIGO",BLUE));EditText code=new EditText(this);code.setSingleLine(true);code.setHint("Ex.: 7K4M2Q");code.setTextColor(TEXT);code.setHintTextColor(MUTED);code.setTextSize(20);code.setAllCaps(true);code.setGravity(Gravity.CENTER);code.setBackground(panel(SURFACE2,13,BORDER));LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(-1,dp(58));ip.setMargins(0,dp(10),0,dp(10));join.addView(code,ip);Button enter=btn("ENTRAR NO COMBOIO",false);join.addView(enter,new LinearLayout.LayoutParams(-1,dp(54)));enter.setOnClickListener(v->joinConvoy(code.getText().toString(),enter));LinearLayout.LayoutParams jp=new LinearLayout.LayoutParams(-1,-2);jp.setMargins(0,dp(10),0,0);p.addView(join,jp);
        p.addView(text("A posição é compartilhada somente enquanto o aparelho participa do comboio. Grupos expiram automaticamente.",10,MUTED,false));
    }

    private void buildActive(){
        boolean landscape=getResources().getDisplayMetrics().widthPixels>getResources().getDisplayMetrics().heightPixels;LinearLayout root=col();root.setBackgroundColor(BG);if(landscape)root.setOrientation(LinearLayout.HORIZONTAL);
        map=new RoadMapView(this);ScrollView scroll=new ScrollView(this);LinearLayout panel=col();panel.setPadding(dp(16),dp(14),dp(16),dp(22));panel.setBackgroundColor(BG);scroll.addView(panel);
        if(landscape){root.addView(map,new LinearLayout.LayoutParams(0,-1,1.35f));root.addView(scroll,new LinearLayout.LayoutParams(0,-1,1f));}else{int h=getResources().getDisplayMetrics().heightPixels;root.addView(map,new LinearLayout.LayoutParams(-1,Math.max(dp(260),(int)(h*.39f))));root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1f));}
        setContentView(UnifiedAppShell.wrap(this,"central",root));
        panel.addView(over("COMBOIO ATIVO",GREEN));TextView code=text(store.code(),30,TEXT,true);code.setLetterSpacing(.12f);panel.addView(code);status=text("Atualizando posições…",11,MUTED,false);panel.addView(status);
        LinearLayout actions=row();Button share=btn("COMPARTILHAR CÓDIGO",false),refresh=btn("ATUALIZAR",false);actions.addView(share,new LinearLayout.LayoutParams(0,dp(48),1));LinearLayout.LayoutParams ar=new LinearLayout.LayoutParams(0,dp(48),1);ar.setMargins(dp(8),0,0,0);actions.addView(refresh,ar);share.setOnClickListener(v->shareCode());refresh.setOnClickListener(v->syncNow());LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,-2);ap.setMargins(0,dp(12),0,0);panel.addView(actions,ap);
        TextView mtitle=over("MOTORISTAS",GOLD);LinearLayout.LayoutParams mtp=new LinearLayout.LayoutParams(-1,-2);mtp.setMargins(0,dp(18),0,dp(6));panel.addView(mtitle,mtp);members=col();panel.addView(members);
        Button leave=btn("SAIR DO COMBOIO",true);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(52));lp.setMargins(0,dp(18),0,0);panel.addView(leave,lp);leave.setOnClickListener(v->leaveConvoy(leave));
        current=last();if(current!=null)map.setUserLocation(current.getLatitude(),current.getLongitude(),current.hasBearing()?current.getBearing():0);syncNow();
    }

    private void createConvoy(Button b){b.setEnabled(false);b.setText("CRIANDO…");io.execute(()->{try{JSONObject j=store.create(last());runOnUiThread(()->{if(j.optBoolean("ok",false))build();else{b.setEnabled(true);b.setText("CRIAR COMBOIO");toast(j.optString("error","Não consegui criar o comboio."));}});}catch(Throwable e){runOnUiThread(()->{b.setEnabled(true);b.setText("CRIAR COMBOIO");toast("Sem conexão para criar o comboio agora.");});}});}
    private void joinConvoy(String raw,Button b){String c=ConvoyStore.normalize(raw);if(c.length()<4){toast("Digite o código do comboio.");return;}b.setEnabled(false);b.setText("ENTRANDO…");io.execute(()->{try{JSONObject j=store.join(c,last());runOnUiThread(()->{if(j.optBoolean("ok",false))build();else{b.setEnabled(true);b.setText("ENTRAR NO COMBOIO");toast(j.optString("error","Comboio não encontrado."));}});}catch(Throwable e){runOnUiThread(()->{b.setEnabled(true);b.setText("ENTRAR NO COMBOIO");toast("Sem conexão para entrar no comboio.");});}});}
    private void leaveConvoy(Button b){b.setEnabled(false);io.execute(()->{try{store.leave();}catch(Throwable ignored){store.clear();}runOnUiThread(this::build);});}

    private void syncNow(){if(store.code().isEmpty()||!syncing.compareAndSet(false,true))return;io.execute(()->{try{Location l=last();JSONObject j=l==null?store.state():store.ping(l);ArrayList<ConvoyStore.Member> ms=store.members(j);runOnUiThread(()->renderState(j,ms,l));}catch(Throwable e){runOnUiThread(()->{if(status!=null)status.setText("Sem conexão · mantendo última posição na tela");});}finally{syncing.set(false);}});}
    private void renderState(JSONObject j,ArrayList<ConvoyStore.Member> ms,Location l){if(j!=null&&!j.optBoolean("ok",false)){if(status!=null)status.setText(j.optString("error","Não consegui atualizar o comboio."));return;}current=l;if(map!=null){if(l!=null)map.setUserLocation(l.getLatitude(),l.getLongitude(),l.hasBearing()?l.getBearing():0);map.setConvoyMembers(ms);}if(status!=null)status.setText(ms.size()+" motorista"+(ms.size()==1?"":"s")+" no comboio · atualização automática");if(members==null)return;members.removeAllViews();if(ms.isEmpty()){members.addView(text("Aguardando os outros motoristas…",11,MUTED,false));return;}double refLat=l==null?Double.NaN:l.getLatitude(),refLon=l==null?Double.NaN:l.getLongitude();for(ConvoyStore.Member m:ms){LinearLayout c=card();LinearLayout top=row();TextView name=text(m.nickname+(m.self?" · VOCÊ":""),14,m.self?GREEN:TEXT,true);top.addView(name,new LinearLayout.LayoutParams(0,-2,1));String speed=Math.round(m.speedKmh)+" km/h";TextView sp=text(speed,11,GOLD,true);sp.setGravity(Gravity.RIGHT);top.addView(sp);c.addView(top);String dist="Posição aguardando GPS";if(!m.self&&Double.isFinite(refLat)&&Double.isFinite(m.lat)){double d=RoadPackStore.distanceM(refLat,refLon,m.lat,m.lon);dist=d<1000?Math.round(d)+" m de você":String.format(Locale.getDefault(),"%.1f km de você",d/1000.0);if(d>2000)dist+=" · DISTANTE DO COMBOIO";}else if(m.self)dist="Sua posição no grupo";c.addView(text(dist,10,!m.self&&dist.contains("DISTANTE")?RED:MUTED,false));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.setMargins(0,0,0,dp(7));members.addView(c,cp);}}

    private void shareCode(){Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");i.putExtra(Intent.EXTRA_TEXT,"Entre no meu Comboio Estrada Play com o código: "+store.code());try{startActivity(Intent.createChooser(i,"Compartilhar código do comboio"));}catch(Throwable ignored){}}
    private Location last(){if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED)return null;try{LocationManager m=(LocationManager)getSystemService(LOCATION_SERVICE);Location best=null;for(String p:new String[]{LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER}){Location x=m.getLastKnownLocation(p);if(x!=null&&(best==null||x.getTime()>best.getTime()))best=x;}return best;}catch(Throwable e){return null;}}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private LinearLayout card(){LinearLayout c=col();c.setPadding(dp(14),dp(12),dp(14),dp(12));c.setBackground(panel(SURFACE,14,BORDER));return c;}private Button btn(String v,boolean primary){Button b=new Button(this);b.setAllCaps(false);b.setText(v);b.setTextColor(TEXT);b.setTextSize(10);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setStateListAnimator(null);b.setBackground(panel(primary?RED:SURFACE2,13,primary?0:BORDER));return b;}private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}private LinearLayout col(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}private TextView text(String v,float s,int c,boolean bold){TextView t=new TextView(this);t.setText(v);t.setTextSize(s);t.setTextColor(c);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}private TextView over(String v,int c){TextView t=text(v,9,c,true);t.setLetterSpacing(.12f);return t;}private GradientDrawable panel(int c,int r,int stroke){GradientDrawable g=new GradientDrawable();g.setColor(c);g.setCornerRadius(dp(r));if(stroke!=0)g.setStroke(dp(1),stroke);return g;}private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
}

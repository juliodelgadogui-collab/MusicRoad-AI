package com.musicroad.ai;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String PREFS="musicroad_native_shell_v1",KEY_SERVER="server_url",KEY_SETUP="setup_complete",KEY_UF="last_offline_uf";
    private static final int REQ_LOCATION=1001,REQ_AUDIO=1002,REQ_NOTIFICATIONS=1003;
    private static final int HOME=0,MAP=1,MUSIC=2,OFFLINE=3,SETTINGS=4;
    private final int BG=Color.rgb(7,11,16),PANEL=Color.rgb(13,20,28),PANEL2=Color.rgb(18,27,37),LINE=Color.rgb(42,57,70),TEXT=Color.rgb(244,247,249),MUTED=Color.rgb(139,158,173),ACCENT=Color.rgb(255,122,26),GREEN=Color.rgb(50,213,131),RED=Color.rgb(255,92,92);
    private SharedPreferences prefs;
    private NativeApiClient api;
    private OfflineStore offline;
    private final ExecutorService io=Executors.newFixedThreadPool(3);
    private final Handler ui=new Handler(Looper.getMainLooper());
    private FrameLayout root,content;
    private int screen=HOME,versionTaps=0;
    private JSONObject account=new JSONObject();
    private LocationManager locationManager;
    private Location lastLocation;
    private NativeMapView mapView;
    private TextView playerNow;
    private boolean playerReceiverRegistered=false,autoTried=false;

    private static final State[] STATES={
            new State("AC","Acre","12"),new State("AL","Alagoas","27"),new State("AP","Amapá","16"),new State("AM","Amazonas","13"),new State("BA","Bahia","29"),new State("CE","Ceará","23"),new State("DF","Distrito Federal","53"),new State("ES","Espírito Santo","32"),new State("GO","Goiás","52"),new State("MA","Maranhão","21"),new State("MT","Mato Grosso","51"),new State("MS","Mato Grosso do Sul","50"),new State("MG","Minas Gerais","31"),new State("PA","Pará","15"),new State("PB","Paraíba","25"),new State("PR","Paraná","41"),new State("PE","Pernambuco","26"),new State("PI","Piauí","22"),new State("RJ","Rio de Janeiro","33"),new State("RN","Rio Grande do Norte","24"),new State("RS","Rio Grande do Sul","43"),new State("RO","Rondônia","11"),new State("RR","Roraima","14"),new State("SC","Santa Catarina","42"),new State("SP","São Paulo","35"),new State("SE","Sergipe","28"),new State("TO","Tocantins","17")
    };
    private static final class State {final String uf,name,id;State(String u,String n,String i){uf=u;name=n;id=i;}@Override public String toString(){return name+" · "+uf;}}

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        prefs=getSharedPreferences(PREFS,MODE_PRIVATE);api=new NativeApiClient(this);offline=new OfflineStore(this);locationManager=(LocationManager)getSystemService(LOCATION_SERVICE);
        root=new FrameLayout(this);root.setBackgroundColor(BG);setContentView(root);registerPlayerReceiver();requestNotifications();loadAccount();boot();
    }

    private void boot(){if(prefs.getBoolean(KEY_SETUP,false)&&hasAccount()){showShell();refreshAccount();return;}showSetup();String s=server();if(!autoTried&&s.startsWith("https://")&&!s.contains("SEU-DOMINIO")){autoTried=true;ui.postDelayed(()->connectAndRecover(s,true),500);}}
    private void loadAccount(){try{String s=offline.account();if(s!=null&&!s.trim().isEmpty())account=new JSONObject(s);}catch(Exception ignored){account=new JSONObject();}}
    private boolean hasAccount(){return account.optJSONObject("user")!=null||account.optBoolean("authenticated",false);}
    private void saveAccount(JSONObject a){account=a==null?new JSONObject():a;offline.saveAccount(account.toString());}
    private void clearAccount(){account=new JSONObject();offline.saveAccount("{}");}
    private String server(){String s=prefs.getString(KEY_SERVER,"");if(s==null||s.trim().isEmpty())s=BuildConfig.MUSICROAD_URL;return NativeApiClient.normalizeBase(s);}
    private boolean landscape(){return getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;}
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}

    private GradientDrawable bg(int color,int radius,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));if(stroke!=0)d.setStroke(dp(1),stroke);return d;}
    private TextView t(String s,float size,int color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);v.setGravity(Gravity.CENTER_VERTICAL);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;}
    private TextView small(String s){return t(s,12,MUTED,false);}
    private Button btn(String s,boolean primary){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(12);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setTextColor(primary?Color.rgb(25,13,5):TEXT);b.setBackground(bg(primary?ACCENT:PANEL2,14,primary?0:LINE));b.setMinHeight(dp(50));return b;}
    private EditText edit(String hint){EditText e=new EditText(this);e.setHint(hint);e.setHintTextColor(Color.rgb(97,118,134));e.setTextColor(TEXT);e.setTextSize(15);e.setSingleLine(true);e.setPadding(dp(14),0,dp(14),0);e.setBackground(bg(Color.rgb(8,14,20),13,LINE));return e;}
    private LinearLayout card(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(16),dp(15),dp(16),dp(15));l.setBackground(bg(PANEL,18,LINE));return l;}
    private void margins(View v,int l,int top,int r,int bottom){ViewGroup.MarginLayoutParams p=v.getLayoutParams() instanceof ViewGroup.MarginLayoutParams?(ViewGroup.MarginLayoutParams)v.getLayoutParams():new ViewGroup.MarginLayoutParams(-1,-2);p.setMargins(dp(l),dp(top),dp(r),dp(bottom));v.setLayoutParams(p);}

    private void showSetup(){
        root.removeAllViews();LinearLayout outer=new LinearLayout(this);outer.setGravity(Gravity.CENTER);outer.setPadding(dp(20),dp(20),dp(20),dp(20));root.addView(outer,new FrameLayout.LayoutParams(-1,-1));LinearLayout c=card();outer.addView(c,new LinearLayout.LayoutParams(landscape()?dp(620):-1,-2));
        TextView mark=t("MR",15,Color.rgb(25,13,5),true);mark.setGravity(Gravity.CENTER);mark.setBackground(bg(ACCENT,16,0));c.addView(mark,new LinearLayout.LayoutParams(dp(52),dp(52)));
        TextView h=t("Preparar o MusicRoad",28,TEXT,true);c.addView(h);margins(h,0,18,0,6);TextView p=t("O aplicativo agora é Android nativo. O servidor fica para conta, pagamentos e sincronização; a interface e os dados baixados permanecem no aparelho.",13,MUTED,false);p.setLineSpacing(0,1.2f);c.addView(p);margins(p,0,0,0,16);
        EditText url=edit("https://seu-servidor.com/");url.setText(server());c.addView(url,new LinearLayout.LayoutParams(-1,dp(54)));TextView status=small("Aguardando conexão segura…");c.addView(status);margins(status,2,9,2,11);Button go=btn("Conectar e reconhecer dispositivo",true);c.addView(go,new LinearLayout.LayoutParams(-1,dp(54)));TextView dev=small("Dispositivo: "+DeviceIdentity.label()+" · identificação protegida");c.addView(dev);margins(dev,2,11,0,0);
        go.setOnClickListener(v->{String s=url.getText().toString().trim();if(!s.startsWith("https://")){status.setText("Use um endereço HTTPS.");status.setTextColor(RED);return;}connectAndRecover(s,false);});
    }

    private void connectAndRecover(String url,boolean automatic){
        String base=NativeApiClient.normalizeBase(url);toast("Verificando sua conta…");io.execute(()->{try{
            NativeApiClient.Response ping=api.get(base,"api/native_app.php?action=ping");if(!ping.ok()||!ping.json().optBoolean("ok"))throw new Exception("Servidor MusicRoad não respondeu.");prefs.edit().putString(KEY_SERVER,base).apply();JSONObject d=new JSONObject();d.put("device_token",DeviceIdentity.token(this));d.put("app_version",BuildConfig.VERSION_NAME);NativeApiClient.Response r=api.post(base,"api/native_app.php?action=device_login",d);JSONObject j=r.json();
            if(r.ok()&&j.optBoolean("ok")&&j.optJSONObject("account")!=null){saveAccount(j.optJSONObject("account"));prefs.edit().putBoolean(KEY_SETUP,true).apply();ui.post(()->{toast("Dispositivo reconhecido.");showShell();});}else ui.post(()->{prefs.edit().putBoolean(KEY_SETUP,true).apply();showAuth(false,"Servidor conectado. Entre ou crie sua conta.");});
        }catch(Exception e){ui.post(()->{if(hasAccount()){toast("Sem conexão. Abrindo offline.");showShell();}else if(!automatic)alert("Não foi possível conectar",err(e,"Confira o endereço e a internet."));});}});
    }

    private void showAuth(boolean register,String note){
        root.removeAllViews();ScrollView sv=new ScrollView(this);root.addView(sv,new FrameLayout.LayoutParams(-1,-1));LinearLayout outer=new LinearLayout(this);outer.setGravity(Gravity.CENTER_HORIZONTAL);outer.setPadding(dp(20),dp(26),dp(20),dp(40));sv.addView(outer,new ScrollView.LayoutParams(-1,-2));LinearLayout c=card();outer.addView(c,new LinearLayout.LayoutParams(landscape()?dp(620):-1,-2));
        c.addView(t(register?"NOVO MOTORISTA · TESTE 24H":"CONTA MUSICROAD",11,ACCENT,true));TextView h=t(register?"Criar conta":"Entrar no MusicRoad",27,TEXT,true);c.addView(h);margins(h,0,6,0,4);if(note!=null){c.addView(small(note));}
        EditText name=null,email=null,user=edit(register?"Login":"Usuário ou e-mail"),pass=edit("Senha");pass.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if(register){name=edit("Nome");email=edit("E-mail");email.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);c.addView(name,new LinearLayout.LayoutParams(-1,dp(54)));margins(name,0,13,0,0);c.addView(email,new LinearLayout.LayoutParams(-1,dp(54)));margins(email,0,10,0,0);}c.addView(user,new LinearLayout.LayoutParams(-1,dp(54)));margins(user,0,10,0,0);c.addView(pass,new LinearLayout.LayoutParams(-1,dp(54)));margins(pass,0,10,0,0);
        Button submit=btn(register?"Criar conta e iniciar teste":"Entrar",true),toggle=btn(register?"Já tenho uma conta":"Criar conta · testar 24 horas",false);c.addView(submit,new LinearLayout.LayoutParams(-1,dp(54)));margins(submit,0,13,0,0);c.addView(toggle,new LinearLayout.LayoutParams(-1,dp(50)));margins(toggle,0,8,0,0);TextView nativeLabel=small("Interface 100% Android · sem WebView");nativeLabel.setGravity(Gravity.CENTER);c.addView(nativeLabel);margins(nativeLabel,0,14,0,0);toggle.setOnClickListener(v->showAuth(!register,null));
        EditText fn=name,fe=email;submit.setOnClickListener(v->{String u=user.getText().toString().trim(),pw=pass.getText().toString();if(u.isEmpty()||pw.isEmpty()){toast("Preencha os campos.");return;}submit.setEnabled(false);JSONObject d=new JSONObject();try{d.put("device_token",DeviceIdentity.token(this));d.put("device_label",DeviceIdentity.label());d.put("app_version",BuildConfig.VERSION_NAME);if(register){d.put("name",fn.getText().toString().trim());d.put("email",fe.getText().toString().trim());d.put("username",u);d.put("password",pw);}else{d.put("login",u);d.put("password",pw);}}catch(Exception ignored){}String action=register?"register":"login";io.execute(()->{try{NativeApiClient.Response r=api.post(server(),"api/native_app.php?action="+action,d);JSONObject j=r.json();if(!r.ok()||!j.optBoolean("ok")){ui.post(()->{submit.setEnabled(true);alert("MusicRoad",j.optString("error","Não foi possível entrar."));});return;}saveAccount(j.optJSONObject("account"));prefs.edit().putBoolean(KEY_SETUP,true).apply();ui.post(()->showShell());}catch(Exception e){ui.post(()->{submit.setEnabled(true);alert("Sem conexão",err(e,"Tente novamente."));});}});});
    }

    private void showShell(){
        root.removeAllViews();LinearLayout shell=new LinearLayout(this);shell.setOrientation(landscape()?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);root.addView(shell,new FrameLayout.LayoutParams(-1,-1));if(landscape())shell.addView(rail(),new LinearLayout.LayoutParams(dp(102),-1));LinearLayout main=new LinearLayout(this);main.setOrientation(LinearLayout.VERTICAL);shell.addView(main,new LinearLayout.LayoutParams(0,-1,1));main.addView(top(),new LinearLayout.LayoutParams(-1,dp(64)));content=new FrameLayout(this);main.addView(content,new LinearLayout.LayoutParams(-1,0,1));render();
    }
    private View top(){LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(12),dp(8),dp(12),dp(8));bar.setBackgroundColor(Color.rgb(8,13,19));if(!landscape()){Button menu=btn("☰",false);menu.setTextSize(20);bar.addView(menu,new LinearLayout.LayoutParams(dp(50),dp(48)));menu.setOnClickListener(v->menu());}LinearLayout brand=new LinearLayout(this);brand.setOrientation(LinearLayout.VERTICAL);brand.setPadding(dp(11),0,0,0);brand.addView(t("MusicRoad",18,TEXT,true));brand.addView(t(name(screen),10,MUTED,true));bar.addView(brand,new LinearLayout.LayoutParams(0,-1,1));TextView net=t(online()?"ONLINE":"OFFLINE",10,online()?GREEN:ACCENT,true);net.setGravity(Gravity.CENTER);net.setBackground(bg(PANEL2,12,LINE));bar.addView(net,new LinearLayout.LayoutParams(dp(76),dp(38)));return bar;}
    private View rail(){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.VERTICAL);r.setGravity(Gravity.CENTER_HORIZONTAL);r.setPadding(dp(8),dp(10),dp(8),dp(10));r.setBackgroundColor(Color.rgb(5,9,14));TextView m=t("MR",14,Color.rgb(25,13,5),true);m.setGravity(Gravity.CENTER);m.setBackground(bg(ACCENT,15,0));r.addView(m,new LinearLayout.LayoutParams(dp(50),dp(50)));addRail(r,"HOME",HOME);addRail(r,"NAV",MAP);addRail(r,"MEDIA",MUSIC);addRail(r,"OFF",OFFLINE);addRail(r,"AJUSTES",SETTINGS);Space sp=new Space(this);r.addView(sp,new LinearLayout.LayoutParams(1,0,1));TextView v=small("v"+BuildConfig.VERSION_NAME);v.setGravity(Gravity.CENTER);r.addView(v,new LinearLayout.LayoutParams(-1,dp(28)));return r;}
    private void addRail(LinearLayout r,String s,int id){Button b=btn(s,false);if(id==screen)b.setBackground(bg(PANEL2,14,ACCENT));r.addView(b,new LinearLayout.LayoutParams(-1,dp(52)));margins(b,0,9,0,0);b.setOnClickListener(v->go(id));}
    private void menu(){String[] a={"Início","Navegação","Música","Mapas offline","Ajustes"};new AlertDialog.Builder(this).setTitle("MusicRoad").setItems(a,(d,w)->go(w)).setNegativeButton("Fechar",null).show();}
    private void go(int s){screen=s;showShell();}
    private String name(int s){return s==MAP?"NAVEGAÇÃO":s==MUSIC?"MÚSICA":s==OFFLINE?"OFFLINE":s==SETTINGS?"AJUSTES":"INÍCIO";}
    private void render(){content.removeAllViews();if(screen==MAP)map();else if(screen==MUSIC)music();else if(screen==OFFLINE)offline();else if(screen==SETTINGS)settings();else home();}

    private void home(){ScrollView sv=new ScrollView(this);content.addView(sv,new FrameLayout.LayoutParams(-1,-1));LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(landscape()?28:18),dp(22),dp(landscape()?28:18),dp(30));sv.addView(body,new ScrollView.LayoutParams(-1,-2));JSONObject u=account.optJSONObject("user");String n=u==null?"motorista":u.optString("name","motorista");body.addView(small("Olá, "+first(n)+"."));TextView h=t("Para onde vamos?",landscape()?38:33,TEXT,true);body.addView(h);margins(h,0,4,0,16);Button d=btn("⌖  Buscar destino",true);d.setTextSize(16);body.addView(d,new LinearLayout.LayoutParams(-1,dp(64)));d.setOnClickListener(v->go(MAP));LinearLayout q=new LinearLayout(this);q.setOrientation(landscape()?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);body.addView(q);margins(q,0,15,0,0);quick(q,"NAVEGAR","Mapa, GPS e alertas",MAP);quick(q,"MÚSICA","Biblioteca do aparelho",MUSIC);quick(q,"OFFLINE","Mapas no dispositivo",OFFLINE);LinearLayout s=card();body.addView(s);margins(s,0,16,0,0);s.addView(t("PRONTO PARA DIRIGIR",11,GREEN,true));s.addView(t(check(Manifest.permission.ACCESS_FINE_LOCATION)?"GPS autorizado":"GPS precisa de permissão",14,TEXT,true));s.addView(small("Conta e interface ficam locais. Novos dados sincronizam quando houver internet."));}
    private void quick(LinearLayout q,String h,String sub,int id){LinearLayout c=card();c.addView(t(h,16,TEXT,true));c.addView(small(sub));LinearLayout.LayoutParams p=landscape()?new LinearLayout.LayoutParams(0,dp(105),1):new LinearLayout.LayoutParams(-1,dp(94));if(landscape())p.setMargins(0,0,dp(10),0);else p.setMargins(0,0,0,dp(9));q.addView(c,p);c.setOnClickListener(v->go(id));}

    private void map(){
        locate();LinearLayout wrap=new LinearLayout(this);wrap.setOrientation(landscape()?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);content.addView(wrap,new FrameLayout.LayoutParams(-1,-1));mapView=new NativeMapView(this);loadMap();loadRoute();if(lastLocation!=null)mapView.setUserLocation(lastLocation.getLatitude(),lastLocation.getLongitude());LinearLayout c=card();EditText dest=edit("Destino · cidade, rua ou endereço");Button route=btn("Calcular rota",true),stop=btn("Encerrar navegação",false);TextView summary=small(lastLocation==null?"Aguardando GPS…":"GPS pronto");c.addView(t("NAVEGAÇÃO",11,ACCENT,true));c.addView(dest,new LinearLayout.LayoutParams(-1,dp(52)));margins(dest,0,9,0,0);c.addView(route,new LinearLayout.LayoutParams(-1,dp(50)));margins(route,0,9,0,0);c.addView(summary);margins(summary,0,10,0,0);c.addView(stop,new LinearLayout.LayoutParams(-1,dp(48)));margins(stop,0,10,0,0);if(landscape()){wrap.addView(mapView,new LinearLayout.LayoutParams(0,-1,1));LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(330),-1);cp.setMargins(dp(9),dp(9),dp(9),dp(9));wrap.addView(c,cp);}else{wrap.addView(c,new LinearLayout.LayoutParams(-1,-2));wrap.addView(mapView,new LinearLayout.LayoutParams(-1,0,1));}route.setOnClickListener(v->{String d=dest.getText().toString().trim();if(d.length()<2){toast("Informe um destino.");return;}if(lastLocation==null){toast("Aguardando GPS.");locate();return;}calc(d,route,summary);});stop.setOnClickListener(v->{try{startService(NavigationService.stopIntent(this));}catch(Exception ignored){}mapView.clearRoute();summary.setText("Navegação encerrada.");});
    }
    private void calc(String destination,Button b,TextView summary){if(!online()){summary.setText("Sem internet. Última rota e mapas baixados continuam disponíveis.");return;}b.setEnabled(false);b.setText("Calculando…");String origin=String.format(Locale.US,"%.7f,%.7f",lastLocation.getLatitude(),lastLocation.getLongitude());io.execute(()->{try{NativeApiClient.Response r=api.get(server(),"api/route.php?origin="+enc(origin)+"&destination="+enc(destination));JSONObject j=r.json();if(!r.ok()||!j.optBoolean("ok")){ui.post(()->{b.setEnabled(true);b.setText("Calcular rota");summary.setText(j.optString("error","Falha na rota."));});return;}JSONObject route=j.optJSONObject("route"),geom=route==null?null:route.optJSONObject("geometry");JSONArray coords=geom==null?null:geom.optJSONArray("coordinates");if(coords==null||coords.length()<2)throw new Exception("Rota sem geometria.");JSONObject saved=new JSONObject();saved.put("destination",destination);saved.put("route",route);offline.saveRoute(saved.toString());JSONArray hazards=hazards();ui.post(()->{b.setEnabled(true);b.setText("Recalcular");mapView.setRoute(coords);mapView.setRadars(hazards);summary.setText(km(route.optDouble("distance",0))+" · "+duration(route.optDouble("duration",0)));try{Intent i=NavigationService.startIntent(this,coords.toString(),hazards.toString(),"[]",destination);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}catch(Exception ignored){}});}catch(Exception e){ui.post(()->{b.setEnabled(true);b.setText("Calcular rota");summary.setText(err(e,"Falha ao calcular rota."));});}});}
    private void loadMap(){String uf=prefs.getString(KEY_UF,"");if(uf==null||uf.isEmpty())return;try{String raw=offline.get("state/"+uf+"/map");if(!raw.isEmpty()){mapView.setOfflinePack(new JSONObject(raw));mapView.setMessage("Mapa offline "+uf+" · no aparelho");}}catch(Exception ignored){}}
    private void loadRoute(){try{String raw=offline.route();if(raw.isEmpty())return;JSONObject j=new JSONObject(raw),r=j.optJSONObject("route"),g=r==null?null:r.optJSONObject("geometry");JSONArray c=g==null?null:g.optJSONArray("coordinates");if(c!=null)mapView.setRoute(c);}catch(Exception ignored){}}
    private JSONArray hazards(){String uf=prefs.getString(KEY_UF,"");if(uf==null)return new JSONArray();for(String kind:new String[]{"radars","map"})try{String raw=offline.get("state/"+uf+"/"+kind);if(!raw.isEmpty()){JSONArray a=new JSONObject(raw).optJSONArray("radars");if(a!=null)return a;}}catch(Exception ignored){}return new JSONArray();}

    private void music(){ScrollView sv=new ScrollView(this);content.addView(sv,new FrameLayout.LayoutParams(-1,-1));LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(18),dp(18),dp(18),dp(28));sv.addView(body,new ScrollView.LayoutParams(-1,-2));LinearLayout p=card();body.addView(p);p.addView(t("AGORA TOCANDO",10,ACCENT,true));playerNow=t("Nenhuma música",17,TEXT,true);p.addView(playerNow);LinearLayout controls=new LinearLayout(this);Button prev=btn("◀",false),play=btn("▶ / ❚❚",true),next=btn("▶",false);controls.addView(prev,new LinearLayout.LayoutParams(0,dp(48),1));controls.addView(play,new LinearLayout.LayoutParams(0,dp(48),1));controls.addView(next,new LinearLayout.LayoutParams(0,dp(48),1));p.addView(controls);prev.setOnClickListener(v->startService(PlaybackService.intentAction(this,PlaybackService.ACTION_PREVIOUS)));play.setOnClickListener(v->startService(PlaybackService.intentAction(this,PlaybackService.ACTION_TOGGLE)));next.setOnClickListener(v->startService(PlaybackService.intentAction(this,PlaybackService.ACTION_NEXT)));TextView h=t("Músicas do dispositivo",22,TEXT,true);body.addView(h);margins(h,0,18,0,8);TextView status=small("Lendo biblioteca…");body.addView(status);if(!NativeMusicRepository.hasPermission(this)){Button allow=btn("Permitir acesso às músicas",true);body.addView(allow,new LinearLayout.LayoutParams(-1,dp(52)));allow.setOnClickListener(v->requestPermissions(new String[]{NativeMusicRepository.permissionName()},REQ_AUDIO));return;}io.execute(()->{List<MusicTrack> tracks=NativeMusicRepository.scan(this);ui.post(()->{status.setText(tracks.size()+" músicas");for(int i=0;i<Math.min(tracks.size(),400);i++){MusicTrack tr=tracks.get(i);LinearLayout row=card();row.setPadding(dp(13),dp(11),dp(13),dp(11));row.addView(t(tr.title,15,TEXT,true));row.addView(small(tr.artist));body.addView(row);margins(row,0,8,0,0);final int ix=i;row.setOnClickListener(v->play(tracks,ix));}});});}
    private void play(List<MusicTrack> tracks,int start){try{JSONArray a=new JSONArray();for(MusicTrack tr:tracks)a.put(tr.toJson());startService(PlaybackService.intentSetQueue(this,a.toString(),start,true));}catch(Exception e){toast("Não foi possível tocar.");}}

    private void offline(){ScrollView sv=new ScrollView(this);content.addView(sv,new FrameLayout.LayoutParams(-1,-1));LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(18),dp(18),dp(18),dp(30));sv.addView(body,new ScrollView.LayoutParams(-1,-2));body.addView(t("Mapas no dispositivo",28,TEXT,true));TextView intro=t("Baixe um estado. O mapa vetorial e os radares ficam no armazenamento privado do aplicativo.",13,MUTED,false);body.addView(intro);margins(intro,0,7,0,16);LinearLayout c=card();body.addView(c);Spinner sp=new Spinner(this);sp.setAdapter(new ArrayAdapter<State>(this,android.R.layout.simple_spinner_dropdown_item,STATES));String last=prefs.getString(KEY_UF,"");for(int i=0;i<STATES.length;i++)if(STATES[i].uf.equals(last))sp.setSelection(i);c.addView(sp,new LinearLayout.LayoutParams(-1,dp(54)));TextView status=small("Selecione o estado.");c.addView(status);margins(status,0,8,0,10);Button map=btn("Baixar mapa do estado",true),rad=btn("Baixar radares do estado",false),del=btn("Excluir dados deste estado",false);c.addView(map,new LinearLayout.LayoutParams(-1,dp(52)));c.addView(rad,new LinearLayout.LayoutParams(-1,dp(52)));margins(rad,0,8,0,0);c.addView(del,new LinearLayout.LayoutParams(-1,dp(48)));margins(del,0,8,0,0);Runnable update=()->{State s=(State)sp.getSelectedItem();status.setText((offline.has("state/"+s.uf+"/map")?"Mapa ✓":"Mapa —")+" · "+(offline.has("state/"+s.uf+"/radars")?"Radares ✓":"Radares —"));};sp.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){update.run();}public void onNothingSelected(android.widget.AdapterView<?> p){}});update.run();map.setOnClickListener(v->download((State)sp.getSelectedItem(),"map",map,status,update));rad.setOnClickListener(v->download((State)sp.getSelectedItem(),"radars",rad,status,update));del.setOnClickListener(v->{State s=(State)sp.getSelectedItem();offline.removePrefix("state/"+s.uf+"/");update.run();});}
    private void download(State s,String kind,Button b,TextView status,Runnable done){if(!online()){toast("Conecte-se para baixar.");return;}prefs.edit().putString(KEY_UF,s.uf).apply();b.setEnabled(false);b.setText("Preparando "+s.uf+"…");String path="api/offline_state.php?kind="+kind+"&state_id="+enc(s.id)+"&uf="+enc(s.uf)+"&state="+enc(s.name);io.execute(()->{try{NativeApiClient.Response r=api.getLarge(server(),path);JSONObject j=r.json();if(!r.ok()||!j.optBoolean("ok")){ui.post(()->{b.setEnabled(true);b.setText(kind.equals("map")?"Baixar mapa do estado":"Baixar radares do estado");status.setText(j.optString("error","Falha no download."));});return;}boolean ok=offline.put("state/"+s.uf+"/"+kind,r.body);ui.post(()->{b.setEnabled(true);b.setText(kind.equals("map")?"Baixar mapa do estado":"Baixar radares do estado");status.setText(ok?"Salvo no dispositivo":"Falha ao salvar");done.run();});}catch(Exception e){ui.post(()->{b.setEnabled(true);status.setText(err(e,"Falha no download."));});}});}

    private void settings(){ScrollView sv=new ScrollView(this);content.addView(sv,new FrameLayout.LayoutParams(-1,-1));LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(18),dp(18),dp(18),dp(30));sv.addView(body,new ScrollView.LayoutParams(-1,-2));body.addView(t("Ajustes",28,TEXT,true));JSONObject u=account.optJSONObject("user");LinearLayout a=card();body.addView(a);margins(a,0,14,0,0);a.addView(t(u==null?"Conta local":u.optString("name","Conta"),18,TEXT,true));a.addView(small(u==null?"Offline":u.optString("email",u.optString("username",""))));a.addView(small("Servidor: "+server()));LinearLayout opts=card();body.addView(opts);margins(opts,0,11,0,0);SharedPreferences ap=getSharedPreferences(NavigationService.ALERT_PREFS,MODE_PRIVATE);Switch voice=sw("Voz do copiloto",ap.getBoolean("voice",true)),vib=sw("Vibração",ap.getBoolean("vibrate",true));opts.addView(voice);opts.addView(vib);voice.setOnCheckedChangeListener((x,y)->ap.edit().putBoolean("voice",y).apply());vib.setOnCheckedChangeListener((x,y)->ap.edit().putBoolean("vibrate",y).apply());LinearLayout about=card();body.addView(about);margins(about,0,11,0,0);TextView ver=t("MusicRoad "+BuildConfig.VERSION_NAME,16,TEXT,true);about.addView(ver);about.addView(small("Android nativo · Native Core · sem WebView"));ver.setOnClickListener(v->{versionTaps++;if(versionTaps>=7){versionTaps=0;deviceDialog();}else if(versionTaps>=4)toast((7-versionTaps)+" toques para opções do dispositivo");});Button logout=btn("Sair desta conta",false);body.addView(logout,new LinearLayout.LayoutParams(-1,dp(52)));margins(logout,0,13,0,0);logout.setOnClickListener(v->{clearAccount();api.clearCookie();showAuth(false,"Sessão encerrada. O dispositivo continua vinculado.");});}
    private Switch sw(String s,boolean checked){Switch x=new Switch(this);x.setText(s);x.setTextColor(TEXT);x.setChecked(checked);x.setPadding(0,dp(8),0,dp(8));return x;}
    private void deviceDialog(){new AlertDialog.Builder(this).setTitle("Dispositivo registrado").setMessage("Remover desativa a entrada automática desta conta depois de reinstalar o aplicativo.").setPositiveButton("Remover dispositivo",(d,w)->removeDevice()).setNegativeButton("Cancelar",null).show();}
    private void removeDevice(){if(!online()){alert("Sem internet","A remoção precisa ser confirmada pelo servidor.");return;}JSONObject d=new JSONObject();try{d.put("device_token",DeviceIdentity.token(this));}catch(Exception ignored){}io.execute(()->{try{NativeApiClient.Response r=api.post(server(),"api/native_app.php?action=remove_device",d);JSONObject j=r.json();ui.post(()->{if(r.ok()&&j.optBoolean("removed")){clearAccount();api.clearCookie();showAuth(false,"Dispositivo removido.");}else alert("MusicRoad",j.optString("error","Não foi possível remover."));});}catch(Exception e){ui.post(()->alert("Falha",err(e,"Tente novamente.")));}});}

    private void refreshAccount(){if(!online())return;io.execute(()->{try{NativeApiClient.Response r=api.get(server(),"api/native_app.php?action=me");JSONObject a=r.json().optJSONObject("account");if(r.ok()&&a!=null)saveAccount(a);}catch(Exception ignored){}});}
    private void locate(){if(!check(Manifest.permission.ACCESS_FINE_LOCATION)){requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},REQ_LOCATION);return;}try{Location g=locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER),n=locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);lastLocation=g!=null?g:n;}catch(Exception ignored){}try{locationManager.removeUpdates(locationListener);}catch(Exception ignored){}try{locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,2f,locationListener);}catch(Exception ignored){}}
    private final LocationListener locationListener=new LocationListener(){@Override public void onLocationChanged(Location l){lastLocation=l;if(mapView!=null)mapView.setUserLocation(l.getLatitude(),l.getLongitude());}@Override public void onProviderEnabled(String p){}@Override public void onProviderDisabled(String p){}@Override public void onStatusChanged(String p,int s,Bundle e){}};
    private final BroadcastReceiver playerReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){if(!PlaybackService.ACTION_STATE.equals(i.getAction()))return;try{JSONObject j=new JSONObject(i.getStringExtra(PlaybackService.EXTRA_STATE_JSON));JSONObject tr=j.optJSONObject("track");if(playerNow!=null&&tr!=null)playerNow.setText(tr.optString("title","Música")+" · "+tr.optString("artist",""));}catch(Exception ignored){}}};
    private void registerPlayerReceiver(){if(playerReceiverRegistered)return;IntentFilter f=new IntentFilter(PlaybackService.ACTION_STATE);if(Build.VERSION.SDK_INT>=33)registerReceiver(playerReceiver,f,RECEIVER_NOT_EXPORTED);else registerReceiver(playerReceiver,f);playerReceiverRegistered=true;}
    private boolean online(){try{ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);if(Build.VERSION.SDK_INT>=23){android.net.Network n=cm.getActiveNetwork();if(n==null)return false;NetworkCapabilities c=cm.getNetworkCapabilities(n);return c!=null&&(c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)||c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)||c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));}NetworkInfo n=cm.getActiveNetworkInfo();return n!=null&&n.isConnected();}catch(Exception e){return false;}}
    private boolean check(String p){return Build.VERSION.SDK_INT<23||checkSelfPermission(p)==PackageManager.PERMISSION_GRANTED;}
    private void requestNotifications(){if(Build.VERSION.SDK_INT>=33&&!check(Manifest.permission.POST_NOTIFICATIONS))requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},REQ_NOTIFICATIONS);}

    @Override public void onRequestPermissionsResult(int req,String[] permissions,int[] grants){super.onRequestPermissionsResult(req,permissions,grants);if(req==REQ_LOCATION&&grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)locate();if(req==REQ_AUDIO&&grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED&&screen==MUSIC)render();}
    @Override public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);if(prefs.getBoolean(KEY_SETUP,false)&&hasAccount())showShell();}
    @Override protected void onResume(){super.onResume();if(screen==MAP)locate();try{startService(PlaybackService.intentAction(this,PlaybackService.ACTION_BROADCAST_STATE));}catch(Exception ignored){}}
    @Override protected void onPause(){super.onPause();try{locationManager.removeUpdates(locationListener);}catch(Exception ignored){}}
    @Override protected void onDestroy(){try{if(playerReceiverRegistered)unregisterReceiver(playerReceiver);}catch(Exception ignored){}io.shutdownNow();super.onDestroy();}
    @Override public void onBackPressed(){if(prefs.getBoolean(KEY_SETUP,false)&&hasAccount()&&screen!=HOME){go(HOME);return;}super.onBackPressed();}

    private void toast(String s){ui.post(()->Toast.makeText(this,s,Toast.LENGTH_SHORT).show());}
    private void alert(String title,String msg){ui.post(()->new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK",null).show());}
    private static String err(Exception e,String fallback){String m=e==null?"":e.getMessage();return m==null||m.trim().isEmpty()?fallback:(m.length()>180?m.substring(0,180):m);}
    private static String first(String s){s=s==null?"":s.trim();int i=s.indexOf(' ');return i>0?s.substring(0,i):s;}
    private static String enc(String s){try{return URLEncoder.encode(s,"UTF-8");}catch(Exception e){return s;}}
    private static String km(double m){return m<1000?Math.round(m)+" m":String.format(Locale.getDefault(),"%.1f km",m/1000.0);}
    private static String duration(double sec){long m=Math.round(sec/60.0);return m<60?m+" min":(m/60)+"h "+(m%60)+"min";}
}

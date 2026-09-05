package com.musicroad.ai;

import android.Manifest;
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
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
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
import android.os.Environment;
import android.text.InputType;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import android.media.MediaScannerConnection;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.net.URL;
import java.net.HttpURLConnection;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends ComponentActivity {
    private static final String PREFS="musicroad_native_shell_v1",KEY_SERVER="server_url",KEY_SETUP="setup_complete",KEY_UF="last_offline_uf";
    private static final int REQ_LOCATION=1001,REQ_AUDIO=1002,REQ_NOTIFICATIONS=1003;
    private static final int HOME=0,MAP=1,MUSIC=2,OFFLINE=3,SETTINGS=4,RADIO=5;
    private final int BG=Color.rgb(5,11,18),PANEL=Color.rgb(9,18,28),PANEL2=Color.rgb(14,25,39),LINE=Color.rgb(37,55,72),TEXT=Color.rgb(247,249,252),MUTED=Color.rgb(139,156,177),ACCENT=Color.rgb(255,122,26),PURPLE=Color.rgb(142,49,255),PURPLE2=Color.rgb(91,51,210),GREEN=Color.rgb(42,224,135),RED=Color.rgb(255,88,91);
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
    private FastMapboxRouteEngine mapboxRoute;
    private Button activeRouteButton;
    private double currentDestinationLat=Double.NaN,currentDestinationLon=Double.NaN;
    private int mapboxOffRouteSamples=0;
    private long lastMapboxRerouteAt=0;
    private TextView playerNow,hudSpeed,hudLimit,hudRadar,turnInstruction,landscapeTripInfo;
    private boolean landscapePaneRender=false;
    private double currentRouteDistanceMeters=0d,currentRouteDurationSeconds=0d;
    private boolean playerReceiverRegistered=false,autoTried=false;
    private String pendingDestination="",currentDestination="";
    private JSONArray currentHazards=new JSONArray(),currentRouteCoords=new JSONArray();
    private final List<MusicTrack> deviceTracks=new ArrayList<>(),onlineTracks=new ArrayList<>(),shownTracks=new ArrayList<>();
    private String musicSource="all",musicFolder="all",musicGenre="all";
    private static final String KEY_RADIO="radio_stations_v1",KEY_FAVORITES="music_favorites_v1";

    private static final State[] STATES={
            new State("AC","Acre","12"),new State("AL","Alagoas","27"),new State("AP","Amapá","16"),new State("AM","Amazonas","13"),new State("BA","Bahia","29"),new State("CE","Ceará","23"),new State("DF","Distrito Federal","53"),new State("ES","Espírito Santo","32"),new State("GO","Goiás","52"),new State("MA","Maranhão","21"),new State("MT","Mato Grosso","51"),new State("MS","Mato Grosso do Sul","50"),new State("MG","Minas Gerais","31"),new State("PA","Pará","15"),new State("PB","Paraíba","25"),new State("PR","Paraná","41"),new State("PE","Pernambuco","26"),new State("PI","Piauí","22"),new State("RJ","Rio de Janeiro","33"),new State("RN","Rio Grande do Norte","24"),new State("RS","Rio Grande do Sul","43"),new State("RO","Rondônia","11"),new State("RR","Roraima","14"),new State("SC","Santa Catarina","42"),new State("SP","São Paulo","35"),new State("SE","Sergipe","28"),new State("TO","Tocantins","17")
    };
    private static final class State {final String uf,name,id;State(String u,String n,String i){uf=u;name=n;id=i;}@Override public String toString(){return name+" · "+uf;}}

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        prefs=getSharedPreferences(PREFS,MODE_PRIVATE);api=new NativeApiClient(this);offline=new OfflineStore(this);locationManager=(LocationManager)getSystemService(LOCATION_SERVICE);
        mapboxRoute=new FastMapboxRouteEngine(this,api,server(),new FastMapboxRouteEngine.Listener(){
            @Override public void onRoute(JSONArray coords,double distance,double durationSeconds,String destination,double destLat,double destLon,boolean reroute){
                currentRouteCoords=coords;currentDestination=destination;currentDestinationLat=destLat;currentDestinationLon=destLon;pendingDestination="";currentHazards=new JSONArray();mapboxOffRouteSamples=0;currentRouteDistanceMeters=distance;currentRouteDurationSeconds=durationSeconds;
                try{JSONObject route=new JSONObject();JSONObject geom=new JSONObject();geom.put("coordinates",coords);route.put("geometry",geom);route.put("distance",distance);route.put("duration",durationSeconds);JSONObject saved=new JSONObject();saved.put("destination",destination);saved.put("destination_lat",destLat);saved.put("destination_lon",destLon);saved.put("route",route);saved.put("radars",currentHazards);offline.saveRoute(saved.toString());}catch(Exception ignored){}
                if(activeRouteButton!=null){activeRouteButton.setEnabled(true);activeRouteButton.setText("↻");}
                if(mapView!=null){mapView.setRoute(coords);mapView.setRadars(currentHazards);mapView.fitRoute();mapView.setMessage("Mapbox · rota pronta");if(landscape()){mapView.setDrivingMode(true);if(lastLocation!=null)ui.postDelayed(()->{if(mapView!=null&&lastLocation!=null)mapView.recenter(lastLocation.getLatitude(),lastLocation.getLongitude());},900);}}if(landscapeTripInfo!=null)landscapeTripInfo.setText(km(distance));
                if(turnInstruction!=null)turnInstruction.setText(reroute?"Rota recalculada pelo Mapbox":"Rota Mapbox ativa");
                if(hudRadar!=null)hudRadar.setText("--");
                try{Intent i=reroute?NavigationService.routeChangedIntent(MainActivity.this,coords.toString(),currentHazards.toString(),destination):NavigationService.startIntent(MainActivity.this,coords.toString(),currentHazards.toString(),"[]",destination);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}catch(Exception ignored){}
                toast((reroute?"Rota recalculada · ":"")+km(distance)+" · "+duration(durationSeconds));
            }
            @Override public void onHazards(JSONArray hazards){
                currentHazards=hazards==null?new JSONArray():hazards;
                if(mapView!=null)mapView.setRadars(currentHazards);
                if(hudRadar!=null)hudRadar.setText(nearestHazardText());
                try{startService(NavigationService.updateIntent(MainActivity.this,currentHazards.toString(),"[]"));}catch(Exception ignored){}
            }
            @Override public void onError(String message){if(activeRouteButton!=null){activeRouteButton.setEnabled(true);activeRouteButton.setText(currentRouteCoords.length()>1?"↻":"IR");}toast(message);if(mapView!=null)mapView.setMessage("Mapbox: "+message);}
        });
        root=new FrameLayout(this);root.setBackgroundColor(BG);setContentView(root);registerPlayerReceiver();requestNotifications();loadAccount();boot();
    }

    private void boot(){
        if(hasAccount()){prefs.edit().putBoolean(KEY_SETUP,true).apply();showShell();refreshAccount();return;}
        showAuth(false,"Entre na sua conta ou crie seu acesso gratuito. O MusicRoad reconhece este aparelho automaticamente.");
        String s=server();if(!autoTried&&online()&&s.startsWith("https://")){autoTried=true;ui.postDelayed(()->connectAndRecover(s,true),350);}
    }
    private void loadAccount(){try{String s=offline.account();if(s!=null&&!s.trim().isEmpty())account=new JSONObject(s);}catch(Exception ignored){account=new JSONObject();}}
    private boolean hasAccount(){return account.optJSONObject("user")!=null||account.optBoolean("authenticated",false);}
    private void saveAccount(JSONObject a){account=a==null?new JSONObject():a;offline.saveAccount(account.toString());}
    private void clearAccount(){account=new JSONObject();offline.saveAccount("{}");}
    private String server(){return NativeApiClient.normalizeBase(BuildConfig.MUSICROAD_URL);}
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
        if(landscape()){showLandscapeShell();return;}
        setAutomotiveImmersive(false);
        root.removeAllViews();
        LinearLayout shell=new LinearLayout(this);shell.setOrientation(LinearLayout.VERTICAL);root.addView(shell,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout main=new LinearLayout(this);main.setOrientation(LinearLayout.VERTICAL);shell.addView(main,new LinearLayout.LayoutParams(-1,0,1));
        if(screen!=MAP)main.addView(top(),new LinearLayout.LayoutParams(-1,dp(80)));
        content=new FrameLayout(this);main.addView(content,new LinearLayout.LayoutParams(-1,0,1));render();
    }

    private void showLandscapeShell(){
        setAutomotiveImmersive(true);
        root.removeAllViews();root.setBackgroundColor(Color.rgb(2,7,12));
        LinearLayout outer=new LinearLayout(this);outer.setOrientation(LinearLayout.VERTICAL);root.addView(outer,new FrameLayout.LayoutParams(-1,-1));
        outer.addView(driveStatusBar(),new LinearLayout.LayoutParams(-1,dp(40)));
        LinearLayout shell=new LinearLayout(this);shell.setOrientation(LinearLayout.HORIZONTAL);outer.addView(shell,new LinearLayout.LayoutParams(-1,0,1));
        shell.addView(driveRail(),new LinearLayout.LayoutParams(dp(74),-1));
        content=new FrameLayout(this);content.setBackgroundColor(Color.rgb(4,10,16));shell.addView(content,new LinearLayout.LayoutParams(0,-1,1));
        renderLandscape();
    }

    private View driveRail(){
        LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.VERTICAL);r.setGravity(Gravity.CENTER_HORIZONTAL);r.setPadding(dp(6),dp(7),dp(6),dp(7));r.setBackgroundColor(Color.rgb(3,8,13));
        TextView mark=t("MR",14,Color.WHITE,true);mark.setGravity(Gravity.CENTER);mark.setBackground(bg(Color.rgb(26,32,40),14,Color.rgb(48,58,70)));r.addView(mark,new LinearLayout.LayoutParams(-1,dp(46)));
        driveRailButton(r,"⌂","HOME",HOME);driveRailButton(r,"➤","NAV",MAP);driveRailButton(r,"♫","MÍDIA",MUSIC);driveRailButton(r,"FM","RÁDIO",RADIO);driveRailButton(r,"⇩","OFF",OFFLINE);driveRailButton(r,"⚙","AJUSTE",SETTINGS);
        Space sp=new Space(this);r.addView(sp,new LinearLayout.LayoutParams(1,0,1));TextView ver=t("v"+BuildConfig.VERSION_NAME,8,Color.rgb(92,108,122),false);ver.setGravity(Gravity.CENTER);r.addView(ver,new LinearLayout.LayoutParams(-1,dp(20)));return r;
    }

    private void driveRailButton(LinearLayout r,String icon,String label,int target){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setGravity(Gravity.CENTER);box.setPadding(dp(2),dp(3),dp(2),dp(3));
        boolean active=screen==target;
        box.setBackground(active?bg(Color.rgb(42,25,17),14,ACCENT):bg(Color.TRANSPARENT,14,0));
        TextView i=t(icon,target==RADIO?12:19,active?ACCENT:Color.rgb(158,170,181),true);i.setGravity(Gravity.CENTER);box.addView(i,new LinearLayout.LayoutParams(-1,dp(28)));
        TextView l=t(label,7.5f,active?Color.WHITE:Color.rgb(120,136,149),true);l.setGravity(Gravity.CENTER);box.addView(l,new LinearLayout.LayoutParams(-1,dp(14)));
        r.addView(box,new LinearLayout.LayoutParams(-1,dp(52)));margins(box,0,4,0,0);box.setOnClickListener(v->go(target));
    }

    private void renderLandscape(){
        content.removeAllViews();
        if(screen==MAP){landscapeMap();return;}
        if(screen==HOME){landscapeHome();return;}
        if(screen==MUSIC){landscapeMusic();return;}
        landscapeUtility(screen);
    }

    private void landscapeHome(){
        locate();
        LinearLayout board=new LinearLayout(this);board.setOrientation(LinearLayout.HORIZONTAL);board.setPadding(dp(14),dp(12),dp(14),dp(12));content.addView(board,new FrameLayout.LayoutParams(-1,-1));

        LinearLayout navCard=autoPanel();board.addView(navCard,new LinearLayout.LayoutParams(0,-1,1.24f));
        LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);head.addView(t("NAVEGAÇÃO",10,ACCENT,true),new LinearLayout.LayoutParams(0,dp(28),1));head.addView(pill(lastLocation==null?"GPS…":"GPS",lastLocation==null?MUTED:GREEN),new LinearLayout.LayoutParams(-2,dp(28)));navCard.addView(head);
        TextView hero=t(currentRouteCoords.length()>1?"Viagem em andamento":"Para onde vamos?",30,TEXT,true);navCard.addView(hero);margins(hero,0,4,0,2);
        TextView routeState=t(currentRouteCoords.length()>1?(currentDestination.isEmpty()?"Destino ativo":currentDestination):"Mapa, radares e alertas no mesmo painel.",12,MUTED,false);routeState.setMaxLines(2);navCard.addView(routeState);margins(routeState,0,0,0,12);

        EditText dest=edit("Cidade, rua ou endereço");dest.setText(pendingDestination);navCard.addView(dest,new LinearLayout.LayoutParams(-1,dp(54)));
        Button start=btn(currentRouteCoords.length()>1?"ABRIR NAVEGAÇÃO":"INICIAR NAVEGAÇÃO",true);start.setTextSize(14);navCard.addView(start,new LinearLayout.LayoutParams(-1,dp(56)));margins(start,0,9,0,0);
        start.setOnClickListener(v->{String d=dest.getText().toString().trim();if(currentRouteCoords.length()>1&&d.isEmpty()){go(MAP);return;}if(d.length()<2){toast("Digite um destino.");return;}pendingDestination=d;go(MAP);});

        Space navSpace=new Space(this);navCard.addView(navSpace,new LinearLayout.LayoutParams(1,0,1));
        LinearLayout metrics=new LinearLayout(this);metrics.setGravity(Gravity.CENTER_VERTICAL);navCard.addView(metrics,new LinearLayout.LayoutParams(-1,dp(92)));
        autoMetric(metrics,lastLocation==null?"0":String.valueOf(Math.max(0,Math.round(lastLocation.getSpeed()*3.6f))),"KM/H",TEXT);
        autoMetric(metrics,currentRouteDistanceMeters>0?km(currentRouteDistanceMeters):"--","VIAGEM",currentRouteDistanceMeters>0?GREEN:TEXT);
        autoMetric(metrics,nearestHazardText(),"ALERTA",nearestHazardText().equals("--")?TEXT:ACCENT);

        LinearLayout right=new LinearLayout(this);right.setOrientation(LinearLayout.VERTICAL);right.setPadding(dp(12),0,0,0);board.addView(right,new LinearLayout.LayoutParams(0,-1,.76f));
        LinearLayout media=autoPanel();right.addView(media,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout mh=new LinearLayout(this);mh.setGravity(Gravity.CENTER_VERTICAL);mh.addView(t("MÍDIA",10,Color.rgb(193,125,255),true),new LinearLayout.LayoutParams(0,dp(26),1));TextView mIcon=t("♫",22,Color.rgb(210,157,255),true);mIcon.setGravity(Gravity.CENTER);media.addView(mh);media.addView(t("Tocando agora",22,TEXT,true));TextView msub=t("Música local, Drive e rádio sem tirar o foco da estrada.",11,MUTED,false);media.addView(msub);margins(msub,0,4,0,8);View now=nowCard();media.addView(now,new LinearLayout.LayoutParams(-1,dp(82)));media.setOnClickListener(v->go(MUSIC));

        LinearLayout tiles=new LinearLayout(this);right.addView(tiles,new LinearLayout.LayoutParams(-1,dp(96)));margins(tiles,0,10,0,0);
        View radio=autoTile("FM","RÁDIO","Estações",RADIO);View off=autoTile("⇩","OFFLINE","Mapas",OFFLINE);tiles.addView(radio,new LinearLayout.LayoutParams(0,-1,1));tiles.addView(off,new LinearLayout.LayoutParams(0,-1,1));margins(off,8,0,0,0);
        LinearLayout sys=autoPanel();sys.setOrientation(LinearLayout.HORIZONTAL);sys.setGravity(Gravity.CENTER_VERTICAL);sys.addView(t("⚙  Sistema e alertas",13,TEXT,true),new LinearLayout.LayoutParams(0,-1,1));sys.addView(pill(online()?"ONLINE":"OFFLINE",online()?GREEN:ACCENT),new LinearLayout.LayoutParams(-2,dp(30)));right.addView(sys,new LinearLayout.LayoutParams(-1,dp(64)));margins(sys,0,10,0,0);sys.setOnClickListener(v->go(SETTINGS));
    }

    private TextView driveMetric(LinearLayout row,String value,String unit){
        LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setGravity(Gravity.CENTER);TextView v=t(value,22,TEXT,true);v.setGravity(Gravity.CENTER);TextView u=t(unit,9,MUTED,true);u.setGravity(Gravity.CENTER);c.addView(v);c.addView(u);row.addView(c,new LinearLayout.LayoutParams(0,-1,1));return v;
    }

    private void landscapeMap(){
        locate();
        FrameLayout mapStage=new FrameLayout(this);content.addView(mapStage,new FrameLayout.LayoutParams(-1,-1));
        mapView=new NativeMapView(this);mapStage.addView(mapView,new FrameLayout.LayoutParams(-1,-1));
        loadMap();loadRoute();
        if(lastLocation!=null){mapView.setUserBearing(lastLocation.hasBearing()?lastLocation.getBearing():0f);mapView.setUserLocation(lastLocation.getLatitude(),lastLocation.getLongitude());}
        if(currentRouteCoords.length()>1)mapView.setDrivingMode(true);

        int stageW=Math.max(dp(720),getResources().getDisplayMetrics().widthPixels-dp(74));
        int searchW=Math.min(dp(560),Math.max(dp(390),(int)(stageW*.48f)));
        LinearLayout search=new LinearLayout(this);search.setGravity(Gravity.CENTER_VERTICAL);search.setPadding(dp(9),dp(7),dp(9),dp(7));search.setBackground(bg(Color.argb(236,6,13,21),18,Color.rgb(51,67,80)));
        EditText dest=edit("Destino · cidade, rua ou endereço");dest.setText(pendingDestination);search.addView(dest,new LinearLayout.LayoutParams(0,dp(48),1));
        Button route=btn(currentRouteCoords.length()>1?"↻":"IR",true);route.setTextSize(14);search.addView(route,new LinearLayout.LayoutParams(dp(64),dp(48)));margins(route,8,0,0,0);
        Button stop=btn("×",false);stop.setTextSize(22);stop.setVisibility(currentRouteCoords.length()>1?View.VISIBLE:View.GONE);search.addView(stop,new LinearLayout.LayoutParams(dp(52),dp(48)));margins(stop,7,0,0,0);activeRouteButton=route;
        FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(searchW,dp(62));sp.gravity=Gravity.TOP|Gravity.START;sp.setMargins(dp(14),dp(12),0,0);mapStage.addView(search,sp);

        TextView mode=pill(currentRouteCoords.length()>1?"ROTA ATIVA":"MAPBOX",currentRouteCoords.length()>1?GREEN:PURPLE);FrameLayout.LayoutParams mp=new FrameLayout.LayoutParams(-2,dp(32));mp.gravity=Gravity.TOP|Gravity.END;mp.setMargins(0,dp(14),dp(14),0);mapStage.addView(mode,mp);

        LinearLayout speedCluster=new LinearLayout(this);speedCluster.setGravity(Gravity.BOTTOM);hudSpeed=autoSpeedGauge(speedCluster,lastLocation==null?"0":String.valueOf(Math.max(0,Math.round(lastLocation.getSpeed()*3.6f))));hudLimit=autoLimitGauge(speedCluster,"--");
        FrameLayout.LayoutParams sgp=new FrameLayout.LayoutParams(dp(206),dp(122));sgp.gravity=Gravity.BOTTOM|Gravity.START;sgp.setMargins(dp(14),0,0,dp(14));mapStage.addView(speedCluster,sgp);

        LinearLayout hazard=autoPanel();hazard.setOrientation(LinearLayout.HORIZONTAL);hazard.setGravity(Gravity.CENTER_VERTICAL);hazard.setPadding(dp(14),dp(8),dp(14),dp(8));TextView hi=t("⚠",28,ACCENT,true);hi.setGravity(Gravity.CENTER);hazard.addView(hi,new LinearLayout.LayoutParams(dp(48),-1));LinearLayout hz=new LinearLayout(this);hz.setOrientation(LinearLayout.VERTICAL);hz.addView(t("PRÓXIMO ALERTA",9,MUTED,true));hudRadar=t(nearestHazardText(),18,TEXT,true);hudRadar.setMaxLines(1);hz.addView(hudRadar);hazard.addView(hz,new LinearLayout.LayoutParams(0,-1,1));FrameLayout.LayoutParams hp=new FrameLayout.LayoutParams(dp(310),dp(72));hp.gravity=Gravity.BOTTOM|Gravity.START;hp.setMargins(dp(224),0,0,dp(18));mapStage.addView(hazard,hp);

        LinearLayout maneuver=autoPanel();maneuver.setOrientation(LinearLayout.HORIZONTAL);maneuver.setGravity(Gravity.CENTER_VERTICAL);maneuver.setPadding(dp(14),dp(7),dp(14),dp(7));TextView arrow=t("➤",24,Color.WHITE,true);arrow.setGravity(Gravity.CENTER);arrow.setBackground(bg(Color.rgb(34,28,21),14,ACCENT));maneuver.addView(arrow,new LinearLayout.LayoutParams(dp(50),dp(50)));LinearLayout md=new LinearLayout(this);md.setOrientation(LinearLayout.VERTICAL);md.setPadding(dp(12),0,0,0);turnInstruction=t(currentRouteCoords.length()>1?"Siga a rota destacada":"Radarbot-style: mapa e alertas em primeiro plano",16,TEXT,true);turnInstruction.setMaxLines(1);md.addView(turnInstruction);landscapeTripInfo=t(currentRouteDistanceMeters>0?km(currentRouteDistanceMeters)+(currentRouteDurationSeconds>0?" · "+duration(currentRouteDurationSeconds):""):"GPS pronto",10,MUTED,true);md.addView(landscapeTripInfo);maneuver.addView(md,new LinearLayout.LayoutParams(0,-1,1));FrameLayout.LayoutParams manp=new FrameLayout.LayoutParams(dp(390),dp(68));manp.gravity=Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL;manp.setMargins(0,0,0,dp(18));mapStage.addView(maneuver,manp);

        LinearLayout tools=new LinearLayout(this);tools.setOrientation(LinearLayout.VERTICAL);Button follow=autoIconButton("➤"),report=autoIconButton("⚠");tools.addView(follow,new LinearLayout.LayoutParams(dp(56),dp(56)));tools.addView(report,new LinearLayout.LayoutParams(dp(56),dp(56)));margins(report,0,9,0,0);FrameLayout.LayoutParams tp=new FrameLayout.LayoutParams(dp(58),-2);tp.gravity=Gravity.END|Gravity.CENTER_VERTICAL;tp.setMargins(0,0,dp(14),0);mapStage.addView(tools,tp);
        follow.setOnClickListener(v->{if(lastLocation!=null){mapView.setDrivingMode(true);mapView.recenter(lastLocation.getLatitude(),lastLocation.getLongitude());}});report.setOnClickListener(v->reportPointDialog());

        TextView summary=small(currentRouteCoords.length()>1?"Viagem ativa":"GPS pronto");summary.setVisibility(View.GONE);mapStage.addView(summary,new FrameLayout.LayoutParams(1,1));
        route.setOnClickListener(v->{String d=dest.getText().toString().trim();if(d.length()<2){toast("Informe o destino.");return;}if(lastLocation==null){toast("Aguardando GPS.");locate();return;}pendingDestination=d;mapView.setDrivingMode(false);calc(d,route,summary,mapStage);});
        stop.setOnClickListener(v->{try{startService(NavigationService.stopIntent(this));}catch(Exception ignored){}currentRouteCoords=new JSONArray();currentHazards=new JSONArray();currentDestination="";currentRouteDistanceMeters=0;currentRouteDurationSeconds=0;if(mapView!=null){mapView.setDrivingMode(false);mapView.clearRoute();mapView.setRadars(currentHazards);}turnInstruction.setText("Escolha um destino");if(landscapeTripInfo!=null)landscapeTripInfo.setText("GPS pronto");stop.setVisibility(View.GONE);route.setText("IR");toast("Navegação encerrada.");});
        if(!pendingDestination.isEmpty()&&currentRouteCoords.length()<2&&lastLocation!=null&&online())ui.postDelayed(()->calc(pendingDestination,route,summary,mapStage),400);
    }

    private void landscapeMusic(){
        content.removeAllViews();LinearLayout shell=new LinearLayout(this);shell.setOrientation(LinearLayout.HORIZONTAL);shell.setPadding(dp(14),dp(12),dp(14),dp(12));content.addView(shell,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout player=autoPanel();player.setPadding(dp(20),dp(16),dp(20),dp(16));shell.addView(player,new LinearLayout.LayoutParams(dp(330),-1));
        LinearLayout mh=new LinearLayout(this);mh.setGravity(Gravity.CENTER_VERTICAL);mh.addView(t("MÍDIA",10,Color.rgb(202,139,255),true),new LinearLayout.LayoutParams(0,dp(28),1));mh.addView(pill("PLAYER",PURPLE),new LinearLayout.LayoutParams(-2,dp(28)));player.addView(mh);
        TextView art=t("♫",66,Color.rgb(218,170,255),true);art.setGravity(Gravity.CENTER);art.setBackground(bg(Color.rgb(20,24,38),22,Color.rgb(70,50,101)));player.addView(art,new LinearLayout.LayoutParams(-1,0,1));margins(art,0,10,0,10);
        playerNow=t("Nenhuma música",18,TEXT,true);playerNow.setMaxLines(2);player.addView(playerNow);TextView src=t("MusicRoad · biblioteca local e online",10,MUTED,false);player.addView(src);margins(src,0,2,0,10);
        LinearLayout controls=new LinearLayout(this);controls.setGravity(Gravity.CENTER);Button prev=autoIconButton("⏮"),play=autoIconButton("▶"),next=autoIconButton("⏭");play.setBackground(bg(Color.rgb(52,27,72),18,PURPLE));controls.addView(prev,new LinearLayout.LayoutParams(0,dp(58),1));controls.addView(play,new LinearLayout.LayoutParams(0,dp(58),1));controls.addView(next,new LinearLayout.LayoutParams(0,dp(58),1));margins(play,8,0,8,0);player.addView(controls,new LinearLayout.LayoutParams(-1,dp(58)));prev.setOnClickListener(v->startService(PlaybackService.intentAction(this,PlaybackService.ACTION_PREVIOUS)));play.setOnClickListener(v->startService(PlaybackService.intentAction(this,PlaybackService.ACTION_TOGGLE)));next.setOnClickListener(v->startService(PlaybackService.intentAction(this,PlaybackService.ACTION_NEXT)));
        LinearLayout shortcuts=new LinearLayout(this);Button radio=btn("FM RÁDIO",false),off=btn("OFFLINE",false);shortcuts.addView(radio,new LinearLayout.LayoutParams(0,dp(46),1));shortcuts.addView(off,new LinearLayout.LayoutParams(0,dp(46),1));margins(off,7,0,0,0);player.addView(shortcuts);margins(shortcuts,0,9,0,0);radio.setOnClickListener(v->go(RADIO));off.setOnClickListener(v->go(OFFLINE));
        FrameLayout library=new FrameLayout(this);shell.addView(library,new LinearLayout.LayoutParams(0,-1,1));margins(library,12,0,0,0);FrameLayout previous=content;content=library;landscapePaneRender=true;music();landscapePaneRender=false;content=previous;
    }

    private void landscapeUtility(int target){
        content.removeAllViews();LinearLayout shell=new LinearLayout(this);shell.setOrientation(LinearLayout.HORIZONTAL);shell.setPadding(dp(14),dp(12),dp(14),dp(12));content.addView(shell,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout side=autoPanel();side.setPadding(dp(18),dp(16),dp(18),dp(16));shell.addView(side,new LinearLayout.LayoutParams(dp(224),-1));side.addView(t("DRIVE OS",9,ACCENT,true));side.addView(t(name(target),26,TEXT,true));TextView desc=t(target==OFFLINE?"Prepare mapas e alertas para rodar sem sinal.":target==RADIO?"Rádio e áudio pensados para a estrada.":"Voz, alertas, conta e comportamento do sistema.",11,MUTED,false);side.addView(desc);margins(desc,0,4,0,12);
        if(target==OFFLINE)side.addView(pill("DADOS NO APARELHO",GREEN),new LinearLayout.LayoutParams(-2,dp(30)));else if(target==RADIO)side.addView(pill("ENTRETENIMENTO",PURPLE),new LinearLayout.LayoutParams(-2,dp(30)));else side.addView(pill("SISTEMA",ACCENT),new LinearLayout.LayoutParams(-2,dp(30)));
        Space sp=new Space(this);side.addView(sp,new LinearLayout.LayoutParams(1,0,1));View now=nowCard();side.addView(now,new LinearLayout.LayoutParams(-1,dp(78)));
        FrameLayout pane=new FrameLayout(this);shell.addView(pane,new LinearLayout.LayoutParams(0,-1,1));margins(pane,12,0,0,0);FrameLayout previous=content;content=pane;landscapePaneRender=true;if(target==OFFLINE)offline();else if(target==RADIO)radio();else settings();landscapePaneRender=false;content=previous;
    }

    private void setAutomotiveImmersive(boolean enabled){
        try{
            View decor=getWindow().getDecorView();
            if(enabled){
                decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            }else{
                decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
            }
        }catch(Exception ignored){}
    }

    private View driveStatusBar(){
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(12),0,dp(14),0);bar.setBackgroundColor(Color.rgb(2,6,10));
        TextView brand=t("MR  MusicRoad",12,TEXT,true);bar.addView(brand,new LinearLayout.LayoutParams(-2,-1));
        TextView mode=t("  DRIVE OS",8,ACCENT,true);bar.addView(mode,new LinearLayout.LayoutParams(-2,-1));
        Space sp=new Space(this);bar.addView(sp,new LinearLayout.LayoutParams(0,1,1));
        TextView gps=t(lastLocation==null?"● GPS":"● GPS",9,lastLocation==null?MUTED:GREEN,true);bar.addView(gps,new LinearLayout.LayoutParams(-2,-1));
        TextView net=t(online()?"  ● ONLINE":"  ● OFFLINE",9,online()?GREEN:ACCENT,true);bar.addView(net,new LinearLayout.LayoutParams(-2,-1));
        TextView clock=t("  "+android.text.format.DateFormat.format("HH:mm",new java.util.Date()),13,TEXT,true);bar.addView(clock,new LinearLayout.LayoutParams(-2,-1));
        return bar;
    }

    private LinearLayout autoPanel(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(16),dp(14),dp(16),dp(14));l.setBackground(bg(Color.rgb(8,15,22),18,Color.rgb(35,48,59)));return l;}
    private void autoMetric(LinearLayout row,String value,String label,int color){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setGravity(Gravity.CENTER);TextView v=t(value,24,color,true);v.setGravity(Gravity.CENTER);v.setMaxLines(1);TextView l=t(label,8,MUTED,true);l.setGravity(Gravity.CENTER);c.addView(v);c.addView(l);row.addView(c,new LinearLayout.LayoutParams(0,-1,1));}
    private View autoTile(String icon,String title,String sub,int target){LinearLayout c=autoPanel();c.setGravity(Gravity.CENTER);TextView i=t(icon,22,target==RADIO?Color.rgb(202,139,255):ACCENT,true);i.setGravity(Gravity.CENTER);c.addView(i);TextView h=t(title,12,TEXT,true);h.setGravity(Gravity.CENTER);c.addView(h);TextView s=t(sub,8,MUTED,false);s.setGravity(Gravity.CENTER);c.addView(s);c.setOnClickListener(v->go(target));return c;}
    private Button autoIconButton(String icon){Button b=btn(icon,false);b.setTextSize(20);b.setTextColor(Color.WHITE);b.setBackground(bg(Color.argb(238,8,17,26),17,Color.rgb(52,68,80)));return b;}
    private TextView autoSpeedGauge(LinearLayout row,String value){LinearLayout g=new LinearLayout(this);g.setOrientation(LinearLayout.VERTICAL);g.setGravity(Gravity.CENTER);g.setBackground(bg(Color.argb(238,5,12,18),60,Color.rgb(71,88,100)));TextView v=t(value,38,TEXT,true);v.setGravity(Gravity.CENTER);TextView u=t("km/h",9,MUTED,true);u.setGravity(Gravity.CENTER);g.addView(v);g.addView(u);row.addView(g,new LinearLayout.LayoutParams(dp(118),dp(118)));return v;}
    private TextView autoLimitGauge(LinearLayout row,String value){LinearLayout g=new LinearLayout(this);g.setOrientation(LinearLayout.VERTICAL);g.setGravity(Gravity.CENTER);g.setBackground(bg(Color.rgb(245,245,245),40,Color.rgb(222,54,54)));TextView v=t(value,22,Color.rgb(24,24,24),true);v.setGravity(Gravity.CENTER);g.addView(v);row.addView(g,new LinearLayout.LayoutParams(dp(72),dp(72)));margins(g,10,0,0,7);return v;}

    private SpannableString logoSpan(){SpannableString x=new SpannableString("MusicRoad");x.setSpan(new StyleSpan(Typeface.BOLD_ITALIC),0,x.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);x.setSpan(new ForegroundColorSpan(ACCENT),5,x.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);return x;}
    private GradientDrawable grad(int[] colors,int radius,int stroke){GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,colors);d.setCornerRadius(dp(radius));if(stroke!=0)d.setStroke(dp(1),stroke);return d;}
    private TextView logo(float size){TextView v=t("",size,TEXT,true);v.setText(logoSpan());v.setGravity(Gravity.CENTER);return v;}
    private TextView pill(String value,int color){TextView p=t(value,11,color,true);p.setGravity(Gravity.CENTER);p.setPadding(dp(12),0,dp(12),0);p.setBackground(bg(Color.argb(38,Color.red(color),Color.green(color),Color.blue(color)),18,Color.argb(100,Color.red(color),Color.green(color),Color.blue(color))));return p;}
    private View top(){
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(20),dp(8),dp(20),dp(8));bar.setBackgroundColor(Color.rgb(5,13,22));
        Button menu=btn("☰",false);menu.setTextSize(25);menu.setBackground(bg(Color.rgb(12,24,38),18,LINE));bar.addView(menu,new LinearLayout.LayoutParams(dp(64),dp(62)));menu.setOnClickListener(v->menu());
        TextView brand=logo(27);bar.addView(brand,new LinearLayout.LayoutParams(0,-1,1));
        TextView dot=t("●",16,online()?GREEN:MUTED,true);dot.setGravity(Gravity.CENTER);bar.addView(dot,new LinearLayout.LayoutParams(dp(30),-1));
        Button fav=btn("♡",false);fav.setTextSize(30);fav.setBackground(bg(Color.rgb(12,24,38),18,LINE));bar.addView(fav,new LinearLayout.LayoutParams(dp(62),dp(62)));fav.setOnClickListener(v->{musicSource="all";go(MUSIC);});
        return bar;
    }
    private View rail(){
        LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.VERTICAL);r.setGravity(Gravity.CENTER_HORIZONTAL);r.setPadding(dp(9),dp(12),dp(9),dp(12));r.setBackgroundColor(Color.rgb(4,9,15));
        TextView m=logo(16);m.setBackground(grad(new int[]{Color.rgb(31,20,67),Color.rgb(15,27,43)},17,PURPLE2));r.addView(m,new LinearLayout.LayoutParams(-1,dp(58)));
        addRail(r,"⌂\nHOME",HOME);addRail(r,"➤\nNAV",MAP);addRail(r,"♫\nMEDIA",MUSIC);addRail(r,"FM\nRÁDIO",RADIO);addRail(r,"⇩\nOFF",OFFLINE);addRail(r,"⚙\nAJUSTES",SETTINGS);
        Space sp=new Space(this);r.addView(sp,new LinearLayout.LayoutParams(1,0,1));TextView v=small("v"+BuildConfig.VERSION_NAME);v.setGravity(Gravity.CENTER);r.addView(v,new LinearLayout.LayoutParams(-1,dp(30)));return r;
    }
    private void addRail(LinearLayout r,String s,int id){Button b=btn(s,false);b.setTextSize(10);b.setGravity(Gravity.CENTER);if(id==screen)b.setBackground(grad(new int[]{Color.rgb(111,44,214),Color.rgb(49,28,112)},16,PURPLE));r.addView(b,new LinearLayout.LayoutParams(-1,dp(62)));margins(b,0,8,0,0);b.setOnClickListener(v->go(id));}
    private void menu(){
        FrameLayout overlay=new FrameLayout(this);overlay.setBackgroundColor(Color.argb(155,0,0,0));root.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
        View backdrop=new View(this);overlay.addView(backdrop,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout drawer=new LinearLayout(this);drawer.setOrientation(LinearLayout.VERTICAL);drawer.setPadding(dp(28),dp(48),dp(26),dp(28));drawer.setBackground(grad(new int[]{Color.rgb(7,14,29),Color.rgb(6,11,22)},0,PURPLE2));FrameLayout.LayoutParams dpv=new FrameLayout.LayoutParams((int)(getResources().getDisplayMetrics().widthPixels*.82f),-1);dpv.gravity=Gravity.START;overlay.addView(drawer,dpv);
        TextView lg=logo(32);lg.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);drawer.addView(lg,new LinearLayout.LayoutParams(-1,dp(50)));JSONObject u=account.optJSONObject("user");drawer.addView(t(u==null?"Motorista":u.optString("name","Motorista"),14,MUTED,false));
        View line=new View(this);line.setBackgroundColor(LINE);drawer.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));margins(line,0,28,0,18);
        drawerItem(drawer,"⌂","Início","Seu painel de viagem",HOME,overlay);drawerItem(drawer,"♫","Música","Celular, Drive e downloads",MUSIC,overlay);drawerItem(drawer,"➤","Mapa","Navegação e alertas",MAP,overlay);drawerItem(drawer,"FM","Rádio","FM e estações online",RADIO,overlay);drawerItem(drawer,"⇩","Offline","Mapas e radares",OFFLINE,overlay);drawerItem(drawer,"⚙","Ajustes","Voz, alertas e conta",SETTINGS,overlay);
        Space sp=new Space(this);drawer.addView(sp,new LinearLayout.LayoutParams(1,0,1));LinearLayout foot=new LinearLayout(this);foot.setGravity(Gravity.CENTER_VERTICAL);foot.addView(t("●",15,online()?GREEN:MUTED,true));LinearLayout ft=new LinearLayout(this);ft.setOrientation(LinearLayout.VERTICAL);ft.setPadding(dp(10),0,0,0);ft.addView(t("MusicRoad "+BuildConfig.VERSION_NAME,13,TEXT,true));ft.addView(small("Android nativo"));foot.addView(ft);drawer.addView(foot,new LinearLayout.LayoutParams(-1,dp(54)));
        backdrop.setOnClickListener(v->root.removeView(overlay));
    }
    private void drawerItem(LinearLayout d,String icon,String title,String sub,int id,View overlay){LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(14),dp(9),dp(14),dp(9));row.setBackground(id==screen?grad(new int[]{Color.argb(120,93,38,190),Color.argb(45,97,61,210)},18,PURPLE):bg(Color.TRANSPARENT,18,0));TextView ic=t(icon,20,id==screen?Color.rgb(196,112,255):MUTED,true);ic.setGravity(Gravity.CENTER);ic.setBackground(bg(Color.rgb(14,24,43),14,0));row.addView(ic,new LinearLayout.LayoutParams(dp(54),dp(54)));LinearLayout cp=new LinearLayout(this);cp.setOrientation(LinearLayout.VERTICAL);cp.setPadding(dp(16),0,0,0);cp.addView(t(title,17,id==screen?TEXT:Color.rgb(210,216,228),true));cp.addView(small(sub));row.addView(cp,new LinearLayout.LayoutParams(0,-1,1));d.addView(row,new LinearLayout.LayoutParams(-1,dp(74)));margins(row,0,4,0,0);row.setOnClickListener(v->{root.removeView(overlay);go(id);});}
    private void go(int s){screen=s;showShell();}
    private String name(int s){return s==MAP?"NAVEGAÇÃO":s==MUSIC?"MÚSICA":s==OFFLINE?"OFFLINE":s==RADIO?"RÁDIO":s==SETTINGS?"AJUSTES":"INÍCIO";}
    private void render(){content.removeAllViews();if(screen==MAP)map();else if(screen==MUSIC)music();else if(screen==OFFLINE)offline();else if(screen==RADIO)radio();else if(screen==SETTINGS)settings();else home();}

    private void home(){
        locate();ScrollView sv=new ScrollView(this);sv.setFillViewport(true);content.addView(sv,new FrameLayout.LayoutParams(-1,-1));LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(landscape()?30:24),dp(22),dp(landscape()?30:24),dp(34));sv.addView(body,new ScrollView.LayoutParams(-1,-2));
        LinearLayout hello=new LinearLayout(this);hello.setGravity(Gravity.CENTER_VERTICAL);LinearLayout hc=new LinearLayout(this);hc.setOrientation(LinearLayout.VERTICAL);hc.addView(t("PAINEL DE BORDO",11,MUTED,false));JSONObject u=account.optJSONObject("user");String n=u==null?"Motorista":u.optString("name","Motorista");hc.addView(t("Olá, "+first(n),34,TEXT,true));hello.addView(hc,new LinearLayout.LayoutParams(0,-2,1));TextView gps=pill(lastLocation==null?"GPS aguardando":"GPS ±"+Math.max(3,Math.round(lastLocation.getAccuracy()))+"m",lastLocation==null?MUTED:GREEN);hello.addView(gps,new LinearLayout.LayoutParams(-2,dp(48)));body.addView(hello);
        FrameLayout hero=new FrameLayout(this);hero.setBackground(grad(new int[]{Color.rgb(31,46,112),Color.rgb(83,37,91),Color.rgb(7,18,29)},28,Color.rgb(120,67,58)));body.addView(hero,new LinearLayout.LayoutParams(-1,landscape()?dp(365):dp(500)));margins(hero,0,20,0,0);LinearLayout in=new LinearLayout(this);in.setOrientation(LinearLayout.VERTICAL);in.setPadding(dp(28),dp(26),dp(28),dp(24));hero.addView(in,new FrameLayout.LayoutParams(-1,-1));TextView kicker=pill("NAVEGAÇÃO MUSICROAD",Color.rgb(255,175,120));kicker.setGravity(Gravity.CENTER);in.addView(kicker,new LinearLayout.LayoutParams(-2,dp(42)));TextView q=t("Para onde você vai?",landscape()?38:34,TEXT,false);in.addView(q);margins(q,0,20,0,16);in.addView(t("DESTINO",10,MUTED,true));EditText dest=edit("Cidade, endereço ou coordenada");dest.setText(pendingDestination);in.addView(dest,new LinearLayout.LayoutParams(-1,dp(62)));margins(dest,0,7,0,13);in.addView(t("ORIGEM",10,MUTED,true));LinearLayout orow=new LinearLayout(this);EditText origin=edit("Minha localização");origin.setEnabled(false);orow.addView(origin,new LinearLayout.LayoutParams(0,dp(58),1));Button loc=btn("◎",false);loc.setTextSize(24);orow.addView(loc,new LinearLayout.LayoutParams(dp(72),dp(58)));margins(loc,10,0,0,0);in.addView(orow);Button start=btn("INICIAR VIAGEM",true);start.setTextSize(15);start.setTypeface(Typeface.DEFAULT,Typeface.BOLD_ITALIC);in.addView(start,new LinearLayout.LayoutParams(-1,dp(70)));margins(start,0,17,0,0);start.setOnClickListener(v->{String d=dest.getText().toString().trim();if(d.length()<2){toast("Digite um destino.");return;}pendingDestination=d;go(MAP);});loc.setOnClickListener(v->{locate();toast(lastLocation==null?"Buscando GPS…":"Localização atualizada.");});
        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);body.addView(actions);margins(actions,0,18,0,0);quick(actions,"♫","Música","Ouvir agora",MUSIC);quick(actions,"⌖","Mapa","Navegação",MAP);quick(actions,"FM","Rádio","Ouvir rádio",RADIO);
        if(currentRouteCoords.length()>1){LinearLayout stats=new LinearLayout(this);stats.setOrientation(LinearLayout.HORIZONTAL);body.addView(stats);margins(stats,0,18,0,0);stat(stats,"VELOCIDADE",lastLocation==null?"0":String.valueOf(Math.max(0,Math.round(lastLocation.getSpeed()*3.6f))),"km/h");stat(stats,"LIMITE","--","km/h");stat(stats,"ALERTA",nearestHazardText(),"à frente");}
        body.addView(nowCard());margins(body.getChildAt(body.getChildCount()-1),0,22,0,0);
    }
    private void quick(LinearLayout q,String icon,String h,String sub,int id){LinearLayout c=card();c.setGravity(Gravity.CENTER);TextView ic=t(icon,22,ACCENT,true);ic.setGravity(Gravity.CENTER);ic.setBackground(bg(Color.rgb(11,31,49),13,0));c.addView(ic,new LinearLayout.LayoutParams(dp(48),dp(48)));TextView ti=t(h,15,TEXT,true);ti.setGravity(Gravity.CENTER);c.addView(ti);TextView st=small(sub);st.setGravity(Gravity.CENTER);c.addView(st);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(120),1);p.setMargins(0,0,dp(10),0);q.addView(c,p);c.setOnClickListener(v->go(id));}
    private void stat(LinearLayout row,String label,String value,String sub){LinearLayout c=card();c.setGravity(Gravity.CENTER);TextView a=small(label);a.setGravity(Gravity.CENTER);c.addView(a);TextView b=t(value,23,TEXT,true);b.setGravity(Gravity.CENTER);c.addView(b);TextView d=small(sub);d.setGravity(Gravity.CENTER);c.addView(d);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(100),1);p.setMargins(0,0,dp(8),0);row.addView(c,p);}
    private View nowCard(){LinearLayout c=new LinearLayout(this);c.setGravity(Gravity.CENTER_VERTICAL);c.setPadding(dp(14),dp(10),dp(14),dp(10));c.setBackground(grad(new int[]{Color.rgb(8,18,35),Color.rgb(16,15,45)},22,Color.rgb(44,48,87)));TextView art=t("♫",25,ACCENT,true);art.setGravity(Gravity.CENTER);art.setBackground(grad(new int[]{Color.rgb(19,51,75),Color.rgb(37,21,83)},16,0));c.addView(art,new LinearLayout.LayoutParams(dp(64),dp(64)));LinearLayout meta=new LinearLayout(this);meta.setOrientation(LinearLayout.VERTICAL);meta.setPadding(dp(13),0,0,0);playerNow=t("Nenhuma música",15,TEXT,true);meta.addView(playerNow);meta.addView(small("MusicRoad"));c.addView(meta,new LinearLayout.LayoutParams(0,-1,1));Button play=btn("▶",false);play.setTextSize(24);play.setTextColor(Color.WHITE);play.setBackground(grad(new int[]{PURPLE,Color.rgb(181,48,255)},28,0));c.addView(play,new LinearLayout.LayoutParams(dp(62),dp(62)));play.setOnClickListener(v->startService(PlaybackService.intentAction(this,PlaybackService.ACTION_TOGGLE)));return c;}

    private void map(){
        locate();FrameLayout stage=new FrameLayout(this);content.addView(stage,new FrameLayout.LayoutParams(-1,-1));mapView=new NativeMapView(this);FrameLayout.LayoutParams mapLp=new FrameLayout.LayoutParams(-1,-1);mapLp.topMargin=dp(70);mapLp.bottomMargin=dp(118);stage.addView(mapView,mapLp);loadMap();loadRoute();if(lastLocation!=null)mapView.setUserLocation(lastLocation.getLatitude(),lastLocation.getLongitude());
        LinearLayout toolbar=new LinearLayout(this);toolbar.setGravity(Gravity.CENTER_VERTICAL);toolbar.setPadding(dp(14),dp(8),dp(14),dp(8));toolbar.setBackgroundColor(Color.argb(232,5,13,22));Button back=btn("←",false);back.setTextSize(22);toolbar.addView(back,new LinearLayout.LayoutParams(dp(54),dp(50)));LinearLayout tc=new LinearLayout(this);tc.setOrientation(LinearLayout.VERTICAL);tc.setPadding(dp(12),0,0,0);tc.addView(t("Navegação",17,TEXT,true));tc.addView(small(currentDestination.isEmpty()?"Nenhuma viagem ativa":currentDestination));toolbar.addView(tc,new LinearLayout.LayoutParams(0,-1,1));TextView mode=pill(online()?"MAPBOX":"OFFLINE",online()?GREEN:ACCENT);toolbar.addView(mode,new LinearLayout.LayoutParams(-2,dp(38)));FrameLayout.LayoutParams tbp=new FrameLayout.LayoutParams(-1,dp(70));tbp.gravity=Gravity.TOP;stage.addView(toolbar,tbp);back.setOnClickListener(v->go(HOME));
        LinearLayout turn=card();turn.setOrientation(LinearLayout.HORIZONTAL);turn.setGravity(Gravity.CENTER_VERTICAL);TextView arrow=t("➜",30,PURPLE,true);arrow.setGravity(Gravity.CENTER);turn.addView(arrow,new LinearLayout.LayoutParams(dp(62),dp(62)));LinearLayout tx=new LinearLayout(this);tx.setOrientation(LinearLayout.VERTICAL);turnInstruction=t(currentRouteCoords.length()>1?"Siga o trajeto destacado":"Escolha um destino",18,TEXT,true);tx.addView(turnInstruction);tx.addView(small(currentDestination.isEmpty()?"MusicRoad Navigation":currentDestination));turn.addView(tx,new LinearLayout.LayoutParams(0,-1,1));FrameLayout.LayoutParams tp=new FrameLayout.LayoutParams(landscape()?dp(480):-1,-2);tp.gravity=Gravity.TOP|Gravity.START;tp.setMargins(dp(18),dp(82),landscape()?0:dp(18),0);stage.addView(turn,tp);
        LinearLayout hud=new LinearLayout(this);hud.setOrientation(LinearLayout.HORIZONTAL);hudSpeed=hudBox(hud,"0","km/h",false);hudLimit=hudBox(hud,"--","LIMITE",true);hudRadar=hudBox(hud,nearestHazardText(),"PRÓXIMA FISCALIZAÇÃO",true);FrameLayout.LayoutParams hp=new FrameLayout.LayoutParams(landscape()?dp(520):-1,dp(104));hp.gravity=Gravity.BOTTOM|Gravity.START;hp.setMargins(dp(18),0,landscape()?0:dp(18),dp(landscape()?18:126));stage.addView(hud,hp);
        LinearLayout tools=new LinearLayout(this);tools.setOrientation(LinearLayout.VERTICAL);Button report=btn("⚠",false),follow=btn("➤",false);report.setTextSize(24);follow.setTextSize(24);tools.addView(report,new LinearLayout.LayoutParams(dp(58),dp(58)));margins(report,0,0,0,10);tools.addView(follow,new LinearLayout.LayoutParams(dp(58),dp(58)));FrameLayout.LayoutParams toolp=new FrameLayout.LayoutParams(dp(62),-2);toolp.gravity=Gravity.END|Gravity.CENTER_VERTICAL;toolp.setMargins(0,0,dp(18),0);stage.addView(tools,toolp);report.setOnClickListener(v->reportPointDialog());follow.setOnClickListener(v->{if(lastLocation!=null)mapView.recenter(lastLocation.getLatitude(),lastLocation.getLongitude());});
        LinearLayout panel=card();panel.setOrientation(LinearLayout.HORIZONTAL);panel.setGravity(Gravity.CENTER_VERTICAL);EditText dest=edit("Cidade, rua ou endereço");dest.setText(pendingDestination);panel.addView(dest,new LinearLayout.LayoutParams(0,dp(58),1));Button route=btn(currentRouteCoords.length()>1?"↻":"IR",true);panel.addView(route,new LinearLayout.LayoutParams(dp(88),dp(58)));margins(route,7,0,0,0);Button stop=btn("■",false);panel.addView(stop,new LinearLayout.LayoutParams(dp(54),dp(58)));margins(stop,6,0,0,0);TextView summary=small(currentRouteCoords.length()>1?"Viagem ativa":"GPS pronto");summary.setVisibility(View.GONE);FrameLayout.LayoutParams pp=new FrameLayout.LayoutParams(-1,dp(94));pp.gravity=Gravity.BOTTOM;pp.setMargins(dp(18),0,dp(18),dp(18));stage.addView(panel,pp);
        route.setOnClickListener(v->{String d=dest.getText().toString().trim();if(d.length()<2){toast("Informe o destino.");return;}if(lastLocation==null){toast("Aguardando GPS.");locate();return;}pendingDestination=d;calc(d,route,summary,panel);});stop.setOnClickListener(v->{try{startService(NavigationService.stopIntent(this));}catch(Exception ignored){}currentRouteCoords=new JSONArray();currentDestination="";mapView.clearRoute();turnInstruction.setText("Escolha um destino");toast("Navegação encerrada.");});
        if(!pendingDestination.isEmpty()&&currentRouteCoords.length()<2&&lastLocation!=null&&online())ui.postDelayed(()->calc(pendingDestination,route,summary,panel),500);
    }
    private TextView hudBox(LinearLayout row,String value,String label,boolean compact){LinearLayout c=card();c.setGravity(Gravity.CENTER);TextView v=t(value,compact?20:28,TEXT,true);v.setGravity(Gravity.CENTER);c.addView(v);TextView l=t(label,9,MUTED,true);l.setGravity(Gravity.CENTER);c.addView(l);row.addView(c,new LinearLayout.LayoutParams(0,-1,1));margins(c,0,0,8,0);return v;}
    private void calc(String destination,Button b,TextView summary,View panel){
        if(lastLocation==null){toast("Aguardando localização GPS.");locate();return;}
        if(!online()){toast("A rota Mapbox precisa de internet nesta versão. Alertas baixados continuam disponíveis offline.");return;}
        activeRouteButton=b;b.setEnabled(false);b.setText("…");
        if(mapboxRoute==null){b.setEnabled(true);b.setText("IR");toast("Motor de rota Mapbox não inicializado.");return;}
        mapboxRoute.requestRoute(lastLocation.getLatitude(),lastLocation.getLongitude(),destination,destination,false);
    }
    private String routeInstruction(JSONObject route){try{JSONArray legs=route.optJSONArray("legs");JSONObject leg=legs==null?null:legs.optJSONObject(0);JSONArray steps=leg==null?null:leg.optJSONArray("steps");JSONObject step=steps==null?null:steps.optJSONObject(0);JSONObject m=step==null?null:step.optJSONObject("maneuver");String type=m==null?"":m.optString("type","");String mod=m==null?"":m.optString("modifier","");String road=step==null?"":step.optString("name","");String act=mod.contains("left")?"Vire à esquerda":mod.contains("right")?"Vire à direita":type.equals("depart")?"Siga em frente":"Siga o trajeto";return road.isEmpty()?act:act+" na "+road;}catch(Exception e){return"Siga o trajeto destacado";}}
    private void loadMap(){String uf=prefs.getString(KEY_UF,"");if(uf==null||uf.isEmpty())return;try{String raw=offline.get("state/"+uf+"/map");if(!raw.isEmpty()){mapView.setOfflinePack(new JSONObject(raw));mapView.setMessage("Mapa offline "+uf);}}catch(Exception ignored){}}
    private void loadRoute(){try{String raw=offline.route();if(raw.isEmpty())return;JSONObject j=new JSONObject(raw),r=j.optJSONObject("route"),g=r==null?null:r.optJSONObject("geometry");JSONArray c=g==null?null:g.optJSONArray("coordinates");if(c!=null){currentRouteCoords=c;currentDestination=j.optString("destination","");currentDestinationLat=j.optDouble("destination_lat",Double.NaN);currentDestinationLon=j.optDouble("destination_lon",Double.NaN);mapView.setRoute(c);JSONArray savedHazards=j.optJSONArray("radars");currentHazards=savedHazards==null?hazards():savedHazards;mapView.setRadars(currentHazards);}}catch(Exception ignored){}}
    private JSONArray hazards(){String uf=prefs.getString(KEY_UF,"");if(uf==null)return new JSONArray();for(String kind:new String[]{"radars","map"})try{String raw=offline.get("state/"+uf+"/"+kind);if(!raw.isEmpty()){JSONArray a=new JSONObject(raw).optJSONArray("radars");if(a!=null)return a;}}catch(Exception ignored){}return new JSONArray();}
    private String nearestHazardText(){if(lastLocation==null||currentHazards==null||currentHazards.length()==0)return"--";double best=Double.MAX_VALUE;for(int i=0;i<currentHazards.length();i++){JSONObject h=currentHazards.optJSONObject(i);if(h==null)continue;double lat=h.optDouble("latitude",Double.NaN),lon=h.optDouble("longitude",Double.NaN);if(!Double.isFinite(lat)||!Double.isFinite(lon))continue;best=Math.min(best,haversine(lastLocation.getLatitude(),lastLocation.getLongitude(),lat,lon));}if(!Double.isFinite(best)||best==Double.MAX_VALUE)return"--";return best<1000?Math.round(best)+"m":String.format(Locale.US,"%.1fkm",best/1000d);}
    private double haversine(double a,double b,double c,double d){double r=6371000,la=Math.toRadians(c-a),lo=Math.toRadians(d-b),x=Math.sin(la/2)*Math.sin(la/2)+Math.cos(Math.toRadians(a))*Math.cos(Math.toRadians(c))*Math.sin(lo/2)*Math.sin(lo/2);return 2*r*Math.atan2(Math.sqrt(x),Math.sqrt(1-x));}

    private void reportPointDialog(){if(lastLocation==null){toast("Aguarde o GPS localizar o veículo.");locate();return;}String[] labels={"Radar de velocidade","Fiscalização portátil","Vídeo monitoramento","Fiscalização semafórica","Quebra-mola"};String[] types={"RADAR_REPORTADO","FISCALIZACAO_PORTATIL","VIDEO_MONITORAMENTO","FISCALIZACAO_SEMAFORICA","QUEBRA_MOLA"};new AlertDialog.Builder(this).setTitle("Registrar fiscalização").setItems(labels,(d,w)->{if(w<=1)choosePointSpeed(types[w],labels[w]);else savePoint(types[w],labels[w],null);}).setNegativeButton("Cancelar",null).show();}
    private void choosePointSpeed(String type,String label){String[] s={"30","40","50","60","70","80","90","100","110","Não sei"};new AlertDialog.Builder(this).setTitle(label+" · velocidade").setItems(s,(d,w)->savePoint(type,label,w==9?null:Integer.parseInt(s[w]))).setNegativeButton("Voltar",null).show();}
    private void savePoint(String type,String label,Integer speed){if(!online()){toast("Conecte-se para registrar este ponto.");return;}JSONObject d=new JSONObject();try{d.put("external_id","community-"+type.toLowerCase(Locale.ROOT)+"-"+System.currentTimeMillis());d.put("latitude",lastLocation.getLatitude());d.put("longitude",lastLocation.getLongitude());if(speed!=null)d.put("velocidade",speed);d.put("tipo",type);d.put("situacao","ATIVO");d.put("fonte","MUSICROAD_COMUNIDADE");d.put("confiabilidade","BAIXA");d.put("quantidade_fontes",1);d.put("ativo",1);}catch(Exception ignored){}io.execute(()->{try{NativeApiClient.Response r=api.postCsrf(server(),"api/radars.php?action=save",d,account.optString("csrf",""));JSONObject j=r.json();ui.post(()->{if(r.ok()&&j.optBoolean("ok")){currentHazards.put(d);if(mapView!=null)mapView.setRadars(currentHazards);toast(label+" registrado.");}else toast(j.optString("error","Não foi possível registrar."));});}catch(Exception e){ui.post(()->toast("Falha ao registrar ponto."));}});}

    private void music(){
        if(landscape()&&!landscapePaneRender){landscapeMusic();return;}
        ScrollView sv=new ScrollView(this);content.addView(sv,new FrameLayout.LayoutParams(-1,-1));LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(24),dp(18),dp(24),dp(34));sv.addView(body,new ScrollView.LayoutParams(-1,-2));LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);LinearLayout title=new LinearLayout(this);title.setOrientation(LinearLayout.VERTICAL);title.addView(t("MINHA BIBLIOTECA",10,ACCENT,true));title.addView(t("Música",38,TEXT,true));head.addView(title,new LinearLayout.LayoutParams(0,-1,1));Button sync=btn("↻",false);sync.setTextSize(25);head.addView(sync,new LinearLayout.LayoutParams(dp(60),dp(60)));body.addView(head);
        LinearLayout tabs=new LinearLayout(this);body.addView(tabs);margins(tabs,0,14,0,0);String[] ks={"all","device","drive","server"},ls={"Todas","Celular","Drive","Servidor"};for(int i=0;i<ks.length;i++){String key=ks[i];Button x=btn(ls[i],false);x.setBackground(key.equals(musicSource)?grad(new int[]{Color.rgb(70,38,112),Color.rgb(35,25,74)},15,PURPLE):bg(PANEL,15,LINE));tabs.addView(x,new LinearLayout.LayoutParams(0,dp(60),1));if(i>0)margins(x,8,0,0,0);x.setOnClickListener(v->{musicSource=key;musicFolder="all";musicGenre="all";music();});}
        LinearLayout off=card();off.setOrientation(LinearLayout.HORIZONTAL);off.setGravity(Gravity.CENTER_VERTICAL);TextView di=t("⇩",28,PURPLE,true);di.setGravity(Gravity.CENTER);di.setBackground(bg(Color.rgb(27,20,60),20,PURPLE));off.addView(di,new LinearLayout.LayoutParams(dp(58),dp(58)));LinearLayout dc=new LinearLayout(this);dc.setOrientation(LinearLayout.VERTICAL);dc.setPadding(dp(14),0,0,0);dc.addView(t("Downloads offline",16,TEXT,true));dc.addView(small("Músicas baixadas ficam disponíveis no próprio aparelho."));off.addView(dc,new LinearLayout.LayoutParams(0,-1,1));body.addView(off);margins(off,0,15,0,0);
        EditText search=edit("Buscar música, artista, pasta ou ritmo");body.addView(search,new LinearLayout.LayoutParams(-1,dp(62)));margins(search,0,14,0,0);TextView status=small("Organizando sua biblioteca…");body.addView(status);margins(status,3,10,0,8);
        TextView ftitle=t("PASTAS",12,Color.rgb(196,101,255),true);body.addView(ftitle);margins(ftitle,0,10,0,6);HorizontalScrollView foldersScroll=new HorizontalScrollView(this);foldersScroll.setHorizontalScrollBarEnabled(false);LinearLayout folders=new LinearLayout(this);folders.setOrientation(LinearLayout.HORIZONTAL);foldersScroll.addView(folders);body.addView(foldersScroll,new LinearLayout.LayoutParams(-1,dp(54)));
        TextView gtitle=t("RITMOS",12,Color.rgb(196,101,255),true);body.addView(gtitle);margins(gtitle,0,12,0,6);HorizontalScrollView genresScroll=new HorizontalScrollView(this);genresScroll.setHorizontalScrollBarEnabled(false);LinearLayout genres=new LinearLayout(this);genres.setOrientation(LinearLayout.HORIZONTAL);genresScroll.addView(genres);body.addView(genresScroll,new LinearLayout.LayoutParams(-1,dp(54)));
        LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);body.addView(list);margins(list,0,12,0,0);
        TextWatcher tw=new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int b,int c){renderMusic(search,status,folders,genres,list);}public void afterTextChanged(Editable e){}};search.addTextChangedListener(tw);sync.setOnClickListener(v->{deviceTracks.clear();onlineTracks.clear();loadMusicData(search,status,folders,genres,list);});loadMusicData(search,status,folders,genres,list);
    }
    private void loadMusicData(EditText search,TextView status,LinearLayout folders,LinearLayout genres,LinearLayout list){status.setText("Lendo celular e sincronizando biblioteca…");if(NativeMusicRepository.hasPermission(this))io.execute(()->{List<MusicTrack> t=NativeMusicRepository.scan(this);synchronized(deviceTracks){deviceTracks.clear();deviceTracks.addAll(t);}ui.post(()->renderMusic(search,status,folders,genres,list));});else{status.setText("Permissão de músicas necessária. Toque aqui para autorizar.");status.setOnClickListener(v->requestPermissions(new String[]{NativeMusicRepository.permissionName()},REQ_AUDIO));}if(online())io.execute(()->{try{NativeApiClient.Response r=api.get(server(),"api/library.php?action=list");JSONArray a=r.json().optJSONArray("tracks");ArrayList<MusicTrack> got=new ArrayList<>();if(r.ok()&&a!=null)for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;String src=o.optString("source","");if(!src.startsWith("http://")&&!src.startsWith("https://")&&!src.startsWith("content://")&&!src.startsWith("file://")&&!src.isEmpty())o.put("source",server()+src.replaceFirst("^/+",""));got.add(MusicTrack.fromJson(o));}synchronized(onlineTracks){onlineTracks.clear();onlineTracks.addAll(got);}ui.post(()->renderMusic(search,status,folders,genres,list));}catch(Exception e){ui.post(()->renderMusic(search,status,folders,genres,list));}});}
    private void renderMusic(EditText search,TextView status,LinearLayout folders,LinearLayout genres,LinearLayout list){ArrayList<MusicTrack> base=new ArrayList<>();synchronized(deviceTracks){base.addAll(deviceTracks);}synchronized(onlineTracks){base.addAll(onlineTracks);}int dev=deviceTracks.size(),drv=0,srv=0;for(MusicTrack t:onlineTracks){String k=sourceKey(t);if("drive".equals(k))drv++;else srv++;}status.setText(dev+" no celular · "+drv+" no Drive · "+srv+" no servidor");Set<String> fs=new LinkedHashSet<>(),gs=new LinkedHashSet<>();for(MusicTrack t:base){if(!t.folder.isEmpty())fs.add(t.folder);if(!t.genre.isEmpty())gs.add(t.genre);}renderFacet(folders,fs,true,search,status,folders,genres,list);renderFacet(genres,gs,false,search,status,folders,genres,list);String q=search.getText().toString().trim().toLowerCase(Locale.ROOT);shownTracks.clear();for(MusicTrack t:base){String sk=sourceKey(t);if(!"all".equals(musicSource)&&!musicSource.equals(sk))continue;if(!"all".equals(musicFolder)&&!musicFolder.equals(t.folder))continue;if(!"all".equals(musicGenre)&&!musicGenre.equals(t.genre))continue;String hay=(t.title+" "+t.artist+" "+t.album+" "+t.folder+" "+t.genre).toLowerCase(Locale.ROOT);if(!q.isEmpty()&&!hay.contains(q))continue;shownTracks.add(t);if(shownTracks.size()>=500)break;}list.removeAllViews();if(shownTracks.isEmpty()){TextView empty=t("Nenhuma música encontrada com estes filtros.",17,MUTED,false);empty.setGravity(Gravity.CENTER);list.addView(empty,new LinearLayout.LayoutParams(-1,dp(120)));return;}for(int i=0;i<shownTracks.size();i++)list.addView(trackRow(shownTracks.get(i),i),new LinearLayout.LayoutParams(-1,dp(84)));}
    private void renderFacet(LinearLayout rail,Set<String> values,boolean folder,EditText search,TextView status,LinearLayout folders,LinearLayout genres,LinearLayout list){rail.removeAllViews();Button all=btn(folder?"Todas":"Todos",false);all.setBackground((folder?"all".equals(musicFolder):"all".equals(musicGenre))?bg(Color.rgb(14,48,42),14,GREEN):bg(PANEL2,14,LINE));rail.addView(all,new LinearLayout.LayoutParams(-2,dp(44)));all.setOnClickListener(v->{if(folder)musicFolder="all";else musicGenre="all";renderMusic(search,status,folders,genres,list);});int n=0;for(String value:values){if(value==null||value.trim().isEmpty()||n++>24)continue;Button b=btn(value,false);boolean on=folder?value.equals(musicFolder):value.equals(musicGenre);b.setBackground(on?bg(Color.rgb(58,30,96),14,PURPLE):bg(PANEL2,14,LINE));rail.addView(b,new LinearLayout.LayoutParams(-2,dp(44)));margins(b,8,0,0,0);b.setOnClickListener(v->{if(folder)musicFolder=value.equals(musicFolder)?"all":value;else musicGenre=value.equals(musicGenre)?"all":value;renderMusic(search,status,folders,genres,list);});}}
    private View trackRow(MusicTrack tr,int ix){LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(10),dp(7),dp(8),dp(7));row.setBackground(bg(Color.rgb(7,15,24),16,LINE));TextView art=t("♪",22,ACCENT,true);art.setGravity(Gravity.CENTER);art.setBackground(bg(Color.rgb(16,31,47),14,0));row.addView(art,new LinearLayout.LayoutParams(dp(58),dp(58)));LinearLayout meta=new LinearLayout(this);meta.setOrientation(LinearLayout.VERTICAL);meta.setPadding(dp(12),0,0,0);meta.addView(t(tr.title,14,TEXT,true));meta.addView(small(tr.artist+(tr.folder.isEmpty()?"":" · "+tr.folder)));row.addView(meta,new LinearLayout.LayoutParams(0,-1,1));Button fav=btn(isFavorite(tr)?"★":"☆",false);fav.setTextSize(18);row.addView(fav,new LinearLayout.LayoutParams(dp(46),dp(46)));fav.setOnClickListener(v->{toggleFavorite(tr);fav.setText(isFavorite(tr)?"★":"☆");});if(!"device".equals(sourceKey(tr))){Button dl=btn("↓",false);dl.setTextSize(18);row.addView(dl,new LinearLayout.LayoutParams(dp(46),dp(46)));margins(dl,5,0,0,0);dl.setOnClickListener(v->downloadTrack(tr,dl));}Button play=btn("▶",true);row.addView(play,new LinearLayout.LayoutParams(dp(50),dp(46)));margins(play,5,0,0,0);play.setOnClickListener(v->play(new ArrayList<>(shownTracks),ix));margins(row,0,0,0,7);return row;}
    private String sourceKey(MusicTrack t){String o=(t.origin==null?"":t.origin).toLowerCase(Locale.ROOT),src=t.source==null?"":t.source;if(src.startsWith("content://")||o.contains("device")||o.contains("celular")||o.contains("dispositivo"))return"device";if(o.contains("drive"))return"drive";return"server";}
    private Set<String> favorites(){try{JSONArray a=new JSONArray(prefs.getString(KEY_FAVORITES,"[]"));Set<String>s=new LinkedHashSet<>();for(int i=0;i<a.length();i++)s.add(a.optString(i));return s;}catch(Exception e){return new LinkedHashSet<>();}}
    private String favKey(MusicTrack t){return sourceKey(t)+":"+t.id+":"+t.title;}
    private boolean isFavorite(MusicTrack t){return favorites().contains(favKey(t));}
    private void toggleFavorite(MusicTrack t){Set<String>s=favorites();String k=favKey(t);if(s.contains(k))s.remove(k);else s.add(k);JSONArray a=new JSONArray();for(String x:s)a.put(x);prefs.edit().putString(KEY_FAVORITES,a.toString()).apply();}
    private void downloadTrack(MusicTrack tr,Button b){if(!online()||tr.source==null||!tr.source.startsWith("http")){toast("Download indisponível.");return;}b.setEnabled(false);b.setText("…");io.execute(()->{try{File root=getExternalFilesDir(Environment.DIRECTORY_MUSIC);if(root==null)throw new Exception("storage");File dir=new File(root,"MusicRoadOffline");if(!dir.exists()&&!dir.mkdirs())throw new Exception("mkdir");File file=new File(dir,safeName(tr.title)+(tr.mimeType!=null&&tr.mimeType.contains("m4a")?".m4a":".mp3"));HttpURLConnection c=(HttpURLConnection)new URL(tr.source).openConnection();c.setConnectTimeout(12000);c.setReadTimeout(60000);c.setRequestProperty("User-Agent","MusicRoadAndroid/"+BuildConfig.VERSION_NAME);String ck=api.cookie();if(ck!=null&&!ck.isEmpty())c.setRequestProperty("Cookie",ck);try(InputStream in=c.getInputStream();FileOutputStream out=new FileOutputStream(file)){byte[] buf=new byte[32768];int n;while((n=in.read(buf))>0)out.write(buf,0,n);}MediaScannerConnection.scanFile(this,new String[]{file.getAbsolutePath()},new String[]{tr.mimeType==null?"audio/mpeg":tr.mimeType},null);ui.post(()->{b.setEnabled(true);b.setText("✓");toast("Música salva para uso offline.");});}catch(Exception e){ui.post(()->{b.setEnabled(true);b.setText("↓");toast("Não foi possível baixar esta música.");});}});}
    private String safeName(String s){return(s==null?"musica":s).replaceAll("[\\\\/:*?\"<>|]+"," ").replaceAll("\\s+"," ").trim().substring(0,Math.min(80,Math.max(1,(s==null?"musica":s).replaceAll("[\\\\/:*?\"<>|]+"," ").replaceAll("\\s+"," ").trim().length())));}
    private void play(List<MusicTrack> tracks,int start){try{JSONArray a=new JSONArray();for(MusicTrack tr:tracks)a.put(tr.toJson());startService(PlaybackService.intentSetQueue(this,a.toString(),start,true));}catch(Exception e){toast("Não foi possível tocar.");}}

    private void radio(){if(landscape()&&!landscapePaneRender){landscapeUtility(RADIO);return;}ScrollView sv=new ScrollView(this);content.addView(sv,new FrameLayout.LayoutParams(-1,-1));LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(24),dp(20),dp(24),dp(34));sv.addView(body,new ScrollView.LayoutParams(-1,-2));body.addView(t("ENTRETENIMENTO",10,ACCENT,true));body.addView(t("Rádio",38,TEXT,true));LinearLayout fm=card();body.addView(fm);margins(fm,0,18,0,0);LinearLayout fh=new LinearLayout(this);fh.setGravity(Gravity.CENTER_VERTICAL);LinearLayout fc=new LinearLayout(this);fc.setOrientation(LinearLayout.VERTICAL);fc.addView(t("Rádio FM do aparelho",18,TEXT,true));fc.addView(small("Abre o tuner FM disponível no Android, quando houver."));fh.addView(fc,new LinearLayout.LayoutParams(0,-1,1));fh.addView(pill("FM",ACCENT),new LinearLayout.LayoutParams(-2,dp(38)));fm.addView(fh);Button open=btn("ABRIR RÁDIO FM",true);fm.addView(open,new LinearLayout.LayoutParams(-1,dp(56)));margins(open,0,14,0,0);open.setOnClickListener(v->openFm());LinearLayout web=card();body.addView(web);margins(web,0,14,0,0);web.addView(t("Rádios online",18,TEXT,true));web.addView(small("Salve suas estações. O player continua em segundo plano."));EditText name=edit("Nome da rádio"),freq=edit("Frequência · ex. 98.7 FM"),url=edit("URL do stream de áudio");web.addView(name,new LinearLayout.LayoutParams(-1,dp(54)));margins(name,0,12,0,0);web.addView(freq,new LinearLayout.LayoutParams(-1,dp(54)));margins(freq,0,8,0,0);web.addView(url,new LinearLayout.LayoutParams(-1,dp(54)));margins(url,0,8,0,0);Button save=btn("SALVAR ESTAÇÃO",true);web.addView(save,new LinearLayout.LayoutParams(-1,dp(54)));margins(save,0,10,0,0);save.setOnClickListener(v->{String n=name.getText().toString().trim(),u=url.getText().toString().trim();if(n.isEmpty()||(!u.startsWith("http://")&&!u.startsWith("https://"))){toast("Informe nome e URL do stream.");return;}JSONArray a=stations();JSONObject o=new JSONObject();try{o.put("name",n);o.put("frequency",freq.getText().toString().trim());o.put("url",u);a.put(o);}catch(Exception ignored){}prefs.edit().putString(KEY_RADIO,a.toString()).apply();radio();});body.addView(t("MINHAS ESTAÇÕES",11,Color.rgb(196,101,255),true));margins(body.getChildAt(body.getChildCount()-1),0,18,0,7);JSONArray a=stations();if(a.length()==0){body.addView(small("Nenhuma estação online salva ainda."));}for(int i=0;i<a.length();i++){JSONObject st=a.optJSONObject(i);if(st==null)continue;LinearLayout row=card();row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);LinearLayout mt=new LinearLayout(this);mt.setOrientation(LinearLayout.VERTICAL);mt.addView(t(st.optString("name","Rádio"),16,TEXT,true));mt.addView(small(st.optString("frequency","WEB")));row.addView(mt,new LinearLayout.LayoutParams(0,-1,1));Button p=btn("▶",true),del=btn("×",false);row.addView(p,new LinearLayout.LayoutParams(dp(54),dp(48)));row.addView(del,new LinearLayout.LayoutParams(dp(48),dp(48)));margins(del,6,0,0,0);body.addView(row,new LinearLayout.LayoutParams(-1,dp(78)));margins(row,0,6,0,0);String rn=st.optString("name","Rádio"),rf=st.optString("frequency",""),ru=st.optString("url","");p.setOnClickListener(v->{MusicTrack tr=new MusicTrack("radio-"+rn,rn,rf,"Rádio online",ru,"Rádio","audio/mpeg",0,0,0,"Rádio","Rádio","");play(java.util.Collections.singletonList(tr),0);});final int idx=i;del.setOnClickListener(v->{JSONArray old=stations(),nw=new JSONArray();for(int k=0;k<old.length();k++)if(k!=idx)nw.put(old.opt(k));prefs.edit().putString(KEY_RADIO,nw.toString()).apply();radio();});}body.addView(nowCard());margins(body.getChildAt(body.getChildCount()-1),0,20,0,0);}
    private JSONArray stations(){try{return new JSONArray(prefs.getString(KEY_RADIO,"[]"));}catch(Exception e){return new JSONArray();}}
    private void openFm(){try{Intent i=new Intent("android.intent.action.RADIO");i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);if(i.resolveActivity(getPackageManager())!=null)startActivity(i);else{Intent m=new Intent(Intent.ACTION_MAIN);m.addCategory(Intent.CATEGORY_APP_MUSIC);if(m.resolveActivity(getPackageManager())!=null)startActivity(m);else toast("Nenhum rádio FM compatível foi encontrado.");}}catch(Exception e){toast("Nenhum rádio FM compatível foi encontrado.");}}

    private void offline(){if(landscape()&&!landscapePaneRender){landscapeUtility(OFFLINE);return;}ScrollView sv=new ScrollView(this);content.addView(sv,new FrameLayout.LayoutParams(-1,-1));LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(24),dp(20),dp(24),dp(34));sv.addView(body,new ScrollView.LayoutParams(-1,-2));body.addView(t("USO SEM INTERNET",10,ACCENT,true));body.addView(t("Mapas e radares",34,TEXT,true));TextView intro=t("Baixe seu estado para manter mapa, vias e fiscalização disponíveis no aparelho.",14,MUTED,false);body.addView(intro);margins(intro,0,7,0,18);LinearLayout c=card();body.addView(c);LinearLayout title=new LinearLayout(this);title.setGravity(Gravity.CENTER_VERTICAL);LinearLayout tt=new LinearLayout(this);tt.setOrientation(LinearLayout.VERTICAL);tt.addView(t("Baixar por estado",18,TEXT,true));tt.addView(small("Os dados são salvos no dispositivo e podem ser usados sem internet."));title.addView(tt,new LinearLayout.LayoutParams(0,-1,1));title.addView(pill("BR",ACCENT),new LinearLayout.LayoutParams(-2,dp(38)));c.addView(title);Spinner sp=new Spinner(this);sp.setAdapter(new ArrayAdapter<State>(this,android.R.layout.simple_spinner_dropdown_item,STATES));String last=prefs.getString(KEY_UF,"");for(int i=0;i<STATES.length;i++)if(STATES[i].uf.equals(last))sp.setSelection(i);c.addView(sp,new LinearLayout.LayoutParams(-1,dp(58)));margins(sp,0,14,0,0);TextView status=small("Selecione o estado.");c.addView(status);margins(status,2,9,2,10);Button map=btn("↓ BAIXAR MAPA",true),rad=btn("↓ BAIXAR RADARES",false),del=btn("EXCLUIR DADOS DO ESTADO",false);c.addView(map,new LinearLayout.LayoutParams(-1,dp(56)));c.addView(rad,new LinearLayout.LayoutParams(-1,dp(54)));margins(rad,0,8,0,0);c.addView(del,new LinearLayout.LayoutParams(-1,dp(50)));margins(del,0,8,0,0);Runnable update=()->{State st=(State)sp.getSelectedItem();status.setText((offline.has("state/"+st.uf+"/map")?"Mapa ✓":"Mapa —")+" · "+(offline.has("state/"+st.uf+"/radars")?"Radares ✓":"Radares —"));};sp.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){update.run();}public void onNothingSelected(android.widget.AdapterView<?> p){}});update.run();map.setOnClickListener(v->download((State)sp.getSelectedItem(),"map",map,status,update));rad.setOnClickListener(v->download((State)sp.getSelectedItem(),"radars",rad,status,update));del.setOnClickListener(v->{State st=(State)sp.getSelectedItem();offline.removePrefix("state/"+st.uf+"/");update.run();});LinearLayout note=card();body.addView(note);margins(note,0,14,0,0);note.addView(t("Mapa no aparelho",17,TEXT,true));note.addView(small("O servidor transmite os dados durante o download; o conteúdo offline permanece no armazenamento privado do MusicRoad."));}
    private void download(State st,String kind,Button b,TextView status,Runnable done){if(!online()){toast("Conecte-se para baixar.");return;}prefs.edit().putString(KEY_UF,st.uf).apply();b.setEnabled(false);b.setText("PREPARANDO "+st.uf+"…");String path="api/offline_state.php?kind="+kind+"&state_id="+enc(st.id)+"&uf="+enc(st.uf)+"&state="+enc(st.name);io.execute(()->{try{NativeApiClient.Response r=api.getLarge(server(),path);JSONObject j=r.json();if(!r.ok()||!j.optBoolean("ok")){ui.post(()->{b.setEnabled(true);b.setText(kind.equals("map")?"↓ BAIXAR MAPA":"↓ BAIXAR RADARES");status.setText(j.optString("error","Falha no download."));});return;}boolean ok=offline.put("state/"+st.uf+"/"+kind,r.body);ui.post(()->{b.setEnabled(true);b.setText(kind.equals("map")?"↓ BAIXAR MAPA":"↓ BAIXAR RADARES");status.setText(ok?"Salvo no dispositivo ✓":"Falha ao salvar");done.run();});}catch(Exception e){ui.post(()->{b.setEnabled(true);b.setText(kind.equals("map")?"↓ BAIXAR MAPA":"↓ BAIXAR RADARES");status.setText(err(e,"Falha no download."));});}});}

    private void settings(){if(landscape()&&!landscapePaneRender){landscapeUtility(SETTINGS);return;}ScrollView sv=new ScrollView(this);content.addView(sv,new FrameLayout.LayoutParams(-1,-1));LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(24),dp(20),dp(24),dp(34));sv.addView(body,new ScrollView.LayoutParams(-1,-2));body.addView(t("SISTEMA",10,ACCENT,true));body.addView(t("Ajustes",36,TEXT,true));JSONObject u=account.optJSONObject("user");LinearLayout a=card();body.addView(a);margins(a,0,18,0,0);a.addView(t("Conta",12,Color.rgb(196,101,255),true));a.addView(t(u==null?"Conta local":u.optString("name","Conta"),19,TEXT,true));a.addView(small(u==null?"Modo offline":u.optString("email",u.optString("username",""))));SharedPreferences ap=getSharedPreferences(NavigationService.ALERT_PREFS,MODE_PRIVATE);LinearLayout voice=card();body.addView(voice);margins(voice,0,12,0,0);voice.addView(t("Voz e alertas da viagem",18,TEXT,true));voice.addView(small("Escolha como o MusicRoad avisa durante a condução."));String[][] opts={{"voice","Falar alertas"},{"system","Notificação Android"},{"vibrate","Vibração"},{"maneuvers","Orientação da rota"},{"speeding","Excesso de velocidade"},{"m300","Avisar a 300 m"},{"m200","Avisar a 200 m"},{"m100","Avisar a 100 m"},{"m50","Avisar a 50 m"},{"front","Aviso na sua frente"},{"radar","Radares"},{"portable","Fiscalização portátil"},{"video","Vídeo monitoramento"},{"signal","Semáforos"},{"bump","Quebra-molas"}};for(String[] o:opts){Switch sw=sw(o[1],ap.getBoolean(o[0],true));voice.addView(sw);sw.setOnCheckedChangeListener((x,y)->ap.edit().putBoolean(o[0],y).apply());}Button test=btn("TESTAR VOZ DO COPILOTO",false);voice.addView(test,new LinearLayout.LayoutParams(-1,dp(50)));margins(test,0,8,0,0);test.setOnClickListener(v->{Intent i=PlaybackService.intentAction(this,PlaybackService.ACTION_SPEAK);i.putExtra(PlaybackService.EXTRA_TEXT,"Olá. Esta é a voz do copiloto MusicRoad.");i.putExtra(PlaybackService.EXTRA_FORCE,true);startService(i);});LinearLayout about=card();body.addView(about);margins(about,0,12,0,0);TextView ver=t("MusicRoad "+BuildConfig.VERSION_NAME,18,TEXT,true);about.addView(ver);about.addView(small("Aplicativo Android nativo · rota online 100% Mapbox · sem WebView"));about.addView(small("Toque 7 vezes na versão para opções do dispositivo."));ver.setOnClickListener(v->{versionTaps++;if(versionTaps>=7){versionTaps=0;deviceDialog();}else if(versionTaps>=4)toast((7-versionTaps)+" toques para opções do dispositivo");});Button off=btn("MAPAS OFFLINE",false);body.addView(off,new LinearLayout.LayoutParams(-1,dp(54)));margins(off,0,13,0,0);off.setOnClickListener(v->go(OFFLINE));Button logout=btn("SAIR DESTA CONTA",false);logout.setTextColor(RED);body.addView(logout,new LinearLayout.LayoutParams(-1,dp(54)));margins(logout,0,9,0,0);logout.setOnClickListener(v->{clearAccount();api.clearCookie();showAuth(false,"Sessão encerrada. O dispositivo continua vinculado à sua conta.");});}
    private Switch sw(String s,boolean checked){Switch x=new Switch(this);x.setText(s);x.setTextColor(TEXT);x.setTextSize(14);x.setChecked(checked);x.setPadding(0,dp(9),0,dp(9));return x;}
    private void deviceDialog(){new AlertDialog.Builder(this).setTitle("Opções do dispositivo").setMessage("Remover este dispositivo desativa a entrada automática da conta depois de uma reinstalação.").setPositiveButton("Remover dispositivo",(d,w)->removeDevice()).setNegativeButton("Cancelar",null).show();}
    private void removeDevice(){if(!online()){alert("Sem internet","A remoção precisa ser confirmada pelo servidor.");return;}JSONObject d=new JSONObject();try{d.put("device_token",DeviceIdentity.token(this));}catch(Exception ignored){}io.execute(()->{try{NativeApiClient.Response r=api.post(server(),"api/native_app.php?action=remove_device",d);JSONObject j=r.json();ui.post(()->{if(r.ok()&&j.optBoolean("removed")){clearAccount();api.clearCookie();showAuth(false,"Dispositivo removido. Entre novamente quando quiser.");}else alert("MusicRoad",j.optString("error","Não foi possível remover."));});}catch(Exception e){ui.post(()->alert("Falha",err(e,"Tente novamente.")));}});}
    private void refreshAccount(){if(!online())return;io.execute(()->{try{NativeApiClient.Response r=api.get(server(),"api/native_app.php?action=me");JSONObject a=r.json().optJSONObject("account");if(r.ok()&&a!=null)saveAccount(a);}catch(Exception ignored){}});}
    private void locate(){if(!check(Manifest.permission.ACCESS_FINE_LOCATION)){requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},REQ_LOCATION);return;}try{Location g=locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER),n=locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);lastLocation=g!=null?g:n;}catch(Exception ignored){}try{locationManager.removeUpdates(locationListener);}catch(Exception ignored){}try{locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,2f,locationListener);}catch(Exception ignored){}}
    private void maybeMapboxReroute(Location l){
        if(l==null||currentRouteCoords==null||currentRouteCoords.length()<2||!Double.isFinite(currentDestinationLat)||!Double.isFinite(currentDestinationLon)||mapboxRoute==null)return;
        double off=distanceToRouteMeters(l.getLatitude(),l.getLongitude(),currentRouteCoords);double threshold=Math.max(85d,l.hasAccuracy()?l.getAccuracy()*2.2d:85d);
        if(off>threshold)mapboxOffRouteSamples++;else mapboxOffRouteSamples=Math.max(0,mapboxOffRouteSamples-1);
        long now=System.currentTimeMillis();
        if(mapboxOffRouteSamples>=3&&online()&&!mapboxRoute.isBusy()&&now-lastMapboxRerouteAt>15000){
            mapboxOffRouteSamples=0;lastMapboxRerouteAt=now;if(turnInstruction!=null)turnInstruction.setText("Recalculando pelo Mapbox…");
            String target=String.format(Locale.US,"%.7f,%.7f",currentDestinationLat,currentDestinationLon);
            mapboxRoute.requestRoute(l.getLatitude(),l.getLongitude(),target,currentDestination,true);
        }
    }
    private double distanceToRouteMeters(double lat,double lon,JSONArray coords){
        double best=Double.MAX_VALUE,cos=Math.cos(Math.toRadians(lat));
        for(int i=1;i<coords.length();i++){JSONArray a=coords.optJSONArray(i-1),b=coords.optJSONArray(i);if(a==null||b==null||a.length()<2||b.length()<2)continue;double x1=(a.optDouble(0)-lon)*111320d*cos,y1=(a.optDouble(1)-lat)*110540d,x2=(b.optDouble(0)-lon)*111320d*cos,y2=(b.optDouble(1)-lat)*110540d,dx=x2-x1,dy=y2-y1,den=dx*dx+dy*dy,t=den<=1e-6?0d:-(x1*dx+y1*dy)/den;t=Math.max(0d,Math.min(1d,t));double x=x1+t*dx,y=y1+t*dy;best=Math.min(best,Math.sqrt(x*x+y*y));}
        return best==Double.MAX_VALUE?999999d:best;
    }

    private final LocationListener locationListener=new LocationListener(){@Override public void onLocationChanged(Location l){lastLocation=l;if(mapView!=null){mapView.setUserBearing(l.hasBearing()?l.getBearing():0f);mapView.setUserLocation(l.getLatitude(),l.getLongitude());}if(hudSpeed!=null)hudSpeed.setText(String.valueOf(Math.max(0,Math.round(l.getSpeed()*3.6f))));if(hudRadar!=null)hudRadar.setText(nearestHazardText());maybeMapboxReroute(l);}@Override public void onProviderEnabled(String p){}@Override public void onProviderDisabled(String p){}@Override public void onStatusChanged(String p,int st,Bundle e){}};
    private final BroadcastReceiver playerReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){if(!PlaybackService.ACTION_STATE.equals(i.getAction()))return;try{JSONObject j=new JSONObject(i.getStringExtra(PlaybackService.EXTRA_STATE_JSON));JSONObject tr=j.optJSONObject("track");if(playerNow!=null&&tr!=null)playerNow.setText(tr.optString("title","Música")+" · "+tr.optString("artist",""));}catch(Exception ignored){}}};
    private void registerPlayerReceiver(){if(playerReceiverRegistered)return;IntentFilter f=new IntentFilter(PlaybackService.ACTION_STATE);if(Build.VERSION.SDK_INT>=33)registerReceiver(playerReceiver,f,RECEIVER_NOT_EXPORTED);else registerReceiver(playerReceiver,f);playerReceiverRegistered=true;}
    private boolean online(){try{ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);if(Build.VERSION.SDK_INT>=23){android.net.Network n=cm.getActiveNetwork();if(n==null)return false;NetworkCapabilities c=cm.getNetworkCapabilities(n);return c!=null&&(c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)||c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)||c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));}NetworkInfo n=cm.getActiveNetworkInfo();return n!=null&&n.isConnected();}catch(Exception e){return false;}}
    private boolean check(String p){return Build.VERSION.SDK_INT<23||checkSelfPermission(p)==PackageManager.PERMISSION_GRANTED;}
    private void requestNotifications(){if(Build.VERSION.SDK_INT>=33&&!check(Manifest.permission.POST_NOTIFICATIONS))requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},REQ_NOTIFICATIONS);}

    @Override public void onRequestPermissionsResult(int req,String[] permissions,int[] grants){super.onRequestPermissionsResult(req,permissions,grants);if(req==REQ_LOCATION&&grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)locate();if(req==REQ_AUDIO&&grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED&&screen==MUSIC)render();}
    @Override public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);if(prefs.getBoolean(KEY_SETUP,false)&&hasAccount())showShell();}
    @Override protected void onResume(){super.onResume();if(screen==MAP)locate();try{startService(PlaybackService.intentAction(this,PlaybackService.ACTION_BROADCAST_STATE));}catch(Exception ignored){}}
    @Override protected void onPause(){super.onPause();try{locationManager.removeUpdates(locationListener);}catch(Exception ignored){}}
    @Override protected void onDestroy(){if(mapboxRoute!=null)mapboxRoute.shutdown();try{if(playerReceiverRegistered)unregisterReceiver(playerReceiver);}catch(Exception ignored){}io.shutdownNow();super.onDestroy();}
    @Override public void onBackPressed(){if(prefs.getBoolean(KEY_SETUP,false)&&hasAccount()&&screen!=HOME){go(HOME);return;}super.onBackPressed();}

    private void toast(String s){ui.post(()->Toast.makeText(this,s,Toast.LENGTH_SHORT).show());}
    private void alert(String title,String msg){ui.post(()->new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK",null).show());}
    private static String err(Exception e,String fallback){String m=e==null?"":e.getMessage();return m==null||m.trim().isEmpty()?fallback:(m.length()>180?m.substring(0,180):m);}
    private static String first(String s){s=s==null?"":s.trim();int i=s.indexOf(' ');return i>0?s.substring(0,i):s;}
    private static String enc(String s){try{return URLEncoder.encode(s,"UTF-8");}catch(Exception e){return s;}}
    private static String km(double m){return m<1000?Math.round(m)+" m":String.format(Locale.getDefault(),"%.1f km",m/1000.0);}
    private static String duration(double sec){long m=Math.round(sec/60.0);return m<60?m+" min":(m/60)+"h "+(m%60)+"min";}
}

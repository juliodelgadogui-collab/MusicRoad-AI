package com.estradaplay.comunista;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.StatFs;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

/** Read-only local health snapshot. No diagnostics data is uploaded. */
public final class SystemDiagnosticsActivity extends ComponentActivity {
    private final int BG=Color.rgb(8,5,7),SURFACE=Color.rgb(22,11,14),BORDER=Color.rgb(76,38,44),TEXT=Color.rgb(246,238,224),MUTED=Color.rgb(174,151,146),RED=Color.rgb(190,18,38),GREEN=Color.rgb(72,212,134),GOLD=Color.rgb(226,185,76);
    private LinearLayout page;

    @Override protected void onCreate(Bundle state){super.onCreate(state);build();}

    private void build(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(18),dp(16),dp(18),dp(28));scroll.addView(page);
        setContentView(UnifiedAppShell.wrap(this,"central",scroll));

        LinearLayout head=row();head.setGravity(Gravity.CENTER_VERTICAL);
        Button back=button("‹ VOLTAR",false);head.addView(back,new LinearLayout.LayoutParams(dp(96),dp(48)));back.setOnClickListener(v->finish());
        LinearLayout title=col();title.addView(over("SISTEMA · LOCAL",RED));title.addView(text("Diagnóstico do app",27,TEXT,true));title.addView(text("Leitura local de versão, permissões e prontidão. Nada é enviado ao servidor.",11,MUTED,false));head.addView(title,new LinearLayout.LayoutParams(0,-2,1));page.addView(head);

        section("APLICATIVO");
        rowStatus("Versão",BuildConfig.VERSION_NAME+" · code "+BuildConfig.VERSION_CODE,GREEN);
        rowStatus("Android","API "+Build.VERSION.SDK_INT+" · "+Build.VERSION.RELEASE,TEXT);
        rowStatus("Variante",BuildConfig.FIXED_LAYOUT,TEXT);
        rowStatus("Estabilidade recente",ProcessCrashGuard.recentCrash(this)?"RECUPERAÇÃO ATIVA":"NORMAL",ProcessCrashGuard.recentCrash(this)?GOLD:GREEN);

        section("CONEXÃO");
        ApiClient api=new ApiClient(this);
        boolean https=api.base()!=null&&api.base().toLowerCase(java.util.Locale.ROOT).startsWith("https://");
        rowStatus("Rede",online()?"CONECTADA":"OFFLINE",online()?GREEN:GOLD);
        rowStatus("Servidor",ServerEndpointStore.custom(this).isEmpty()?"PADRÃO DO APK":"PERSONALIZADO",https?GREEN:RED);
        rowStatus("Transporte",https?"HTTPS":"REVISAR ENDPOINT",https?GREEN:RED);
        rowStatus("Sessão segura",api.hasSecureSession()?"ATIVA":"NÃO INICIADA",api.hasSecureSession()?GREEN:MUTED);

        section("PERMISSÕES");
        permissionRow("Localização",Manifest.permission.ACCESS_FINE_LOCATION);
        permissionRow("Câmera",Manifest.permission.CAMERA);
        permissionRow("Microfone",Manifest.permission.RECORD_AUDIO);
        if(Build.VERSION.SDK_INT>=33)permissionRow("Músicas do aparelho",Manifest.permission.READ_MEDIA_AUDIO);
        else permissionRow("Músicas do aparelho",Manifest.permission.READ_EXTERNAL_STORAGE);
        if(Build.VERSION.SDK_INT>=33)permissionRow("Notificações",Manifest.permission.POST_NOTIFICATIONS);
        else rowStatus("Notificações","NATIVAS DO SISTEMA",GREEN);

        section("ARMAZENAMENTO");
        rowStatus("Espaço interno livre",formatBytes(freeBytes()),freeBytes()>512L*1024L*1024L?GREEN:GOLD);
        rowStatus("Biblioteca do aparelho",PhoneMp3Store.hasPermission(this)?"ACESSO PERMITIDO":"SEM PERMISSÃO",PhoneMp3Store.hasPermission(this)?GREEN:GOLD);

        LinearLayout actions=row();
        Button permissions=button("ABRIR PERMISSÕES",false);actions.addView(permissions,new LinearLayout.LayoutParams(0,dp(52),1));permissions.setOnClickListener(v->openAppSettings());
        Button server=button("SERVIDOR",false);LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(0,dp(52),1);sp.setMargins(dp(8),0,0,0);actions.addView(server,sp);server.setOnClickListener(v->startActivity(new Intent(this,ServerSettingsActivity.class)));
        page.addView(actions);margins(actions,0,18,0,0);
    }

    private void permissionRow(String label,String permission){boolean ok=checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED;rowStatus(label,ok?"PERMITIDO":"NÃO PERMITIDO",ok?GREEN:GOLD);}
    private void rowStatus(String label,String value,int color){LinearLayout r=row();r.setGravity(Gravity.CENTER_VERTICAL);r.setPadding(dp(13),dp(11),dp(13),dp(11));r.setBackground(panel(SURFACE,13,BORDER));TextView a=text(label,12,TEXT,true);r.addView(a,new LinearLayout.LayoutParams(0,-2,1));TextView b=over(value,color);b.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);b.setMaxLines(2);r.addView(b,new LinearLayout.LayoutParams(0,-2,1));page.addView(r);margins(r,0,0,0,7);}
    private void section(String name){TextView s=over(name,MUTED);page.addView(s);margins(s,2,18,0,8);}

    private boolean online(){try{ConnectivityManager cm=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);if(cm==null)return false;NetworkCapabilities c=cm.getNetworkCapabilities(cm.getActiveNetwork());return c!=null&&c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);}catch(Throwable e){return false;}}
    private long freeBytes(){try{return new StatFs(getFilesDir().getAbsolutePath()).getAvailableBytes();}catch(Throwable e){return 0L;}}
    private String formatBytes(long b){if(b<=0)return"INDISPONÍVEL";double gb=b/(1024d*1024d*1024d);if(gb>=1d)return String.format(java.util.Locale.US,"%.1f GB",gb);return Math.max(0,Math.round(b/(1024d*1024d)))+" MB";}
    private void openAppSettings(){try{Intent i=new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+getPackageName()));startActivity(i);}catch(Throwable ignored){}}

    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}
    private LinearLayout col(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private TextView text(String v,float s,int c,boolean bold){TextView t=new TextView(this);t.setText(v);t.setTextSize(s);t.setTextColor(c);t.setGravity(Gravity.CENTER_VERTICAL);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private TextView over(String v,int c){TextView t=text(v,8.5f,c,true);t.setLetterSpacing(.10f);return t;}
    private Button button(String v,boolean primary){Button b=new Button(this);b.setAllCaps(false);b.setText(v);b.setTextSize(9);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setTextColor(TEXT);b.setStateListAnimator(null);b.setBackground(panel(primary?RED:Color.rgb(28,14,18),12,primary?0:BORDER));return b;}
    private GradientDrawable panel(int c,int r,int stroke){GradientDrawable g=new GradientDrawable();g.setColor(c);g.setCornerRadius(dp(r));if(stroke!=0)g.setStroke(dp(1),stroke);return g;}
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private void margins(View v,int l,int t,int r,int b){if(v.getLayoutParams() instanceof LinearLayout.LayoutParams){LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)v.getLayoutParams();p.setMargins(dp(l),dp(t),dp(r),dp(b));v.setLayoutParams(p);}}
}

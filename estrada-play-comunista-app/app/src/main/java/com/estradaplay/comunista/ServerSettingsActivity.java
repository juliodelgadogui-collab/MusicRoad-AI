package com.estradaplay.comunista;

import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.activity.ComponentActivity;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Recovery/settings screen. The server address never appears in the normal driving UI. */
public final class ServerSettingsActivity extends ComponentActivity {
    private final int BG=Color.rgb(8,5,7),TEXT=Color.rgb(246,238,224),MUTED=Color.rgb(174,151,146),RED=Color.rgb(190,18,38),GREEN=Color.rgb(69,212,131);
    private EditText url; private TextView state; private Button save;
    @Override protected void onCreate(Bundle b){super.onCreate(b);build();}
    private void build(){
        ScrollView sv=new ScrollView(this);LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);p.setPadding(dp(22),dp(24),dp(22),dp(30));p.setBackgroundColor(BG);sv.addView(p);setContentView(UnifiedAppShell.wrap(this,"central",sv));
        p.addView(t("ESTRADA PLAY · RECUPERAÇÃO",11,RED,true));p.addView(t("Servidor",30,TEXT,true));p.addView(t("Use esta tela apenas quando a hospedagem mudar. O endereço fica salvo neste aparelho e não aparece durante a condução.",13,MUTED,false));
        url=new EditText(this);url.setSingleLine(true);url.setTextColor(TEXT);url.setHintTextColor(MUTED);url.setHint("https://seu-dominio.com/");url.setText(ServerEndpointStore.custom(this));url.setPadding(dp(14),0,dp(14),0);url.setBackground(box(Color.rgb(29,14,18),10,Color.rgb(77,38,44)));LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(-1,dp(58));ep.setMargins(0,dp(18),0,dp(10));p.addView(url,ep);
        Button test=button("TESTAR SERVIDOR",Color.rgb(55,28,32));p.addView(test,new LinearLayout.LayoutParams(-1,dp(52)));test.setOnClickListener(v->test(false));
        save=button("SALVAR E REINICIAR APP",RED);LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,dp(58));sp.setMargins(0,dp(9),0,0);p.addView(save,sp);save.setOnClickListener(v->test(true));
        Button def=button("USAR SERVIDOR PADRÃO DO APK",Color.rgb(40,23,26));LinearLayout.LayoutParams dpv=new LinearLayout.LayoutParams(-1,dp(50));dpv.setMargins(0,dp(9),0,0);p.addView(def,dpv);def.setOnClickListener(v->{ServerEndpointStore.clear(this);clearOldSession();restart();});
        state=t("O teste consulta apenas api/ping.php e não envia sua senha.",12,MUTED,false);state.setPadding(dp(12),dp(13),dp(12),dp(13));LinearLayout.LayoutParams st=new LinearLayout.LayoutParams(-1,-2);st.setMargins(0,dp(14),0,0);p.addView(state,st);
    }
    private void test(boolean persist){
        final String raw=url.getText().toString().trim();if(!ServerEndpointStore.valid(raw)){state.setText("Endereço inválido. Informe o domínio completo do novo servidor.");state.setTextColor(Color.rgb(255,110,115));return;}
        state.setText("Testando conexão…");state.setTextColor(MUTED);save.setEnabled(false);
        new Thread(()->{String msg;boolean reachable=false;boolean installed=false;try{String base=ServerEndpointStore.normalize(raw);HttpURLConnection c=(HttpURLConnection)new URL(base+"api/ping.php").openConnection();c.setConnectTimeout(7000);c.setReadTimeout(9000);c.setRequestProperty("Accept","application/json");c.setRequestProperty("User-Agent","EstradaPlayPortable/1.6.2");int code=c.getResponseCode();InputStream in=code>=200&&code<400?c.getInputStream():c.getErrorStream();String body=read(in);c.disconnect();JSONObject j=new JSONObject(body);reachable=code>=200&&code<500;installed=j.optBoolean("installed",false)&&j.optBoolean("ok",false);if(installed)msg="Servidor pronto · "+j.optString("server_version","EstradaPlay");else if(j.optBoolean("setup_required",false))msg="Servidor encontrado, mas falta concluir /install.php";else msg="Servidor respondeu, porém não está pronto para o app.";}catch(Throwable e){msg="Não foi possível alcançar esse servidor.";}final boolean ok=reachable;final boolean ready=installed;final String m=msg;runOnUiThread(()->{save.setEnabled(true);state.setText(m);state.setTextColor(ready?GREEN:(ok?Color.rgb(226,185,76):Color.rgb(255,110,115)));if(persist&&ok){ServerEndpointStore.set(this,raw);clearOldSession();restart();}});}).start();
    }
    private void clearOldSession(){try{new ApiClient(this).clearSession();}catch(Throwable ignored){}getSharedPreferences("estradaplay_ui_v1",MODE_PRIVATE).edit().remove("account").apply();}
    private void restart(){Intent i=new Intent(this,GateActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);startActivity(i);finish();}
    private static String read(InputStream in)throws Exception{if(in==null)return"";ByteArrayOutputStream o=new ByteArrayOutputStream();byte[]b=new byte[4096];int n;try(InputStream x=in){while((n=x.read(b))>0&&o.size()<100000)o.write(b,0,n);}return new String(o.toByteArray(),StandardCharsets.UTF_8);}
    private TextView t(String s,int z,int c,boolean b){TextView v=new TextView(this);v.setText(s);v.setTextSize(z);v.setTextColor(c);if(b)v.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);v.setPadding(0,dp(4),0,dp(4));return v;}
    private Button button(String s,int c){Button b=new Button(this);b.setText(s);b.setTextColor(TEXT);b.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);b.setBackground(box(c,10,c));return b;}
    private GradientDrawable box(int fill,int r,int stroke){GradientDrawable g=new GradientDrawable();g.setColor(fill);g.setCornerRadius(dp(r));g.setStroke(dp(1),stroke);return g;}
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
}

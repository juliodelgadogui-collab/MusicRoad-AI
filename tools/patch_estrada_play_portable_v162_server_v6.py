#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'estrada-play-comunista-app'
JAVA = APP / 'app/src/main/java/com/estradaplay/comunista'


def read(p): return p.read_text(encoding='utf-8')
def write(p, s): p.parent.mkdir(parents=True, exist_ok=True); p.write_text(s, encoding='utf-8')
def once(s, old, new, label):
    if new in s: return s
    if old not in s: raise SystemExit('Trecho nao encontrado: ' + label)
    return s.replace(old, new, 1)

# ---------------------------------------------------------------------------
# Universal 1.6.2 — portable server endpoint
# ---------------------------------------------------------------------------
p = APP / 'app/build.gradle'
s = read(p)
s = re.sub(r'versionCode\s+161\b', 'versionCode 162', s, count=1)
s = re.sub(r"versionName\s+'1\.6\.1'", "versionName '1.6.2'", s, count=1)
write(p, s)

p = APP / 'app/src/main/AndroidManifest.xml'
s = read(p)
if '.ServerSettingsActivity' not in s:
    marker = '        <activity android:name=".RoadRadioActivity" android:exported="false" android:screenOrientation="${appOrientation}" />\n'
    s = once(s, marker, marker + '        <activity android:name=".ServerSettingsActivity" android:exported="false" android:screenOrientation="${appOrientation}" />\n', 'manifest server settings')
write(p, s)

p = JAVA / 'ApiClient.java'
s = read(p)
old = '''        String b = BuildConfig.SERVER_URL == null ? "" : BuildConfig.SERVER_URL.trim();\n        if (!b.endsWith("/")) b += "/";\n        base = b;\n'''
new = '''        String fallback = BuildConfig.SERVER_URL == null ? "" : BuildConfig.SERVER_URL.trim();\n        base = ServerEndpointStore.base(app, fallback);\n'''
s = once(s, old, new, 'ApiClient dynamic endpoint')
write(p, s)

write(JAVA / 'ServerEndpointStore.java', r'''package com.estradaplay.comunista;

import android.content.Context;
import android.content.SharedPreferences;
import java.net.URI;

/** Persistent server override so the APK can move to a new hosting without recompilation. */
final class ServerEndpointStore {
    private static final String PREFS="estradaplay_server_endpoint_v1";
    private static final String KEY="base_url";
    private ServerEndpointStore() {}

    static String base(Context c,String fallback){
        String custom=c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY,"");
        String v=custom==null||custom.trim().isEmpty()?fallback:custom;
        return normalize(v);
    }
    static String custom(Context c){
        String v=c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY,"");
        return v==null?"":v.trim();
    }
    static void set(Context c,String value){
        c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(KEY,normalize(value)).apply();
    }
    static void clear(Context c){c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().remove(KEY).apply();}
    static String normalize(String raw){
        String v=raw==null?"":raw.trim();
        if(v.isEmpty()) return "";
        if(!v.startsWith("https://")&&!v.startsWith("http://"))v="https://"+v;
        while(v.endsWith("/"))v=v.substring(0,v.length()-1);
        return v+"/";
    }
    static boolean valid(String raw){
        try{String v=normalize(raw);URI u=new URI(v);return ("https".equalsIgnoreCase(u.getScheme())||"http".equalsIgnoreCase(u.getScheme()))&&u.getHost()!=null&&!u.getHost().isEmpty();}
        catch(Throwable e){return false;}
    }
}
''')

write(JAVA / 'ServerSettingsActivity.java', r'''package com.estradaplay.comunista;

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
        ScrollView sv=new ScrollView(this);LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);p.setPadding(dp(22),dp(24),dp(22),dp(30));p.setBackgroundColor(BG);sv.addView(p);setContentView(sv);
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
''')

# Settings entry inside Central Inteligente.
p = JAVA / 'DriveToolsActivity.java'
s = read(p)
if 'CONFIGURAR SERVIDOR' not in s:
    marker = 'button("RÁDIO DA RODOVIA · PTT",RoadRadioActivity.class);'
    if marker not in s: raise SystemExit('DriveTools radio marker missing')
    s = s.replace(marker, marker + 'button("CONFIGURAR SERVIDOR",ServerSettingsActivity.class);', 1)
write(p, s)

# Recovery entry on auth screen, useful when the old server is unavailable.
p = JAVA / 'MainActivity.java'
s = read(p)
if 'TROCAR SERVIDOR' not in s:
    marker = '''        toggle.setOnClickListener(v -> showAuth(!register, null));\n'''
    add = marker + '''        Button server = textButton("TROCAR SERVIDOR");\n        form.addView(server, lp(-1, 44));\n        server.setOnClickListener(v -> startActivity(new Intent(this, ServerSettingsActivity.class)));\n'''
    s = once(s, marker, add, 'Main auth server recovery')
write(p, s)

# ---------------------------------------------------------------------------
# Server Central v6 — clean installer and portable public health endpoint
# ---------------------------------------------------------------------------
p = ROOT / 'api/bootstrap.php'
s = read(p)
if 'ESTRADAPLAY_SETUP_REQUIRED_V6' not in s:
    old = '''<?php\ndeclare(strict_types=1);\n\n$config = require __DIR__ . '/../config/config.php';\n'''
    new = '''<?php\ndeclare(strict_types=1);\n\n// ESTRADAPLAY_SETUP_REQUIRED_V6: clean-host packages contain no credentials.\n$configFile = __DIR__ . '/../config/config.php';\nif (!is_file($configFile)) {\n    if (PHP_SAPI !== 'cli') {\n        $accept = strtolower((string)($_SERVER['HTTP_ACCEPT'] ?? ''));\n        $uri = (string)($_SERVER['REQUEST_URI'] ?? '');\n        if (str_contains($accept, 'application/json') || str_contains($uri, '/api/')) {\n            http_response_code(503); header('Content-Type: application/json; charset=utf-8');\n            echo json_encode(['ok'=>false,'setup_required'=>true,'error'=>'Servidor ainda não instalado.'], JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES); exit;\n        }\n        header('Location: install.php'); exit;\n    }\n    throw new RuntimeException('Servidor ainda não instalado: execute install.php');\n}\n$config = require $configFile;\n'''
    s = once(s, old, new, 'bootstrap setup guard')
write(p, s)

p = ROOT / 'api/server_intelligent.php'
s = read(p).replace("const ESTRADAPLAY_SERVER_INTELLIGENT_VERSION = '500MB-v5';", "const ESTRADAPLAY_SERVER_INTELLIGENT_VERSION = '500MB-v6';", 1)
write(p, s)

write(ROOT / 'api/ping.php', r'''<?php
declare(strict_types=1);
header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
$configFile=__DIR__.'/../config/config.php';
$lockFile=__DIR__.'/../storage/install.lock';
if(!is_file($configFile)||!is_file($lockFile)){
    http_response_code(200);
    echo json_encode(['ok'=>false,'installed'=>false,'setup_required'=>true,'server_version'=>'500MB-v6'],JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES);exit;
}
try{
    require_once __DIR__.'/bootstrap.php';
    require_once __DIR__.'/server_intelligent.php';
    db()->query('SELECT 1');
    echo json_encode(['ok'=>true,'installed'=>true,'setup_required'=>false,'server_version'=>ESTRADAPLAY_SERVER_INTELLIGENT_VERSION,'time'=>date(DATE_ATOM)],JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES);
}catch(Throwable $e){http_response_code(503);echo json_encode(['ok'=>false,'installed'=>false,'setup_required'=>false,'server_version'=>'500MB-v6','error'=>'Banco ou configuração indisponível.'],JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES);}
''')

write(ROOT / 'install.php', r'''<?php
declare(strict_types=1);
session_name('ESTRADAPLAYINSTALL');session_start();
$root=__DIR__;$configFile=$root.'/config/config.php';$lockFile=$root.'/storage/install.lock';
if(is_file($lockFile)&&is_file($configFile)){header('Location: login.php');exit;}
if(empty($_SESSION['install_csrf']))$_SESSION['install_csrf']=bin2hex(random_bytes(24));
$error='';$done=false;
function ih(string $v):string{return htmlspecialchars($v,ENT_QUOTES,'UTF-8');}
function iv(string $k,string $d=''):string{return trim((string)($_POST[$k]??$d));}
function write_atomic(string $path,string $content):void{$dir=dirname($path);if(!is_dir($dir)&&!mkdir($dir,0775,true)&&!is_dir($dir))throw new RuntimeException('Não foi possível criar '.basename($dir));$tmp=$path.'.tmp.'.bin2hex(random_bytes(4));if(file_put_contents($tmp,$content,LOCK_EX)===false)throw new RuntimeException('Não foi possível gravar a configuração.');@chmod($tmp,0640);if(!@rename($tmp,$path)){@unlink($path);if(!@rename($tmp,$path)){@unlink($tmp);throw new RuntimeException('Não foi possível ativar a configuração.');}}}
function base_url_guess():string{$https=!empty($_SERVER['HTTPS'])&&$_SERVER['HTTPS']!=='off';$scheme=$https?'https':'http';$host=(string)($_SERVER['HTTP_HOST']??'');$dir=rtrim(str_replace('\\','/',dirname((string)($_SERVER['SCRIPT_NAME']??'/'))),'/');return $host!==''?$scheme.'://'.$host.($dir===''?'':$dir).'/':'';}
if($_SERVER['REQUEST_METHOD']==='POST'){
 try{
  if(!hash_equals((string)$_SESSION['install_csrf'],(string)($_POST['csrf']??'')))throw new RuntimeException('Sessão do instalador expirou. Atualize a página.');
  if(PHP_VERSION_ID<80100)throw new RuntimeException('É necessário PHP 8.1 ou superior.');
  if(!extension_loaded('pdo'))throw new RuntimeException('A extensão PDO precisa estar ativa.');
  $driver=iv('driver','mysql');if(!in_array($driver,['mysql','sqlite'],true))throw new RuntimeException('Banco inválido.');
  $appUrl=iv('app_url',base_url_guess());if($appUrl!==''&&!preg_match('~^https?://~i',$appUrl))throw new RuntimeException('URL pública inválida.');if($appUrl!==''&&!str_ends_with($appUrl,'/'))$appUrl.='/';
  $name=iv('admin_name');$email=iv('admin_email');$username=iv('admin_username');$password=(string)($_POST['admin_password']??'');
  if(strlen($name)<2||!filter_var($email,FILTER_VALIDATE_EMAIL)||strlen($username)<3||strlen($password)<8)throw new RuntimeException('Preencha o administrador; a senha deve ter pelo menos 8 caracteres.');
  $mysqlDsn='';$mysqlUser='';$mysqlPass='';$sqlitePath=$root.'/storage/db/estradaplay.sqlite';
  if($driver==='mysql'){
    if(!extension_loaded('pdo_mysql'))throw new RuntimeException('PDO MySQL/MariaDB não está ativo nesta hospedagem.');
    $host=iv('mysql_host','localhost');$port=(int)iv('mysql_port','3306');$dbn=iv('mysql_db');$mysqlUser=iv('mysql_user');$mysqlPass=(string)($_POST['mysql_pass']??'');
    if($host===''||$dbn===''||$mysqlUser==='')throw new RuntimeException('Informe host, banco e usuário MariaDB/MySQL.');
    if(!preg_match('/^[A-Za-z0-9_$-]+$/',$dbn))throw new RuntimeException('Nome do banco contém caracteres não permitidos.');
    $mysqlDsn='mysql:host='.$host.';port='.max(1,$port).';dbname='.$dbn.';charset=utf8mb4';
    $test=new PDO($mysqlDsn,$mysqlUser,$mysqlPass,[PDO::ATTR_ERRMODE=>PDO::ERRMODE_EXCEPTION]);$test->query('SELECT 1');$test=null;
  }else{
    if(!extension_loaded('pdo_sqlite'))throw new RuntimeException('PDO SQLite não está ativo nesta hospedagem.');
    if(!is_dir(dirname($sqlitePath)))@mkdir(dirname($sqlitePath),0775,true);$test=new PDO('sqlite:'.$sqlitePath);$test->query('SELECT 1');$test=null;
  }
  $cfg=['app_name'=>'Estrada Play Comunista','app_version'=>'server-v6','app_url'=>$appUrl,'env'=>'production','force_https'=>str_starts_with(strtolower($appUrl),'https://'),'db'=>['driver'=>$driver,'sqlite_path'=>$sqlitePath,'mysql_dsn'=>$mysqlDsn,'mysql_user'=>$mysqlUser,'mysql_pass'=>$mysqlPass],'google'=>['api_key'=>''],'routing'=>['osrm_base_url'=>'https://router.project-osrm.org','overpass_url'=>'https://overpass-api.de/api/interpreter','nominatim_url'=>'https://nominatim.openstreetmap.org/search','user_agent'=>'EstradaPlay/1.6.2'],'security'=>['session_name'=>'ESTRADAPLAY_'.bin2hex(random_bytes(8)),'rate_limit_per_minute'=>90]];
  $php="<?php\n// Gerado pelo instalador EstradaPlay v6. Não publique este arquivo.\nreturn ".var_export($cfg,true).";\n";write_atomic($configFile,$php);
  require_once $root.'/api/bootstrap.php';require_once $root.'/api/server_500mb.php';require_once $root.'/api/server_intelligent.php';
  ensure_schema();if(function_exists('server_ensure_500mb_schema'))server_ensure_500mb_schema();intelligent_server_ensure_schema();
  $now=date('Y-m-d H:i:s');$count=(int)db()->query("SELECT COUNT(*) FROM users WHERE role='admin'")->fetchColumn();
  if($count===0){$st=db()->prepare("INSERT INTO users(name,email,username,password_hash,role,status,created_at,updated_at) VALUES(?,?,?,?,?,'active',?,?)");$st->execute([$name,$email,$username,password_hash($password,PASSWORD_DEFAULT),'admin',$now,$now]);}
  set_app_setting('installed_at',$now);set_app_setting('server_public_url',$appUrl);set_app_setting('server_setup_version','v6');set_app_setting('installation_id',bin2hex(random_bytes(16)));
  if(!is_dir(dirname($lockFile)))@mkdir(dirname($lockFile),0775,true);if(file_put_contents($lockFile,json_encode(['installed_at'=>$now,'version'=>'v6'],JSON_UNESCAPED_SLASHES),LOCK_EX)===false)throw new RuntimeException('Banco criado, mas não foi possível bloquear o instalador. Verifique permissão de storage/.');@chmod($lockFile,0640);
  $done=true;unset($_SESSION['install_csrf']);
 }catch(Throwable $e){$error=$e->getMessage();if(!is_file($lockFile)&&is_file($configFile))@unlink($configFile);}
}
$guess=base_url_guess();
?><!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="robots" content="noindex,nofollow"><title>Instalar EstradaPlay</title><style>body{margin:0;background:#080507;color:#f6eee0;font:15px system-ui,Arial}.wrap{max-width:760px;margin:0 auto;padding:28px 18px 50px}.card{background:#160b0e;border:1px solid #54262d;border-radius:16px;padding:20px;margin:14px 0}.red{color:#e2253b}.muted{color:#b19894}.grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}label{display:block;font-size:12px;color:#cfb7b1;margin:9px 0 5px}input,select{width:100%;box-sizing:border-box;padding:13px;border-radius:9px;border:1px solid #64323a;background:#0e080a;color:#fff}button,a.btn{display:inline-block;border:0;border-radius:10px;background:#be1226;color:#fff;font-weight:800;padding:14px 18px;text-decoration:none;cursor:pointer}.ok{border-color:#236f4a}.err{border-color:#a42834;color:#ffb8b8}@media(max-width:620px){.grid{grid-template-columns:1fr}}</style></head><body><main class="wrap"><div class="red"><b>ESTRADA PLAY · INSTALAÇÃO LIMPA</b></div><h1>Servidor pronto para começar do zero</h1><p class="muted">Este pacote não contém credenciais nem banco antigo. Configure uma vez; depois o instalador é bloqueado automaticamente.</p><?php if($done):?><section class="card ok"><h2>Instalação concluída</h2><p>Banco, tabelas e administrador foram criados. Agora abra o painel e, no APK Universal 1.6.2, aponte para este domínio em “Trocar servidor”.</p><a class="btn" href="login.php">ABRIR PAINEL</a></section><?php else:?><?php if($error):?><div class="card err"><?=ih($error)?></div><?php endif;?><form method="post"><input type="hidden" name="csrf" value="<?=ih((string)$_SESSION['install_csrf'])?>"><section class="card"><h2>1 · Endereço público</h2><label>URL do servidor</label><input name="app_url" value="<?=ih(iv('app_url',$guess))?>" placeholder="https://seu-dominio.com/"></section><section class="card"><h2>2 · Banco</h2><label>Tipo</label><select name="driver" id="driver" onchange="document.getElementById('mysql').style.display=this.value==='mysql'?'grid':'none'"><option value="mysql" <?=iv('driver','mysql')==='mysql'?'selected':''?>>MariaDB / MySQL</option><option value="sqlite" <?=iv('driver')==='sqlite'?'selected':''?>>SQLite</option></select><div class="grid" id="mysql"><div><label>Host</label><input name="mysql_host" value="<?=ih(iv('mysql_host','localhost'))?>"></div><div><label>Porta</label><input name="mysql_port" value="<?=ih(iv('mysql_port','3306'))?>"></div><div><label>Banco</label><input name="mysql_db" value="<?=ih(iv('mysql_db'))?>"></div><div><label>Usuário</label><input name="mysql_user" value="<?=ih(iv('mysql_user'))?>"></div><div style="grid-column:1/-1"><label>Senha do banco</label><input type="password" name="mysql_pass" autocomplete="new-password"></div></div></section><section class="card"><h2>3 · Primeiro administrador</h2><div class="grid"><div><label>Nome</label><input name="admin_name" value="<?=ih(iv('admin_name'))?>"></div><div><label>E-mail</label><input type="email" name="admin_email" value="<?=ih(iv('admin_email'))?>"></div><div><label>Usuário</label><input name="admin_username" value="<?=ih(iv('admin_username','admin'))?>"></div><div><label>Senha · mínimo 8 caracteres</label><input type="password" name="admin_password" autocomplete="new-password"></div></div></section><button type="submit">INSTALAR E BLOQUEAR INSTALADOR</button></form><script>document.getElementById('driver').onchange()</script><?php endif;?></main></body></html>
''')

# Server admin label.
p = ROOT / 'admin_server.php'
s = read(p).replace('ESTRADAPLAY · SERVIDOR 500 MB V4', 'ESTRADAPLAY · SERVIDOR 500 MB V6', 1)
write(p, s)

write(APP / 'PORTABLE-SERVER-1.6.2.md', '''# Estrada Play Comunista Universal 1.6.2 — servidor portátil\n\n- APK não fica preso ao domínio compilado: endereço pode ser trocado no aparelho.\n- Tela normal não exibe URL do servidor.\n- Recuperação disponível na autenticação (TROCAR SERVIDOR) e na Central Inteligente.\n- Teste usa apenas `api/ping.php`.\n- Troca de servidor limpa sessão/conta local do servidor anterior e reinicia o fluxo de login.\n- Server Central v6 é instalação completa e limpa via `/install.php`.\n- O ZIP final exclui `config/config.php`, bancos e credenciais.\n''')

print('Portable Universal 1.6.2 + clean Server Central v6 patch applied')

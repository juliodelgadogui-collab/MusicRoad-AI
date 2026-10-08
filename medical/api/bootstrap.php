<?php
/**
 * Bootstrap V4 — módulo médico isolado.
 * Não compartilha sessão, banco ou configuração com o MusicRoad-AI.
 */
$medical_root = dirname(__DIR__);
$session_dir = $medical_root . '/storage/sessions';
if (!is_dir($session_dir)) @mkdir($session_dir, 0750, true);
if (is_dir($session_dir) && is_writable($session_dir)) session_save_path($session_dir);

session_name('MEDICAL_SESSION_V4');
ini_set('session.use_strict_mode','1');
ini_set('session.use_only_cookies','1');
$secure = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off');
if (session_status() !== PHP_SESSION_ACTIVE) {
    session_set_cookie_params(['lifetime'=>0,'path'=>'/','secure'=>$secure,'httponly'=>true,'samesite'=>'Lax']);
    session_start();
}

$db_path = __DIR__ . '/../database/medical.sqlite';
$db_dir = dirname($db_path);
if (!is_dir($db_dir)) @mkdir($db_dir,0750,true);
$db = new PDO('sqlite:' . $db_path);
$db->setAttribute(PDO::ATTR_ERRMODE,PDO::ERRMODE_EXCEPTION);
$db->setAttribute(PDO::ATTR_DEFAULT_FETCH_MODE,PDO::FETCH_ASSOC);
$db->exec('PRAGMA foreign_keys=ON');
$db->exec('PRAGMA journal_mode=WAL');

function db(){ global $db; return $db; }
function e($v){ return htmlspecialchars((string)$v,ENT_QUOTES,'UTF-8'); }
function medical_base(){
    static $base=null;
    if($base!==null)return $base;
    $script=str_replace('\\','/',dirname($_SERVER['SCRIPT_NAME']??''));
    $pos=strpos($script,'/medical');
    $base=$pos===false?'/medical':substr($script,0,$pos+strlen('/medical'));
    return rtrim($base,'/');
}
function medical_url($path=''){ return medical_base().'/'.ltrim($path,'/'); }
function redirect_to($path){ header('Location: '.medical_url($path)); exit; }

function table_exists($name){
    $s=db()->prepare("SELECT name FROM sqlite_master WHERE type='table' AND name=?");
    $s->execute([$name]); return (bool)$s->fetchColumn();
}
function ensure_column($table,$column,$definition){
    $allowed=['medicos','pacientes','atendimentos','atestados','laudos','cids','modelos_documento','documentos'];
    if(!in_array($table,$allowed,true))return;
    $cols=db()->query('PRAGMA table_info('.$table.')')->fetchAll();
    foreach($cols as $c) if($c['name']===$column)return;
    db()->exec('ALTER TABLE '.$table.' ADD COLUMN '.$column.' '.$definition);
}

$db->exec("CREATE TABLE IF NOT EXISTS medicos(
 id INTEGER PRIMARY KEY AUTOINCREMENT,nome_completo TEXT,cpf TEXT,crm TEXT,uf TEXT,
 especialidade TEXT,rqe TEXT,telefone TEXT,email TEXT,endereco TEXT,logo_path TEXT,
 assinatura_path TEXT,senha_hash TEXT,is_admin INTEGER DEFAULT 0,created_at TEXT DEFAULT CURRENT_TIMESTAMP
)");
$db->exec("CREATE TABLE IF NOT EXISTS pacientes(
 id INTEGER PRIMARY KEY AUTOINCREMENT,medico_id INTEGER,nome TEXT,cpf TEXT,data_nascimento TEXT,
 sexo TEXT,telefone TEXT,email TEXT,endereco TEXT,profissao TEXT,funcao TEXT,empresa TEXT,
 info_clinica TEXT,alergias TEXT,medicamentos TEXT,antecedentes TEXT,created_at TEXT DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(medico_id) REFERENCES medicos(id)
)");
$db->exec("CREATE TABLE IF NOT EXISTS atendimentos(
 id INTEGER PRIMARY KEY AUTOINCREMENT,paciente_id INTEGER,medico_id INTEGER,data_atendimento TEXT,
 queixa_principal TEXT,historia_clinica TEXT,sintomas TEXT,duracao TEXT,evolucao TEXT,medicamentos TEXT,
 alergias TEXT,exame_fisico TEXT,exames_apresentados TEXT,tratamento TEXT,observacoes TEXT,
 capacidade_limitacoes TEXT,capacidade_restricoes TEXT,capacidade_atividades TEXT,capacidade_impacto TEXT,
 capacidade_obs TEXT,created_at TEXT DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(paciente_id) REFERENCES pacientes(id),FOREIGN KEY(medico_id) REFERENCES medicos(id)
)");
$db->exec("CREATE TABLE IF NOT EXISTS atestados(
 id INTEGER PRIMARY KEY AUTOINCREMENT,paciente_id INTEGER,medico_id INTEGER,atendimento_id INTEGER,
 codigo_validacao TEXT UNIQUE,data_emissao TEXT,titulo TEXT,conteudo_personalizado TEXT,cid_codigo TEXT,
 cid_descricao TEXT,periodo_afastamento TEXT,inicio_afastamento TEXT,fim_afastamento TEXT,
 dias_afastamento INTEGER,atividade_habitual TEXT,incapacidade_restricao TEXT,info_clinica TEXT,
 observacoes TEXT,modelo_id INTEGER,status TEXT DEFAULT 'EMITIDO',pdf_path TEXT,cancelado_em TEXT,
 cancelado_motivo TEXT,FOREIGN KEY(paciente_id) REFERENCES pacientes(id),FOREIGN KEY(medico_id) REFERENCES medicos(id)
)");
$db->exec("CREATE TABLE IF NOT EXISTS laudos(
 id INTEGER PRIMARY KEY AUTOINCREMENT,paciente_id INTEGER,medico_id INTEGER,atendimento_id INTEGER,
 codigo_validacao TEXT UNIQUE,data_emissao TEXT,titulo TEXT,conteudo_personalizado TEXT,historico_clinico TEXT,
 avaliacao TEXT,exames TEXT,cid_codigo TEXT,cid_descricao TEXT,repercussao TEXT,conclusao TEXT,
 atividade_habitual TEXT,periodo_estimado TEXT,incapacidade_restricao TEXT,modelo_id INTEGER,
 status TEXT DEFAULT 'EMITIDO',pdf_path TEXT,cancelado_em TEXT,cancelado_motivo TEXT,
 FOREIGN KEY(paciente_id) REFERENCES pacientes(id),FOREIGN KEY(medico_id) REFERENCES medicos(id)
)");
$db->exec("CREATE TABLE IF NOT EXISTS cids(id INTEGER PRIMARY KEY AUTOINCREMENT,codigo TEXT UNIQUE,descricao TEXT,fonte TEXT,versao TEXT)");
$db->exec("CREATE TABLE IF NOT EXISTS modelos_documento(
 id INTEGER PRIMARY KEY AUTOINCREMENT,medico_id INTEGER NOT NULL,tipo TEXT NOT NULL,nome TEXT NOT NULL,
 conteudo TEXT NOT NULL,ativo INTEGER DEFAULT 1,created_at TEXT DEFAULT CURRENT_TIMESTAMP,updated_at TEXT DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(medico_id) REFERENCES medicos(id)
)");
$db->exec("CREATE TABLE IF NOT EXISTS auditoria(
 id INTEGER PRIMARY KEY AUTOINCREMENT,usuario_id INTEGER,data_hora TEXT,acao TEXT,tabela TEXT,registro_id INTEGER,detalhes TEXT
)");
$db->exec("CREATE TABLE IF NOT EXISTS documentos_versoes(
 id INTEGER PRIMARY KEY AUTOINCREMENT,tipo TEXT NOT NULL,documento_id INTEGER NOT NULL,medico_id INTEGER NOT NULL,
 versao INTEGER NOT NULL,conteudo TEXT NOT NULL,criado_em TEXT DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(medico_id) REFERENCES medicos(id)
)");

foreach([
 ['pacientes','alergias','TEXT'],['pacientes','medicamentos','TEXT'],['pacientes','antecedentes','TEXT'],
 ['atestados','titulo','TEXT'],['atestados','conteudo_personalizado','TEXT'],['atestados','inicio_afastamento','TEXT'],
 ['atestados','fim_afastamento','TEXT'],['atestados','dias_afastamento','INTEGER'],['atestados','atividade_habitual','TEXT'],
 ['atestados','incapacidade_restricao','TEXT'],['atestados','modelo_id','INTEGER'],
 ['laudos','titulo','TEXT'],['laudos','conteudo_personalizado','TEXT'],['laudos','atividade_habitual','TEXT'],
 ['laudos','periodo_estimado','TEXT'],['laudos','incapacidade_restricao','TEXT'],['laudos','modelo_id','INTEGER'],
 ['cids','fonte','TEXT'],['cids','versao','TEXT']
] as $c) ensure_column($c[0],$c[1],$c[2]);

function csrf_token(){ if(empty($_SESSION['csrf_token']))$_SESSION['csrf_token']=bin2hex(random_bytes(32)); return $_SESSION['csrf_token']; }
function require_csrf(){
    $token=$_POST['csrf']??'';
    if(!is_string($token)||$token===''||empty($_SESSION['csrf_token'])||!hash_equals($_SESSION['csrf_token'],$token)){
        http_response_code(400); exit('Ação inválida (CSRF). Recarregue a página e tente novamente.');
    }
}
function unique_code($p){ return $p.'-'.date('Y').'-'.strtoupper(bin2hex(random_bytes(5))); }
function current_medico(){
    if(empty($_SESSION['medico_id']))return null;
    $s=db()->prepare('SELECT * FROM medicos WHERE id=?');$s->execute([$_SESSION['medico_id']]);return $s->fetch()?:null;
}
function require_login(){ if(!current_medico())redirect_to('login/'); }
function patient_for_doctor($id,$doctor){$s=db()->prepare('SELECT * FROM pacientes WHERE id=? AND medico_id=?');$s->execute([$id,$doctor]);return $s->fetch()?:null;}
function cid_find($q,$limit=30){$q=trim($q);if($q==='')return[];$s=db()->prepare('SELECT codigo,descricao FROM cids WHERE codigo LIKE ? OR descricao LIKE ? ORDER BY codigo LIMIT ?');$x='%'.$q.'%';$s->execute([$x,$x,(int)$limit]);return $s->fetchAll();}
function audit_log($acao,$tabela=null,$registro_id=null,$detalhes=null){$s=db()->prepare("INSERT INTO auditoria(usuario_id,data_hora,acao,tabela,registro_id,detalhes) VALUES(?,datetime('now'),?,?,?,?)");$s->execute([$_SESSION['medico_id']??null,$acao,$tabela,$registro_id,$detalhes===null?null:json_encode($detalhes,JSON_UNESCAPED_UNICODE)]);}
function render_header($title){
    $m=current_medico(); ?><!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title><?=e($title)?> · Sistema Médico</title>
    <style>
    :root{--primary:#1456a0;--dark:#0f2742;--bg:#f4f7fb;--line:#d9e2ec;--text:#172033;--ok:#16794b}
    *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:15px system-ui,-apple-system,Segoe UI,Arial}
    .top{background:var(--dark);color:#fff;padding:16px 22px;display:flex;justify-content:space-between;gap:16px;align-items:center}.top a{color:#fff}
    .nav{background:#fff;border-bottom:1px solid var(--line);padding:11px 22px;display:flex;gap:18px;flex-wrap:wrap}.nav a{color:#24445f;text-decoration:none;font-weight:600}
    .wrap{max-width:1180px;margin:24px auto;padding:0 16px}.card{background:#fff;border:1px solid var(--line);border-radius:14px;padding:20px;margin-bottom:18px}
    .grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}.grid3{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px}
    label{font-weight:700;display:block;margin:7px 0}input,select,textarea{width:100%;padding:11px;border:1px solid #cbd6e2;border-radius:9px;font:inherit;background:#fff}textarea{min-height:110px}
    .btn{display:inline-block;background:var(--primary);color:#fff;border:0;border-radius:9px;padding:11px 16px;text-decoration:none;cursor:pointer;font-weight:700}.btn.ok{background:var(--ok)}.btn.secondary{background:#64748b}
    .error{background:#fff0f0;color:#9c1c1c;border:1px solid #f2c2c2;padding:12px;border-radius:9px}.success{background:#effaf4;color:#16643f;border:1px solid #bce5cf;padding:12px;border-radius:9px}
    table{width:100%;border-collapse:collapse;background:#fff}th,td{padding:11px;border-bottom:1px solid var(--line);text-align:left}.muted{color:#64748b}.toolbar{display:flex;gap:10px;flex-wrap:wrap;align-items:center}
    .cid-results{border:1px solid #ccd6e1;max-height:220px;overflow:auto}.cid-results button{display:block;width:100%;text-align:left;padding:10px;border:0;border-bottom:1px solid #eee;background:#fff;cursor:pointer}
    @media(max-width:760px){.grid,.grid3{grid-template-columns:1fr}.top{flex-direction:column;align-items:flex-start}}
    </style></head><body><div class="top"><strong>🏥 Sistema Médico</strong><span><?=e($m['nome_completo']??'')?> · <a href="<?=e(medical_url('login/logout.php'))?>">Sair</a></span></div>
    <nav class="nav"><a href="<?=e(medical_url(''))?>">Início</a><a href="<?=e(medical_url('pacientes/'))?>">Pacientes</a><a href="<?=e(medical_url('atendimentos/'))?>">Atendimentos</a><a href="<?=e(medical_url('atestados/'))?>">Atestados</a><a href="<?=e(medical_url('laudos/'))?>">Laudos</a><a href="<?=e(medical_url('cid/'))?>">CID-10</a><a href="<?=e(medical_url('modelos/'))?>">Modelos</a><a href="<?=e(medical_url('ia/'))?>">IA de apoio</a><a href="<?=e(medical_url('configuracoes/'))?>">Configurações</a></nav><main class="wrap"><?php
}
function render_footer(){echo'</main></body></html>';}

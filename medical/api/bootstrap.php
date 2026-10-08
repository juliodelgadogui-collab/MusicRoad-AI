<?php
session_name('MEDICAL_SESSION');
$secure=(!empty($_SERVER['HTTPS'])&&$_SERVER['HTTPS']!=='off');
session_set_cookie_params(['httponly'=>true,'secure'=>$secure,'samesite'=>'Lax']);
session_start();

$db_path = __DIR__ . '/../database/medical.sqlite';
$db_exists = file_exists($db_path);
$db = new PDO('sqlite:' . $db_path);
$db->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
$db->setAttribute(PDO::ATTR_DEFAULT_FETCH_MODE, PDO::FETCH_ASSOC);
$db->exec('PRAGMA foreign_keys=ON');
$db->exec('PRAGMA journal_mode=WAL');

if (!$db_exists) {
    $db->exec('CREATE TABLE IF NOT EXISTS medicos (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        nome_completo TEXT,
        cpf TEXT,
        crm TEXT,
        uf TEXT,
        especialidade TEXT,
        rqe TEXT,
        telefone TEXT,
        email TEXT,
        endereco TEXT,
        logo_path TEXT,
        assinatura_path TEXT,
        senha_hash TEXT,
        is_admin INTEGER DEFAULT 0
    )');

    $db->exec('CREATE TABLE IF NOT EXISTS pacientes (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        medico_id INTEGER,
        nome TEXT,
        cpf TEXT,
        data_nascimento TEXT,
        sexo TEXT,
        telefone TEXT,
        email TEXT,
        endereco TEXT,
        profissao TEXT,
        funcao TEXT,
        empresa TEXT,
        info_clinica TEXT,
        FOREIGN KEY (medico_id) REFERENCES medicos(id)
    )');

    $db->exec('CREATE TABLE IF NOT EXISTS atendimentos (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        paciente_id INTEGER,
        medico_id INTEGER,
        data_atendimento TEXT,
        queixa_principal TEXT,
        historia_clinica TEXT,
        sintomas TEXT,
        duracao TEXT,
        evolucao TEXT,
        medicamentos TEXT,
        alergias TEXT,
        exame_fisico TEXT,
        exames_apresentados TEXT,
        tratamento TEXT,
        observacoes TEXT,
        capacidade_limitacoes TEXT,
        capacidade_restricoes TEXT,
        capacidade_atividades TEXT,
        capacidade_impacto TEXT,
        capacidade_obs TEXT,
        FOREIGN KEY (paciente_id) REFERENCES pacientes(id),
        FOREIGN KEY (medico_id) REFERENCES medicos(id)
    )');

    $db->exec('CREATE TABLE IF NOT EXISTS atestados (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        paciente_id INTEGER,
        medico_id INTEGER,
        atendimento_id INTEGER,
        codigo_validacao TEXT UNIQUE,
        data_emissao TEXT,
        cid_codigo TEXT,
        cid_descricao TEXT,
        periodo_afastamento TEXT,
        info_clinica TEXT,
        observacoes TEXT,
        status TEXT,
        pdf_path TEXT,
        cancelado_em TEXT,
        cancelado_motivo TEXT,
        FOREIGN KEY (paciente_id) REFERENCES pacientes(id),
        FOREIGN KEY (medico_id) REFERENCES medicos(id),
        FOREIGN KEY (atendimento_id) REFERENCES atendimentos(id)
    )');

    $db->exec('CREATE TABLE IF NOT EXISTS laudos (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        paciente_id INTEGER,
        medico_id INTEGER,
        atendimento_id INTEGER,
        codigo_validacao TEXT UNIQUE,
        data_emissao TEXT,
        historico_clinico TEXT,
        avaliacao TEXT,
        exames TEXT,
        cid_codigo TEXT,
        cid_descricao TEXT,
        repercussao TEXT,
        conclusao TEXT,
        status TEXT,
        pdf_path TEXT,
        cancelado_em TEXT,
        cancelado_motivo TEXT,
        FOREIGN KEY (paciente_id) REFERENCES pacientes(id),
        FOREIGN KEY (medico_id) REFERENCES medicos(id),
        FOREIGN KEY (atendimento_id) REFERENCES atendimentos(id)
    )');

    $db->exec('CREATE TABLE IF NOT EXISTS auditoria (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        usuario_id INTEGER,
        data_hora TEXT,
        acao TEXT,
        tabela TEXT,
        registro_id INTEGER,
        detalhes TEXT
    )');

    $db->exec('CREATE TABLE IF NOT EXISTS cids (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        codigo TEXT UNIQUE,
        descricao TEXT
    )');
} else {
    // Attempt migration to ensure 'medico_id' in pacientes table if missing.
    try {
        $db->exec('ALTER TABLE pacientes ADD COLUMN medico_id INTEGER REFERENCES medicos(id)');
    } catch(Exception $e) {}
    try {
        $db->exec('ALTER TABLE atestados ADD COLUMN cancelado_em TEXT');
        $db->exec('ALTER TABLE atestados ADD COLUMN cancelado_motivo TEXT');
    } catch(Exception $e) {}
    try {
        $db->exec('ALTER TABLE laudos ADD COLUMN cancelado_em TEXT');
        $db->exec('ALTER TABLE laudos ADD COLUMN cancelado_motivo TEXT');
    } catch(Exception $e) {}
}

function ensure_column($table,$column,$definition){$cols=db()->query('PRAGMA table_info('.$table.')')->fetchAll();foreach($cols as $c)if($c['name']===$column)return;db()->exec('ALTER TABLE '.$table.' ADD COLUMN '.$column.' '.$definition);}
foreach([['pacientes','alergias','TEXT'],['pacientes','medicamentos','TEXT'],['pacientes','antecedentes','TEXT'],['atestados','titulo','TEXT'],['atestados','conteudo_personalizado','TEXT'],['atestados','inicio_afastamento','TEXT'],['atestados','fim_afastamento','TEXT'],['atestados','dias_afastamento','INTEGER'],['atestados','atividade_habitual','TEXT'],['atestados','incapacidade_restricao','TEXT'],['laudos','titulo','TEXT'],['laudos','conteudo_personalizado','TEXT'],['laudos','atividade_habitual','TEXT'],['laudos','periodo_estimado','TEXT'],['laudos','incapacidade_restricao','TEXT'],['cids','fonte','TEXT'],['cids','versao','TEXT']] as $c)ensure_column($c[0],$c[1],$c[2]);
$db->exec("CREATE TABLE IF NOT EXISTS modelos_documento(id INTEGER PRIMARY KEY AUTOINCREMENT,medico_id INTEGER NOT NULL,tipo TEXT NOT NULL,nome TEXT NOT NULL,conteudo TEXT NOT NULL,ativo INTEGER DEFAULT 1,created_at TEXT DEFAULT CURRENT_TIMESTAMP,updated_at TEXT DEFAULT CURRENT_TIMESTAMP)");

function e($v){return htmlspecialchars((string)$v,ENT_QUOTES,'UTF-8');}
function unique_code($p){return $p.'-'.date('Y').'-'.strtoupper(bin2hex(random_bytes(5)));}
function patient_for_doctor($id,$doctor){$q=db()->prepare('SELECT * FROM pacientes WHERE id=? AND medico_id=?');$q->execute([$id,$doctor]);return $q->fetch()?:null;}
function cid_find($q,$limit=30){$q=trim($q);if($q==='')return[];$s=db()->prepare('SELECT codigo,descricao FROM cids WHERE codigo LIKE ? OR descricao LIKE ? ORDER BY codigo LIMIT ?');$l='%'.$q.'%';$s->execute([$l,$l,$limit]);return $s->fetchAll();}
function render_header($title){$m=current_medico();?><!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title><?=e($title)?> · Sistema Médico</title><style>body{font:15px Arial;margin:0;background:#f4f7fb;color:#172033}.top{background:#102a43;color:#fff;padding:16px 22px;display:flex;justify-content:space-between}.nav{background:#fff;padding:11px 22px;border-bottom:1px solid #dbe3ee;display:flex;gap:16px;flex-wrap:wrap}.nav a{color:#24445f;text-decoration:none}.wrap{max-width:1150px;margin:24px auto;padding:0 16px}.card{background:#fff;border:1px solid #dbe3ee;border-radius:14px;padding:20px;margin-bottom:18px}.grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}label{font-weight:700;display:block;margin:6px 0}input,select,textarea{width:100%;box-sizing:border-box;padding:11px;border:1px solid #cbd6e2;border-radius:9px;font:inherit}textarea{min-height:110px}.btn{display:inline-block;background:#1565c0;color:#fff;border:0;border-radius:9px;padding:11px 16px;text-decoration:none;cursor:pointer;font-weight:700}.btn.ok{background:#16845b}.muted{color:#64748b}.error{background:#fff0f0;color:#a61b1b;padding:12px;border-radius:9px}.cid-results{border:1px solid #ccd6e1;max-height:220px;overflow:auto}.cid-results button{display:block;width:100%;text-align:left;padding:10px;border:0;border-bottom:1px solid #eee;background:#fff}@media(max-width:700px){.grid{grid-template-columns:1fr}.top{flex-direction:column;gap:8px}}</style></head><body><div class="top"><strong>🏥 Sistema Médico</strong><span><?=e($m['nome_completo']??'')?> · <a style="color:#fff" href="/medical/login/logout.php">Sair</a></span></div><div class="nav"><a href="/medical/">Início</a><a href="/medical/pacientes/index.php">Pacientes</a><a href="/medical/atendimentos/index.php">Atendimentos</a><a href="/medical/atestados/index.php">Atestados</a><a href="/medical/laudos/index.php">Laudos</a><a href="/medical/cid/index.php">CID-10</a><a href="/medical/configuracoes/index.php">Configurações</a></div><main class="wrap"><?php}
function render_footer(){echo'</main></body></html>';}
function db() {
    global $db;
    return $db;
}

function csrf_token() {
    if (empty($_SESSION['csrf_token'])) {
        $_SESSION['csrf_token'] = bin2hex(random_bytes(32));
    }
    return $_SESSION['csrf_token'];
}

function require_csrf() {
    $token = $_POST['csrf'] ?? '';
    if (!hash_equals(csrf_token(), $token)) {
        die('Ação inválida (CSRF Token incorreto). Volte e tente novamente.');
    }
}

function audit_log($acao, $tabela = null, $registro_id = null, $detalhes = null) {
    $uid = $_SESSION['medico_id'] ?? null;
    $stmt = db()->prepare("INSERT INTO auditoria (usuario_id, data_hora, acao, tabela, registro_id, detalhes) VALUES (?, datetime('now'), ?, ?, ?, ?)");
    $stmt->execute([$uid, $acao, $tabela, $registro_id, json_encode($detalhes)]);
}

function current_medico() {
    if (isset($_SESSION['medico_id'])) {
        $stmt = db()->prepare('SELECT * FROM medicos WHERE id = ?');
        $stmt->execute([$_SESSION['medico_id']]);
        return $stmt->fetch();
    }
    return null;
}

function require_login() {
    if (!current_medico()) {
        header('Location: /medical/login/index.php');
        die();
    }
}

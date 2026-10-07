<?php
session_name('MEDICAL_SESSION');
session_start();

$db_path = __DIR__ . '/../database/medical.sqlite';
$db_exists = file_exists($db_path);
$db = new PDO('sqlite:' . $db_path);
$db->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
$db->setAttribute(PDO::ATTR_DEFAULT_FETCH_MODE, PDO::FETCH_ASSOC);

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

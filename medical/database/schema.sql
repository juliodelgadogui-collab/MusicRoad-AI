PRAGMA foreign_keys=ON;
CREATE TABLE IF NOT EXISTS usuarios (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 nome TEXT NOT NULL,
 email TEXT NOT NULL UNIQUE,
 senha_hash TEXT NOT NULL,
 perfil TEXT NOT NULL DEFAULT 'medico',
 ativo INTEGER NOT NULL DEFAULT 1,
 criado_em TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS medicos (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 usuario_id INTEGER NOT NULL UNIQUE,
 nome TEXT NOT NULL, cpf TEXT, crm TEXT NOT NULL, uf TEXT NOT NULL,
 especialidade TEXT, rqe TEXT, telefone TEXT, email TEXT, endereco TEXT,
 logo_path TEXT, assinatura_path TEXT, criado_em TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(usuario_id) REFERENCES usuarios(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS pacientes (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 nome TEXT NOT NULL, cpf TEXT, nascimento TEXT, sexo TEXT, telefone TEXT,
 email TEXT, endereco TEXT, profissao TEXT, funcao TEXT, empresa TEXT,
 informacoes_clinicas TEXT, criado_em TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
 atualizado_em TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS atendimentos (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 paciente_id INTEGER NOT NULL, medico_id INTEGER NOT NULL, data_atendimento TEXT NOT NULL,
 queixa TEXT, historia TEXT, sintomas TEXT, duracao TEXT, evolucao TEXT, medicamentos TEXT,
 alergias TEXT, exame_fisico TEXT, exames TEXT, tratamento TEXT, observacoes TEXT,
 limitacoes TEXT, restricoes TEXT, atividades_afetadas TEXT, impacto_atividade TEXT,
 capacidade_laboral TEXT, criado_em TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(paciente_id) REFERENCES pacientes(id), FOREIGN KEY(medico_id) REFERENCES medicos(id)
);
CREATE TABLE IF NOT EXISTS cids (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 codigo TEXT NOT NULL, descricao TEXT NOT NULL, versao TEXT NOT NULL DEFAULT 'base',
 UNIQUE(codigo,versao)
);
CREATE TABLE IF NOT EXISTS documentos (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 uuid TEXT NOT NULL UNIQUE, numero TEXT NOT NULL UNIQUE, tipo TEXT NOT NULL,
 paciente_id INTEGER NOT NULL, medico_id INTEGER NOT NULL, atendimento_id INTEGER,
 diagnostico TEXT, cid_id INTEGER, periodo_inicio TEXT, periodo_fim TEXT,
 conteudo TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'rascunho',
 token_validacao TEXT NOT NULL UNIQUE, emitido_em TEXT, cancelado_em TEXT,
 criado_em TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, atualizado_em TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(paciente_id) REFERENCES pacientes(id), FOREIGN KEY(medico_id) REFERENCES medicos(id),
 FOREIGN KEY(atendimento_id) REFERENCES atendimentos(id), FOREIGN KEY(cid_id) REFERENCES cids(id)
);
CREATE TABLE IF NOT EXISTS assinaturas (
 id INTEGER PRIMARY KEY AUTOINCREMENT, documento_id INTEGER NOT NULL, medico_id INTEGER NOT NULL,
 confirmado INTEGER NOT NULL DEFAULT 0, ip TEXT, confirmado_em TEXT,
 FOREIGN KEY(documento_id) REFERENCES documentos(id), FOREIGN KEY(medico_id) REFERENCES medicos(id)
);
CREATE TABLE IF NOT EXISTS auditoria (
 id INTEGER PRIMARY KEY AUTOINCREMENT, usuario_id INTEGER, acao TEXT NOT NULL,
 entidade TEXT, entidade_id INTEGER, paciente_id INTEGER, detalhes TEXT, ip TEXT,
 criado_em TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(usuario_id) REFERENCES usuarios(id)
);
CREATE TABLE IF NOT EXISTS configuracoes (
 chave TEXT PRIMARY KEY, valor TEXT, atualizado_em TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_pacientes_nome ON pacientes(nome);
CREATE INDEX IF NOT EXISTS idx_atendimentos_paciente ON atendimentos(paciente_id);
CREATE INDEX IF NOT EXISTS idx_documentos_token ON documentos(token_validacao);
CREATE INDEX IF NOT EXISTS idx_auditoria_data ON auditoria(criado_em);

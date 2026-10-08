SISTEMA MÉDICO — VERSÃO FINAL DE HARDENING

Módulo isolado em /medical. O restante do MusicRoad-AI permanece preservado.

Correções desta etapa:
- Isolamento das listagens de atestados e laudos por médico.
- Migração automática das colunas fonte/versão do CID.
- Gerador local de QR Code incluído em medical/lib/qrcode.php.
- QR aponta para a validação pública do documento.
- Assinatura armazenada em storage continua protegida por .htaccess e passa a ser servida por endpoint controlado.
- Validação pública não expõe dados clínicos do paciente.
- Cancelamento invalida o status do documento e registra auditoria.

Fluxo:
Paciente -> Atendimento -> Médico revisa -> CID/conclusão -> confirmação -> emissão -> QR -> validação.

Observação:
A estrutura do documento pode apoiar documentação clínica/previdenciária, mas não garante concessão de benefício pelo INSS. A decisão previdenciária é do órgão competente.

<?php
require_once __DIR__ . '/../api/bootstrap.php';
require_once __DIR__ . '/../libs/tcpdf/tcpdf.php';
require_login();

$medico = current_medico();
$id = (int)($_GET['id'] ?? 0);

$stmt = db()->prepare('
    SELECT a.*, p.nome as paciente_nome, p.cpf as paciente_cpf
    FROM atestados a
    JOIN pacientes p ON a.paciente_id = p.id
    WHERE a.id = ? AND a.medico_id = ?
');
$stmt->execute([$id, $medico['id']]);
$atestado = $stmt->fetch();

if (!$atestado) {
    die('Atestado não encontrado ou acesso negado.');
}

class AtestadoPDF extends TCPDF {
    public function Header() {
        $this->SetFont('helvetica', 'B', 20);
        $this->Cell(0, 15, 'ATESTADO MÉDICO', 0, false, 'C', 0, '', 0, false, 'M', 'M');
    }
    public function Footer() {
        $this->SetY(-15);
        $this->SetFont('helvetica', 'I', 8);
        $this->Cell(0, 10, 'Página '.$this->getAliasNumPage().'/'.$this->getAliasNbPages(), 0, false, 'C', 0, '', 0, false, 'T', 'M');
    }
}

$pdf = new AtestadoPDF(PDF_PAGE_ORIENTATION, PDF_UNIT, PDF_PAGE_FORMAT, true, 'UTF-8', false);
$pdf->SetCreator(PDF_CREATOR);
$pdf->SetAuthor($medico['nome_completo']);
$pdf->SetTitle('Atestado Médico - ' . $atestado['paciente_nome']);
$pdf->SetMargins(PDF_MARGIN_LEFT, PDF_MARGIN_TOP, PDF_MARGIN_RIGHT);
$pdf->SetHeaderMargin(PDF_MARGIN_HEADER);
$pdf->SetFooterMargin(PDF_MARGIN_FOOTER);
$pdf->SetAutoPageBreak(TRUE, PDF_MARGIN_BOTTOM);
$pdf->AddPage();
$pdf->SetFont('helvetica', '', 12);

$html = '
<br><br>
<p>Atesto para os devidos fins que o(a) paciente <strong>' . htmlspecialchars($atestado['paciente_nome']) . '</strong>, portador(a) do CPF ' . htmlspecialchars($atestado['paciente_cpf'] ?? 'não informado') . ', foi submetido(a) a avaliação médica nesta data.</p>
<p>Necessita de <strong>' . htmlspecialchars($atestado['periodo_afastamento']) . '</strong> de afastamento de suas atividades laborais/escolares.</p>
';

if (!empty($atestado['cid_codigo'])) {
    $html .= '<p><strong>CID-10:</strong> ' . htmlspecialchars($atestado['cid_codigo']);
    if (!empty($atestado['cid_descricao'])) {
        $html .= ' - ' . htmlspecialchars($atestado['cid_descricao']);
    }
    $html .= '</p>';
}

if (!empty($atestado['info_clinica'])) {
    $html .= '<p><strong>Informações Clínicas:</strong> ' . nl2br(htmlspecialchars($atestado['info_clinica'])) . '</p>';
}

if (!empty($atestado['observacoes'])) {
    $html .= '<p><strong>Observações:</strong> ' . nl2br(htmlspecialchars($atestado['observacoes'])) . '</p>';
}

$html .= '<br><br><br><p style="text-align: center;">';
$html .= date('d/m/Y', strtotime($atestado['data_emissao'])) . '<br><br><br>';

if (!empty($medico['assinatura_path']) && file_exists(__DIR__ . '/../' . $medico['assinatura_path'])) {
    $html .= '<img src="../' . $medico['assinatura_path'] . '" height="80"><br>';
}

$html .= '________________________________________________<br>';
$html .= '<strong>' . htmlspecialchars($medico['nome_completo']) . '</strong><br>';
$html .= 'CRM ' . htmlspecialchars($medico['crm'] . '/' . $medico['uf']) . '<br>';
$html .= '</p>';

$pdf->writeHTML($html, true, false, true, false, '');

// QR CODE
$style = array(
    'border' => 2,
    'vpadding' => 'auto',
    'hpadding' => 'auto',
    'fgcolor' => array(0,0,0),
    'bgcolor' => false,
    'module_width' => 1,
    'module_height' => 1
);
$url = 'https://' . $_SERVER['HTTP_HOST'] . '/medical/validar.php?id=' . $atestado['codigo_validacao'];
$pdf->write2DBarcode($url, 'QRCODE,H', 20, 220, 35, 35, $style, 'N');
$pdf->SetXY(20, 255);
$pdf->SetFont('helvetica', '', 8);
$pdf->Cell(35, 5, 'Validação QR Code', 0, 1, 'C');

$pdf->SetXY(60, 220);
$pdf->Cell(0, 5, 'Código de Validação: ' . $atestado['codigo_validacao'], 0, 1, 'L');
if ($atestado['status'] === 'CANCELADO') {
    $pdf->SetTextColor(255, 0, 0);
    $pdf->SetFont('helvetica', 'B', 16);
    $pdf->Cell(0, 10, 'DOCUMENTO CANCELADO', 0, 1, 'L');
}

$pdf->Output('Atestado_' . $atestado['codigo_validacao'] . '.pdf', 'I');

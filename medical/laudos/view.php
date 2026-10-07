<?php
require_once __DIR__ . '/../api/bootstrap.php';
require_once __DIR__ . '/../libs/tcpdf/tcpdf.php';
require_login();

$medico = current_medico();
$id = (int)($_GET['id'] ?? 0);

$stmt = db()->prepare('
    SELECT l.*, p.nome as paciente_nome, p.cpf as paciente_cpf
    FROM laudos l
    JOIN pacientes p ON l.paciente_id = p.id
    WHERE l.id = ? AND l.medico_id = ?
');
$stmt->execute([$id, $medico['id']]);
$laudo = $stmt->fetch();

if (!$laudo) {
    die('Laudo não encontrado ou acesso negado.');
}

class LaudoPDF extends TCPDF {
    public function Header() {
        $this->SetFont('helvetica', 'B', 20);
        $this->Cell(0, 15, 'LAUDO MÉDICO', 0, false, 'C', 0, '', 0, false, 'M', 'M');
    }
    public function Footer() {
        $this->SetY(-15);
        $this->SetFont('helvetica', 'I', 8);
        $this->Cell(0, 10, 'Página '.$this->getAliasNumPage().'/'.$this->getAliasNbPages(), 0, false, 'C', 0, '', 0, false, 'T', 'M');
    }
}

$pdf = new LaudoPDF(PDF_PAGE_ORIENTATION, PDF_UNIT, PDF_PAGE_FORMAT, true, 'UTF-8', false);
$pdf->SetCreator(PDF_CREATOR);
$pdf->SetAuthor($medico['nome_completo']);
$pdf->SetTitle('Laudo Médico - ' . $laudo['paciente_nome']);
$pdf->SetMargins(PDF_MARGIN_LEFT, PDF_MARGIN_TOP, PDF_MARGIN_RIGHT);
$pdf->SetHeaderMargin(PDF_MARGIN_HEADER);
$pdf->SetFooterMargin(PDF_MARGIN_FOOTER);
$pdf->SetAutoPageBreak(TRUE, PDF_MARGIN_BOTTOM);
$pdf->AddPage();
$pdf->SetFont('helvetica', '', 11);

$html = '<br><br>
<h4>IDENTIFICAÇÃO</h4>
<p><strong>Paciente:</strong> ' . htmlspecialchars($laudo['paciente_nome']) . '<br>
<strong>CPF:</strong> ' . htmlspecialchars($laudo['paciente_cpf'] ?? 'Não informado') . '<br>
<strong>Data da Avaliação:</strong> ' . date('d/m/Y', strtotime($laudo['data_emissao'])) . '</p>';

if (!empty($laudo['historico_clinico'])) {
    $html .= '<h4>HISTÓRIA CLÍNICA</h4><p>' . nl2br(htmlspecialchars($laudo['historico_clinico'])) . '</p>';
}

if (!empty($laudo['avaliacao'])) {
    $html .= '<h4>AVALIAÇÃO / EXAME FÍSICO</h4><p>' . nl2br(htmlspecialchars($laudo['avaliacao'])) . '</p>';
}

if (!empty($laudo['exames'])) {
    $html .= '<h4>EXAMES COMPLEMENTARES</h4><p>' . nl2br(htmlspecialchars($laudo['exames'])) . '</p>';
}

if (!empty($laudo['cid_codigo'])) {
    $html .= '<h4>DIAGNÓSTICO</h4><p><strong>CID-10:</strong> ' . htmlspecialchars($laudo['cid_codigo']) . ' - ' . htmlspecialchars($laudo['cid_descricao']) . '</p>';
}

if (!empty($laudo['repercussao'])) {
    $html .= '<h4>REPERCUSSÃO FUNCIONAL</h4><p>' . nl2br(htmlspecialchars($laudo['repercussao'])) . '</p>';
}

$html .= '<h4>CONCLUSÃO MÉDICA</h4><p>' . nl2br(htmlspecialchars($laudo['conclusao'])) . '</p>';

$html .= '<br><br><p style="text-align: center;">';
$html .= date('d/m/Y', strtotime($laudo['data_emissao'])) . '<br><br><br>';

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
$url = 'https://' . $_SERVER['HTTP_HOST'] . '/medical/validar.php?id=' . $laudo['codigo_validacao'];
$y = $pdf->GetY();
if ($y > 200) {
    $pdf->AddPage();
    $y = 30;
}
$pdf->write2DBarcode($url, 'QRCODE,H', 20, $y, 35, 35, $style, 'N');
$pdf->SetXY(20, $y + 35);
$pdf->SetFont('helvetica', '', 8);
$pdf->Cell(35, 5, 'Validação QR Code', 0, 1, 'C');

$pdf->SetXY(60, $y);
$pdf->Cell(0, 5, 'Código de Validação: ' . $laudo['codigo_validacao'], 0, 1, 'L');
if ($laudo['status'] === 'CANCELADO') {
    $pdf->SetTextColor(255, 0, 0);
    $pdf->SetFont('helvetica', 'B', 16);
    $pdf->Cell(0, 10, 'DOCUMENTO CANCELADO', 0, 1, 'L');
}

$pdf->Output('Laudo_' . $laudo['codigo_validacao'] . '.pdf', 'I');

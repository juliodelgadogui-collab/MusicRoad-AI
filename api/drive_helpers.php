<?php
declare(strict_types=1);

/**
 * Utilitários para transformar um arquivo público do Google Drive em um fluxo
 * de bytes reproduzível. Não usa Google API Key.
 */

function drive_is_binary_content_type(string $contentType): bool
{
    $contentType = strtolower(trim(explode(';', $contentType, 2)[0] ?? ''));
    if ($contentType === '') {
        return false;
    }
    return !in_array($contentType, [
        'text/html',
        'text/plain',
        'application/xhtml+xml',
        'application/json',
    ], true);
}

function drive_public_candidates(string $fileId): array
{
    $id = rawurlencode($fileId);
    return [
        'https://drive.usercontent.google.com/download?id=' . $id . '&export=download&authuser=0&confirm=t',
        'https://drive.google.com/uc?export=download&confirm=t&id=' . $id,
        'https://drive.google.com/uc?export=download&id=' . $id,
    ];
}

function drive_curl_probe(string $url, bool $head = true): array
{
    if (!function_exists('curl_init')) {
        return ['ok' => false, 'status_code' => 0, 'content_type' => '', 'effective_url' => $url, 'body' => '', 'headers' => []];
    }

    $ch = curl_init($url);
    $body = '';
    $responseHeaders = [];
    curl_setopt_array($ch, [
        CURLOPT_FOLLOWLOCATION => true,
        CURLOPT_RETURNTRANSFER => !$head,
        CURLOPT_NOBODY => $head,
        CURLOPT_CONNECTTIMEOUT => 10,
        CURLOPT_TIMEOUT => 25,
        CURLOPT_ENCODING => '',
        CURLOPT_HTTPHEADER => [
            'User-Agent: Mozilla/5.0 MusicRoadAI/2.0',
            'Accept: */*',
        ],
        CURLOPT_HEADERFUNCTION => static function ($ch, string $line) use (&$responseHeaders): int {
            $trimmed = trim($line);
            if (str_starts_with($trimmed, 'HTTP/')) {
                $responseHeaders = [];
            } elseif (str_contains($trimmed, ':')) {
                [$name, $value] = array_map('trim', explode(':', $trimmed, 2));
                $responseHeaders[strtolower($name)] = $value;
            }
            return strlen($line);
        },
    ]);

    if (!$head) {
        // Evita baixar um arquivo inteiro durante a resolução. Se for binário,
        // 128 KB já são suficientes para confirmar que existe um fluxo real.
        curl_setopt($ch, CURLOPT_RANGE, '0-131071');
        curl_setopt($ch, CURLOPT_WRITEFUNCTION, static function ($ch, string $chunk) use (&$body): int {
            $remaining = 262144 - strlen($body);
            if ($remaining <= 0) {
                return 0;
            }
            $body .= substr($chunk, 0, $remaining);
            return strlen($chunk);
        });
    }

    $result = curl_exec($ch);
    $errno = curl_errno($ch);
    $status = (int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
    $contentType = (string)curl_getinfo($ch, CURLINFO_CONTENT_TYPE);
    $effectiveUrl = (string)curl_getinfo($ch, CURLINFO_EFFECTIVE_URL);
    curl_close($ch);

    // CURLE_WRITE_ERROR é esperado quando a sonda corta o corpo após o limite.
    $ok = $status >= 200 && $status < 400 && ($errno === 0 || (!$head && $errno === CURLE_WRITE_ERROR));

    return [
        'ok' => $ok,
        'status_code' => $status,
        'content_type' => $contentType ?: ($responseHeaders['content-type'] ?? ''),
        'effective_url' => $effectiveUrl ?: $url,
        'body' => $body,
        'headers' => $responseHeaders,
        'curl_errno' => $errno,
    ];
}

function drive_extract_confirm_url(string $html, string $baseUrl): ?string
{
    if ($html === '') {
        return null;
    }

    // Formulário atual da página de confirmação do Google Drive.
    if (preg_match('~<form[^>]+(?:id="download-form"[^>]*|action="([^"]+)"[^>]*)>(.*?)</form>~is', $html, $form)) {
        $action = $form[1] ?? '';
        if ($action === '' && preg_match('~action="([^"]+)"~i', $form[0], $m)) {
            $action = html_entity_decode($m[1], ENT_QUOTES | ENT_HTML5, 'UTF-8');
        }
        if ($action !== '') {
            $params = [];
            preg_match_all('~<input[^>]+name="([^"]+)"[^>]+value="([^"]*)"~i', $form[2] ?? $form[0], $inputs, PREG_SET_ORDER);
            foreach ($inputs as $input) {
                $params[html_entity_decode($input[1], ENT_QUOTES | ENT_HTML5, 'UTF-8')] = html_entity_decode($input[2], ENT_QUOTES | ENT_HTML5, 'UTF-8');
            }
            $sep = str_contains($action, '?') ? '&' : '?';
            return $action . ($params ? $sep . http_build_query($params) : '');
        }
    }

    // Algumas variantes expõem o link diretamente no HTML/JSON.
    if (preg_match('~https://drive\.usercontent\.google\.com/download\?[^"\'<> ]+~i', html_entity_decode($html, ENT_QUOTES | ENT_HTML5, 'UTF-8'), $m)) {
        return str_replace('&amp;', '&', $m[0]);
    }
    if (preg_match('~href="([^"]*(?:uc\?export=download|drive\.usercontent\.google\.com/download)[^"]*)"~i', $html, $m)) {
        $url = html_entity_decode($m[1], ENT_QUOTES | ENT_HTML5, 'UTF-8');
        if (str_starts_with($url, '/')) {
            return 'https://drive.google.com' . $url;
        }
        return $url;
    }

    return null;
}

function drive_resolve_public_download(string $fileId): array
{
    if (!function_exists('curl_init')) {
        return [
            'ok' => true,
            'url' => 'https://drive.google.com/uc?export=download&id=' . rawurlencode($fileId),
            'status_code' => 302,
            'content_type' => '',
            'message' => 'cURL indisponível; usando redirecionamento direto do Google Drive.',
        ];
    }

    $last = null;
    foreach (drive_public_candidates($fileId) as $candidate) {
        $probe = drive_curl_probe($candidate, true);
        $last = $probe;
        if ($probe['ok'] && drive_is_binary_content_type((string)$probe['content_type'])) {
            return [
                'ok' => true,
                'url' => $probe['effective_url'],
                'status_code' => $probe['status_code'],
                'content_type' => $probe['content_type'],
                'message' => 'Fluxo público de áudio localizado.',
            ];
        }

        // HEAD do Drive nem sempre revela o tipo. Faz uma sonda GET parcial.
        $getProbe = drive_curl_probe($candidate, false);
        $last = $getProbe;
        if ($getProbe['ok'] && drive_is_binary_content_type((string)$getProbe['content_type'])) {
            return [
                'ok' => true,
                'url' => $getProbe['effective_url'],
                'status_code' => $getProbe['status_code'],
                'content_type' => $getProbe['content_type'],
                'message' => 'Fluxo público de áudio localizado.',
            ];
        }

        $confirmUrl = drive_extract_confirm_url((string)$getProbe['body'], (string)$getProbe['effective_url']);
        if ($confirmUrl) {
            $confirmed = drive_curl_probe($confirmUrl, true);
            if ($confirmed['ok'] && drive_is_binary_content_type((string)$confirmed['content_type'])) {
                return [
                    'ok' => true,
                    'url' => $confirmed['effective_url'],
                    'status_code' => $confirmed['status_code'],
                    'content_type' => $confirmed['content_type'],
                    'message' => 'Fluxo público confirmado pelo Google Drive.',
                ];
            }
            $confirmedGet = drive_curl_probe($confirmUrl, false);
            if ($confirmedGet['ok'] && drive_is_binary_content_type((string)$confirmedGet['content_type'])) {
                return [
                    'ok' => true,
                    'url' => $confirmedGet['effective_url'],
                    'status_code' => $confirmedGet['status_code'],
                    'content_type' => $confirmedGet['content_type'],
                    'message' => 'Fluxo público confirmado pelo Google Drive.',
                ];
            }
            $last = $confirmedGet;
        }
    }

    return [
        'ok' => false,
        'url' => null,
        'status_code' => (int)($last['status_code'] ?? 0),
        'content_type' => (string)($last['content_type'] ?? ''),
        'message' => 'O Google Drive abriu uma página HTML em vez do arquivo de áudio. Confirme que o arquivo e a pasta estão públicos para qualquer pessoa com o link.',
    ];
}

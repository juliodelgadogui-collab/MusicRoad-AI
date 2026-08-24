<?php
declare(strict_types=1);

/**
 * Google Drive public-media helpers for MusicRoad.
 *
 * - preserves resourcekey when Google requires it;
 * - probes only a small byte range;
 * - caches the resolved Google media URL for a short period so every play does
 *   not repeat the expensive Drive resolution flow.
 */

function drive_is_binary_content_type(string $contentType): bool
{
    $contentType = strtolower(trim(explode(';', $contentType, 2)[0] ?? ''));
    if ($contentType === '') return false;
    return !in_array($contentType, [
        'text/html', 'text/plain', 'application/xhtml+xml', 'application/json',
    ], true);
}

function drive_public_candidates(string $fileId, string $resourceKey = ''): array
{
    $id = rawurlencode($fileId);
    $rk = $resourceKey !== '' ? '&resourcekey=' . rawurlencode($resourceKey) : '';
    return [
        'https://drive.usercontent.google.com/download?id=' . $id . '&export=download&authuser=0&confirm=t' . $rk,
        'https://drive.google.com/uc?export=download&confirm=t&id=' . $id . $rk,
        'https://drive.google.com/uc?export=download&id=' . $id . $rk,
    ];
}

function drive_curl_probe(string $url, bool $head = true): array
{
    if (!function_exists('curl_init')) {
        return ['ok'=>false,'status_code'=>0,'content_type'=>'','effective_url'=>$url,'body'=>'','headers'=>[],'curl_errno'=>0];
    }
    $ch = curl_init($url);
    $body = '';
    $responseHeaders = [];
    curl_setopt_array($ch, [
        CURLOPT_FOLLOWLOCATION => true,
        CURLOPT_RETURNTRANSFER => !$head,
        CURLOPT_NOBODY => $head,
        CURLOPT_CONNECTTIMEOUT => 8,
        CURLOPT_TIMEOUT => 18,
        CURLOPT_ENCODING => 'identity',
        CURLOPT_HTTPHEADER => [
            'User-Agent: Mozilla/5.0 MusicRoadAI/2.2',
            'Accept: audio/*,application/octet-stream;q=0.9,*/*;q=0.8',
            'Accept-Encoding: identity',
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
        curl_setopt($ch, CURLOPT_RANGE, '0-65535');
        curl_setopt($ch, CURLOPT_WRITEFUNCTION, static function ($ch, string $chunk) use (&$body): int {
            $remaining = 131072 - strlen($body);
            if ($remaining <= 0) return 0;
            $body .= substr($chunk, 0, $remaining);
            return strlen($chunk);
        });
    }
    curl_exec($ch);
    $errno = curl_errno($ch);
    $status = (int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
    $contentType = (string)curl_getinfo($ch, CURLINFO_CONTENT_TYPE);
    $effectiveUrl = (string)curl_getinfo($ch, CURLINFO_EFFECTIVE_URL);
    curl_close($ch);
    $ok = $status >= 200 && $status < 400 && ($errno === 0 || (!$head && $errno === CURLE_WRITE_ERROR));
    return [
        'ok'=>$ok,
        'status_code'=>$status,
        'content_type'=>$contentType ?: ($responseHeaders['content-type'] ?? ''),
        'effective_url'=>$effectiveUrl ?: $url,
        'body'=>$body,
        'headers'=>$responseHeaders,
        'curl_errno'=>$errno,
    ];
}

function drive_extract_confirm_url(string $html, string $baseUrl): ?string
{
    if ($html === '') return null;
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
    $decoded = html_entity_decode($html, ENT_QUOTES | ENT_HTML5, 'UTF-8');
    if (preg_match('~https://drive\.usercontent\.google\.com/download\?[^"\'<> ]+~i', $decoded, $m)) {
        return str_replace('&amp;', '&', $m[0]);
    }
    if (preg_match('~href="([^"]*(?:uc\?export=download|drive\.usercontent\.google\.com/download)[^"]*)"~i', $html, $m)) {
        $url = html_entity_decode($m[1], ENT_QUOTES | ENT_HTML5, 'UTF-8');
        if (str_starts_with($url, '/')) return 'https://drive.google.com' . $url;
        return $url;
    }
    return null;
}

function drive_cache_dir(): ?string
{
    $root = dirname(__DIR__) . '/storage/cache/drive_stream';
    if (!is_dir($root) && !@mkdir($root, 0775, true) && !is_dir($root)) return null;
    return is_writable($root) ? $root : null;
}

function drive_cache_key(string $fileId, string $resourceKey): string
{
    return hash('sha256', $fileId . '|' . $resourceKey);
}

function drive_cached_resolution(string $fileId, string $resourceKey): ?array
{
    $dir = drive_cache_dir();
    if ($dir === null) return null;
    $file = $dir . '/' . drive_cache_key($fileId, $resourceKey) . '.json';
    if (!is_file($file) || time() - (int)@filemtime($file) > 600) return null;
    $data = json_decode((string)@file_get_contents($file), true);
    if (!is_array($data) || empty($data['url']) || !preg_match('~^https://(?:[^/]+\.)?(?:google\.com|googleusercontent\.com)/~i', (string)$data['url'])) return null;
    $data['ok'] = true;
    $data['cached'] = true;
    return $data;
}

function drive_store_resolution(string $fileId, string $resourceKey, array $resolved): void
{
    if (empty($resolved['ok']) || empty($resolved['url'])) return;
    $dir = drive_cache_dir();
    if ($dir === null) return;
    $payload = [
        'ok'=>true,
        'url'=>(string)$resolved['url'],
        'status_code'=>(int)($resolved['status_code'] ?? 200),
        'content_type'=>(string)($resolved['content_type'] ?? ''),
        'message'=>(string)($resolved['message'] ?? 'Fluxo em cache.'),
        'saved_at'=>time(),
    ];
    @file_put_contents($dir . '/' . drive_cache_key($fileId, $resourceKey) . '.json', json_encode($payload, JSON_UNESCAPED_SLASHES), LOCK_EX);
}

function drive_resolve_public_download(string $fileId, string $resourceKey = ''): array
{
    $cached = drive_cached_resolution($fileId, $resourceKey);
    if ($cached) return $cached;

    if (!function_exists('curl_init')) {
        return [
            'ok'=>true,
            'url'=>'https://drive.google.com/uc?export=download&id=' . rawurlencode($fileId) . ($resourceKey !== '' ? '&resourcekey=' . rawurlencode($resourceKey) : ''),
            'status_code'=>302,
            'content_type'=>'',
            'message'=>'cURL indisponível; usando redirecionamento direto do Google Drive.',
        ];
    }

    $last = null;
    foreach (drive_public_candidates($fileId, $resourceKey) as $candidate) {
        // A partial GET is usually faster and more reliable than HEAD + GET on Drive.
        $probe = drive_curl_probe($candidate, false);
        $last = $probe;
        if ($probe['ok'] && drive_is_binary_content_type((string)$probe['content_type'])) {
            $resolved = [
                'ok'=>true,
                'url'=>$probe['effective_url'] ?: $candidate,
                'status_code'=>$probe['status_code'],
                'content_type'=>$probe['content_type'],
                'message'=>'Fluxo público de áudio localizado.',
            ];
            drive_store_resolution($fileId, $resourceKey, $resolved);
            return $resolved;
        }

        $confirmUrl = drive_extract_confirm_url((string)$probe['body'], (string)$probe['effective_url']);
        if ($confirmUrl) {
            if ($resourceKey !== '' && !str_contains($confirmUrl, 'resourcekey=')) {
                $confirmUrl .= (str_contains($confirmUrl, '?') ? '&' : '?') . 'resourcekey=' . rawurlencode($resourceKey);
            }
            $confirmed = drive_curl_probe($confirmUrl, false);
            $last = $confirmed;
            if ($confirmed['ok'] && drive_is_binary_content_type((string)$confirmed['content_type'])) {
                $resolved = [
                    'ok'=>true,
                    'url'=>$confirmed['effective_url'] ?: $confirmUrl,
                    'status_code'=>$confirmed['status_code'],
                    'content_type'=>$confirmed['content_type'],
                    'message'=>'Fluxo público confirmado pelo Google Drive.',
                ];
                drive_store_resolution($fileId, $resourceKey, $resolved);
                return $resolved;
            }
        }
    }

    return [
        'ok'=>false,
        'url'=>null,
        'status_code'=>(int)($last['status_code'] ?? 0),
        'content_type'=>(string)($last['content_type'] ?? ''),
        'message'=>'O Google Drive não entregou o áudio. Confirme compartilhamento público; arquivos protegidos por resourcekey precisam manter essa chave na biblioteca.',
    ];
}

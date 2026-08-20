<?php
require __DIR__ . '/bootstrap.php';
require_login();

$action = strtolower(trim((string)($_GET['action'] ?? 'states')));
require_session_rate_limit('locations',120,60);
session_write_close();
$cacheDir = dirname(__DIR__) . '/storage/cache/locations';
if (!is_dir($cacheDir)) @mkdir($cacheDir, 0775, true);

function mr_location_cached_json(string $url, string $file, int $ttl = 2592000): ?array {
    $stale = null;
    if (is_file($file)) {
        $j = json_decode((string)@file_get_contents($file), true);
        if (is_array($j)) {
            if (filemtime($file) > time() - $ttl) return $j;
            $stale = $j;
        }
    }
    $j = http_json($url, null, [], 20);
    if (is_array($j)) {
        $encoded=json_encode($j,JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES);if(is_string($encoded))write_runtime_file($file,$encoded);
        return $j;
    }
    // Se o IBGE estiver temporariamente indisponível, nunca derruba uma lista já conhecida.
    return is_array($stale) ? $stale : null;
}

$states = [
 ['id'=>12,'sigla'=>'AC','nome'=>'Acre'],['id'=>27,'sigla'=>'AL','nome'=>'Alagoas'],['id'=>16,'sigla'=>'AP','nome'=>'Amapá'],['id'=>13,'sigla'=>'AM','nome'=>'Amazonas'],['id'=>29,'sigla'=>'BA','nome'=>'Bahia'],['id'=>23,'sigla'=>'CE','nome'=>'Ceará'],['id'=>53,'sigla'=>'DF','nome'=>'Distrito Federal'],['id'=>32,'sigla'=>'ES','nome'=>'Espírito Santo'],['id'=>52,'sigla'=>'GO','nome'=>'Goiás'],['id'=>21,'sigla'=>'MA','nome'=>'Maranhão'],['id'=>51,'sigla'=>'MT','nome'=>'Mato Grosso'],['id'=>50,'sigla'=>'MS','nome'=>'Mato Grosso do Sul'],['id'=>31,'sigla'=>'MG','nome'=>'Minas Gerais'],['id'=>15,'sigla'=>'PA','nome'=>'Pará'],['id'=>25,'sigla'=>'PB','nome'=>'Paraíba'],['id'=>41,'sigla'=>'PR','nome'=>'Paraná'],['id'=>26,'sigla'=>'PE','nome'=>'Pernambuco'],['id'=>22,'sigla'=>'PI','nome'=>'Piauí'],['id'=>33,'sigla'=>'RJ','nome'=>'Rio de Janeiro'],['id'=>24,'sigla'=>'RN','nome'=>'Rio Grande do Norte'],['id'=>43,'sigla'=>'RS','nome'=>'Rio Grande do Sul'],['id'=>11,'sigla'=>'RO','nome'=>'Rondônia'],['id'=>14,'sigla'=>'RR','nome'=>'Roraima'],['id'=>42,'sigla'=>'SC','nome'=>'Santa Catarina'],['id'=>35,'sigla'=>'SP','nome'=>'São Paulo'],['id'=>28,'sigla'=>'SE','nome'=>'Sergipe'],['id'=>17,'sigla'=>'TO','nome'=>'Tocantins']
];

if ($action === 'states') {
    json_response(['ok'=>true,'states'=>$states,'source'=>'IBGE/fallback-local']);
}

if ($action === 'municipalities') {
    $uf = strtoupper(trim((string)($_GET['uf'] ?? '')));
    $state = null;
    foreach ($states as $s) if ($s['sigla'] === $uf || (string)$s['id'] === $uf) { $state = $s; break; }
    if (!$state) json_response(['ok'=>false,'error'=>'Estado inválido.'],422);
    $file = $cacheDir . '/municipios-' . $state['sigla'] . '.json';
    $url = 'https://servicodados.ibge.gov.br/api/v1/localidades/estados/' . rawurlencode((string)$state['id']) . '/municipios?orderBy=nome';
    $rows = mr_location_cached_json($url, $file, 2592000);
    if (!$rows) json_response(['ok'=>false,'error'=>'Não foi possível carregar os municípios do IBGE. Tente novamente com internet.'],503);
    $out=[];
    foreach($rows as $r){ if(!isset($r['id'],$r['nome']))continue; $out[]=['id'=>(int)$r['id'],'nome'=>(string)$r['nome'],'uf'=>$state['sigla']]; }
    usort($out,fn($a,$b)=>strnatcasecmp($a['nome'],$b['nome']));
    json_response(['ok'=>true,'municipalities'=>$out,'state'=>$state,'source'=>'IBGE/cache-servidor']);
}

json_response(['ok'=>false,'error'=>'Ação inválida.'],422);

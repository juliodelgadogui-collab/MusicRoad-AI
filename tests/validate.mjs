import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';

const root=process.cwd();
const failures=[];
const read=file=>fs.readFileSync(path.join(root,file),'utf8');
const check=(condition,message)=>{if(!condition)failures.push(message)};
const contains=(file,pattern)=>pattern.test(read(file));

function walk(dir){
  const out=[];
  for(const entry of fs.readdirSync(path.join(root,dir),{withFileTypes:true})){
    const rel=path.join(dir,entry.name);
    if(entry.isDirectory()){
      if(['.git','node_modules','vendor','storage','logs'].includes(entry.name))continue;
      out.push(...walk(rel));
    }else out.push(rel);
  }
  return out;
}

const version=read('VERSION').trim();
check(version==='1.2.0','VERSION deve ser 1.2.0');
check(contains('api/bootstrap.php',/MUSICROAD_VERSION\s*=\s*'1\.2\.0'/),'bootstrap sem versão 1.2.0');
check(contains('android-app/app/build.gradle',/versionCode\s+26\b/),'versionCode Android deve ser 26');
check(contains('android-app/app/build.gradle',/versionName\s+'1\.2\.0'/),'versionName Android deve ser 1.2.0');

for(const file of ['api/radars.php','api/library.php','api/drive_stream.php','api/offline_state.php','api/route_radars.php']){
  check(contains(file,/require_(?:login|admin|client)\s*\(/),`${file} precisa de autenticação`);
}
for(const file of ['api/import_radars.php','api/google_drive_public.php','api/radar_sync_brazil.php']){
  check(contains(file,/require_admin\s*\(/),`${file} precisa ser administrativo`);
  check(contains(file,/require_csrf\s*\(/),`${file} precisa validar CSRF`);
}
check(contains('api/radars.php',/status[^\n]+PENDENTE/),'relato comunitário não está explicitamente pendente');
check(!contains('api/radars.php',/\$data\[['"]ativo['"]\]/),'API não deve aceitar ativo enviado pelo usuário');
check(contains('api/radars.php',/\$externalId\s*=\s*null/),'relato comunitário não deve controlar external_id');
check(contains('api/radars.php',/radar_coordinates_in_brazil\(\$lat,\$lon\)/),'relato comunitário precisa ficar limitado ao Brasil');
check(contains('api/route_radars.php',/require_csrf\s*\(/),'consulta de radares da rota precisa validar CSRF');
check(contains('api/device_auth.php',/action\s*===\s*['"]remove['"][\s\S]{0,180}require_csrf/),'remoção de dispositivo precisa validar CSRF');
check(contains('logout.php',/if\(\$method===['"]POST['"]\)require_csrf/),'logout por POST precisa validar CSRF');
for(const file of ['index.php','admin.php','horizontal/index.php']){
  check(contains(file,/<form[^>]+method=['"]post['"][^>]+action=['"][^'"]*logout\.php['"]/i),`${file} precisa encerrar sessão por formulário POST`);
}
check(contains('database/schema.mysql.sql',/reviewed_by/),'esquema sem campos de moderação');
check(contains('database/schema.mysql.sql',/expires_at/),'esquema sem expiração de alertas');
check(contains('api/bootstrap.php',/GET_LOCK\s*\(/),'migração sem bloqueio concorrente');
check(contains('api/bootstrap.php',/version_compare\(\$currentVersion,\s*MUSICROAD_SCHEMA_VERSION,\s*['"]>=['"]\)/),'bootstrap precisa evitar lock quando o esquema já está atualizado');
check(contains('install/index.php',/storage\/install\.token/),'instalador sem token privado de autorização');
check(contains('install/index.php',/install_authorized_at/),'autorização do instalador precisa expirar');
check(contains('install/index.php',/install_atomic_write/),'instalador precisa gravar configuração e lock de forma atômica');
check(contains('.htaccess',/install\\?\.token|install\.token/),'servidor Apache precisa bloquear o token de instalação');

const bootstrap=read('api/bootstrap.php');
const scriptPolicy=(bootstrap.match(/script-src[^;]+/)||[])[0]||'';
check(/nonce-\{\$cspNonce\}/.test(scriptPolicy),'CSP de scripts precisa usar nonce por requisição');
check(!/unsafe-inline/.test(scriptPolicy),'CSP de scripts não pode liberar unsafe-inline');
check(contains('install/index.php',/script-src 'none'/),'instalador sem CSP restritiva');
for(const file of ['admin.php','index.php','horizontal/index.php','license.php','login.php','register.php']){
  const source=read(file);
  for(const match of source.matchAll(/<script(?![^>]*\bsrc=)([^>]*)>/gi)){
    check(/\bnonce=/.test(match[1]),`${file} tem script inline sem nonce CSP`);
  }
  check(!/\s(?:onclick|onsubmit|onload|onerror)\s*=/i.test(source),`${file} tem evento HTML inline`);
}

const sw=read('sw.js');
check(!/LAST_APP|last-auth|navigation-fallback/i.test(sw),'service worker não deve guardar página autenticada global');
check(!/unpkg\.com|cdn\.jsdelivr\.net/i.test(read('index.php')+read('horizontal/index.php')+sw),'Leaflet remoto voltou ao shell offline');
check(fs.existsSync(path.join(root,'assets/vendor/leaflet/leaflet.js')),'Leaflet local ausente');
check(fs.existsSync(path.join(root,'assets/vendor/leaflet/LICENSE')),'licença do Leaflet ausente');

const workflow=read('.github/workflows/build-native-apk.yml');
check(/assembleRelease/.test(workflow),'workflow sem build release');
check(/MUSICROAD_KEYSTORE_BASE64/.test(workflow),'workflow sem keystore de produção');
check(/apksigner[^\n]*verify/.test(workflow),'workflow sem verificação da assinatura');
check(/needs:\s*\[validate-web, android-debug\]/.test(workflow),'release deve depender das validações web e Android');
check(/lintRelease assembleRelease/.test(workflow),'release precisa executar o lint da variante de produção');
check(/sha256sum MusicRoad-1\.2\.0\.apk/.test(workflow),'workflow sem checksum SHA-256 do APK');
check(!/stable debug signing|apply_v\d+/i.test(workflow),'workflow ainda usa assinatura/patches históricos');
check(contains('android-app/app/build.gradle',/debuggable false/),'release Android precisa bloquear debug');
check(contains('android-app/app/build.gradle',/MUSICROAD_URL must be an absolute HTTPS URL/),'build Android precisa recusar servidor inseguro');
check(contains('android-app/app/src/main/java/com/musicroad/ai/MainActivity.java',/setWebContentsDebuggingEnabled\(BuildConfig\.DEBUG\)/),'debug do WebView não está condicionado ao build');
check(contains('android-app/app/src/main/java/com/musicroad/ai/MainActivity.java',/verifyDownloadedApk/),'atualizador Android não verifica o arquivo');
check(contains('android-app/app/src/main/java/com/musicroad/ai/MainActivity.java',/SHA-256/),'atualizador Android não calcula SHA-256');
check(contains('android-app/app/src/main/java/com/musicroad/ai/MainActivity.java',/expected\.matches\("\[0-9a-f\]\{64\}"\)/),'atualizador Android deve exigir hash SHA-256 válido');
check(contains('android-app/app/src/main/java/com/musicroad/ai/MainActivity.java',/Download recusado: origem não confiável/),'downloads do WebView precisam ficar na origem confiável');
check(contains('android-app/app/src/main/java/com/musicroad/ai/NativeBridge.java',/removeRegisteredDevice\(String csrf\)/),'ponte Android precisa enviar CSRF ao remover dispositivo');
check(!fs.existsSync(path.join(root,'downloads/MusicRoad-1.1.0.apk')),'APK debug legado não deve ser distribuído');

check(contains('android-app/app/src/main/assets/offline_core.html',/startNavigation/),'Offline Core não retoma o motor Android');
check(contains('android-app/app/src/main/assets/offline_core.html',/trip\?\.active===true/),'Offline Core não restaura viagem ativa');
check(contains('android-app/app/src/main/assets/offline_core.html',/state\/\$\{uf\}\/base/),'Offline Core não usa o mapa-base estadual');
check(contains('android-app/app/src/main/assets/offline_core.html',/stateFeatures/),'Offline Core não renderiza as rodovias estaduais');
check(contains('assets/js/cockpit.js',/baseBbox=geoJsonBounds/),'cockpit vertical não indexa o mapa-base estadual');
check(contains('horizontal/assets/auto.js',/baseBbox=autoGeoJsonBounds/),'DriveOS não indexa o mapa-base estadual');
check(!contains('assets/js/cockpit.js',/offline_state_manifest\.php/),'cockpit vertical não deve baixar todos os municípios');
check(!contains('horizontal/assets/auto.js',/offline_state_manifest\.php/),'DriveOS não deve baixar todos os municípios');
check(!contains('assets/js/cockpit.js',/Promise\.all\(\[worker\(\),worker\(\),worker\(\)\]\)/),'cockpit vertical ainda dispara downloads municipais em massa');
check(!contains('horizontal/assets/auto.js',/Promise\.all\(\[worker\(\),worker\(\),worker\(\)\]\)/),'DriveOS ainda dispara downloads municipais em massa');
for(const file of ['api/offline_state.php','api/offline_city_roads.php']){
  check(contains(file,/require_session_rate_limit\s*\(/),`${file} precisa limitar downloads pesados`);
  check(contains(file,/session_write_close\s*\(/),`${file} precisa liberar o lock de sessão`);
}
check(contains('api/offline_city_roads.php',/Município e UF não correspondem/),'mapa municipal precisa validar a UF do código IBGE');
check(!contains('api/offline_city_roads.php',/['"]detail['"]\s*=>/),'mapa municipal não deve expor detalhe de erro interno');
check(contains('api/bootstrap.php',/SELECT o\.\*,p\.duration_days[\s\S]{0,180}FOR UPDATE/),'pagamento precisa bloquear a ordem durante a confirmação');
check(contains('api/bootstrap.php',/sanitize_payment_payload/),'payload de pagamento precisa remover dados pessoais');
for(const file of ['api/payment_pix.php','api/payment_checkout.php'])check(contains(file,/require_session_rate_limit\s*\(/),`${file} precisa limitar criação de pagamentos`);
check(contains('api/mercadopago_webhook.php',/sanitize_payment_payload\(\$payload\)/),'webhook não deve persistir dados pessoais brutos');
check(contains('api/mercadopago_webhook.php',/invalid_resource_id/),'webhook precisa validar o identificador do pagamento');
check(contains('api/bootstrap.php',/function report_runtime_exception/),'bootstrap sem correlação segura de incidentes');
check(contains('api/bootstrap.php',/CURLOPT_WRITEFUNCTION[\s\S]{0,260}\$maxBytes/),'cliente HTTP precisa limitar o corpo recebido');
for(const file of ['api/radar_brazil_sources.php','api/radar_official_es.php'])check(contains(file,/write_runtime_file\(/),`${file} precisa gravar caches de forma atômica`);
const addressApi=read('api/address_local.php');
check(/unset\(\$pack\[['"]source_url['"]\],\$pack\[['"]zip_path['"]\],\$pack\[['"]last_error['"]\]\)/.test(addressApi),'API CNEFE precisa remover origem, caminho e erro interno');
check(!/catch\s*\(Throwable\s+\$e\)\s*\{[^}]*\$e->getMessage\(\)[^}]*json_response/s.test(addressApi),'API CNEFE não pode devolver exceção bruta');
const lightMapStatus=(read('lib/LightMap.php').match(/function lightmap_status[\s\S]*?function lightmap_atomic_write/)||[])[0]||'';
check(!/['"]last_error['"]\s*=>/.test(lightMapStatus),'status do mapa leve não pode expor erro interno');
check(!/\$e->getMessage\(\)/.test(read('api/radar_route_engine.php')),'motor de radares não pode devolver exceções brutas nos diagnósticos');
for(const file of ['api/route.php','api/route_guidance.php'])check(!/routing_attempts|['"]attempts['"]\s*=>/.test(read(file)),`${file} não pode expor diagnósticos brutos dos provedores`);
check(contains('api/route.php',/write_runtime_file\(\$file,\$encoded\)/),'cache de geocodificação precisa ser gravado de forma atômica');
check(contains('api/mercadopago_webhook.php',/MUSICROAD_STATELESS_REQUEST/),'webhook de pagamento deve ser stateless');
check(contains('api/mercadopago_webhook.php',/262144/),'webhook de pagamento precisa limitar o corpo');
check(contains('api/radar_import_helpers.php',/LIBXML_NONET/),'importação KML precisa bloquear acesso externo XML');
check(contains('lib/CnefeAddressIndex.php',/1073741824/),'download CNEFE precisa ter limite de tamanho');
check(contains('lib/CnefeAddressIndex.php',/in_array\(\$cachedStatus,\[['"]ready['"],['"]indexed['"]\],true\)/),'índice CNEFE preservado não deve forçar novo download sem número');
check(contains('cron/update_radars.php',/cnefe_archives_pruned/),'cron precisa podar arquivos CNEFE sem uso');
for(const file of ['api/clients.php','api/library.php'])check(contains(file,/require_session_rate_limit\s*\(/),`${file} precisa limitar operações por sessão`);
check(contains('login.php',/92IXUNpkjO0rOQ5byMi/),'login precisa usar hash fictício contra enumeração por tempo');
check(contains('horizontal/assets/auto.js',/watchId:null/),'DriveOS sem controle do observador de GPS');
check(contains('horizontal/assets/auto.js',/arrivalAnnounced/),'DriveOS sem proteção contra chegada repetida');
const driveOs=read('horizontal/assets/auto.js');
check((driveOs.match(/\$\$\('\[data-point-speed\]'\)\.forEach/g)||[]).length===1,'DriveOS não pode registrar duas vezes o envio de velocidade');
check((driveOs.match(/saveUnknownPointSpeed/g)||[]).length===1,'DriveOS não pode registrar duas vezes o envio sem velocidade');
check(/preparedCityContext[\s\S]{0,1600}city_id:city\.id/.test(driveOs),'busca do DriveOS precisa informar o município preparado');
check(/savePendingFavoriteDestination[\s\S]{0,500}saveFavorite/.test(driveOs),'favorito digitado no DriveOS precisa ser salvo após resolver a rota');

for(const file of walk('.').filter(f=>f.endsWith('.js')&&!f.includes('assets/vendor/'))){
  const result=spawnSync(process.execPath,['--check',file],{cwd:root,encoding:'utf8'});
  if(result.status!==0)failures.push(`JavaScript inválido em ${file}: ${(result.stderr||result.stdout).trim()}`);
}

for(const file of ['android-app/app/src/main/assets/setup.html','android-app/app/src/main/assets/launcher.html','android-app/app/src/main/assets/offline_core.html']){
  const html=read(file);let match,index=0;const rx=/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/gi;
  while((match=rx.exec(html))){index++;if(match[1].includes('<?'))continue;try{new Function(match[1]);}catch(error){failures.push(`Script inline inválido em ${file} #${index}: ${error.message}`);}
  }
}

if(failures.length){
  console.error(`Falharam ${failures.length} validações:`);
  failures.forEach(item=>console.error(`- ${item}`));
  process.exit(1);
}
console.log('MusicRoad 1.2: validações estáticas concluídas.');

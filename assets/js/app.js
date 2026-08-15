const MR_VERSION = '1.0.0';
const MR_USER_ID = Number(window.MR_USER?.id || 0);
const USER_PREFIX = `u${MR_USER_ID}:`;
window.csrfToken = window.MR_CSRF || '';
let tracks = [];
let currentFilter = 'all';
let deferredInstallPrompt = null;
let fallbackFolderContext = null;
let lastDeviceSyncAt = 0;

const $ = id => document.getElementById(id);
const userKey = key => `${USER_PREFIX}${key}`;

function jump(screen){
  document.querySelectorAll('.screen,.nav').forEach(el=>el.classList.remove('active'));
  $(screen)?.classList.add('active');
  document.querySelector(`.nav[data-screen="${screen}"]`)?.classList.add('active');
  setTimeout(()=>window.dispatchEvent(new Event('resize')),80);
}
document.querySelectorAll('[data-screen],[data-screen-jump]').forEach(btn=>btn.addEventListener('click',()=>jump(btn.dataset.screen||btn.dataset.screenJump)));

function isLocalTrack(t){ return Number(t?.user_id)===MR_USER_ID && !!(t?.deviceSource==='folder'||t?.localBlobId||t?.localFileHandle||String(t?.origin||'').toLowerCase()==='dispositivo'); }
function isServerTrack(t){ return /^\d+$/.test(String(t?.id??'')) && !isLocalTrack(t); }
function trackDate(t){ return new Date(t?.localLastPlayedAt||t?.last_played_at||t?.created_at||t?.createdAt||0).getTime()||0; }
function filteredTracks(){
  if(currentFilter==='favorite') return tracks.filter(t=>+t.is_favorite===1);
  if(currentFilter==='device') return tracks.filter(isLocalTrack);
  if(currentFilter==='drive') return tracks.filter(t=>String(t.origin||'').toLowerCase()==='google drive');
  if(currentFilter==='recent') return [...tracks].filter(t=>trackDate(t)>0).sort((a,b)=>trackDate(b)-trackDate(a));
  return tracks;
}

function render(list=filteredTracks(),target=$('tracks'),options={}){
  if(!target) return;
  target.innerHTML='';
  if($('mixCount')) $('mixCount').textContent=`${tracks.length} músicas na biblioteca`;
  if(!list.length){ target.innerHTML='<div class="panel">Nenhuma música nesta seleção.</div>'; return; }
  list.forEach((t,i)=>{
    const row=document.createElement('div');row.className='track';
    const img=document.createElement('img');img.src=t.cover_url||'assets/img-icon.svg';img.alt='';img.onerror=()=>img.src='assets/img-icon.svg';
    const meta=document.createElement('div');
    const title=document.createElement('strong');title.textContent=t.title||'Sem título';
    const sub=document.createElement('span');
    const size=t.file_size?` · ${(Number(t.file_size)/1048576).toFixed(1)} MB`:'';
    sub.append(document.createTextNode(`${t.artist||'Artista desconhecido'} · `));
    const origin=document.createElement('b');origin.className='origin';origin.textContent=t.origin||'Dispositivo';sub.append(origin,document.createTextNode(size));
    meta.append(title,sub);
    const actions=document.createElement('div');actions.className='track-actions';
    const play=document.createElement('button');play.textContent='▶ Tocar';play.addEventListener('click',()=>Player.setQueue(list,i));actions.append(play);
    if(!options.simple){
      const fav=document.createElement('button');fav.className='secondary-action';fav.textContent=+t.is_favorite===1?'★':'☆';fav.title='Favoritar';fav.addEventListener('click',()=>toggleFavorite(t));actions.append(fav);
    }
    row.append(img,meta,actions);target.append(row);
  });
}

async function toggleFavorite(track){
  if(isLocalTrack(track)){
    track.is_favorite=+track.is_favorite===1?0:1;
    await MRDB.put('tracks',track);
  }else if(isServerTrack(track)){
    const res=await fetch('api/library.php?action=favorite',{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-Token':window.csrfToken},body:JSON.stringify({id:Number(track.id)})}).then(r=>r.json()).catch(()=>null);
    if(res?.ok) track.is_favorite=Number(res.is_favorite||0);
  }
  render();
}

async function allUserLocalTracks(){ return (await MRDB.all('tracks')).filter(isLocalTrack); }
async function allUserFolders(){ return (await MRDB.all('folders')).filter(f=>Number(f.user_id)===MR_USER_ID); }

async function loadLibrary(){
  const local=await allUserLocalTracks();
  const res=await fetch(`api/library.php?action=list&v=${encodeURIComponent(MR_VERSION)}`,{cache:'no-store'}).then(r=>r.json()).catch(()=>({ok:false,tracks:[]}));
  if(res?.csrf) window.csrfToken=res.csrf;
  tracks=[...local,...(Array.isArray(res?.tracks)?res.tracks:[])];
  render();
  await updateDeviceStatus();
  await updatePermissionUI();
}

function parseLocalName(file){
  const base=String(file.name||'').replace(/\.[^.]+$/,'').trim();
  const parts=base.split(/\s+-\s+/);
  if(parts.length>=2) return {artist:parts.shift(),title:parts.join(' - ')};
  return {artist:'Arquivo local',title:base||'Sem título'};
}
function isAudioFile(fileOrName,type=''){
  const name=typeof fileOrName==='string'?fileOrName:(fileOrName?.name||'');
  const mime=typeof fileOrName==='string'?type:(fileOrName?.type||type||'');
  return /^audio\//i.test(mime)||/\.(mp3|m4a|aac|ogg|oga|opus|flac|wav|wave)$/i.test(name);
}
function mimeFromName(name='',type=''){
  if(/^audio\//i.test(type)) return type;
  const ext=String(name).split('.').pop().toLowerCase();
  return {mp3:'audio/mpeg',m4a:'audio/mp4',mp4:'audio/mp4',aac:'audio/aac',ogg:'audio/ogg',oga:'audio/ogg',opus:'audio/ogg',wav:'audio/wav',wave:'audio/wav',flac:'audio/flac'}[ext]||type||'application/octet-stream';
}
function hashText(value){
  let hash=2166136261;const text=String(value||'').toLowerCase();
  for(let i=0;i<text.length;i++){hash^=text.charCodeAt(i);hash=Math.imul(hash,16777619);}
  return (hash>>>0).toString(16);
}
function localTrackId(folderKey,path){ return `${USER_PREFIX}track:${hashText(folderKey+'|'+path)}`; }

async function saveHandleFile(folderKey,fileHandle,file,relativePath){
  if(!isAudioFile(file)) return null;
  const id=localTrackId(folderKey,relativePath);const old=await MRDB.get('tracks',id);const parsed=parseLocalName(file);
  const track={id,user_id:MR_USER_ID,title:parsed.title,artist:parsed.artist,album:old?.album||'',genre:old?.genre||'',origin:'Dispositivo',mime_type:mimeFromName(file.name,file.type),file_name:file.name,relative_path:relativePath,file_size:file.size,folder_key:folderKey,localFileHandle:fileHandle,deviceSource:'folder',is_favorite:+(old?.is_favorite||0),play_count:Number(old?.play_count||0),localLastPlayedAt:old?.localLastPlayedAt||null,createdAt:old?.createdAt||Date.now()};
  await MRDB.put('tracks',track);return track;
}
async function saveFallbackFile(folderKey,file,relativePath){
  if(!isAudioFile(file)) return null;
  const id=localTrackId(folderKey,relativePath);const old=await MRDB.get('tracks',id);const parsed=parseLocalName(file);const mime=mimeFromName(file.name,file.type);
  const blob=file.type===mime?file:new Blob([file],{type:mime});
  const track={id,user_id:MR_USER_ID,title:parsed.title,artist:parsed.artist,album:old?.album||'',genre:old?.genre||'',origin:'Dispositivo',mime_type:mime,file_name:file.name,relative_path:relativePath,file_size:file.size,folder_key:folderKey,localBlobId:id,deviceSource:'folder',is_favorite:+(old?.is_favorite||0),play_count:Number(old?.play_count||0),localLastPlayedAt:old?.localLastPlayedAt||null,createdAt:old?.createdAt||Date.now()};
  await MRDB.put('blobs',{id,user_id:MR_USER_ID,blob,name:file.name,type:mime,size:file.size});
  await MRDB.put('tracks',track);return track;
}

async function walkDirectory(directoryHandle,prefix=''){
  const found=[];
  for await(const [name,handle] of directoryHandle.entries()){
    const path=prefix?`${prefix}/${name}`:name;
    if(handle.kind==='directory') found.push(...await walkDirectory(handle,path));
    else if(handle.kind==='file'){
      try{const file=await handle.getFile();if(isAudioFile(file))found.push({handle,file,path});}catch(_){ }
    }
  }
  return found;
}

function setDeviceStatus(text,kind=''){
  const el=$('deviceStatus');if(!el)return;el.textContent=text;el.className=`device-status ${kind}`.trim();
}

async function scanHandleFolder(folderRow,{silent=false}={}){
  const handle=folderRow?.handle;if(!handle)return {count:0,state:'missing'};
  try{
    let state=typeof handle.queryPermission==='function'?await handle.queryPermission({mode:'read'}):'granted';
    if(state!=='granted' && !silent && typeof handle.requestPermission==='function') state=await handle.requestPermission({mode:'read'});
    if(state!=='granted') return {count:0,state};
    const files=await walkDirectory(handle);let count=0;
    for(const item of files){if(await saveHandleFile(folderRow.key,item.handle,item.file,item.path))count++;}
    folderRow.last_scan_at=Date.now();folderRow.track_count=count;folderRow.permission='granted';await MRDB.put('folders',folderRow);
    return {count,state:'granted'};
  }catch(err){
    folderRow.permission='error';folderRow.last_error=String(err?.message||err);await MRDB.put('folders',folderRow).catch(()=>{});
    return {count:0,state:'error',error:err};
  }
}

async function connectDeviceFolder(){
  const button=$('scanDeviceMusic')||$('permissionMusicButton')||$('onboardingMusic');
  try{
    if('showDirectoryPicker' in window){
      setDeviceStatus('Escolha uma pasta que contenha suas músicas...','waiting');
      const handle=await window.showDirectoryPicker({mode:'read'});
      let state=typeof handle.requestPermission==='function'?await handle.requestPermission({mode:'read'}):'granted';
      if(state!=='granted') throw new Error('A pasta não foi autorizada.');
      let row=null;
      const existing=await allUserFolders();
      for(const f of existing){
        if(f.mode!=='handle'||!f.handle||typeof f.handle.isSameEntry!=='function') continue;
        try{if(await f.handle.isSameEntry(handle)){row=f;break;}}catch(_){ }
      }
      const key=row?.key || userKey(`folder:${hashText(handle.name+'|'+Date.now())}`);
      row={...(row||{}),key,user_id:MR_USER_ID,name:handle.name,handle,mode:'handle',permission:'granted',created_at:row?.created_at||Date.now()};
      await MRDB.put('folders',row);
      const result=await scanHandleFolder(row,{silent:false});
      await loadLibrary();
      setDeviceStatus(`${result.count} música(s) encontradas em “${handle.name}”.`,'ok');
      await markMusicAccessTouched();
      return true;
    }
    // Compatibilidade Android/iOS/Chromium sem File System Access API: seleciona a pasta inteira, nunca arquivo por arquivo.
    fallbackFolderContext={requestedAt:Date.now()};
    $('folderInput')?.click();
    return false;
  }catch(err){
    if(err?.name==='AbortError'){setDeviceStatus('Seleção de pasta cancelada.','waiting');return false;}
    if($('folderInput')){
      setDeviceStatus('O acesso direto à pasta não funcionou neste navegador. Abrindo modo compatível...','waiting');
      setTimeout(()=>$('folderInput')?.click(),50);
      return false;
    }
    setDeviceStatus(err?.message||'Não foi possível autorizar a pasta.','error');
    await updatePermissionUI(true);return false;
  }finally{ if(button) button.disabled=false; }
}

async function importFallbackFolder(fileList){
  const files=[...(fileList||[])].filter(isAudioFile);
  if(!files.length){setDeviceStatus('Nenhum arquivo de áudio encontrado na pasta selecionada.','error');await updatePermissionUI();return false;}
  const firstPath=files[0].webkitRelativePath||files[0].name;
  const root=(firstPath.split('/')[0]||'Pasta de músicas').trim();
  const key=userKey(`folder:fallback:${hashText(root)}`);
  const row={key,user_id:MR_USER_ID,name:root,mode:'blob',permission:'granted',created_at:Date.now(),track_count:files.length};
  await MRDB.put('folders',row);
  setDeviceStatus(`Indexando ${files.length} música(s) de “${root}”...`,'waiting');
  let count=0;
  for(const file of files){
    const path=file.webkitRelativePath||file.name;
    try{if(await saveFallbackFile(key,file,path))count++;}catch(err){console.warn('MusicRoad arquivo:',file.name,err);}
  }
  await loadLibrary();
  setDeviceStatus(`${count} música(s) prontas para tocar. Os arquivos não foram enviados ao servidor.`,'ok');
  await markMusicAccessTouched();
  return true;
}

async function reconnectStoredFolders(silent=true){
  const folders=await allUserFolders();let total=0,granted=0;
  for(const folder of folders){
    if(folder.mode!=='handle') continue;
    const result=await scanHandleFolder(folder,{silent});
    total+=result.count;if(result.state==='granted')granted++;
  }
  if(total>0) await loadLibrary();
  return {total,granted};
}

async function updateDeviceStatus(){
  const local=tracks.filter(isLocalTrack);const folders=await allUserFolders();
  if(local.length){setDeviceStatus(`${local.length} música(s) do dispositivo disponíveis em ${folders.length} pasta(s).`,'ok');}
  else if(folders.length){setDeviceStatus('Pastas cadastradas, mas nenhuma música está acessível agora. Autorize/sincronize novamente.','waiting');}
  else setDeviceStatus('Nenhuma pasta de músicas autorizada neste aparelho.','waiting');
  await renderFolderList(folders);
}

async function renderFolderList(folders=null){
  if(!folders) folders=await allUserFolders();
  const box=$('folderList');if(!box)return;box.innerHTML='';
  if(!folders.length){box.innerHTML='<div class="folder-empty">Nenhuma pasta conectada.</div>';return;}
  for(const folder of folders){
    const item=document.createElement('div');item.className='folder-item';
    const text=document.createElement('div');
    const strong=document.createElement('strong');strong.textContent=folder.name||'Pasta de músicas';
    const small=document.createElement('small');small.textContent=folder.mode==='handle'?'Acesso direto à pasta':'Modo compatível: cópia local no PWA';
    text.append(strong,small);
    const remove=document.createElement('button');remove.className='secondary-action';remove.textContent='Remover';remove.addEventListener('click',()=>removeFolder(folder));
    item.append(text,remove);box.append(item);
  }
}

async function removeFolder(folder){
  const local=await allUserLocalTracks();
  const owned=local.filter(t=>t.folder_key===folder.key);
  for(const t of owned){await MRDB.del('tracks',t.id);if(t.localBlobId)await MRDB.del('blobs',t.localBlobId).catch(()=>{});}
  await MRDB.del('folders',folder.key);
  await loadLibrary();
}

async function musicPermissionState(){
  const folders=await allUserFolders();const local=await allUserLocalTracks();
  if(!folders.length) return 'prompt';
  if(local.length && folders.some(f=>f.mode==='blob')) return 'granted';
  let anyGranted=false,anyPrompt=false;
  for(const f of folders){
    if(f.mode!=='handle'||!f.handle) continue;
    try{const s=typeof f.handle.queryPermission==='function'?await f.handle.queryPermission({mode:'read'}):'granted';if(s==='granted')anyGranted=true;else if(s==='prompt')anyPrompt=true;}catch(_){ }
  }
  if(anyGranted) return 'granted';
  if(local.length) return 'granted';
  return anyPrompt?'prompt':'denied';
}

function permissionText(type,state){
  const map={granted:'Permitido',prompt:'Aguardando autorização',denied:'Negado',unknown:'Ainda não confirmado',insecure:'HTTPS obrigatório',unsupported:'Não suportado',error:'Erro ao verificar'};
  return map[state]||state||'Desconhecido';
}

async function effectiveLocationPermissionState(){
  const raw=await RadaMaps.permissionState();
  if(raw==='unknown') return localStorage.getItem(userKey('locationAccess')) || 'unknown';
  return raw;
}

async function requestLocationAccess(){
  const info=$('routeInfo');if(info)info.textContent='Solicitando sua localização...';
  try{
    const pos=await RadaMaps.locateOnce();
    const {latitude,longitude}=pos.coords;
    if($('origin'))$('origin').value=`${latitude.toFixed(6)},${longitude.toFixed(6)}`;
    if(info)info.textContent='Localização permitida. Buscando radares próximos...';
    const res=await fetch(`api/radars.php?action=near&lat=${encodeURIComponent(latitude)}&lon=${encodeURIComponent(longitude)}&radius=20000`,{cache:'no-store'}).then(r=>r.json()).catch(()=>null);
    if(res?.ok){RadaMaps.drawRadars(res.radars,[latitude,longitude]);if(info)info.textContent=`Localização ativa. ${res.radars.length} radar(es) encontrados em até 20 km.`;}
    RadaMaps.startTracking();
    localStorage.setItem(userKey('locationAccess'),'granted');
    await updatePermissionUI(true);
    return true;
  }catch(err){if(info)info.textContent=err?.message||'Não consegui acessar a localização.';const raw=await RadaMaps.permissionState();if(raw==='denied')localStorage.setItem(userKey('locationAccess'),'denied');await updatePermissionUI(true);return false;}
}

function getDeviceId(){
  let id=localStorage.getItem('mr_device_id');
  if(!id){id=crypto.randomUUID?crypto.randomUUID():`${Date.now()}-${Math.random().toString(36).slice(2)}`;localStorage.setItem('mr_device_id',id);}
  return id;
}
async function syncClientDeviceState(force=false){
  if(!force && Date.now()-lastDeviceSyncAt<30000) return;
  lastDeviceSyncAt=Date.now();
  const folders=await allUserFolders();const local=await allUserLocalTracks();const music=await musicPermissionState();const location=await effectiveLocationPermissionState();
  const payload={device_id:getDeviceId(),device_name:(navigator.userAgentData?.platform||navigator.platform||'Navegador').slice(0,120),music_permission:music,location_permission:location,music_folder_count:folders.length,music_track_count:local.length};
  fetch('api/client_state.php',{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-Token':window.csrfToken},body:JSON.stringify(payload)}).catch(()=>{});
}

async function markMusicAccessTouched(){ localStorage.setItem(userKey('musicAccessTouched'),'1'); await updatePermissionUI(true); }

async function updatePermissionUI(forceSync=false){
  const music=await musicPermissionState();const location=await effectiveLocationPermissionState();
  if($('musicPermissionLabel')) $('musicPermissionLabel').textContent=permissionText('music',music);
  if($('locationPermissionLabel')) $('locationPermissionLabel').textContent=permissionText('location',location);
  if($('onboardingMusicState')) $('onboardingMusicState').textContent=permissionText('music',music);
  if($('onboardingLocationState')) $('onboardingLocationState').textContent=permissionText('location',location);
  if($('homePermissionSummary')) $('homePermissionSummary').textContent=`Músicas: ${permissionText('music',music)} · Localização: ${permissionText('location',location)}`;
  const finish=$('finishOnboarding');if(finish)finish.disabled=!(music==='granted'&&location==='granted');
  $('permissionMusicButton')?.classList.toggle('permission-ok',music==='granted');
  $('permissionLocationButton')?.classList.toggle('permission-ok',location==='granted');
  await syncClientDeviceState(forceSync);
  return {music,location};
}

async function showOnboardingIfNeeded(){
  const done=localStorage.getItem(userKey('onboardingDone'))==='1';
  if(done) return;
  const modal=$('permissionOnboarding');if(!modal)return;modal.hidden=false;document.body.classList.add('modal-open');
  await updatePermissionUI();
}
function closeOnboarding(){localStorage.setItem(userKey('onboardingDone'),'1');const modal=$('permissionOnboarding');if(modal)modal.hidden=true;document.body.classList.remove('modal-open');}

$('scanDeviceMusic')?.addEventListener('click',connectDeviceFolder);
$('permissionMusicButton')?.addEventListener('click',connectDeviceFolder);
$('onboardingMusic')?.addEventListener('click',connectDeviceFolder);
$('folderInput')?.addEventListener('change',async e=>{const files=e.target.files;e.target.value='';await importFallbackFolder(files);});
$('syncDeviceMusic')?.addEventListener('click',async()=>{
  const folders=await allUserFolders();
  const handleFolders=folders.filter(f=>f.mode==='handle');
  if(handleFolders.length){setDeviceStatus('Sincronizando pastas autorizadas...','waiting');const r=await reconnectStoredFolders(false);setDeviceStatus(`${r.total} música(s) sincronizadas.`,'ok');await updatePermissionUI();}
  else $('folderInput')?.click();
});
$('permissionLocationButton')?.addEventListener('click',requestLocationAccess);
$('onboardingLocation')?.addEventListener('click',requestLocationAccess);
$('finishOnboarding')?.addEventListener('click',closeOnboarding);
$('skipOnboarding')?.addEventListener('click',closeOnboarding);

$('clearOffline')?.addEventListener('click',async()=>{
  const folders=await allUserFolders();for(const f of folders)await MRDB.del('folders',f.key);
  const local=await allUserLocalTracks();for(const t of local){await MRDB.del('tracks',t.id);if(t.localBlobId)await MRDB.del('blobs',t.localBlobId).catch(()=>{});}
  await loadLibrary();await updatePermissionUI(true);setDeviceStatus('Pastas desconectadas deste usuário neste aparelho.','waiting');
});

document.querySelectorAll('.tabs [data-filter]').forEach(button=>button.addEventListener('click',()=>{currentFilter=button.dataset.filter;document.querySelectorAll('.tabs [data-filter]').forEach(b=>b.classList.toggle('active',b===button));render();}));

function showAiResult(result){
  const box=$('aiResult'),summary=$('aiSummary');if(!box||!summary)return;summary.textContent=result.message||'';
  if(result.type==='empty'){box.innerHTML=`<div class="panel">${result.message}</div>`;return;}
  if(result.type==='duplicates'){
    box.innerHTML='';if(!result.groups.length){box.innerHTML='<div class="panel">Nenhuma repetida encontrada.</div>';return;}
    result.groups.forEach(group=>{const wrap=document.createElement('div');wrap.className='panel duplicate-group';const h=document.createElement('h3');h.textContent=`${group[0].title||'Sem título'} · ${group.length} versões`;const b=document.createElement('button');b.textContent='Tocar versões';b.addEventListener('click',()=>Player.setQueue(group,0));wrap.append(h,b);box.append(wrap);});return;
  }
  box.innerHTML='';const name=document.createElement('div');name.className='ai-result-title';name.textContent=result.name||'Smart Mix';const list=document.createElement('div');list.className='track-list';box.append(name,list);render(result.tracks||[],list,{simple:true});
}
function runSmart(prompt){showAiResult(SmartMix.build(prompt,tracks));}
$('askAi')?.addEventListener('click',()=>runSmart($('aiPrompt')?.value.trim()||'Monte um mix variado'));
document.querySelectorAll('[data-prompt]').forEach(b=>b.addEventListener('click',()=>{if($('aiPrompt'))$('aiPrompt').value=b.dataset.prompt;runSmart(b.dataset.prompt);}));

$('useLocation')?.addEventListener('click',requestLocationAccess);
$('calcRoute')?.addEventListener('click',async()=>{
  const rawOrigin=$('origin')?.value.trim(),rawDestination=$('destination')?.value.trim(),info=$('routeInfo');
  if(!rawOrigin||!rawDestination){if(info)info.textContent='Informe origem e destino. Você também pode usar sua localização.';return;}
  if(info)info.textContent='Calculando rota e consultando radares...';
  const res=await fetch(`api/route.php?origin=${encodeURIComponent(rawOrigin)}&destination=${encodeURIComponent(rawDestination)}`,{cache:'no-store'}).then(r=>r.json()).catch(e=>({ok:false,error:e.message}));
  if(!res.ok){if(info)info.textContent=res.error||'Falha ao calcular rota.';return;}
  RadaMaps.drawRoute(res.route,res.radars||[]);if(info)info.textContent=`Distância: ${(res.route.distance/1000).toFixed(1)} km · Tempo: ${Math.round(res.route.duration/60)} min · Radares: ${(res.radars||[]).length}.`;
});

document.addEventListener('mr:track-played',async e=>{const track=e.detail?.track;if(!track||!isLocalTrack(track))return;track.play_count=Number(track.play_count||0)+1;track.localLastPlayedAt=new Date().toISOString();await MRDB.put('tracks',track);});
document.addEventListener('mr:location-update',()=>updatePermissionUI().catch(()=>{}));

function loadSettings(){
  const voice=localStorage.getItem(userKey('voice'));if($('voiceAlerts'))$('voiceAlerts').checked=voice===null?true:voice==='1';
  if($('alertVolume'))$('alertVolume').value=localStorage.getItem(userKey('alertVolume'))||'1';
}
$('voiceAlerts')?.addEventListener('change',e=>localStorage.setItem(userKey('voice'),e.target.checked?'1':'0'));
$('alertVolume')?.addEventListener('change',e=>localStorage.setItem(userKey('alertVolume'),e.target.value));

window.addEventListener('beforeinstallprompt',e=>{e.preventDefault();deferredInstallPrompt=e;});
$('installApp')?.addEventListener('click',async()=>{
  if(!deferredInstallPrompt){if($('installStatus'))$('installStatus').textContent='Use o menu do navegador → Instalar aplicativo / Adicionar à tela inicial.';return;}
  deferredInstallPrompt.prompt();const choice=await deferredInstallPrompt.userChoice;if($('installStatus'))$('installStatus').textContent=choice.outcome==='accepted'?'Instalação aceita.':'Instalação cancelada.';deferredInstallPrompt=null;
});

async function updateDiagnostics(){
  const parts=[`Versão ${MR_VERSION}`,window.isSecureContext?'HTTPS: OK':'HTTPS: NÃO',`MP3: ${$('audio')?.canPlayType('audio/mpeg')?'OK':'não confirmado'}`,`Localização: ${await RadaMaps.permissionState()}`,`PWA: ${'serviceWorker' in navigator?'suportado':'não suportado'}`,`Pastas: ${'showDirectoryPicker' in window?'acesso persistente':'modo compatível'}`];
  if($('diagnostics'))$('diagnostics').textContent=parts.join(' · ');
}

async function resetOldCaches(){if(!('caches' in window))return;const keys=await caches.keys();await Promise.all(keys.filter(k=>k.startsWith('musicroad-ai-')&&k!=='musicroad-ai-v1').map(k=>caches.delete(k)));}
if('serviceWorker' in navigator){resetOldCaches().catch(()=>{});navigator.serviceWorker.register(`sw.js?v=${MR_VERSION}`,{updateViaCache:'none'}).then(r=>r.update()).catch(()=>{});}

document.addEventListener('DOMContentLoaded',async()=>{
  loadSettings();
  try{RadaMaps.init();}catch(err){if($('routeInfo'))$('routeInfo').textContent=`Mapa não iniciou: ${err.message}`;}
  await loadLibrary();
  await reconnectStoredFolders(true);
  await loadLibrary();
  await updateDiagnostics();
  await showOnboardingIfNeeded();
});

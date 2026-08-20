(() => {
  'use strict';
  const screens=[...document.querySelectorAll('.cockpit-screen')];
  const navs=[...document.querySelectorAll('[data-nav]')];
  let activeScreen='board', map=null, baseTileLayer=null, mapboxBase=null, routeLayer=null, routeCasing=null, radarLayer=null, offlineRegionLayer=null, lightCityLayer=null, lightCityPack=null, lightCityLoadedCode='', lightMapPreparingCode='', baseTileErrors=0, userMarker=null, watchId=null, lastPos=null, currentRoute=null, currentRadars=[], currentSpeedLimits=[], currentManeuvers=[], currentGuidanceSteps=[];
  let tripActive=false, followMap=true, routeLatLngs=[], routeCumM=[], lastRouteProgressM=0, alertTimer=null, lastSpeedVoiceAt=0;
  let tripDestinationCoords=null, tripDestinationLabel='', rerouteInFlight=false, offRouteSamples=0, lastRerouteAt=0, lastOfflineDeviationAlertAt=0;
  let nativeTripActive=false, nativeNavLastEventAt=0;
  const hazardMilestonesWarned=new Set(), radarAnnouncedNow=new Set(), bumpSequenceMilestonesWarned=new Set(), bumpNearWarned=new Set(), maneuverPrepareWarned=new Set(), maneuverNowWarned=new Set();
  let allTracks=[];
  const ALERT_PREF_KEY='mr:alert:prefs:v2';
  const DEFAULT_ALERT_PREFS={voice:true,visual:true,system:true,vibrate:true,maneuvers:true,speeding:true,m300:true,m200:true,m100:true,m50:true,front:true,radar:true,portable:true,video:true,signal:true,bump:true,voiceName:'',voiceRate:1,voicePitch:1};
  let alertPrefs={...DEFAULT_ALERT_PREFS};
  function loadAlertPrefs(){try{alertPrefs={...DEFAULT_ALERT_PREFS,...JSON.parse(localStorage.getItem(ALERT_PREF_KEY)||'{}')}}catch(_){alertPrefs={...DEFAULT_ALERT_PREFS}}return alertPrefs;}
  function saveAlertPrefs(){try{localStorage.setItem(ALERT_PREF_KEY,JSON.stringify(alertPrefs))}catch(_){};syncAlertPrefsToNative();}
  function syncAlertPrefsToNative(){
    try{if(window.MusicRoadAndroid?.setAlertPreferences)window.MusicRoadAndroid.setAlertPreferences(JSON.stringify(alertPrefs));}catch(_){}
    try{if(window.MusicRoadAndroid?.setTtsPreferences)window.MusicRoadAndroid.setTtsPreferences(String(alertPrefs.voiceName||''),Number(alertPrefs.voiceRate)||1,Number(alertPrefs.voicePitch)||1);}catch(_){}
  }
  function alertKindEnabled(kind){return alertPrefs[kind]!==false;}
  function alertMilestoneEnabled(m){return alertPrefs[`m${m}`]!==false;}
  loadAlertPrefs();
  function haptic(){try{if(window.MusicRoadAndroid?.haptic)window.MusicRoadAndroid.haptic();}catch(_){}}
  function showScreen(name){
    if(name!==activeScreen)haptic();
    activeScreen=name;document.body.dataset.screen=name;
    screens.forEach(s=>{const active=s.dataset.screen===name;s.classList.toggle('active',active);if(active&&name!=='map')s.scrollTop=0;});
    navs.forEach(n=>n.classList.toggle('active',n.dataset.nav===name));
    if(name==='map'){[50,220,650].forEach(ms=>setTimeout(()=>map?.invalidateSize({pan:false}),ms));}
    history.replaceState({screen:name},'',location.pathname+location.search+'#'+name);
  }
  document.body.dataset.screen=activeScreen;
  navs.forEach(b=>b.addEventListener('click',()=>showScreen(b.dataset.nav)));
  document.querySelectorAll('[data-go]').forEach(b=>b.addEventListener('click',()=>showScreen(b.dataset.go)));

  window.MusicRoadHandleBack=()=>{ if(activeScreen!=='board'){showScreen('board');return true;} return false; };

  const OFFLINE_TRIP_KEY='mr:offline:last-trip:v12';
  function offlineTrip(){try{return JSON.parse(localStorage.getItem(OFFLINE_TRIP_KEY)||'null')}catch(_){return null}}
  function saveOfflineTrip(patch={}){
    try{
      const prev=offlineTrip()||{};const next={...prev,...patch,ts:Date.now()};
      localStorage.setItem(OFFLINE_TRIP_KEY,JSON.stringify(next));try{window.MusicRoadAndroid?.saveLastRouteSnapshot?.(JSON.stringify(next))}catch(_){}updateOfflineStatus();
    }catch(_){}
  }
  function updateOfflineStatus(){
    const el=document.getElementById('offlineStatus'),badge=document.getElementById('offlineReadyBadge'),cached=offlineTrip();
    if(!el)return;
    const routeReady=!!cached?.data?.route?.geometry?.coordinates?.length;
    const radarCount=Array.isArray(cached?.radars)?cached.radars.length:0;
    el.textContent=routeReady?`Offline pronto · última rota salva${cached.destination?' para '+cached.destination:''} · ${radarCount} pontos de fiscalização`:'Abra uma rota online uma vez para deixá-la disponível offline.';
    if(badge){badge.textContent=routeReady?'PRONTO':'AUTO';badge.classList.toggle('ok',routeReady)}
  }
  function setNet(){const b=document.getElementById('netBadge');const online=navigator.onLine;b.textContent=online?'online':'offline';b.classList.toggle('offline',!online);updateOfflineStatus()}
  addEventListener('online',setNet);addEventListener('offline',setNet);setNet();
  if('serviceWorker' in navigator){
    navigator.serviceWorker.register(`sw.js?v=${window.MR_BOOTSTRAP?.version||'1.2.0'}`,{updateViaCache:'none'}).then(reg=>reg.update()).catch(()=>{});
  }

  function compareVersions(a,b){
    const A=String(a||'0').split('.').map(n=>Number(String(n).replace(/\D.*$/,''))||0),B=String(b||'0').split('.').map(n=>Number(String(n).replace(/\D.*$/,''))||0);
    for(let i=0;i<Math.max(A.length,B.length);i++){const x=A[i]||0,y=B[i]||0;if(x!==y)return x>y?1:-1;}return 0;
  }
  async function checkAppUpdate(){
    if(!navigator.onLine||!window.MusicRoadAndroid)return;
    try{
      const r=await fetch('api/app_update.php',{cache:'no-store',credentials:'same-origin'});const j=await r.json();
      if(!r.ok||!j.ok||j.available===false)return;
      const latestCode=Number(j.version_code||0),latestVersion=String(j.version||'0');
      const hasCode=typeof window.MusicRoadAndroid.versionCode==='function';
      const currentCode=hasCode?Number(window.MusicRoadAndroid.versionCode()||0):0;
      let currentVersion='0';try{currentVersion=String(window.MusicRoadAndroid.version?.()||window.MusicRoadNativeInfo?.version||'0')}catch(_){currentVersion=String(window.MusicRoadNativeInfo?.version||'0')}
      const newer=hasCode&&latestCode?latestCode>currentCode:compareVersions(latestVersion,currentVersion)>0;
      const dismissKey=latestCode||latestVersion;if(!newer||sessionStorage.getItem(`mr:update-dismissed:${dismissKey}`)==='1')return;
      const sh=document.getElementById('appUpdateSheet');if(!sh)return;
      document.getElementById('appUpdateVersion').textContent=`Versão ${latestVersion}`;
      let notes=j.notes||'Nova versão do MusicRoad disponível.';
      if(typeof window.MusicRoadAndroid.requestAppUpdate!=='function'&&typeof window.MusicRoadAndroid.requestAppUpdateWithHash!=='function')notes+=' O APK será aberto para download pelo navegador.';
      document.getElementById('appUpdateNotes').textContent=notes;
      sh.dataset.url=new URL(j.url,location.href).href;sh.dataset.version=latestVersion;sh.dataset.sha256=String(j.sha256||'');sh.dataset.code=String(dismissKey);
      sh.classList.add('open');sh.setAttribute('aria-hidden','false');
    }catch(_){}
  }
  function closeAppUpdateSheet(dismiss=true){const sh=document.getElementById('appUpdateSheet');if(!sh)return;if(dismiss&&sh.dataset.code)sessionStorage.setItem(`mr:update-dismissed:${sh.dataset.code}`,'1');sh.classList.remove('open');sh.setAttribute('aria-hidden','true');}
  document.getElementById('appUpdateLater')?.addEventListener('click',()=>closeAppUpdateSheet(true));
  document.getElementById('appUpdateLaterBackdrop')?.addEventListener('click',()=>closeAppUpdateSheet(true));
  document.getElementById('appUpdateDownload')?.addEventListener('click',()=>{
    const sh=document.getElementById('appUpdateSheet'),url=sh?.dataset.url||'',version=sh?.dataset.version||'',sha256=sh?.dataset.sha256||'';if(!url)return;
    try{if(window.MusicRoadAndroid?.requestAppUpdateWithHash){window.MusicRoadAndroid.requestAppUpdateWithHash(url,version,sha256);showRoadAlert('info','ATUALIZAÇÃO','O Android vai baixar e verificar a atualização.','',3500);}else if(window.MusicRoadAndroid?.requestAppUpdate){window.MusicRoadAndroid.requestAppUpdate(url,version);showRoadAlert('info','ATUALIZAÇÃO','O Android vai preparar a atualização do MusicRoad.','',3500);}else location.href=url;}catch(_){location.href=url;}
    closeAppUpdateSheet(false);
  });
  document.addEventListener('mr:update-download-started',()=>showRoadAlert('info','BAIXANDO ATUALIZAÇÃO','O download do novo APK começou.','',3000));
  document.addEventListener('mr:update-installing',()=>showRoadAlert('info','ATUALIZAÇÃO PRONTA','Confirme a instalação na tela do Android.','',4200));
  setTimeout(checkAppUpdate,1400);

  function removeLegacyNativeStyle(){const old=document.getElementById('mr-native-ui');if(old)old.remove()}
  document.addEventListener('mr:native-ready',()=>{removeLegacyNativeStyle();setTimeout(removeLegacyNativeStyle,40)});
  function initNative(){
    const native=!!window.Player?.native; document.documentElement.classList.toggle('native-app',native);
    document.getElementById('nativeInfo').textContent=native?`Android conectado · player + motor de viagem nativos · retrato/paisagem`:'Navegador web · player HTML5';
    document.getElementById('openNativeLibrary')?.addEventListener('click',()=>Player.openNativeLibrary());
    if(native){syncAlertPrefsToNative();setTimeout(removeLegacyNativeStyle,250);setTimeout(removeLegacyNativeStyle,900);}
    updateFmStatus();
  }

  const libraries={device:[],drive:[],server:[]};
  let sourceFilter='all',folderFilter='all',rhythmFilter='all',renderedTracks=[],downloadedOnly=false,favoritesOnly=false;
  const FAVORITES_KEY='mr:music:favorites:v14';
  let localFavoriteKeys=loadLocalFavorites();
  function loadLocalFavorites(){try{return new Set(JSON.parse(localStorage.getItem(FAVORITES_KEY)||'[]'))}catch(_){return new Set()}}
  function saveLocalFavorites(){try{localStorage.setItem(FAVORITES_KEY,JSON.stringify([...localFavoriteKeys]))}catch(_){}}
  function favoriteKey(track){return `${sourceKey(track)}:${String(track?.id||'')}|${fold(track?.title)}|${fold(track?.artist)}`}
  function isFavorite(track){return Number(track?.is_favorite||0)===1||localFavoriteKeys.has(favoriteKey(track))}
  function syncFavoriteHeader(){const b=document.getElementById('auroraFavorite');if(!b)return;b.textContent=favoritesOnly?'♥':'♡';b.classList.toggle('active',favoritesOnly);b.setAttribute('aria-pressed',favoritesOnly?'true':'false');b.title=favoritesOnly?'Mostrando favoritas':'Mostrar favoritas';}
  const downloadStates=new Map();let downloadPollTimer=null,localOfflineSignatures=new Set();

  const RHYTHMS=[
    ['Forró',['forro','forró','xote','baiao','baião','arrasta pe','arrasta-pé']],
    ['Piseiro',['piseiro','pisadinha']],
    ['Funk',['funk','mandelao','mandelão','150 bpm','automotivo']],
    ['Sertanejo',['sertanejo','modao','modão','universitario','universitário','sofrencia','sofrência']],
    ['Arrocha',['arrocha','seresta']],
    ['Pagode',['pagode']],
    ['Samba',['samba']],
    ['Gospel',['gospel','louvor','adoracao','adoração','evangelico','evangélico']],
    ['Eletrônica',['eletronica','eletrônica','electronic','edm','house','techno','trance','dance','dj','remix']],
    ['Rock',['rock','metal','punk','grunge']],
    ['Pop',['pop']],
    ['Rap/Trap',['rap','trap','hip hop','hiphop']],
    ['Reggae',['reggae']],
    ['MPB',['mpb','bossa nova']],
    ['Romântica',['romantica','romântica','romantico','romântico']]
  ];

  function fold(value){
    return String(value??'').normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase().trim();
  }
  function esc(value){
    return String(value??'').replace(/[&<>'"]/g,char=>({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[char]));
  }
  function sourceKey(track){
    const origin=fold(track?.origin), id=String(track?.id||'');
    if(id.startsWith('android-')||track?.nativeMedia||track?.deviceSource==='mediastore'||origin.includes('dispositivo')||origin.includes('celular')) return 'device';
    if(origin.includes('google drive')||origin==='drive'||origin.includes('drive')) return 'drive';
    return 'server';
  }
  function sourceLabel(key){return key==='device'?'Celular':key==='drive'?'Drive':'Servidor'}
  function cleanFolder(raw){
    let value=String(raw||'').replace(/\\/g,'/').replace(/\/+/g,'/').replace(/^\/|\/$/g,'').trim();
    if(!value)return'';
    const parts=value.split('/').map(v=>v.trim()).filter(Boolean);
    return parts[parts.length-1]||value;
  }
  function folderFor(track){
    const key=sourceKey(track);
    if(key==='device'){
      return cleanFolder(track.folder||track.relative_path||track.folder_path||track.path)||'Sem pasta';
    }
    if(key==='drive'){
      return cleanFolder(track.folder||track.folder_name||track.album||track.path)||'Google Drive';
    }
    return cleanFolder(track.folder||track.album||track.path)||'Servidor';
  }
  function mapExplicitGenre(raw){
    const g=fold(raw);
    if(!g)return null;
    for(const [name,words] of RHYTHMS){
      if(words.some(w=>g.includes(fold(w))))return name;
    }
    return String(raw).trim().slice(0,24)||null;
  }
  function detectRhythm(track){
    const explicit=mapExplicitGenre(track.genre||track.rhythm);
    if(explicit)return{name:explicit,confidence:.98,basis:'tag'};
    const folder=fold([track.folder,track.relative_path,track.folder_path,track.path,track.album].filter(Boolean).join(' '));
    for(const [name,words] of RHYTHMS){
      if(words.some(w=>folder.includes(fold(w))))return{name,confidence:.78,basis:'pasta'};
    }
    const text=fold([track.title,track.artist,track.album].filter(Boolean).join(' '));
    for(const [name,words] of RHYTHMS){
      if(words.some(w=>text.includes(fold(w))))return{name,confidence:.58,basis:'nome'};
    }
    return{name:'Outros',confidence:.12,basis:'indefinido'};
  }
  function enrich(track){
    const t=Player.normalize(track);
    t._sourceKey=sourceKey(t);
    t._folder=folderFor(t);
    const rhythm=detectRhythm(t);
    t._rhythm=rhythm.name;t._rhythmConfidence=rhythm.confidence;t._rhythmBasis=rhythm.basis;
    return t;
  }
  function trackOfflineSignature(track){return `${fold(track?.title)}|${fold(track?.artist)}`;}
  function offlineKey(track){
    if(sourceKey(track)!=='drive')return'';
    const id=driveId(track);return id?`drive:${id}`:'';
  }
  function isMusicRoadLocal(track){
    if(sourceKey(track)!=='device')return false;
    return fold([track.folder,track.relative_path,track.folder_path,track.path].filter(Boolean).join(' ')).includes('musicroad');
  }
  function offlineState(track){
    const key=offlineKey(track);
    if(key&&downloadStates.has(key))return downloadStates.get(key);
    if(isMusicRoadLocal(track))return{state:'downloaded',progress:100};
    if(sourceKey(track)==='drive'&&localOfflineSignatures.has(trackOfflineSignature(track)))return{state:'downloaded',progress:100};
    return{state:'none',progress:0};
  }
  function isDownloadedTrack(track){return offlineState(track).state==='downloaded';}
  function sourceBase(){
    const base=sourceFilter==='all'?allTracks:(libraries[sourceFilter]||[]);
    return downloadedOnly?base.filter(isDownloadedTrack):base;
  }
  function rebuildAll(){
    allTracks=[...libraries.device,...libraries.drive,...libraries.server].map(enrich);
    libraries.device=allTracks.filter(t=>t._sourceKey==='device');
    libraries.drive=allTracks.filter(t=>t._sourceKey==='drive');
    libraries.server=allTracks.filter(t=>t._sourceKey==='server');
    localOfflineSignatures=new Set(libraries.device.filter(isMusicRoadLocal).map(trackOfflineSignature));
    updateSourceCounts();renderFacets();queueDownloadStateRefresh(120);
  }
  function updateSourceCounts(){
    const counts={all:allTracks.length,device:libraries.device.length,drive:libraries.drive.length,server:libraries.server.length};
    const downloaded=allTracks.filter(isDownloadedTrack).length;
    document.querySelectorAll('[data-source-filter]').forEach(btn=>{
      const key=btn.dataset.sourceFilter,count=counts[key]??0;
      btn.classList.toggle('active',key===sourceFilter);
      btn.classList.toggle('is-empty',key!=='all'&&count===0);
      const badge=btn.querySelector('b');if(badge)badge.textContent=String(count);
    });
    const downBtn=document.getElementById('downloadedOnly'),downCount=document.getElementById('downloadedCount');
    if(downBtn){downBtn.classList.toggle('active',downloadedOnly);downBtn.setAttribute('aria-pressed',downloadedOnly?'true':'false')}
    if(downCount)downCount.textContent=String(downloaded);
    const summary=document.getElementById('librarySummary');
    if(summary)summary.textContent=`${counts.device} no celular · ${counts.drive} no Drive${counts.server?` · ${counts.server} no servidor`:''} · ${downloaded} offline`;
    const empty=counts.all===0,screen=document.getElementById('screen-music'),emptyCard=document.getElementById('emptyLibraryCard');
    screen?.classList.toggle('library-empty',empty);document.body.classList.toggle('library-empty-global',empty);if(emptyCard)emptyCard.hidden=!empty;
  }
  function replaceLibrary(key,tracks,label){
    libraries[key]=(tracks||[]).map(enrich);
    rebuildAll();
    const status=document.getElementById('musicStatus');
    if(status){status.textContent=label;status.classList.remove('player-error')}
    renderTracks();
  }
  async function loadServerMusic(){
    const status=document.getElementById('musicStatus');status.textContent='Sincronizando Drive e servidor...';
    try{
      const r=await fetch('api/library.php?action=list',{cache:'no-store'});
      const j=await r.json();
      if(j.csrf)window.MR_BOOTSTRAP.csrf=j.csrf;
      if(!r.ok||!j.ok)throw new Error(j.error||'Falha ao carregar biblioteca.');
      const tracks=(j.tracks||[]).map(enrich);
      libraries.drive=tracks.filter(t=>t._sourceKey==='drive');
      libraries.server=tracks.filter(t=>t._sourceKey==='server');
      rebuildAll();
      status.textContent=`Biblioteca online atualizada · ${libraries.drive.length} no Drive`;
      status.classList.remove('player-error');renderTracks();
    }catch(e){status.textContent=e.message||'Não foi possível carregar Drive/servidor.';status.classList.add('player-error')}
  }
  async function loadNativeMusic(){
    const status=document.getElementById('musicStatus');
    if(!Player.native){status.textContent='Músicas do celular aparecem no APK. No navegador, use o Drive.';return;}
    status.textContent='Lendo pastas e músicas do celular...';status.classList.add('music-loading');
    const data=await Player.getNativeLibrary();status.classList.remove('music-loading');
    if(!data.ok&&data.permission==='required'){status.textContent='Permissão de músicas necessária. Autorize o MusicRoad.';Player.requestNativePermission();return;}
    if(!data.ok){status.textContent=`Falha ao ler músicas do celular (${data.permission||'erro'}).`;status.classList.add('player-error');return;}
    replaceLibrary('device',data.tracks||[],`Celular atualizado · ${data.count||0} músicas`);
  }

  function trackHaystack(t){
    return fold([t.title,t.artist,t.album,t.genre,t._rhythm,t._folder,t.origin,t.origin_ref,t.folder,t.path].filter(Boolean).join(' '));
  }
  function matchesQuery(t,q){
    const tokens=fold(q).split(/\s+/).filter(Boolean);
    if(!tokens.length)return true;
    const hay=trackHaystack(t);
    return tokens.every(token=>hay.includes(token));
  }
  function filteredBase(){
    return sourceBase().filter(t=>{
      if(folderFilter!=='all'&&t._folder!==folderFilter)return false;
      if(rhythmFilter!=='all'&&t._rhythm!==rhythmFilter)return false;
      if(favoritesOnly&&!isFavorite(t))return false;
      return true;
    });
  }
  function renderFacets(){
    const base=sourceBase();
    const folders=new Map(),rhythms=new Map();
    base.forEach(t=>{
      folders.set(t._folder,(folders.get(t._folder)||0)+1);
      rhythms.set(t._rhythm,(rhythms.get(t._rhythm)||0)+1);
    });
    const folderRail=document.getElementById('folderRail');
    if(folderRail){
      const ordered=[...folders.entries()].sort((a,b)=>b[1]-a[1]||a[0].localeCompare(b[0],'pt-BR')).slice(0,30);
      folderRail.innerHTML=ordered.length?ordered.map(([name,count])=>`<button type="button" class="folder-card${folderFilter===name?' active':''}" data-folder="${encodeURIComponent(name)}"><span class="folder-icon">▰</span><strong></strong><small>${count} música${count===1?'':'s'}</small></button>`).join(''):'<span class="facet-empty">Nenhuma pasta encontrada</span>';
      [...folderRail.querySelectorAll('.folder-card')].forEach((btn,i)=>{
        const name=ordered[i][0];btn.querySelector('strong').textContent=name;
        btn.addEventListener('click',()=>{folderFilter=folderFilter===name?'all':name;renderFacets();renderTracks();});
      });
    }
    const rhythmRail=document.getElementById('rhythmRail');
    if(rhythmRail){
      const ordered=[...rhythms.entries()].sort((a,b)=>(a[0]==='Outros')-(b[0]==='Outros')||b[1]-a[1]);
      rhythmRail.innerHTML=ordered.map(([name,count])=>`<button type="button" class="rhythm-chip${rhythmFilter===name?' active':''}"><span></span><b>${count}</b></button>`).join('');
      [...rhythmRail.querySelectorAll('.rhythm-chip')].forEach((btn,i)=>{
        const name=ordered[i][0];btn.querySelector('span').textContent=name;
        btn.addEventListener('click',()=>{rhythmFilter=rhythmFilter===name?'all':name;renderFacets();renderTracks();});
      });
    }
  }
  function renderActiveFilters(){
    const box=document.getElementById('activeFilters');if(!box)return;
    const items=[];
    if(folderFilter!=='all')items.push(`Pasta: ${folderFilter}`);
    if(rhythmFilter!=='all')items.push(`Ritmo: ${rhythmFilter}`);
    if(favoritesOnly)items.push('Favoritas');
    box.innerHTML=items.map(v=>`<span>${esc(v)}</span>`).join('');
    box.classList.toggle('show',items.length>0);
  }
  function renderTracks(forceTracks=null){
    const list=document.getElementById('trackList');if(!list)return;
    const q=(document.getElementById('musicSearch')?.value||'').trim();
    let baseTracks=Array.isArray(forceTracks)?forceTracks:filteredBase();
    renderedTracks=baseTracks.filter(t=>matchesQuery(t,q)).slice(0,800);
    renderActiveFilters();
    if(!renderedTracks.length){list.innerHTML='<div class="empty-state">Nenhuma música encontrada com estes filtros.</div>';return;}
    const frag=document.createDocumentFragment();
    renderedTracks.forEach((track,i)=>{
      const key=track._sourceKey||sourceKey(track),row=document.createElement('div');
      row.className='track-row track-row-v2';row.dataset.trackIndex=String(i);
      const reason=track._ai_reason?`<small class="ai-reason"></small>`:'';
      const dstate=offlineState(track);
      const downloadButton=key==='drive'?`<button class="track-download state-${dstate.state||'none'}" type="button" aria-label="${dstate.state==='downloaded'?'Baixada no celular':dstate.state==='downloading'?'Baixando música':'Baixar para offline'}" title="${dstate.state==='downloaded'?'Baixada no celular':dstate.state==='downloading'?'Baixando…':'Baixar para offline'}" data-offline-key="${esc(offlineKey(track))}" style="--download-progress:${Math.max(0,Math.min(100,Number(dstate.progress)||0))}%"><span class="download-ring"></span><span class="download-glyph">${dstate.state==='downloaded'?'✓':dstate.state==='downloading'?`${Math.round(Number(dstate.progress)||0)}`:'↓'}</span></button>`:'';
      const favoriteButton=`<button class="track-favorite${isFavorite(track)?' active':''}" type="button" aria-label="${isFavorite(track)?'Remover dos favoritos':'Adicionar aos favoritos'}" title="Favorito">${isFavorite(track)?'★':'☆'}</button>`;
      const offlineBadge=(dstate.state==='downloaded'||dstate.state==='downloading')?'<em class="offline-badge"></em>':'';
      row.innerHTML=`<div class="track-art">♪</div><div class="track-meta"><strong></strong><span class="track-sub"></span><div class="track-badges"><em class="rhythm-badge"></em><em class="folder-badge"></em>${offlineBadge}</div>${reason}</div><div class="track-actions-v2">${favoriteButton}${downloadButton}<button class="track-play" type="button" aria-label="Tocar">▶</button></div>`;
      row.querySelector('strong').textContent=track.title;
      const sub=[track.artist||'Artista desconhecido',sourceLabel(key)].filter(Boolean).join(' · ');
      row.querySelector('.track-sub').textContent=sub;
      row.querySelector('.rhythm-badge').textContent=track._rhythm;
      row.querySelector('.folder-badge').textContent=track._folder;
      const aiReason=row.querySelector('.ai-reason');if(aiReason)aiReason.textContent=track._ai_reason;
      const offBadge=row.querySelector('.offline-badge');if(offBadge)offBadge.textContent=dstate.state==='downloaded'?'✓ Baixada':'↓ Baixando';
      frag.appendChild(row);
    });
    list.replaceChildren(frag);
    queueDownloadStateRefresh(80);
  }
  function setSourceFilter(key){
    sourceFilter=key;folderFilter='all';rhythmFilter='all';
    document.getElementById('aiMixResult')?.classList.remove('show');
    updateSourceCounts();renderFacets();renderTracks();
    const status=document.getElementById('musicStatus');
    if(status)status.textContent=`${key==='all'?'Todas as fontes':sourceLabel(key)} · ${sourceBase().length} músicas`;
  }
  document.querySelectorAll('[data-source-filter]').forEach(btn=>btn.addEventListener('click',()=>setSourceFilter(btn.dataset.sourceFilter)));
  document.getElementById('auroraFavorite')?.addEventListener('click',()=>{favoritesOnly=!favoritesOnly;showScreen('music');syncFavoriteHeader();renderFacets();renderTracks();const st=document.getElementById('musicStatus');if(st)st.textContent=favoritesOnly?'Mostrando somente favoritas':'Mostrando toda a biblioteca';});
  syncFavoriteHeader();
  document.getElementById('downloadedOnly')?.addEventListener('click',()=>{downloadedOnly=!downloadedOnly;folderFilter='all';rhythmFilter='all';updateSourceCounts();renderFacets();renderTracks();});
  document.getElementById('clearFolderFilter')?.addEventListener('click',()=>{folderFilter='all';renderFacets();renderTracks()});
  document.getElementById('clearRhythmFilter')?.addEventListener('click',()=>{rhythmFilter='all';renderFacets();renderTracks()});

  function safeDownloadName(track){
    const base=String(track?.title||'musica').replace(/[\/:*?"<>|]+/g,' ').replace(/\s+/g,' ').trim().slice(0,90)||'musica';
    const mime=String(track?.mime_type||'').toLowerCase();const ext=mime.includes('flac')?'.flac':mime.includes('wav')?'.wav':mime.includes('ogg')?'.ogg':mime.includes('mp4')||mime.includes('m4a')?'.m4a':'.mp3';
    return base.toLowerCase().endsWith(ext)?base:base+ext;
  }
  function driveId(track){
    if(track?.origin_ref)return String(track.origin_ref);
    try{const u=new URL(track?.source||'',location.href);return u.searchParams.get('id')||''}catch(_){return''}
  }
  function applyDownloadState(key,state,progress=0){
    if(!key)return;
    downloadStates.set(key,{state:state||'none',progress:Math.max(0,Math.min(100,Number(progress)||0))});
  }
  function updateDownloadRows(){
    document.querySelectorAll('.track-row-v2').forEach(row=>{
      const track=renderedTracks[Number(row.dataset.trackIndex)];if(!track)return;
      const btn=row.querySelector('.track-download');if(!btn)return;
      const ds=offlineState(track),state=ds.state||'none',progress=Math.max(0,Math.min(100,Number(ds.progress)||0));
      btn.className=`track-download state-${state}`;btn.style.setProperty('--download-progress',`${progress}%`);
      const glyph=btn.querySelector('.download-glyph');if(glyph)glyph.textContent=state==='downloaded'?'✓':state==='downloading'?String(Math.round(progress)):'↓';
      btn.title=state==='downloaded'?'Baixada no celular':state==='downloading'?`Baixando ${Math.round(progress)}%`:'Baixar para offline';
      btn.setAttribute('aria-label',btn.title);
      let badge=row.querySelector('.offline-badge');
      if(state==='downloaded'||state==='downloading'){
        if(!badge){badge=document.createElement('em');badge.className='offline-badge';row.querySelector('.track-badges')?.appendChild(badge)}
        if(badge)badge.textContent=state==='downloaded'?'✓ Baixada':'↓ Baixando';
      }else badge?.remove();
    });
    updateSourceCounts();
  }
  function readNativeDownloadStates(){
    if(!Player.native||!window.MusicRoadAndroid?.getOfflineDownloadStates)return false;
    const keys=[...new Set(libraries.drive.map(offlineKey).filter(Boolean))];if(!keys.length)return false;
    try{
      const raw=window.MusicRoadAndroid.getOfflineDownloadStates(JSON.stringify(keys));const data=JSON.parse(raw||'{}');
      Object.entries(data||{}).forEach(([key,v])=>applyDownloadState(key,v?.state||'none',v?.progress||0));
      return Object.values(data||{}).some(v=>v?.state==='downloading'||v?.state==='pending'||v?.state==='paused');
    }catch(_){return false}
  }
  function queueDownloadStateRefresh(delay=0){
    clearTimeout(downloadPollTimer);downloadPollTimer=setTimeout(()=>{
      const active=readNativeDownloadStates();updateDownloadRows();
      if(active)queueDownloadStateRefresh(900);
    },Math.max(0,delay));
  }
  function downloadDriveTrack(track){
    const id=driveId(track),key=offlineKey(track);if(!id||!key){showRoadAlert('danger','DOWNLOAD INDISPONÍVEL','Não encontrei o ID desta música no Drive.','',3500);return;}
    const current=offlineState(track);
    if(current.state==='downloaded'){showRoadAlert('info','JÁ ESTÁ OFFLINE',`${track.title} já está salva no celular.`,'',2800);return;}
    if(current.state==='downloading'){showRoadAlert('info','DOWNLOAD EM ANDAMENTO',`${track.title} · ${Math.round(current.progress||0)}%`,'',2500);return;}
    const url=new URL(`api/drive_stream.php?id=${encodeURIComponent(id)}&download=1`,location.href).href;
    const filename=safeDownloadName(track),status=document.getElementById('musicStatus');
    applyDownloadState(key,'downloading',1);updateDownloadRows();
    try{
      if(window.Player?.native&&window.MusicRoadAndroid?.downloadForOfflineTrack){
        const downloadId=window.MusicRoadAndroid.downloadForOfflineTrack(url,filename,track.mime_type||'audio/mpeg',key);
        if(Number(downloadId)<0)throw new Error('download');
        if(status)status.textContent=`Baixando para o celular · ${track.title}`;
        showRoadAlert('info','SALVANDO OFFLINE',`${track.title} · acompanhe o círculo de download na lista.`,'',3200);
        queueDownloadStateRefresh(350);return downloadId;
      }
      if(window.Player?.native&&window.MusicRoadAndroid?.downloadForOffline){
        const downloadId=window.MusicRoadAndroid.downloadForOffline(url,filename,track.mime_type||'audio/mpeg');
        if(status)status.textContent=`Download iniciado · ${track.title}`;
        showRoadAlert('info','DOWNLOAD INICIADO',`${track.title} será salva no celular.`,'',3000);queueDownloadStateRefresh(900);return downloadId;
      }
    }catch(_){applyDownloadState(key,'none',0);updateDownloadRows();}
    const a=document.createElement('a');a.href=url;a.download=filename;a.style.display='none';document.body.appendChild(a);a.click();a.remove();
    if(status)status.textContent=`Download solicitado · ${track.title}`;
  }
  async function toggleFavorite(track,row){
    const key=sourceKey(track),was=isFavorite(track),btn=row?.querySelector('.track-favorite');
    if(key==='device'){
      const fk=favoriteKey(track);if(was)localFavoriteKeys.delete(fk);else localFavoriteKeys.add(fk);saveLocalFavorites();track.is_favorite=was?0:1;
    }else{
      const id=Number(track?.id);if(!Number.isFinite(id)||id<=0)return;
      try{const r=await fetch('api/library.php?action=favorite',{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-Token':window.MR_BOOTSTRAP?.csrf||''},body:JSON.stringify({id})});const j=await r.json();if(!r.ok||!j.ok)throw new Error(j.error||'Falha ao favoritar.');track.is_favorite=was?0:1;}catch(e){showRoadAlert('danger','FAVORITOS',e.message||'Não foi possível atualizar o favorito.','',2600);return;}
    }
    const now=isFavorite(track);if(btn){btn.textContent=now?'★':'☆';btn.classList.toggle('active',now);btn.setAttribute('aria-label',now?'Remover dos favoritos':'Adicionar aos favoritos');}
    if(favoritesOnly&&!now)renderTracks();
  }
  document.getElementById('trackList')?.addEventListener('click',e=>{
    const row=e.target.closest('.track-row');if(!row)return;
    const track=renderedTracks[Number(row.dataset.trackIndex)];if(!track)return;
    if(e.target.closest('.track-download')){e.preventDefault();e.stopPropagation();downloadDriveTrack(track);return;}
    if(e.target.closest('.track-favorite')){e.preventDefault();e.stopPropagation();toggleFavorite(track,row);return;}
    Player.playTrack(track,renderedTracks);
  });
  document.addEventListener('mr:download-complete',e=>{
    const detail=e.detail||{};if(detail.key)applyDownloadState(detail.key,'downloaded',100);
    const status=document.getElementById('musicStatus');if(status)status.textContent='Download concluído ✓ · disponível offline no celular';
    showRoadAlert('info','DOWNLOAD CONCLUÍDO','Música salva no celular e pronta para ouvir offline.','',3000);
    updateDownloadRows();setTimeout(()=>{if(Player.native)loadNativeMusic();queueDownloadStateRefresh(200)},700);
  });
  document.getElementById('musicSearch')?.addEventListener('input',()=>renderTracks());
  document.getElementById('scanDeviceMusic')?.addEventListener('click',loadNativeMusic);
  document.getElementById('loadServerMusic')?.addEventListener('click',loadServerMusic);
  document.getElementById('refreshMusic')?.addEventListener('click',async()=>{
    const btn=document.getElementById('refreshMusic');btn?.classList.add('spinning');
    libraries.device=[];libraries.drive=[];libraries.server=[];rebuildAll();
    await loadServerMusic();if(Player.native)await loadNativeMusic();
    btn?.classList.remove('spinning');
  });
  document.addEventListener('mr:native-audio-permission',e=>{if(e.detail?.granted)loadNativeMusic()});
  document.getElementById('emptyGrantMusic')?.addEventListener('click',()=>{if(Player.native)loadNativeMusic();else{const st=document.getElementById('musicStatus');if(st)st.textContent='No navegador, sincronize o Drive. O acesso às músicas locais funciona no APK.';}});
  document.getElementById('emptySyncOnline')?.addEventListener('click',loadServerMusic);

  // Smart Mix legado removido na v15: biblioteca e filtros continuam locais e determinísticos.

  function mapSourceBadge(text,mode='online'){
    const el=document.getElementById('mapSourceBadge');if(!el)return;el.textContent=text;el.dataset.mode=mode;
  }
  function lightRoadStyle(feature){
    const rank=Number(feature?.properties?.rank)||7;
    if(rank<=1)return{weight:4.2,opacity:.90};
    if(rank===2)return{weight:3.5,opacity:.86};
    if(rank===3)return{weight:2.8,opacity:.82};
    if(rank===4)return{weight:2.2,opacity:.78};
    return{weight:1.35,opacity:.60};
  }
  function syncMapBaseMode(){
    if(!map)return;const localWanted=!navigator.onLine||baseTileErrors>=6;
    if(lightCityLayer){if(localWanted&&!map.hasLayer(lightCityLayer))lightCityLayer.addTo(map);if(!localWanted&&map.hasLayer(lightCityLayer))map.removeLayer(lightCityLayer);}
    const deviceLayer=offlineDetailLayer&&map.hasLayer(offlineDetailLayer),hasLocal=!!deviceLayer||!!lightCityLayer;
    const mapboxActive=!!(navigator.onLine&&!localWanted&&mapboxBase?.ready?.()&&mapboxBase.setVisible(true));
    if(mapboxBase&&!mapboxActive)mapboxBase.setVisible(false);
    if(baseTileLayer){const useOsm=!mapboxActive&&!(localWanted&&hasLocal);if(useOsm&&!map.hasLayer(baseTileLayer))baseTileLayer.addTo(map);if(!useOsm&&map.hasLayer(baseTileLayer))map.removeLayer(baseTileLayer);baseTileLayer.setOpacity(1);}
    if(localWanted&&deviceLayer)mapSourceBadge('MAPA NO DISPOSITIVO','local');
    else if(localWanted&&lightCityLayer)mapSourceBadge('OFFLINE LIGHT','local');
    else if(mapboxActive)mapSourceBadge('MAPBOX PREMIUM','mapbox');
    else if(deviceLayer)mapSourceBadge('ONLINE · DEVICE PRONTO','ready');
    else if(lightCityPack)mapSourceBadge('ONLINE · LOCAL PRONTO','ready');
    else mapSourceBadge(navigator.onLine?'MAPA ONLINE':'SEM MAPA LOCAL',navigator.onLine?'online':'offline');
  }
  function renderLightCityMap(pack){
    if(!map||!window.L||!pack?.features)return false;
    try{if(lightCityLayer&&map.hasLayer(lightCityLayer))map.removeLayer(lightCityLayer)}catch(_){}
    try{
      lightCityLayer=L.geoJSON(pack,{renderer:L.canvas({padding:.5}),style:lightRoadStyle,interactive:false});
      lightCityPack=pack;lightCityLoadedCode=String(pack?.musicRoad?.city_id||'');syncMapBaseMode();return true;
    }catch(_){lightCityLayer=null;lightCityPack=null;lightCityLoadedCode='';return false;}
  }
  const LIGHT_MAP_DEVICE_CACHE='musicroad-light-map-v1';
  async function loadLightCityMap(code){
    code=String(code||'');if(!/^\d{7}$/.test(code))return false;if(lightCityLoadedCode===code&&lightCityPack)return true;
    const url=`api/map_light.php?action=data&city_id=${encodeURIComponent(code)}`;
    try{
      let response=null;
      if(navigator.onLine){
        try{const r=await fetch(url,{cache:'no-store'});if(r.ok){response=r;if('caches' in window){const c=await caches.open(LIGHT_MAP_DEVICE_CACHE);await c.put(url,r.clone());}}}catch(_){}
      }
      if(!response&&'caches' in window){const c=await caches.open(LIGHT_MAP_DEVICE_CACHE);response=await c.match(url);}
      if(!response||!response.ok)return false;const pack=await response.json();return renderLightCityMap(pack);
    }catch(_){return false;}
  }
  function formatMapBytes(n){n=Number(n)||0;if(n<1024)return `${n} B`;if(n<1048576)return `${(n/1024).toFixed(1)} KB`;return `${(n/1048576).toFixed(n<10485760?1:0)} MB`;}
  async function refreshLightMapStats(){
    const st=document.getElementById('lightMapServerStats'),btn=document.getElementById('prepareLightCityMap');if(btn)btn.disabled=!destinationSelection.cityId;
    try{const r=await fetch('api/map_light.php?action=stats',{cache:'no-store'}),j=await r.json();if(!r.ok||!j?.ok)throw new Error('status indisponível');const x=j.map_light||{};if(st)st.textContent=`${Number(x.city_count||0)} cidade(s) no servidor · ${formatMapBytes(x.size_bytes)} usados de ${formatMapBytes(x.limit_bytes)} · limpeza automática quando atingir o limite.`;}catch(_){if(st)st.textContent='Mapa Light: status do servidor indisponível.';}
  }
  async function prepareSelectedLightMap(force=false,quiet=true){
    const code=String(destinationSelection.cityId||''),uf=String(destinationSelection.uf||''),city=String(destinationSelection.city||'');if(!/^\d{7}$/.test(code)||!/^[A-Z]{2}$/.test(uf)||!city)return false;
    const status=document.getElementById('lightMapServerStats');if(lightMapPreparingCode===code)return false;lightMapPreparingCode=code;
    try{
      const sr=await fetch(`api/map_light.php?action=status&city_id=${encodeURIComponent(code)}`,{cache:'no-store'}),sj=await sr.json().catch(()=>null);
      if(sr.ok&&sj?.ready){await loadLightCityMap(code);if(status&&!quiet)status.textContent=`${city}, ${uf}: mapa local pronto · ${Number(sj.feature_count||0).toLocaleString('pt-BR')} vias/trechos · ${formatMapBytes(sj.size_bytes)}.`;return true;}
      if(!navigator.onLine){if(status&&!quiet)status.textContent='Sem internet: esta cidade ainda não foi preparada no servidor.';return false;}
      if(status&&!quiet)status.textContent=`Preparando mapa leve de ${city}… O primeiro preparo pode demorar um pouco.`;
      const r=await fetch(`api/map_light.php?action=prepare&city_id=${encodeURIComponent(code)}&uf=${encodeURIComponent(uf)}&city=${encodeURIComponent(city)}${force?'&force=1':''}`,{method:'POST',credentials:'same-origin',cache:'no-store',headers:{'X-CSRF-Token':String(window.MR_BOOTSTRAP?.csrf||'')}}),j=await r.json().catch(()=>null);
      if(!r.ok||!j?.ok)throw new Error(j?.error||'Não foi possível preparar o mapa local.');await loadLightCityMap(code);
      if(status&&!quiet)status.textContent=`${city}, ${uf}: pronto ✓ · ${Number(j.feature_count||0).toLocaleString('pt-BR')} vias/trechos · ${formatMapBytes(j.size_bytes)}.`;
      await refreshLightMapStats();return true;
    }catch(e){if(status&&!quiet)status.textContent=e.message||'Falha ao preparar mapa local.';return false;}finally{lightMapPreparingCode='';}
  }
  function queueSelectedLightMap(){if(!destinationSelection.cityId)return;setTimeout(()=>loadOfflineCityRoads(destinationSelection.cityId),180);}
  function initMap(){
    if(!window.L)return;
    map=L.map('map',{zoomControl:false,preferCanvas:true,minZoom:4,maxZoom:19}).setView([-14.235,-51.9253],4);
    L.control.zoom({position:'bottomright'}).addTo(map);
    baseTileLayer=L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{
      maxZoom:19,maxNativeZoom:19,detectRetina:true,updateWhenIdle:true,keepBuffer:4,
      attribution:'© OpenStreetMap'
    }).addTo(map);
    baseTileLayer.on('tileerror',()=>{baseTileErrors=Math.min(20,baseTileErrors+1);if(baseTileErrors>=6)syncMapBaseMode();});
    baseTileLayer.on('tileload',()=>{if(baseTileErrors>0)baseTileErrors--;if(baseTileErrors===0)syncMapBaseMode();});
    mapboxBase=window.MRMapboxBase?.mount({host:document.getElementById('map'),leafletMap:map,onReady:syncMapBaseMode,onFallback:syncMapBaseMode})||null;
    radarLayer=L.layerGroup().addTo(map);syncMapBaseMode();
    map.on('dragstart',()=>{followMap=false;document.getElementById('recenterMap').textContent='◎ Seguir';});
  }
  function gpsStatus(text,kind='neutral'){const el=document.getElementById('gpsStatus');if(!el)return;el.textContent=text;el.className=`v15-home-status ${kind}`}
  function fmtDistance(m){if(!Number.isFinite(m))return '--';return m>=1000?`${(m/1000).toFixed(m>=10000?0:1)} km`:`${Math.max(0,Math.round(m))} m`;}
  function radarKey(r){return String(r.external_id||r.id||`${Number(r.latitude).toFixed(5)},${Number(r.longitude).toFixed(5)}`)}
  function showRoadAlert(kind,title,text,voice='',duration=5200){
    if(voice&&alertPrefs.voice!==false)Player.speak(voice);
    if(alertPrefs.visual===false)return;
    const box=document.getElementById('roadAlert');if(!box)return;
    clearTimeout(alertTimer);
    box.style.setProperty('--alert-duration',`${Math.max(1800,Number(duration)||5200)}ms`);
    box.className=`road-alert show ${kind||'warning'}`;
    document.getElementById('roadAlertIcon').textContent=kind==='danger'?'⚠':kind==='radar'?'◉':kind==='portable'?'⚡':kind==='video'?'CAM':kind==='signal'?'●':kind==='bump'?'⌁':'ℹ';
    document.getElementById('roadAlertTitle').textContent=title||'Atenção';
    document.getElementById('roadAlertText').textContent=text||'';
    const distanceEl=document.getElementById('roadAlertDistance'),match=String(text||'').match(/~?\s*(\d+(?:[.,]\d+)?)\s*(km|m)\b/i);
    if(distanceEl){distanceEl.hidden=!match;distanceEl.textContent=match?`${match[1].replace(',', '.')} ${match[2].toLowerCase()}`:'';}
    alertTimer=setTimeout(()=>box.classList.remove('show'),Math.max(1800,Number(duration)||5200));
  }
  function sampleRouteForNative(coords,max=1600){
    const src=(coords||[]).filter(c=>Array.isArray(c)&&Number.isFinite(Number(c[0]))&&Number.isFinite(Number(c[1])));
    if(src.length<=max)return src.map(c=>[Number(c[0]),Number(c[1])]);
    const out=[],step=(src.length-1)/(max-1);for(let i=0;i<max;i++){const c=src[Math.min(src.length-1,Math.round(i*step))];out.push([Number(c[0]),Number(c[1])]);}return out;
  }
  function mergeSpeedLimits(base,extra){
    const out=(base||[]).slice();for(const x of (extra||[])){const m=Number(x.route_m),v=Number(x.velocidade);if(!Number.isFinite(m)||!v)continue;if(!out.some(e=>Math.abs(Number(e.route_m)-m)<120&&Number(e.velocidade)===v))out.push({...x,route_m:m,velocidade:v});}
    return out.sort((a,b)=>Number(a.route_m)-Number(b.route_m));
  }
  function roadLimitAtProgress(progress){
    let found=null;for(const x of currentSpeedLimits){const m=Number(x.route_m);if(!Number.isFinite(m)||m>progress+80)break;if(progress-m<=6000)found=x;}return found;
  }
  function projectOnCurrentRoute(lat,lon){
    if(routeLatLngs.length<2)return {progress:0,offRoute:Infinity};
    let bestD=Infinity,bestI=0;const step=Math.max(1,Math.floor(routeLatLngs.length/1800));
    for(let i=0;i<routeLatLngs.length;i+=step){const p=routeLatLngs[i],d=distM(lat,lon,p[0],p[1]);if(d<bestD){bestD=d;bestI=i;}}
    const from=Math.max(0,bestI-step*4),to=Math.min(routeLatLngs.length-2,bestI+step*4),latScale=110540,lonScale=111320*Math.max(.2,Math.cos(lat*Math.PI/180));let progress=routeCumM[bestI]||0;
    for(let i=from;i<=to;i++){const a=routeLatLngs[i],b=routeLatLngs[i+1],ax=(a[1]-lon)*lonScale,ay=(a[0]-lat)*latScale,bx=(b[1]-lon)*lonScale,by=(b[0]-lat)*latScale,vx=bx-ax,vy=by-ay,den=vx*vx+vy*vy,t=den?Math.max(0,Math.min(1,-(ax*vx+ay*vy)/den)):0,x=ax+t*vx,y=ay+t*vy,d=Math.hypot(x,y);if(d<bestD){bestD=d;progress=(routeCumM[i]||0)+((routeCumM[i+1]||0)-(routeCumM[i]||0))*t;}}
    return {progress,offRoute:bestD};
  }
  function routeMAt(lat,lon){return projectOnCurrentRoute(lat,lon).progress;}
  function routeManeuvers(route=currentRoute){
    const out=[];let idx=0;
    const steps=currentGuidanceSteps.length?currentGuidanceSteps:(route?.legs||[]).flatMap(leg=>leg?.steps||[]);
    for(const step of steps){
        const m=step?.maneuver||{},loc=m.location||[];
        const lon=Number(loc[0]),lat=Number(loc[1]);if(!Number.isFinite(lat)||!Number.isFinite(lon))continue;
        const type=String(m.type||'turn').toLowerCase(),modifier=String(m.modifier||'').toLowerCase();
        if(type==='depart'||type==='notification'||type==='new name')continue;
        if(type==='continue'&&(!modifier||modifier==='straight')&&Number(step.distance)>900)continue;
        const item={nav_type:'maneuver',key:`nav-${idx++}-${lat.toFixed(5)}-${lon.toFixed(5)}`,latitude:lat,longitude:lon,route_m:routeMAt(lat,lon),type,modifier,name:String(step.name||''),destinations:String(step.destinations||''),exit:Number(m.exit)||0};
        const prev=out[out.length-1];if(prev&&Math.abs(prev.route_m-item.route_m)<15&&prev.type===item.type&&prev.modifier===item.modifier)continue;
        out.push(item);
    }
    return out.sort((a,b)=>a.route_m-b.route_m);
  }
  async function loadRouteGuidance(originGeo,destinationGeo){
    const olat=Number(originGeo?.lat),olon=Number(originGeo?.lon),dlat=Number(destinationGeo?.lat),dlon=Number(destinationGeo?.lon);
    if(![olat,olon,dlat,dlon].every(Number.isFinite))return;
    try{
      const r=await fetch(`api/route_guidance.php?olat=${encodeURIComponent(olat)}&olon=${encodeURIComponent(olon)}&dlat=${encodeURIComponent(dlat)}&dlon=${encodeURIComponent(dlon)}`,{cache:'no-store'});
      const j=await r.json();if(!r.ok||!j.ok)throw new Error(j.error||'Orientação indisponível');
      currentGuidanceSteps=Array.isArray(j.steps)?j.steps:[];currentManeuvers=routeManeuvers(currentRoute);syncNativeTrip(false);saveOfflineTrip({maneuvers:currentManeuvers});
    }catch(_){currentGuidanceSteps=[];currentManeuvers=[];}
  }
  function maneuverAction(m){
    const type=String(m?.type||'turn').toLowerCase(),mod=String(m?.modifier||'').toLowerCase(),name=String(m?.name||'').trim();
    const road=name?` na ${name}`:'';
    if(type==='arrive')return 'Destino à frente';
    if(type.includes('roundabout')||type.includes('rotary'))return Number(m.exit)>0?`Na rotatória, pegue a saída número ${Number(m.exit)}${road}`:`Entre na rotatória${road}`;
    if(type==='off ramp'||type==='exit roundabout'||type==='exit rotary')return `Pegue a saída${mod.includes('left')?' à esquerda':mod.includes('right')?' à direita':''}${road}`;
    if(type==='on ramp')return `Pegue o acesso${mod.includes('left')?' à esquerda':mod.includes('right')?' à direita':''}${road}`;
    if(type==='fork')return `Mantenha-se ${mod.includes('left')?'à esquerda':mod.includes('right')?'à direita':'em frente'}${road}`;
    if(type==='merge')return `Entre ${mod.includes('left')?'à esquerda':mod.includes('right')?'à direita':'na via'}${road}`;
    if(mod==='uturn')return 'Faça o retorno';
    if(mod.includes('left'))return `Vire à esquerda${road}`;
    if(mod.includes('right'))return `Vire à direita${road}`;
    return `Siga em frente${road}`;
  }
  function maneuverTitle(m){
    const a=maneuverAction(m).toUpperCase();
    if(a.startsWith('VIRE À DIREITA'))return 'VIRE À DIREITA';if(a.startsWith('VIRE À ESQUERDA'))return 'VIRE À ESQUERDA';if(a.startsWith('FAÇA O RETORNO'))return 'FAÇA O RETORNO';if(a.startsWith('NA ROTATÓRIA'))return 'ROTATÓRIA';if(a.startsWith('PEGUE A SAÍDA'))return 'PEGUE A SAÍDA';if(a.startsWith('PEGUE O ACESSO'))return 'PEGUE O ACESSO';if(a.startsWith('DESTINO'))return 'CHEGANDO AO DESTINO';return 'SIGA EM FRENTE';
  }
  function nextManeuver(progress){return currentManeuvers.find(m=>Number(m.route_m)>=progress-25)||null;}
  function updateManeuverGuidance(lat,lon){
    if(!tripActive||!currentRoute)return;const progress=currentRouteProgress(lat,lon).progress,m=nextManeuver(progress);if(!m)return;
    const distance=Number(m.route_m)-progress,key=String(m.key);if(distance<-25||distance>380)return;
    if(distance<=300&&distance>60&&!maneuverPrepareWarned.has(key)){
      maneuverPrepareWarned.add(key);const meters=distance>230?300:distance>150?200:100,action=maneuverAction(m);
      showRoadAlert('info','PRÓXIMA MANOBRA',`Em ${meters} m · ${action}`,nativeTripActive?'':`Em ${meters} metros. ${action}.`,4200);
    }
    if(distance<=55&&distance>=-20&&!maneuverNowWarned.has(key)){
      maneuverNowWarned.add(key);const action=maneuverAction(m);showRoadAlert('info',maneuverTitle(m),action,nativeTripActive?'':`${action} agora.`,4800);
    }
  }
  function stopWebGpsWatch(){if(watchId!==null&&navigator.geolocation){try{navigator.geolocation.clearWatch(watchId)}catch(_){}watchId=null;}}
  function syncNativeTrip(start=false){
    if(!currentRoute?.geometry?.coordinates?.length||!window.MusicRoadAndroid)return false;
    const api=window.MusicRoadAndroid;if(typeof api.startNavigation!=='function')return false;
    try{
      const route=sampleRouteForNative(currentRoute.geometry.coordinates);
      const hazards=currentRadars.map(r=>({external_id:r.external_id||String(r.id||''),latitude:Number(r.latitude),longitude:Number(r.longitude),route_m:Number(r.route_m),velocidade:Number(r.velocidade)||null,tipo:r.tipo||'RADAR',heading:Number.isFinite(Number(r.heading))?Number(r.heading):null,alert_radius_m:Number(r.alert_radius_m)||null,fonte:r.fonte||''}));
      const limits=currentSpeedLimits.map(x=>({latitude:Number(x.latitude),longitude:Number(x.longitude),route_m:Number(x.route_m),velocidade:Number(x.velocidade),rodovia:x.rodovia||''}));
      const navData=[...limits,...currentManeuvers];
      if(start||!nativeTripActive){nativeTripActive=api.startNavigation(JSON.stringify(route),JSON.stringify(hazards),JSON.stringify(navData),tripDestinationLabel||'Destino')!==false;}
      else if(typeof api.updateNavigation==='function')api.updateNavigation(JSON.stringify(hazards),JSON.stringify(navData));
      return nativeTripActive;
    }catch(_){return false;}
  }
  function stopNativeTrip(){
    try{if(window.MusicRoadAndroid?.stopNavigation)window.MusicRoadAndroid.stopNavigation();}catch(_){}
    nativeTripActive=false;
  }
  document.addEventListener('mr:native-nav',ev=>{
    const d=ev.detail||{};nativeNavLastEventAt=Date.now();
    if(d.type==='position'){
      nativeTripActive=true;
      const pos={coords:{latitude:Number(d.lat),longitude:Number(d.lon),speed:Number(d.speed_mps)||0,accuracy:Number(d.accuracy)||99,heading:Number.isFinite(Number(d.heading))?Number(d.heading):null}};
      if(Number.isFinite(pos.coords.latitude)&&Number.isFinite(pos.coords.longitude))updatePosition(pos);
      if(Number(d.limit)>0){document.getElementById('speedLimit').textContent=String(Math.round(Number(d.limit)));document.getElementById('mapLimit').textContent=String(Math.round(Number(d.limit)));}
      return;
    }
    if(d.type==='hazard'){
      const kind=d.kind||'info';showRoadAlert(kind,d.title||'FISCALIZAÇÃO',d.text||'', '',Number(d.duration)||5200);return;
    }
    if(d.type==='reroute'&&Number.isFinite(Number(d.lat))&&Number.isFinite(Number(d.lon))){rerouteFromCurrent(Number(d.lat),Number(d.lon));return;}
    if(d.type==='status'&&d.message)showRoadAlert('info','MOTOR DE BORDO',String(d.message),'',3000);
  });

  function updateUserMarker(latitude,longitude,heading){
    if(!map)return;
    if(!userMarker){
      const icon=L.divIcon({className:'navigation-marker',html:'<div class="navigation-marker-ring"><span class="navigation-arrow">▲</span></div>',iconSize:[34,34],iconAnchor:[17,17]});
      userMarker=L.marker([latitude,longitude],{icon,zIndexOffset:1200,interactive:false}).addTo(map);
    }else userMarker.setLatLng([latitude,longitude]);
    const el=userMarker.getElement()?.querySelector('.navigation-arrow');
    if(el&&Number.isFinite(heading))el.style.transform=`rotate(${heading}deg)`;
  }
  function updatePosition(pos){
    lastPos=pos;
    const {latitude,longitude,speed,accuracy,heading}=pos.coords;
    const kmh=Number.isFinite(speed)&&speed!==null?Math.max(0,Math.round(speed*3.6)):0;
    document.getElementById('speedNow').textContent=kmh;
    document.getElementById('mapSpeed').textContent=kmh;
    gpsStatus(`GPS ±${Math.round(accuracy||0)}m`,'');
    updateUserMarker(latitude,longitude,heading);if(!navigator.onLine)activateOfflineMapForPosition(latitude,longitude);
    if(map&&activeScreen==='map'){
      const target=[latitude,longitude];
      if(!tripActive){if(followMap||map.getZoom()<10)map.setView(target,16,{animate:false});}
      else if(followMap){if(map.getZoom()<15)map.setView(target,16,{animate:false});else map.panTo(target,{animate:true,duration:.35});}
    }
    updateNearestRadar(latitude,longitude,kmh,accuracy||99);
    updateManeuverGuidance(latitude,longitude);
    checkRouteDeviation(latitude,longitude,kmh,accuracy||99);
  }
  function startGps(){
    if(!navigator.geolocation){gpsStatus('GPS indisponível','offline');return;}
    navigator.geolocation.getCurrentPosition(updatePosition,()=>gpsStatus('GPS bloqueado','offline'),{enableHighAccuracy:true,timeout:15000,maximumAge:3000});
    if(watchId===null)watchId=navigator.geolocation.watchPosition(updatePosition,()=>{}, {enableHighAccuracy:true,timeout:30000,maximumAge:1500});
  }
  document.getElementById('useLocation')?.addEventListener('click',()=>{startGps();document.getElementById('origin').value='Minha localização';});
  document.getElementById('recenterMap')?.addEventListener('click',()=>{
    startGps();followMap=true;document.getElementById('recenterMap').textContent='◎ Seguindo';
    if(lastPos)map?.setView([lastPos.coords.latitude,lastPos.coords.longitude],16,{animate:true});
  });
  document.getElementById('fitRoute')?.addEventListener('click',()=>{
    followMap=false;document.getElementById('recenterMap').textContent='◎ Seguir';
    if(routeLayer?.getBounds?.().isValid())map.fitBounds(routeLayer.getBounds(),{padding:[34,34]});
  });
  function pointKind(r){
    const t=String(r?.tipo||'').toUpperCase();
    if(t.includes('VIDEO')||t.includes('OCR')||t.includes('MONITOR'))return'video';
    if(t.includes('QUEBRA')||t.includes('LOMBADA')||t.includes('BUMP')||t.includes('HUMP')||t.includes('TRAFFIC_CALMING'))return'bump';
    if(t.includes('SEMAFOR')||t.includes('SEMÁFOR')||t.includes('TRAFFIC_SIGNAL')||t.includes('AVANCO')||t.includes('AVANÇO'))return'signal';
    if(t.includes('PORTAT')||t.includes('MOVEL')||t.includes('MÓVEL'))return'portable';
    return'radar';
  }
  function pointLabel(r){const k=pointKind(r),t=String(r?.tipo||'').toUpperCase();if(k==='video')return'Videomonitoramento';if(k==='bump')return'Quebra-mola';if(k==='signal')return t.includes('FISCALIZ')||t.includes('AVAN')?'Fiscalização semafórica':'Semáforo';if(k==='portable')return'Fiscalização portátil';return'Radar';}
  function isSpeedPoint(r){const k=pointKind(r);return k==='radar'||k==='portable';}
  function mapKindForPoint(r){const k=pointKind(r);return k==='portable'?'radar':k;}
  function mapKindEnabled(kind){if(kind==='radar')return alertPrefs.radar!==false||alertPrefs.portable!==false;return alertPrefs[kind]!==false;}
  function pointVisibleOnMap(r){return mapKindEnabled(mapKindForPoint(r));}
  function refreshFiscalCounts(){
    const counts={radar:0,bump:0,video:0,signal:0};currentRadars.forEach(r=>{const k=mapKindForPoint(r);if(k in counts)counts[k]++});
    Object.entries(counts).forEach(([k,v])=>{const el=document.querySelector(`[data-kind-count="${k}"]`);if(el)el.textContent=String(v)});
    document.querySelectorAll('[data-map-kind]').forEach(btn=>{const k=btn.dataset.mapKind,on=mapKindEnabled(k);btn.classList.toggle('is-on',on);btn.setAttribute('aria-pressed',on?'true':'false')});
  }
  function setMapKind(kind,on){
    if(kind==='radar'){alertPrefs.radar=on;alertPrefs.portable=on;}else alertPrefs[kind]=on;
    saveAlertPrefs();refreshFiscalCounts();renderRadarMarkers();
    if(lastPos)updateNearestRadar(lastPos.coords.latitude,lastPos.coords.longitude,Math.round((lastPos.coords.speed||0)*3.6),lastPos.coords.accuracy||99);
  }
  const fiscalPanel=document.getElementById('mapFiscalPanel'),fiscalToggle=document.getElementById('mapFiscalToggle');
  function closeFiscalPanel(){fiscalPanel?.classList.remove('open');fiscalPanel?.setAttribute('aria-hidden','true');fiscalToggle?.classList.remove('active');fiscalToggle?.setAttribute('aria-expanded','false')}
  function toggleFiscalPanel(){const open=!fiscalPanel?.classList.contains('open');if(open){refreshFiscalCounts();fiscalPanel?.classList.add('open');fiscalPanel?.setAttribute('aria-hidden','false');fiscalToggle?.classList.add('active');fiscalToggle?.setAttribute('aria-expanded','true')}else closeFiscalPanel()}
  fiscalToggle?.addEventListener('click',toggleFiscalPanel);document.getElementById('closeMapFiscal')?.addEventListener('click',closeFiscalPanel);
  document.querySelectorAll('[data-map-kind]').forEach(btn=>btn.addEventListener('click',()=>setMapKind(btn.dataset.mapKind,!mapKindEnabled(btn.dataset.mapKind))));
  document.addEventListener('click',ev=>{if(!fiscalPanel?.classList.contains('open'))return;if(fiscalPanel.contains(ev.target)||fiscalToggle?.contains(ev.target))return;closeFiscalPanel()});
  let pendingPointType=null,pendingPointLabel='';
  function resetPointSheet(){
    pendingPointType=null;pendingPointLabel='';
    const typeStep=document.getElementById('pointTypeStep'),speedStep=document.getElementById('pointSpeedStep');
    if(typeStep)typeStep.hidden=false;if(speedStep)speedStep.hidden=true;
    const custom=document.getElementById('customPointSpeed');if(custom)custom.value='';
  }
  function openPointSheet(){
    if(!lastPos){showRoadAlert('danger','GPS NECESSÁRIO','Aguarde o GPS localizar o veículo.','',3500);startGps();return;}
    resetPointSheet();const sh=document.getElementById('pointSheet');if(!sh)return;document.body.classList.add('point-sheet-open');sh.classList.add('open');sh.setAttribute('aria-hidden','false');
  }
  function closePointSheet(){const sh=document.getElementById('pointSheet');document.body.classList.remove('point-sheet-open');if(!sh)return;sh.classList.remove('open');sh.setAttribute('aria-hidden','true');resetPointSheet();}
  function choosePointType(type,label){
    if(!isSpeedPoint({tipo:type})){reportPointHere(type,label,null);return;}
    pendingPointType=type;pendingPointLabel=label;
    const typeStep=document.getElementById('pointTypeStep'),speedStep=document.getElementById('pointSpeedStep');
    if(typeStep)typeStep.hidden=true;if(speedStep)speedStep.hidden=false;
    const lbl=document.getElementById('pointSpeedLabel');if(lbl)lbl.textContent=label;
  }
  async function reportPointHere(type,label,explicitSpeed=null){
    if(!lastPos)return;
    const lat=lastPos.coords.latitude,lon=lastPos.coords.longitude;
    const speed=explicitSpeed===null||explicitSpeed===''?null:Math.max(10,Math.min(150,Number(explicitSpeed)||0));
    const payload={external_id:`community-${type.toLowerCase()}-${Date.now()}-${lat.toFixed(5)}-${lon.toFixed(5)}`,latitude:lat,longitude:lon,velocidade:speed||null,tipo:type,situacao:'ATIVO',fonte:'MUSICROAD_COMUNIDADE',confiabilidade:'BAIXA',quantidade_fontes:1,ativo:1,position_accuracy:'GPS_USUARIO'};
    try{
      const r=await fetch('api/radars.php?action=save',{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-Token':window.MR_BOOTSTRAP?.csrf||''},body:JSON.stringify(payload)});
      const j=await r.json();if(!r.ok||!j.ok)throw new Error(j.error||'Falha ao registrar ponto.');
      const kind=pointKind(payload);const speedText=isSpeedPoint(payload)?(payload.velocidade?` · ${payload.velocidade} km/h`:' · velocidade não informada'):'';
      showRoadAlert(kind,'ENVIADO PARA ANÁLISE',`${j.message||'Ponto aguardando validação'}${speedText}.`,'Ponto enviado para validação.',4500);
    }catch(e){showRoadAlert('danger','NÃO FOI POSSÍVEL SALVAR',e.message||'Erro ao registrar ponto.','',4500);}
    finally{closePointSheet();}
  }
  document.getElementById('reportPoint')?.addEventListener('click',openPointSheet);
  document.getElementById('closePointSheet')?.addEventListener('click',closePointSheet);
  document.getElementById('pointSheetBackdrop')?.addEventListener('click',closePointSheet);
  document.getElementById('backPointType')?.addEventListener('click',resetPointSheet);
  document.querySelectorAll('[data-point-type]').forEach(btn=>btn.addEventListener('click',()=>choosePointType(btn.dataset.pointType||'RADAR_REPORTADO',btn.dataset.pointLabel||'Ponto de fiscalização')));
  document.querySelectorAll('[data-point-speed]').forEach(btn=>btn.addEventListener('click',()=>{if(pendingPointType)reportPointHere(pendingPointType,pendingPointLabel,Number(btn.dataset.pointSpeed));}));
  document.getElementById('saveCustomPointSpeed')?.addEventListener('click',()=>{
    const value=Number(document.getElementById('customPointSpeed')?.value||0);
    if(value<20||value>130){showRoadAlert('danger','VELOCIDADE INVÁLIDA','Informe uma velocidade entre 20 e 130 km/h.','',3000);return;}
    if(pendingPointType)reportPointHere(pendingPointType,pendingPointLabel,value);
  });
  document.getElementById('saveUnknownPointSpeed')?.addEventListener('click',()=>{if(pendingPointType)reportPointHere(pendingPointType,pendingPointLabel,null);});

  function distM(a,b,c,d){const R=6371000,r=x=>x*Math.PI/180,dl=r(c-a),dn=r(d-b);const h=Math.sin(dl/2)**2+Math.cos(r(a))*Math.cos(r(c))*Math.sin(dn/2)**2;return R*2*Math.atan2(Math.sqrt(h),Math.sqrt(1-h));}
  function buildRouteProgress(coords){
    routeLatLngs=(coords||[]).map(c=>[+c[1],+c[0]]).filter(c=>Number.isFinite(c[0])&&Number.isFinite(c[1]));
    routeCumM=[];let total=0;
    routeLatLngs.forEach((p,i)=>{if(i)total+=distM(routeLatLngs[i-1][0],routeLatLngs[i-1][1],p[0],p[1]);routeCumM[i]=total;});
    lastRouteProgressM=0;
  }
  function currentRouteProgress(lat,lon){const p=projectOnCurrentRoute(lat,lon);if(p.offRoute<500||p.progress>=lastRouteProgressM-350)lastRouteProgressM=Math.max(0,p.progress);return {progress:lastRouteProgressM,offRoute:p.offRoute};}
  async function rerouteFromCurrent(lat,lon){
    if(rerouteInFlight||!tripDestinationCoords||!navigator.onLine)return;
    rerouteInFlight=true;lastRerouteAt=Date.now();offRouteSamples=0;
    showRoadAlert('info','RECALCULANDO ROTA','Você saiu do trajeto. Buscando uma nova rota e nova fiscalização.','Recalculando rota.',4200);
    try{
      const origin=`${lat},${lon}`,dest=`${tripDestinationCoords.lat},${tripDestinationCoords.lon}`;
      const r=await fetch(`api/route.php?origin=${encodeURIComponent(origin)}&destination=${encodeURIComponent(dest)}`,{cache:'no-store'});
      const j=await r.json();if(!r.ok||!j.ok)throw new Error(j.error||'Não foi possível recalcular a rota.');
      drawRoute(j);tripDestinationCoords={lat:Number(j.destination?.lat),lon:Number(j.destination?.lon)};tripDestinationLabel=tripDestinationLabel||j.destination?.display_name||'';
      saveOfflineTrip({origin,destination:tripDestinationLabel||dest,data:j,radars:[],active:true});
      loadRouteGuidance(j.origin,j.destination);await loadRouteRadars(j.route?.geometry?.coordinates||[]);
      showRoadAlert('info','NOVA ROTA PRONTA','Trajeto atualizado e pontos de fiscalização recarregados.','Nova rota pronta.',3500);
    }catch(e){showRoadAlert('danger','FALHA AO RECALCULAR',e.message||'Continue na rota atual e tente novamente.','',4000);}finally{rerouteInFlight=false;}
  }
  function checkRouteDeviation(lat,lon,kmh,accuracy){
    if(!tripActive||!currentRoute||!routeLatLngs.length||!tripDestinationCoords||rerouteInFlight)return;
    if(!Number.isFinite(accuracy)||accuracy>80)return;
    const rp=currentRouteProgress(lat,lon),threshold=Math.max(85,accuracy*1.8);
    if(rp.offRoute>threshold)offRouteSamples++;else offRouteSamples=Math.max(0,offRouteSamples-1);
    if(offRouteSamples<3||Date.now()-lastRerouteAt<22000)return;
    if(!navigator.onLine){const now=Date.now();if(now-lastOfflineDeviationAlertAt>30000){lastOfflineDeviationAlertAt=now;showRoadAlert('danger','FORA DA ROTA','Sem internet para recalcular agora. O GPS continua ativo.','',4200);}offRouteSamples=0;return;}
    rerouteFromCurrent(lat,lon);
  }

  function findUpcomingRadar(lat,lon){
    if(!currentRadars.length)return null;
    const rp=currentRouteProgress(lat,lon);
    const withDistance=currentRadars.filter(pointVisibleOnMap).map(r=>{
      const routeM=Number(r.route_m);
      const along=Number.isFinite(routeM)?routeM-rp.progress:NaN;
      const direct=distM(lat,lon,+r.latitude,+r.longitude);
      return {...r,_along:along,_direct:direct};
    });
    const ahead=withDistance.filter(r=>{
      if(Number.isFinite(r._along)){
        if(r._along < -12) return false;
        if(radarAnnouncedNow.has(radarKey(r))){
          const passedWindow=pointKind(r)==='bump'?45:10;
          if(r._along < passedWindow) return false;
        }
        return true;
      }
      return r._direct>=0;
    }).sort((a,b)=>{
        const da=Number.isFinite(a._along)?Math.max(0,a._along):a._direct;
        const db=Number.isFinite(b._along)?Math.max(0,b._along):b._direct;
        return da-db;
      });
    return ahead[0]||null;
  }
  function bumpSequenceInfo(target){
    if(pointKind(target)!=='bump')return null;
    const bumps=currentRadars.filter(r=>pointKind(r)==='bump'&&Number.isFinite(Number(r.route_m))).slice().sort((a,b)=>Number(a.route_m)-Number(b.route_m));
    const targetKey=radarKey(target);let center=bumps.findIndex(r=>radarKey(r)===targetKey);
    if(center<0)return null;
    let left=center,right=center;
    // Pontos a até 110 m na mesma rota são tratados como uma sequência contínua.
    // Isso cobre ruas com muitos quebra-molas sem juntar bairros/trechos distantes.
    const maxGapM=110;
    while(left>0&&Number(bumps[left].route_m)-Number(bumps[left-1].route_m)<=maxGapM)left--;
    while(right+1<bumps.length&&Number(bumps[right+1].route_m)-Number(bumps[right].route_m)<=maxGapM)right++;
    const items=bumps.slice(left,right+1);if(items.length<2)return null;
    const index=items.findIndex(r=>radarKey(r)===targetKey);
    const span=Math.max(0,Number(items[items.length-1].route_m)-Number(items[0].route_m));
    return {items,index:index<0?0:index,span,key:`bump-seq:${radarKey(items[0])}:${radarKey(items[items.length-1])}`};
  }
  function handleBumpSequenceWeb(next,distance,accuracy,kmh){
    if(!alertKindEnabled('bump'))return true;
    const seq=bumpSequenceInfo(next);if(!seq)return false;
    const total=seq.items.length,index=seq.index,remaining=Math.max(1,total-index),key=radarKey(next);

    // A sequência é anunciada UMA vez. Não repete 300/200/100/50 para cada lombada.
    const entryKey=`${seq.key}@entry`;
    if(distance<=350&&!bumpSequenceMilestonesWarned.has(entryKey)){
      bumpSequenceMilestonesWarned.add(entryKey);
      const extent=seq.span>=100?` nos próximos ${Math.round(seq.span/50)*50} metros`:'';
      showRoadAlert('bump','SEQUÊNCIA DE QUEBRA-MOLAS',`Sequência de ${total} quebra-molas${extent} · primeiro em ${fmtDistance(distance)}`,
        `Atenção. Sequência de ${total} quebra-molas${extent}.`,5600);
    }

    // Em sequência densa não usamos o antigo aviso fixo de “50 m”.
    // O card já mostra a distância real; por voz avisamos somente a chegada de cada ponto.
    const gpsAcc=Number.isFinite(Number(accuracy))?Math.max(0,Number(accuracy)):20;
    const speedMs=Math.max(0,Number(kmh)||0)/3.6;
    const nowThreshold=Math.max(14,Math.min(30,10+gpsAcc*0.45+speedMs*0.35));
    if(alertPrefs.front!==false&&distance<=nowThreshold&&!radarAnnouncedNow.has(key)){
      radarAnnouncedNow.add(key);
      bumpNearWarned.add(key);
      const last=index===total-1;
      const nextText=last?'Fim da sequência após este ponto':`${remaining-1} ainda na sequência`;
      showRoadAlert('bump',last?'ÚLTIMO QUEBRA-MOLA':`QUEBRA-MOLA ${index+1} DE ${total}`,
        `AGORA · ${nextText}`,
        last?'Atenção. Último quebra-mola da sequência agora.':`Atenção. Quebra-mola agora.`,4300);
    }
    return true;
  }
  function updateNearestRadar(lat,lon,kmh,accuracy){
    const next=findUpcomingRadar(lat,lon);
    const speedCurrent=document.querySelector('.speed-current');
    if(!next){
      document.getElementById('nextRadar').textContent='--';
      document.getElementById('mapNextRadar').textContent='--';
      document.getElementById('mapRadarInfo').textContent='Nenhum ponto à frente';
      const progress=currentRouteProgress(lat,lon).progress,roadLimit=roadLimitAtProgress(progress),autoLimit=Number(roadLimit?.velocidade)||0;
      document.getElementById('speedLimit').textContent=autoLimit?String(autoLimit):'--';
      document.getElementById('mapLimit').textContent=autoLimit?String(autoLimit):'--';
      const over=autoLimit&&accuracy<=60&&kmh>autoLimit+3;speedCurrent?.classList.toggle('danger',!!over);document.querySelector('.hud-speed')?.classList.toggle('danger',!!over);return;
    }
    const distance=Number.isFinite(next._along)?Math.max(0,next._along):next._direct;
    const limit=Number(next.velocidade)||0,kind=pointKind(next),label=pointLabel(next);
    const display=distance<=25?'AGORA':fmtDistance(distance),hasSpeed=isSpeedPoint(next);
    document.getElementById('nextRadar').textContent=display;
    document.getElementById('mapNextRadar').textContent=display;
    document.getElementById('mapRadarInfo').textContent=hasSpeed&&limit?`${label} · ${limit} km/h`:label;
    const alertRadius=300;
    const routeProgress=currentRouteProgress(lat,lon).progress,roadLimit=roadLimitAtProgress(routeProgress),roadLimitValue=Number(roadLimit?.velocidade)||0;
    const enforcementLimit=hasSpeed&&limit>0&&distance<=800?limit:0;
    const activeLimit=enforcementLimit||roadLimitValue;
    const limitActive=activeLimit>0;
    document.getElementById('speedLimit').textContent=limitActive?String(activeLimit):'--';
    document.getElementById('mapLimit').textContent=limitActive?String(activeLimit):'--';
    const over=limitActive&&accuracy<=60&&kmh>activeLimit+3;
    speedCurrent?.classList.toggle('danger',!!over);document.querySelector('.hud-speed')?.classList.toggle('danger',!!over);

    if(nativeTripActive)return;
    if(!alertKindEnabled(kind))return;
    if(kind==='bump'&&handleBumpSequenceWeb(next,distance,accuracy,kmh))return;
    const key=radarKey(next),milestone=distance<=50?50:distance<=100?100:distance<=200?200:distance<=300?300:0;
    if(milestone&&alertMilestoneEnabled(milestone)){
      const milestoneKey=`${key}@${milestone}`;
      if(!hazardMilestonesWarned.has(milestoneKey)){
        [300,200,100,50].filter(m=>m>=milestone).forEach(m=>hazardMilestonesWarned.add(`${key}@${m}`));
        const suffix=hasSpeed&&limit?` · limite ${limit} km/h`:'';
        const title=kind==='radar'?'RADAR À FRENTE':kind==='portable'?'FISCALIZAÇÃO PORTÁTIL':kind==='video'?'ÁREA MONITORADA':kind==='signal'?(String(next.tipo||'').toUpperCase().includes('FISCALIZ')?'FISCALIZAÇÃO SEMAFÓRICA':'SEMÁFORO À FRENTE'):'QUEBRA-MOLA À FRENTE';
        const labelText=kind==='bump'?'Quebra-mola':label;
        const voice=`Atenção. ${labelText} em ${milestone} metros.${hasSpeed&&limit?` Limite de ${limit} quilômetros por hora.`:''}`;
        showRoadAlert(kind,title,`${labelText} · ${milestone} m${suffix}`,voice,milestone<=50?4200:5000);
      }
    }
    if(alertPrefs.front!==false&&distance<=25&&!radarAnnouncedNow.has(key)){
      radarAnnouncedNow.add(key);
      const front=kind==='radar'?'Radar':kind==='portable'?'Fiscalização portátil':kind==='video'?'Videomonitoramento':kind==='signal'?(String(next.tipo||'').toUpperCase().includes('FISCALIZ')?'Fiscalização semafórica':'Semáforo'):'Quebra-mola';
      const title=`${front.toUpperCase()} NA SUA FRENTE`,text=`${front} · agora${hasSpeed&&limit?` · limite ${limit} km/h`:''}`,voice=`Atenção. ${front} na sua frente.${hasSpeed&&limit?` Limite de ${limit} quilômetros por hora.`:''}`;
      showRoadAlert(kind==='radar'||kind==='portable'?'danger':kind,title,text,voice,5200);
    }
    if(over&&alertPrefs.speeding!==false){const now=Date.now();if(now-lastSpeedVoiceAt>22000){lastSpeedVoiceAt=now;showRoadAlert('danger','REDUZA A VELOCIDADE',`Você está a ${kmh} km/h · limite conhecido ${activeLimit} km/h`,`Atenção. Reduza a velocidade. Limite de ${activeLimit} quilômetros por hora.`,6000);}}
  }
  function radarIcon(r){
    const limit=Number(r.velocidade)||0,kind=pointKind(r);
    const symbol=kind==='video'?'CAM':kind==='signal'?'●':kind==='bump'?'⌁':kind==='portable'?'⚡':'◉';
    return L.divIcon({className:`radar-marker point-${kind}`,html:`<div class="radar-pin"><span>${symbol}</span>${isSpeedPoint(r)&&limit?`<b>${limit}</b>`:''}</div>`,iconSize:[44,44],iconAnchor:[22,22],popupAnchor:[0,-21]});
  }
  function mergeRadarLists(base,extra){
    const out=(base||[]).slice();
    for(const r of (extra||[])){
      const lat=Number(r.latitude),lon=Number(r.longitude);
      const duplicate=out.some(e=>{
        if(r.external_id&&e.external_id&&String(r.external_id)===String(e.external_id))return true;
        const elat=Number(e.latitude),elon=Number(e.longitude),rk=pointKind(r),ek=pointKind(e);
        const sameKind=rk===ek||((rk==='radar'||rk==='portable')&&(ek==='radar'||ek==='portable'));
        const threshold=rk==='bump'?7:rk==='signal'?12:rk==='video'?20:32;
        return sameKind&&Number.isFinite(lat)&&Number.isFinite(lon)&&Number.isFinite(elat)&&Number.isFinite(elon)&&distM(lat,lon,elat,elon)<=threshold;
      });
      if(!duplicate)out.push(r);
    }
    return out.sort((a,b)=>(Number(a.route_m)||0)-(Number(b.route_m)||0));
  }
  function renderRadarMarkers(){
    if(!radarLayer)return;
    radarLayer.clearLayers();
    currentRadars.filter(pointVisibleOnMap).forEach(r=>{
      L.marker([+r.latitude,+r.longitude],{icon:radarIcon(r),zIndexOffset:900})
        .bindPopup(`<strong>${esc(pointLabel(r))}${isSpeedPoint(r)&&r.velocidade?` · ${esc(r.velocidade)} km/h`:''}</strong><br>${esc(r.rodovia||r.cidade||'Na rota')}${r.km!==null&&r.km!==undefined&&r.km!==''?` · km ${esc(r.km)}`:''}${r.fonte?`<br><small>Fonte: ${esc(r.fonte)}</small>`:''}${r.position_accuracy==='TRECHO_APROXIMADO'?'<br><small>Posição aproximada no trecho</small>':''}`)
        .addTo(radarLayer);
    });
    refreshFiscalCounts();
    const km=((Number(currentRoute?.distance)||0)/1000).toFixed(1),min=Math.max(1,Math.round((Number(currentRoute?.duration)||0)/60));
    const txt=`${km} km · ~${min} min · ${currentRadars.length} ponto${currentRadars.length===1?'':'s'} de fiscalização`;
    const trip=document.getElementById('tripSummary'),route=document.getElementById('mapRouteText');
    if(trip)trip.textContent=txt;if(route)route.textContent=txt;
  }
  function setTripUi(active){document.body.classList.toggle('trip-active',!!active);const card=document.querySelector('.trip-card');card?.classList.toggle('is-active',!!active);}
  function drawRoute(data){
    currentRoute=data.route;currentRadars=[];currentSpeedLimits=[];
    if(Number.isFinite(Number(data?.destination?.lat))&&Number.isFinite(Number(data?.destination?.lon)))tripDestinationCoords={lat:Number(data.destination.lat),lon:Number(data.destination.lon)};
    hazardMilestonesWarned.clear();radarAnnouncedNow.clear();bumpSequenceMilestonesWarned.clear();bumpNearWarned.clear();maneuverPrepareWarned.clear();maneuverNowWarned.clear();lastSpeedVoiceAt=0;offRouteSamples=0;tripActive=true;followMap=true;setTripUi(true);
    if(routeLayer)map.removeLayer(routeLayer);if(routeCasing)map.removeLayer(routeCasing);radarLayer.clearLayers();
    const coords=currentRoute?.geometry?.coordinates||[];buildRouteProgress(coords);currentGuidanceSteps=[];currentManeuvers=[];
    if(routeLatLngs.length){
      routeCasing=L.polyline(routeLatLngs,{color:'#0a1024',weight:11,opacity:.96,lineCap:'round',lineJoin:'round'}).addTo(map);
      routeLayer=L.polyline(routeLatLngs,{color:'#7657ff',weight:6,opacity:1,lineCap:'round',lineJoin:'round'}).addTo(map);
      map.fitBounds(routeLayer.getBounds(),{padding:[32,32]});
    }
    renderRadarMarkers();
    document.getElementById('radarCoverage').innerHTML='<strong><span class="ok">ROTA PRONTA</span></strong><br>Carregando pontos de fiscalização…';
    if(lastPos)setTimeout(()=>{map.setView([lastPos.coords.latitude,lastPos.coords.longitude],16,{animate:true});document.getElementById('recenterMap').textContent='◎ Seguindo';},180);
    setTimeout(()=>syncNativeTrip(true),220);
    const stop=document.getElementById('stopTrip');if(stop)stop.hidden=false;
  }
  let radarLoadToken=0;
  async function fetchRadarMode(payload,mode){
    const controller=new AbortController();
    const timeout=setTimeout(()=>controller.abort(),mode==='local'?12000:mode==='antt'?18000:mode==='regional'?32000:38000);
    try{
      const r=await fetch('api/route_radars.php',{
        method:'POST',credentials:'same-origin',cache:'no-store',signal:controller.signal,
        headers:{'Content-Type':'application/json','X-CSRF-Token':String(window.MR_BOOTSTRAP?.csrf||'')},body:JSON.stringify({...payload,mode})
      });
      const text=await r.text();let j;
      try{j=JSON.parse(text)}catch(_){throw new Error(`${mode}: resposta inválida do servidor`)}
      if(!r.ok||!j.ok)throw new Error(j.error||`${mode}: consulta falhou`);
      return j;
    }finally{clearTimeout(timeout)}
  }
  function radarSourceBucket(r){
    const f=String(r?.fonte||'').toUpperCase();
    if(f.includes('ANTT')||f.includes('DNIT')||f.includes('DER_')||f.includes('OFICIAL'))return'official';
    if(f.includes('OPENSTREETMAP'))return'osm';
    if(f.includes('COMUN')||f.includes('MAPARADAR')||f.includes('IMPORT_USUARIO'))return'community';
    return'local';
  }
  function radarCoverageHtml(state){
    const total=currentRadars.length;
    const counts={official:0,osm:0,community:0,local:0};
    currentRadars.forEach(r=>counts[radarSourceBucket(r)]++);
    const done=['local','antt','regional','osm'].every(k=>state[k].done);
    const title=total?`FISCALIZAÇÃO NA ROTA · ${total}`:(done?'NENHUM PONTO CONFIRMADO':'BUSCANDO FISCALIZAÇÃO…');
    const klass=total?'ok':done?'warn':'ok';
    const parts=[`Oficiais ${counts.official}`,`Banco ${counts.local}`,`OSM ${counts.osm}`,`Limites ${currentSpeedLimits.length}`];
    if(counts.community)parts.push(`Base pessoal ${counts.community}`);
    const errors=[];for(const key of ['local','antt','regional','osm'])if(state[key].error)errors.push(`${key.toUpperCase()}: ${state[key].error}`);
    const pending=[];for(const key of ['local','antt','regional','osm'])if(!state[key].done)pending.push(key==='antt'?'ANTT':key==='regional'?'DER/DNIT':key.toUpperCase());
    const diag={...(state.antt.coverage?.diagnostics||{}),...(state.regional.coverage?.diagnostics||{})};
    const sourceDiag=[];
    if(diag.antt)sourceDiag.push(`ANTT ${diag.antt.candidates??0}`);
    if(diag.der_es)sourceDiag.push(`DER-ES ${diag.der_es.positioned??0}/${diag.der_es.rows??0}`);
    if(diag.dnit)sourceDiag.push(`DNIT ${diag.dnit.positioned??0}/${diag.dnit.rows??0}`);
    if(diag.der_mg)sourceDiag.push(`DER-MG ${diag.der_mg.positioned??0}/${diag.der_mg.rows??0}`);
    if(diag.der_rj)sourceDiag.push(`DER-RJ ${diag.der_rj.positioned??0}/${diag.der_rj.rows??0}${diag.der_rj.approximate?` · ${diag.der_rj.approximate} aprox.`:''}`);
    const shortTitle=total?`${total} ponto${total===1?'':'s'} de fiscalização na rota`:(done?'Nenhum ponto confirmado':'Buscando fiscalização…');
    return `<strong><span class="${klass}">${shortTitle}</span></strong>${pending.length?`<small> · carregando ${pending.join(' + ')}</small>`:''}`;
  }
  async function cachedCityRadarItems(){
    if(!('caches' in window))return [];const url=selectedCityPackUrl('radars');if(!url)return [];
    try{const c=await caches.open('musicroad-city-offline-v1'),r=await c.match(url);if(!r)return [];const j=await r.json();return Array.isArray(j.radars)?j.radars:[];}catch(_){return []}
  }
  function downloadedRadarsOnCurrentRoute(items,maxDistance=220){const out=[];for(const r of(items||[])){const lat=Number(r.latitude),lon=Number(r.longitude);if(!Number.isFinite(lat)||!Number.isFinite(lon)||routeLatLngs.length<2)continue;const p=projectOnCurrentRoute(lat,lon);if(p.offRoute<=maxDistance)out.push({...r,distance_to_route_m:Math.round(p.offRoute),route_m:Math.round(p.progress),fonte:r.fonte||'Pacote offline'});}return out.sort((a,b)=>Number(a.route_m)-Number(b.route_m));}

  async function loadRouteRadars(coords){
    const token=++radarLoadToken;
    const payload={coords:(coords||[]).map(c=>[Number(c[0]),Number(c[1])]).filter(c=>Number.isFinite(c[0])&&Number.isFinite(c[1]))};
    const state={local:{done:false,error:'',coverage:null},antt:{done:false,error:'',coverage:null},regional:{done:false,error:'',coverage:null},osm:{done:false,error:'',coverage:null}};
    const coverage=document.getElementById('radarCoverage');
    const apply=(mode,j)=>{
      if(token!==radarLoadToken)return;
      state[mode].done=true;state[mode].coverage=j.coverage||null;
      currentRadars=mergeRadarLists(currentRadars,j.radars||[]);
      currentSpeedLimits=mergeSpeedLimits(currentSpeedLimits,j.speed_limits||[]);
      renderRadarMarkers();syncNativeTrip(false);
      if(lastPos)updateNearestRadar(lastPos.coords.latitude,lastPos.coords.longitude,Math.round((lastPos.coords.speed||0)*3.6),lastPos.coords.accuracy||999);
      if(coverage)coverage.innerHTML=radarCoverageHtml(state);
      saveOfflineTrip({radars:currentRadars,speed_limits:currentSpeedLimits});
    };
    const fail=(mode,e)=>{
      if(token!==radarLoadToken)return;
      state[mode].done=true;state[mode].error=e?.name==='AbortError'?'tempo excedido':String(e?.message||'falhou').slice(0,100);
      if(coverage)coverage.innerHTML=radarCoverageHtml(state);
    };

    currentRadars=[];currentSpeedLimits=[];
    const downloaded=downloadedRadarsOnCurrentRoute(await cachedStateRadarItems());if(downloaded.length)currentRadars=mergeRadarLists(currentRadars,downloaded);
    renderRadarMarkers();syncNativeTrip(false);
    if(coverage)coverage.innerHTML=downloaded.length?`<strong><span class="ok">PACOTE OFFLINE · ${downloaded.length}</span></strong><br>Complementando com fontes online…`:radarCoverageHtml(state);

    // Quatro pipelines em paralelo: banco local aparece quase instantaneamente,
    // ANTT chega sem esperar DER/DNIT e o OSM é um complemento independente.
    const localP=fetchRadarMode(payload,'local').then(j=>apply('local',j)).catch(e=>fail('local',e));
    const anttP=fetchRadarMode(payload,'antt').then(j=>apply('antt',j)).catch(e=>fail('antt',e));
    const regionalP=fetchRadarMode(payload,'regional').then(j=>apply('regional',j)).catch(e=>fail('regional',e));
    const osmP=fetchRadarMode(payload,'osm').then(j=>apply('osm',j)).catch(e=>fail('osm',e));
    await Promise.allSettled([localP,anttP,regionalP,osmP]);
    if(token!==radarLoadToken)return;
    if(coverage)coverage.innerHTML=radarCoverageHtml(state);
    if(currentRadars.length){
      showRoadAlert('info','FISCALIZAÇÃO PRONTA',`${currentRadars.length} ponto${currentRadars.length===1?'':'s'} de fiscalização carregado${currentRadars.length===1?'':'s'} nesta rota.`,'',3200);
    }
  }

  const DESTINATION_PREF_KEY='mr:destination:city:v2';
  let destinationMode='city', destinationSelection={uf:'',stateId:'',cityId:'',city:'',lat:'',lon:''};
  let destinationMunicipalities=[], citySearchTimer=null, addressSearchTimer=null, freeDestinationResolved=null, citySearchSeq=0, addressSearchSeq=0;
  function currentDestination(){
    if(destinationMode==='city'){
      const city=String(destinationSelection.city||'').trim(),uf=String(destinationSelection.uf||'').trim();
      return city&&uf?`${city}, ${uf}`:'';
    }
    return document.getElementById('destination')?.value.trim()||'';
  }
  function destinationSummary(){
    const el=document.getElementById('tripSummary');if(!el||tripActive)return;
    if(destinationMode==='city'){
      const dest=currentDestination();
      el.textContent=dest?`Cidade selecionada: ${dest}. Toque em INICIAR VIAGEM.`:'Escolha o estado, digite o município e toque em uma opção da lista.';
      return;
    }
    if(!destinationSelection.cityId){el.textContent='Para buscar endereço exato, selecione primeiro o estado e o município.';return;}
    const input=document.getElementById('destination')?.value.trim()||'';
    const selected=!!(freeDestinationResolved&&input===freeDestinationResolved.input&&Number.isFinite(Number(freeDestinationResolved.lat))&&Number.isFinite(Number(freeDestinationResolved.lon)));
    el.textContent=selected?`Endereço selecionado: ${freeDestinationResolved.label}. Toque em INICIAR VIAGEM.`:(input?'Toque em um endereço da lista para selecionar o destino correto.':`Base local de ${destinationSelection.city}, ${destinationSelection.uf}. Digite rua e número.`);
  }
  function saveDestinationSelection(){try{localStorage.setItem(DESTINATION_PREF_KEY,JSON.stringify(destinationSelection))}catch(_){} }
  function hideSuggestions(id){const e=document.getElementById(id);if(e){e.hidden=true;e.innerHTML='';}}
  function updateAddressInputAvailability(){const inp=document.getElementById('destination');if(!inp)return;const ready=!!destinationSelection.cityId;inp.disabled=!ready;inp.placeholder=ready?`Digite rua e número em ${destinationSelection.city}`:'Selecione estado e município primeiro';if(!ready){inp.value='';freeDestinationResolved=null;}}
  let addressBasePreparing=false;
  async function prepareAddressCityBase(){
    if(destinationMode!=='free'||!destinationSelection.cityId||addressBasePreparing)return;
    addressBasePreparing=true;renderAddressSuggestions([],`Preparando base local de ${destinationSelection.city}… Na primeira vez pode levar alguns segundos.`);
    try{
      const url=`api/address_local.php?action=prepare&city_id=${encodeURIComponent(destinationSelection.cityId)}&uf=${encodeURIComponent(destinationSelection.uf)}&city=${encodeURIComponent(destinationSelection.city)}`;
      const r=await fetch(url,{method:'POST',credentials:'same-origin',cache:'no-store',headers:{'X-CSRF-Token':String(window.MR_BOOTSTRAP?.csrf||'')}});let j=null;try{j=await r.json()}catch(_){};
      if(!r.ok||!j?.ok)throw new Error(j?.error||'Não foi possível preparar a base local.');
      if(j.status==='ready'){renderAddressSuggestions([],`Base local pronta: ${Number(j.street_count||0).toLocaleString('pt-BR')} vias de ${destinationSelection.city}.`);setTimeout(()=>hideSuggestions('destinationSuggestions'),1800);}
    }catch(e){renderAddressSuggestions([],e.message||'Falha ao preparar a base local do município.');}
    finally{addressBasePreparing=false;}
  }
  function setDestinationMode(mode){
    destinationMode=mode==='free'?'free':'city';
    const cityFields=document.getElementById('destinationCityFields'),freeField=document.getElementById('destinationFreeField');
    if(cityFields)cityFields.hidden=false;if(freeField)freeField.hidden=destinationMode!=='free';
    hideSuggestions('destinationCitySuggestions');hideSuggestions('destinationSuggestions');
    ['destinationCityMode','destinationFreeMode'].forEach(id=>{const b=document.getElementById(id),active=(id==='destinationCityMode')===(destinationMode==='city');if(b){b.classList.toggle('is-active',active);b.setAttribute('aria-pressed',String(active));}});
    updateAddressInputAvailability();destinationSummary();if(destinationMode==='free')prepareAddressCityBase();
  }
  async function getStates(){
    const r=await fetch('api/locations.php?action=states',{cache:'no-store'}),j=await r.json();if(!r.ok||!j.ok)throw new Error(j.error||'Estados indisponíveis');return j.states||[];
  }
  function fillStateSelect(sel,states){if(!sel)return;sel.innerHTML='<option value="">Selecione o estado</option>'+states.map(x=>`<option value="${esc(x.id)}" data-uf="${esc(x.sigla)}">${esc(x.nome)} (${esc(x.sigla)})</option>`).join('');}
  async function loadDestinationStates(){
    const sel=document.getElementById('destinationState');if(!sel)return;
    try{
      const states=await getStates();fillStateSelect(sel,states);fillStateSelect(document.getElementById('offlineState'),states);
      let saved=null;try{saved=JSON.parse(localStorage.getItem(DESTINATION_PREF_KEY)||'null')}catch(_){}
      if(saved?.stateId){destinationSelection={...destinationSelection,...saved};sel.value=String(saved.stateId);await loadDestinationMunicipalities(String(saved.stateId),String(saved.uf||''),String(saved.cityId||''));}
    }catch(e){sel.innerHTML='<option value="">Falha ao carregar estados</option>';document.getElementById('tripSummary').textContent='Não foi possível carregar a lista de estados.';}
  }
  const CITY_CACHE_PREFIX='mr:municipalities:v3:';
  function normalizeMunicipalities(rows,uf){
    return (Array.isArray(rows)?rows:[]).map(x=>({id:String(x.id||x.codigo_ibge||''),nome:String(x.nome||x.name||'').trim(),uf:String(x.uf||uf||'').toUpperCase(),lat:Number.isFinite(Number(x.latitude??x.lat))?Number(x.latitude??x.lat):null,lon:Number.isFinite(Number(x.longitude??x.lon))?Number(x.longitude??x.lon):null})).filter(x=>x.nome);
  }
  function municipalityCacheGet(uf){try{const j=JSON.parse(localStorage.getItem(CITY_CACHE_PREFIX+uf)||'null');return j&&Array.isArray(j.items)?j:null}catch(_){return null}}
  function municipalityCachePut(uf,items,source){try{localStorage.setItem(CITY_CACHE_PREFIX+uf,JSON.stringify({ts:Date.now(),source,items}))}catch(_){} }
  function fillDestinationCities(items,restoreCityId='',source=''){
    destinationMunicipalities=items||[];
    const hidden=document.getElementById('destinationCity'),search=document.getElementById('destinationCitySearch');
    if(hidden){hidden.innerHTML='<option value="">Selecione o município</option>'+destinationMunicipalities.map(x=>`<option value="${esc(x.id)}" data-lat="${esc(x.lat??'')}" data-lon="${esc(x.lon??'')}">${esc(x.nome)}</option>`).join('');hidden.disabled=!destinationMunicipalities.length;hidden.dataset.source=source||'';}
    if(search){search.disabled=false;search.placeholder=destinationMunicipalities.length?'Comece a digitar a cidade':'Digite a cidade para pesquisar';}
    if(restoreCityId){const x=destinationMunicipalities.find(x=>String(x.id)===String(restoreCityId));if(x){selectCitySuggestion(x);}}
  }
  async function municipalitiesFromServer(stateId,uf){
    const r=await fetch(`api/locations.php?action=municipalities&uf=${encodeURIComponent(uf||stateId)}`,{cache:'no-store'});let j=null;try{j=await r.json()}catch(_){}
    if(!r.ok||!j?.ok)throw new Error(j?.error||'Servidor não conseguiu consultar os municípios.');return {items:normalizeMunicipalities(j.municipalities,uf),source:'servidor'};
  }
  async function municipalitiesFromIbge(stateId,uf){
    const url=`https://servicodados.ibge.gov.br/api/v1/localidades/estados/${encodeURIComponent(stateId)}/municipios?orderBy=nome`;const r=await fetch(url,{cache:'no-store',mode:'cors'});if(!r.ok)throw new Error('IBGE direto indisponível');const rows=await r.json();return {items:normalizeMunicipalities(rows,uf),source:'IBGE direto'};
  }
  async function municipalitiesFromStaticCsv(stateId,uf){
    const url='https://raw.githubusercontent.com/kelvins/municipios-brasileiros/main/csv/municipios.csv';const r=await fetch(url,{cache:'force-cache',mode:'cors'});if(!r.ok)throw new Error('Base alternativa indisponível');
    const text=await r.text(),lines=text.split(/\r?\n/),items=[];for(let i=1;i<lines.length;i++){const line=lines[i];if(!line)continue;const c=line.split(',');if(c.length<6||String(c[5]).trim()!==String(stateId))continue;items.push({id:String(c[0]).trim(),nome:String(c[1]).trim(),uf,lat:Number(c[2]),lon:Number(c[3])});}items.sort((a,b)=>a.nome.localeCompare(b.nome,'pt-BR'));return {items,source:'base alternativa'};
  }
  async function loadDestinationMunicipalities(stateId,uf,restoreCityId=''){
    const search=document.getElementById('destinationCitySearch');if(search){search.disabled=false;search.value=restoreCityId?String(destinationSelection.city||''):'';search.placeholder='Carregando cidades…';}
    destinationSelection.uf=uf||destinationSelection.uf;destinationSelection.stateId=String(stateId||destinationSelection.stateId||'');destinationMunicipalities=[];
    const cached=municipalityCacheGet(uf);if(cached?.items?.length)fillDestinationCities(cached.items,restoreCityId,cached.source||'cache');
    let result=null;for(const loader of [municipalitiesFromServer,municipalitiesFromIbge,municipalitiesFromStaticCsv]){try{result=await loader(stateId,uf);if(result.items.length)break}catch(_){}}
    if(result?.items?.length){municipalityCachePut(uf,result.items,result.source);fillDestinationCities(result.items,restoreCityId,result.source);return;}
    if(cached?.items?.length)return;
    fillDestinationCities([],restoreCityId,'pesquisa online');
    if(search){search.disabled=false;search.placeholder='Digite a cidade — pesquisa online';}
  }
  const STATE_NAME_BY_UF={AC:'Acre',AL:'Alagoas',AP:'Amapá',AM:'Amazonas',BA:'Bahia',CE:'Ceará',DF:'Distrito Federal',ES:'Espírito Santo',GO:'Goiás',MA:'Maranhão',MT:'Mato Grosso',MS:'Mato Grosso do Sul',MG:'Minas Gerais',PA:'Pará',PB:'Paraíba',PR:'Paraná',PE:'Pernambuco',PI:'Piauí',RJ:'Rio de Janeiro',RN:'Rio Grande do Norte',RS:'Rio Grande do Sul',RO:'Rondônia',RR:'Roraima',SC:'Santa Catarina',SP:'São Paulo',SE:'Sergipe',TO:'Tocantins'};
  function ufFromStateName(name){const f=fold(name||'');for(const [uf,n] of Object.entries(STATE_NAME_BY_UF))if(fold(n)===f||f.includes(fold(n)))return uf;return '';}
  function uniquePlaces(rows){const seen=new Set(),out=[];for(const x of (rows||[])){if(!x||!Number.isFinite(Number(x.lat))||!Number.isFinite(Number(x.lon)))continue;const label=String(x.label||x.name||x.city||'').trim();if(!label)continue;const k=fold(label)+'|'+Number(x.lat).toFixed(4)+'|'+Number(x.lon).toFixed(4);if(seen.has(k))continue;seen.add(k);out.push({...x,label});}return out;}
  async function searchPlacesServer(q,type='address',uf=''){
    try{
      const city=type==='address'?String(destinationSelection.city||'').trim():'';
      const cityId=type==='address'?String(destinationSelection.cityId||'').trim():'';
      let url=`api/geocode_search.php?q=${encodeURIComponent(q)}&type=${encodeURIComponent(type)}${uf?`&uf=${encodeURIComponent(uf)}`:''}${city?`&city=${encodeURIComponent(city)}`:''}${cityId?`&city_id=${encodeURIComponent(cityId)}`:''}`;
      const r=await fetch(url,{cache:'no-store'});let j=null;try{j=await r.json()}catch(_){}if(r.ok&&j?.ok&&Array.isArray(j.results))return j.results;
    }catch(_){}return[];
  }
  function placeSearchScore(x,q,uf=''){
    const f=fold(q),name=fold(x.name||''),label=fold(x.label||''),city=fold(x.city||''),wanted=String(uf||'').toUpperCase(),found=String(x.uf||'').toUpperCase(),selectedCity=fold(destinationSelection.city||'');let score=0;
    if(name===f)score+=100;if(name.startsWith(f))score+=70;else if(name.includes(f))score+=45;if(label.startsWith(f))score+=35;else if(label.includes(f))score+=22;if(city.includes(f))score+=8;if(wanted&&found===wanted)score+=35;if(selectedCity&&city===selectedCity)score+=120;else if(selectedCity&&label.includes(selectedCity))score+=75;if(/cnefe/i.test(x.source||''))score+=45;return score;
  }
  async function searchPlaces(q,type='address',uf=''){
    const rows=await searchPlacesServer(q,type,uf);
    if(type==='city')return (rows||[]).slice(0,12);
    return uniquePlaces(rows||[]).sort((a,b)=>placeSearchScore(b,q,uf)-placeSearchScore(a,q,uf)).slice(0,15);
  }
  function renderCitySuggestions(items,message=''){
    const box=document.getElementById('destinationCitySuggestions');if(!box)return;box.innerHTML='';
    if(message){const info=document.createElement('div');info.className='suggestion-status';info.textContent=message;box.appendChild(info);box.hidden=false;if(!items.length)return;}
    if(!items.length){box.hidden=true;return;}items.slice(0,10).forEach(x=>{const b=document.createElement('button');b.type='button';b.className='place-suggestion-item';b.innerHTML=`<span class="suggestion-pin">⌖</span><span><strong>${esc(x.nome)}</strong><small>${esc(x.uf||destinationSelection.uf)}${x.source?' · '+esc(x.source):''}</small></span><b class="suggestion-select">Selecionar</b>`;b.addEventListener('pointerdown',e=>{e.preventDefault();selectCitySuggestion(x)});b.addEventListener('click',()=>selectCitySuggestion(x));box.appendChild(b);});box.hidden=false;
  }
  function selectCitySuggestion(x){
    destinationSelection={uf:String(x.uf||destinationSelection.uf||''),stateId:String(destinationSelection.stateId||''),cityId:String(x.id||''),city:String(x.nome||'').trim(),lat:x.lat??'',lon:x.lon??''};saveDestinationSelection();freeDestinationResolved=null;
    const search=document.getElementById('destinationCitySearch');if(search){search.value=destinationSelection.city;search.blur();}const address=document.getElementById('destination');if(address)address.value='';hideSuggestions('destinationCitySuggestions');updateAddressInputAvailability();destinationSummary();if(destinationMode==='free')prepareAddressCityBase();queueSelectedLightMap();loadOfflineCityRoads(destinationSelection.cityId);
  }
  async function citySearchChanged(value){
    const seq=++citySearchSeq,q=String(value||'').trim();destinationSelection.cityId='';destinationSelection.city='';destinationSelection.lat='';destinationSelection.lon='';freeDestinationResolved=null;saveDestinationSelection();updateAddressInputAvailability();destinationSummary();
    if(q.length<2){renderCitySuggestions([]);return;}
    const f=fold(q);let items=destinationMunicipalities.filter(x=>fold(x.nome).includes(f)).sort((a,b)=>{const aa=fold(a.nome).startsWith(f)?0:1,bb=fold(b.nome).startsWith(f)?0:1;return aa-bb||a.nome.localeCompare(b.nome,'pt-BR')}).slice(0,10);
    if(items.length){renderCitySuggestions(items);return;}
    renderCitySuggestions([],'Buscando municípios…');
    const online=await searchPlaces(q,'city',destinationSelection.uf);if(seq!==citySearchSeq)return;
    items=online.map(x=>({id:x.ibge_id||'',nome:x.city||x.name||String(x.label||'').split(',')[0],uf:x.uf||destinationSelection.uf,lat:x.lat,lon:x.lon,source:x.source||'pesquisa online'})).filter(x=>x.nome&&(!destinationSelection.uf||!x.uf||x.uf===destinationSelection.uf));
    renderCitySuggestions(items,items.length?'':'Nenhum município encontrado. Continue digitando o nome completo.');
  }
  function renderAddressSuggestions(items,message=''){
    const box=document.getElementById('destinationSuggestions');if(!box)return;box.innerHTML='';
    if(message){const info=document.createElement('div');info.className='suggestion-status';info.textContent=message;box.appendChild(info);box.hidden=false;if(!items.length)return;}
    if(!items.length){box.hidden=true;return;}
    items.slice(0,15).forEach(x=>{const b=document.createElement('button');b.type='button';b.className='place-suggestion-item';const title=String(x.name||x.city||x.label).trim();b.innerHTML=`<span class="suggestion-pin">⌖</span><span><strong>${esc(title)}</strong><small>${esc(x.label)}</small></span><b class="suggestion-select">Selecionar</b>`;const choose=()=>{const inp=document.getElementById('destination');if(inp){inp.value=x.label;inp.blur();}freeDestinationResolved={lat:Number(x.lat),lon:Number(x.lon),label:x.label,input:x.label};hideSuggestions('destinationSuggestions');destinationSummary();};b.addEventListener('pointerdown',e=>{e.preventDefault();choose()});b.addEventListener('click',choose);box.appendChild(b);});box.hidden=false;
  }

  const STATE_OFFLINE_CACHE='musicroad-state-offline-v1';
  const nativeOfflineStore=()=>!!window.MusicRoadAndroid?.storeOfflinePack;
  function nativeOfflineKey(kind,uf,id=''){return `state/${String(uf||'').toUpperCase()}/${kind}${id?'/'+id:''}`;}
  function nativeOfflinePut(key,obj){try{return !!window.MusicRoadAndroid?.storeOfflinePack?.(key,JSON.stringify(obj))}catch(_){return false}}
  function nativeOfflineGet(key){try{const raw=window.MusicRoadAndroid?.readOfflinePack?.(key);return raw?JSON.parse(String(raw)):null}catch(_){return null}}
  function nativeOfflineHas(key){try{return !!window.MusicRoadAndroid?.offlinePackExists?.(key)}catch(_){return false}}
  const STATE_MAP_CACHE='musicroad-state-map-device-v2';
  const STATE_MAP_INDEX_KEY='mr:state-map-index-v2';
  let offlineDetailLayer=null,offlineDetailCityId='';
  function selectedOfflineState(){const sel=document.getElementById('offlineState'),o=sel?.selectedOptions?.[0];return {stateId:String(sel?.value||''),uf:String(o?.dataset?.uf||''),name:String(o?.textContent||'').replace(/\s*\([A-Z]{2}\)\s*$/,'')};}
  function statePackUrl(kind){const x=selectedOfflineState();if(!x.stateId||!x.uf)return'';return `api/offline_state.php?kind=${encodeURIComponent(kind)}&state_id=${encodeURIComponent(x.stateId)}&uf=${encodeURIComponent(x.uf)}&state=${encodeURIComponent(x.name)}`;}
  function cityMapUrl(city,x){return `api/offline_city_roads.php?city_id=${encodeURIComponent(city.id)}&uf=${encodeURIComponent(x.uf)}&city=${encodeURIComponent(city.name||'')}`;}
  function stateMapIndex(){try{const n=nativeOfflineGet('state/index');if(n)return n;return JSON.parse(localStorage.getItem(STATE_MAP_INDEX_KEY)||'{}')||{}}catch(_){return{}}}
  function saveStateMapIndex(x){try{localStorage.setItem(STATE_MAP_INDEX_KEY,JSON.stringify(x))}catch(_){};nativeOfflinePut('state/index',x)}
  async function requestPersistentMapStorage(){try{if(navigator.storage?.persist)await navigator.storage.persist()}catch(_){} }
  function geoJsonBounds(...nodes){const box=[Infinity,Infinity,-Infinity,-Infinity];const visit=n=>{if(Array.isArray(n)){if(n.length>=2&&Number.isFinite(Number(n[0]))&&Number.isFinite(Number(n[1]))){const lon=Number(n[0]),lat=Number(n[1]);box[0]=Math.min(box[0],lon);box[1]=Math.min(box[1],lat);box[2]=Math.max(box[2],lon);box[3]=Math.max(box[3],lat);return}n.forEach(visit);return}if(!n||typeof n!=='object')return;if(n.coordinates)visit(n.coordinates);if(n.geometry)visit(n.geometry);if(n.features)visit(n.features)};nodes.forEach(visit);return box.every(Number.isFinite)&&box[0]<box[2]&&box[1]<box[3]?box.map(x=>Number(x.toFixed(5))):null;}
  function decodeRoadPolyline(str){const out=[];let i=0,lat=0,lon=0;while(i<String(str||'').length){let shift=0,result=0,b;do{b=str.charCodeAt(i++)-63;result|=(b&31)<<shift;shift+=5}while(b>=32&&i<str.length);const dlat=(result&1)?~(result>>1):(result>>1);shift=0;result=0;do{b=str.charCodeAt(i++)-63;result|=(b&31)<<shift;shift+=5}while(b>=32&&i<str.length);const dlon=(result&1)?~(result>>1):(result>>1);lat+=dlat;lon+=dlon;out.push([lat/1e5,lon/1e5])}return out}
  function offlineRoadStyle(cls){cls=Number(cls)||8;if(cls<=2)return{weight:4.2,opacity:.92};if(cls<=4)return{weight:3.2,opacity:.84};if(cls<=6)return{weight:2.2,opacity:.76};return{weight:1.35,opacity:.62};}
  async function renderOfflineCityPack(pack){if(!map||!window.L||!pack?.roads)return false;try{if(offlineDetailLayer&&map.hasLayer(offlineDetailLayer))map.removeLayer(offlineDetailLayer)}catch(_){};try{offlineDetailLayer=L.layerGroup();for(const row of pack.roads){const pts=decodeRoadPolyline(row?.[3]||'');if(pts.length<2)continue;L.polyline(pts,{...offlineRoadStyle(row?.[0]),interactive:false}).addTo(offlineDetailLayer)}offlineDetailLayer.addTo(map);offlineDetailCityId=String(pack?.city?.id||'');mapSourceBadge(navigator.onLine?'DISPOSITIVO PRONTO':'MAPA NO DISPOSITIVO','local');return true}catch(_){offlineDetailLayer=null;offlineDetailCityId='';return false}}
  async function loadOfflineStateBase(uf){uf=String(uf||'').toUpperCase();if(!/^[A-Z]{2}$/.test(uf))return false;try{const n=nativeOfflineGet(nativeOfflineKey('base',uf));if(n?.ok&&n?.map)return renderOfflineRegionPack(n);if(!('caches'in window))return false;const c=await caches.open(STATE_OFFLINE_CACHE),keys=await c.keys();const req=keys.find(k=>{try{const u=new URL(k.url);return u.pathname.endsWith('/api/offline_state.php')&&u.searchParams.get('kind')==='map'&&u.searchParams.get('uf')===uf}catch(_){return false}});if(!req)return false;const r=await c.match(req),j=r?await r.json():null;return j?.ok&&j?.map?renderOfflineRegionPack(j):false}catch(_){return false}}
  async function loadOfflineCityRoads(code){code=String(code||'');if(!/^\d{7}$/.test(code))return loadOfflineStateBase(destinationSelection.uf);if(offlineDetailCityId===code&&offlineDetailLayer)return true;let fallbackUf=String(destinationSelection.uf||'').toUpperCase();try{const idx=stateMapIndex();for(const [uf,st] of Object.entries(idx)){if(st?.cities?.[code]){fallbackUf=uf;const n=nativeOfflineGet(nativeOfflineKey('city',uf,code));if(n?.ok)return renderOfflineCityPack(n)}}if('caches'in window){const c=await caches.open(STATE_MAP_CACHE),keys=await c.keys();const req=keys.find(k=>k.url.includes('offline_city_roads.php')&&k.url.includes(`city_id=${encodeURIComponent(code)}`));if(req){const r=await c.match(req),j=r?await r.json():null;if(j?.ok)return renderOfflineCityPack(j)}}return loadOfflineStateBase(fallbackUf)}catch(_){return loadOfflineStateBase(fallbackUf)}}
  async function activateOfflineMapForPosition(lat,lon){if(navigator.onLine)return false;const idx=stateMapIndex();for(const state of Object.values(idx)){for(const [code,m] of Object.entries(state?.cities||{})){const b=m?.bbox;if(!Array.isArray(b)||b.length<4)continue;if(lon>=b[0]&&lon<=b[2]&&lat>=b[1]&&lat<=b[3])return loadOfflineCityRoads(code)}}for(const [uf,state] of Object.entries(idx)){const b=state?.baseBbox;if(Array.isArray(b)&&b.length>=4&&lon>=b[0]&&lon<=b[2]&&lat>=b[1]&&lat<=b[3])return loadOfflineStateBase(uf)}return false}
  async function updateStateOfflineUi(){
    const x=selectedOfflineState(),ready=!!x.stateId;['downloadStateRadars','downloadStateMap'].forEach(id=>{const b=document.getElementById(id);if(b)b.disabled=!ready});const st=document.getElementById('stateOfflineProgress');if(!st)return;if(!ready){st.textContent='Selecione um estado para começar.';return;}
    let r=nativeOfflineHas(nativeOfflineKey('radars',x.uf)),m=nativeOfflineHas(nativeOfflineKey('base',x.uf));try{if(!nativeOfflineStore()&&'caches'in window){const c=await caches.open(STATE_OFFLINE_CACHE);r=!!(await c.match(statePackUrl('radars')));m=!!(await c.match(statePackUrl('map')))}}catch(_){}
    const idx=stateMapIndex()[x.uf]||{},cityCount=Object.keys(idx.cities||{}).length;
    st.textContent=`${x.name}${r?' · radares ✓':''}${m||idx.baseReady?' · mapa estadual ✓':''}${cityCount?` · ${cityCount} cidade${cityCount===1?'':'s'} detalhada${cityCount===1?'':'s'}`:''} · armazenamento: dispositivo`;
  }
  async function downloadStatePack(kind){
    if(kind==='map')return downloadFullStateMap();
    const url=statePackUrl(kind),x=selectedOfflineState(),st=document.getElementById('stateOfflineProgress'),btn=document.getElementById('downloadStateRadars');if(!url)return;
    btn.disabled=true;if(st)st.textContent=`Baixando radares de ${x.name}…`;
    try{const r=await fetch(url,{cache:'no-store'});let j=null;try{j=await r.clone().json()}catch(_){}if(!r.ok||!j?.ok)throw new Error(j?.error||'Falha no download.');if(nativeOfflineStore()){if(!nativeOfflinePut(nativeOfflineKey('radars',x.uf),j))throw new Error('Não foi possível gravar o pacote no dispositivo.');}else if('caches'in window){const c=await caches.open(STATE_OFFLINE_CACHE);await c.put(url,r.clone());}if(st)st.textContent=`Radares de ${x.name} salvos ✓ · ${Array.isArray(j.radars)?j.radars.length:0} pontos · no dispositivo`;showRoadAlert('info','DOWNLOAD CONCLUÍDO',`${x.name}: radares disponíveis offline.`,'',3200);await updateStateOfflineUi();
    }catch(e){if(st)st.textContent=e.message||'Falha no download.';}finally{btn.disabled=false;}
  }
  async function downloadStateSkeleton(x){const url=statePackUrl('map');const r=await fetch(url,{cache:'no-store'}),j=await r.clone().json().catch(()=>null);if(!r.ok||!j?.ok)throw new Error(j?.error||'Falha ao baixar a base estadual.');if(nativeOfflineStore()){if(!nativeOfflinePut(nativeOfflineKey('base',x.uf),j))throw new Error('Não foi possível gravar a base no dispositivo.');}else if('caches'in window){const c=await caches.open(STATE_OFFLINE_CACHE);await c.put(url,r.clone())}renderOfflineRegionPack(j);return j}
  async function downloadFullStateMap(){
    const x=selectedOfflineState(),st=document.getElementById('stateOfflineProgress'),btn=document.getElementById('downloadStateMap');if(!x.stateId||!x.uf)return;if(!nativeOfflineStore()&&!('caches'in window)){if(st)st.textContent='Este aparelho não oferece armazenamento offline compatível.';return}
    btn.disabled=true;await requestPersistentMapStorage();let citySaved=false,cityFailed=false;
    try{
      if(st)st.textContent=`Preparando base viária de ${x.name}…`;
      const base=await downloadStateSkeleton(x),idx=stateMapIndex(),stateIdx=idx[x.uf]||{cities:{}};stateIdx.stateId=x.stateId;stateIdx.name=x.name;stateIdx.cities=stateIdx.cities||{};stateIdx.baseBbox=geoJsonBounds(base?.map?.boundary,base?.map?.roads);stateIdx.baseReady=true;stateIdx.complete=true;stateIdx.updatedAt=new Date().toISOString();idx[x.uf]=stateIdx;saveStateMapIndex(idx);
      const cityId=String(destinationSelection.cityId||''),sameState=destinationSelection.uf===x.uf&&/^\d{7}$/.test(cityId);
      if(sameState){const city={id:cityId,name:String(destinationSelection.city||'Município')},url=cityMapUrl({id:cityId,name:String(destinationSelection.city||'')},x),cache=!nativeOfflineStore()&&'caches'in window?await caches.open(STATE_MAP_CACHE):null;let detail=null;if(nativeOfflineHas(nativeOfflineKey('city',x.uf,cityId)))detail=nativeOfflineGet(nativeOfflineKey('city',x.uf,cityId));else if(cache&&await cache.match(url))detail=await (await cache.match(url)).json().catch(()=>null);for(let attempt=0;!detail&&attempt<2;attempt++){try{if(st)st.textContent=`Mapa estadual salvo. Baixando detalhes de ${city.name}${attempt?' (nova tentativa)':''}…`;const r=await fetch(url,{cache:'no-store'}),j=await r.clone().json().catch(()=>null);if(!r.ok||!j?.ok)throw new Error(j?.error||'Falha no detalhe municipal.');if(nativeOfflineStore()){if(!nativeOfflinePut(nativeOfflineKey('city',x.uf,cityId),j))throw new Error('Não foi possível gravar o município.');}else if(cache)await cache.put(url,r.clone());detail=j}catch(_){if(attempt===0)await new Promise(res=>setTimeout(res,900));}}if(detail?.ok){stateIdx.cities[cityId]={name:city.name,bbox:detail.bbox||null,count:Number(detail.road_count||0)};stateIdx.updatedAt=new Date().toISOString();saveStateMapIndex(idx);citySaved=true;await renderOfflineCityPack(detail)}else cityFailed=true;}
      await updateStateOfflineUi();if(st)st.textContent=`${x.name}: mapa estadual salvo no dispositivo ✓${citySaved?` · ${destinationSelection.city} detalhada ✓`:cityFailed?' · detalhe da cidade indisponível agora':''}`;
      showRoadAlert(cityFailed?'warning':'info',cityFailed?'MAPA ESTADUAL PRONTO':'MAPA OFFLINE PRONTO',cityFailed?`${x.name} está salvo. O detalhe da cidade poderá ser baixado depois.`:`${x.name} está disponível no dispositivo.`,'',4200);
    }catch(e){if(st)st.textContent=e.message||'Falha no download do mapa.';}finally{btn.disabled=false;}
  }
  async function cachedStateRadarItems(){try{const all=[];const idx=stateMapIndex();for(const uf of Object.keys(idx)){const n=nativeOfflineGet(nativeOfflineKey('radars',uf));if(Array.isArray(n?.radars))all.push(...n.radars)}if(!nativeOfflineStore()&&'caches'in window){const c=await caches.open(STATE_OFFLINE_CACHE),keys=await c.keys();for(const req of keys){if(!req.url.includes('kind=radars'))continue;const r=await c.match(req);if(!r)continue;const j=await r.json();if(Array.isArray(j.radars))all.push(...j.radars)}}return mergeRadarLists([],all);}catch(_){return[]}}
  function renderOfflineRegionPack(pack){
    if(!map||!window.L||!pack?.map)return false;try{if(offlineRegionLayer)map.removeLayer(offlineRegionLayer);if(offlineDetailLayer)map.removeLayer(offlineDetailLayer)}catch(_){}offlineDetailLayer=null;offlineDetailCityId='';offlineRegionLayer=L.layerGroup().addTo(map);try{if(pack.map.boundary)L.geoJSON(pack.map.boundary,{style:{weight:2,opacity:.65,fillOpacity:.03}}).addTo(offlineRegionLayer)}catch(_){}try{if(pack.map.roads)L.geoJSON(pack.map.roads,{style:{weight:3,opacity:.72}}).addTo(offlineRegionLayer)}catch(_){}mapSourceBadge(navigator.onLine?'DISPOSITIVO PRONTO':'MAPA ESTADUAL NO DISPOSITIVO','local');return true;
  }
  document.getElementById('destinationCityMode')?.addEventListener('click',()=>setDestinationMode('city'));
  document.getElementById('destinationFreeMode')?.addEventListener('click',()=>setDestinationMode('free'));
  document.getElementById('destinationState')?.addEventListener('change',async e=>{const o=e.target.selectedOptions[0],stateId=e.target.value,uf=o?.dataset?.uf||'';destinationSelection={uf,stateId,cityId:'',city:'',lat:'',lon:''};freeDestinationResolved=null;saveDestinationSelection();const search=document.getElementById('destinationCitySearch');if(search)search.value='';updateAddressInputAvailability();destinationSummary();if(stateId)await loadDestinationMunicipalities(stateId,uf);else{if(search){search.disabled=true;search.placeholder='Escolha primeiro o estado';}destinationMunicipalities=[];}});
  document.getElementById('destinationCitySearch')?.addEventListener('input',e=>{clearTimeout(citySearchTimer);citySearchTimer=setTimeout(()=>citySearchChanged(e.target.value),180);});
  document.getElementById('destination')?.addEventListener('input',e=>{freeDestinationResolved=null;const seq=++addressSearchSeq;clearTimeout(addressSearchTimer);const q=e.target.value.trim();if(!destinationSelection.cityId){renderAddressSuggestions([],'Selecione primeiro o estado e o município.');destinationSummary();return;}if(q.length<2){renderAddressSuggestions([]);destinationSummary();return;}renderAddressSuggestions([],'Pesquisando na base local do IBGE…');addressSearchTimer=setTimeout(async()=>{const items=await searchPlaces(q,'address',destinationSelection.uf||'');if(seq!==addressSearchSeq)return;renderAddressSuggestions(items,items.length?'':'Nenhum endereço encontrado nessa cidade. Tente parte do nome da via ou inclua o número.');},260);destinationSummary();});
  async function loadCachedCityMap(){if(destinationSelection.cityId)await loadOfflineCityRoads(destinationSelection.cityId);syncMapBaseMode();}
  document.getElementById('offlineState')?.addEventListener('change',updateStateOfflineUi);
  document.getElementById('downloadStateRadars')?.addEventListener('click',()=>downloadStatePack('radars'));
  document.getElementById('downloadStateMap')?.addEventListener('click',downloadFullStateMap);
  document.getElementById('prepareLightCityMap')?.addEventListener('click',()=>prepareSelectedLightMap(true,false));
  document.getElementById('refreshLightMapStats')?.addEventListener('click',refreshLightMapStats);
  addEventListener('online',()=>{baseTileErrors=0;syncMapBaseMode();queueSelectedLightMap();});
  addEventListener('offline',syncMapBaseMode);


  async function startTrip(){
    const btn=document.getElementById('startTrip'),dest=currentDestination();if(!dest){document.getElementById('tripSummary').textContent=destinationMode==='city'?'Escolha o estado e toque no município desejado.':'Informe o destino para iniciar.';return;}
    if(destinationMode==='free'){
      const typed=document.getElementById('destination')?.value.trim()||'';
      if(!freeDestinationResolved||typed!==freeDestinationResolved.input){
        const results=await searchPlaces(typed,'address',destinationSelection.uf||'');renderAddressSuggestions(results,results.length?'Selecione o endereço correto antes de iniciar.':'Não encontrei esse endereço. Tente incluir bairro/cidade ou escreva apenas parte do nome da rua.');document.getElementById('tripSummary').textContent=results.length?'Selecione uma opção da lista de endereços.':'Não foi possível localizar esse endereço.';return;
      }
    }
    btn.disabled=true;btn.textContent='CALCULANDO ROTA...';startGps();queueSelectedLightMap();
    let origin=document.getElementById('origin').value.trim();
    try{
      if(!origin||origin==='Minha localização'){
        if(!lastPos)await new Promise((resolve,reject)=>navigator.geolocation.getCurrentPosition(p=>{updatePosition(p);resolve()},reject,{enableHighAccuracy:true,timeout:9000,maximumAge:15000}));
        origin=`${lastPos.coords.latitude},${lastPos.coords.longitude}`;
      }
      const started=performance.now();
      const cityExtra=destinationMode==='city'&&destinationSelection.city?`&destination_city=${encodeURIComponent(destinationSelection.city)}&destination_uf=${encodeURIComponent(destinationSelection.uf)}&destination_ibge_id=${encodeURIComponent(destinationSelection.cityId||'')}${destinationSelection.lat&&destinationSelection.lon?`&destination_lat=${encodeURIComponent(destinationSelection.lat)}&destination_lon=${encodeURIComponent(destinationSelection.lon)}`:''}`:'';
      const routeDestination=(destinationMode==='free'&&freeDestinationResolved&&document.getElementById('destination')?.value.trim()===freeDestinationResolved.input)?`${freeDestinationResolved.lat},${freeDestinationResolved.lon}`:(destinationMode==='city'&&destinationSelection.lat&&destinationSelection.lon?`${destinationSelection.lat},${destinationSelection.lon}`:dest);
      const r=await fetch(`api/route.php?origin=${encodeURIComponent(origin)}&destination=${encodeURIComponent(routeDestination)}${cityExtra}`,{cache:'no-store'});
      const j=await r.json();if(!r.ok||!j.ok)throw new Error(j.error||'Rota não encontrada.');
      tripDestinationLabel=dest;drawRoute(j);saveOfflineTrip({origin,destination:dest,data:j,radars:[],active:true});Player.setTripMode(true);showScreen('map');
      const ms=Math.round(performance.now()-started);
      showRoadAlert('info','ROTA PRONTA',`Mapa aberto em ${(ms/1000).toFixed(1)} s. Carregando fiscalização e orientação…`,'Viagem iniciada.',3000);
      loadRouteGuidance(j.origin,j.destination);loadRouteRadars(j.route?.geometry?.coordinates||[]);return;
    }catch(e){
      const cached=offlineTrip();
      if(cached?.data?.route?.geometry?.coordinates?.length&&fold(cached.destination)===fold(dest)){
        tripDestinationLabel=cached.destination||dest;drawRoute(cached.data);currentRadars=Array.isArray(cached.radars)?cached.radars:[];currentSpeedLimits=Array.isArray(cached.speed_limits)?cached.speed_limits:[];currentManeuvers=Array.isArray(cached.maneuvers)?cached.maneuvers:[];const stateOffline=downloadedRadarsOnCurrentRoute(await cachedStateRadarItems());currentRadars=mergeRadarLists(currentRadars,stateOffline);renderRadarMarkers();syncNativeTrip(false);saveOfflineTrip({active:true,radars:currentRadars,speed_limits:currentSpeedLimits,maneuvers:currentManeuvers});Player.setTripMode(true);showScreen('map');
        document.getElementById('radarCoverage').innerHTML=`<strong><span class="warn">MODO OFFLINE</span></strong><br>Última rota salva · ${currentRadars.length} pontos armazenados`;
        showRoadAlert('info','ROTA OFFLINE','Usando a última rota e os pontos de fiscalização salvos neste aparelho.','',4200);return;
      }
      document.getElementById('tripSummary').textContent=navigator.onLine?(e.message||'Não foi possível calcular a rota.'):'Sem internet. Para navegar offline, use o mesmo destino da última rota salva.';
    }finally{btn.disabled=false;btn.textContent='INICIAR VIAGEM';}
  }

  function endTrip(){
    saveOfflineTrip({active:false});
    tripActive=false;setTripUi(false);rerouteInFlight=false;offRouteSamples=0;hazardMilestonesWarned.clear();radarAnnouncedNow.clear();bumpSequenceMilestonesWarned.clear();bumpNearWarned.clear();maneuverPrepareWarned.clear();maneuverNowWarned.clear();
    stopNativeTrip();Player.setTripMode(false);currentRadars=[];currentSpeedLimits=[];currentManeuvers=[];currentGuidanceSteps=[];routeLatLngs=[];routeCumM=[];lastRouteProgressM=0;
    if(routeLayer){try{map.removeLayer(routeLayer)}catch(_){}routeLayer=null;}if(routeCasing){try{map.removeLayer(routeCasing)}catch(_){}routeCasing=null;}try{radarLayer?.clearLayers()}catch(_){}
    currentRoute=null;tripDestinationCoords=null;document.getElementById('nextRadar').textContent='--';document.getElementById('speedLimit').textContent='--';document.getElementById('mapNextRadar').textContent='--';document.getElementById('mapLimit').textContent='--';const keptDest=currentDestination();document.getElementById('tripSummary').textContent=keptDest?'Destino mantido: '+keptDest+'. Toque em INICIAR VIAGEM para viajar novamente.':'Escolha o destino para iniciar uma nova viagem.';
    const stop=document.getElementById('stopTrip');if(stop)stop.hidden=true;showRoadAlert('info','VIAGEM ENCERRADA','Motor de bordo e alertas de rota foram desligados.','',2800);showScreen('board');startGps();
  }
  document.getElementById('stopTrip')?.addEventListener('click',endTrip);
  document.getElementById('startTrip')?.addEventListener('click',startTrip);
  document.getElementById('destination')?.addEventListener('input',()=>destinationSummary());

  // Base pessoal de radares. O arquivo fica no servidor do próprio usuário
  // e os pontos entram imediatamente no mesmo banco consultado pelas rotas.
  const radarImportFile=document.getElementById('radarImportFile');
  radarImportFile?.addEventListener('change',()=>{
    const st=document.getElementById('radarImportStatus');
    const f=radarImportFile.files?.[0];
    if(st&&f){st.className='inline-status';st.textContent=`Pronto para importar: ${f.name} · ${(f.size/1024/1024).toFixed(1)} MB`;}
  });
  document.getElementById('importRadarDb')?.addEventListener('click',async()=>{
    const btn=document.getElementById('importRadarDb'),st=document.getElementById('radarImportStatus'),f=radarImportFile?.files?.[0];
    if(!f){if(st){st.className='inline-status error';st.textContent='Selecione um arquivo CSV, TXT ou KML.';}return;}
    const fd=new FormData();fd.append('file',f);fd.append('source',document.getElementById('radarImportSource')?.value||'MAPARADAR_USUARIO');
    btn.disabled=true;btn.textContent='IMPORTANDO...';if(st){st.className='inline-status';st.textContent='Lendo coordenadas e atualizando o banco...';}
    try{
      const r=await fetch('api/radar_import.php',{method:'POST',credentials:'same-origin',cache:'no-store',headers:{'X-CSRF-Token':window.MR_BOOTSTRAP?.csrf||''},body:fd});
      const text=await r.text();let x;try{x=JSON.parse(text)}catch(_){throw new Error('Resposta inválida do servidor.')}
      if(!r.ok||!x.ok)throw new Error(x.error||'Falha na importação.');
      if(st){st.className='inline-status ok';st.textContent=`Base pronta: ${x.parsed} pontos lidos · ${x.inserted} novos · ${x.updated} atualizados · ${x.active_total} radares ativos no MusicRoad.`;}
      showRoadAlert('info','BASE DE RADARES ATUALIZADA',`${x.inserted+x.updated} pontos processados. As próximas rotas já usam a nova base.`,'',3800);
    }catch(e){if(st){st.className='inline-status error';st.textContent=e.message||'Falha na importação.';}}
    finally{btn.disabled=false;btn.textContent='IMPORTAR BASE';}
  });

  document.getElementById('clientCreateForm')?.addEventListener('submit',async e=>{
    e.preventDefault();const form=e.currentTarget,btn=form.querySelector('button[type=submit]'),st=document.getElementById('clientCreateStatus');
    const payload={name:document.getElementById('clientName')?.value.trim(),username:document.getElementById('clientUsername')?.value.trim(),email:document.getElementById('clientEmail')?.value.trim(),password:document.getElementById('clientPassword')?.value||''};
    btn.disabled=true;btn.textContent='CRIANDO...';if(st){st.className='inline-status';st.textContent='Criando acesso do cliente...';}
    try{
      const r=await fetch('api/clients.php?action=create',{method:'POST',credentials:'same-origin',cache:'no-store',headers:{'Content-Type':'application/json','X-CSRF-Token':window.MR_BOOTSTRAP?.csrf||''},body:JSON.stringify(payload)});
      const j=await r.json();if(!r.ok||!j.ok)throw new Error(j.error||'Não foi possível criar o cliente.');
      if(st){st.className='inline-status ok';st.textContent=`Cliente criado · login: ${j.username}`;}
      form.reset();showRoadAlert('info','CLIENTE CRIADO',`${j.name} já pode entrar com o login ${j.username}.`,'',3800);
    }catch(err){if(st){st.className='inline-status error';st.textContent=err.message||'Falha ao criar cliente.';}}
    finally{btn.disabled=false;btn.textContent='CRIAR CLIENTE';}
  });

  const PREF_INPUTS={prefVoice:'voice',prefVisual:'visual',prefSystem:'system',prefVibrate:'vibrate',prefManeuvers:'maneuvers',prefSpeeding:'speeding',pref300:'m300',pref200:'m200',pref100:'m100',pref50:'m50',prefFront:'front',prefRadar:'radar',prefPortable:'portable',prefVideo:'video',prefSignal:'signal',prefBump:'bump'};
  function renderAlertPrefs(){
    Object.entries(PREF_INPUTS).forEach(([id,key])=>{const el=document.getElementById(id);if(el)el.checked=alertPrefs[key]!==false;});
    const rate=document.getElementById('ttsRate'),pitch=document.getElementById('ttsPitch');if(rate)rate.value=String(alertPrefs.voiceRate||1);if(pitch)pitch.value=String(alertPrefs.voicePitch||1);
    const rv=document.getElementById('ttsRateValue'),pv=document.getElementById('ttsPitchValue');if(rv)rv.textContent=`${Number(alertPrefs.voiceRate||1).toFixed(2)}×`;if(pv)pv.textContent=`${Number(alertPrefs.voicePitch||1).toFixed(2)}×`;
  }
  Object.entries(PREF_INPUTS).forEach(([id,key])=>document.getElementById(id)?.addEventListener('change',e=>{alertPrefs[key]=!!e.target.checked;saveAlertPrefs();const st=document.getElementById('notificationPrefStatus');if(st)st.textContent='Preferências atualizadas ✓';}));
  document.getElementById('ttsRate')?.addEventListener('input',e=>{alertPrefs.voiceRate=Number(e.target.value)||1;document.getElementById('ttsRateValue').textContent=`${alertPrefs.voiceRate.toFixed(2)}×`;saveAlertPrefs();});
  document.getElementById('ttsPitch')?.addEventListener('input',e=>{alertPrefs.voicePitch=Number(e.target.value)||1;document.getElementById('ttsPitchValue').textContent=`${alertPrefs.voicePitch.toFixed(2)}×`;saveAlertPrefs();});
  document.getElementById('ttsVoice')?.addEventListener('change',e=>{alertPrefs.voiceName=e.target.value||'';saveAlertPrefs();const st=document.getElementById('ttsStatus');if(st)st.textContent='Voz selecionada e salva neste aparelho.';});
  function renderTtsVoices(list){
    const sel=document.getElementById('ttsVoice');if(!sel)return;const current=String(alertPrefs.voiceName||'');sel.innerHTML='<option value="">Voz padrão do aparelho</option>';
    (list||[]).forEach((v,i)=>{const o=document.createElement('option');o.value=v.name||'';o.textContent=`Voz ${i+1} · ${v.locale||'pt-BR'}${v.network?' · online':' · dispositivo'}`;if(o.value===current)o.selected=true;sel.appendChild(o);});
    const st=document.getElementById('ttsStatus');if(st)st.textContent=list?.length?`${list.length} vozes em português disponíveis. Escolha e toque em Testar voz.`:'Nenhuma voz extra em português encontrada; será usada a voz padrão.';
  }
  function requestTtsVoices(){
    const st=document.getElementById('ttsStatus');if(st)st.textContent='Carregando vozes do aparelho...';
    if(window.MusicRoadAndroid?.requestTtsVoices){try{window.MusicRoadAndroid.requestTtsVoices();return}catch(_){}}
    if('speechSynthesis'in window){const voices=speechSynthesis.getVoices().filter(v=>/^pt(-|_)/i.test(v.lang||''));renderTtsVoices(voices.map(v=>({name:v.name,locale:v.lang,network:false})));return;}
    if(st)st.textContent='Seleção de voz disponível no APK Android.';
  }
  document.addEventListener('mr:tts-voices',e=>renderTtsVoices(e.detail?.voices||[]));
  document.getElementById('refreshTtsVoices')?.addEventListener('click',requestTtsVoices);
  document.getElementById('testTtsVoice')?.addEventListener('click',()=>{saveAlertPrefs();const text='Olá. Esta é a voz do copiloto MusicRoad.';if(window.MusicRoadAndroid?.testTtsVoice){try{window.MusicRoadAndroid.testTtsVoice(text);return}catch(_){}}Player.speak(text);});

  const RADIO_KEY='mr:radio:stations:v1';
  function loadStations(){try{return JSON.parse(localStorage.getItem(RADIO_KEY)||'[]').filter(x=>x&&x.name&&x.url)}catch(_){return[]}}
  function saveStations(list){try{localStorage.setItem(RADIO_KEY,JSON.stringify(list))}catch(_){}renderStations();}
  function renderStations(){
    const box=document.getElementById('radioStations');if(!box)return;const list=loadStations();
    if(!list.length){box.innerHTML='<div class="radio-empty"><span>FM</span><strong>Nenhuma rádio online salva</strong><small>Adicione o nome, frequência e URL do stream.</small></div>';return;}
    box.innerHTML=list.map((r,i)=>`<article class="radio-station"><button class="radio-play" data-radio-play="${i}" type="button">▶</button><div><strong>${esc(r.name)}</strong><span>${esc(r.frequency||'Rádio online')}</span></div><button class="radio-remove" data-radio-remove="${i}" type="button" aria-label="Excluir">×</button></article>`).join('');
    box.querySelectorAll('[data-radio-play]').forEach(b=>b.addEventListener('click',()=>{const r=list[Number(b.dataset.radioPlay)];if(!r)return;Player.playTrack({id:`radio-${Date.now()}`,title:r.name,artist:r.frequency||'Rádio online',album:'Rádio',origin:'Rádio',source:r.url});const st=document.getElementById('radioStatus');if(st)st.textContent=`Tocando ${r.name}${r.frequency?' · '+r.frequency:''}`;}));
    box.querySelectorAll('[data-radio-remove]').forEach(b=>b.addEventListener('click',()=>{list.splice(Number(b.dataset.radioRemove),1);saveStations(list);}));
  }
  document.getElementById('radioStationForm')?.addEventListener('submit',e=>{e.preventDefault();const name=document.getElementById('radioName').value.trim(),frequency=document.getElementById('radioFrequency').value.trim(),url=document.getElementById('radioUrl').value.trim(),st=document.getElementById('radioStatus');let secure=false;try{secure=new URL(url).protocol==='https:'}catch(_){}if(!secure){if(st)st.textContent='Use uma URL HTTPS direta do stream de áudio.';return;}const list=loadStations();list.push({name,frequency,url});saveStations(list);e.currentTarget.reset();if(st)st.textContent=`${name} salva ✓`;});
  function updateFmStatus(){
    const st=document.getElementById('fmRadioStatus');if(!st)return;
    if(!window.MusicRoadAndroid?.getFmRadioStatus){st.textContent='Rádio FM físico está disponível somente no APK quando o aparelho expõe um app de rádio.';return;}
    try{const x=JSON.parse(window.MusicRoadAndroid.getFmRadioStatus()||'{}');st.textContent=x.available?`Rádio FM encontrado: ${x.label||'aplicativo do aparelho'}.`:'Este aparelho não expõe um aplicativo de rádio FM ao MusicRoad. Use rádios online.';}catch(_){st.textContent='Não foi possível verificar o rádio FM deste aparelho.';}
  }
  document.getElementById('openFmRadio')?.addEventListener('click',()=>{if(window.MusicRoadAndroid?.openFmRadioApp){try{const ok=window.MusicRoadAndroid.openFmRadioApp();if(!ok){const st=document.getElementById('fmRadioStatus');if(st)st.textContent='Nenhum aplicativo de rádio FM compatível foi encontrado.';}return}catch(_){}}const st=document.getElementById('fmRadioStatus');if(st)st.textContent='O rádio FM físico depende do hardware/aplicativo do próprio telefone.';});
  renderAlertPrefs();renderStations();setTimeout(requestTtsVoices,900);

  updateOfflineStatus();
  setDestinationMode('city');loadDestinationStates();
  document.body.dataset.screen='board';initNative();initMap();setTimeout(loadCachedCityMap,500);
  try{const mapEl=document.getElementById('map');if(window.ResizeObserver&&mapEl)new ResizeObserver(()=>{if(activeScreen==='map')requestAnimationFrame(()=>map?.invalidateSize({pan:false}))}).observe(mapEl);}catch(_){}
  try{window.visualViewport?.addEventListener('resize',()=>{if(activeScreen==='map')setTimeout(()=>map?.invalidateSize({pan:false}),40)});}catch(_){}
  addEventListener('orientationchange',()=>{if(activeScreen==='map')[80,260].forEach(ms=>setTimeout(()=>map?.invalidateSize({pan:false}),ms))});
  startGps();loadServerMusic().then(()=>{if(Player.native)loadNativeMusic()});
  let deviceSecretTaps=0,deviceSecretTimer=null;const versionDeviceCard=document.getElementById('versionDeviceCard'),removeDeviceBtn=document.getElementById('removeRegisteredDevice');
  versionDeviceCard?.addEventListener('click',e=>{if(e.target===removeDeviceBtn)return;clearTimeout(deviceSecretTimer);deviceSecretTimer=setTimeout(()=>deviceSecretTaps=0,4500);if(++deviceSecretTaps>=7&&removeDeviceBtn){removeDeviceBtn.hidden=false;deviceSecretTaps=0;showRoadAlert('info','DISPOSITIVO','Opção avançada liberada.','',2200)}});
  removeDeviceBtn?.addEventListener('click',()=>{if(!confirm('Remover este aparelho da conta? Na próxima instalação será necessário entrar novamente.'))return;try{window.MusicRoadAndroid?.removeRegisteredDevice?.(String(window.MR_BOOTSTRAP?.csrf||''))}catch(_){showRoadAlert('warning','DISPOSITIVO','Recurso disponível somente no app Android.','',3200)}});
  document.addEventListener('mr:device-auth',e=>{const d=e.detail||{};if(d.action==='remove'&&d.ok){showRoadAlert('info','DISPOSITIVO REMOVIDO','Este aparelho não fará mais login automático.','',3200);setTimeout(()=>document.getElementById('logoutForm')?.requestSubmit(),900)}});
  const hash=location.hash.replace('#','');if(['board','music','radio','map','offline','settings'].includes(hash))showScreen(hash);
})();

/* v13.4 Neon Drive UI shell */
(()=>{
  const drawer=document.getElementById('mainDrawer');
  const toggle=document.getElementById('mainMenuToggle');
  const backdrop=document.getElementById('mainDrawerBackdrop');
  const open=()=>{if(!drawer)return;drawer.classList.add('open');drawer.setAttribute('aria-hidden','false');toggle?.setAttribute('aria-expanded','true');};
  const close=()=>{if(!drawer)return;drawer.classList.remove('open');drawer.setAttribute('aria-hidden','true');toggle?.setAttribute('aria-expanded','false');};
  toggle?.addEventListener('click',()=>drawer?.classList.contains('open')?close():open());
  backdrop?.addEventListener('click',close);
  drawer?.querySelectorAll('[data-nav]').forEach(b=>b.addEventListener('click',close));
  document.getElementById('mapBack')?.addEventListener('click',(ev)=>{ev.preventDefault();open();});
  const routeText=document.getElementById('mapRouteText');
  const turnRoad=document.getElementById('auroraTurnRoad');
  const turnInstruction=document.getElementById('auroraTurnInstruction');
  const turnDistance=document.getElementById('auroraTurnDistance');
  const tripSummary=document.getElementById('tripSummary');
  const sync=()=>{
    if(routeText&&turnRoad)turnRoad.textContent=routeText.textContent||'MusicRoad Navigation';
    if(tripSummary){
      const t=(tripSummary.textContent||'').trim();
      const dist=t.match(/(\d+(?:[.,]\d+)?)\s*km/i); const mins=t.match(/(\d+)\s*min/i);
      const d=document.getElementById('auroraTripDistance'),m=document.getElementById('auroraTripTime');
      if(d&&dist)d.textContent=dist[1]+' km'; if(m&&mins)m.textContent=mins[1]+' min';
    }
    if(document.body.classList.contains('trip-active')){
      if(turnDistance&&turnDistance.textContent==='Rota')turnDistance.textContent='Navegando';
      if(turnInstruction&&turnInstruction.textContent==='Siga o trajeto destacado')turnInstruction.textContent='Continue pela rota destacada';
    } else {
      if(turnDistance)turnDistance.textContent='Rota';
      if(turnInstruction)turnInstruction.textContent='Siga o trajeto destacado';
    }
  };
  sync(); setInterval(sync,1400);

  document.getElementById('openDriveOs')?.addEventListener('click',()=>{location.href='horizontal/';});
})();

(() => {
  'use strict';
  const bridge=window.MusicRoadAndroid||null;
  const native=!!(bridge&&typeof bridge.playQueue==='function');
  const audio=document.getElementById('webAudio');
  const nowTitle=document.getElementById('nowTitle'),nowArtist=document.getElementById('nowArtist');
  const boardTitle=document.getElementById('boardNowTitle'),boardArtist=document.getElementById('boardNowArtist');
  const progress=document.getElementById('playerProgress');
  let queue=[],index=-1,current=null,nativePlaying=false,lastPlayedId='';document.body.classList.add('player-empty');

  function absolute(path){try{return new URL(path,location.href).href}catch(_){return path}}
  function isDrive(t){return String(t?.origin||'').trim().toLowerCase()==='google drive'}
  function driveId(t){if(!isDrive(t))return'';const raw=String(t?.origin_ref||'').trim();return (!raw||/^https?:/i.test(raw))?'':raw}
  function normalize(track){
    const t={...track};
    t.id=String(t.id??t.origin_ref??Math.random());t.title=String(t.title||'Sem título');t.artist=String(t.artist||'Artista desconhecido');t.album=String(t.album||'');t.origin=String(t.origin||'Servidor');
    t.duration_ms=Number(t.duration_ms||0)||Number(t.duration||0)*1000;
    let src=String(t.source||t.content_uri||'').trim();
    if(!src&&isDrive(t)&&driveId(t))src=`api/drive_stream.php?id=${encodeURIComponent(driveId(t))}`;
    if(!src&&t.url)src=String(t.url);
    if(!src&&/^https?:/i.test(String(t.origin_ref||'')))src=String(t.origin_ref);
    if(src&&(/^\//.test(src)||!/^[a-z]+:/i.test(src)))src=absolute(src);
    t.source=src;return t;
  }
  function setStatus(msg,error=false){const el=document.getElementById('musicStatus');if(!el)return;el.textContent=msg;el.classList.toggle('player-error',!!error)}
  function updateLabels(track){current=track||current;const has=!!current;document.body.classList.toggle('player-empty',!has);const title=current?.title||'Nenhuma música',artist=current?.artist||'MusicRoad';nowTitle.textContent=title;nowArtist.textContent=artist;boardTitle.textContent=title;boardArtist.textContent=artist}
  function setPlayIcon(playing){document.querySelectorAll('[data-player="toggle"]').forEach(b=>b.textContent=playing?'⏸':'▶')}
  function showPlayerError(msg){msg=msg||'Não foi possível reproduzir esta faixa.';nowArtist.textContent=msg;boardArtist.textContent=msg;setPlayIcon(false);setStatus(msg,true);document.dispatchEvent(new CustomEvent('mr:player-error',{detail:{track:current,message:msg}}))}
  function setQueue(tracks,start=0,autoplay=true){
    const normalized=(tracks||[]).map(normalize);
    const wanted=normalized[Math.max(0,Math.min(start,Math.max(0,normalized.length-1)))]?.id;
    queue=normalized.filter(t=>t.source);
    if(!queue.length){showPlayerError('As músicas desta lista não possuem uma fonte de áudio válida.');return false}
    index=wanted?queue.findIndex(t=>String(t.id)===String(wanted)):-1;if(index<0)index=0;
    current=queue[index];updateLabels(current);progress.value=0;setStatus(`Carregando: ${current.title}`);
    if(native){try{bridge.playQueue(JSON.stringify(queue),index);nativePlaying=!!autoplay;setPlayIcon(nativePlaying);return true}catch(e){showPlayerError('Falha ao enviar a música para o player Android.');return false}}
    loadWeb(current,autoplay);return true;
  }
  async function loadWeb(track,autoplay=true){if(!track?.source){showPlayerError('Fonte de áudio não disponível.');return}current=track;updateLabels(track);audio.src=track.source;audio.load();if(autoplay){try{await audio.play()}catch(e){showPlayerError(e?.message||'Falha ao iniciar o áudio.')}}}
  function playTrack(track,allTracks){const list=(allTracks&&allTracks.length?allTracks:[track]).map(normalize);const wanted=String(track.id),at=Math.max(0,list.findIndex(t=>String(t.id)===wanted));return setQueue(list,at,true)}
  function toggle(){if(native){bridge.togglePlay();return}if(!audio.src&&current){loadWeb(current,true);return}if(audio.paused)audio.play().catch(e=>showPlayerError(e?.message));else audio.pause()}
  function next(){if(native){bridge.next();return}if(!queue.length)return;index=(index+1)%queue.length;loadWeb(queue[index],true)}
  function previous(){if(native){bridge.previous();return}if(!queue.length)return;index=(index-1+queue.length)%queue.length;loadWeb(queue[index],true)}
  function seek(p){if(native){bridge.seekToPercent(Number(p));return}if(audio.duration)audio.currentTime=audio.duration*(Number(p)/100)}
  function speak(text){if(!text)return;if(native&&typeof bridge.speakAlert==='function'){bridge.speakAlert(text);return}if('speechSynthesis'in window){const u=new SpeechSynthesisUtterance(text);u.lang='pt-BR';try{const p=JSON.parse(localStorage.getItem('mr:alert:prefs:v2')||'{}');u.rate=Math.max(.5,Math.min(2,Number(p.voiceRate)||1));u.pitch=Math.max(.5,Math.min(2,Number(p.voicePitch)||1));if(p.voiceName){const v=speechSynthesis.getVoices().find(x=>x.name===p.voiceName);if(v)u.voice=v;}}catch(_){}speechSynthesis.cancel();speechSynthesis.speak(u)}}
  function onNativeState(state){
    if(!state)return;nativePlaying=!!state.playing;setPlayIcon(nativePlaying);
    const idx=Number(state.index);if(Number.isInteger(idx)&&queue[idx]){index=idx;current=queue[idx]}
    if(state.title&&!current)current={id:state.id,title:state.title,artist:state.artist||'',origin:state.origin||''};
    updateLabels(current||state);
    const dur=Number(state.durationMs||state.duration_ms||0),pos=Number(state.positionMs||state.position_ms||0);if(dur>0)progress.value=Math.max(0,Math.min(100,pos/dur*100));
    if(state.error){showPlayerError(String(state.error));return}
    if(state.prepared&&!nativePlaying)setStatus(`Pronta: ${state.title||current?.title||'música'}`);if(nativePlaying)setStatus(`Tocando: ${state.title||current?.title||'música'}`);
    const played=String(state.id||current?.id||'');if(nativePlaying&&played&&played!==lastPlayedId){lastPlayedId=played;markPlayed(current||state)}
  }
  function markPlayed(track){if(!track||String(track.id).startsWith('android-'))return;const id=Number(track.id);if(!Number.isFinite(id)||id<=0)return;fetch('api/library.php?action=played',{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-Token':window.MR_BOOTSTRAP?.csrf||''},body:JSON.stringify({id})}).catch(()=>{})}
  function getNativeLibrary(){
    if(!native)return Promise.resolve({ok:false,tracks:[],permission:'unsupported'});
    if(typeof bridge.scanMusicLibraryAsync==='function')return new Promise(resolve=>{let done=false;const finish=data=>{if(done)return;done=true;clearTimeout(timer);document.removeEventListener('mr:native-library',onData);resolve(data||{ok:false,tracks:[],permission:'error'})},onData=e=>finish(e.detail);document.addEventListener('mr:native-library',onData,{once:true});const timer=setTimeout(()=>finish({ok:false,tracks:[],permission:'timeout'}),20000);try{bridge.scanMusicLibraryAsync()}catch(_){finish({ok:false,tracks:[],permission:'error'})}});
    try{return Promise.resolve(JSON.parse(bridge.getMusicLibrary()||'{}'))}catch(e){return Promise.resolve({ok:false,tracks:[],permission:'error'})}
  }
  function requestNativePermission(){if(native&&typeof bridge.requestAudioPermission==='function')bridge.requestAudioPermission()}
  function openNativeLibrary(){if(native&&typeof bridge.openNativeLibrary==='function')bridge.openNativeLibrary()}
  function setTripMode(active){if(native&&typeof bridge.setTripMode==='function')bridge.setTripMode(!!active)}

  document.querySelectorAll('[data-player="toggle"]').forEach(b=>b.addEventListener('click',toggle));
  document.querySelector('[data-player="prev"]')?.addEventListener('click',previous);document.querySelector('[data-player="next"]')?.addEventListener('click',next);progress?.addEventListener('input',()=>seek(progress.value));
  if(!native){audio.addEventListener('play',()=>{setPlayIcon(true);setStatus(`Tocando: ${current?.title||'música'}`);markPlayed(current)});audio.addEventListener('pause',()=>setPlayIcon(false));audio.addEventListener('timeupdate',()=>{progress.value=audio.duration?(audio.currentTime/audio.duration)*100:0});audio.addEventListener('ended',next);audio.addEventListener('error',()=>showPlayerError('O navegador não conseguiu abrir esta fonte de áudio.'))}else setTimeout(()=>{try{bridge.requestPlaybackState()}catch(_){}},300);
  window.Player={native,normalize,setQueue,playTrack,toggle,next,previous,seek,speak,onNativeState,getNativeLibrary,requestNativePermission,openNativeLibrary,setTripMode,getQueue:()=>queue,getCurrent:()=>current};
})();
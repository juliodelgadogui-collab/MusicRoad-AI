const Player = (() => {
  const audio = document.getElementById('audio');
  const playButton = document.getElementById('play');
  const progress = document.getElementById('progress');
  const nowTitle = document.getElementById('nowTitle');
  const nowArtist = document.getElementById('nowArtist');
  const cover = document.getElementById('cover');
  let queue = [], index = -1, currentTrack = null, currentObjectUrl = null, shuffle = false, sourceKind = '';

  function isLocal(track){ return !!(track?.deviceSource === 'folder' || track?.localBlobId || track?.localFileHandle || String(track?.origin||'').toLowerCase()==='dispositivo'); }
  function serverId(track){ return /^\d+$/.test(String(track?.id ?? '')) && !isLocal(track) ? Number(track.id) : 0; }
  function driveId(track){
    if (String(track?.origin||'').toLowerCase() !== 'google drive') return '';
    const ref=String(track?.origin_ref||'');
    const m=ref.match(/(?:\/d\/|[?&]id=)([A-Za-z0-9_-]{15,})/);
    return m?.[1] || (/^[A-Za-z0-9_-]{15,}$/.test(ref) ? ref : '');
  }
  function driveProxyUrl(track){ const id=driveId(track); return id ? `api/drive_stream.php?id=${encodeURIComponent(id)}&v=1` : ''; }

  function setQueue(list,start=0){
    queue=Array.isArray(list)?list:[];
    index=Math.max(0,Math.min(Number(start)||0,Math.max(0,queue.length-1)));
    if(queue[index]) load(queue[index],true);
  }

  function extensionMime(name=''){
    const ext=String(name).split('.').pop().toLowerCase();
    return {mp3:'audio/mpeg',m4a:'audio/mp4',mp4:'audio/mp4',aac:'audio/aac',ogg:'audio/ogg',oga:'audio/ogg',opus:'audio/ogg',wav:'audio/wav',wave:'audio/wav',flac:'audio/flac'}[ext] || '';
  }

  async function blobFromTrack(track){
    if(track.localFileHandle){
      const handle=track.localFileHandle;
      let state='granted';
      if(typeof handle.queryPermission==='function') state=await handle.queryPermission({mode:'read'});
      if(state!=='granted' && typeof handle.requestPermission==='function') state=await handle.requestPermission({mode:'read'});
      if(state!=='granted') throw new Error('Acesso à pasta expirou. Autorize novamente a pasta de músicas.');
      const file=await handle.getFile();
      if(!file || file.size===0) throw new Error('O arquivo local está vazio ou não pode ser lido.');
      return file;
    }
    if(track.localBlobId){
      const row=await MRDB.get('blobs',track.localBlobId);
      if(row?.blob instanceof Blob && row.blob.size>0) return row.blob;
      throw new Error('A cópia local desta música não está mais disponível. Autorize a pasta novamente.');
    }
    throw new Error('Esta música local perdeu o vínculo com o arquivo. Autorize a pasta novamente.');
  }

  async function resolveSource(track){
    if(currentObjectUrl){ URL.revokeObjectURL(currentObjectUrl); currentObjectUrl=null; }
    if(isLocal(track)){
      sourceKind='local';
      const original=await blobFromTrack(track);
      const mime=String(original.type || track.mime_type || extensionMime(track.file_name||track.title||'') || 'application/octet-stream');
      const blob = original.type ? original : new Blob([original],{type:mime});
      track._resolvedMime=mime; track._resolvedSize=blob.size;
      currentObjectUrl=URL.createObjectURL(blob);
      return currentObjectUrl;
    }
    const drive=driveProxyUrl(track);
    if(drive){sourceKind='drive';return drive;}
    if(track.url){sourceKind='remote';return track.url;}
    if(/^https?:/i.test(String(track.origin_ref||''))){sourceKind='remote';return track.origin_ref;}
    throw new Error('Esta música não possui uma fonte de áudio válida.');
  }

  async function load(track,autoplay=true){
    if(!track) return;
    currentTrack=track;
    nowTitle.textContent=track.title||'Sem título';
    nowArtist.textContent=`${track.artist||'Artista desconhecido'} · ${track.origin||'Dispositivo'}`;
    cover.src=track.cover_url||'assets/img-icon.svg';
    progress.value=0;
    audio.pause();
    audio.removeAttribute('src');
    try{
      const src=await resolveSource(track);
      audio.src=src;
      audio.load();
      if(autoplay) await tryPlay();
    }catch(err){ showError(err?.message||'Não consegui carregar esta música.'); }
  }

  async function tryPlay(){
    try{
      await audio.play();
    }catch(err){
      if(err?.name==='NotAllowedError'){
        nowArtist.textContent='Música carregada. Toque em ▶ para iniciar o áudio.';
        return;
      }
      if(err?.name==='NotSupportedError'){
        if(sourceKind==='local'){
          showError(`O arquivo foi localizado, mas o navegador não suporta o codec (${currentTrack?._resolvedMime || currentTrack?.mime_type || 'desconhecido'}).`);
        }else if(sourceKind==='drive'){
          showError('O Google Drive não entregou um fluxo de áudio compatível. Teste o arquivo no Painel ADM.');
        }else showError('A fonte recebida não é um áudio suportado neste navegador.');
        return;
      }
      showError(err?.message||'Falha ao iniciar o áudio.');
    }
  }

  function showError(message){
    nowArtist.textContent=message;
    playButton.textContent='▶';
    document.dispatchEvent(new CustomEvent('mr:player-error',{detail:{track:currentTrack,message}}));
  }

  function next(){
    if(!queue.length) return;
    index=shuffle?Math.floor(Math.random()*queue.length):(index+1)%queue.length;
    load(queue[index],true);
  }
  function previous(){
    if(!queue.length) return;
    index=index<=0?queue.length-1:index-1;
    load(queue[index],true);
  }

  async function duckForAlert(text){
    if(!document.getElementById('voiceAlerts')?.checked || !('speechSynthesis' in window)) return;
    const old=audio.volume;
    const speechVolume=Number(document.getElementById('alertVolume')?.value||1);
    audio.volume=Math.max(.12,old*.3);
    const utter=new SpeechSynthesisUtterance(text);utter.lang='pt-BR';utter.volume=Math.max(.1,Math.min(1,speechVolume));
    utter.onend=()=>{const t=setInterval(()=>{audio.volume=Math.min(old,audio.volume+.08);if(audio.volume>=old)clearInterval(t);},100);};
    speechSynthesis.cancel();speechSynthesis.speak(utter);
  }

  playButton?.addEventListener('click',()=>{
    if(!audio.src && currentTrack) return load(currentTrack,true);
    if(!audio.src && queue[index]) return load(queue[index],true);
    if(audio.paused) return tryPlay();
    audio.pause();
  });
  document.getElementById('next')?.addEventListener('click',next);
  document.getElementById('prev')?.addEventListener('click',previous);
  document.getElementById('shuffle')?.addEventListener('click',e=>{shuffle=!shuffle;e.currentTarget.classList.toggle('active',shuffle);});

  audio?.addEventListener('error',()=>{
    if(!currentTrack) return;
    const code=audio.error?.code;
    if(sourceKind==='local'){
      showError(code===4 ? `Arquivo encontrado, mas formato/codec não reproduzível (${currentTrack?._resolvedMime||currentTrack?.mime_type||'desconhecido'}).` : 'O navegador perdeu acesso ao arquivo local. Autorize a pasta novamente.');
    }else if(sourceKind==='drive'){
      showError('Falha no streaming do Google Drive. Confirme se o arquivo está público e se é áudio real.');
    }else showError('Não consegui reproduzir este arquivo.');
  });
  audio?.addEventListener('play',()=>{
    playButton.textContent='⏸';
    const id=serverId(currentTrack);
    if(id) fetch('api/library.php?action=played',{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-Token':window.MR_CSRF||window.csrfToken||''},body:JSON.stringify({id})}).catch(()=>{});
    document.dispatchEvent(new CustomEvent('mr:track-played',{detail:{track:currentTrack}}));
  });
  audio?.addEventListener('pause',()=>{playButton.textContent='▶';});
  audio?.addEventListener('ended',next);
  audio?.addEventListener('timeupdate',()=>{progress.value=audio.duration?(audio.currentTime/audio.duration)*100:0;});
  audio?.addEventListener('loadedmetadata',()=>{if(currentTrack && Number.isFinite(audio.duration)&&audio.duration>0)currentTrack.duration=Math.round(audio.duration);});
  progress?.addEventListener('input',()=>{if(audio.duration)audio.currentTime=(Number(progress.value)/100)*audio.duration;});

  return {setQueue,load,next,previous,duckForAlert,getCurrent:()=>currentTrack};
})();

const SmartMix = (() => {
  const stop = new Set(['quero','uma','um','para','pra','de','do','da','das','dos','com','sem','mais','menos','muito','muita','musica','musicas','música','músicas','playlist','mix','monte','crie','criar','encontre','achar','ouvir','escutar','horas','hora','minutos','minuto','tempo','que','eu','as','os','e','a','o']);
  const groups = {
    sertanejo: ['sertanejo','modao','modão','universitario','universitário','sofrencia','sofrência','roca','roça'],
    forro: ['forro','forró','piseiro','xote','baiao','baião','vaquejada'],
    romantico: ['romantico','romântico','romantica','romântica','amor','love','sofrencia','sofrência'],
    animado: ['festa','ao vivo','live','forro','forró','piseiro','sertanejo','funk','pagode','samba','eletronica','eletrônica','dance'],
    calmo: ['calmo','calma','acustico','acústico','mpb','romantico','romântico','relax','relaxar'],
    rock: ['rock','metal','punk','grunge'],
    gospel: ['gospel','louvor','adoração','adoracao','jesus'],
    pagode: ['pagode','samba'],
    funk: ['funk'],
    mpb: ['mpb','brasil','brasileira'],
    viagem: ['viagem','estrada','road','trip','drive']
  };

  function norm(value='') {
    return String(value).normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();
  }

  function seedKeywords(prompt) {
    const n = norm(prompt);
    const words = n.split(/\s+/).filter(w => w.length > 2 && !stop.has(w));
    const expanded = new Set(words);
    Object.entries(groups).forEach(([key, vals]) => {
      if (n.includes(norm(key)) || vals.some(v => n.includes(norm(v)))) {
        vals.forEach(v => expanded.add(norm(v)));
      }
    });
    return [...expanded];
  }

  function durationTarget(prompt) {
    const n = norm(prompt);
    let m = n.match(/(\d+(?:[.,]\d+)?)\s*h(?:ora|oras)?/);
    if (m) return Math.max(10, Math.round(parseFloat(m[1].replace(',','.')) * 60));
    m = n.match(/(\d+)\s*min/);
    if (m) return Math.max(10, parseInt(m[1],10));
    return 180;
  }

  function trackText(t) {
    return norm([t.title,t.artist,t.album,t.genre].filter(Boolean).join(' '));
  }

  function scoreTrack(t, keywords) {
    const title = norm(t.title);
    const artist = norm(t.artist);
    const album = norm(t.album);
    const genre = norm(t.genre);
    let score = 0;
    keywords.forEach(k => {
      if (!k) return;
      if (genre.includes(k)) score += 12;
      if (title.includes(k)) score += 9;
      if (artist.includes(k)) score += 7;
      if (album.includes(k)) score += 5;
      if (trackText(t).includes(k)) score += 2;
    });
    if (+t.is_favorite === 1) score += 2;
    score += Math.min(4, Number(t.play_count || 0) * 0.15);
    return score;
  }

  function shuffle(list) {
    const a = [...list];
    for (let i=a.length-1;i>0;i--) {
      const j = Math.floor(Math.random()*(i+1));
      [a[i],a[j]]=[a[j],a[i]];
    }
    return a;
  }

  function duplicateKey(t) {
    let title = norm(t.title).replace(/\b(remaster(?:ed)?|ao vivo|live|official|oficial|audio|video|clipe|lyrics|lyric)\b/g,'').replace(/\s+/g,' ').trim();
    const artist = norm(t.artist).replace(/arquivo local|google drive|links avulsos/g,'').trim();
    return `${title}|${artist}`;
  }

  function duplicates(tracks) {
    const map = new Map();
    tracks.forEach(t => {
      const key = duplicateKey(t);
      if (!key || key.startsWith('|')) return;
      if (!map.has(key)) map.set(key, []);
      map.get(key).push(t);
    });
    return [...map.values()].filter(g => g.length > 1).sort((a,b)=>b.length-a.length);
  }

  function forgotten(tracks) {
    return [...tracks].sort((a,b) => {
      const ad = a.last_played_at ? new Date(a.last_played_at).getTime() : 0;
      const bd = b.last_played_at ? new Date(b.last_played_at).getTime() : 0;
      if (ad !== bd) return ad - bd;
      return Number(a.play_count || 0) - Number(b.play_count || 0);
    });
  }

  function byDuration(list, minutes) {
    const target = minutes * 60;
    let total = 0;
    const out = [];
    for (const t of list) {
      out.push(t);
      total += Number(t.duration || 210);
      if (total >= target) break;
    }
    return out;
  }

  function build(prompt, tracks) {
    const n = norm(prompt);
    if (!tracks.length) return {type:'empty', message:'Sua biblioteca está vazia. Adicione músicas do dispositivo ou importe do Google Drive.'};

    if (/repetid|duplicad/.test(n)) {
      const groups = duplicates(tracks);
      return {type:'duplicates', groups, message: groups.length ? `Encontrei ${groups.length} grupo(s) de possíveis músicas repetidas.` : 'Não encontrei músicas repetidas pelo título e artista.'};
    }

    if (/esquecid|nao escuto|não escuto|tempo sem ouvir|antiga/.test(prompt.toLowerCase())) {
      const result = forgotten(tracks).slice(0,100);
      return {type:'playlist', tracks: result, name:'Esquecidas da Biblioteca', message:'Priorizei músicas nunca tocadas ou ouvidas há mais tempo.'};
    }

    if (/favorit/.test(n)) {
      const fav = tracks.filter(t => +t.is_favorite === 1);
      return {type:'playlist', tracks: fav.length ? fav : tracks.slice(0,50), name:'Favoritas', message: fav.length ? 'Mix feito com suas favoritas.' : 'Você ainda não marcou favoritas; mostrei a biblioteca.'};
    }

    const keywords = seedKeywords(prompt);
    let ranked = tracks.map(t => ({t,score:scoreTrack(t,keywords)})).sort((a,b)=>b.score-a.score);
    const hasUsefulMatch = ranked.some(x => x.score > 0);
    let pool = hasUsefulMatch ? ranked.filter(x => x.score > 0).map(x => x.t) : shuffle(tracks);
    if (hasUsefulMatch) {
      const topScore = ranked[0]?.score || 0;
      const strong = ranked.filter(x => x.score >= Math.max(2, topScore * .35)).map(x=>x.t);
      pool = shuffle(strong.length >= 5 ? strong : pool);
    }
    const minutes = durationTarget(prompt);
    const selected = byDuration(pool, minutes);
    return {
      type:'playlist',
      tracks:selected,
      name: keywords.length ? `Smart Mix · ${keywords.slice(0,3).join(' + ')}` : 'Smart Mix',
      target_minutes: minutes,
      message: hasUsefulMatch
        ? `Usei título, artista, álbum, gênero, favoritas e histórico local para montar aproximadamente ${minutes} min.`
        : `Não encontrei metadados suficientes para esse pedido; montei um mix variado de aproximadamente ${minutes} min.`
    };
  }

  return {build, duplicates, norm};
})();

(()=>{
'use strict';
const cfg=window.EPC_V3||{};
const API=cfg.api||'../../api/v8';
const $=id=>document.getElementById(id);
const state={map:null,position:null,userMarker:null,destMarker:null,routeLine:null,radarLayer:null,route:null,destination:null,tracks:[],filtered:[],currentTrack:null,lastContextAt:0,lastRadarAt:0,watchId:null};

function setText(id,value){const el=$(id);if(el)el.textContent=value;}
function km(m){return (Number(m||0)/1000).toFixed(Number(m||0)>=100000?0:1);}
function mins(sec){return Math.max(1,Math.round(Number(sec||0)/60));}
function escapeHtml(v){return String(v??'').replace(/[&<>'"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[c]));}
function fetchJson(url,opts={}){return fetch(url,{credentials:'same-origin',...opts}).then(async r=>{let d={};try{d=await r.json();}catch(e){}if(!r.ok||d.ok===false)throw new Error(d.error||('HTTP '+r.status));return d;});}

function initMap(){
  state.map=L.map('map',{zoomControl:false,attributionControl:true}).setView([-20.3,-44.0],6);
  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap'}).addTo(state.map);
  L.control.zoom({position:'bottomleft'}).addTo(state.map);
  state.radarLayer=L.layerGroup().addTo(state.map);
}

function startGps(){
  if(!navigator.geolocation){setText('gpsState','INDISPONÍVEL');return;}
  setText('gpsState','LOCALIZANDO');
  state.watchId=navigator.geolocation.watchPosition(onPosition,onGpsError,{enableHighAccuracy:true,maximumAge:2500,timeout:15000});
}

function onPosition(pos){
  const c=pos.coords;
  const next={lat:c.latitude,lon:c.longitude,accuracy:c.accuracy||0,speed:Number.isFinite(c.speed)?c.speed*3.6:0,heading:Number.isFinite(c.heading)?c.heading:null};
  state.position=next;
  setText('speedValue',Math.max(0,Math.round(next.speed)));
  setText('gpsState',Math.round(next.accuracy)+' m');
  if(!state.userMarker){
    state.userMarker=L.circleMarker([next.lat,next.lon],{radius:8,weight:3,color:'#f3d074',fillColor:'#b3131b',fillOpacity:1}).addTo(state.map);
    state.map.setView([next.lat,next.lon],15);
  }else state.userMarker.setLatLng([next.lat,next.lon]);
  const now=Date.now();
  if(now-state.lastContextAt>15000){state.lastContextAt=now;refreshContext();}
  if(now-state.lastRadarAt>20000){state.lastRadarAt=now;refreshRadars();}
}
function onGpsError(err){setText('gpsState',err.code===1?'PERMISSÃO NEGADA':'SEM SINAL');}

function locate(){if(!state.position)return;state.map.flyTo([state.position.lat,state.position.lon],16,{duration:.6});}

async function searchDestination(q){
  const data=await fetchJson(API+'/geocode.php?q='+encodeURIComponent(q));
  if(!data.results?.length)throw new Error('Destino não encontrado.');
  const r=data.results[0];
  state.destination={lat:Number(r.lat),lon:Number(r.lon),name:r.name||r.label||q};
  setText('routeTitle',state.destination.name);
  if(state.destMarker)state.destMarker.remove();
  state.destMarker=L.marker([state.destination.lat,state.destination.lon]).addTo(state.map).bindPopup(escapeHtml(state.destination.name));
  if(state.position)await calculateRoute();
  else throw new Error('Aguardando sua localização para calcular a rota.');
}

async function calculateRoute(){
  if(!state.position||!state.destination)return;
  setText('routeTitle','Calculando…');
  const body={from_lat:state.position.lat,from_lon:state.position.lon,to_lat:state.destination.lat,to_lon:state.destination.lon};
  const data=await fetchJson(API+'/route.php',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
  state.route=data.route;
  drawRoute(data.route);
  setText('routeTitle',state.destination.name);
  $('routeMetrics').innerHTML=`<span>${km(data.route.distance)} km</span><span>${mins(data.route.duration)} min</span>`;
  renderSteps(data.route);
  await refreshContext();
  await refreshRadars();
}

function drawRoute(route){
  const coords=route?.geometry?.coordinates||[];
  if(!coords.length)return;
  const latlngs=coords.map(c=>[Number(c[1]),Number(c[0])]);
  if(state.routeLine)state.routeLine.remove();
  state.routeLine=L.polyline(latlngs,{weight:6,color:'#c51a23',opacity:.92,lineCap:'round'}).addTo(state.map);
  state.map.fitBounds(state.routeLine.getBounds(),{padding:[50,50]});
}

function renderSteps(route){
  const steps=[];
  for(const leg of route?.legs||[])for(const s of leg.steps||[])steps.push(s);
  const box=$('routeSteps');
  if(!steps.length){box.innerHTML='<p>Rota pronta. Siga o traçado no mapa.</p>';return;}
  box.innerHTML=steps.slice(0,8).map(s=>{
    const man=s.maneuver||{};
    const name=s.name||man.instruction||'Continue';
    const dist=Number(s.distance||0);
    return `<div class="v3-step"><strong>${escapeHtml(name)}</strong><br><span>${dist>=1000?(dist/1000).toFixed(1)+' km':Math.round(dist)+' m'}</span></div>`;
  }).join('');
}

function routePoints(){
  const coords=state.route?.geometry?.coordinates||[];
  if(!coords.length)return [];
  const max=40,step=Math.max(1,Math.floor(coords.length/max)),out=[];
  for(let i=0;i<coords.length;i+=step)out.push({lat:Number(coords[i][1]),lon:Number(coords[i][0])});
  const last=coords[coords.length-1];
  if(out.length&&last)out.push({lat:Number(last[1]),lon:Number(last[0])});
  return out.slice(0,48);
}

async function refreshContext(){
  if(!state.position)return;
  try{
    const payload={lat:state.position.lat,lon:state.position.lon,speed_kmh:state.position.speed||70,route_points:routePoints(),include:['hazards','weather']};
    const data=await fetchJson(API+'/context.php',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)});
    renderWeather(data.weather||[]);
    renderContextAlerts(data.hazards||[],data.priorities||[]);
    const badge=$('serverBadge');if(badge){badge.textContent=(data.road_data_mode||'SERVER')+' · '+(data.version||'V3');badge.style.opacity='1';}
  }catch(e){const badge=$('serverBadge');if(badge){badge.textContent='SERVIDOR OFFLINE';badge.style.opacity='.65';}}
}

function renderWeather(items){
  const w=items[0];
  if(!w){setText('weatherTitle','Sem previsão');setText('weatherText','Não foi possível carregar o clima da rota.');return;}
  setText('weatherTitle',w.condition||w.summary||'Condição da rota');
  const parts=[];
  if(w.temperature_c!=null)parts.push(Math.round(w.temperature_c)+'°C');
  if(w.rain_mm!=null)parts.push('chuva '+Number(w.rain_mm).toFixed(1)+' mm');
  if(w.wind_kmh!=null)parts.push('vento '+Math.round(w.wind_kmh)+' km/h');
  setText('weatherText',w.alert||parts.join(' · ')||'Previsão atualizada.');
  const sev=String(w.severity||'OK').toUpperCase();setText('weatherSeverity',sev);
}

function renderContextAlerts(hazards,priorities){
  const box=$('alertsList');
  const arr=[];
  for(const h of hazards.slice(0,5))arr.push({title:(h.type||'ALERTA').replaceAll('_',' '),sub:[h.road,h.ahead_m!=null?Math.round(h.ahead_m)+' m':'',h.source].filter(Boolean).join(' · ')});
  for(const p of priorities.slice(0,4))if(!arr.some(x=>x.title===p.message))arr.push({title:p.message||p.kind||'ATENÇÃO',sub:[p.severity,p.ahead_m!=null?Math.round(p.ahead_m)+' m':''].filter(Boolean).join(' · ')});
  if(!arr.length){box.innerHTML='<p>Nenhum alerta oficial relevante nesta rota.</p>';return;}
  box.innerHTML=arr.map(x=>`<div class="v3-list-item"><strong>${escapeHtml(x.title)}</strong><span>${escapeHtml(x.sub)}</span></div>`).join('');
}

async function refreshRadars(){
  if(!state.position)return;
  try{
    const d=await fetchJson(`${API}/radars.php?lat=${state.position.lat}&lon=${state.position.lon}&radius=18000`);
    drawRadars(d.radars||[]);
    setText('alertCount',(d.radars||[]).length+' radares próximos');
  }catch(e){}
}

function drawRadars(radars){
  state.radarLayer.clearLayers();
  for(const r of radars.slice(0,120)){
    const marker=L.circleMarker([Number(r.latitude),Number(r.longitude)],{radius:7,weight:2,color:'#f0cf77',fillColor:'#b3131b',fillOpacity:.95});
    marker.bindPopup(`<strong>Radar ${r.velocidade?escapeHtml(r.velocidade)+' km/h':''}</strong><br>${escapeHtml(r.rodovia||r.cidade||'Fonte oficial')}<br><small>${escapeHtml(r.fonte||'')}</small>`);
    marker.addTo(state.radarLayer);
  }
}

function clearRoute(){
  state.route=null;state.destination=null;
  if(state.routeLine){state.routeLine.remove();state.routeLine=null;}
  if(state.destMarker){state.destMarker.remove();state.destMarker=null;}
  $('destinationInput').value='';setText('routeTitle','Sem destino');
  $('routeMetrics').innerHTML='<span>— km</span><span>— min</span>';
  $('routeSteps').innerHTML='<p>Digite um destino para começar.</p>';
  if(state.position)state.map.flyTo([state.position.lat,state.position.lon],15,{duration:.5});
}

async function loadLibrary(){
  try{const d=await fetchJson(API+'/library.php');state.tracks=d.tracks||[];state.filtered=state.tracks;renderLibrary();}
  catch(e){$('musicList').innerHTML='<p>Não foi possível carregar a biblioteca.</p>';}
}
function renderLibrary(){
  const box=$('musicList');const rows=state.filtered.slice(0,150);
  if(!rows.length){box.innerHTML='<p>Nenhuma música encontrada.</p>';return;}
  box.innerHTML=rows.map((t,i)=>`<div class="v3-music-row" data-track="${escapeHtml(t.id)}"><div><strong>${escapeHtml(t.title||'Sem título')}</strong><span>${escapeHtml(t.artist||t.album||'Estrada Play')}</span></div><b>▶</b></div>`).join('');
  box.querySelectorAll('[data-track]').forEach(el=>el.addEventListener('click',()=>playTrack(el.dataset.track)));
}
function filterMusic(q){q=String(q||'').toLowerCase().trim();state.filtered=q?state.tracks.filter(t=>(`${t.title||''} ${t.artist||''} ${t.album||''}`).toLowerCase().includes(q)):state.tracks;renderLibrary();}
function playTrack(id){
  const t=state.tracks.find(x=>String(x.id)===String(id));if(!t||!t.source)return;
  state.currentTrack=t;const a=$('audio');a.src=t.source;a.play().catch(()=>{});$('playPause').disabled=false;setText('nowTitle',t.title||'Sem título');setText('nowArtist',t.artist||t.album||'Estrada Play');setText('playPause','❚❚');
}
function togglePlay(){const a=$('audio');if(!state.currentTrack)return;if(a.paused){a.play().catch(()=>{});setText('playPause','❚❚');}else{a.pause();setText('playPause','▶');}}
function toggleMusic(open){const p=$('musicPanel');const show=open??!p.classList.contains('open');p.classList.toggle('open',show);p.setAttribute('aria-hidden',show?'false':'true');}

function bind(){
  $('searchForm').addEventListener('submit',async e=>{e.preventDefault();const q=$('destinationInput').value.trim();if(!q)return;try{setText('routeTitle','Buscando destino…');await searchDestination(q);}catch(err){setText('routeTitle',err.message||'Falha ao buscar destino');}});
  $('locateButton').addEventListener('click',locate);
  $('clearRoute').addEventListener('click',clearRoute);
  $('musicButton').addEventListener('click',()=>toggleMusic(true));
  $('closeMusic').addEventListener('click',()=>toggleMusic(false));
  $('musicSearch').addEventListener('input',e=>filterMusic(e.target.value));
  $('playPause').addEventListener('click',togglePlay);
  $('audio').addEventListener('ended',()=>setText('playPause','▶'));
}

function registerSw(){if('serviceWorker'in navigator)navigator.serviceWorker.register('../sw.js',{scope:'../'}).catch(()=>{});}

initMap();bind();startGps();loadLibrary();registerSw();
})();

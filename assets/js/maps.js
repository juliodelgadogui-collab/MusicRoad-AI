const RadaMaps = (() => {
  let map = null, routeLayer = null, radarLayer = null, userMarker = null;
  let watchId = null, radars = [], alerted = new Set(), lastPosition = null;

  function gpsEl(){ return document.getElementById('gpsStatus'); }
  function infoEl(){ return document.getElementById('routeInfo'); }
  function setGpsStatus(text, kind=''){
    const el = gpsEl();
    if (!el) return;
    el.textContent = text;
    el.className = `status-badge ${kind}`.trim();
  }

  function init(){
    if (!document.getElementById('map') || !window.L) return;
    map = L.map('map').setView([-14.235, -51.9253], 4);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {maxZoom:19, attribution:'&copy; OpenStreetMap'}).addTo(map);
    radarLayer = L.layerGroup().addTo(map);
  }

  function markerIcon(type){
    const icons = {RADAR_FIXO:'📸',FISCALIZACAO:'🚓',ACIDENTE:'⚠️',OBRA:'🚧',BURACO:'!',POSTO:'⛽',RESTAURANTE:'🍴',PEDAGIO:'💰'};
    return L.divIcon({html:`<div class="map-pin">${icons[type]||'📸'}</div>`,className:'',iconSize:[30,30]});
  }

  function drawRadars(items, center){
    radars = Array.isArray(items) ? items : [];
    if (!map || !radarLayer || !window.L) return;
    radarLayer.clearLayers();
    radars.forEach(r => L.marker([+r.latitude,+r.longitude],{icon:markerIcon(r.tipo)}).bindPopup(`Radar ${r.velocidade || '--'} km/h<br>${r.rodovia || ''} ${r.km || ''}`).addTo(radarLayer));
    if (center) map.setView(center, 13);
  }

  function drawRoute(route, items){
    if (!route?.geometry?.coordinates) return;
    radars = Array.isArray(items) ? items : [];
    MRDB.put('tripCache',{key:'lastTrip',route,radars,savedAt:Date.now()}).catch(()=>{});
    if (!map || !window.L) return;
    if (routeLayer) map.removeLayer(routeLayer);
    if (radarLayer) radarLayer.clearLayers();
    const latlngs = route.geometry.coordinates.map(c => [c[1], c[0]]);
    routeLayer = L.polyline(latlngs,{weight:5}).addTo(map);
    map.fitBounds(routeLayer.getBounds(),{padding:[30,30]});
    drawRadars(radars);
  }

  function distance(a,b,c,d){
    const R=6371000,toRad=x=>x*Math.PI/180,dLat=toRad(c-a),dLon=toRad(d-b);
    const h=Math.sin(dLat/2)**2+Math.cos(toRad(a))*Math.cos(toRad(c))*Math.sin(dLon/2)**2;
    return R*2*Math.atan2(Math.sqrt(h),Math.sqrt(1-h));
  }

  function updateFromPosition(pos){
    lastPosition = pos;
    const {latitude,longitude,speed,accuracy} = pos.coords;
    setGpsStatus(`GPS ativo · precisão ~${Math.round(accuracy || 0)} m`,'ok');
    const speedEl=document.getElementById('speedNow');
    if (speedEl) speedEl.textContent = Number.isFinite(speed) && speed !== null ? `${Math.round(speed*3.6)} km/h` : '-- km/h';
    if (map && window.L) {
      if (!userMarker) userMarker=L.circleMarker([latitude,longitude],{radius:7,weight:3}).addTo(map);
      else userMarker.setLatLng([latitude,longitude]);
    }
    const nearest = radars.map(r=>({...r,dist:distance(latitude,longitude,+r.latitude,+r.longitude)})).sort((a,b)=>a.dist-b.dist)[0];
    const nextEl=document.getElementById('nextRadar'), homeEl=document.getElementById('homeRadar'), limitEl=document.getElementById('speedLimit');
    if (!nearest) {
      if(nextEl) nextEl.textContent='--'; if(homeEl) homeEl.textContent='--'; if(limitEl) limitEl.textContent='--';
    } else {
      const distText = nearest.dist >= 1000 ? `${(nearest.dist/1000).toFixed(1)} km` : `${Math.round(nearest.dist)} m`;
      if(nextEl) nextEl.textContent=distText; if(homeEl) homeEl.textContent=distText; if(limitEl) limitEl.textContent=nearest.velocidade?`${nearest.velocidade} km/h`:'--';
      const kmh = Number.isFinite(speed) && speed !== null ? speed*3.6 : 0;
      if(speedEl) speedEl.classList.toggle('danger',!!nearest.velocidade && kmh>Number(nearest.velocidade));
      [[1000,'Atenção. Radar a um quilômetro.'],[800,`Radar a 800 metros. Limite informado: ${nearest.velocidade || 'não informado'} quilômetros por hora.`],[300,'Radar próximo. Respeite o limite da via.'],[100,'Radar muito próximo.']].forEach(([m,text])=>{
        const key=`${nearest.id}-${m}`;
        if(nearest.dist<=m && !alerted.has(key)){ alerted.add(key); window.Player?.duckForAlert?.(text); }
      });
    }
    document.dispatchEvent(new CustomEvent('mr:location-update',{detail:{position:pos}}));
  }

  async function permissionState(){
    if (!window.isSecureContext) return 'insecure';
    if (!navigator.geolocation) return 'unsupported';
    try {
      if (!navigator.permissions?.query) return 'unknown';
      const p=await navigator.permissions.query({name:'geolocation'});
      return p.state || 'unknown';
    } catch (_) { return 'unknown'; }
  }

  function explainError(err){
    if (!window.isSecureContext) return 'Localização exige HTTPS.';
    if (err?.code===1) return 'Permissão de localização negada. Libere Localização nas permissões do navegador e do aparelho.';
    if (err?.code===2) return 'Localização indisponível. Ative GPS/localização do aparelho e tente novamente.';
    if (err?.code===3) return 'O GPS demorou para responder. Tente novamente em local aberto ou com Wi-Fi ligado.';
    return err?.message || 'Não foi possível obter a localização.';
  }

  async function locateOnce(){
    if (!window.isSecureContext) throw new Error('Localização exige HTTPS.');
    if (!navigator.geolocation) throw new Error('Este navegador não oferece geolocalização.');
    setGpsStatus('Solicitando localização...','waiting');
    return new Promise((resolve,reject)=>{
      navigator.geolocation.getCurrentPosition(
        pos=>{updateFromPosition(pos);resolve(pos);},
        err=>{const msg=explainError(err);setGpsStatus(err?.code===1?'GPS bloqueado':'GPS indisponível','error');reject(new Error(msg));},
        {enableHighAccuracy:true,maximumAge:30000,timeout:20000}
      );
    });
  }

  function startTracking(){
    if (!window.isSecureContext || !navigator.geolocation || watchId!==null) return;
    watchId=navigator.geolocation.watchPosition(updateFromPosition,err=>{
      if(err?.code===1){setGpsStatus('GPS bloqueado','error');stopTracking(false);} else setGpsStatus('GPS tentando reconectar...','waiting');
    },{enableHighAccuracy:true,maximumAge:5000,timeout:30000});
  }

  function stopTracking(updateLabel=true){
    if(watchId!==null && navigator.geolocation) navigator.geolocation.clearWatch(watchId);
    watchId=null;
    if(updateLabel) setGpsStatus('GPS parado');
  }

  return {init,drawRoute,drawRadars,locateOnce,startTracking,stopTracking,permissionState,getLastPosition:()=>lastPosition};
})();

const VERSION='1.2.0';
const SHELL=`musicroad-shell-${VERSION}`;
const TILES=`musicroad-tiles-${VERSION}`;
const RUNTIME=`musicroad-runtime-${VERSION}`;
const OFFLINE_ASSETS=[
  './offline.html',
  `./manifest.webmanifest?v=${VERSION}`,
  './assets/img-icon.svg','./assets/mr-one.svg','./assets/icon-192.png','./assets/icon-512.png',
  `./assets/css/cockpit.css?v=${VERSION}`,
  `./assets/css/command.css?v=${VERSION}`,
  `./assets/css/aurora.css?v=${VERSION}`,
  `./assets/css/final-v15.css?v=${VERSION}`,
  `./assets/css/one.css?v=${VERSION}`,
  `./assets/css/premium.css?v=${VERSION}`,
  './assets/hero-road.jpg',
  `./assets/css/login-v15.css?v=${VERSION}`,
  `./assets/css/admin-v15.css?v=${VERSION}`,
  `./assets/css/premium-admin.css?v=${VERSION}`,
  `./assets/js/cockpit-player.js?v=${VERSION}`,
  `./assets/js/cockpit.js?v=${VERSION}`,
  `./assets/js/mapbox-base.js?v=${VERSION}`,
  `./horizontal/assets/auto.css?v=${VERSION}`,
  `./horizontal/assets/driveos.css?v=${VERSION}`,
  `./horizontal/assets/premium-driveos.css?v=${VERSION}`,
  `./horizontal/assets/auto.js?v=${VERSION}`,
  './assets/vendor/leaflet/leaflet.css',
  './assets/vendor/leaflet/leaflet.js'
];
self.addEventListener('install',event=>{
  event.waitUntil(caches.open(SHELL).then(c=>Promise.allSettled(OFFLINE_ASSETS.map(u=>c.add(u)))).catch(()=>{}));
  self.skipWaiting();
});
self.addEventListener('activate',event=>{
  event.waitUntil((async()=>{
    const keep=new Set([SHELL,TILES,RUNTIME]);
    const keys=await caches.keys();
    await Promise.all(keys.filter(k=>k.startsWith('musicroad-')&&!keep.has(k)&&!k.startsWith('musicroad-state-offline-')&&!k.startsWith('musicroad-city-offline-')&&!k.startsWith('musicroad-light-map-')&&!k.startsWith('musicroad-state-map-device-')).map(k=>caches.delete(k)));
    await self.clients.claim();
  })());
});
async function trim(cacheName,max){
  const cache=await caches.open(cacheName);const keys=await cache.keys();
  if(keys.length<=max)return;
  await Promise.all(keys.slice(0,keys.length-max).map(k=>cache.delete(k)));
}
async function navigation(req){
  try{
    return await fetch(req,{cache:'no-store'});
  }catch(e){
    return (await caches.match('./offline.html'))||Response.error();
  }
}
async function networkWithCache(req,cacheName){
  const cache=await caches.open(cacheName);
  try{const response=await fetch(req,{cache:'no-store'});if(response&&response.ok)await cache.put(req,response.clone());return response;}
  catch(e){const cached=await cache.match(req);if(cached)return cached;throw e;}
}
async function tile(req){
  const cache=await caches.open(TILES);const cached=await cache.match(req);
  if(cached){fetch(req).then(r=>{if(r&&r.ok){cache.put(req,r.clone());trim(TILES,650);}}).catch(()=>{});return cached;}
  const response=await fetch(req);if(response&&response.ok){await cache.put(req,response.clone());trim(TILES,650);}return response;
}
self.addEventListener('fetch',event=>{
  const req=event.request;if(req.method!=='GET')return;
  const url=new URL(req.url);
  if(req.mode==='navigate'){event.respondWith(navigation(req));return;}
  if(req.headers.has('range')||req.destination==='audio'||url.pathname.includes('/api/')){event.respondWith(fetch(req,{cache:'no-store'}));return;}
  if(url.hostname.endsWith('tile.openstreetmap.org')){event.respondWith(tile(req));return;}
  if(url.origin===self.location.origin&&/\.(?:js|css|png|jpe?g|webp|svg|webmanifest)$/i.test(url.pathname)){event.respondWith(networkWithCache(req,SHELL));return;}
  if(url.origin===self.location.origin){event.respondWith(networkWithCache(req,RUNTIME));}
});

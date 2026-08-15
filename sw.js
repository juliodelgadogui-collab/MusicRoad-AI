const CACHE='musicroad-ai-v1';
const OFFLINE_ASSETS=['./offline.html','./manifest.webmanifest?v=1.0.0','./assets/img-icon.svg','./assets/icon-192.png','./assets/icon-512.png'];
self.addEventListener('install',event=>{event.waitUntil(caches.open(CACHE).then(c=>c.addAll(OFFLINE_ASSETS)));self.skipWaiting();});
self.addEventListener('activate',event=>{event.waitUntil((async()=>{const keys=await caches.keys();await Promise.all(keys.filter(k=>k.startsWith('musicroad-ai-')&&k!==CACHE).map(k=>caches.delete(k)));await self.clients.claim();})());});
self.addEventListener('fetch',event=>{
  const req=event.request;if(req.method!=='GET')return;const url=new URL(req.url);
  if(req.headers.has('range')||req.destination==='audio'||url.pathname.includes('/api/')){event.respondWith(fetch(req,{cache:'no-store'}));return;}
  if(req.mode==='navigate'){event.respondWith(fetch(req,{cache:'no-store'}).catch(()=>caches.match('./offline.html')));return;}
  if(url.origin===self.location.origin&&/\.(?:js|css|php)$/i.test(url.pathname)){event.respondWith(fetch(req,{cache:'no-store'}));return;}
  if(url.origin===self.location.origin)event.respondWith(fetch(req).catch(()=>caches.match(req)));
});

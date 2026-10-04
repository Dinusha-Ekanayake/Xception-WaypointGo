import {readdirSync,readFileSync,writeFileSync} from 'node:fs';import path from 'node:path';
// Background Sync (issue #28): on 'waypoint-drain' an open page drains its own
// queue (DRAIN_TAG, DRAIN_MESSAGE in src/shared/offline/queue.ts). With no page
// open the worker drains the queues itself with scripts/sw-drain.mjs, inlined
// here; a loader queue still waits for a page (see that file).
// Push (issue #118): a push is shown as a notification with the server's title
// and body; tapping it focuses the app, or opens it.
// Installed apps (issue #201): icons, fonts, images and map tiles are kept at
// run time by the rules in scripts/sw-cache.mjs, inlined here too.
const inline=f=>readFileSync(new URL(f,import.meta.url),'utf8').replace(/^export /gm,'');
const drainSource=inline('./sw-drain.mjs');const cacheSource=inline('./sw-cache.mjs');
function walk(dir){return readdirSync(dir,{withFileTypes:true}).flatMap(e=>e.isDirectory()?walk(path.join(dir,e.name)):[path.join(dir,e.name)])}
// URL paths always use '/': path.join gives '\' on Windows, and an asset list of
// '/.next\static\...' made every precache request 404, so the worker never installed.
const assets=walk('.next/static').filter(p=>!p.endsWith('.map')).map(p=>'/_next/'+path.relative('.next',p).split(path.sep).join('/'));
const version=readFileSync('.next/BUILD_ID','utf8').trim();
writeFileSync('public/sw.js',`const CACHE=${JSON.stringify('waypoint-'+version)};const ASSETS=${JSON.stringify(['/','/assets/chilled.svg','/assets/error_truck_light.webp','/assets/error_truck_dark.webp',...assets])};
self.addEventListener('install',e=>e.waitUntil(caches.open(CACHE).then(c=>c.addAll(ASSETS)).then(()=>self.skipWaiting())));
self.addEventListener('activate',e=>e.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(k=>k.startsWith('waypoint-')&&k!==CACHE&&!keepsAcrossBuilds(k)).map(k=>caches.delete(k)))).then(()=>self.clients.claim())));
self.addEventListener('fetch',e=>{const u=new URL(e.request.url);if(e.request.method!=='GET'||u.origin!==self.location.origin)return;const net=r=>fetch(r);switch(strategyFor(u.pathname,e.request.mode==='navigate',ASSETS.includes(u.pathname))){
case 'navigate':e.respondWith(fetch(e.request).catch(()=>caches.match('/')));return;
case 'precache':e.respondWith(caches.match(e.request).then(r=>r||fetch(e.request)));return;
case 'address':e.respondWith(networkFirst(caches,STATIC_CACHE,e.request,net));return;
case 'static':e.respondWith(cacheFirst(caches,STATIC_CACHE,STATIC_LIMIT,e.request,net));return;
case 'tile':e.respondWith(cacheFirst(caches,TILE_CACHE,TILE_LIMIT,e.request,net));return;}});
self.addEventListener('push',e=>{let d={};try{d=e.data?e.data.json():{}}catch(x){d={title:'Waypoint',body:e.data?e.data.text():''}}e.waitUntil(self.registration.showNotification(d.title||'Waypoint',{body:d.body||'',tag:d.notificationId||undefined,data:{notificationId:d.notificationId||null,subjectType:d.subjectType||null,subjectId:d.subjectId||null}}));});
self.addEventListener('notificationclick',e=>{e.notification.close();e.waitUntil(self.clients.matchAll({type:'window',includeUncontrolled:true}).then(list=>{const open=list.find(c=>new URL(c.url).origin===self.location.origin);if(open){open.postMessage({type:'waypoint:notification',data:e.notification.data});return open.focus()}return self.clients.openWindow('/')}));});
self.addEventListener('sync',e=>{if(e.tag!=='waypoint-drain')return;e.waitUntil(self.clients.matchAll({type:'window'}).then(list=>{if(!list.length)return drainWithoutPage(self.indexedDB,self.fetch.bind(self));list.forEach(c=>c.postMessage({type:'waypoint:drain'}));}));});
${cacheSource}
${drainSource}`);
console.log('Offline shell: '+assets.length+' immutable assets.');

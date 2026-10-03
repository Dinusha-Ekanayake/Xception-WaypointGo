import {readdirSync,readFileSync,writeFileSync} from 'node:fs';import path from 'node:path';
// Background Sync (issue #28): on 'waypoint-drain' an open page drains its own
// queue (DRAIN_TAG, DRAIN_MESSAGE in src/shared/offline/queue.ts). With no page
// open the worker drains the queues itself with scripts/sw-drain.mjs, inlined
// here; a loader queue still waits for a page (see that file).
const drainSource=readFileSync(new URL('./sw-drain.mjs',import.meta.url),'utf8').replace(/^export /gm,'');
function walk(dir){return readdirSync(dir,{withFileTypes:true}).flatMap(e=>e.isDirectory()?walk(path.join(dir,e.name)):[path.join(dir,e.name)])}
// URL paths always use '/': path.join gives '\' on Windows, and an asset list of
// '/.next\static\...' made every precache request 404, so the worker never installed.
const assets=walk('.next/static').filter(p=>!p.endsWith('.map')).map(p=>'/_next/'+path.relative('.next',p).split(path.sep).join('/'));
const version=readFileSync('.next/BUILD_ID','utf8').trim();
writeFileSync('public/sw.js',`const CACHE=${JSON.stringify('waypoint-'+version)};const ASSETS=${JSON.stringify(['/', '/manifest.json','/assets/chilled.svg',...assets])};
self.addEventListener('install',e=>e.waitUntil(caches.open(CACHE).then(c=>c.addAll(ASSETS)).then(()=>self.skipWaiting())));
self.addEventListener('activate',e=>e.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(k=>k.startsWith('waypoint-')&&k!==CACHE).map(k=>caches.delete(k)))).then(()=>self.clients.claim())));
self.addEventListener('fetch',e=>{const u=new URL(e.request.url);if(e.request.method!=='GET'||u.origin!==self.location.origin||['/api/','/oauth/','/.well-known/','/mcp'].some(p=>u.pathname.startsWith(p)))return;if(e.request.mode==='navigate'){e.respondWith(fetch(e.request).catch(()=>caches.match('/')));return}if(ASSETS.includes(u.pathname))e.respondWith(caches.match(e.request).then(r=>r||fetch(e.request)));});
self.addEventListener('sync',e=>{if(e.tag!=='waypoint-drain')return;e.waitUntil(self.clients.matchAll({type:'window'}).then(list=>{if(!list.length)return drainWithoutPage(self.indexedDB,self.fetch.bind(self));list.forEach(c=>c.postMessage({type:'waypoint:drain'}));}));});
${drainSource}`);
console.log('Offline shell: '+assets.length+' immutable assets.');

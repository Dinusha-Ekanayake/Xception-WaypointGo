import {readdirSync,readFileSync,writeFileSync} from 'node:fs';import path from 'node:path';
function walk(dir){return readdirSync(dir,{withFileTypes:true}).flatMap(e=>e.isDirectory()?walk(path.join(dir,e.name)):[path.join(dir,e.name)])}
const assets=walk('.next/static').filter(p=>!p.endsWith('.map')).map(p=>'/'+p.replace('.next/','_next/'));
const version=readFileSync('.next/BUILD_ID','utf8').trim();
writeFileSync('public/sw.js',`const CACHE=${JSON.stringify('waypoint-'+version)};const ASSETS=${JSON.stringify(['/', '/manifest.json','/assets/chilled.svg',...assets])};
self.addEventListener('install',e=>e.waitUntil(caches.open(CACHE).then(c=>c.addAll(ASSETS)).then(()=>self.skipWaiting())));
self.addEventListener('activate',e=>e.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(k=>k.startsWith('waypoint-')&&k!==CACHE).map(k=>caches.delete(k)))).then(()=>self.clients.claim())));
self.addEventListener('fetch',e=>{const u=new URL(e.request.url);if(e.request.method!=='GET'||u.origin!==self.location.origin||u.pathname.startsWith('/api/'))return;if(e.request.mode==='navigate'){e.respondWith(fetch(e.request).catch(()=>caches.match('/')));return}if(ASSETS.includes(u.pathname))e.respondWith(caches.match(e.request).then(r=>r||fetch(e.request)));});`);
console.log('Offline shell: '+assets.length+' immutable assets.');

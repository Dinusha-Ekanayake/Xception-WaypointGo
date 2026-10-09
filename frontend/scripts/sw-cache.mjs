// What the service worker keeps for an installed app with no coverage (issue
// #201). scripts/build-sw.mjs inlines this file into public/sw.js;
// tests/sw-cache.test.ts imports it.
//
//   - the shell and every build asset are precached under the build's cache;
//   - icons, fonts and images are kept the first time they are shown, and
//     survive a new build, since their names do not change with it;
//   - map tiles are kept as the map shows them, up to a cap, oldest first out,
//     so a run's map still draws in a valley with no signal;
//   - the manifest and home screen icon follow the address, so the network
//     answers first and the kept copy only stands in when it is down;
//   - nothing under /api is ever kept here. Offline reads are the role's kept
//     snapshots (src/shared/offline/snapshots.ts), which are per account and
//     cleared on sign-out; a shared cache would serve one person's data to the
//     next.

export const STATIC_CACHE = "waypoint-static-1";
export const TILE_CACHE = "waypoint-tiles-1";
export const STATIC_LIMIT = 300;
export const TILE_LIMIT = 1500;

const NEVER = ["/api/", "/oauth/", "/.well-known/", "/mcp"];
const STATIC = ["/icons/", "/fonts/", "/assets/"];
const FOLLOWS_ADDRESS = ["/manifest.webmanifest", "/apple-touch-icon.png"];

/**
 * How a same-origin GET is answered: `skip` leaves it to the network,
 * `navigate` is the page, `precache` a build asset, `address` network first,
 * `static` and `tile` cache first into their own caches.
 */
export function strategyFor(pathname, navigate, precached) {
  if (NEVER.some((p) => pathname.startsWith(p))) return "skip";
  if (navigate) return "navigate";
  if (precached) return "precache";
  if (FOLLOWS_ADDRESS.includes(pathname)) return "address";
  if (pathname.startsWith("/map-tiles/")) return "tile";
  if (STATIC.some((p) => pathname.startsWith(p))) return "static";
  return "skip";
}

/** True for the caches a new build keeps; every other `waypoint-` cache is the old build's. */
export function keepsAcrossBuilds(name) {
  return name === STATIC_CACHE || name === TILE_CACHE;
}

/** Only a whole, same-origin answer is kept; an error or a partial file never is. */
function keepable(response) {
  return response.ok && response.status === 200 && response.type === "basic";
}

/** Drops the oldest entries past the cap. Cache keys come back in the order they were put. */
export async function trim(cache, limit) {
  const keys = await cache.keys();
  for (const key of keys.slice(0, Math.max(0, keys.length - limit))) await cache.delete(key);
}

export async function cacheFirst(caches, name, limit, request, fetcher) {
  const cache = await caches.open(name);
  const kept = await cache.match(request);
  if (kept) return kept;
  const response = await fetcher(request);
  if (keepable(response)) {
    await cache.put(request, response.clone());
    await trim(cache, limit);
  }
  return response;
}

export async function networkFirst(caches, name, request, fetcher) {
  const cache = await caches.open(name);
  try {
    const response = await fetcher(request);
    if (keepable(response)) await cache.put(request, response.clone());
    return response;
  } catch (error) {
    const kept = await cache.match(request);
    if (kept) return kept;
    throw error;
  }
}

// Roadmap F-05.

/**
 * Precaches the build. The shell is required: without it the app cannot open
 * offline, so its failure fails the install and the browser tries again later.
 * Any other file that fails is left out, to be fetched when first needed,
 * rather than one bad file blocking the whole install. Returns how many failed.
 */
export async function precacheAll(cache, assets, required) {
  await cache.add(required);
  const results = await Promise.allSettled(assets.filter((a) => a !== required).map((a) => cache.add(a)));
  return results.filter((r) => r.status === "rejected").length;
}

/** How long opening the app waits for the network before the kept shell opens instead. */
export const NAVIGATE_WAIT_MS = 6000;

/**
 * The network's page when it answers within `ms`, else the kept shell. A weak
 * signal in the hills can hold a request open for minutes; the driver should not
 * stare at a blank screen meanwhile. With nothing kept, waits for the network.
 */
export function navigateWithin(network, kept, ms = NAVIGATE_WAIT_MS) {
  return new Promise((resolve, reject) => {
    let done = false;
    const timer = setTimeout(async () => {
      const shell = await kept();
      if (shell && !done) {
        done = true;
        resolve(shell);
      }
    }, ms);
    network.then(
      (response) => {
        if (done) return;
        done = true;
        clearTimeout(timer);
        resolve(response);
      },
      async (error) => {
        clearTimeout(timer);
        if (done) return;
        const shell = await kept();
        done = true;
        if (shell) resolve(shell);
        else reject(error);
      },
    );
  });
}

/** How long Background Sync waits for an open page to report its drain. */
export const DRAIN_WAIT_MS = 60000;

/** Asks one page to send its queue and resolves with whether nothing it can send is left. No answer is false. */
export function askToDrain(client, ms = DRAIN_WAIT_MS, Channel = MessageChannel) {
  return new Promise((resolve) => {
    const channel = new Channel();
    const timer = setTimeout(() => resolve(false), ms);
    channel.port1.onmessage = (event) => {
      clearTimeout(timer);
      resolve(Boolean(event.data && event.data.done));
    };
    client.postMessage({ type: "waypoint:drain" }, [channel.port2]);
  });
}

/**
 * Background Sync with pages open: every page drains its own queue, and the
 * sync event settles only with their results. Work left over rejects, so the
 * browser tries the sync again later instead of thinking it is done.
 */
export async function drainThroughPages(clients, ms = DRAIN_WAIT_MS, Channel = MessageChannel) {
  const done = await Promise.all(clients.map((client) => askToDrain(client, ms, Channel)));
  if (!done.every(Boolean)) throw new Error("work is still waiting to be sent");
}

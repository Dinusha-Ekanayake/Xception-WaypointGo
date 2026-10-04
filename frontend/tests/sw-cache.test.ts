import assert from "node:assert/strict";
import test from "node:test";
import { STATIC_CACHE, TILE_CACHE, cacheFirst, keepsAcrossBuilds, networkFirst, strategyFor, trim } from "../scripts/sw-cache.mjs";

// What an installed app keeps for use with no coverage (issue #201).

test("API, sign-in and assistant traffic is never kept by the worker", () => {
  for (const path of ["/api/driver/run-sheets", "/oauth/authorize", "/.well-known/oauth-protected-resource", "/mcp"]) {
    assert.equal(strategyFor(path, false, false), "skip", path);
    assert.equal(strategyFor(path, true, false), "skip", path);
  }
});

test("each kind of request has its own rule", () => {
  assert.equal(strategyFor("/", true, true), "navigate");
  assert.equal(strategyFor("/_next/static/chunks/main.js", false, true), "precache");
  assert.equal(strategyFor("/manifest.webmanifest", false, false), "address");
  assert.equal(strategyFor("/apple-touch-icon.png", false, false), "address");
  assert.equal(strategyFor("/map-tiles/12/2953/1914", false, false), "tile");
  assert.equal(strategyFor("/icons/go/truck.svg", false, false), "static");
  assert.equal(strategyFor("/fonts/UN-Malithi-4.ttf", false, false), "static");
  assert.equal(strategyFor("/assets/proof-sample.png", false, false), "static");
  assert.equal(strategyFor("/healthz", false, false), "skip");
});

test("kept icons and tiles survive a new build; the old build's shell does not", () => {
  assert.equal(keepsAcrossBuilds(STATIC_CACHE), true);
  assert.equal(keepsAcrossBuilds(TILE_CACHE), true);
  assert.equal(keepsAcrossBuilds("waypoint-OldBuildId"), false);
});

/** A Cache Storage stand-in keyed by URL, keeping insertion order like the real one. */
function fakeCaches() {
  const stores = new Map<string, Map<string, Response>>();
  return {
    stores,
    open: async (name: string) => {
      const store = stores.get(name) ?? new Map<string, Response>();
      stores.set(name, store);
      return {
        match: async (r: Request) => store.get(r.url)?.clone(),
        put: async (r: Request, res: Response) => void store.set(r.url, res),
        keys: async () => [...store.keys()].map((u) => new Request(u)),
        delete: async (r: Request) => store.delete(r.url),
      };
    },
  };
}

/** A same-origin answer; `new Response` reports type "default", the worker sees "basic". */
const basic = (body: string, status = 200) => Object.defineProperty(new Response(body, { status }), "type", { value: "basic" });

test("a tile is fetched once, then served from the cache with no network", async () => {
  const caches = fakeCaches();
  let calls = 0;
  const tile = new Request("https://driver.example.live/map-tiles/12/1/1");
  await cacheFirst(caches, TILE_CACHE, 10, tile, async () => (calls++, basic("png")));
  const offline = await cacheFirst(caches, TILE_CACHE, 10, tile, async () => { throw new TypeError("offline"); });
  assert.equal(calls, 1);
  assert.equal(await offline.text(), "png");
});

test("a failed answer is passed on but never kept", async () => {
  const caches = fakeCaches();
  const tile = new Request("https://driver.example.live/map-tiles/12/1/2");
  const res = await cacheFirst(caches, TILE_CACHE, 10, tile, async () => basic("", 502));
  assert.equal(res.status, 502);
  assert.equal(caches.stores.get(TILE_CACHE)?.size, 0);
});

test("past the cap the oldest entries go first", async () => {
  const caches = fakeCaches();
  const cache = await caches.open(TILE_CACHE);
  for (let i = 0; i < 5; i++) await cache.put(new Request(`https://x.live/map-tiles/1/1/${i}`), basic("t"));
  await trim(cache, 3);
  assert.deepEqual([...caches.stores.get(TILE_CACHE)!.keys()].map((u) => u.at(-1)), ["2", "3", "4"]);
});

test("the manifest comes from the network, and from the cache when it is down", async () => {
  const caches = fakeCaches();
  const manifest = new Request("https://store.example.live/manifest.webmanifest");
  assert.equal(await (await networkFirst(caches, STATIC_CACHE, manifest, async () => basic("v1"))).text(), "v1");
  assert.equal(await (await networkFirst(caches, STATIC_CACHE, manifest, async () => basic("v2"))).text(), "v2");
  const down = await networkFirst(caches, STATIC_CACHE, manifest, async () => { throw new TypeError("offline"); });
  assert.equal(await down.text(), "v2");
  await assert.rejects(networkFirst(caches, STATIC_CACHE, new Request("https://store.example.live/apple-touch-icon.png"), async () => { throw new TypeError("offline"); }));
});

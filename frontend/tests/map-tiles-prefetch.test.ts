import assert from "node:assert/strict";
import test from "node:test";
import { tileAllowed, tilesFor } from "../src/shared/ui/map/geo.ts";
import { TILE_LIMIT } from "../scripts/sw-cache.mjs";

// The tiles a driver's phone keeps for the run's map (issue #201).

const KANDY = { lat: 7.2906, lon: 80.6337 };
const PERADENIYA = { lat: 7.2599, lon: 80.5977 };

test("no stops with a location, nothing to keep", () => {
  assert.deepEqual(tilesFor([]), []);
});

test("the overview and the streets around each stop, all inside the map's own box", () => {
  const urls = tilesFor([KANDY, PERADENIYA]);
  assert.ok(urls.length > 20);
  const zooms = new Set(urls.map((u) => Number(u.split("/")[2])));
  for (const z of [9, 13, 14, 15]) assert.ok(zooms.has(z), `zoom ${z}`);
  for (const url of urls) {
    const [z, x, y] = url.replace(/^\/map-tiles\//, "").replace(/\.png$/, "").split("/").map(Number);
    assert.ok(tileAllowed(z!, x!, y!), url);
  }
  assert.equal(new Set(urls).size, urls.length, "each tile once");
});

test("never more than the cap, which stays well under the worker's tile cache", () => {
  const island = [{ lat: 6.0, lon: 80.0 }, { lat: 9.7, lon: 81.9 }];
  assert.equal(tilesFor(island, 600).length, 600);
  assert.ok(600 < TILE_LIMIT / 2);
});

test("a spread-out run keeps the streets around every stop before the overview", () => {
  const stops = Array.from({ length: 20 }, (_, i) => ({ lat: 6.93 + (i * (7.29 - 6.93)) / 19, lon: 79.86 + (i * (80.63 - 79.86)) / 19 }));
  const urls = tilesFor(stops);
  const at = (z: number) => urls.filter((u) => u.startsWith(`/map-tiles/${z}/`)).length;
  assert.ok(urls.length <= 600);
  for (const p of stops) {
    const z = 15;
    const x = Math.floor(((p.lon + 180) / 360) * 2 ** z);
    assert.ok(urls.some((u) => u.startsWith(`/map-tiles/${z}/${x}/`)), `stop ${p.lat},${p.lon} at zoom 15`);
  }
  assert.ok(at(8) > 0, "the map's opening view is kept");
});

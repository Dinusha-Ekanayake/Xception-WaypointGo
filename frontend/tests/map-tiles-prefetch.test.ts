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

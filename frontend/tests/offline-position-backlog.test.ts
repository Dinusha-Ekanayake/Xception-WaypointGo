import assert from "node:assert/strict";
import { test } from "node:test";
import type { StoredEntry } from "../src/shared/offline/store.ts";
import { MAX_POINTS, POSITION_KIND, extendBacklog } from "../src/shared/offline/lanes.ts";

// Roadmap F-08: with no signal, fixes join the last batch still waiting.

const point = (i: number) => ({ recordedAt: `2026-10-07T04:00:${String(i).padStart(2, "0")}Z`, latitude: 7.29, longitude: 80.63 });
const batch = (n: number, trip = "t-1") => ({ kind: POSITION_KIND, payload: { kind: POSITION_KIND, payload: { vehicleId: "VEH043", tripId: trip, points: Array.from({ length: n }, (_, i) => point(i)) } } });
const entry = (n: number, extra: Partial<StoredEntry> = {}): StoredEntry => ({
  commandId: "c-1", kind: POSITION_KIND, payload: batch(n).payload, enqueuedAt: "2026-10-07T04:00:00Z", order: 1, attempts: 0, ...extra,
});
const pointsOf = (e: StoredEntry) => ((e.payload as { payload: { points: unknown[] } }).payload.points).length;

test("a new batch joins the last one never tried, keeping its id and place", () => {
  const extended = extendBacklog([entry(2)], batch(2));
  assert.ok(extended);
  assert.equal(extended.commandId, "c-1");
  assert.equal(pointsOf(extended), 4);
});

test("a batch that may have reached the server, another trip, a full batch or a business write stand alone", () => {
  assert.equal(extendBacklog([entry(2, { attempts: 1 })], batch(2)), null);
  assert.equal(extendBacklog([entry(2)], batch(2, "t-2")), null);
  assert.equal(extendBacklog([entry(MAX_POINTS - 1)], batch(2)), null);
  assert.equal(extendBacklog([entry(2)], { kind: "delivery:Record", payload: {} }), null);
  assert.equal(extendBacklog([], batch(2)), null);
});

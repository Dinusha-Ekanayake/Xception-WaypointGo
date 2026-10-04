import test from "node:test";
import assert from "node:assert/strict";
import { bearing, cluster, compass, metres, SAMPLE_MS, takeSample, tileAllowed, travelBearing } from "../src/shared/ui/map/geo.ts";
import { serveTile } from "../src/app-shell/mapTiles.ts";
import { mapStatus, vehicleDay } from "../src/roles/dispatcher/data/live.ts";
import { batches, toPoint } from "../src/roles/driver/data/points.ts";
import type { RunSheetStopView } from "../src/shared/domain/types.ts";

// Issue #161: the pure halves of the live map.

test("the tile proxy serves Sri Lanka at zoom 5-17 and nothing else", () => {
  // Colombo at zoom 12 is tile 2956, 1968.
  assert.equal(tileAllowed(12, 2956, 1968), true);
  assert.equal(tileAllowed(12, 0, 0), false, "outside the island");
  assert.equal(tileAllowed(4, 11, 7), false, "too far out");
  assert.equal(tileAllowed(18, 96132, 63168), false, "too far in");
  assert.equal(tileAllowed(12.5, 2956, 1968), false, "not an integer");
});

test("a missing tile is never cached by the browser", async () => {
  const unset = await serveTile("12", "2956", "1968", "");
  assert.equal(unset.status, 404);
  assert.equal(unset.headers.get("cache-control"), "no-store");
  const outside = await serveTile("12", "0", "0", "https://tiles.invalid/{z}/{x}/{y}.png");
  assert.equal(outside.status, 404);
  assert.equal(outside.headers.get("cache-control"), "no-store");
});

test("vehicles closer than the radius cluster; distant ones stay apart", () => {
  const groups = cluster([
    { id: "a", x: 10, y: 10 },
    { id: "b", x: 20, y: 30 },
    { id: "c", x: 400, y: 400 },
  ]);
  assert.deepEqual(groups.map((g) => g.map((p) => p.id)), [["a", "b"], ["c"]]);
});

test("R-EXE-23: each five-second tick keeps the newest fix once, and never a stale one", () => {
  assert.equal(SAMPLE_MS, 5_000);
  const now = 100_000;
  assert.equal(takeSample(null, now - 1_000, now), true, "the first fix");
  assert.equal(takeSample(now - 1_000, now - 1_000, now), false, "the same fix again is a duplicate");
  assert.equal(takeSample(now - 6_000, now - 1_000, now), true, "a newer fix, still standing still or not");
  assert.equal(takeSample(now - 30_000, now - 20_000, now), false, "the phone lost its signal 20 s ago");
  assert.ok(Math.abs(metres({ lat: 6.9, lon: 79.86 }, { lat: 6.902, lon: 79.86 }) - 222) < 2);
});

test("R-EXE-22: the direction of travel is from the last real movement, never from jitter", () => {
  const a = { lat: 7, lon: 80 };
  assert.ok(Math.abs(bearing(a, { lat: 7, lon: 80.001 }) - 90) < 0.2, "east");
  assert.ok(Math.abs(bearing(a, { lat: 6.999, lon: 80 }) - 180) < 0.2, "south");
  assert.ok(Math.abs(bearing(a, { lat: 7, lon: 79.999 }) - 270) < 0.2, "west");
  assert.equal(travelBearing([a, { lat: 7.00005, lon: 80 }]), null, "5 m is jitter");
  const east = travelBearing([{ lat: 7, lon: 79.999 }, a, { lat: 7.00002, lon: 80 }]);
  assert.ok(east !== null && Math.abs(east - 90) < 2, "parked after driving east still faces east");
  assert.equal(travelBearing([]), null);
});

test("headings name the eight compass directions", () => {
  assert.equal(compass(null), "N");
  assert.equal(compass(44), "NE");
  assert.equal(compass(181), "S");
  assert.equal(compass(-90), "W");
  assert.equal(compass(350), "N");
});

test("points are rounded as the server accepts and batched by 100", () => {
  const p = toPoint({ latitude: 6.123456789, longitude: 79.987654321, accuracy: 12.34, heading: 359.99, speed: 10 }, Date.UTC(2026, 9, 3));
  assert.deepEqual(p, { recordedAt: "2026-10-03T00:00:00.000Z", latitude: 6.123457, longitude: 79.987654, accuracyM: 12.3, headingDeg: 359.9, speedKmh: 36 });
  assert.deepEqual(batches(Array.from({ length: 230 }, () => p)).map((b) => b.length), [100, 100, 30]);
});

function stop(over: Partial<RunSheetStopView>): RunSheetStopView {
  return {
    deliveryId: "d", tripId: "t", sequence: 1, orderId: "o", outletId: "OUT001", itemCount: 1, mallOutlet: false,
    plannedArrival: "05:20:00", windowOpen: "05:00:00", windowClose: "07:30:00", expectedArrival: null, startedAt: null,
    arrivedAt: null, completedAt: null, waitMinutes: null, lateMinutes: null, outcome: "PENDING", deliveredUnits: null, proofCaptured: false,
    rowVersion: 1, lines: [], ...over,
  };
}

test("map status: offline wins, finished is returning, recorded late is late, a closing window is at risk", () => {
  const date = "2026-10-03";
  const early = new Date("2026-10-03T05:30:00+05:30");
  const day = (stops: RunSheetStopView[]) => vehicleDay({ vehicleId: "VEH001", serviceDate: date, stops }, early);
  assert.equal(mapStatus(date, day([stop({})]), { offline: true }, early), "offline");
  assert.equal(mapStatus(date, day([stop({ outcome: "DELIVERED", arrivedAt: "x", completedAt: "x" })]), { offline: false }, early), "returning");
  assert.equal(mapStatus(date, day([stop({ outcome: "DELIVERED", lateMinutes: 5 }), stop({ deliveryId: "e" })]), null, early), "late");
  assert.equal(mapStatus(date, day([stop({ expectedArrival: "2026-10-03T07:45:00+05:30" })]), null, early), "at-risk");
  assert.equal(mapStatus(date, day([stop({})]), null, new Date("2026-10-03T08:00:00+05:30")), "at-risk");
  assert.equal(mapStatus(date, day([stop({})]), null, early), "on-time");
});

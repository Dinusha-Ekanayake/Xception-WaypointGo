import test from "node:test";
import assert from "node:assert/strict";
import { BACK_AT_DEPOT_M, backAtDepot, recordingTrip, RETURN_LIMIT_MS, returning, type RecordingStop } from "../src/roles/driver/data/recording.ts";

// R-EXE-23: the phone records from Start run until the vehicle is back at its depot.

const depot = { lat: 6.9725, lon: 79.9005 };
const away = { lat: 6.93, lon: 79.86 };
const now = Date.parse("2026-10-05T08:00:00Z");

function stop(over: Partial<RecordingStop>): RecordingStop {
  return { tripId: "T1", outcome: "PENDING", startedAt: null, completedAt: null, ...over };
}

const none = new Set<string>();

test("nothing is recorded before the driver starts the trip", () => {
  assert.equal(recordingTrip([stop({}), stop({})], { here: depot, depot, ended: none, now }), null);
  assert.equal(recordingTrip([], { here: null, depot, ended: none, now }), null);
});

test("Start run begins recording, and every stop of the trip keeps it going", () => {
  const started = [stop({ startedAt: "2026-10-05T05:00:00Z" }), stop({})];
  assert.equal(recordingTrip(started, { here: depot, depot, ended: none, now }), "T1", "even while still at the depot gate");
  const arrived = [stop({ outcome: "ARRIVED" }), stop({})];
  assert.equal(recordingTrip(arrived, { here: away, depot, ended: none, now }), "T1");
});

test("after the last stop the drive back is recorded, until the vehicle is within 200 m of the depot", () => {
  const done = [
    stop({ outcome: "DELIVERED", startedAt: "2026-10-05T05:00:00Z", completedAt: "2026-10-05T07:00:00Z" }),
    stop({ outcome: "FAILED", completedAt: "2026-10-05T07:30:00Z" }),
  ];
  assert.equal(returning(done, "T1"), true);
  assert.equal(recordingTrip(done, { here: away, depot, ended: none, now }), "T1", "driving back");
  assert.equal(recordingTrip(done, { here: null, depot, ended: none, now }), "T1", "no fix yet");
  const gate = { lat: depot.lat + 0.0015, lon: depot.lon };
  assert.ok(backAtDepot(gate, depot), `about 167 m is inside ${BACK_AT_DEPOT_M} m`);
  assert.equal(recordingTrip(done, { here: gate, depot, ended: none, now }), null, "back at the depot");
  assert.equal(recordingTrip(done, { here: away, depot: null, ended: none, now }), "T1", "depot unknown: the time limit ends it");
  assert.equal(recordingTrip(done, { here: away, depot, ended: new Set(["T1"]), now }), null, "already back once: a reload does not restart it");
  const later = Date.parse("2026-10-05T07:30:00Z") + RETURN_LIMIT_MS + 1;
  assert.equal(recordingTrip(done, { here: away, depot, ended: none, now: later }), null, "a phone left in the cab stops");
});

test("starting the next trip ends the one before", () => {
  const stops = [
    stop({ outcome: "DELIVERED", startedAt: "2026-10-05T05:00:00Z", completedAt: "2026-10-05T06:00:00Z" }),
    stop({ tripId: "T2", startedAt: "2026-10-05T09:00:00Z" }),
    stop({ tripId: "T2" }),
  ];
  assert.equal(recordingTrip(stops, { here: away, depot, ended: new Set(["T1"]), now }), "T2");
  assert.equal(returning(stops, "T2"), false);
  assert.equal(returning(stops, null), false);
});

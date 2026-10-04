import assert from "node:assert/strict";
import test from "node:test";
import type { Command } from "../src/shared/api/commands.ts";
import type { StoredEntry } from "../src/shared/offline/store.ts";
import { discardCommand, inRecordedOrder, nextOrder, recordedOrder, redoCommands } from "../src/shared/offline/review.ts";
import { outcomeAction } from "../src/shared/offline/outcome.ts";
import { redoVersion, tripOf } from "../src/roles/loader/data/redo.ts";

// Issue #28: a person redoes or discards a write the server held (sync:Resolve,
// sync:Discard). Only its owner does it (decision D-O), on their own device.

const command = (id: string, tripId: string, at: string, actingUserId = "isuru"): Command => ({
  commandId: id,
  kind: "loading:CheckItem",
  expectedVersion: 4,
  payload: { tripId, orderId: "o1", lineNo: 1, outcome: "LOADED" },
  clientRecordedAt: at,
  actingUserId,
});

const held = (serverVersion?: number): StoredEntry => ({
  commandId: "held-1",
  kind: "loading:CheckItem",
  payload: command("held-1", "trip-1", "2026-10-03T01:00:00.000Z"),
  enqueuedAt: "2026-10-03T01:00:00.000Z",
  attempts: 1,
  needsReview: true,
  problemCode: "VERSION_CONFLICT",
  ...(serverVersion === undefined ? {} : { serverVersion }),
});

test("a redo is the same write on the current version under a new id, recorded before the resolve that names it", () => {
  const now = new Date("2026-10-03T02:00:00.000Z");
  const { redo, resolve } = redoCommands(held(2), 9, "kasun", now);

  assert.notEqual(redo.commandId, "held-1", "a resend of the held id would only get its first answer back");
  assert.equal(redo.kind, "loading:CheckItem");
  assert.equal(redo.expectedVersion, 9);
  assert.deepEqual(redo.payload, { tripId: "trip-1", orderId: "o1", lineNo: 1, outcome: "LOADED" });
  assert.equal(redo.actingUserId, "kasun", "the loader doing the redo records it");
  assert.equal(redo.clientRecordedAt, now.toISOString());

  assert.ok(resolve);
  assert.equal(resolve.kind, "sync:Resolve");
  assert.equal(resolve.expectedVersion, 2, "names the held operation's version on the server");
  assert.deepEqual(resolve.payload, { operationId: "held-1", replacedBy: redo.commandId });
  assert.ok(Date.parse(resolve.clientRecordedAt) > Date.parse(redo.clientRecordedAt), "the server applies the redo first");
});

test("a write held before versions were kept is redone without a resolve", () => {
  const { redo, resolve } = redoCommands(held(), 9, undefined, new Date());
  assert.equal(resolve, null);
  assert.equal(redo.actingUserId, "isuru", "with nobody named, the original loader stays");
});

test("a discard names the operation, its version and the person's reason", () => {
  const discard = discardCommand(held(2), "Entered by mistake", new Date("2026-10-03T02:00:00.000Z"));
  assert.ok(discard);
  assert.equal(discard.kind, "sync:Discard");
  assert.equal(discard.expectedVersion, 2);
  assert.deepEqual(discard.payload, { operationId: "held-1", reason: "Entered by mistake" });
  assert.equal(discardCommand(held(), "x", new Date()), null, "an unknown version is dropped on the device only");
});

test("a batch goes in the order things were recorded, not the order storage keeps them in", () => {
  const entries = [
    { commandId: "a", enqueuedAt: "2026-10-03T01:00:02.000Z" },
    { commandId: "z", enqueuedAt: "2026-10-03T01:00:00.000Z" },
    { commandId: "m", enqueuedAt: "2026-10-03T01:00:01.000Z" },
  ];
  assert.deepEqual(inRecordedOrder(entries).map((e) => e.commandId), ["z", "m", "a"]);
});

test("a redone or discarded write leaves the device", () => {
  assert.equal(outcomeAction("RESOLVED"), "dropped");
  assert.equal(outcomeAction("DISCARDED"), "dropped");
});

test("a loader redo counts the checks for the same trip still waiting to apply first", () => {
  const waiting: StoredEntry[] = [
    { commandId: "w1", kind: "loading:CheckItem", payload: command("w1", "trip-1", "t"), enqueuedAt: "t", attempts: 0 },
    { commandId: "w2", kind: "loading:Shortfall", payload: command("w2", "trip-1", "t"), enqueuedAt: "t", attempts: 0 },
    { commandId: "w3", kind: "loading:CheckItem", payload: command("w3", "trip-2", "t"), enqueuedAt: "t", attempts: 0 },
    { commandId: "w4", kind: "sync:Discard", payload: { commandId: "w4", kind: "sync:Discard", payload: { operationId: "x" } }, enqueuedAt: "t", attempts: 0 },
  ];
  assert.equal(tripOf(held()), "trip-1");
  assert.equal(redoVersion("trip-1", 7, waiting), 9);
  assert.equal(redoVersion("trip-3", 7, waiting), 7);
});

test("writes recorded in the same millisecond keep the order they were queued in", () => {
  const at = "2026-10-05T03:30:00.000Z";
  const start = { commandId: "zz-start", enqueuedAt: at, order: nextOrder(at, []) };
  const arrival = { commandId: "mm-arrival", enqueuedAt: at, order: nextOrder(at, [start]) };
  const record = { commandId: "aa-record", enqueuedAt: at, order: nextOrder(at, [start, arrival]) };
  const proof = { commandId: "00-proof", enqueuedAt: at, order: nextOrder(at, [start, arrival, record]) };
  const queued = [proof, record, start, arrival];
  assert.deepEqual(inRecordedOrder(queued).map((e) => e.commandId), ["zz-start", "mm-arrival", "aa-record", "00-proof"]);
  const sequences = inRecordedOrder(queued).map(recordedOrder);
  assert.equal(new Set(sequences).size, 4, "every write has its own sequence");
  // A write queued before order existed falls back to its recording time, and the next one goes after it.
  const old = { commandId: "old", enqueuedAt: "2026-10-05T03:31:00.000Z" };
  assert.ok(nextOrder(at, [old]) > recordedOrder(old));
});

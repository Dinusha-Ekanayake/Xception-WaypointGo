import assert from "node:assert/strict";
import test from "node:test";
import { drainAccount, POSITION_KIND as WORKER_POSITION_KIND } from "../scripts/sw-drain.mjs";
import { ExecutionCommandKind } from "../src/shared/domain/execution.ts";
import { inSendOrder, isPosition, POSITION_KIND } from "../src/shared/offline/lanes.ts";
import type { StoredEntry } from "../src/shared/offline/store.ts";

// Roadmap F-03: a backlog of positions never holds up a delivery, receipt or
// issue write.

const entry = (id: string, order: number, kind: string, waitsFor?: string[]): StoredEntry => ({
  commandId: id, kind, payload: { commandId: id, kind, payload: {} }, enqueuedAt: "2026-10-06T01:00:00Z", order, attempts: 0,
  ...(waitsFor ? { waitsFor } : {}),
});

test("the page, the worker and the command catalogue name the same position command", () => {
  assert.equal(POSITION_KIND, ExecutionCommandKind.recordPositions);
  assert.equal(WORKER_POSITION_KIND, POSITION_KIND);
  assert.equal(isPosition({ kind: POSITION_KIND }), true);
  assert.equal(isPosition({ kind: "delivery:Record" }), false);
});

test("business writes go first in their recorded order, even behind an older position backlog", () => {
  const backlog = Array.from({ length: 150 }, (_, i) => entry(`p${i}`, i + 1, POSITION_KIND));
  const writes = [entry("arrival", 200, "delivery:RecordArrival"), entry("record", 201, "delivery:Record")];
  const order = inSendOrder([...backlog, ...writes], new Set()).map((e) => e.commandId);
  assert.deepEqual(order.slice(0, 2), ["arrival", "record"]);
  assert.equal(order[2], "p0");
  // A first batch of 100 carries both business writes.
  assert.ok(order.slice(0, 100).includes("record"));
});

test("a business write waiting for its upload stops later business writes, but not the positions", () => {
  const order = inSendOrder(
    [entry("proof", 1, "delivery:CaptureProof", ["photo-1"]), entry("record", 2, "delivery:Record"), entry("p1", 3, POSITION_KIND)],
    new Set(["photo-1"]),
  ).map((e) => e.commandId);
  assert.deepEqual(order, ["p1"]);
});

test("the service worker sends in the same order", async () => {
  const posts: Array<{ operations: Array<{ command: { commandId: string } }> }> = [];
  const entries = [entry("p0", 1, POSITION_KIND), entry("p1", 2, POSITION_KIND), entry("arrival", 3, "delivery:RecordArrival")];
  await drainAccount({
    entries: async () => entries,
    deviceId: async () => "device-1",
    post: async (body: (typeof posts)[number]) => {
      posts.push(body);
      return { status: 200, json: { results: body.operations.map((o, i) => ({ operationId: o.command.commandId, sequence: i + 1, status: "APPLIED" })) } };
    },
    put: async () => undefined,
    remove: async () => undefined,
  });
  assert.deepEqual(posts[0]!.operations.map((o) => o.command.commandId), ["arrival", "p0", "p1"]);
});

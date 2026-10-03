import assert from "node:assert/strict";
import test from "node:test";
import { drainAccount, outcomeOf } from "../scripts/sw-drain.mjs";
import { outcomeAction } from "../src/shared/offline/outcome.ts";
import type { OperationStatus } from "../src/shared/domain/sync.ts";

// Background Sync with no page open (issue #28, A-39): the service worker
// drains a queue itself, by the page's rules.

type Entry = { commandId: string; kind: string; enqueuedAt: string; order?: number; attempts: number; payload: unknown; needsReview?: boolean; serverVersion?: number; problemCode?: string };

const entry = (id: string, at: string, kind = "delivery:RecordArrival"): Entry => ({
  commandId: id, kind, enqueuedAt: at, attempts: 0, payload: { commandId: id, kind, payload: {} },
});

function fakeIo(entries: Entry[], answer: (body: { operations: Array<{ command: { commandId: string } }> }) => { status: number; json: unknown }, device: string | null = "device-1") {
  const store = new Map(entries.map((e) => [e.commandId, e]));
  const posts: Array<{ deviceId: string; operations: Array<{ sequence: number; command: { commandId: string } }> }> = [];
  return {
    store,
    posts,
    io: {
      entries: async () => [...store.values()],
      deviceId: async () => device,
      post: async (body: (typeof posts)[number]) => { posts.push(body); return answer(body); },
      put: async (e: Entry) => { store.set(e.commandId, e); },
      remove: async (id: string) => { store.delete(id); },
    },
  };
}

const results = (statuses: Record<string, { status: string; problemCode?: string; rowVersion?: number }>) =>
  ({ operations }: { operations: Array<{ command: { commandId: string } }> }) => ({
    status: 200,
    json: { results: operations.map((o, i) => ({ operationId: o.command.commandId, sequence: i + 1, detail: null, replayed: false, problemCode: null, ...statuses[o.command.commandId] })) },
  });

test("the worker treats every answer exactly as the page's queue does", () => {
  for (const status of ["RECEIVED", "APPLIED", "CONFLICT", "REJECTED", "DISCARDED", "RESOLVED"] as OperationStatus[]) {
    assert.equal(outcomeOf(status), outcomeAction(status), status);
  }
});

test("it sends in recorded order under the page's device id, removes what applied and holds a conflict with its version", async () => {
  const { io, store, posts } = fakeIo(
    [entry("b", "2026-10-03T01:00:02Z"), entry("a", "2026-10-03T01:00:01Z")],
    results({ a: { status: "APPLIED" }, b: { status: "CONFLICT", problemCode: "VERSION_CONFLICT", rowVersion: 2 } }),
  );
  assert.equal(await drainAccount(io), "sent");
  assert.equal(posts[0]!.deviceId, "device-1");
  assert.deepEqual(posts[0]!.operations.map((o) => o.command.commandId), ["a", "b"]);
  assert.equal(store.has("a"), false);
  assert.deepEqual(
    { review: store.get("b")!.needsReview, version: store.get("b")!.serverVersion, code: store.get("b")!.problemCode },
    { review: true, version: 2, code: "VERSION_CONFLICT" },
  );
});

test("an undecided answer stops there and keeps the rest", async () => {
  const { io, store } = fakeIo(
    [entry("a", "2026-10-03T01:00:01Z"), entry("b", "2026-10-03T01:00:02Z")],
    results({ a: { status: "RECEIVED" } }),
  );
  await drainAccount(io);
  assert.equal(store.get("a")!.attempts, 1);
  assert.equal(store.has("b"), true);
});

test("loader work waits for a page, which replays the operator switches first", async () => {
  const { io, posts } = fakeIo([entry("a", "2026-10-03T01:00:01Z", "loading:Check")], results({}));
  assert.equal(await drainAccount(io), "needs-page");
  assert.equal(posts.length, 0);
});

test("with no device id kept by a page it waits, and a signed-out answer keeps everything", async () => {
  const none = fakeIo([entry("a", "t")], results({}), null);
  assert.equal(await drainAccount(none.io), "no-device");
  assert.equal(none.posts.length, 0);

  const signedOut = fakeIo([entry("a", "2026-10-03T01:00:01Z")], () => ({ status: 401, json: null }));
  assert.equal(await drainAccount(signedOut.io), "kept");
  assert.equal(signedOut.store.has("a"), true);
});

test("an outage throws so the browser tries the sync again", async () => {
  const { io, store } = fakeIo([entry("a", "2026-10-03T01:00:01Z")], () => ({ status: 503, json: null }));
  await assert.rejects(drainAccount(io));
  assert.equal(store.has("a"), true);
});

test("held writes are not sent again, and an empty queue sends nothing", async () => {
  const held = { ...entry("a", "2026-10-03T01:00:01Z"), needsReview: true };
  const { io, posts } = fakeIo([held], results({}));
  assert.equal(await drainAccount(io), "empty");
  assert.equal(posts.length, 0);
});

test("the worker keeps the device's order for writes recorded in the same millisecond", async () => {
  const at = "2026-10-05T03:30:00.000Z";
  const base = Date.parse(at) * 1000;
  const { io, posts } = fakeIo(
    [{ ...entry("zz", at), order: base + 3 }, { ...entry("aa", at), order: base + 1 }, { ...entry("mm", at), order: base + 2 }],
    results({ zz: { status: "APPLIED" }, aa: { status: "APPLIED" }, mm: { status: "APPLIED" } }),
  );
  await drainAccount(io);
  assert.deepEqual(posts[0]!.operations.map((o) => [o.command.commandId, o.sequence]), [["aa", base + 1], ["mm", base + 2], ["zz", base + 3]]);
});

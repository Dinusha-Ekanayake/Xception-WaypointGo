import assert from "node:assert/strict";
import test from "node:test";
import { drainAccount } from "../scripts/sw-drain.mjs";
import { request, READ_TIMEOUT_MS } from "../src/shared/api/client.ts";
import { isOutage } from "../src/shared/offline/keptReads.ts";
import { BACKOFF_CAP_MS, BACKOFF_FLOOR_MS, backoffMs, nextAttemptAt, retryAfterOf, waiting } from "../src/shared/offline/retry.ts";

// Roadmap F-01 (jittered backoff that honours Retry-After, EXE-04) and F-02
// (request timeouts that count as an outage).

const lowest = () => 0;
const highest = () => 0.999999;

test("the delay grows and is jittered by half either way", () => {
  assert.equal(backoffMs(0, () => 0.5), 1_000);
  assert.equal(backoffMs(3, () => 0.5), 8_000);
  assert.equal(backoffMs(0, lowest), 500);
  assert.equal(backoffMs(0, highest), 1_500);
  // Two phones that failed together do not try again together.
  assert.notEqual(backoffMs(4, () => 0.1), backoffMs(4, () => 0.9));
});

test("the base delay is capped so a phone retries within 30 s of reconnecting", () => {
  assert.equal(backoffMs(30, () => 0.5), BACKOFF_CAP_MS);
  assert.ok(backoffMs(30, highest) <= 30_000);
  assert.ok(backoffMs(-3, lowest) >= BACKOFF_FLOOR_MS);
});

test("a server that asks for longer through Retry-After gets longer", () => {
  const now = 1_000_000;
  assert.equal(nextAttemptAt(now, 1, 120, () => 0.5), now + 120_000);
  // A Retry-After shorter than the backoff does not shorten it.
  assert.equal(nextAttemptAt(now, 5, 1, () => 0.5), now + 16_000);
  assert.equal(nextAttemptAt(now, 1, undefined, () => 0.5), now + 1_000);
});

test("Retry-After is read from the problem the API client recorded", () => {
  assert.equal(retryAfterOf({ problem: { extensions: { retryAfterSeconds: 30 } } }), 30);
  assert.equal(retryAfterOf({ problem: { extensions: {} } }), undefined);
  assert.equal(retryAfterOf(new TypeError("Failed to fetch")), undefined);
});

test("a write waits until its time and not after", () => {
  assert.equal(waiting({ nextAttemptAt: 2_000 }, 1_999), true);
  assert.equal(waiting({ nextAttemptAt: 2_000 }, 2_000), false);
  assert.equal(waiting({}, 0), false);
});

test("the service worker does not send before the time the page set", async () => {
  const posts: unknown[] = [];
  const io = {
    now: () => 1_000,
    entries: async () => [{ commandId: "a", kind: "delivery:RecordArrival", enqueuedAt: "2026-10-06T01:00:00Z", attempts: 2, nextAttemptAt: 5_000, payload: {} }],
    deviceId: async () => "device-1",
    post: async (body: unknown) => {
      posts.push(body);
      return { status: 200, json: { results: [] } };
    },
    put: async () => undefined,
    remove: async () => undefined,
  };
  await assert.rejects(drainAccount(io), /waiting until/);
  assert.equal(posts.length, 0);
});

test("a request that gets no answer times out as an outage, and a caller's cancel does not", async (t) => {
  const realFetch = globalThis.fetch;
  t.after(() => {
    globalThis.fetch = realFetch;
  });
  // A connected line that carries nothing: the fetch settles only when aborted.
  globalThis.fetch = ((_: string, init: RequestInit) =>
    new Promise((_resolve, reject) => {
      init.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
    })) as typeof fetch;

  const timedOut = await request("/api/slow", { timeoutMs: 20 }).catch((e: unknown) => e);
  assert.ok(timedOut instanceof DOMException && timedOut.name === "TimeoutError");
  assert.equal(isOutage(timedOut), true);

  const caller = new AbortController();
  const pending = request("/api/slow", { signal: caller.signal, timeoutMs: 5_000 }).catch((e: unknown) => e);
  caller.abort();
  const cancelled = await pending;
  assert.ok(cancelled instanceof DOMException && cancelled.name === "AbortError");
  assert.equal(isOutage(cancelled), false);

  assert.equal(READ_TIMEOUT_MS, 8_000);
});

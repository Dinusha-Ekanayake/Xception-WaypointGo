import assert from "node:assert/strict";
import test from "node:test";
import { ApiError } from "../src/shared/api/problem.ts";
import { isOutage, keptSince, readThrough } from "../src/shared/offline/keptReads.ts";

// Kept reads for the loader and store (issue #201): only an outage may be
// answered from the device. The fallback itself needs IndexedDB and is covered
// by the role browser suites (offline.spec.ts).

const problem = (status: number) => new ApiError({ type: "about:blank", title: "t", status, code: "C", detail: "", violations: [] } as never);

test("no network, a timeout and a server error are outages", () => {
  assert.equal(isOutage(new TypeError("Failed to fetch")), true);
  assert.equal(isOutage(problem(503)), true);
  assert.equal(isOutage(problem(408)), true);
});

test("a refusal is the server's answer, and so is a cancelled read", () => {
  for (const status of [401, 403, 404, 409, 422]) assert.equal(isOutage(problem(status)), false, String(status));
  assert.equal(isOutage(new DOMException("aborted", "AbortError")), false);
});

test("a refusal passes through and is never answered from the device", async () => {
  await assert.rejects(readThrough("acct", "store:orders:O1", () => Promise.reject(problem(403))), (e) => e instanceof ApiError && e.status === 403);
});

test("with nothing kept, an outage fails as it did before", async () => {
  const offline = new TypeError("Failed to fetch");
  await assert.rejects(readThrough("acct", "loader:trips:KDY:2026-10-04", () => Promise.reject(offline)), (e) => e === offline);
  assert.equal(keptSince(), null);
});

test("a live answer is returned as is", async () => {
  assert.deepEqual(await readThrough("acct", "store:profile", async () => ({ name: "A" })), { name: "A" });
  assert.equal(keptSince(), null);
});

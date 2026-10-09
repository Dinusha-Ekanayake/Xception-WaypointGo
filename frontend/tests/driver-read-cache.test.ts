import assert from "node:assert/strict";
import { test } from "node:test";
import { readCache } from "../src/roles/driver/data/readCache.ts";

// Roadmap F-07: details the run needs are read again only once their copy is old.

test("a kept value is reused while young, read again once old, and a failure is not kept", async () => {
  let clock = 0;
  let reads = 0;
  const cache = readCache(() => clock);
  const read = async () => ++reads;
  assert.equal(await cache.get("outlet:A", 1000, read), 1);
  clock = 999;
  assert.equal(await cache.get("outlet:A", 1000, read), 1);
  clock = 1000;
  assert.equal(await cache.get("outlet:A", 1000, read), 2);

  let fail = true;
  const flaky = async () => {
    if (fail) throw new Error("offline");
    return "ok";
  };
  await assert.rejects(cache.get("vehicle:V", 1000, flaky));
  fail = false;
  assert.equal(await cache.get("vehicle:V", 1000, flaky), "ok", "a failed read is tried again at once");
});

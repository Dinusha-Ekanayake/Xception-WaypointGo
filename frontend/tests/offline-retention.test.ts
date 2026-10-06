import assert from "node:assert/strict";
import { test } from "node:test";
import { KEEP_MAX, REWRITE_MS, contentHash, storageFailure, toEvict, unchanged } from "../src/shared/offline/retention.ts";
import { nearlyFull } from "../src/shared/pwa/storage.ts";

// Roadmap F-04: what the phone keeps of its saved reads, and for how long.

const NOW = Date.parse("2026-10-07T04:00:00Z");
const ago = (days: number) => new Date(NOW - days * 86_400_000).toISOString();

test("saved reads older than a week go, and the app's own keys never do", () => {
  const evict = toEvict(
    [
      { key: "driver-run:2026-09-28", savedAt: ago(9) },
      { key: "driver-run:2026-10-06", savedAt: ago(1) },
      { key: "waypoint.deviceId", savedAt: ago(30) },
    ],
    NOW,
  );
  assert.deepEqual(evict, ["driver-run:2026-09-28"]);
});

test("beyond the cap the oldest go first", () => {
  const saved = Array.from({ length: KEEP_MAX + 3 }, (_, i) => ({ key: `store:k${i}`, savedAt: new Date(NOW - i * 1000).toISOString() }));
  assert.deepEqual(toEvict(saved, NOW), [`store:k${KEEP_MAX}`, `store:k${KEEP_MAX + 1}`, `store:k${KEEP_MAX + 2}`]);
});

test("an unchanged read is not written again within a minute, a changed one is", () => {
  const hash = contentHash({ stops: [1, 2] });
  assert.equal(hash, contentHash({ stops: [1, 2] }));
  assert.notEqual(hash, contentHash({ stops: [1, 3] }));
  assert.equal(unchanged({ hash, at: NOW }, hash, NOW + 5_000), true);
  assert.equal(unchanged({ hash, at: NOW }, hash, NOW + REWRITE_MS), false, "rewritten so the saved time stays honest");
  assert.equal(unchanged({ hash, at: NOW }, contentHash({ stops: [] }), NOW + 5_000), false);
  assert.equal(unchanged(undefined, hash, NOW), false);
});

test("a full phone is said in words, and the warning comes at 80%", () => {
  const full = Object.assign(new Error("quota"), { name: "QuotaExceededError" });
  assert.equal(storageFailure(full), "This phone is out of storage space. Free some space, then save again.");
  assert.match(storageFailure(new Error("blocked")), /blocked/);
  assert.equal(nearlyFull({ usage: 80, quota: 100 }), true);
  assert.equal(nearlyFull({ usage: 79, quota: 100 }), false);
  assert.equal(nearlyFull(null), false);
  assert.equal(nearlyFull({ usage: 10 }), false);
});

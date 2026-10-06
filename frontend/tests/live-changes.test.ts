import assert from "node:assert/strict";
import { test } from "node:test";
import { concerns, streamKey } from "../src/shared/api/changes.ts";

// Roadmap F-07b: one live connection per set of depots; a read re-reads only for its topics.

test("the same depots in any order share one connection", () => {
  assert.equal(streamKey(["PLG", "KDY", "KDY"]), "KDY,PLG");
  assert.equal(streamKey([]), "");
});

test("a read re-reads only for the topics it shows", () => {
  assert.equal(concerns(["plan", "order"], { topic: "plan", depot: "KDY" }), true);
  assert.equal(concerns(["plan", "order"], { topic: "issue", depot: "KDY" }), false);
});

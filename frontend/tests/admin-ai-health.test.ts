import assert from "node:assert/strict";
import { test } from "node:test";
import { agreement, byUse, flagged } from "../src/roles/admin/data/aiHealth.ts";

test("each use sums its days, counts capped calls as failures and sorts the worst first (R-ML-27)", () => {
  const day = (purpose: string, used: number, unfaithful: number, failed: number, capped: number, p95: number) =>
    ({ purpose, day: "2026-10-21", used, unfaithful, failed, capped, p50Millis: 100, p95Millis: p95 });
  const uses = byUse([day("explain_plan", 8, 1, 0, 1, 900), day("explain_plan", 2, 0, 0, 0, 1200), day("explain_order", 4, 2, 1, 0, 400)]);
  assert.deepEqual(uses.map((u) => u.purpose), ["explain_order", "explain_plan"]);
  assert.equal(uses[1]!.calls, 12);
  assert.equal(uses[1]!.failureShare, 2 / 12);
  assert.equal(uses[1]!.worstP95, 1200);
  assert.equal(uses[1]!.label, "Plan explanations");
  assert.equal(flagged(uses[0]!), true, "3 of 7 failed");
  assert.equal(flagged(uses[1]!), false);
});

test("Jev's agreement reads in words, and says nothing before an answer", () => {
  assert.equal(agreement(20, 18), "18 of 20 agreed (90%)");
  assert.equal(agreement(0, 0), null);
});

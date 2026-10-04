import assert from "node:assert/strict";
import test from "node:test";
import { PLAYBOOKS, fill, suggestionFor, type Scenario } from "../src/roles/dispatcher/data/playbooks.ts";

// Issue #269, first slice: a playbook per scenario, and a message filled from the trip.

test("every scenario has steps, and every blank in a template is one the trip can fill", () => {
  const known = new Set(["vehicle", "store", "expected", "window close"]);
  for (const scenario of Object.keys(PLAYBOOKS) as Scenario[]) {
    const playbook = PLAYBOOKS[scenario];
    assert.ok(playbook.steps.length > 0, `${scenario} has no steps`);
    for (const blank of playbook.message?.template.match(/\{([^}]+)\}/g) ?? []) assert.ok(known.has(blank.slice(1, -1)), `${scenario} uses ${blank}`);
  }
});

test("a message is filled from the trip and the store", () => {
  const s = suggestionFor("window", { vehicle: "VEH037", store: "OUT012", expected: "10:40", "window close": "11:00" });
  assert.equal(s.message?.to, "outlet");
  assert.equal(s.message?.body, "VEH037 is running late to OUT012 and is now expected at 10:40. Your window closes at 11:00. Can you still receive it?");
});

test("a message with a blank it cannot fill is not offered, and the steps still are", () => {
  assert.equal(fill("{vehicle} at {store}", { vehicle: "VEH037" }), null);
  const s = suggestionFor("failed", { vehicle: "VEH037", store: null });
  assert.equal(s.message, null);
  assert.ok(s.steps.length > 0);
});

test("the same situation always gets the same advice", () => {
  const facts = { vehicle: "VEH001", store: "OUT001", expected: "09:00", "window close": "10:00" };
  assert.deepEqual(suggestionFor("left", facts), suggestionFor("left", facts));
  assert.equal(suggestionFor("issue", facts).message, null);
});

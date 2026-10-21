import assert from "node:assert/strict";
import test from "node:test";
import { fill, playbookOf, suggestionFor } from "../src/roles/dispatcher/data/playbooks.ts";
import type { PlaybookView } from "../src/shared/domain/intelligence.ts";

// Issue #269: the playbooks are the server's (intelligence Playbooks, whose
// PlaybooksTest checks every blank is one the trip can fill); the screen finds
// a scenario's playbook and fills its message from the trip.

const CATALOGUE: PlaybookView[] = [
  {
    situation: "WINDOW",
    steps: ["Tell the store the new arrival time, so it can keep someone at the dock."],
    message: { to: "outlet", template: "{vehicle} is running late to {store} and is now expected at {expected}. Your window closes at {window close}. Can you still receive it?" },
  },
  { situation: "FAILED", steps: ["Open the trip and read why the driver could not deliver."], message: { to: "outlet", template: "The delivery to {store} on {vehicle} could not be completed today." } },
  { situation: "ISSUE", steps: ["Open the issue and read what was reported."], message: null },
];

test("a scenario finds its playbook in the server's catalogue, and none while it is unread", () => {
  assert.equal(playbookOf(CATALOGUE, "window")?.situation, "WINDOW");
  assert.equal(playbookOf(null, "window"), undefined);
  assert.equal(playbookOf(CATALOGUE, "left"), undefined);
});

test("a message is filled from the trip and the store", () => {
  const s = suggestionFor(playbookOf(CATALOGUE, "window")!, { vehicle: "VEH037", store: "OUT012", expected: "10:40", "window close": "11:00" });
  assert.equal(s.message?.to, "outlet");
  assert.equal(s.message?.body, "VEH037 is running late to OUT012 and is now expected at 10:40. Your window closes at 11:00. Can you still receive it?");
});

test("a message with a blank it cannot fill is not offered, and the steps still are", () => {
  assert.equal(fill("{vehicle} at {store}", { vehicle: "VEH037" }), null);
  const s = suggestionFor(playbookOf(CATALOGUE, "failed")!, { vehicle: "VEH037", store: null });
  assert.equal(s.message, null);
  assert.ok(s.steps.length > 0);
  assert.equal(suggestionFor(playbookOf(CATALOGUE, "issue")!, {}).message, null);
});

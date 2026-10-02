import assert from "node:assert/strict";
import { test } from "node:test";
import type { IssueView } from "../src/shared/domain/issues.ts";
import type { OrderView } from "../src/shared/domain/ordering.ts";
import { issueCard, problemsIn } from "../src/roles/store/data/issues.ts";

// How an issue reads on the store's Issues tab (Figma "08 Issues").

const clock = (instant: string) => instant.slice(11, 16);

const issue = (over: Partial<IssueView>): IssueView => ({
  issueId: "iss-1",
  type: "RECEIPT_DISPUTE",
  severity: "HIGH",
  status: "OPEN",
  depotCode: "KDY",
  outletId: "OUT085",
  subjects: [{ type: "order", id: "order-1" }],
  description: "",
  assignee: null,
  resolutionAction: null,
  resolutionNote: null,
  raisedBy: "system",
  raisedAt: "2026-09-28T05:54:00Z",
  resolvedAt: null,
  rowVersion: 1,
  ...over,
});

const order = { orderRef: "ORD0092335", temperature: "ambient" } as OrderView;

test("a store's note is read back as units per problem", () => {
  assert.deepEqual(problemsIn("shortage investigation: partial receipt. Store's note: Damaged: Red lentils 1 kg x1, Biscuits x1. Missing: Soya meat x1. 2 photos."), [
    { word: "damaged", units: 2 },
    { word: "missing", units: 1 },
  ]);
  assert.deepEqual(problemsIn("Missing: Milk 1.5 L x2, Tea 0.5 kg x3. Note: late."), [{ word: "missing", units: 5 }], "a decimal point is not the end of a part");
  assert.deepEqual(problemsIn("nothing in our words"), []);
});

test("the count's investigation reads as damaged on arrival, with the packages and the photos", () => {
  const card = issueCard(
    issue({
      description: "shortage investigation: partial receipt. Store's note: Damaged: Red lentils 1 kg x1, Biscuits x1. Missing: Soya meat x1.",
      attachments: [{ attachmentId: "a1", contentType: "image/jpeg" }],
    }),
    order,
    clock,
  );
  assert.deepEqual(card, {
    label: "Damaged on arrival",
    tone: "danger",
    title: "3 packages of ORD0092335 (Ambient)",
    detail: "2 damaged · 1 missing · photos attached",
    stamp: "Sent 05:54",
  });
});

test("the loader's shortage reads as a short delivery reported at the dock", () => {
  const card = issueCard(
    issue({ type: "LOADING_SHORTFALL", description: "MISSING at loading: 1 units. not on the shelf", raisedAt: "2026-09-28T03:10:00Z" }),
    { orderRef: "ORD0092336", temperature: "chilled" } as OrderView,
    clock,
  );
  assert.equal(card.label, "Short delivery");
  assert.equal(card.title, "1 package of ORD0092336 (Chilled)");
  assert.equal(card.detail, "Reported by the loader at 03:10");
  assert.equal(card.stamp, "Reported 03:10");
});

test("a resolved issue is muted and says when", () => {
  const card = issueCard(issue({ type: "OTHER", status: "RESOLVED", description: "Wrong item: Sugar 1 kg x2.", resolvedAt: "2026-09-28T09:00:00Z" }), order, clock);
  assert.equal(card.label, "Wrong item");
  assert.equal(card.tone, "muted");
  assert.equal(card.stamp, "Resolved 09:00");
});

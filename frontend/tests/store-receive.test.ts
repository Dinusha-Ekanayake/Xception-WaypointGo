import assert from "node:assert/strict";
import { test } from "node:test";
import type { ItemView } from "../src/shared/domain/loading.ts";
import type { ReceiptLineView } from "../src/shared/domain/receipt.ts";
import { answerFor, counted, knownShortages, noteOf, room, type Report } from "../src/roles/store/data/receive.ts";

// The store's count (Figma "06 Receive delivery"): one problem is one report, and
// what the loader already flagged lowers the count without being reported again.

const LINES: ReceiptLineView[] = [
  { productId: "Fresh milk 1 L", expectedQuantity: 3, receivedQuantity: null },
  { productId: "Butter 200 g", expectedQuantity: 2, receivedQuantity: null },
];

const item = (lineNo: number, productId: string, units: number, status: ItemView["status"], loadedUnits: number): ItemView => ({
  lineNo, productId, units, status, loadedUnits, attempt: 1, checkedAt: null, checkedBy: null,
});

const report = (productId: string, kind: Report["kind"], units: number, photoIds: string[] = []): Report => ({ productId, kind, units, photoIds });

test("only what the loader flagged and did not load counts as kept back", () => {
  const known = knownShortages([
    item(1, "Fresh milk 1 L", 3, "LOADED", 3),
    item(2, "Butter 200 g", 2, "SHORT", 1),
    item(3, "Cheese 200 g", 4, "MISSING", 0),
    item(4, "Yoghurt", 6, "PENDING", 0),
  ]);
  assert.deepEqual([...known.entries()], [["Butter 200 g", 1], ["Cheese 200 g", 4]]);
  assert.equal(knownShortages(null).size, 0, "no loading check, nothing assumed");
});

test("the count is expected, less what the loader kept back, less what the store reported", () => {
  const known = new Map([["Butter 200 g", 1]]);
  const reports = [report("Fresh milk 1 L", "Damaged", 1), report("Butter 200 g", "Other", 0)];
  assert.deepEqual(counted(LINES, reports, known), [
    { productId: "Fresh milk 1 L", receivedQuantity: 2 },
    { productId: "Butter 200 g", receivedQuantity: 1 },
  ]);
  assert.equal(room(LINES[0]!, reports, known), 2);
  assert.equal(room(LINES[1]!, reports, known), 1, "the loader's unit cannot be reported again");
});

test("the note lists the problems by kind, the photos and the store's words", () => {
  const note = noteOf([report("Fresh milk 1 L", "Damaged", 1, ["p1", "p2"]), report("Butter 200 g", "Missing", 1), report("Butter 200 g", "Other", 0)], "seal open");
  assert.equal(note, "Missing: Butter 200 g x1. Damaged: Fresh milk 1 L x1. Other: Butter 200 g. 2 photos. Note: seal open.");
  assert.equal(noteOf([]), null);
});

test("a shortage only the loader explains is a partial receipt with no note, so it is not investigated twice", () => {
  assert.deepEqual(answerFor(LINES, [], new Map([["Butter 200 g", 1]]), false, ""), { kind: "partial", note: null });
});

test("a damaged item is a partial receipt whose note says so", () => {
  assert.deepEqual(answerFor(LINES, [report("Fresh milk 1 L", "Damaged", 1)], new Map(), false, ""), {
    kind: "partial",
    note: "Damaged: Fresh milk 1 L x1.",
  });
});

test("a remark on a complete delivery is a confirmation and one issue", () => {
  const answer = answerFor(LINES, [report("Butter 200 g", "Other", 0)], new Map(), false, "wrapper torn");
  assert.deepEqual(answer, { kind: "confirm-and-raise", description: "Other: Butter 200 g. Note: wrapper torn." });
});

test("nothing wrong is a plain confirmation; something else wrong is a dispute with its reason first", () => {
  assert.deepEqual(answerFor(LINES, [], new Map(), false, ""), { kind: "confirm" });
  assert.deepEqual(answerFor(LINES, [report("Fresh milk 1 L", "Missing", 1)], new Map(), true, "wrong pallet"), {
    kind: "dispute",
    reason: "wrong pallet · Missing: Fresh milk 1 L x1.",
  });
});

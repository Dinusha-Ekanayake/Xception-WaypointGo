import { test } from "node:test";
import assert from "node:assert/strict";
import { issueText, productLabel } from "../src/shared/wording/index.ts";

// Issue texts and seeded product lines read as sentences (glossary: counts in words, no codes).

test("a loading shortfall reads as a sentence with the count in words", () => {
  assert.equal(issueText("SHORT at loading: 1 units. One case short at the dock"), "Short at loading: 1 unit. One case short at the dock");
  assert.equal(issueText("DOES_NOT_FIT at loading: 4 units."), "Doesn't fit at loading: 4 units.");
});

test("a shortage investigation names the seeded line as mixed goods", () => {
  assert.equal(
    issueText("shortage investigation: partial receipt. Store's note: Damaged: SEED-Fresh-ambient x1. 1 photo."),
    "Shortage to check: partial receipt. Store's note: Damaged: Dry goods (mixed) x1. 1 photo.",
  );
});

test("a real product keeps its name", () => {
  assert.equal(productLabel("Butter 200 g"), "Butter 200 g");
  assert.equal(productLabel("SEED-Tech-chilled"), "Chilled goods (mixed)");
  assert.equal(issueText("Driver could not reach the store"), "Driver could not reach the store");
});

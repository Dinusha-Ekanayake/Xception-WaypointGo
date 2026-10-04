import assert from "node:assert/strict";
import test from "node:test";
import { ISSUE_KIND_LABEL } from "../src/roles/loader/data/manifest.ts";

test("loader reports Short, Damaged, Doesn't fit and Missing, in the order of Figma 03", () => {
  assert.deepEqual(Object.values(ISSUE_KIND_LABEL), ["Short", "Damaged", "Doesn't fit", "Missing"]);
});

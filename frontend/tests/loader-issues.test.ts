import assert from "node:assert/strict";
import test from "node:test";
import { ISSUE_KIND_LABEL } from "../src/roles/loader/data/manifest.ts";

test("loader can report only Damaged, Doesn't fit, and Missing", () => {
  assert.deepEqual(Object.values(ISSUE_KIND_LABEL), ["Damaged", "Doesn't fit", "Missing"]);
});

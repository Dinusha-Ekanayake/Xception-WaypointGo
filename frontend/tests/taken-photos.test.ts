import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

// Roadmap FF-01: one photo flow for every report sheet. The loader's shortfall
// sheet and the store's report and receive screens take photos through the
// shared module, never their own copy of the encode-and-check steps.

const read = (path: string) => readFileSync(new URL(`../src/${path}`, import.meta.url), "utf8");

test("every report sheet takes photos through the shared flow", () => {
  for (const file of ["roles/loader/screens/IssueSheet.tsx", "roles/store/screens/issues/ReportIssue.tsx", "roles/store/screens/receive/Receive.tsx"]) {
    const source = read(file);
    assert.match(source, /shared\/media\/useTakenPhotos/, `${file} uses the shared photo flow`);
    assert.doesNotMatch(source, /checkPhoto\(|encodePhoto\(|createObjectURL\(/, `${file} has no copy of the steps`);
  }
});

test("the shared flow releases every preview it makes", () => {
  const source = read("shared/media/useTakenPhotos.ts");
  assert.match(source, /revokeObjectURL/);
  assert.match(source, /useEffect\(\(\) => \(\) =>/, "on unmount");
});

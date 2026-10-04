import assert from "node:assert/strict";
import test from "node:test";
import { dismisses, rubberBand } from "../src/shared/ui/sheetDrag.ts";

// UX polish: a phone's bottom sheet follows the finger down, resists a pull up,
// and closes only on a long or quick drag down.

test("a drag down moves the sheet by the same distance", () => {
  assert.equal(rubberBand(0), 0);
  assert.equal(rubberBand(90), 90);
});

test("a pull up resists and never passes the limit", () => {
  const small = rubberBand(-20);
  const large = rubberBand(-2000);
  assert.ok(small < 0 && small > -20, `pull of 20 moved ${small}`);
  assert.ok(large > -160, `pull of 2000 moved ${large}`);
  assert.ok(rubberBand(-200) < rubberBand(-100), "a longer pull still moves further");
});

test("a long drag or a quick flick down closes; a short slow one springs back", () => {
  assert.equal(dismisses(121, 0), true);
  assert.equal(dismisses(40, 0.8), true);
  assert.equal(dismisses(40, 0.2), false);
  assert.equal(dismisses(-30, 2), false);
});

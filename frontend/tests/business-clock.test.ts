import assert from "node:assert/strict";
import test from "node:test";
import { addDays, businessNow, clockOffsetMs, depotToday, nowMs, onClockOffset, setClockOffset } from "../src/shared/wording/index.ts";

// Issue #231: today, the cutoff and every countdown on screen read the business
// clock, which is the device's time plus the demo clock's offset.

test("with no offset the business clock is the device's own time", () => {
  setClockOffset(0);
  assert.equal(clockOffsetMs(), 0);
  assert.ok(Math.abs(nowMs() - Date.now()) < 50);
});

test("a demo offset moves now and today, and back to real time moves them back", () => {
  setClockOffset(0);
  const realToday = depotToday();
  setClockOffset(2 * 86_400);
  assert.ok(Math.abs(businessNow().getTime() - (Date.now() + 2 * 86_400_000)) < 50);
  assert.equal(depotToday(), addDays(realToday, 2));
  setClockOffset(0);
  assert.equal(depotToday(), realToday);
});

test("a listener hears an offset change once, and not a repeat of the same offset", () => {
  setClockOffset(0);
  let heard = 0;
  const stop = onClockOffset(() => heard++);
  setClockOffset(3600);
  setClockOffset(3600);
  stop();
  setClockOffset(0);
  assert.equal(heard, 1);
});

import assert from "node:assert/strict";
import test from "node:test";
import { BARS, liveHeight, peaksFrom, placeholderPeaks, resample, seekFraction } from "../src/shared/messaging/waveform.ts";

// Issue #136: the bars of a voice note.

test("a waveform has one bar per slice, the loudest at full height, silence still visible", () => {
  const samples = Float32Array.from({ length: 3600 }, (_, i) => (i < 1800 ? 0 : Math.sin(i) * (i / 3600)));
  const peaks = peaksFrom(samples);
  assert.equal(peaks.length, BARS);
  assert.equal(Math.max(...peaks), 1);
  assert.ok(peaks.every((p) => p >= 0.08 && p <= 1));
  assert.ok(peaks[0]! < peaks[BARS - 1]!, "the quiet half is lower than the loud half");
  assert.deepEqual(peaksFrom([], 4), [0.08, 0.08, 0.08, 0.08]);
});

test("levels sampled while recording become the same number of bars, whatever their count", () => {
  assert.equal(resample([0.1, 0.5, 0.2]).length, BARS);
  assert.equal(resample(Array.from({ length: 1200 }, (_, i) => i % 7)).length, BARS);
  assert.equal(Math.max(...resample([0.1, 0.5, 0.2], 3)), 1);
});

test("a note that cannot be decoded keeps the same bars every time", () => {
  assert.deepEqual(placeholderPeaks("voice-a"), placeholderPeaks("voice-a"));
  assert.notDeepEqual(placeholderPeaks("voice-a"), placeholderPeaks("voice-b"));
  assert.ok(placeholderPeaks("x").every((p) => p >= 0.08 && p <= 1));
});

test("a tap on the bars jumps to that point; live bars stay within the row", () => {
  assert.equal(seekFraction(150, 100, 200), 0.25);
  assert.equal(seekFraction(50, 100, 200), 0);
  assert.equal(seekFraction(400, 100, 200), 1);
  assert.equal(liveHeight(0), 0.12);
  assert.equal(liveHeight(5), 1);
});

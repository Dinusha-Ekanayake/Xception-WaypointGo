import assert from "node:assert/strict";
import { test } from "node:test";
import { brightness, encodedAs, fitWithin, judge, sharpness } from "../src/shared/media/photo.ts";

// Roadmap M-02: one photo encoder, and a blur and darkness check on the phone.

test("a photo is drawn at 1600 px on its longest side and never enlarged", () => {
  assert.deepEqual(fitWithin(4000, 3000, 1600), { width: 1600, height: 1200 });
  assert.deepEqual(fitWithin(3000, 4000, 1600), { width: 1200, height: 1600 });
  assert.deepEqual(fitWithin(800, 600, 1600), { width: 800, height: 600 });
});

test("a browser that quietly made PNG instead of WebP is caught", () => {
  assert.equal(encodedAs("image/webp", new Blob(["x"], { type: "image/webp" })), true);
  assert.equal(encodedAs("image/webp", new Blob(["x"], { type: "image/png" })), false);
  assert.equal(encodedAs("image/webp", null), false);
});

/** A grey image: a sharp checkerboard, a flat field, or a dark one. */
function image(kind: "sharp" | "flat" | "dark", w = 32, h = 32): Uint8ClampedArray {
  const out = new Uint8ClampedArray(w * h);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      out[y * w + x] = kind === "sharp" ? ((x + y) % 2 ? 230 : 40) : kind === "flat" ? 128 : 12;
    }
  }
  return out;
}

test("sharp edges score high, a smeared or flat photo low, and a dark one is called dark", () => {
  assert.ok(sharpness(image("sharp"), 32, 32) > 1000);
  assert.equal(sharpness(image("flat"), 32, 32), 0);
  assert.equal(judge(image("sharp"), 32, 32), "ok");
  assert.equal(judge(image("flat"), 32, 32), "blurry");
  assert.equal(judge(image("dark"), 32, 32), "dark");
  assert.equal(Math.round(brightness(image("flat"))), 128);
});

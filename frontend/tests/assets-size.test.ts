import assert from "node:assert/strict";
import { readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

// Roadmap M-03: pictures in public/assets are WebP and small. The sign-in
// skylines were 1.4 MB PNGs each, loaded on every sign-in over a phone
// connection; as WebP they are about 70 KB.

const DIR = join(import.meta.dirname, "..", "public", "assets");
const LIMIT_BYTES = 300 * 1024;
// Kept PNG on purpose: a signature must stay lossless, and both are a few KB.
const PNG_ALLOWED = new Set(["proof-sample.png", "signature-sample.png"]);

const raster = /\.(png|jpe?g|webp|gif|avif)$/i;

test("every picture in public/assets is under 300 KB", () => {
  for (const name of readdirSync(DIR).filter((n) => raster.test(n))) {
    const size = statSync(join(DIR, name)).size;
    assert.ok(size <= LIMIT_BYTES, `${name} is ${Math.round(size / 1024)} KB; convert it to WebP (cwebp -q 80) or shrink it`);
  }
});

test("new pictures are WebP, not PNG or JPEG", () => {
  const legacy = readdirSync(DIR).filter((n) => /\.(png|jpe?g)$/i.test(n) && !PNG_ALLOWED.has(n));
  assert.deepEqual(legacy, [], "convert these to WebP, or say here why one must stay lossless");
});

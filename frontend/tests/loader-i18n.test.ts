import assert from "node:assert/strict";
import test from "node:test";
import { translate } from "../src/roles/loader/data/strings.ts";

test("English is the key and the default", () => {
  assert.equal(translate("en", "Take trip"), "Take trip");
  assert.equal(translate("en", "{n} left", { n: 3 }), "3 left");
});

test("Sinhala and Tamil fill in values and fall back to English when a string has no translation", () => {
  assert.equal(translate("si", "{n} left", { n: 3 }), "3ක් ඉතිරියි");
  assert.equal(translate("ta", "Take trip"), "பயணத்தை எடு");
  assert.equal(translate("ta", "Not in the dictionary"), "Not in the dictionary");
});

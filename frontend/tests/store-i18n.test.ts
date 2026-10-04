import assert from "node:assert/strict";
import test from "node:test";
import { STRINGS, translate } from "../src/roles/store/data/strings.ts";

test("English is the key and the default", () => {
  assert.equal(translate("en", "Receive delivery"), "Receive delivery");
  assert.equal(translate("en", "{n} placed", { n: 3 }), "3 placed");
});

test("Sinhala and Tamil fill in values and fall back to English when a string has no translation", () => {
  assert.equal(translate("si", "Order for {day}", { day: "Tue 6 Oct" }), "Tue 6 Oct සඳහා ඇණවුම");
  assert.equal(translate("ta", "Sign out"), "வெளியேறு");
  assert.equal(translate("ta", "Not in the dictionary"), "Not in the dictionary");
});

test("every translation keeps the values its English names, and none is empty", () => {
  const names = (text: string) => [...text.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort().join(",");
  for (const [english, row] of Object.entries(STRINGS)) {
    for (const text of row) {
      assert.notEqual(text.trim(), "", english);
      assert.equal(names(text), names(english), english);
    }
  }
});

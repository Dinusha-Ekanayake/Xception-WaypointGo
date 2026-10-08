import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { test } from "node:test";
import { STRINGS, translate } from "../src/roles/driver/data/strings.ts";
import { SUMMARY_LABELS } from "../src/roles/driver/data/stopView.ts";
import { DOCK_TYPE } from "../src/shared/wording/labels.ts";
import { keysIn, sourceFiles as files } from "./i18n-keys.ts";
import { LEAVE_REASONS } from "../src/roles/driver/data/handover.ts";

// Roadmap FF-00: every text the driver's screens pass through t() has a Sinhala
// and a Tamil row, so switching language never leaves an English sentence behind
// on a critical screen. A key here is the first argument of t(...), and both
// branches of a ternary inside it.

const ROOT = join(import.meta.dirname, "../src/roles/driver");

test("a key is found in a plain call, a ternary and a call with values", () => {
  assert.deepEqual(keysIn('t("Vehicle"); t(a === "done" ? "Start trip" : "Continue trip"); tr("Trip {n}", { n: f(1, 2) }); t("or \\u201cthis\\u201d")'), [
    "Vehicle",
    "Start trip",
    "Continue trip",
    "Trip {n}",
    "or \u201cthis\u201d",
  ]);
});

test("every text the driver's screens translate has a Sinhala and a Tamil row", () => {
  const missing = new Set<string>();
  for (const file of files(ROOT)) {
    for (const key of keysIn(readFileSync(file, "utf8"))) {
      if (!STRINGS[key]) missing.add(key);
    }
  }
  assert.deepEqual([...missing].sort(), []);
});

test("every notification kind the driver's Home shows has a Sinhala and a Tamil row", () => {
  const source = readFileSync(join(import.meta.dirname, "../src/shared/notifications/inbox.ts"), "utf8");
  const labels = [...source.matchAll(/label: "([^"]+)"/g)].map((m) => m[1]!);
  assert.ok(labels.length > 10);
  assert.deepEqual(labels.filter((label) => !STRINGS[label]), []);
});

test("the labels translated by value have a Sinhala and a Tamil row", () => {
  // The screens' tables are read as text: importing a .tsx screen would need React here.
  const screen = (name: string) => readFileSync(join(ROOT, "screens", name), "utf8");
  const table = (source: string, start: string) => {
    const body = source.slice(source.indexOf(start), source.indexOf("};", source.indexOf(start)));
    return [...body.matchAll(/:\s*"([^"]+)"/g)].map((m) => m[1]!);
  };
  const choices = [...screen("Sheets.tsx").matchAll(/label: "([^"]+)"/g)].map((m) => m[1]!);
  const labels = [
    ...SUMMARY_LABELS,
    ...Object.values(DOCK_TYPE),
    ...LEAVE_REASONS.map((reason) => reason.label),
    ...choices,
    ...table(screen("DeliveryReport.tsx"), "export const REASON_LABEL"),
    ...table(screen("FuelPassSheet.tsx"), "export const PASS_MISSING"),
  ];
  assert.ok(choices.length >= 5);
  assert.deepEqual(labels.filter((label) => !STRINGS[label]), []);
});

test("a translation keeps every value its English names", () => {
  for (const [english, [si, ta]] of Object.entries(STRINGS)) {
    const names = (s: string) => (s.match(/\{\w+\}/g) ?? []).sort().join(",");
    assert.equal(names(si), names(english), `si: ${english}`);
    assert.equal(names(ta), names(english), `ta: ${english}`);
  }
});

test("English is the key, and a value is filled in every language", () => {
  assert.equal(translate("en", "Trip {n}", { n: 2 }), "Trip 2");
  assert.equal(translate("si", "no such text"), "no such text");
});

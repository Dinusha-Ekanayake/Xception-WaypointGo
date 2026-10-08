import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { test } from "node:test";
import { STRINGS as LOADER } from "../src/roles/loader/data/strings.ts";
import { STRINGS as STORE } from "../src/roles/store/data/strings.ts";
import { keysOfRole } from "./i18n-keys.ts";

// Roadmap FF-00, the loader and the store manager: as for the driver
// (driver-strings.test.ts), every text their screens translate has a Sinhala and
// a Tamil row, and a translation keeps every value its English names.

const ROLES = { loader: LOADER, store: STORE } as const;

for (const [role, table] of Object.entries(ROLES)) {
  test(`every text the ${role}'s screens translate has a Sinhala and a Tamil row`, () => {
    const missing = keysOfRole(join(import.meta.dirname, "../src/roles", role)).filter(({ key }) => !table[key]);
    assert.deepEqual(missing.map(({ key, file }) => `${file}: ${key}`).sort(), []);
  });

  test(`a ${role} translation keeps every value its English names`, () => {
    const names = (s: string) => (s.match(/\{\w+\}/g) ?? []).sort().join(",");
    for (const [english, [si, ta]] of Object.entries(table)) {
      assert.equal(names(si), names(english), `si: ${english}`);
      assert.equal(names(ta), names(english), `ta: ${english}`);
    }
  });
}

// Texts a screen draws by value, as t(label): a table of labels, a helper's
// fixed answer. They are read as sentence-like literals of the files that hold
// them, comments removed, so a new label in one of them needs its row too.
const BY_VALUE = [
  "data/format.ts",
  "data/issues.ts",
  "data/runs.ts",
  "data/outlook.ts",
  "data/nextDelivery.ts",
  "data/deferral.ts",
  "data/receive.ts",
  "screens/deliveries/Deliveries.tsx",
  "AccountMenu.tsx",
  "screens/Orders.tsx",
  "screens/Home.tsx",
  "ui.tsx",
];
const NOT_TEXT = new Set(["Asia/Colombo", "Escape"]);
// Built with a value in code: a receipt's status in words, and a problem's count.
const BUILT = ["confirmed", "partial", "disputed", "auto closed", "pending", "{n} damaged", "{n} missing", "{n} wrong"];

export function literalsByValue(source: string): string[] {
  const code = source.replace(/\/\*[\s\S]*?\*\//g, "").replace(/(^|[^:])\/\/.*$/gm, "$1");
  const sentences = [...code.matchAll(/(?:^|[^\w(])"([A-Z][a-z][^"]*)"/gm)].map((m) => m[1]!);
  // A place in a sentence, "at your rear dock", starts in lower case.
  const places = [...code.matchAll(/\bwhere: "([^"]+)"/g)].map((m) => m[1]!);
  return [...sentences, ...places].filter((x) => !NOT_TEXT.has(x));
}

test("the store's labels drawn by value have a Sinhala and a Tamil row", () => {
  const root = join(import.meta.dirname, "../src/roles/store");
  const shared = readFileSync(join(import.meta.dirname, "../src/shared/notifications/inbox.ts"), "utf8");
  const labels = [
    ...BY_VALUE.flatMap((file) => literalsByValue(readFileSync(join(root, file), "utf8")).map((x) => `${file}: ${x}`)),
    ...[...shared.matchAll(/label: "([^"]+)"/g)].map((m) => `notification kind: ${m[1]!}`),
    ...BUILT.map((x) => `built in code: ${x}`),
  ];
  assert.deepEqual(labels.filter((l) => !STORE[l.slice(l.indexOf(": ") + 2)]).sort(), []);
});

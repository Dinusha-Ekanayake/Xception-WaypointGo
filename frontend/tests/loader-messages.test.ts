import assert from "node:assert/strict";
import test from "node:test";
import { MESSAGE_TEMPLATES, localized } from "../src/roles/loader/data/messages.ts";
import { translate, type Lang } from "../src/roles/loader/data/strings.ts";

// Issue #118: the loader reads its notifications in Sinhala and Tamil.

const tr = (lang: Lang) => (english: string, vars?: Record<string, string | number>) => translate(lang, english, vars);
const published = {
  eventType: "plan.published",
  title: "Plan published for 2026-10-03",
  body: "Version 2 with 6 trips is ready to load.",
  facts: { serviceDate: "2026-10-03", planVersion: "2", tripCount: "6", depotCode: "Peliyagoda" },
};

test("every message template has Sinhala and Tamil", () => {
  for (const template of MESSAGE_TEMPLATES) {
    assert.notEqual(translate("si", template), template, `si: ${template}`);
    assert.notEqual(translate("ta", template), template, `ta: ${template}`);
  }
});

test("a message is filled from its facts in the loader's language, and matches the server in English", () => {
  assert.deepEqual(localized(published, tr("en")), { title: published.title, body: published.body });
  const si = localized(published, tr("si"));
  assert.match(si.title, /2026-10-03/);
  assert.match(si.body, /6/);
  assert.notEqual(si.body, published.body);
  assert.match(localized(published, tr("ta")).title, /திட்டம்/);
});

test("an older message without facts, or with one missing, is shown as the server wrote it", () => {
  const { facts: _f, ...older } = published;
  assert.deepEqual(localized(older, tr("si")), { title: older.title, body: older.body });
  assert.deepEqual(localized({ ...published, facts: { serviceDate: "2026-10-03" } }, tr("si")), { title: published.title, body: published.body });
  assert.deepEqual(localized({ ...published, eventType: "something.else" }, tr("si")), { title: published.title, body: published.body });
});

import assert from "node:assert/strict";
import test from "node:test";
import { activateModel, metricLabel, metricValue, retireModel, trainingPeriod } from "../src/roles/admin/data/models.ts";

// Roadmap MF-01: the admin model registry.

test("validation figures read as words, never raw codes", () => {
  assert.equal(metricLabel("total_wape"), "Total volume, typical miss");
  assert.equal(metricLabel("service_mae_min_last6w"), "Unloading time, average miss in minutes (last 6 weeks)");
  // A name the screen does not know yet still reads without underscores.
  assert.equal(metricLabel("new_metric_name"), "new metric name");
});

test("a share reads as a percentage and other figures as short numbers", () => {
  assert.equal(metricValue("total_wape", "0.0415"), "4.2%");
  assert.equal(metricValue("late_auc_same_season", 0.9788), "0.979");
  assert.equal(metricValue("service_mae_min_same_season", 3.5643), "3.564");
  assert.equal(metricValue("x", "not a number"), "not a number");
});

test("activate and retire name the version and send its rowVersion, so a stale screen cannot overwrite", async (t) => {
  const sent: Array<{ kind: string; expectedVersion: number | null; payload: Record<string, unknown> }> = [];
  const realFetch = globalThis.fetch;
  t.after(() => {
    globalThis.fetch = realFetch;
  });
  globalThis.fetch = (async (_: string, init: RequestInit) => {
    sent.push(JSON.parse(String(init.body)));
    return new Response(JSON.stringify({ commandId: "c", kind: "k", replayed: false, result: {} }), { status: 200 });
  }) as typeof fetch;

  const model = { name: "datathon-task2a", version: "2026.1.1", rowVersion: 4 };
  await activateModel(model);
  await retireModel(model, "replaced by a model trained on our own deliveries");

  assert.equal(sent[0]!.kind, "ml:ActivateModel");
  assert.equal(sent[0]!.expectedVersion, 4);
  assert.deepEqual(sent[0]!.payload, { name: "datathon-task2a", version: "2026.1.1" });
  assert.equal(sent[1]!.kind, "ml:RetireModel");
  assert.equal(sent[1]!.payload.reason, "replaced by a model trained on our own deliveries");
});

test("a training period that spans years shows the years", () => {
  assert.equal(trainingPeriod("2024-01-01", "2026-02-14"), "Trained on Jan 2024 to Feb 2026");
  assert.equal(trainingPeriod(null, "2026-02-14"), "Training period not recorded");
});

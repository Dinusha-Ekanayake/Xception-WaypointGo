import assert from "node:assert/strict";
import { test } from "node:test";
import type { FuelPassView } from "../src/shared/domain/execution.ts";
import { fuelPassSrc, loadFuelPass, stillValid, type FuelPassDeps } from "../src/roles/driver/data/fuelPass.ts";
import { fileProblem, sizeText, statusFor } from "../src/roles/admin/data/fuelPasses.ts";

// Roadmap FF-06b: the official Fuel Pass on the driver's phone. It shows with no
// signal while still valid, never after, and never once the server refuses it.

const pass = (validUntil: string): FuelPassView => ({
  vehicleId: "VEH001", passId: "p-1", contentType: "image/png", sha256: "abc", image: "iVBORw0KGgo=", validUntil,
});

function deps(over: Partial<FuelPassDeps> & { stored?: FuelPassView | null }): FuelPassDeps & { saved: Array<FuelPassView | null> } {
  const saved: Array<FuelPassView | null> = [];
  return {
    read: async () => pass("2026-10-07"),
    kept: async () => over.stored ?? null,
    keep: async (p) => {
      saved.push(p);
    },
    today: "2026-10-07",
    saved,
    ...over,
  };
}

const failing = (status?: number) => async () => {
  throw Object.assign(new Error("failed"), status === undefined ? {} : { status });
};

test("with a signal the pass is read and kept on the phone", async () => {
  const d = deps({});
  const state = await loadFuelPass(d);
  assert.equal(state.kind, "ready");
  assert.equal(state.kind === "ready" && state.fromDevice, false);
  assert.equal(d.saved.length, 1);
});

test("with no signal the kept pass shows while it is still valid, and is dropped after", async () => {
  const valid = await loadFuelPass(deps({ read: failing(), stored: pass("2026-10-07") }));
  assert.equal(valid.kind === "ready" && valid.fromDevice, true);

  const d = deps({ read: failing(), stored: pass("2026-10-06") });
  assert.deepEqual(await loadFuelPass(d), { kind: "unavailable" });
  assert.deepEqual(d.saved, [null], "an expired pass is deleted from the phone");

  assert.deepEqual(await loadFuelPass(deps({ read: failing() })), { kind: "unavailable" });
});

test("a withdrawn pass or a refusal deletes the kept copy and is never shown from the phone", async () => {
  const none = deps({ read: failing(404), stored: pass("2026-10-07") });
  assert.deepEqual(await loadFuelPass(none), { kind: "none" });
  assert.deepEqual(none.saved, [null]);

  const refused = deps({ read: failing(403), stored: pass("2026-10-07") });
  assert.deepEqual(await loadFuelPass(refused), { kind: "refused" });
  assert.deepEqual(refused.saved, [null]);
});

test("validity is to the end of the last day", () => {
  assert.equal(stillValid({ validUntil: "2026-10-08" }, "2026-10-07"), true);
  assert.equal(stillValid({ validUntil: "2026-10-07" }, "2026-10-07"), true);
  assert.equal(stillValid({ validUntil: "2026-10-06" }, "2026-10-07"), false);
  assert.equal(fuelPassSrc(pass("2026-10-07")), "data:image/png;base64,iVBORw0KGgo=");
});

test("the administrator's screen checks the file before sending it", () => {
  assert.equal(fileProblem({ type: "image/png", size: 20_000 }), null);
  assert.match(fileProblem({ type: "application/pdf", size: 20_000 }) ?? "", /PNG, JPEG or WebP/);
  assert.match(fileProblem({ type: "image/jpeg", size: 600 * 1024 }) ?? "", /512 KB/);
  assert.equal(statusFor([], "VEH009").version, 0, "a vehicle that never had a pass expects version 0");
  assert.equal(sizeText(14_500), "14 KB");
});

import test from "node:test";
import assert from "node:assert/strict";
import { testDatabase } from "./helpers/database.ts";
import { Service } from "../lib/service.ts";
import { allocate, validateRoute, loadReference } from "../lib/domain.ts";
import type { User, Order } from "../lib/types.ts";

const dispatcher: User = {
  id: "dispatcher@waypoint.local",
  role: "dispatcher",
  scope: "all",
};
async function setup(t: test.TestContext) {
  const db = await testDatabase(t);
  const s = new Service(db);
  await s.seed();
  let sequence = 0;
  const cmd = async (kind: string, data: Record<string, unknown> = {}) =>
    (await s.command(dispatcher, { id: "test-" + ++sequence, kind, ...data }));
  return { s, cmd };
}

test("fresh seed contains the advertised operational scenarios", async (t) => {
  const { s } = (await setup(t));
  for (const day of [
    "2026-02-09",
    "2026-02-12",
    "2026-02-13",
    "2026-02-14",
    "2026-02-16",
    "2026-02-17",
    "2026-02-18",
  ])
    assert.ok(
      (await s.orders()).some((o) => o.day === day) ||
        (await s.plans()).some((p) => p.day === day),
    );
  assert.equal(s.ref.outlets.length, 120);
  assert.equal(s.ref.vehicles.length, 60);
  assert.ok(!(await s.orders()).some((o) => o.id === "OVERLOAD-001" && o.volume > 20));
});

test("published deferrals join the next open run exactly once with history", async (t) => {
  const { s, cmd } = (await setup(t));
  const day = "2026-02-14";
  (await cmd("plan", { day }));
  const plan = (await s.plans()).find((p) => p.day === day)!;
  for (const d of plan.deferred)
    if (d.repeat)
      (await cmd("defer_note", {
        day,
        order_id: d.order_id,
        note: "Review alternative capacity on next run.",
      }));
  const d = plan.deferred.find((d) => d.order_id !== "OVERLOAD-001")!;
  assert.ok(d);
  const before = (await s.orders()).find((o) => o.id === d.order_id)!;
  const publish = { id: "publish-once", kind: "publish", day };
  (await s.command(dispatcher, publish));
  (await s.command(dispatcher, publish));
  const after = (await s.orders()).find((o) => o.id === d.order_id)!;
  assert.equal(after.day, d.next_date);
  assert.equal(after.skips, before.skips + 1);
  assert.equal(after.requested_day, day);
  assert.equal(
    (await s.state(dispatcher)).orders.filter((o) => o.id === d.order_id).length,
    1,
  );
  (await cmd("plan", { day: d.next_date }));
  const next = (await s.plans()).find((p) => p.day === d.next_date)!;
  assert.ok(
    [
      ...next.routes.flatMap((r) => r.order_ids),
      ...next.deferred.map((d) => d.order_id),
    ].includes(d.order_id),
  );
  assert.equal(
    (await s.plans()).find((p) => p.day === day)!.deferred.length,
    plan.deferred.length,
  );
});

test("workshop vehicles cannot appear in a peak-day plan", async (t) => {
  const { s, cmd } = (await setup(t));
  (await cmd("plan", { day: "2026-02-16" }));
  const plan = (await s.plans()).find((p) => p.day === "2026-02-16")!;
  assert.ok(plan.routes.length > 0);
  assert.ok(
    !plan.routes.some((r) => ["VEH001", "VEH002"].includes(r.vehicle_id)),
  );
});

test("fuel pressure opening consumption persists through the rest of its week", async (t) => {
  const { s } = (await setup(t));
  const opening = (await s.reservations("2026-02-17"));
  assert.ok(Object.keys(opening).length > 0);
  assert.deepEqual((await s.reservations("2026-02-18")), opening);
  assert.deepEqual((await s.reservations("2026-02-22")), opening);
  assert.deepEqual((await s.reservations("2026-02-23")), {});
});

test("route validation rejects unavailable vehicles even with spare capacity", async () => {
  const ref = loadReference();
  const v = ref.vehicles[0];
  const outlet = ref.outlets.find(
    (o) =>
      o.depot === v.depot &&
      o.brand === "Fresh" &&
      o.parking_constraint !== "van_only",
  )!;
  const order = {
    ...outlet,
    id: "A",
    day: "2026-02-14",
    weight: 10,
    volume: 0.1,
    units: 1,
    temp: "ambient",
    status: "confirmed_order",
    version: 0,
    skips: 0,
  } as Order;
  assert.ok(
    validateRoute(
      [order],
      { ...v, status: "in_workshop" },
      ref,
    ).errors.includes("unavailable"),
  );
});

test("assignment preview does not mutate a draft and lists fully validated alternatives", async (t) => {
  const { s, cmd } = (await setup(t));
  (await cmd("plan", { day: "2026-02-14" }));
  const plan = (await s.plans()).find((p) => p.day === "2026-02-14")!;
  const before = JSON.stringify(plan);
  const orderId = plan.deferred[0].order_id;
  const result = (await s.previewAssignments(dispatcher, plan.day, orderId));
  assert.ok(result.length > 0);
  assert.ok(result.every((x) => typeof x.feasible === "boolean"));
  assert.equal(
    JSON.stringify((await s.plans()).find((p) => p.day === plan.day)),
    before,
  );
  (await assert.rejects(
    async () =>
      (await s.previewAssignments(
        { id: "store@waypoint.local", role: "store", scope: "OUT001" },
        plan.day,
        orderId,
      )),
    /Dispatcher/,
  ));
});

test("a stale dispatcher cannot overwrite a newer draft", async (t) => {
  const { s, cmd } = (await setup(t));
  (await cmd("plan", { day: "2026-02-14" }));
  const revision = (await s.plans()).find((p) => p.day === "2026-02-14")!.revision;
  (await cmd("plan", { day: "2026-02-14", revision }));
  const before = JSON.stringify((await s.plans()));
  (await assert.rejects(
    async () => (await cmd("publish", { day: "2026-02-14", revision })),
    /draft changed/,
  ));
  assert.equal(JSON.stringify((await s.plans())), before);
});

test("carryover skips a future run that is already published", async (t) => {
  const { s, cmd } = (await setup(t));
  // Isolate the future run so the fixture does not depend on unrelated S1 decisions.
  (await s.run("DELETE FROM orders WHERE day=$1", "2026-02-16"));
  const base = (await s
    .orders())
    .find(
      (o) =>
        o.day === "2026-02-14" &&
        o.temp === "ambient" &&
        o.parking_constraint !== "van_only",
    )!;
  (await s.save({
    ...base,
    id: "FUTURE-ORDER",
    day: "2026-02-16",
    weight: 10,
    volume: 0.1,
    units: 1,
  }));
  (await cmd("plan", { day: "2026-02-16" }));
  (await cmd("publish", { day: "2026-02-16" }));
  (await cmd("plan", { day: "2026-02-14" }));
  const draft = (await s.plans()).find((p) => p.day === "2026-02-14")!;
  for (const d of draft.deferred)
    if (d.repeat)
      (await cmd("defer_note", {
        day: draft.day,
        order_id: d.order_id,
        note: "Arrange replacement capacity.",
      }));
  (await cmd("publish", { day: draft.day }));
  assert.ok(draft.deferred.length > 0);
  for (const d of draft.deferred)
    assert.equal(
      (await s.orders()).find((o) => o.id === d.order_id)!.day,
      "2026-02-17",
    );
});

test("loading, proof, idempotency and role boundaries preserve one shared record", async (t) => {
  const { s, cmd } = (await setup(t));
  (await cmd("plan", { day: "2026-02-14" }));
  const plan = (await s.plans()).find((p) => p.day === "2026-02-14")!;
  for (const d of plan.deferred)
    if (d.repeat)
      (await cmd("defer_note", {
        day: plan.day,
        order_id: d.order_id,
        note: "Arrange appropriate van capacity.",
      }));
  (await cmd("publish", { day: plan.day }));
  const driver = (await s.login("driver@waypoint.local", "Waypoint2026!")).user;
  const loader = (await s.login("loader@waypoint.local", "Waypoint2026!")).user;
  const store = (await s.login("store@waypoint.local", "Waypoint2026!")).user;
  const target = (await s
    .orders())
    .find(
      (o) =>
        o.day === plan.day &&
        o.vehicle_id === driver.scope &&
        o.outlet_id === store.scope,
    )!;
  const fresh = async () => (await s.orders()).find((o) => o.id === target.id)!;
  let n = 0;
  const action = async (
    user: User,
    kind: string,
    extra: Record<string, unknown> = {},
  ) =>
    (await s.command(user, {
      id: `flow-${++n}`,
      kind,
      order_id: target.id,
      version: (await fresh()).version,
      ...extra,
    }));
  (await assert.rejects(async () => (await action(store, "load")), /role/));
  (await action(loader, "shortfall", { count: 1, note: "Damaged carton" }));
  (await assert.rejects(async () => (await action(driver, "depart")), /current state/));
  (await action(dispatcher, "resolve", { note: "Replaced carton" }));
  assert.equal((await fresh()).status, "planned");
  for (const id of plan.routes.find((r) => r.order_ids.includes(target.id))!
    .order_ids) {
    const o = (await s.orders()).find((o) => o.id === id)!;
    (await s.command(loader, {
      id: `load-${id}`,
      kind: "load",
      order_id: id,
      version: o.version,
    }));
  }
  (await action(driver, "depart"));
  (await action(driver, "arrive"));
  (await assert.rejects(
    async () =>
      (await action(driver, "deliver", {
        outcome: "partial",
        count: target.units,
        photo: "",
        signature: "",
        receiver: "Store",
      })),
    /count/,
  ));
  const beforeVersion = (await fresh()).version;
  const image =
    "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a0XcAAAAASUVORK5CYII=";
  const delivery = {
    id: "same-delivery",
    kind: "deliver",
    order_id: target.id,
    version: beforeVersion,
    outcome: "delivered",
    count: target.units,
    receiver: "Receiver",
    photo: image,
    signature: image,
  };
  const result = (await s.command(driver, delivery));
  assert.deepEqual((await s.command(driver, delivery)), result);
  assert.equal((await fresh()).version, beforeVersion + 1);
  (await assert.rejects(
    async () => (await s.command(driver, { ...delivery, count: 0 })),
    /different data/,
  ));
  (await action(store, "receive"));
  assert.equal((await fresh()).status, "confirmed");
  assert.equal(
    (await s
      .state(store))
      .events.filter((e) => e.kind === "deliver" && e.order_id === target.id)
      .length,
    1,
  );
});

test("published plan snapshots expose only the signed-in account assignments", async (t) => {
  const { s } = (await setup(t));
  const store = {
    id: "store@waypoint.local",
    role: "store",
    scope: "OUT001",
  } as const;
  const state = (await s.state(store));
  assert.ok(state.plans.some((p) => p.published));
  for (const p of state.plans)
    for (const o of p.orders || []) assert.equal(o.outlet_id, "OUT001");
});

test("state carries proof references instead of image data in every snapshot", async (t) => {
  const { s } = (await setup(t));
  const state = await (await s.state(dispatcher));
  assert.ok(state.orders.some((o) => o.proof));
  assert.equal(JSON.stringify(state).includes("data:image/"), false);
});

import test from "node:test";
import assert from "node:assert/strict";
import { Database } from "../lib/database.ts";
import { Service } from "../lib/service.ts";
import { migrate } from "../lib/migrate.ts";
import { createTestDatabase } from "./helpers/database.ts";
import type { User } from "../lib/types.ts";

const dispatcher: User = { id: "dispatcher@waypoint.local", role: "dispatcher", scope: "all" };
const store: User = { id: "store@waypoint.local", role: "store", scope: "OUT001" };
async function setup(t: test.TestContext) {
  process.env.DEMO_MODE = "1";
  process.env.SEED_PASSWORD = "Waypoint2026!";
  const fixture = await createTestDatabase();
  const second = new Database(fixture.url, fixture.schema);
  t.after(async () => { await second.close(); await fixture.close(); });
  const a = new Service(fixture.db), b = new Service(second);
  await a.seed();
  return { a, b, db: fixture.db };
}
// Hold both transactions after their first receipt read, guaranteeing overlapping snapshots.
function synchronizeCommandReads(a: Database, b: Database) {
  let reads = 0, arrivals = 0;
  let release!: () => void;
  const gate = new Promise<void>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("Second transaction never reached the barrier")), 5000);
    release = () => { clearTimeout(timer); resolve(); };
  });
  for (const db of [a, b]) {
    const all = db.all.bind(db);
    let first = true;
    db.all = async (sql, ...params) => {
      const rows = await all(sql, ...params);
      if (sql.startsWith("SELECT * FROM commands")) {
        reads++;
        if (first) {
          first = false;
          if (++arrivals === 2) release();
          await gate;
        }
      }
      return rows;
    };
  }
  return () => reads;
}

const orderCommand = { id: "concurrent-order", kind: "order", temp: "ambient", weight: 10, volume: 0.1, units: 2 };

test("independent connections replay one concurrent command without duplicate order or event", async (t) => {
  const { a, b, db } = await setup(t);
  const reads = synchronizeCommandReads(a.db, b.db);
  const [first, second] = await Promise.all([a.command(store, orderCommand), b.command(store, orderCommand)]);
  assert.deepEqual(first, second);
  assert.ok(reads() > 2, "losing transaction retried with a fresh snapshot");
  assert.equal((await db.get("SELECT count(*)::integer AS n FROM events WHERE order_id=$1", first.order_id))?.n, 1);
  await assert.rejects(b.command(store, { ...orderCommand, units: 3 }), /different data/);
});

test("concurrent draft edits permit one revision and reject stale decisions", async (t) => {
  const { a, b } = await setup(t);
  const command = { kind: "plan", day: "2026-02-14", revision: 0 };
  const results = await Promise.allSettled([a.command(dispatcher, { ...command, id: "draft-a" }), b.command(dispatcher, { ...command, id: "draft-b" })]);
  assert.equal(results.filter((r) => r.status === "fulfilled").length, 1);
  const rejected = results.find((r) => r.status === "rejected") as PromiseRejectedResult;
  assert.match(rejected.reason.message, /draft changed/);
});

test("migrations and concurrent seeds preserve existing records", async (t) => {
  const { a, b, db } = await setup(t);
  const order = await a.command(store, orderCommand);
  await Promise.all([migrate(db), migrate(b.db)]);
  await Promise.all([a.seed(), b.seed()]);
  assert.ok((await b.orders()).some((o) => o.id === order.order_id));
  assert.equal((await db.get("SELECT count(*)::integer AS n FROM users"))?.n, 4);
});

test("login throttling is shared by independent service instances", async (t) => {
  const { a, b } = await setup(t);
  for (let i = 0; i < 15; i++) await (i % 2 ? a : b).checkLoginRate(store.id);
  await assert.rejects(a.checkLoginRate(store.id), /Too many attempts/);
  await assert.rejects(b.checkLoginRate(store.id), /Too many attempts/);
});

test("proof endpoint enforces order scope and state omits image bytes", async (t) => {
  const { a } = await setup(t);
  const state = await a.state(dispatcher);
  const order = state.orders.find((o) => o.proof?.photo_id)!;
  const receiver = { ...store, scope: order.outlet_id };
  assert.ok(order);
  assert.match((await a.proofImage(receiver, order.id, order.proof!.photo_id!)).data, /^data:image\/png;base64,/);
  await assert.rejects(a.proofImage({ ...store, scope: "NOT-ASSIGNED" }, order.id, order.proof!.photo_id!), /outside your assignment/);
  assert.equal(JSON.stringify(await a.state(dispatcher)).includes("data:image/"), false);
});

test("a failed transaction rolls back order, audit event, image and command receipt", async (t) => {
  const { a, db } = await setup(t);
  const counts = async () => {
    const result = [];
    for (const table of ["orders", "events", "proof_images", "commands"]) result.push(await db.get(`SELECT count(*)::integer AS n FROM ${table}`));
    return result;
  };
  const before = await counts();
  await assert.rejects(db.transaction(async () => {
    const result = await a.command(store, orderCommand);
    const image = "data:image/png;base64,iVBORw0KGgo=";
    const order = (await a.orders()).find((o) => o.id === result.order_id)!;
    await a.save({ ...order, proof: { outcome: "delivered", count: 2, note: "", photo: image, signature: image } });
    throw new Error("injected failure before commit");
  }), /injected failure/);
  assert.deepEqual(await counts(), before);
  assert.equal((await db.get("SELECT count(*)::integer AS n FROM commands WHERE id=$1", orderCommand.id))?.n, 0);
  const result = await a.command(store, orderCommand);
  assert.ok(result.order_id);
});

test("simultaneous publications cannot reserve the same weekly fuel twice", async (t) => {
  const { a, b } = await setup(t);
  const base = (await a.orders()).find((o) => o.temp === "ambient" && o.parking_constraint !== "van_only")!;
  const day = "2026-02-23";
  const firstOrder = { ...base, id: "FUEL-RACE-A", day, status: "confirmed_order" as const, version: 0, skips: 0, weight: 10, volume: 0.1, units: 1 };
  delete firstOrder.route_id;
  delete firstOrder.vehicle_id;
  const { allocate } = await import("../lib/domain.ts");
  const route = allocate([firstOrder], a.ref, day).routes[0];
  assert.ok(route && route.fuel > 0);
  const vehicle = a.ref.vehicles.find((v) => v.vehicle_id === route.vehicle_id)!;
  a.ref.vehicles = [{ ...vehicle, weekly_fuel_quota_l: route.fuel * 1.5 }];
  b.ref.vehicles = structuredClone(a.ref.vehicles);
  await a.save(firstOrder);
  await a.save({ ...firstOrder, id: "FUEL-RACE-B", day: "2026-02-24" });
  await a.command(dispatcher, { id: "fuel-draft-a", kind: "plan", day });
  await b.command(dispatcher, { id: "fuel-draft-b", kind: "plan", day: "2026-02-24" });
  const reads = synchronizeCommandReads(a.db, b.db);
  const results = await Promise.allSettled([
    a.command(dispatcher, { id: "fuel-publish-a", kind: "publish", day, revision: 1 }),
    b.command(dispatcher, { id: "fuel-publish-b", kind: "publish", day: "2026-02-24", revision: 1 }),
  ]);
  assert.equal(results.filter((r) => r.status === "fulfilled").length, 1);
  assert.match((results.find((r) => r.status === "rejected") as PromiseRejectedResult).reason.message, /fuel/i);
  assert.ok(reads() > 2, "publication revalidated after serialization retry");
  assert.equal((await a.plans()).filter((p) => p.published && [day, "2026-02-24"].includes(p.day)).length, 1);
});

test("simultaneous order updates accept only one expected version", async (t) => {
  const { a, b } = await setup(t);
  const order = (await a.orders()).find((o) => o.status === "planned")!;
  const loader: User = { id: "loader@waypoint.local", role: "loader", scope: order.depot };
  const payload = { kind: "load", order_id: order.id, version: order.version };
  const results = await Promise.allSettled([
    a.command(loader, { ...payload, id: "load-a" }), b.command(loader, { ...payload, id: "load-b" }),
  ]);
  assert.equal(results.filter((r) => r.status === "fulfilled").length, 1);
  assert.match((results.find((r) => r.status === "rejected") as PromiseRejectedResult).reason.message, /record changed/);
  assert.equal((await a.orders()).find((o) => o.id === order.id)!.version, order.version + 1);
});

test("fresh concurrent seed calls initialize all accounts and scenarios once", async (t) => {
  process.env.SEED_PASSWORD = "Waypoint2026!";
  process.env.DEMO_MODE = "1";
  const fixture = await createTestDatabase();
  const other = new Database(fixture.url, fixture.schema);
  t.after(async () => { await other.close(); await fixture.close(); });
  const a = new Service(fixture.db), b = new Service(other);
  await Promise.all([a.seed(), b.seed()]);
  assert.equal((await a.get("SELECT count(*)::integer AS n FROM users"))?.n, 4);
  assert.equal((await a.get("SELECT count(*)::integer AS n FROM settings WHERE key='scenario_seed_v2'"))?.n, 1);
});


test("carryover racing a destination publication either skips it or invalidates its draft", async (t) => {
  const { a, b } = await setup(t);
  const base = (await a.orders()).find((o) => o.temp === "ambient" && o.parking_constraint !== "van_only")!;
  await a.save({ ...base, id: "CARRY-RACE", day: "2026-02-23", status: "confirmed_order", version: 0, skips: 0, weight: 100000, volume: 0.1, units: 1 });
  await a.save({ ...base, id: "DESTINATION-RACE", day: "2026-02-24", status: "confirmed_order", version: 0, skips: 0, weight: 10, volume: 0.1, units: 1 });
  await a.command(dispatcher, { id: "carry-draft", kind: "plan", day: "2026-02-23" });
  await a.command(dispatcher, { id: "destination-draft", kind: "plan", day: "2026-02-24" });
  const reads = synchronizeCommandReads(a.db, b.db);
  const [carry, destination] = await Promise.allSettled([
    a.command(dispatcher, { id: "carry-publish", kind: "publish", day: "2026-02-23", revision: 1 }),
    b.command(dispatcher, { id: "destination-publish", kind: "publish", day: "2026-02-24", revision: 1 }),
  ]);
  assert.equal(carry.status, "fulfilled");
  assert.ok(reads() > 2, "publication revalidated after serialization retry");
  const order = (await a.orders()).find((o) => o.id === "CARRY-RACE")!;
  assert.equal(order.skips, 1);
  if (destination.status === "fulfilled") assert.equal(order.day, "2026-02-25");
  else {
    assert.match(destination.reason.message, /Orders changed/);
    assert.equal(order.day, "2026-02-24");
    assert.equal((await a.plans()).find((p) => p.day === order.day)!.published, false);
  }
});

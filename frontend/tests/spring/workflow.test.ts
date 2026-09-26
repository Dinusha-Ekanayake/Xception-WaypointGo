import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { startSpring } from "../helpers/spring.ts";
let backend: Awaited<ReturnType<typeof startSpring>>;
const cookies = new Map<string, string>();
before(async () => {
  backend = await startSpring(43221);
  for (const role of ["dispatcher", "store", "loader", "driver"]) {
    const response = await fetch(backend.url + "/api/login", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ email: `${role}@waypoint.local`, password: "Waypoint2026!" }) });
    assert.equal(response.status, 200); cookies.set(role, response.headers.get("set-cookie")!.split(";")[0]);
  }
});
after(async () => { await backend?.close(); });
async function command(role: string, data: Record<string, unknown>) {
  const response = await fetch(backend.url + "/api/command", { method: "POST", headers: { "Content-Type": "application/json", Cookie: cookies.get(role)! }, body: JSON.stringify({ id: crypto.randomUUID(), ...data }) });
  return { status: response.status, data: await response.json() };
}
async function state(role: string) { return (await fetch(backend.url + "/api/state", { headers: { Cookie: cookies.get(role)! } })).json(); }

test("Spring command replay creates one order under concurrent retries", async () => {
  const cmd = { id: crypto.randomUUID(), kind: "order", weight: 10, volume: 1, units: 2, temp: "ambient" };
  const results = await Promise.all(Array.from({ length: 8 }, () => command("store", cmd)));
  for (const result of results) assert.equal(result.status, 200, JSON.stringify(result));
  assert.equal(new Set(results.map(r => r.data.order_id)).size, 1);
  assert.match(results[0].data.order_id, /^ORD-[0-9a-f-]{36}$/);
  const snapshot = await state("store");
  assert.equal(snapshot.events.filter((e: any) => e.order_id === results[0].data.order_id && e.kind === "order").length, 1);
});

test("Spring rejects stale draft and unauthorized commands", async () => {
  const first = await command("dispatcher", { kind: "plan", day: "2026-02-14", revision: 0 });
  assert.equal(first.status, 200);
  assert.equal((await command("dispatcher", { kind: "publish", day: "2026-02-14", revision: 0 })).status, 409);
  assert.equal((await command("store", { kind: "plan", day: "2026-02-14" })).status, 403);
});

test("exception redelivery preserves evidence, links one new order and rejects repeat resolution", async () => {
  const snapshot = await state("dispatcher");
  const original = snapshot.orders.find((o: any) => o.status === "partial");
  assert.ok(original);
  const cmd = { id: crypto.randomUUID(), kind: "resolve_exception", order_id: original.id, version: original.version,
    decision: "redeliver", count: 1, weight: 1, volume: 0.1, note: "Store agreed to a replacement delivery." };
  const result = await command("dispatcher", cmd);
  assert.equal(result.status, 200, JSON.stringify(result));
  assert.deepEqual((await command("dispatcher", cmd)).data, result.data);
  const updated = await state("dispatcher");
  const closed = updated.orders.find((o: any) => o.id === original.id);
  assert.deepEqual(closed.proof, original.proof);
  assert.equal(closed.status, "resolved");
  assert.equal(updated.orders.filter((o: any) => o.parent_order_id === original.id).length, 1);
  assert.equal((await command("dispatcher", { ...cmd, id: crypto.randomUUID() })).status, 409);
});

test("staff provisioning, password reset, assignment changes and disable revoke access", async () => {
  const env = { ACCOUNT_ID: "operator@example.com", ACCOUNT_OPERATOR: "integration-test",
    ACCOUNT_ROLE: "store", ACCOUNT_SCOPE: "OUT001", ACCOUNT_PASSWORD: "Private-test-password!" };
  assert.equal(await backend.cli("account-create", env), 0);
  const signIn = async (password: string) => fetch(backend.url + "/api/login", { method: "POST",
    headers: { "Content-Type": "application/json" }, body: JSON.stringify({ email: env.ACCOUNT_ID, password }) });
  const login = await signIn(env.ACCOUNT_PASSWORD);
  assert.equal(login.status, 200);
  const cookie = login.headers.get("set-cookie")!.split(";")[0];
  assert.equal(await backend.cli("account-password", { ...env, ACCOUNT_PASSWORD: "Replacement-password!" }), 0);
  assert.equal((await fetch(backend.url + "/api/state", { headers: { Cookie: cookie } })).status, 401);
  assert.equal((await signIn(env.ACCOUNT_PASSWORD)).status, 401);
  assert.equal((await signIn("Replacement-password!")).status, 200);
  assert.equal(await backend.cli("account-update", { ...env, ACCOUNT_SCOPE: "OUT002" }), 0);
  const reassigned = await signIn("Replacement-password!");
  assert.equal((await reassigned.json()).scope, "OUT002");
  assert.equal(await backend.cli("account-disable", env), 0);
  assert.equal((await signIn("Replacement-password!")).status, 401);
  assert.notEqual(await backend.cli("account-disable", { ...env, ACCOUNT_ID: "dispatcher@waypoint.local" }), 0);
});

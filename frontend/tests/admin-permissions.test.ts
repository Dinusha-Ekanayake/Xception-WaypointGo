import test from "node:test";
import assert from "node:assert/strict";
import { canManage, defaultTemplates, effectivePermissions, validatePerson, type Person, type ManagedRole } from "../src/roles/admin/permissions.ts";
const driver: Person = { id: "1", name: "Test Driver", email: "driver@example.com", role: "driver", status: "Active", scope: ["VEH014"], from: "2026-09-27", until: "2026-09-30", overrides: {} };
test("admin UI cannot create admins or assign an unknown privilege", () => {
  assert.equal(canManage("admin", "admin"), false);
  assert.equal(canManage("super_admin", "admin"), true);
  assert.equal(canManage("admin", "super_admin" as ManagedRole), false);
  assert.equal(canManage("super_admin", "super_admin" as ManagedRole), false);
  assert.match(validatePerson({ ...driver, role: "admin" }, [], "admin")!, /Only a super admin/);
});
test("individual denies win over template defaults, without granting another persona's permissions", () => {
  const access = effectivePermissions({ ...driver, overrides: { "delivery.proof": false, "orders.place": true } }, defaultTemplates());
  assert.equal(access.includes("delivery.proof"), false);
  assert.equal(access.includes("orders.place"), false);
  assert.equal(access.includes("route.read"), true);
  assert.deepEqual(effectivePermissions({ ...driver, status: "Suspended" }, defaultTemplates()), []);
});
test("driver scope rejects missing dates, impossible dates and inclusive overlap", () => {
  assert.match(validatePerson({ ...driver, from: undefined }, [], "admin")!, /valid assignment/);
  assert.match(validatePerson({ ...driver, from: "2026-02-30" }, [], "admin")!, /valid assignment/);
  const second = { ...driver, id: "2", email: "second@example.com", from: "2026-09-30", until: "2026-10-02" };
  assert.match(validatePerson(second, [driver], "admin")!, /already has a driver/);
  assert.equal(validatePerson({ ...second, from: "2026-10-01" }, [driver], "admin"), null);
});
test("scope cardinality and duplicate email are validated", () => {
  assert.match(validatePerson({ ...driver, role: "loader", scope: ["Kandy", "Peliyagoda"] }, [], "admin")!, /exactly one/);
  assert.match(validatePerson({ ...driver, role: "store_manager", scope: [] }, [], "admin")!, /at least one/);
  assert.match(validatePerson({ ...driver, id: "2", email: "DRIVER@example.com" }, [driver], "admin")!, /already uses/);
});

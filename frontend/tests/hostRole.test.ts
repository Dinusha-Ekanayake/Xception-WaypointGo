import test from "node:test";
import assert from "node:assert/strict";
import { hostForRole, roleForHost } from "../src/app-shell/hostRole.ts";

test("a role address names its role", () => {
  assert.equal(roleForHost("dispatcher.waypointgo.live"), "dispatcher");
  assert.equal(roleForHost("loader.waypointgo.live"), "loader");
  assert.equal(roleForHost("driver.waypointgo.live"), "driver");
  assert.equal(roleForHost("store.waypointgo.live"), "store_manager");
  assert.equal(roleForHost("admin.waypointgo.live"), "admin");
  assert.equal(roleForHost("auditor.waypointgo.live"), "auditor");
  assert.equal(roleForHost("Loader.WaypointGo.live"), "loader");
});

test("every other address is shared by all roles", () => {
  for (const host of ["waypointgo.live", "preview.waypointgo.live", "www.waypointgo.live", "localhost", "127.0.0.1", "loader.live", "constructor.waypointgo.live", ""]) {
    assert.equal(roleForHost(host), null, host);
  }
});

test("a role's address is a sibling of the one being visited", () => {
  assert.equal(hostForRole("loader.waypointgo.live", "store_manager"), "store.waypointgo.live");
  assert.equal(hostForRole("loader.waypointgo.live", "driver"), "driver.waypointgo.live");
});

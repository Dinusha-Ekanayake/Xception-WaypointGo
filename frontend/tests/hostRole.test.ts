import test from "node:test";
import assert from "node:assert/strict";
import { hostForRole, previewHomeFor, roleForHost } from "../src/app-shell/hostRole.ts";

test("a role address names its role", () => {
  assert.equal(roleForHost("dispatcher.waypointgo.live"), "dispatcher");
  assert.equal(roleForHost("loader.waypointgo.live"), "loader");
  assert.equal(roleForHost("driver.waypointgo.live"), "driver");
  assert.equal(roleForHost("store.waypointgo.live"), "store_manager");
  assert.equal(roleForHost("admin.waypointgo.live"), "admin");
  assert.equal(roleForHost("auditor.waypointgo.live"), "auditor");
  assert.equal(roleForHost("Loader.WaypointGo.live"), "loader");
});

test("a preview role address names the same role", () => {
  assert.equal(roleForHost("loader-preview.waypointgo.live"), "loader");
  assert.equal(roleForHost("store-preview.waypointgo.live"), "store_manager");
});

test("every other address is shared by all roles", () => {
  for (const host of ["waypointgo.live", "preview.waypointgo.live", "www.waypointgo.live", "localhost", "127.0.0.1", "loader.live", "constructor.waypointgo.live", "-preview.waypointgo.live", "loader-preview-preview.waypointgo.live", ""]) {
    assert.equal(roleForHost(host), null, host);
  }
});

test("a role's address is a sibling of the one being visited", () => {
  assert.equal(hostForRole("loader.waypointgo.live", "store_manager"), "store.waypointgo.live");
  assert.equal(hostForRole("loader.waypointgo.live", "driver"), "driver.waypointgo.live");
  assert.equal(hostForRole("loader-preview.waypointgo.live", "store_manager"), "store-preview.waypointgo.live");
});

test("the shared preview address sends each role to its own preview address", () => {
  assert.equal(previewHomeFor("preview.waypointgo.live", "loader"), "loader-preview.waypointgo.live");
  assert.equal(previewHomeFor("Preview.WaypointGo.live", "store_manager"), "store-preview.waypointgo.live");
});

test("no other address moves anyone", () => {
  for (const host of ["waypointgo.live", "www.waypointgo.live", "loader-preview.waypointgo.live", "loader.waypointgo.live", "preview.live", "localhost", ""]) {
    assert.equal(previewHomeFor(host, "loader"), null, host);
  }
});

import test from "node:test";
import assert from "node:assert/strict";
import { hostForRole, isPreviewHome, roleForHost, sharedHomeFor, sharedHostFor } from "../src/app-shell/hostRole.ts";

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

test("the shared preview address knows each role's own preview address", () => {
  for (const served of [true, false]) {
    assert.equal(sharedHomeFor("preview.waypointgo.live", "loader", served), "loader-preview.waypointgo.live");
    assert.equal(sharedHomeFor("Preview.WaypointGo.live", "store_manager", served), "store-preview.waypointgo.live");
  }
  assert.equal(isPreviewHome("preview.waypointgo.live"), true);
  assert.equal(isPreviewHome("waypointgo.live"), false);
});

test("production's shared address knows each role's own address where they are served", () => {
  assert.equal(sharedHomeFor("waypointgo.live", "loader", true), "loader.waypointgo.live");
  assert.equal(sharedHomeFor("WaypointGo.live", "store_manager", true), "store.waypointgo.live");
  assert.equal(sharedHomeFor("dispatch.example.com", "driver", true), "driver.dispatch.example.com");
});

test("a shared address keeps every role where the role addresses are not served", () => {
  for (const host of ["waypointgo.live", "dispatch.example.com", "localhost", "127.0.0.1", ""]) {
    assert.equal(sharedHomeFor(host, "loader", false), null, host);
  }
});

test("a role address, www, a bare machine name and a number are never a shared home", () => {
  for (const host of ["loader.waypointgo.live", "loader-preview.waypointgo.live", "www.waypointgo.live", "localhost", "127.0.0.1", "[::1]", "preview.live", ""]) {
    assert.equal(sharedHomeFor(host, "loader", true), null, host);
  }
});

test("a role address leads back to the address every role shares", () => {
  assert.equal(sharedHostFor("dispatcher-preview.waypointgo.live"), "preview.waypointgo.live");
  assert.equal(sharedHostFor("Admin.WaypointGo.live"), "waypointgo.live");
  assert.equal(sharedHostFor("store.waypointgo.live"), "waypointgo.live");
  for (const host of ["preview.waypointgo.live", "waypointgo.live", "localhost"]) assert.equal(sharedHostFor(host), null, host);
});

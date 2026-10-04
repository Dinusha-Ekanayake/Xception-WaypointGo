import assert from "node:assert/strict";
import { existsSync } from "node:fs";
import test from "node:test";
import { appIconFor, manifestFor } from "../src/app-shell/appManifest.ts";

// Each role address installs as its own app (issue #201).

test("each field role address installs as its own app", () => {
  assert.equal(manifestFor("driver.waypointgo.live").name, "Waypoint Driver");
  assert.equal(manifestFor("loader.waypointgo.live").name, "Waypoint Loader");
  assert.equal(manifestFor("store.waypointgo.live").name, "Waypoint Store");
  assert.equal(manifestFor("driver-preview.waypointgo.live").name, "Waypoint Driver");
  assert.equal(manifestFor("store-preview.waypointgo.live").short_name, "Store");
});

test("the shared address and the desk roles install as the generic app", () => {
  for (const host of ["waypointgo.live", "preview.waypointgo.live", "localhost", "dispatcher.waypointgo.live", "admin.waypointgo.live"]) {
    assert.equal(manifestFor(host).name, "Waypoint Dispatch", host);
    assert.equal(appIconFor(host), "waypoint", host);
  }
});

test("a manifest is installable: standalone, scoped to the address, with every icon on disk", () => {
  for (const host of ["driver.waypointgo.live", "loader.waypointgo.live", "store.waypointgo.live", "waypointgo.live"]) {
    const m = manifestFor(host);
    assert.equal(m.display, "standalone");
    assert.equal(m.start_url, "/");
    assert.equal(m.scope, "/");
    assert.equal(m.orientation, undefined, "no role is locked to one orientation");
    const sizes = (m.icons ?? []).map((i) => `${i.sizes} ${i.purpose}`);
    assert.deepEqual(sizes, ["192x192 any", "512x512 any", "512x512 maskable"]);
    for (const icon of m.icons ?? []) assert.ok(existsSync(`public${icon.src}`), icon.src);
    assert.ok(existsSync(`public/icons/app/${appIconFor(host)}-180.png`), "home screen icon for iOS");
  }
});

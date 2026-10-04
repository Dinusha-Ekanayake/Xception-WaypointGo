import { expect, test } from "@playwright/test";
import { serve, stop } from "./mocks.ts";

// Issue #161: Figma 05 Live map view, 05b selection and 05d offline.

const fix = (vehicleId: string, offline: boolean, lat: string) => ({
  vehicleId, tripId: "trip-1", latitude: lat, longitude: "79.900000", headingDeg: "90.0", accuracyM: "12.0",
  recordedAt: "2027-03-01T00:30:00Z", offline,
});

test("the map shows each seen vehicle, filters, selects and greys an offline one", async ({ page }) => {
  await serve(page, {
    sheets: [
      { vehicleId: "VEH043", serviceDate: "2027-03-01", stops: [stop(1, { startedAt: "2027-03-01T00:10:00Z" })] },
      { vehicleId: "VEH044", serviceDate: "2027-03-01", stops: [stop(2, { startedAt: "2027-03-01T00:10:00Z" })] },
      { vehicleId: "VEH045", serviceDate: "2027-03-01", stops: [stop(3)] },
    ],
    positions: [fix("VEH043", false, "7.300000"), fix("VEH044", true, "6.500000")],
    trail: [
      { recordedAt: "2027-03-01T00:20:00Z", latitude: "6.400000", longitude: "79.900000", lowQuality: false },
      { recordedAt: "2027-03-01T00:25:00Z", latitude: null, longitude: "79.900000", lowQuality: false },
      { recordedAt: "2027-03-01T00:30:00Z", latitude: "6.500000", longitude: "79.900000", lowQuality: false },
    ],
  });
  await page.goto("/#/live");
  await page.getByRole("button", { name: "Map", exact: true }).click();

  await expect(page.getByRole("button", { name: /^VEH043, [a-z]/ })).toBeVisible();
  await expect(page.getByRole("button", { name: /^VEH044, offline/ })).toBeVisible();
  // Never placed at a guess: a vehicle with no fix is listed, not drawn.
  await expect(page.getByRole("button", { name: /^VEH045, [a-z]/ })).toHaveCount(0);
  await expect(page.getByText(/No live location · stops only: VEH045/)).toBeVisible();
  // No MAP_TILE_URL in the test server: degrade visibly.
  await expect(page.getByText(/Base map unavailable/)).toBeVisible();

  await page.getByRole("button", { name: "Offline (1)" }).click();
  await expect(page.getByRole("button", { name: /^VEH043, [a-z]/ })).toHaveCount(0);

  await page.getByRole("button", { name: /^VEH044, [a-z]/ }).click();
  const panel = page.getByRole("complementary", { name: "Selected vehicle" });
  await expect(panel).toContainText("Offline");
  await expect(panel).toContainText("Last seen");
  // Both pages are read, and the point with no latitude is skipped rather than drawn at 0,0.
  await expect(panel).toContainText("Location trail · 2 points");
});

test("trucks face their heading, depots are GO pills, and the run path shows only for the chosen vehicle", async ({ page }) => {
  await serve(page, {
    sheets: [
      { vehicleId: "VEH043", serviceDate: "2027-03-01", stops: [stop(1, { startedAt: "2027-03-01T00:10:00Z" })] },
      { vehicleId: "VEH044", serviceDate: "2027-03-01", stops: [stop(2, { startedAt: "2027-03-01T00:10:00Z" })] },
    ],
    positions: [{ ...fix("VEH043", false, "7.300000"), headingDeg: "135.0" }, fix("VEH044", false, "6.500000")],
    trail: [
      { recordedAt: "2027-03-01T00:20:00Z", latitude: "7.200000", longitude: "79.900000", lowQuality: false },
      { recordedAt: "2027-03-01T00:25:00Z", latitude: "7.250000", longitude: "79.900000", lowQuality: false },
    ],
  });
  await page.goto("/#/live");
  await page.getByRole("button", { name: "Map", exact: true }).click();

  const truck = page.getByRole("button", { name: /^VEH043, [a-z]/ });
  await expect(truck).toBeVisible();
  // R-EXE-22: the exact heading, not a compass point.
  await expect(truck.locator("svg")).toHaveAttribute("style", /rotate\(135deg\)/);
  // A small GO pill with the depot named beside it (Figma 189:21746).
  await expect(page.locator(".leaflet-marker-icon").filter({ hasText: /^GO.+ depot$/ }).first()).toBeVisible();

  // No run path until a vehicle is chosen.
  const path = page.locator(".leaflet-overlay-pane path");
  await expect(path).toHaveCount(0);
  await truck.click();
  await expect(path).toHaveCount(1);
  // The trip's start is marked, and the path reaches the truck: two trail points plus where it is now.
  await expect(page.getByRole("complementary", { name: "Selected vehicle" })).toContainText("Location trail · 2 points");
  await expect(page.locator(".leaflet-marker-icon[title^='VEH043 trip start']")).toHaveCount(1);
  // The other vehicle steps back while one is chosen.
  await expect(page.getByRole("button", { name: /^VEH044, [a-z]/ }).locator("> span").first()).toHaveAttribute("style", /opacity:0\.5/);

  await page.getByRole("complementary", { name: "Selected vehicle" }).getByRole("button", { name: /Back/ }).click();
  await expect(path).toHaveCount(0);
});

test("a silent position stream says live updates are paused and keeps polling", async ({ page }) => {
  await page.clock.install();
  await serve(page, {
    sheets: [{ vehicleId: "VEH043", serviceDate: "2027-03-01", stops: [stop(1, { startedAt: "2027-03-01T00:10:00Z" })] }],
    positions: [fix("VEH043", false, "7.300000")],
    streamSilent: true,
  });
  await page.goto("/#/live");
  await page.getByRole("button", { name: "Map", exact: true }).click();
  await expect(page.getByRole("button", { name: /^VEH043, [a-z]/ })).toBeVisible();
  await expect(page.getByText(/Live updates paused/)).toHaveCount(0);
  // EXE-LOC-11: past 45 s of silence the map says so, and the poll still draws the vehicle.
  await page.clock.runFor(50_000);
  await expect(page.getByText(/Live updates paused · refreshing every 15 s/)).toBeVisible();
  await expect(page.getByRole("button", { name: /^VEH043, [a-z]/ })).toBeVisible();
});

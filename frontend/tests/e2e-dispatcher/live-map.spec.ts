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
  await expect(panel).toContainText("Location trail · 2 points");
});

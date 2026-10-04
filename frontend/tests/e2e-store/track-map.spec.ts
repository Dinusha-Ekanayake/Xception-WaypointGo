import { expect, test } from "@playwright/test";
import { mockStore, today } from "./mocks.ts";

// Issue #161: Figma "05 Delivery tracking" map card, for this store's vehicle only.

const fix = (offline: boolean) => ({
  vehicleId: "VEH043", tripId: "trip-1", latitude: "7.270000", longitude: "80.580000", headingDeg: "270.0", accuracyM: "10.0",
  recordedAt: `${today}T00:10:00Z`, offline,
});

test("the map card shows the vehicle live and labels an approximate store", async ({ page }) => {
  await mockStore(page, { positions: [fix(false)] });
  await page.goto("/");
  await page.getByRole("button", { name: "Track delivery" }).first().click();
  const card = page.getByRole("region", { name: "Live map · VEH043" });
  await expect(card).toContainText("Live");
  await expect(card).toContainText("updated");
  await expect(card.getByText(/Your store · Approximate · Kadugannawa/)).toBeVisible();
  await expect(card.getByText(/Base map unavailable/)).toBeVisible();
});

test("an offline vehicle shows when it was last seen, and no fix says so", async ({ page }) => {
  await mockStore(page, { positions: [fix(true)] });
  await page.goto("/");
  await page.getByRole("button", { name: "Track delivery" }).first().click();
  await expect(page.getByRole("region", { name: "Live map · VEH043" })).toContainText("Last seen");
});

test("with no position the card says the vehicle has no live location", async ({ page }) => {
  await mockStore(page);
  await page.goto("/");
  await page.getByRole("button", { name: "Track delivery" }).first().click();
  await expect(page.getByRole("region", { name: "Live map · VEH043" })).toContainText("No live location");
});

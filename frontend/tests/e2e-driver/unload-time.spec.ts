import { expect, test } from "@playwright/test";
import { serve, startTrip, stop } from "./mocks.ts";

// Roadmap T1-04: the next stop says how long it is expected to take to unload,
// from the plan's prediction; a stop with none says nothing extra.

test("the next stop says how long it is expected to take to unload", async ({ page }) => {
  await serve(page, [stop(1, "OUT0101", { expectedServiceMinutes: "17.60" }), stop(2, "OUT0202")]);
  await page.goto("/");
  await startTrip(page);
  await expect(page.getByRole("heading", { name: "OUT0101" })).toBeVisible();
  await expect(page.getByText(/about 18 min to unload/)).toBeVisible();
});

// Roadmap MF-06: with enough earlier stops, a range instead of one number.
test("the next stop's unloading time is a range when the prediction has one", async ({ page }) => {
  await serve(page, [stop(1, "OUT0101", { expectedServiceMinutes: "17.60", expectedServiceLow: "14.60", expectedServiceHigh: "24.20" }), stop(2, "OUT0202")]);
  await page.goto("/");
  await startTrip(page);
  await expect(page.getByRole("heading", { name: "OUT0101" })).toBeVisible();
  await expect(page.getByText(/15 to 24 min to unload/)).toBeVisible();
  await expect(page.getByText(/about 18 min to unload/)).toHaveCount(0);
});

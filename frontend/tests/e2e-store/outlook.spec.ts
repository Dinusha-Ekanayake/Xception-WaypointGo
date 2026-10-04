import { expect, test } from "@playwright/test";
import { mockStore, shift } from "./mocks.ts";

// Issue #224 (R-ML-07): four weeks of delivery days, each with how likely it is
// to be kept, a word before a busy day is chosen and the day to choose instead.

test("choosing a busy day warns and offers the nearest day on track", async ({ page }) => {
  await mockStore(page, { outlook: { [shift(5)]: "BUSY" } });
  await page.goto("/");
  await page.getByRole("button", { name: /Place order for/ }).click();

  const strip = page.getByRole("group", { name: "Delivery day" });
  await expect(strip.getByRole("button")).toHaveCount(28);
  await strip.getByRole("button", { name: /· Busy$/ }).click();

  const warning = page.getByRole("status").filter({ hasText: "your order may move a day" });
  await expect(warning).toContainText("is busy");
  await warning.getByRole("button", { name: /^Deliver / }).click();
  await expect(warning).toHaveCount(0);
  // The day before the busy one: as near, and earlier.
  const chosen = strip.getByRole("button", { pressed: true });
  await expect(chosen).toHaveAccessibleName(/· On track$/);
  await expect(strip.getByRole("button", { name: /· Busy$/ })).toHaveAttribute("aria-pressed", "false");
});

test("a Tech store is pointed to a day a trip already serves its district", async ({ page }) => {
  await mockStore(page, { outlook: { [shift(5)]: "AT_RISK" }, rideAlong: [{ date: shift(7), stopsBooked: 1 }] });
  await page.goto("/");
  await page.getByRole("button", { name: /Place order for/ }).click();

  const strip = page.getByRole("group", { name: "Delivery day" });
  await strip.getByRole("button", { name: /· At risk$/ }).click();
  const warning = page.getByRole("status").filter({ hasText: "is at risk" });
  await warning.getByRole("button", { name: /^Deliver / }).click();
  const shared = page.getByRole("region", { name: "Shared trip" });
  // Now on the shared day, with nothing better to suggest.
  await expect(shared).toHaveCount(0);
  await expect(page.getByRole("status").filter({ hasText: "your order may move a day" })).toHaveCount(0);
});

test("with the outlook down every day can still be chosen and the strip says so", async ({ page }) => {
  await mockStore(page, { outlook: "down" });
  await page.goto("/");
  await page.getByRole("button", { name: /Place order for/ }).click();

  await expect(page.getByRole("status").filter({ hasText: "Outlook unavailable right now" })).toBeVisible();
  const strip = page.getByRole("group", { name: "Delivery day" });
  await strip.getByRole("button").nth(10).click();
  await expect(strip.getByRole("button").nth(10)).toHaveAttribute("aria-pressed", "true");
});

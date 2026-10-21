import { expect, test } from "@playwright/test";
import { LIVE_NOW, liveDay, serve } from "./mocks.ts";

// Item 9 (docs/issues/store-phone/PLAN.md, R-IAM-41): on a trip the dispatcher
// sees each stop's store manager and phone as text, read in one request, and
// there is no Call button anywhere.

test("each stop on the trip shows its store manager and phone as text, with no Call link", async ({ page }) => {
  await page.clock.install({ time: new Date(LIVE_NOW) });
  await serve(page, liveDay());
  const reads: string[] = [];
  page.on("request", (r) => { if (r.url().includes("/api/outlets/")) reads.push(new URL(r.url()).pathname); });
  await page.goto("/#/live");
  await page.getByRole("region", { name: "Needs you" }).getByRole("listitem").filter({ hasText: "OUT010" })
    .getByRole("button", { name: "Open trip" }).click();
  await expect(page.getByRole("heading", { name: /VEH030/ })).toBeVisible();

  await expect(page.getByRole("columnheader", { name: "Store manager" })).toBeVisible();
  await expect(page.getByRole("row", { name: /OUT010/ })).toContainText("Manager OUT010 · no phone on their profile");
  await expect(page.getByRole("row", { name: /OUT012/ })).toContainText("No store manager");
  await expect(page.locator('a[href^="tel:"]')).toHaveCount(0);
  expect(reads.filter((p) => p === "/api/outlets/contacts")).toHaveLength(1);
});

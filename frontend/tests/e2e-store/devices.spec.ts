import { expect, test, type Page } from "@playwright/test";
import { mockStore } from "./mocks.ts";

// Issue #201: the store works on every phone and tablet, either way up. Each
// screen opens from the navigation, nothing is wider than the screen, and the
// actions are not covered by the top bar or the tab bar.

async function fits(page: Page) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(overflow, "nothing is wider than the screen").toBeLessThanOrEqual(0);
}

test("every screen opens and fits, and New order and Receive can be pressed", async ({ page }) => {
  await mockStore(page);
  await page.goto("/");
  await expect(page.getByRole("heading", { level: 1 }).first()).toBeVisible();
  await fits(page);
  // A click fails if anything is drawn over the button, so this is the overlap check.
  await page.getByRole("button", { name: "Receive delivery" }).first().click({ trial: true });

  const nav = page.getByRole("navigation", { name: "Store" });
  for (const [tab, heading] of [["Orders", "Orders"], ["Deliveries", "Deliveries"], ["Issues", "Issues"]] as const) {
    await nav.getByRole("button", { name: new RegExp(`^${tab}`) }).click();
    await expect(page.getByRole("heading", { name: heading, level: 1 })).toBeVisible();
    await fits(page);
    if (tab === "Orders") await page.getByRole("button", { name: "+ New order" }).click({ trial: true });
  }
});

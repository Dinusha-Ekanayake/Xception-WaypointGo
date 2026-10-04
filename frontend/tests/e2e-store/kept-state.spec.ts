import { expect, test } from "@playwright/test";
import { mockStore } from "./mocks.ts";

// UX polish 2: a store tab keeps its filter when the manager goes elsewhere
// and comes back, and after a reload of the page.

test("the Orders filter is kept across tabs and a reload", async ({ page }) => {
  await mockStore(page, { answered: { status: "CONFIRMED", pin: "4827", rowVersion: 2, confirmedAt: new Date().toISOString() }, deferred: true });
  await page.goto("/");
  const nav = page.getByRole("navigation", { name: "Store" });
  await nav.getByRole("button", { name: /^Orders/ }).click();
  await page.getByRole("button", { name: /^Received/ }).click();
  await expect(page.getByRole("button", { name: /^Received/ })).toHaveAttribute("aria-pressed", "true");

  await nav.getByRole("button", { name: /^Home/ }).click();
  await nav.getByRole("button", { name: /^Orders/ }).click();
  await expect(page.getByRole("button", { name: /^Received/ })).toHaveAttribute("aria-pressed", "true");
  await expect(page.getByRole("button", { name: /ORD0092336/ })).toBeVisible();

  await page.reload();
  await page.getByRole("navigation", { name: "Store" }).getByRole("button", { name: /^Orders/ }).click();
  await expect(page.getByRole("button", { name: /^Received/ })).toHaveAttribute("aria-pressed", "true");
});

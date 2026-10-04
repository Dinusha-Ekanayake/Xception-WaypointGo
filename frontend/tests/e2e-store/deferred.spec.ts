import { expect, test } from "@playwright/test";
import { mockStore } from "./mocks.ts";

// Figma "09 Order deferred": the day it was due, the day it now comes, the
// plan's reason. Home carries no warning cards (Figma "02 Home"); the order is
// reached from the Orders list, whose badge counts it until "Got it".

test("a deferred order says where it moved and why, and Got it stops the Orders badge counting it", async ({ page }) => {
  await mockStore(page, { deferred: true });
  await page.goto("/");
  await expect(page.getByText(/was deferred to/)).toHaveCount(0);
  const nav = page.getByRole("navigation", { name: "Store" }).getByRole("button", { name: /^Orders/ });
  await expect(nav).toContainText("1");

  await nav.click();
  await page.getByRole("button", { name: /ORD0092413/ }).first().click();
  await page.getByRole("dialog", { name: "ORD0092413" }).getByRole("button", { name: "Why it moved" }).click();

  await expect(page.getByRole("heading", { name: "Order deferred" })).toBeVisible();
  const card = page.getByRole("region", { name: "Deferred order" });
  await expect(card).toContainText("ORD0092413 · Chilled · 6 units");
  await expect(card).toContainText("Was");
  await expect(card).toContainText("Now");
  await expect(card).toContainText("No cold space");
  await expect(card).toContainText("Deferred orders go first on the next run.");
  await expect(card).toContainText(/Deferred by the dispatcher · .* 16:40/);
  await expect(card.getByRole("button", { name: /call/i })).toHaveCount(0);
  await card.getByRole("button", { name: "Got it" }).click();

  await expect(nav).not.toContainText("1");
  await page.reload();
  await expect(page.getByRole("navigation", { name: "Store" }).getByRole("button", { name: /^Orders/ })).not.toContainText("1");
});

import { expect, test } from "@playwright/test";
import { mockStore } from "./mocks.ts";

// Figma "09 Order deferred": the day it was due, the day it now comes, the
// plan's reason, and "Got it" puts the notice away on this device.

test("a deferred order says where it moved and why, and Got it puts the notice away", async ({ page }) => {
  await mockStore(page, { deferred: true });
  await page.goto("/");
  await expect(page.getByText(/ORD0092413 was deferred to/)).toBeVisible();
  await page.getByRole("button", { name: "Why" }).click();

  await expect(page.getByRole("heading", { name: "Order deferred" })).toBeVisible();
  const card = page.getByRole("region", { name: "Deferred order" });
  await expect(card).toContainText("ORD0092413 · Chilled · 6 cases");
  await expect(card).toContainText("Was");
  await expect(card).toContainText("Now");
  await expect(card).toContainText("No cold space");
  await expect(card).toContainText("Deferred orders go first on the next run.");
  await expect(card).toContainText(/Deferred by the dispatcher · .* 16:40/);
  await expect(card.getByRole("button", { name: /call/i })).toHaveCount(0);
  await card.getByRole("button", { name: "Got it" }).click();

  await expect(page.getByText(/ORD0092413 was deferred to/)).toHaveCount(0);
  await page.reload();
  await expect(page.getByRole("heading", { name: /Good (morning|afternoon|evening)/ })).toBeVisible();
  await expect(page.getByText(/ORD0092413 was deferred to/)).toHaveCount(0);
});

test("the order sheet of a deferred order opens the same page", async ({ page }) => {
  await mockStore(page, { deferred: true });
  await page.goto("/");
  await page.getByRole("button", { name: /^Orders/ }).click();
  await page.getByRole("button", { name: /ORD0092413/ }).first().click();
  await page.getByRole("dialog", { name: "ORD0092413" }).getByRole("button", { name: "Why it moved" }).click();
  await expect(page.getByRole("region", { name: "Deferred order" })).toContainText("No cold space");
});

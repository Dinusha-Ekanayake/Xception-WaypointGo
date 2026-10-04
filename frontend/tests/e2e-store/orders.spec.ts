import { expect, test } from "@playwright/test";
import { mockStore } from "./mocks.ts";

// The Orders list opens on what is still open: received and cancelled orders
// are each a filter away, and All shows everything.

test("the list hides received orders until their filter is chosen", async ({ page }) => {
  await mockStore(page, { answered: { status: "CONFIRMED", pin: "4827", rowVersion: 2, confirmedAt: new Date().toISOString() }, deferred: true });
  await page.goto("/");
  await page.getByRole("navigation", { name: "Store" }).getByRole("button", { name: /^Orders/ }).click();

  const open = page.getByRole("button", { name: /^Open/ });
  await expect(open).toHaveAttribute("aria-pressed", "true");
  await expect(page.getByRole("button", { name: /ORD0092413/ })).toBeVisible();
  await expect(page.getByRole("button", { name: /ORD0092336/ })).toHaveCount(0);

  await page.getByRole("button", { name: /^Received/ }).click();
  await expect(page.getByRole("button", { name: /ORD0092336/ })).toBeVisible();
  await expect(page.getByRole("button", { name: /ORD0092413/ })).toHaveCount(0);

  await page.getByRole("button", { name: /^Cancelled/ }).click();
  await expect(page.getByText("No cancelled orders.")).toBeVisible();

  await page.getByRole("button", { name: /^All/ }).click();
  await expect(page.getByRole("button", { name: /ORD0092336/ })).toBeVisible();
  await expect(page.getByRole("button", { name: /ORD0092413/ })).toBeVisible();
});

test("a planned order shows its day, stop and planned arrival in the list and its sheet (#224)", async ({ page }) => {
  await mockStore(page, { week: true });
  await page.goto("/");
  await page.getByRole("navigation", { name: "Store" }).getByRole("button", { name: /^Orders/ }).click();

  const row = page.getByRole("button", { name: /ORD0092418/ });
  await expect(row).toContainText(/Planned for .+ · stop 4 · planned arrival 06:10/);
  await expect(page.getByRole("button", { name: /ORD0092420/ })).not.toContainText("stop");

  await row.click();
  await expect(page.getByText(/Planned for .+ · stop 4 · planned arrival 06:10/).last()).toBeVisible();
  await expect(page.getByText("Dispatch has put your order on a vehicle for that day.")).toBeVisible();
});

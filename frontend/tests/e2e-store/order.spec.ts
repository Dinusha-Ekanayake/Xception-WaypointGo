import { expect, test } from "@playwright/test";
import { mockStore } from "./mocks.ts";

test("a saved draft comes back, and submitting shows the order sent dialog", async ({ page }) => {
  const { sent } = await mockStore(page);
  await page.goto("/");
  await page.getByRole("button", { name: /Place order for/ }).click();
  await expect(page.getByRole("heading", { name: "Place order" })).toBeVisible();
  await expect(page.getByPlaceholder("Add an item - search by name or SKU")).toBeVisible();

  await page.getByRole("button", { name: "One more Basmati rice 5 kg" }).click();
  await page.getByRole("button", { name: "One more Basmati rice 5 kg" }).click();
  await page.getByRole("button", { name: "Save draft" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Draft saved" })).toBeVisible();

  // Leave and come back: the quantities are still there.
  await page.reload();
  await page.getByRole("button", { name: /Place order for/ }).click();
  await expect(page.getByRole("status").filter({ hasText: "restored" })).toBeVisible();
  await expect(page.getByRole("group", { name: "Basmati rice 5 kg" })).toContainText("2");

  await page.getByRole("button", { name: "Submit order" }).click();
  const dialog = page.getByRole("dialog", { name: "Order sent" });
  await expect(dialog).toContainText("ORD0092412");
  await expect(dialog).toContainText("05:00-07:30");
  await expect(dialog).toContainText("You can change it until");
  await expect(dialog.getByRole("button", { name: "Edit order" })).toBeVisible();
  expect(sent.find((c) => c.kind === "order:Place")).toMatchObject({
    expectedVersion: null,
    payload: { outletId: "OUT085", lines: [{ productId: "Basmati rice 5 kg", quantity: 2 }] },
  });

  // Sent, so the draft is gone.
  await dialog.getByRole("button", { name: "Back to home" }).click();
  await page.reload();
  await page.getByRole("button", { name: /Place order for/ }).click();
  await expect(page.getByRole("status").filter({ hasText: "restored" })).toHaveCount(0);
});

test("the Issues tab lists what was reported about the outlet's orders", async ({ page }) => {
  await mockStore(page);
  await page.goto("/");
  await page.getByRole("button", { name: /^Issues/ }).click();
  await expect(page.getByRole("heading", { name: "Issues" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Short at loading" })).toBeVisible();
  await expect(page.getByText("1 package short at loading")).toBeVisible();
});

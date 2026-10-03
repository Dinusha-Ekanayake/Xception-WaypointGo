import { expect, test } from "@playwright/test";
import { mockStore } from "./mocks.ts";

test("an item added from the catalogue is kept in a draft, and submitting shows the order sent dialog", async ({ page }) => {
  const { sent } = await mockStore(page);
  await page.goto("/");
  await page.getByRole("button", { name: /Place order for/ }).click();
  await expect(page.getByRole("heading", { name: "Place order" })).toBeVisible();

  // "03c Add item" and "03d Item added": the outlet never ordered it, so it is found and added.
  await expect(page.getByRole("group", { name: "Basmati rice 5 kg" })).toHaveCount(0);
  await page.getByRole("searchbox", { name: "Add an item" }).fill("bas");
  await page.getByRole("button", { name: "Add Basmati rice 5 kg" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Basmati rice 5 kg added · 1 case" })).toBeVisible();
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

test("the usual items are listed without a search, which offers only this order's class", async ({ page }) => {
  await mockStore(page);
  await page.goto("/");
  await page.getByRole("button", { name: /Place order for/ }).click();
  await page.getByRole("searchbox", { name: "Add an item" }).fill("milk");
  await expect(page.getByText('Nothing in the ambient catalogue matches "milk".')).toBeVisible();

  await page.getByRole("tab", { name: /Chilled order/ }).click();
  await expect(page.getByRole("group", { name: "Fresh milk 1 L" })).toBeVisible();
  await expect(page.getByRole("list", { name: "Catalogue matches" })).toContainText("In the list");
  // Butter was ordered before but the catalogue no longer has it, so it is not offered.
  await expect(page.getByRole("group", { name: "Butter 200 g" })).toHaveCount(0);
});

test("the Issues tab lists what was reported about the outlet's orders", async ({ page }) => {
  await mockStore(page);
  await page.goto("/");
  await page.getByRole("button", { name: /^Issues/ }).click();
  await expect(page.getByRole("heading", { name: "Issues" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "1 package of ORD0092336 (Chilled)" })).toBeVisible();
  await expect(page.getByText("Short delivery")).toBeVisible();
  await expect(page.getByText(/Reported by the loader at/)).toBeVisible();
});

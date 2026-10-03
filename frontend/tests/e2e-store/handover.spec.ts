import { expect, test } from "@playwright/test";
import { mockStore } from "./mocks.ts";

// The handover PIN on the store's side (R-RCP-09): shown once with the answer,
// replaced when it expires or locks, and never a condition of the count.

test("an expired PIN is replaced by a new one, online", async ({ page }) => {
  const { sent, handover } = await mockStore(page);
  await page.goto("/");
  await page.getByRole("button", { name: "Receive delivery" }).first().click();
  await page.getByRole("button", { name: "Submit count" }).click();

  const dialog = page.getByRole("dialog", { name: "Enter this PIN on the driver's phone" });
  await expect(dialog.getByLabel("PIN 4 8 2 7")).toBeVisible();
  await expect(dialog).toContainText("Waiting for the driver");

  handover.current = { ...handover.current!, status: "EXPIRED" };
  await expect(dialog).toContainText("Expired", { timeout: 10_000 });
  await dialog.getByRole("button", { name: "New PIN" }).click();
  await expect(dialog.getByLabel("PIN 0 5 1 9")).toBeVisible();
  expect(sent.find((c) => c.kind === "receipt:ReissueHandoverPin")).toMatchObject({ expectedVersion: 1, payload: { orderId: "order-1" } });
});

test("closing the PIN leaves the count on record", async ({ page }) => {
  const { sent } = await mockStore(page);
  await page.goto("/");
  await page.getByRole("button", { name: "Receive delivery" }).first().click();
  await page.getByRole("button", { name: "Submit count" }).click();
  const dialog = page.getByRole("dialog", { name: "Enter this PIN on the driver's phone" });
  await expect(dialog).toContainText("Your count is already on record");
  await dialog.getByRole("button", { name: "Close" }).click();
  await expect(dialog).toHaveCount(0);
  expect(sent.map((c) => c.kind)).toEqual(["receipt:Confirm"]);
});

test("a receipt answered earlier shows where its PIN stands and can get a new one", async ({ page }) => {
  const { sent } = await mockStore(page, { answered: { status: "LOCKED", pin: "4827", rowVersion: 3, confirmedAt: null } });
  await page.goto("/");
  await page.getByRole("button", { name: /^Orders/ }).click();
  // A received order is under its own filter: the list opens on what is still open.
  await page.getByRole("button", { name: /^Received/ }).click();
  await page.getByRole("button", { name: /ORD0092336/ }).first().click();
  await page.getByRole("button", { name: "Receipt and handover PIN" }).click();

  const card = page.getByRole("region", { name: "Handover" });
  await expect(card).toContainText("Locked after five wrong entries");
  await card.getByRole("button", { name: "Get a new PIN" }).click();
  await expect(page.getByRole("dialog", { name: "Enter this PIN on the driver's phone" }).getByLabel("PIN 0 5 1 9")).toBeVisible();
  expect(sent.find((c) => c.kind === "receipt:ReissueHandoverPin")).toMatchObject({ expectedVersion: 3 });
});

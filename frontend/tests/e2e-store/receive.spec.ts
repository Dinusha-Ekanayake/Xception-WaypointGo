import { expect, test } from "@playwright/test";
import { mockStore } from "./mocks.ts";

test("home shows the next delivery, its shortfall and the Issues count", async ({ page }) => {
  await mockStore(page);
  await page.goto("/");

  await expect(page.getByRole("heading", { name: /Good (morning|afternoon|evening), Nuwan Perera/ })).toBeVisible();
  await expect(page.getByText("VEH043").first()).toBeVisible();
  await expect(page.getByText("Rashmika Dilshan")).toBeVisible();
  await expect(page.getByText(/stop 3 of 7/)).toBeVisible();
  await expect(page.getByText("Short at loading").first()).toBeVisible();
  await expect(page.getByRole("button", { name: /Issues/ })).toContainText("1");
});

test("a damaged line lowers the count, is confirmed as partial and raises an issue", async ({ page }) => {
  const { sent, handover } = await mockStore(page);
  await page.goto("/");

  await page.getByRole("button", { name: "Receive delivery" }).first().click();
  await expect(page.getByRole("heading", { name: "Receive delivery" })).toBeVisible();
  await expect(page.getByText("VEH043 is at your rear dock")).toBeVisible();

  await page.getByRole("button", { name: /Fresh milk 1 L/ }).click();
  await page.getByRole("radio", { name: "Damaged" }).click();
  await page.getByRole("button", { name: "Add issue" }).click();
  await expect(page.getByRole("heading", { name: /Reported · 1/ })).toBeVisible();

  await page.getByRole("button", { name: "Submit count" }).click();
  // The answer carries the one-time PIN; the driver types it on their phone (R-RCP-09).
  const pin = page.getByRole("dialog", { name: "Enter this PIN on the driver's phone" });
  await expect(pin.getByLabel("PIN 4 8 2 7")).toBeVisible();
  handover.current = { ...handover.current!, status: "CONFIRMED", confirmedAt: new Date().toISOString() };
  await expect(page.getByRole("dialog", { name: "Delivery confirmed" })).toBeVisible({ timeout: 10_000 });
  await expect(page.getByRole("dialog")).toContainText("4 units received");
  await expect(page.getByRole("dialog")).toContainText("Confirmed with PIN");

  const receipt = sent.find((c) => c.kind === "receipt:ConfirmPartial");
  expect(receipt).toMatchObject({
    expectedVersion: 1,
    payload: { orderId: "order-1", lines: [{ productId: "Fresh milk 1 L", receivedQuantity: 2 }, { productId: "Butter 200 g", receivedQuantity: 2 }] },
  });
  expect(sent.find((c) => c.kind === "issue:Raise")).toMatchObject({
    payload: { type: "DAMAGED_GOODS", depotCode: "KDY", outletId: "OUT085", subjects: [{ type: "order", id: "order-1" }, { type: "receipt", id: "rcp-1" }] },
  });
});

test("with nothing wrong the count is confirmed as it arrived", async ({ page }) => {
  const { sent, handover } = await mockStore(page);
  await page.goto("/");
  await page.getByRole("button", { name: "Receive delivery" }).first().click();

  await page.getByRole("button", { name: "Submit count" }).click();
  await expect(page.getByRole("dialog", { name: "Enter this PIN on the driver's phone" })).toBeVisible();
  handover.current = { ...handover.current!, status: "CONFIRMED", confirmedAt: new Date().toISOString() };
  await expect(page.getByRole("dialog", { name: "Delivery confirmed" })).toContainText("5 units received", { timeout: 10_000 });
  expect(sent.map((c) => c.kind)).toEqual(["receipt:Confirm"]);
});

test("a dispute needs a reason", async ({ page }) => {
  const { sent } = await mockStore(page);
  await page.goto("/");
  await page.getByRole("button", { name: "Receive delivery" }).first().click();

  await page.getByRole("button", { name: "Something else is wrong" }).click();
  const send = page.getByRole("button", { name: "Send dispute" });
  await expect(send).toBeDisabled();
  await page.getByLabel("What is wrong? (required)").fill("Wrong pallet");
  await send.click();
  expect(sent.find((c) => c.kind === "receipt:Dispute")).toMatchObject({ payload: { orderId: "order-1", reason: "Wrong pallet" } });
});

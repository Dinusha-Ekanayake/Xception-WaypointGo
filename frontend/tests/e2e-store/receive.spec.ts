import { expect, test } from "@playwright/test";
import { mockStore, PNG } from "./mocks.ts";

test("home shows the next delivery, its shortfall and the Issues count", async ({ page }) => {
  await mockStore(page);
  await page.goto("/");

  await expect(page.getByRole("heading", { name: /Good (morning|afternoon|evening), Nuwan Perera/ })).toBeVisible();
  await expect(page.getByText("VEH043").first()).toBeVisible();
  await expect(page.getByText("Rashmika Dilshan")).toBeVisible();
  await expect(page.getByText(/stop 3 of 7/)).toBeVisible();
  // "Shortage notice" of "02 Home": a grey card in the next delivery, not a yellow warning.
  await expect(page.getByText("1 package short - 4 of 5 coming")).toBeVisible();
  await expect(page.getByText("Reported at loading · comes next delivery")).toBeVisible();
  await expect(page.getByRole("button", { name: /Issues/ })).toContainText("1");
});

test("a damaged line with a photo lowers the count and is one report: the receipt's note, with the photo", async ({ page }) => {
  const { sent, handover, uploads } = await mockStore(page);
  await page.goto("/");

  await page.getByRole("button", { name: "Receive delivery" }).first().click();
  await expect(page.getByRole("heading", { name: "Receive delivery" })).toBeVisible();
  await expect(page.getByText("VEH043 is at your rear dock")).toBeVisible();

  await page.getByRole("button", { name: /Fresh milk 1 L/ }).click();
  await page.getByRole("radio", { name: "Damaged" }).click();
  await page.getByLabel("Take a photo of the problem").setInputFiles({ name: "carton.png", mimeType: "image/png", buffer: PNG });
  const photo = page.getByRole("dialog", { name: "Package photo" });
  await expect(photo).toBeVisible();
  await photo.getByRole("button", { name: "Close" }).click();
  await page.getByRole("button", { name: "Add issue" }).click();
  await expect(page.getByText("Fresh milk 1 L · 1 · 1 photo")).toBeVisible();
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
    payload: {
      orderId: "order-1",
      lines: [{ productId: "Fresh milk 1 L", receivedQuantity: 2 }, { productId: "Butter 200 g", receivedQuantity: 2 }],
      note: "Damaged: Fresh milk 1 L x1. 1 photo.",
    },
  });
  expect(sent.some((c) => c.kind === "issue:Raise"), "one report: Issues opens the investigation from the note").toBe(false);
  await expect.poll(() => uploads.length).toBe(1);
  expect(uploads[0]).toMatch(/^\/api\/issues\/attachments\/[0-9a-f-]+\?order=order-1&receipt=rcp-1$/);
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

test("what the loader kept back is marked on its item, lowers the count and is not reported again", async ({ page }) => {
  const { sent, handover } = await mockStore(page, { loadingShort: true });
  await page.goto("/");
  await page.getByRole("button", { name: "Receive delivery" }).first().click();

  await expect(page.getByRole("button", { name: /Butter 200 g/ })).toContainText("Short at loading (1)");
  await expect(page.getByRole("region", { name: "Reported" })).toContainText("Short at loading");
  await expect(page.getByText("Nothing new for the dispatcher")).toBeVisible();

  await page.getByRole("button", { name: "Submit count" }).click();
  await expect(page.getByRole("dialog", { name: "Enter this PIN on the driver's phone" })).toBeVisible();
  handover.current = { ...handover.current!, status: "CONFIRMED", confirmedAt: new Date().toISOString() };
  await expect(page.getByRole("dialog", { name: "Delivery confirmed" })).toContainText("4 units received", { timeout: 10_000 });
  expect(sent.find((c) => c.kind === "receipt:ConfirmPartial")).toMatchObject({
    payload: { lines: [{ productId: "Fresh milk 1 L", receivedQuantity: 3 }, { productId: "Butter 200 g", receivedQuantity: 1 }], note: null },
  });
});

test("adding an issue says what is missing: a package, then a type", async ({ page }) => {
  await mockStore(page);
  await page.goto("/");
  await page.getByRole("button", { name: "Receive delivery" }).first().click();

  // The card's own alert, not Next's route announcer, which also has the role.
  const alert = page.getByRole("region", { name: "Report an issue" }).getByRole("alert");
  await page.getByRole("button", { name: "Add issue" }).click();
  await expect(alert).toHaveText("Choose a package first.");
  await page.getByRole("button", { name: /Fresh milk 1 L/ }).click();
  await page.getByRole("button", { name: "Add issue" }).click();
  await expect(alert).toHaveText("Choose an issue type.");
});

test("a remark on a complete delivery confirms it and raises one issue with the photo", async ({ page }) => {
  const { sent, handover } = await mockStore(page);
  await page.goto("/");
  await page.getByRole("button", { name: "Receive delivery" }).first().click();
  await page.getByRole("button", { name: /Butter 200 g/ }).click();
  await page.getByRole("radio", { name: "Other" }).click();
  await page.getByLabel("Take a photo of the problem").setInputFiles({ name: "seal.png", mimeType: "image/png", buffer: PNG });
  await page.getByRole("dialog", { name: "Package photo" }).getByRole("button", { name: "Close" }).click();
  await page.getByRole("button", { name: "Add issue" }).click();
  await page.getByRole("button", { name: "Submit count" }).click();
  await expect(page.getByRole("dialog", { name: "Enter this PIN on the driver's phone" })).toBeVisible();
  handover.current = { ...handover.current!, status: "CONFIRMED", confirmedAt: new Date().toISOString() };
  await expect(page.getByRole("dialog", { name: "Delivery confirmed" })).toBeVisible({ timeout: 10_000 });

  expect(sent.map((c) => c.kind)).toEqual(["receipt:Confirm", "issue:Raise"]);
  const raise = sent[1]!.payload as { type: string; description: string; attachmentIds: string[] };
  expect(raise.type).toBe("OTHER");
  expect(raise.description).toBe("ORD0092336: Other: Butter 200 g. 1 photo.");
  expect(raise.attachmentIds).toHaveLength(1);
});

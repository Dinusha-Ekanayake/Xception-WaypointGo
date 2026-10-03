import { expect, test } from "@playwright/test";
import { mockStore, PNG } from "./mocks.ts";

// Figma "08 Issues", "08b3 Report issue: details" and "08c Issue sent": a problem
// found after unpacking, reported with its order, item, quantity and a photo.

test("damaged items found after unpacking are sent to the dispatcher with a photo", async ({ page }) => {
  const { sent, uploads } = await mockStore(page);
  await page.goto("/");
  await page.getByRole("button", { name: /^Issues/ }).click();

  await page.getByRole("button", { name: "Damaged items" }).click();
  const dialog = page.getByRole("dialog", { name: "Report an issue" });
  await expect(dialog.getByRole("radio", { name: "Damaged items" })).toHaveAttribute("aria-checked", "true");
  await dialog.getByLabel("Package").selectOption("Fresh milk 1 L");
  await dialog.getByRole("button", { name: "One more Cases affected" }).click();
  await dialog.getByLabel("Comment (optional)").fill("carton wet");
  await dialog.getByLabel("Take a photo of the problem").setInputFiles({ name: "wet.png", mimeType: "image/png", buffer: PNG });
  await page.getByRole("dialog", { name: "Package photo" }).getByRole("button", { name: "Close" }).click();
  await dialog.getByRole("button", { name: "Send to dispatcher" }).click();

  await expect(page.getByRole("dialog", { name: "Issue sent" })).toBeVisible();
  const raise = sent.find((c) => c.kind === "issue:Raise")!.payload as Record<string, unknown>;
  expect(raise).toMatchObject({
    type: "DAMAGED_GOODS",
    depotCode: "KDY",
    outletId: "OUT085",
    subjects: [{ type: "order", id: "order-1" }],
    description: "ORD0092336: Damaged: Fresh milk 1 L x2. 1 photo. Note: carton wet.",
  });
  expect(raise.attachmentIds).toHaveLength(1);
  await expect.poll(() => uploads.length).toBe(1);
  expect(uploads[0]).toMatch(/\?order=order-1$/);
});

test("missing items are raised as other, which the store may raise, with the words saying missing", async ({ page }) => {
  const { sent } = await mockStore(page);
  await page.goto("/");
  await page.getByRole("button", { name: /^Issues/ }).click();
  await page.getByRole("button", { name: "Report an issue" }).click();
  const dialog = page.getByRole("dialog", { name: "Report an issue" });
  await dialog.getByRole("radio", { name: "Missing items" }).click();
  await dialog.getByRole("button", { name: "Send to dispatcher" }).click();
  await expect(page.getByRole("dialog", { name: "Issue sent" })).toBeVisible();
  expect(sent.find((c) => c.kind === "issue:Raise")!.payload).toMatchObject({ type: "OTHER", description: "ORD0092336: Missing: Fresh milk 1 L x1." });
});

test("nothing is sent until what is wrong is chosen", async ({ page }) => {
  const { sent } = await mockStore(page);
  await page.goto("/");
  await page.getByRole("button", { name: /^Issues/ }).click();
  await page.getByRole("button", { name: "Report an issue" }).click();
  const dialog = page.getByRole("dialog", { name: "Report an issue" });
  await dialog.getByRole("button", { name: "Send to dispatcher" }).click();
  await expect(dialog).toContainText("Choose what is wrong.");
  expect(sent.filter((c) => c.kind === "issue:Raise")).toHaveLength(0);
});

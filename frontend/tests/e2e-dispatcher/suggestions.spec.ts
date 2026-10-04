import { expect, test } from "@playwright/test";
import { LIVE_NOW, liveDay, serve } from "./mocks.ts";

// Issue #269, first slice: each thing under "Needs you" carries the playbook's
// steps and a message already filled from the trip and the store.

test.beforeEach(async ({ page }) => {
  await page.clock.install({ time: new Date(LIVE_NOW) });
  await serve(page, liveDay());
  await page.goto("/#/live");
});

test("a failed stop shows its suggested steps and a message written to that store", async ({ page }) => {
  const card = page.getByRole("region", { name: "Needs you" }).getByRole("listitem").filter({ hasText: "OUT010 · not delivered" }).first();
  await card.getByText("Suggested steps").click();
  await expect(card).toContainText("Open the trip and read why the driver could not deliver.");
  await expect(card).toContainText("The delivery to OUT010 on");
  await expect(card.getByRole("button", { name: "Write to the store" })).toBeEnabled();
});

test("a closing window keeps Notify store as its message and adds the steps", async ({ page }) => {
  const card = page.getByRole("region", { name: "Needs you" }).getByRole("listitem").filter({ hasText: "may miss its window" }).first();
  await card.getByText("Suggested steps").click();
  await expect(card).toContainText("Tell the store the new arrival time");
  await expect(card.getByRole("button", { name: "Write to the store" })).toHaveCount(0);
  await expect(card.getByRole("button", { name: "Notify store" })).toBeEnabled();
});

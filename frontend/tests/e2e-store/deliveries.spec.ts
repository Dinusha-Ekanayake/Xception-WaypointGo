import { expect, test } from "@playwright/test";
import { mockStore } from "./mocks.ts";

// Figma "05a Deliveries" and "05b make-up delivery": a row per vehicle's visit,
// the orders not on a vehicle yet, the days ahead and the past week's counts.

test("today is a row per vehicle, and a make-up delivery opens its drawer", async ({ page }) => {
  await mockStore(page, { week: true });
  await page.goto("/");
  await page.getByRole("button", { name: /^Deliveries/ }).click();

  const today = page.getByRole("region", { name: "Today" });
  const run = today.getByRole("article", { name: "VEH043" });
  await expect(run).toContainText("Refrigerated vehicle");
  await expect(run).toContainText("Regular delivery · 1 order · 5 units");
  await expect(run).toContainText("Stop 3 of 7 · 1 unit short at loading");
  await expect(run).toContainText("Delivered · to receive");
  await expect(run.getByRole("button", { name: "Receive" })).toBeVisible();

  const makeUp = today.getByRole("article", { name: "ORD0092418" });
  await expect(makeUp).toContainText("Make-up delivery · 2 units");
  await expect(makeUp).toContainText("Scheduled");
  await makeUp.getByRole("button", { name: "Details" }).click();

  const drawer = page.getByRole("dialog", { name: "Make-up delivery" });
  await expect(drawer).toContainText("Yoghurt 80 g");
  await expect(drawer.getByRole("listitem").filter({ hasText: "Short reported" })).toContainText("(done)");
  await expect(drawer.getByRole("listitem").filter({ hasText: "Make-up booked" })).toContainText("(done)");
  await expect(drawer.getByRole("listitem").filter({ hasText: "You confirm what arrived" })).toContainText("(to come)");
  await expect(drawer.getByRole("button", { name: /call/i })).toHaveCount(0);
  await drawer.getByRole("button", { name: "Close" }).first().click();
  await expect(drawer).toBeHidden();
});

test("upcoming days wait for the plan, and the past week shows the store's count", async ({ page }) => {
  await mockStore(page, { week: true });
  await page.goto("/");
  await page.getByRole("button", { name: /^Deliveries/ }).click();

  await page.getByRole("tab", { name: /Upcoming/ }).click();
  const next = page.getByRole("region", { name: "Upcoming" }).getByRole("article");
  await expect(next).toContainText("ORD0092420 Ambient");
  await expect(next).toContainText("Waiting for plan");

  await page.getByRole("tab", { name: /Past 7 days/ }).click();
  const past = page.getByRole("region", { name: "Past 7 days" });
  await expect(past).toContainText("1 delivery · 0 open issues");
  const run = past.getByRole("article", { name: "VEH040" });
  await expect(run).toContainText("Delivered · 1 order · 2 units · signed 05:55");
  await expect(run).toContainText("Confirmed · 1 short");
  await expect(run).toContainText("1 unit short on your count");
});

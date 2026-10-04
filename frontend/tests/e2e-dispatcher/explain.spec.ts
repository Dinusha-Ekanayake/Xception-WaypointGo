import { expect, test } from "@playwright/test";
import { draftPlan, serve } from "./mocks.ts";

// Issue #267: a deferred order explains itself in a pop-up, from the plan's own facts.

test("Explain this decision says why the order was not placed and what can be done", async ({ page }) => {
  await serve(page, { draft: draftPlan() });
  await page.goto("/#/plan");
  await page.getByRole("tab", { name: /Publish/ }).click();
  await page.getByRole("button", { name: "Decide now" }).click();

  const decision = page.getByRole("region", { name: "Decision", exact: true });
  await decision.getByRole("button", { name: "Explain this decision" }).click();

  const sheet = page.getByRole("dialog", { name: "Why this order was not placed" });
  await expect(sheet).toContainText("ORD0092303");
  await expect(sheet).toContainText("was not placed on the plan for");
  await expect(sheet.getByRole("heading", { name: "The reason" })).toBeVisible();
  await expect(sheet.getByRole("heading", { name: "What you can do" })).toBeVisible();

  await page.keyboard.press("Escape");
  await expect(sheet).toHaveCount(0);
});

test("Explain this plan says what the plan carries and why orders were left off", async ({ page }) => {
  await serve(page, { draft: draftPlan() });
  await page.goto("/#/plan");
  await page.getByRole("button", { name: "Explain this plan" }).click();

  const sheet = page.getByRole("dialog", { name: "This plan explained" });
  await expect(sheet).toContainText("placed on");
  await expect(sheet.getByRole("heading", { name: "Orders left off, and why" })).toBeVisible();
  await expect(sheet.getByRole("heading", { name: "What comes next" })).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(sheet).toHaveCount(0);
});

test("each deferred order on the plan view has a question mark that says why it was not placed", async ({ page }) => {
  await serve(page, { draft: draftPlan() });
  await page.goto("/#/plan");
  await page.getByRole("tab", { name: /View plan/ }).click();

  const deferred = page.getByRole("region", { name: "Deferred orders" });
  await deferred.getByRole("button", { name: /^Why .* was not placed$/ }).first().click();

  const sheet = page.getByRole("dialog", { name: "Why this order was not placed" });
  await expect(sheet.getByRole("heading", { name: "The reason" })).toBeVisible();
  await expect(sheet).toContainText("Open the order in Decide");
  await page.keyboard.press("Escape");
  await expect(sheet).toHaveCount(0);
});

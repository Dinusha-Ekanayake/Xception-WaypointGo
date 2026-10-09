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

// Roadmap AI-2: with no place left, the solver says where the order came closest and the smallest change.
test("an order that fits nowhere says where it came closest and the smallest change", async ({ page }) => {
  await serve(page, {
    draft: draftPlan(),
    noPlace: true,
    fit: {
      orderId: "order-3", orderRef: "ORD0092303", outletId: "OUT053", fits: false, placesChecked: 2,
      closest: { vehicleId: "VEH043", tripNumber: 1, joins: true, failed: [{ ruleId: "R-PLN-06", passed: false, reason: "volume 7.9 m³ exceeds 2.5 m³ free", slack: "-5.4" }] },
      swap: { orderId: "order-1", orderRef: "ORD0092301", outletId: "OUT051", vehicleId: "VEH043", tripNumber: 1 },
    },
  });
  await page.goto("/#/plan");
  await page.getByRole("tab", { name: /Publish/ }).click();
  await page.getByRole("button", { name: "Decide now" }).click();
  await page.getByRole("region", { name: "Decision", exact: true }).getByRole("button", { name: "Explain this decision" }).click();

  const sheet = page.getByRole("dialog", { name: "Why this order was not placed" });
  await expect(sheet.getByTestId("fit-closest")).toContainText("VEH043, trip 1, joining the trip already planned.");
  await expect(sheet.getByTestId("fit-closest")).toContainText("volume 7.9 m³ exceeds 2.5 m³ free");
  await expect(sheet.getByTestId("fit-change")).toHaveText("Swapping it with ORD0092301 for OUT051, which ranks lower, makes room on VEH043, trip 1: every rule passes with that swap.");
});

// Roadmap AI-1: what a vehicle back from the workshop would change, from two runs on the server.
test("the Decide step shows what a vehicle back from the workshop would change", async ({ page }) => {
  await serve(page, {
    draft: draftPlan(),
    whatIfVehicles: [{ vehicleId: "VEH001", van: false, refrigerated: true, volumeCapM3: "26.4" }],
    whatIf: {
      planId: "plan-v1", vehicleId: "VEH001", servedBefore: 2, servedAfter: 3, chilledM3Before: "4.5", chilledM3After: "12.4",
      vehiclesBefore: 1, vehiclesAfter: 2, tripsBefore: 1, tripsAfter: 2,
      nowServed: [{ orderId: "order-3", orderRef: "ORD0092303", outletId: "OUT053", brand: "Fresh", temperature: "chilled", volumeM3: "7.9" }],
      nowDeferred: [],
    },
  });
  await page.goto("/#/plan");
  const card = page.getByRole("region", { name: "What if a vehicle comes back" });
  await card.getByLabel("Vehicle").selectOption("VEH001");
  await card.getByRole("button", { name: "Show the difference" }).click();
  const answer = card.getByTestId("whatif-answer");
  await expect(answer).toContainText("3 orders are served instead of 2, and 12.4 m³ of chilled goods instead of 4.5 m³, on 2 vehicles instead of 1.");
  await expect(answer).toContainText("Now served: ORD0092303 for OUT053 (Fresh, chilled, 7.9 m³)");
});

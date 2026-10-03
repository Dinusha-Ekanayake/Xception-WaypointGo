import { expect, test } from "@playwright/test";
import { DEPOT, draftPlan, serve } from "./mocks.ts";

test("generate, see why an order was deferred, place it by hand, publish", async ({ page }) => {
  const desk = await serve(page);
  await page.goto("/#/plan");
  await expect(page.getByRole("heading", { name: `No plan for ${DEPOT}` })).toBeVisible();
  await expect(page.getByText("3 orders are confirmed and waiting to be planned.")).toBeVisible();

  await page.getByRole("button", { name: "Generate draft" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Draft version 1: 2 placed, 1 deferred." })).toBeVisible();
  expect(desk.commands[0]).toMatchObject({ kind: "plan:Generate", expectedVersion: null, payload: { depotCode: DEPOT } });
  // Issue #92: what the engine's second pass achieved is said on the plan.
  await expect(page.getByText("Refrigerated vehicles planned again: 1 more order served")).toBeVisible();
  await expect(page.getByText("Deferred went from 2 to 1, with 7.9 m³ more chilled delivered")).toBeVisible();

  // The deferral names its rule and its reason, never a generic message; every check is one click away.
  const decision = page.getByRole("region", { name: "Decision", exact: true });
  await expect(decision.getByRole("heading", { name: "ORD0092303" })).toBeVisible();
  await expect(decision).toContainText("R-PLN-06");
  await expect(decision).toContainText("No refrigerated vehicle has 7.9 m³ free");
  await decision.getByText("All 2 checks").click();
  await expect(decision).toContainText("slack -5.4");

  // Only places the server says are feasible can be chosen; the refused one says which rule refuses it.
  await expect(decision.getByRole("radio")).toHaveCount(1);
  await decision.getByRole("button", { name: "Show 1 place it does not fit" }).click();
  await expect(decision).toContainText("volume 7.9 m³ exceeds 2.5 m³ free");
  await decision.getByRole("radio", { name: /VEH044 · Trip 1/ }).click();
  const place = decision.getByRole("button", { name: "Place on VEH044 trip 1" });
  await expect(place).toBeDisabled();
  await decision.getByLabel("Why are you placing it by hand?").fill("Outlet has waited two days");
  await place.click();

  await expect(page.getByRole("heading", { name: "Every order is on a trip" })).toBeVisible();
  expect(desk.commands[1]).toMatchObject({
    kind: "plan:Override",
    expectedVersion: 1,
    payload: { planId: "plan-v1", orderId: "order-3", vehicleId: "VEH044", tripNumber: 1, reason: "Outlet has waited two days" },
  });

  await page.getByRole("tab", { name: /View plan/ }).click();
  await expect(page.getByRole("button", { name: /VEH044 trip 1/ })).toBeVisible();
  // 31.5 of 33.4 m³: drawn as tight, so the dispatcher sees there is no room left.
  await expect(page.getByRole("meter", { name: "Volume used" })).toHaveAttribute("aria-valuenow", "94");

  await page.getByRole("tab", { name: /Publish/ }).click();
  await page.getByRole("button", { name: "Publish plan" }).click();
  expect(desk.commands).toHaveLength(2);
  await page.getByRole("button", { name: "Confirm publish" }).click();
  await expect(page.getByRole("status").filter({ hasText: "is published" })).toBeVisible();
  expect(desk.commands[2]).toMatchObject({ kind: "plan:Publish", expectedVersion: 1, payload: { planId: "plan-v2" } });
  // The published plan is read only until it is revised.
  await expect(page.getByRole("button", { name: "Take off" })).toHaveCount(0);
});

test("a refused publish shows every reason and leaves the draft a draft", async ({ page }) => {
  const desk = await serve(page, { draft: draftPlan() });
  desk.refuse = {
    kind: "plan:Publish",
    status: 409,
    code: "CONSTRAINT_VIOLATED",
    detail: "plan plan-v1 cannot be published: PLN-07 demand an order was confirmed after this draft was made; PLN-14 reference the reference data changed",
    rules: ["PLN-07", "PLN-14"],
  };
  await page.goto("/#/plan");
  await page.getByRole("tab", { name: /Publish/ }).click();
  await page.getByRole("button", { name: "Publish plan" }).click();
  await page.getByRole("button", { name: "Confirm publish" }).click();

  const refusal = page.getByRole("alert").filter({ hasText: "Publishing the plan was refused" });
  await expect(refusal).toContainText("an order was confirmed after this draft was made");
  await expect(refusal).toContainText("the reference data changed");
  await expect(refusal).toContainText("PLN-14");
  await expect(page.getByRole("tab", { name: /Publish/ })).toContainText("Not published yet");
});

test("an edit on a draft someone else already changed is refused and the screen moves to their version", async ({ page }) => {
  const desk = await serve(page, { draft: draftPlan() });
  await page.goto("/#/plan");
  const decision = page.getByRole("region", { name: "Decision", exact: true });
  await decision.getByRole("radio", { name: /VEH044 · Trip 1/ }).click();
  await decision.getByLabel("Why are you placing it by hand?").fill("Asked by the store");

  // Another dispatcher regenerates while this one is deciding.
  desk.draft = draftPlan(2);
  desk.refuse = { kind: "plan:Override", status: 409, code: "VERSION_CONFLICT", detail: "plan plan-v1 changed since version 1 was read; the current draft is plan-v2 (plan version 2, row version 1)" };
  await decision.getByRole("button", { name: "Place on VEH044 trip 1" }).click();

  const refusal = page.getByRole("alert").filter({ hasText: "Someone else changed this first" });
  await expect(refusal).toContainText("the current draft is plan-v2");
  await expect(page.getByText("Draft version 2")).toBeVisible();
  // Nothing was merged: the order is still to be decided, on the version that is current.
  await expect(page.getByRole("region", { name: "Decision", exact: true }).getByRole("heading", { name: "ORD0092303" })).toBeVisible();
});

test("offline, the plan can be read and nothing can be changed", async ({ page, context }) => {
  await serve(page, { draft: draftPlan() });
  await page.goto("/#/plan");
  await expect(page.getByRole("region", { name: "Decision", exact: true })).toBeVisible();
  await context.setOffline(true);
  await expect(page.getByText("You are offline. The dispatcher screens are read only.")).toBeVisible();
  await expect(page.getByRole("button", { name: "Regenerate" })).toBeDisabled();
  await expect(page.getByRole("region", { name: "Decision", exact: true }).getByRole("radio")).toHaveCount(0);
  await page.getByRole("tab", { name: /Publish/ }).click();
  await expect(page.getByRole("button", { name: "Publish plan" })).toBeDisabled();
});

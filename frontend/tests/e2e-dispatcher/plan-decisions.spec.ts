import { expect, test } from "@playwright/test";
import { DEPOT, FEASIBLE_PREVIEW, draftPlan, serve, snapshotOf } from "./mocks.ts";

// The decisions beyond placing: keep orders deferred and be allowed to publish,
// swap two orders, hold an order and regenerate around it, save and compare
// plans, fix a trip's stop order, and tell a store its order is too big.

test("publishing waits for a decision on every deferred order, and keeping them deferred opens it", async ({ page }) => {
  const desk = await serve(page, { draft: draftPlan() });
  await page.goto("/#/plan");
  await page.getByRole("tab", { name: /Publish/ }).click();

  await expect(page.getByText("Publishing is blocked: 1 order still needs a decision")).toBeVisible();
  await expect(page.getByRole("button", { name: "Publish plan" })).toBeDisabled();

  await page.getByRole("button", { name: "Decide now" }).click();
  const decision = page.getByRole("region", { name: "Decision", exact: true });
  await expect(decision.getByRole("heading", { name: "ORD0092303" })).toBeVisible();
  await page.getByRole("button", { name: "Keep the rest deferred" }).click();
  const list = page.getByRole("region", { name: "Orders needing a decision" });
  await list.getByRole("button", { name: "Outlet asked to skip" }).click();
  await list.getByRole("button", { name: "Keep them deferred" }).click();

  expect(desk.commands[0]).toMatchObject({
    kind: "plan:KeepDeferred",
    expectedVersion: 1,
    payload: { planId: "plan-v1", orderIds: ["order-3"], reason: "Outlet asked to skip" },
  });
  await expect(page.getByText("1 of 1 decided")).toBeVisible();
  await page.getByRole("tab", { name: /Publish/ }).click();
  await expect(page.getByRole("button", { name: "Publish plan" })).toBeEnabled();
});

test("a swap is previewed on the trip it would leave and sent only when the server says it fits", async ({ page }) => {
  const desk = await serve(page, { draft: draftPlan() });
  await page.goto("/#/plan");
  await page.getByRole("button", { name: "Open swap window" }).click();

  const window = page.getByRole("dialog", { name: "Swap window" });
  await expect(window).toContainText("Swap in ORD0092303");
  await window.getByRole("button", { name: /ORD0092301/ }).click();
  await expect(window.getByRole("status")).toContainText("VEH043 Trip 1 can take it.");
  await expect(window.getByRole("button", { name: "Approve swap" })).toBeDisabled();
  await window.getByLabel("Why are you swapping them?").fill("The outlet asked for the earlier delivery");
  await window.getByRole("button", { name: "Approve swap" }).click();

  expect(desk.commands[0]).toMatchObject({
    kind: "plan:Swap",
    expectedVersion: 1,
    payload: { planId: "plan-v1", outOrderId: "order-1", inOrderId: "order-3", reason: "The outlet asked for the earlier delivery" },
  });
  await expect(page.getByRole("status").filter({ hasText: "Swapped. Draft version 2" })).toBeVisible();
});

test("a swap that breaks a rule says which and cannot be approved", async ({ page }) => {
  const desk = await serve(page, { draft: draftPlan() });
  desk.preview = { ...FEASIBLE_PREVIEW, feasible: false, checks: [{ ruleId: "R-PLN-02", passed: false, reason: "chilled order on an ambient vehicle", slack: null }] };
  await page.goto("/#/plan");
  await page.getByRole("button", { name: "Open swap window" }).click();

  const window = page.getByRole("dialog", { name: "Swap window" });
  await window.getByRole("button", { name: /ORD0092301/ }).click();
  await expect(window.getByRole("status")).toContainText("It cannot swap in: Needs a refrigerated vehicle.");
  await window.getByLabel("Why are you swapping them?").fill("Trying anyway");
  await expect(window.getByRole("button", { name: "Approve swap" })).toBeDisabled();
  await expect(window).not.toContainText("R-PLN-02");
});

test("an order placed by hand can be locked, and a regenerate can keep the decisions", async ({ page }) => {
  const desk = await serve(page, { draft: draftPlan() });
  await page.goto("/#/plan");
  const decision = page.getByRole("region", { name: "Decision", exact: true });
  await decision.getByRole("radio", { name: /VEH044 · Trip 1/ }).click();
  await decision.getByLabel("Why are you deciding this by hand?").fill("Outlet has waited two days");
  await decision.getByRole("button", { name: "Place on VEH044 trip 1" }).click();

  await expect(decision.getByText("Placed on VEH044 Trip 1 by hand")).toBeVisible();
  await decision.getByRole("switch", { name: "Lock" }).click();
  expect(desk.commands[1]).toMatchObject({ kind: "plan:Lock", expectedVersion: 1, payload: { planId: "plan-v2", orderId: "order-3" } });

  await page.getByRole("button", { name: "Regenerate" }).click();
  await page.getByRole("menuitem", { name: /Keep my decisions/ }).click();
  expect(desk.commands[2]).toMatchObject({ kind: "plan:Generate", expectedVersion: null, payload: { depotCode: DEPOT, keepDecisions: true } });
  await expect(page.getByRole("status").filter({ hasText: "Planned again, keeping your decisions." })).toBeVisible();
});

test("a plan can be saved, looked at read only, compared and returned to", async ({ page }) => {
  const desk = await serve(page, { draft: draftPlan(2, true) });
  const early = snapshotOf(1, "Auto plan", "AUTO");
  desk.snapshots = [early];
  desk.savedPlans[early.snapshotId] = draftPlan(1);
  await page.goto("/#/plan");

  await page.getByRole("button", { name: "Save snapshot" }).click();
  expect(desk.commands[0]).toMatchObject({ kind: "plan:SaveSnapshot", expectedVersion: 1, payload: { planId: "plan-v2" } });
  await expect(page.getByRole("status").filter({ hasText: "Snapshot saved." })).toBeVisible();

  await page.getByRole("button", { name: /^PLAN/i }).click();
  await page.getByRole("menuitem", { name: /Auto plan/ }).click();
  await expect(page.getByText("Auto plan: read only")).toBeVisible();
  await expect(page.getByRole("button", { name: "Save snapshot" })).toBeDisabled();
  await page.getByRole("button", { name: /Back to working draft/ }).click();

  await page.getByRole("button", { name: "Compare" }).click();
  await expect(page.getByRole("heading", { name: "Compare plans" })).toBeVisible();
  await expect(page.getByText("1 order differs")).toBeVisible();
  await expect(page.getByText("not in the plan").or(page.getByText("deferred to VEH044"))).toBeVisible();
  await page.getByRole("button", { name: "Use plan A" }).click();
  expect(desk.commands[1]).toMatchObject({ kind: "plan:RestoreSnapshot", expectedVersion: 1, payload: { snapshotId: early.snapshotId } });
});

test("a stop order that breaks a rule shows the rule in words and cannot be saved", async ({ page }) => {
  const desk = await serve(page, { draft: draftPlan(1, true) });
  desk.preview = {
    ...FEASIBLE_PREVIEW,
    feasible: false,
    checks: [{ ruleId: "R-PLN-13", passed: false, reason: "reaches OUT052 at 08:20, after its window closes at 08:00", slack: null }],
  };
  await page.goto("/#/plan");
  await page.getByRole("tab", { name: /View plan/ }).click();
  await page.getByRole("button", { name: "Edit this trip" }).click();

  await page.getByRole("button", { name: /Move OUT052 earlier/ }).click();
  await expect(page.getByText("This order does not work: Delivery window missed.")).toBeVisible();
  await page.getByLabel("Why change the stop order?").fill("The store asked");
  await expect(page.getByRole("button", { name: "Save stop order" })).toBeDisabled();

  desk.preview = FEASIBLE_PREVIEW;
  // Back to the order it had, then to the new one again, so the server is asked once more.
  await page.getByRole("button", { name: /Move OUT052 later/ }).click();
  await page.getByRole("button", { name: /Move OUT052 earlier/ }).click();
  await expect(page.getByText("Every stop is still on time.")).toBeVisible();
  await page.getByRole("button", { name: "Save stop order" }).click();
  expect(desk.commands[0]).toMatchObject({ kind: "plan:ReorderStops", expectedVersion: 1, payload: { planId: "plan-v1", tripId: "trip-VEH043-1" } });
});

test("an order too big for any vehicle is drawn apart and the store manager can be told", async ({ page }) => {
  const plan = draftPlan();
  const desk = await serve(page, {
    draft: { ...plan, allocations: plan.allocations.map((a) => (a.orderId === "order-3" ? { ...a, decision: "UNSERVABLE" as const } : a)) },
  });
  await page.goto("/#/plan");

  const card = page.getByRole("region", { name: "Too big for any vehicle" });
  await expect(card).toContainText("ORD0092303");
  await expect(card).toContainText("largest vehicle 33 m³");
  await card.getByRole("button", { name: "Contact store manager" }).click();
  await expect(card.getByLabel("Message to the store manager")).toHaveValue(/ORD0092303 is bigger than any vehicle/);
  await card.getByRole("button", { name: "Send message" }).click();

  expect(desk.commands[0]).toMatchObject({ kind: "plan:ContactStore", expectedVersion: null, payload: { planId: "plan-v1", orderId: "order-3" } });
  await expect(card.getByText("Store manager contacted")).toBeVisible();
});

test("a deferred order on the board opens its decision", async ({ page }) => {
  await serve(page, { draft: draftPlan() });
  await page.goto("/#/plan");
  await page.getByRole("tab", { name: /View plan/ }).click();

  const column = page.getByRole("region", { name: "Deferred orders" });
  await expect(column).toContainText("1 order");
  await expect(column.getByText("No room on the vehicle")).toBeVisible();
  await expect(column).toContainText("first on Tue");
  await column.getByRole("button").first().click();
  await expect(page.getByRole("region", { name: "Decision", exact: true }).getByRole("heading", { name: "ORD0092303" })).toBeVisible();
});

test("the board marks a tight trip, filters by temperature and labels a free trip by what could go there", async ({ page }) => {
  await serve(page, { draft: draftPlan(1, true) });
  await page.goto("/#/plan");
  await page.getByRole("tab", { name: /View plan/ }).click();

  await expect(page.getByText("Free · refrigerated").first()).toBeVisible();
  await expect(page.getByText("Late risk")).toBeVisible();
  await expect(page.getByText("A draft is scored once it is published")).toBeVisible();
  await page.getByRole("button", { name: "Filter" }).click();
  await page.getByRole("menuitem", { name: "Ambient" }).click();
  await expect(page.getByText("No trip matches this filter.")).toBeVisible();
  await page.getByRole("button", { name: "Clear" }).click();
  await expect(page.getByRole("button", { name: /VEH043 trip 1/ })).toBeVisible();
});

test("an optimised draft says what it saved and opens Compare on the rules plan made beside it", async ({ page }) => {
  // Planning v2 (R-PLN-38, PLN-37): the cost stage replaced the rules plan; the rules plan is a RULES snapshot.
  const draft = {
    ...draftPlan(),
    engine: "priority-insertion-v1+scarce-replan-v1+cost-alns-v1",
    cost: {
      trigger: "DEFERRALS" as const, improved: true, rulesVehicles: 16, rulesTrips: 32, rulesLitres: "724.8",
      vehicles: 13, trips: 25, litres: "610.6", iterations: 2000, stoppedBy: "NONE" as const,
    },
  };
  await serve(page, { draft, snapshots: [snapshotOf(1, "Auto plan", "AUTO"), snapshotOf(2, "Rules plan", "RULES")] });
  await page.goto("/#/plan");
  const note = page.getByText("Optimised: 13 vehicles, 25 trips, 611 L");
  await expect(note).toBeVisible();
  await expect(page.getByText("Rules plan: 16 vehicles, 32 trips, 725 L. The same orders are served with 3 fewer vehicles and 114 L less fuel.")).toBeVisible();
  await page.getByRole("button", { name: "Compare with the rules plan" }).click();
  const compare = page.getByRole("region", { name: "Compare plans" });
  await expect(compare).toContainText("Rules plan");
});

import { expect, test } from "@playwright/test";
import { DEPOT, FEASIBLE_PREVIEW, draftPlan, serve, snapshotOf } from "./mocks.ts";

// The decisions beyond placing: keep orders deferred and be allowed to publish,
// swap two orders, hold an order and regenerate around it, save and compare
// plans, fix a trip's stop order, and tell a store its order is too big.

test("publishing waits for a decision on every deferred order, and keeping them deferred opens it", async ({ page }) => {
  const desk = await serve(page, { draft: draftPlan() });
  await page.goto("/#/plan");
  await page.getByRole("tab", { name: /Publish/ }).click();

  await expect(page.getByRole("heading", { name: "Publishing is blocked" })).toBeVisible();
  await expect(page.getByText("1 order still needs a decision")).toBeVisible();
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
  await expect(window).toContainText("Swap ORD0092303");
  await expect(window.getByRole("button", { name: "Accept changes" })).toBeDisabled();
  // The stop that gives way is dragged, or sent with its Defer button, to "Will be deferred".
  await window.getByRole("listitem").filter({ hasText: "OUT051" }).getByRole("button", { name: "Defer" }).click();
  await expect(window).toContainText("Every check passes");
  await expect(window).toContainText("NEW · added by you");
  await window.getByLabel("Reason for the store").fill("The outlet asked for the earlier delivery");
  await window.getByRole("button", { name: "Accept changes" }).click();

  expect(desk.commands[0]).toMatchObject({
    kind: "plan:Swap",
    expectedVersion: 1,
    payload: { planId: "plan-v1", outOrderId: "order-1", inOrderId: "order-3", reason: "The outlet asked for the earlier delivery" },
  });
  await expect(page.getByRole("status").filter({ hasText: "Swap approved. Draft version 2" })).toBeVisible();
});

test("a swap and a new stop order go to the server as one command", async ({ page }) => {
  const desk = await serve(page, { draft: draftPlan() });
  const stop = (sequence: number, orderId: string, outletId: string) => ({
    sequence, orderId, outletId, plannedArrival: "04:10:00", windowOpen: "03:00:00", windowClose: "08:00:00", serviceMinutes: "20",
  });
  desk.preview = { ...FEASIBLE_PREVIEW, stops: [stop(1, "order-2", "OUT052"), stop(2, "order-3", "OUT053")] };
  await page.goto("/#/plan");
  await page.getByRole("button", { name: "Open swap window" }).click();

  const window = page.getByRole("dialog", { name: "Swap window" });
  await window.getByRole("listitem").filter({ hasText: "OUT051" }).getByRole("button", { name: "Defer" }).click();
  // The AI order slot is there, and says plainly that nothing proposes one yet.
  await expect(window).toContainText("Not available yet: no service proposes a stop order");
  await expect(window.getByRole("button", { name: "Use AI order" })).toBeDisabled();

  await window.getByRole("button", { name: /Move .*OUT053.* earlier/ }).click();
  await expect(window.getByRole("button", { name: "Default order" })).toBeVisible();
  await expect(window).toContainText("your stop order goes with the swap");
  await window.getByRole("button", { name: "Accept changes" }).click();

  expect(desk.commands[0]).toMatchObject({
    kind: "plan:Swap",
    payload: { outOrderId: "order-1", inOrderId: "order-3", orderIds: ["order-3", "order-2"] },
  });
  await expect(page.getByRole("status").filter({ hasText: "Swap approved with your stop order." })).toBeVisible();
});

test("a swap that breaks a rule says which and cannot be approved", async ({ page }) => {
  const desk = await serve(page, { draft: draftPlan() });
  desk.preview = { ...FEASIBLE_PREVIEW, feasible: false, checks: [{ ruleId: "R-PLN-02", passed: false, reason: "chilled order on an ambient vehicle", slack: null }] };
  await page.goto("/#/plan");
  await page.getByRole("button", { name: "Open swap window" }).click();

  const window = page.getByRole("dialog", { name: "Swap window" });
  await window.getByRole("button", { name: "Use it" }).click();
  await expect(window).toContainText("A rule refuses this swap");
  await expect(window).toContainText("✕ Needs a refrigerated vehicle");
  await window.getByLabel("Reason for the store").fill("Trying anyway");
  await expect(window.getByRole("button", { name: "Accept changes" })).toBeDisabled();
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
  const differ = page.getByRole("region", { name: "Orders that differ" });
  await expect(differ).toContainText("1 order");
  await expect(differ).toContainText("ORD0092303");
  await expect(page.getByRole("region", { name: "Compare plans" }).getByText("Decision impact")).toBeVisible();
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
  const window = page.getByRole("dialog", { name: "Edit trip" });

  await window.getByRole("button", { name: /Move .*OUT052.* earlier/ }).click();
  await expect(window).toContainText("a rule refuses it");
  await expect(window).toContainText("✕ Delivery window missed");
  await window.getByLabel("Why this change?").fill("The store asked");
  await expect(window.getByRole("button", { name: "Accept changes" })).toBeDisabled();

  desk.preview = FEASIBLE_PREVIEW;
  // Back to the order it had, then to the new one again, so the server is asked once more.
  await window.getByRole("button", { name: /Move .*OUT052.* later/ }).click();
  await window.getByRole("button", { name: /Move .*OUT052.* earlier/ }).click();
  await expect(window).toContainText("Every check passes · new stop order");
  await window.getByRole("button", { name: "Accept changes" }).click();
  expect(desk.commands[0]).toMatchObject({
    kind: "plan:EditTrip",
    expectedVersion: 1,
    payload: { planId: "plan-v1", tripId: "trip-VEH043-1", orderIds: ["order-2", "order-1"] },
  });
});

test("a trip takes a deferred order by drag and drop, gives one up, and goes as one change", async ({ page }) => {
  const desk = await serve(page, { draft: draftPlan(1, true) });
  await page.goto("/#/plan");
  await page.getByRole("tab", { name: /View plan/ }).click();
  await page.getByRole("button", { name: "Edit this trip" }).click();
  const window = page.getByRole("dialog", { name: "Edit trip" });
  const stops = window.getByRole("list", { name: "Stops in order" });

  // The deferred order is dragged onto the trip, above the first stop.
  await window.getByRole("list", { name: "Deferred orders" }).getByRole("listitem").filter({ hasText: "OUT053" }).dragTo(stops.getByRole("listitem").first());
  await expect(stops.getByRole("listitem").first()).toContainText("NEW · added by you");
  // A stop is dragged out to "Will be deferred".
  await stops.getByRole("listitem").filter({ hasText: "OUT052" }).dragTo(window.getByText("Drag a stop here to defer it"));
  await expect(window.getByRole("region", { name: "Will be deferred" })).toContainText("OUT052");
  // Every rule passes, and the button says what it still waits for: the reason.
  await expect(window).toContainText("Every check passes · add a reason to accept");
  await expect(window.getByRole("button", { name: "Accept changes" })).toBeDisabled();
  await window.getByLabel("Why this change?").fill("The outlet waited two days");
  await expect(window).toContainText("1 added · 1 taken off");
  await window.getByRole("button", { name: "Accept changes" }).click();
  expect(desk.commands[0]).toMatchObject({ kind: "plan:EditTrip", payload: { tripId: "trip-VEH043-1", orderIds: ["order-3", "order-1"] } });
  await expect(page.getByRole("status").filter({ hasText: "Trip saved." })).toBeVisible();
});

test("removing a trip sends it empty, and a refusal comes back as a pop-up with its rule", async ({ page }) => {
  const desk = await serve(page, { draft: draftPlan(1, true) });
  desk.refuse = { kind: "plan:EditTrip", status: 409, code: "CONSTRAINT_VIOLATED", detail: "trip edit refused: R-PLN-07 too many trips", rules: ["R-PLN-07"] };
  await page.goto("/#/plan");
  await page.getByRole("tab", { name: /View plan/ }).click();
  await page.getByRole("button", { name: "Edit this trip" }).click();
  const window = page.getByRole("dialog", { name: "Edit trip" });
  await window.getByRole("button", { name: "Remove trip" }).click();
  await expect(window).toContainText("No stops: the trip is removed");
  await window.getByLabel("Why this change?").fill("Vehicle off the road");
  await window.getByRole("button", { name: "Accept changes" }).click();
  expect(desk.commands[0]).toMatchObject({ kind: "plan:EditTrip", payload: { tripId: "trip-VEH043-1", orderIds: [] } });

  const popup = page.getByRole("alert").filter({ hasText: "Changing the trip was refused" });
  await expect(popup).toContainText("R-PLN-07");
  await popup.getByRole("button", { name: "Close" }).click();
  await expect(popup).toHaveCount(0);
});

test("compare has a way back to the plan, and the step bar stays the same", async ({ page }) => {
  await serve(page, { draft: draftPlan(1, true) });
  await page.goto("/#/plan");
  await page.getByRole("button", { name: "Compare", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Compare plans" })).toBeVisible();
  await expect(page.getByRole("tablist", { name: "Plan steps" })).toBeVisible();
  await page.getByRole("button", { name: "Back to the plan" }).click();
  await expect(page.getByRole("heading", { name: "Compare plans" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Next: publish" })).toBeVisible();
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

test("a deferred order on the board opens its card, and from it its decision", async ({ page }) => {
  await serve(page, { draft: draftPlan() });
  await page.goto("/#/plan");
  await page.getByRole("tab", { name: /View plan/ }).click();

  const column = page.getByRole("region", { name: "Deferred orders" });
  await expect(column).toContainText("1 order");
  await expect(column.getByText("No room on the vehicle")).toBeVisible();
  await expect(column).toContainText("first on Tue");
  await column.getByRole("button").first().click();
  const card = page.getByRole("dialog", { name: "Deferred order ORD0092303" });
  await expect(card).toContainText("Deferred · needs a decision");
  await expect(card).toContainText("No room on the vehicle");
  await card.getByRole("button", { name: "Open in Decide" }).click();
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


test("a blocked publish names its blocker beside the button, and Decide warns in the step bar", async ({ page }) => {
  await serve(page, { draft: draftPlan() });
  await page.goto("/#/plan");
  await page.getByRole("tab", { name: /Publish/ }).click();
  await expect(page.getByRole("button", { name: "Publish plan" })).toBeDisabled();
  await expect(page.getByText("1 order needs a decision in Decide")).toBeVisible();
  await expect(page.getByRole("tab", { name: /Decide/ })).toContainText("1 need a decision");
});

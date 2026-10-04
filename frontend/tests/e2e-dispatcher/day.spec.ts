import { expect, test } from "@playwright/test";
import { DEPOT, dockTrip, draftPlan, order, serve, stop } from "./mocks.ts";

test("the order board follows each order from due to confirmed, and says where it rides", async ({ page }) => {
  const published = { ...draftPlan(), status: "PUBLISHED" as const, publishedAt: "2027-02-28T20:00:00Z" };
  await serve(page, {
    published,
    orders: [order(1, "IN_TRANSIT"), order(2, "RECEIVED"), order(3, "DEFERRED", { deferralCount: 2 }), order(4, "STOCK_UNKNOWN"), order(5, "CANCELLED")],
  });
  await page.goto("/#/orders");

  const flow = page.getByRole("region", { name: "Order flow" });
  await expect(flow).toContainText("Planned2");
  await expect(flow).toContainText("Confirmed by store1");
  await expect(page.getByText("1 order has no stock answer from the warehouse")).toBeVisible();

  const table = page.getByRole("table", { name: "Orders due" });
  const first = table.getByRole("row").filter({ hasText: "ORD0092301" });
  await expect(first).toContainText("VEH043 · T1");
  await expect(first).toContainText("On the road");
  const third = table.getByRole("row").filter({ hasText: "ORD0092303" });
  await expect(third).toContainText("Deferred");
  await expect(third).toContainText("deferred 2×");

  // A row opens the order with its day as a timeline (Figma 03d).
  await first.click();
  const drawer = page.getByRole("dialog", { name: "Order ORD0092301" });
  await expect(drawer).toContainText("Timeline");
  await expect(drawer).toContainText("Planned");
  // Focus moves into the drawer, and Escape gives it back.
  await expect(drawer.getByRole("button", { name: "Close" })).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(drawer).toHaveCount(0);

  await page.getByRole("button", { name: "All statuses" }).click();
  await page.getByRole("menuitem", { name: "Need attention" }).click();
  await expect(table.getByRole("row")).toHaveCount(3);
  await page.getByLabel("Search orders").fill("2304");
  await expect(table.getByRole("row")).toHaveCount(2);
});

test("closing orders before the cutoff is refused with the rule", async ({ page }) => {
  const desk = await serve(page);
  desk.refuse = { kind: "order:CloseForDay", status: 409, code: "CONSTRAINT_VIOLATED", detail: "The cutoff for this day has not passed", rules: ["R-ORD-01"] };
  await page.goto("/#/orders");
  // Closing a day lives on that day's card under Upcoming (Figma 03b).
  await page.getByRole("radio", { name: /Upcoming/ }).click();
  const close = () => page.getByRole("button", { name: "Close orders" }).first().click().then(() => page.getByRole("menuitem", { name: `Close ${DEPOT}` }).click());
  await close();
  const refusal = page.getByRole("alert").filter({ hasText: "Closing orders was refused" });
  await expect(refusal).toContainText("The cutoff for this day has not passed");
  await expect(refusal).toContainText("R-ORD-01");

  await close();
  await expect(page.getByRole("status").filter({ hasText: "Orders closed" })).toBeVisible();
  expect(desk.commands.at(-1)).toMatchObject({ kind: "order:CloseForDay", payload: { depotCode: DEPOT } });
});

test("live lists vehicles most urgent first and what needs the dispatcher", async ({ page }) => {
  await serve(page, {
    sheets: [
      { vehicleId: "VEH043", serviceDate: "2027-03-01", stops: [stop(1, { outcome: "DELIVERED", proofCaptured: true }), stop(2, { startedAt: "2027-03-01T03:00:00Z" })] },
      { vehicleId: "VEH044", serviceDate: "2027-03-01", stops: [stop(3, { outcome: "FAILED" }), stop(4, { outcome: "DELIVERED" })] },
    ],
    dock: [dockTrip("VEH045", "IN_PROGRESS")],
  });
  await page.goto("/#/live");

  const board = page.getByRole("region", { name: "Trip board" });
  await expect(board.getByRole("button").first()).toContainText("VEH044");
  await expect(board.getByRole("button", { name: "VEH044, Returning, 2 of 2 stops done" })).toBeVisible();

  const needs = page.getByRole("region", { name: "Needs you" });
  await expect(needs.getByRole("listitem").nth(0)).toContainText("OUT053 · not delivered");
  await expect(needs.getByRole("listitem").nth(1)).toContainText("proof of delivery owed");
  await expect(page.getByText("1 at the dock")).toBeVisible();

  await needs.getByRole("listitem").nth(0).getByRole("button", { name: "Open trip" }).click();
  await expect(page.getByRole("heading", { name: "VEH044" })).toBeVisible();
  await expect(page.getByRole("row", { name: /OUT053/ })).toContainText("Not delivered");
});


test("the overview leads into tomorrow's plan, and an order opens its day in Plan", async ({ page }) => {
  await serve(page, { draft: draftPlan() });
  await page.goto("/#/overview");
  const card = page.getByRole("region", { name: "Tomorrow's plan" });
  await expect(card).toContainText("Kandy");
  await expect(card).toContainText("1 needs a decision");
  await card.getByRole("button", { name: "Open plan" }).click();
  await expect(page).toHaveURL(/#\/plan/);

  await page.goto("/#/orders");
  await page.getByRole("table", { name: "Orders due" }).getByRole("row").filter({ hasText: "ORD0092303" }).click();
  await page.getByRole("dialog", { name: "Order ORD0092303" }).getByRole("button", { name: /in Plan|on the plan/ }).click();
  await expect(page).toHaveURL(/#\/plan/);
});

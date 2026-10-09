import { expect, test } from "@playwright/test";
import { draftPlan, serve } from "./mocks.ts";

// Issue #119: late risk per trip and per stop on a published plan, from the time
// predictor's scoring; an estimate says so, and a draft is never shown as 0%.

const published = () => ({ ...draftPlan(1), status: "PUBLISHED" as const, publishedAt: "2027-02-28T17:00:00Z" });

const scoring = (degraded: boolean) => ({
  scoring: { planId: "plan-v1", depotCode: "Kandy", serviceDate: "2027-03-01", status: degraded ? "DEGRADED" : "SCORED", modelLabel: degraded ? null : "risk@1", roadConditions: "used", reason: null, scoredAt: "2027-02-28T17:00:30Z" },
  stops: [
    { orderId: "order-1", tripId: "trip-VEH043-1", sequence: 1, outletId: "OUT051", serviceMinutes: "20", lateProbability: "0.12", modelLabel: "risk@1", degraded },
    { orderId: "order-2", tripId: "trip-VEH043-1", sequence: 2, outletId: "OUT052", serviceMinutes: "20", lateProbability: "0.41", modelLabel: "risk@1", degraded,
      reasons: [{ code: "TIGHT_WINDOW", value: 14, of: 0 }, { code: "OUTLET_HISTORY", value: 3, of: 6 }] },
  ],
});

async function viewPlan(page: import("@playwright/test").Page) {
  await page.goto("/#/plan");
  await page.getByRole("tab", { name: /View plan/ }).click();
}

test("a scored published plan tags its risky trip on the board and every stop on the trip", async ({ page }) => {
  await serve(page, { published: published(), predictions: scoring(false) });
  await viewPlan(page);

  await expect(page.getByText("1 high")).toBeVisible();
  const board = page.getByRole("region", { name: "Trips by vehicle" });
  await expect(board.getByText("Late risk 41%", { exact: true })).toBeVisible();

  await board.getByRole("button", { name: /trip 1:/ }).first().click();
  const stops = page.getByRole("list", { name: "Stops in order" });
  await expect(stops.getByText("Late 12%", { exact: true })).toBeVisible();
  await expect(stops.getByText("Late 41%", { exact: true })).toBeVisible();
  // MF-08: a risky stop says why, in plain words; a quiet one says nothing more.
  await expect(stops.getByTestId("risk-reasons")).toHaveText("Why: only 14 min before the window closes · late on 3 of its last 6 visits");
  await expect(stops.getByTestId("risk-reasons")).toHaveCount(1);
});

test("when the time predictor was not running the risk reads as an estimate", async ({ page }) => {
  await serve(page, { published: published(), predictions: scoring(true) });
  await viewPlan(page);

  await expect(page.getByText("0 low · estimated")).toBeVisible();
  await expect(page.getByRole("region", { name: "Trips by vehicle" }).getByText("Late risk 41% · estimate")).toBeVisible();
  await page.getByRole("region", { name: "Trips by vehicle" }).getByRole("button", { name: /trip 1:/ }).first().click();
  await expect(page.getByRole("list", { name: "Stops in order" }).getByText("Late 12% · estimate")).toBeVisible();
});

// Roadmap MF-07: a plan past the last day road conditions are known says so; one inside it says nothing.
test("a plan past the last known road conditions says how far they go", async ({ page }) => {
  await serve(page, { published: published(), predictions: { ...scoring(false), roadConditionsUntil: "2026-06-28" } });
  await viewPlan(page);
  await expect(page.getByText("0 low · road data ends Sun 28 Jun")).toBeVisible();
});

test("a plan within the known road conditions does not mention them", async ({ page }) => {
  await serve(page, { published: published(), predictions: { ...scoring(false), roadConditionsUntil: "2027-06-30" } });
  await viewPlan(page);
  await expect(page.getByText("0 low", { exact: true })).toBeVisible();
  await expect(page.getByText(/road data/)).toHaveCount(0);
});

test("a draft is not scored and shows no risk figures, never 0%", async ({ page }) => {
  await serve(page, { draft: draftPlan(1) });
  await viewPlan(page);

  await expect(page.getByText("Tight windows")).toBeVisible();
  await expect(page.getByText(/^Late( risk)? \d+%/)).toHaveCount(0);
});

// Roadmap T1-01: before publishing, the minutes each stop has before its window
// closes, read from the plan's own times, on the board, the trip and the Publish step.
test("a draft names the stops with little time before their window closes, before it is published", async ({ page }) => {
  const draft = draftPlan(1);
  const stops = draft.trips[0]!.stops;
  stops[0] = { ...stops[0]!, windowClose: "04:20:00" };
  stops[1] = { ...stops[1]!, windowClose: "05:35:00" };
  await serve(page, { draft });
  await viewPlan(page);

  await expect(page.getByText("1 trip", { exact: true })).toBeVisible();
  const board = page.getByRole("region", { name: "Trips by vehicle" });
  await expect(board.getByText("10 min to spare")).toBeVisible();
  await board.getByRole("button", { name: /trip 1:/ }).first().click();
  const list = page.getByRole("list", { name: "Stops in order" });
  await expect(list.getByText("10 min to spare")).toBeVisible();
  await expect(list.getByText("25 min to spare")).toBeVisible();

  await page.keyboard.press("Escape");
  await page.getByRole("tab", { name: /Publish/ }).click();
  await expect(page.getByText("2 stops have under 30 min before the window closes")).toBeVisible();
  await page.getByRole("button", { name: "Show ›" }).click();
  const tight = page.getByRole("listitem", { name: "Tight stops" });
  await expect(tight.getByText("OUT051")).toBeVisible();
  await expect(tight.getByText(/arrives 04:10, closes 04:20/)).toBeVisible();
});

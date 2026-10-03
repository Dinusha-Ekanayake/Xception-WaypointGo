import { expect, test } from "@playwright/test";
import { draftPlan, serve } from "./mocks.ts";

// Issue #119: late risk per trip and per stop on a published plan, from the time
// predictor's scoring; an estimate says so, and a draft is never shown as 0%.

const published = () => ({ ...draftPlan(1), status: "PUBLISHED" as const, publishedAt: "2027-02-28T17:00:00Z" });

const scoring = (degraded: boolean) => ({
  scoring: { planId: "plan-v1", depotCode: "Kandy", serviceDate: "2027-03-01", status: degraded ? "DEGRADED" : "SCORED", modelLabel: degraded ? null : "risk@1", roadConditions: "used", reason: null, scoredAt: "2027-02-28T17:00:30Z" },
  stops: [
    { orderId: "order-1", tripId: "trip-VEH043-1", sequence: 1, outletId: "OUT051", serviceMinutes: "20", lateProbability: "0.12", modelLabel: "risk@1", degraded },
    { orderId: "order-2", tripId: "trip-VEH043-1", sequence: 2, outletId: "OUT052", serviceMinutes: "20", lateProbability: "0.41", modelLabel: "risk@1", degraded },
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

  const stops = page.getByRole("list", { name: "Stops in order" });
  await expect(stops.getByText("Late 12%", { exact: true })).toBeVisible();
  await expect(stops.getByText("Late 41%", { exact: true })).toBeVisible();
});

test("when the time predictor was not running the risk reads as an estimate", async ({ page }) => {
  await serve(page, { published: published(), predictions: scoring(true) });
  await viewPlan(page);

  await expect(page.getByText("0 low · estimated")).toBeVisible();
  await expect(page.getByRole("region", { name: "Trips by vehicle" }).getByText("Late risk 41% · estimate")).toBeVisible();
  await expect(page.getByRole("list", { name: "Stops in order" }).getByText("Late 12% · estimate")).toBeVisible();
});

test("a draft is not scored and shows no risk figures, never 0%", async ({ page }) => {
  await serve(page, { draft: draftPlan(1) });
  await viewPlan(page);

  await expect(page.getByText("A draft is scored once it is published")).toBeVisible();
  await expect(page.getByText(/^Late( risk)? \d+%/)).toHaveCount(0);
});

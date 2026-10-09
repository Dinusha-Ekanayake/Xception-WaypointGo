import { expect, test } from "@playwright/test";
import { draftPlan, serve } from "./mocks.ts";

// Roadmap T1-01: before Publish, the stops likely to be late, each kept or
// swapped with a reason. The figures are an estimate and it never blocks Publish.

const stop = (review: null | { choice: "KEEP" | "SWAP"; reason: string }) => ({
  orderId: "order-2", tripId: "trip-VEH043-1", vehicleId: "VEH043", sequence: 2, outletId: "OUT052", percent: 62,
  minutesToClose: -5, stopOfTrip: 2,
  review: review && { reviewId: "r1", planId: "plan-v1", orderId: "order-2", ...review, decidedBy: "d", decidedAt: "2027-02-28T16:00:00Z" },
});

test("a stop likely to be late is kept with a reason, and the review is recorded", async ({ page }) => {
  const desk = await serve(page, { draft: draftPlan(1) });
  let reviewed = false;
  await page.route("**/api/ml/plans/plan-v1/risk-review", (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ planId: "plan-v1", enabled: true, estimate: true, thresholdPercent: 35,
        stops: [stop(reviewed ? { choice: "KEEP", reason: "Store receives early" } : null)] }),
    }),
  );
  await page.goto("/#/plan");
  await page.getByRole("tab", { name: /Publish/ }).click();
  await expect(page.getByText("1 stop is likely to be late · 1 to review")).toBeVisible();
  await expect(page.getByText(/estimate: learning · publishing is not blocked/)).toBeVisible();
  await page.getByRole("button", { name: "Review ›" }).click();
  const risky = page.getByRole("listitem", { name: "Stops likely to be late" }).getByTestId("risk-stop");
  await expect(risky).toContainText("OUT052");
  await expect(risky).toContainText("VEH043 · planned 5 min after the window closes · stop 2 of the trip");
  await expect(risky).toContainText("62%");

  await risky.getByRole("button", { name: "Keep", exact: true }).click();
  await expect(risky.getByRole("button", { name: "Keep this stop" })).toBeDisabled();
  await risky.getByRole("textbox").fill("Store receives early");
  reviewed = true;
  await risky.getByRole("button", { name: "Keep this stop" }).click();
  await expect.poll(() => desk.commands.find((c) => c.kind === "ml:RecordRiskReview")?.payload).toEqual({
    planId: "plan-v1", orderId: "order-2", choice: "KEEP", reason: "Store receives early",
  });
  await expect(risky).toContainText("Kept · Store receives early");
  await expect(page.getByText("1 stop is likely to be late · all reviewed")).toBeVisible();
});

test("with no stop at risk, or the check down, Publish is not blocked", async ({ page }) => {
  await serve(page, { draft: draftPlan(1) });
  await page.route("**/api/ml/plans/plan-v1/risk-review", (route) =>
    route.fulfill({ status: 503, contentType: "application/problem+json", body: JSON.stringify({ title: "Unavailable", status: 503 }) }),
  );
  await page.goto("/#/plan");
  await page.getByRole("tab", { name: /Publish/ }).click();
  await expect(page.getByText("Late risk could not be checked")).toBeVisible();
});

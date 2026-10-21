import { expect, test } from "@playwright/test";
import { draftPlan, serve } from "./mocks.ts";

// R-FLT-06 (docs/issues/vehicle-broken/PLAN.md): a vehicle out of use after the
// plan was published. Planning has drafted a revision; the Plan screen says why,
// and the dispatcher checks it and sends the update.

test("the Plan screen says a vehicle went out of use and a new draft is ready", async ({ page }) => {
  const published = { ...draftPlan(1), status: "PUBLISHED" as const, publishedAt: "2027-02-28T16:50:00Z" };
  const draft = { ...draftPlan(2), planId: "plan-v2", supersedes: published.planId, revisionReason: "VEH043 is unavailable: broken down, reported by the driver" };
  await serve(page, { published, draft });
  await page.goto("/#/plan");
  const note = page.getByRole("status").filter({ hasText: "VEH043 out of use: a new plan draft is ready" });
  await expect(note).toBeVisible();
  await expect(note).toContainText("Broken down, reported by the driver.");
  await expect(note).toContainText("send the update");
});

test("a revision for any other reason shows no vehicle note", async ({ page }) => {
  const published = { ...draftPlan(1), status: "PUBLISHED" as const, publishedAt: "2027-02-28T16:50:00Z" };
  const draft = { ...draftPlan(2), planId: "plan-v2", supersedes: published.planId, revisionReason: "store asked for a later slot" };
  await serve(page, { published, draft });
  await page.goto("/#/plan");
  await expect(page.getByText(/revises the published plan|Update not sent/).first()).toBeVisible();
  await expect(page.getByText(/out of use: a new plan draft is ready/)).toHaveCount(0);
});
